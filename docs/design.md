# Paper插件设计文档：真实地图(Arnis)驱动的"无限扩展世界"

## 0. 目标与非目标

**目标**
- 玩家(或管理员)设置一个GPS坐标(lat/lon)作为锚点
- 玩家出生的区域按真实地图(通过Arnis生成)渲染
- 玩家持续探索时,前方未生成的区域会"无缝"地按真实地图持续扩展,玩家不会感觉到"卡顿等生成"或"边界突然变成原版地形"

**非目标(v1不做,但设计上留好扩展空间)**
- 不做"真正逐chunk实时调用Overpass"——已论证过,延迟和限流都不现实
- v1先做"单一全局锚点"(整个世界对应同一个真实地点,所有玩家共享同一张地图,随着任何人探索而扩展),不做"每个玩家各自一个GPS锚点的独立世界"。多锚点是后续扩展方向,本文档第8节会说明怎么加。

---

## 1. 核心矛盾与对应解法(必须先理解,否则实现时会到处踩坑)

| 矛盾 | 说明 | 解法 |
|---|---|---|
| Arnis是"一次性批处理",不是"实时查询服务" | 它的最小工作单元是一整个bbox,不是单个chunk | 用**Tile(区域块)**而不是chunk做生成粒度,异步预生成,提前于玩家到达 |
| Arnis永远把生成结果的左下角放在世界坐标(0,0,0) | 每次单独调用Arnis,内部坐标都从0,0,0起算,跟你的"全局世界坐标"是两套坐标系 | 每次调用用**独立的临时staging世界**接收输出,再做**坐标平移**搬运进真正的活世界 |
| Bukkit的chunk一旦"被生成过"就不会自动重新生成 | 如果玩家比你的预生成快一步先触发了chunk加载,原版/占位生成器会先把这个chunk定型,之后很难再覆盖回真实地图数据 | 活世界用**纯void/占位生成器**兜底,正常路径下预生成必须始终跑在玩家前面,并把"安全缓冲距离"设得比"最大可能移动速度 × 单tile生成耗时"更大 |
| 写方块只能在主线程做 | Arnis子进程、Overpass网络请求都是慢操作,不能堵主线程；但把方块数据写进世界又必须回到主线程 | 异步线程池跑生成+IO,主线程**分tick批量**写入方块,避免一次性卡顿 |
| 相邻Tile是独立生成的,边界处建筑/道路可能不连续 | Arnis的floodfill/道路连接算法依赖bbox内的完整上下文,bbox边缘被切断的道路/建筑可能两侧Tile渲染不一致 | Tile之间留**重叠边距**,生成时多算一圈,合并时只取核心区域,丢弃重叠部分;接受少量道路接缝作为v1已知限制 |

---

## 2. 总体架构

```
                         ┌─────────────────────────┐
                         │   PaperMC 活世界 (Live)  │  ← 玩家实际游玩的世界
                         │  ChunkGenerator = Void   │     (占位生成器,兜底)
                         └────────────▲─────────────┘
                                      │ 主线程,分tick批量写方块
                                      │
                         ┌────────────┴─────────────┐
                         │   TileMergeService        │  读取staging世界的
                         │   (主线程调度)              │  chunk数据,offset后
                         └────────────▲─────────────┘  写入活世界
                                      │
                         ┌────────────┴─────────────┐
                         │   TileGenerationWorker     │  异步线程池
                         │  (ProcessBuilder调Arnis)   │
                         └────────────▲─────────────┘
                                      │ bbox + 输出路径
                         ┌────────────┴─────────────┐
                         │   Arnis CLI (子进程)        │
                         │  下载OSM+地形 → 写.mca       │
                         └────────────▲─────────────┘
                                      │
                         ┌────────────┴─────────────┐
                         │  TileScheduler             │  根据玩家位置+移动方向
                         │  (周期性tick / 移动事件触发)  │  决定该预生成哪些Tile
                         └────────────────────────────┘
                                      ▲
                         ┌────────────┴─────────────┐
                         │  PlayerMoveListener         │
                         └────────────────────────────┘
```

---

## 3. 坐标系与映射

### 3.1 GPS锚点 ↔ 世界坐标

定义一次:
```
ANCHOR_LAT, ANCHOR_LON   // 锚点真实坐标,对应世界坐标 (0, 0)
SCALE_METERS_PER_BLOCK   // 必须和传给Arnis的scale factor换算一致
```

小范围(城市级)用平面近似即可,不需要复杂投影:

```
metersPerDegLat = 111320.0
metersPerDegLon = 111320.0 * cos(toRadians(ANCHOR_LAT))

// lat/lon -> 世界坐标(blockX, blockZ)
dx_meters = (lon - ANCHOR_LON) * metersPerDegLon
dz_meters = (lat - ANCHOR_LAT) * metersPerDegLat   // 注意lat增大方向对应你想要的Z方向,自行决定符号
blockX = dx_meters / SCALE_METERS_PER_BLOCK
blockZ = dz_meters / SCALE_METERS_PER_BLOCK

// 世界坐标 -> lat/lon (反向,用于给Arnis生成bbox)
lon = ANCHOR_LON + (blockX * SCALE_METERS_PER_BLOCK) / metersPerDegLon
lat = ANCHOR_LAT + (blockZ * SCALE_METERS_PER_BLOCK) / metersPerDegLat
```

把这套换算封装成一个独立的 `GeoCoordTransformer` 类,**先写单元测试**,用已知地标点验证往返转换误差在可接受范围内(几个block以内)。这是全系统最基础的一层,错了后面全错。

### 3.2 Tile网格

```
TILE_SIZE_CHUNKS = 8    // 一个Tile = 8x8个chunk = 128x128 block,具体数值按Arnis单次生成耗时调
TILE_OVERLAP_BLOCKS = 16 // 生成时四周多算一圈,合并时丢弃,缓解接缝
```

每个Tile有唯一坐标 `(tileX, tileZ)`,与世界坐标的关系:
```
tileOriginBlockX = tileX * TILE_SIZE_CHUNKS * 16
tileOriginBlockZ = tileZ * TILE_SIZE_CHUNKS * 16
```

Tile对应的bbox(给Arnis用,要把overlap也算进去,再用3.1的反向公式转成lat/lon):
```
bboxBlockMinX = tileOriginBlockX - TILE_OVERLAP_BLOCKS
bboxBlockMinZ = tileOriginBlockZ - TILE_OVERLAP_BLOCKS
bboxBlockMaxX = tileOriginBlockX + TILE_SIZE_CHUNKS*16 + TILE_OVERLAP_BLOCKS
bboxBlockMaxZ = tileOriginBlockZ + TILE_SIZE_CHUNKS*16 + TILE_OVERLAP_BLOCKS
```

---

## 4. Tile状态机与持久化

```
PENDING → QUEUED → GENERATING → STAGED → MERGING → MERGED
                                    │
                                    └→ FAILED (重试N次后) → FALLBACK_FLAT (生成一个平地兜底,避免永久空洞)
```

持久化到插件data目录下的SQLite(推荐,比YAML/JSON并发安全)或者简单的flat file:

```sql
CREATE TABLE tiles (
  tile_x INTEGER,
  tile_z INTEGER,
  status TEXT,
  retry_count INTEGER DEFAULT 0,
  updated_at INTEGER,
  PRIMARY KEY (tile_x, tile_z)
);
```

服务器重启后,`TileScheduler`启动时load这张表,所有非MERGED状态的Tile重新评估是否需要继续处理。

---

## 5. 关键模块设计

### 5.1 `GeoCoordTransformer`
- 职责:lat/lon ↔ block坐标互转(见3.1),纯函数,无状态,易测试。

### 5.2 `TileScheduler`(运行在主线程,定时tick,比如每1-2秒一次,不要用PlayerMoveEvent逐次触发——移动事件频率太高)
- 输入:所有在线玩家的当前位置 + 最近N秒的位置历史(用于估算移动方向/速度)
- 逻辑:
  1. 对每个玩家,算出当前所在Tile坐标
  2. 根据移动方向,优先把"玩家前方"的Tile(而不是均匀四周)加入待生成队列——省资源,且更贴合"往前走地图跟着展开"的体验
  3. 同时保留一个较小的全方向缓冲圈(玩家可能掉头)
  4. 把队列里PENDING的Tile丢给`TileGenerationWorker`,但限制**同时在跑的Arnis进程数**(建议1-2个,Overpass对并发请求也不友好)
  5. 如果某个玩家"即将进入"一个还没MERGED的Tile(预测撞线),触发兜底:见5.6

### 5.3 `TileGenerationWorker`(异步线程池,`ExecutorService`,不要用Bukkit的BukkitRunnable异步任务,因为这是阻塞IO+长耗时,用独立线程池更好控制)
- 输入:Tile坐标
- 步骤:
  1. 状态置为GENERATING,写DB
  2. 算出该Tile(含overlap)的bbox → lat/lon
  3. 创建一个干净的staging世界目录(每个Tile独立目录,避免互相污染;用完即删)
  4. `ProcessBuilder`调用Arnis CLI,大致形如:
     ```
     arnis --terrain --path=<staging_dir> --bbox="<minLat>,<minLon>,<maxLat>,<maxLon>" --scale=<...>
     ```
     具体参数名以你实际编译出来的Arnis版本`--help`输出为准,设计阶段先占位
  5. 等待进程结束,检查返回码:
     - 成功 → 状态置STAGED,通知`TileMergeService`
     - 失败/超时 → retry_count+1,指数退避重试;超过阈值 → 状态FAILED,排入"生成平地兜底"任务

### 5.4 `TileMergeService`(必须在主线程跑,因为要写方块)
- 输入:已STAGED的Tile
- 步骤:
  1. 把staging世界目录作为一个普通Bukkit `World` `load`进来(`WorldCreator`),不需要它能被玩家进入,只是借Bukkit的世界加载能力来读区块数据
  2. 对该Tile覆盖的每个chunk(注意要把overlap范围内多生成的部分**裁掉**,只取核心TILE_SIZE_CHUNKS×TILE_SIZE_CHUNKS区域):
     - 用 `stagingWorld.getChunkAt(localX, localZ).getChunkSnapshot()` 读出方块数据(`ChunkSnapshot`自带的`getBlockData(x,y,z)`不阻塞主线程的部分可以提前在异步阶段做快照读取——这点要查Paper当前API文档确认线程安全范围)
     - 计算坐标offset:`liveChunkX = tileOriginChunkX + localX`,`liveChunkZ = tileOriginChunkZ + localZ`
     - 把snapshot里的每个方块写入 `liveWorld.getBlockAt(...).setBlockData(...)`
  3. **分tick处理**:不要在一个tick里写完整个Tile,按"每tick处理N个chunk"的节奏用`BukkitScheduler.runTask`循环调度,避免主线程卡顿(具体N值要实测调,先给个起点,比如每tick处理1个chunk,128x128x(全高度)的方块量级,实测后再调整批大小)
  4. 写完后:状态置MERGED,卸载并删除staging世界目录,DB更新

### 5.5 占位ChunkGenerator(活世界的默认生成器,兜底用)
- 继承`ChunkGenerator`,所有`shouldGenerateXxx()`返回false
- `generateSurface`里只铺一层基岩(或者完全空气),保证玩家"万一跑到了还没merge的chunk"时,后果是掉进空气/站在基岩上,而不是生成一片永久原版地形把后续真实地图数据卡住

### 5.6 撞线兜底逻辑(玩家比生成快的边缘情况)
- `TileScheduler`检测到玩家即将进入一个非MERGED的Tile边界时:
  - 优先:短暂限制玩家继续前进(比如用一个临时的"看不见的墙"/`Vector`力场把玩家推回去几格,或者直接`setVelocity`抵消前进速度),同时给个提示`actionbar`消息"前方地图正在加载…",等对应Tile MERGED后解除限制
  - 这个限制窗口应该很短(正常情况下Tile早就该生成好了,触发兜底说明缓冲距离设小了,应该回头调大`TileScheduler`的预生成提前量,而不是依赖这个兜底常态运行)

### 5.7 命令
- `/realmap setanchor <lat> <lon> [scale]` —— 管理员设置/重设全局锚点(重设需谨慎,已生成区域不会自动跟着变)
- `/realmap status` —— 调试用,显示玩家附近Tile网格状态
- `/realmap teleport <lat> <lon>` —— 按真实坐标传送(如目标区域未MERGED,先触发生成,玩家原地等待提示直到完成或超时给反馈)
- `/realmap regen <tileX> <tileZ>` —— 手动强制重新生成某个Tile(调试/修复用)

---

## 6. 配置项(config.yml)

```yaml
anchor:
  lat: 0.0
  lon: 0.0
  scale_meters_per_block: 1.0   # 必须与传给Arnis的scale一致

tile:
  size_chunks: 8
  overlap_blocks: 16

scheduler:
  tick_interval_seconds: 2
  preload_radius_tiles: 2
  lookahead_tiles: 3          # 玩家前方预生成几格Tile
  buffer_tiles_omnidirectional: 1
  block_unloaded_tiles: true
  max_concurrent_generations: 1

arnis:
  binary_path: "/opt/arnis/arnis"
  staging_dir: "plugins/RealMap/staging"
  timeout_seconds: 300
  extra_args: []               # 比如禁用building interior生成等,提速

merge:
  chunks_per_tick: 1

retry:
  max_retries: 3
  backoff_seconds: 30
```

---

## 7. 实现里程碑建议(给Claude Code的拆解顺序)

1. **M1 - 坐标数学**:`GeoCoordTransformer` + 单元测试,先独立跑通,不接任何Minecraft逻辑
2. **M2 - Arnis子进程调用验证**:写一个最简单的Java程序,`ProcessBuilder`调Arnis对一个固定bbox生成到本地目录,确认输出的region文件能被验证读取
3. **M3 - 单Tile端到端**:手动指定一个Tile坐标,跑完整链路(生成→staging世界加载→读chunk→offset→写入活世界),肉眼在游戏里检查效果对不对、坐标有没有偏移
4. **M4 - 占位生成器+状态机**:把活世界的默认生成器换成void占位,接上Tile状态机和DB持久化
5. **M5 - 调度器**:接玩家位置监听,自动按移动方向预生成
6. **M6 - 边缘情况**:撞线兜底、失败重试、Tile接缝优化(overlap裁剪参数调优)
7. **M7 - 多人/多锚点**(如果v1之后要做):见第8节

---

## 8. 后续扩展:每个玩家独立GPS锚点

如果以后想要"每个玩家设置自己的GPS,互不影响",核心改动:
- 不再是"一个活世界 + 一个全局锚点",而是**每个玩家对应一个独立Bukkit World**(用类似Multiverse的per-player world管理),玩家之间不共享同一片真实地图坐标空间
- 锚点配置从全局config变成per-player存储(数据库一张`player_anchors`表)
- Tile状态机、staging流程不变,只是"活世界"从单例变成按需创建/卸载的per-player世界,注意服务器内存/磁盘开销会显著增加,需要做"长时间不在线玩家的世界卸载/归档"策略

---

## 10. 生产环境预加载策略

### 10.1 三阶段模型

```
┌─────────────────────────────────────────────────────────────┐
│ 阶段 A — 开服门控（一次性）                                    │
│   preload_mode: center → 只生成 Tile(0,0)                     │
│   Tile(0,0) MERGED 之前：AsyncPlayerPreLogin 踢出 + 重试提示   │
├─────────────────────────────────────────────────────────────┤
│ 阶段 B — 在线预加载（定时 tick，默认每 2s）                     │
│   对每个 realmap 世界中的玩家：                                │
│     1. 以玩家当前 tile 为中心，扫描 Chebyshev 半径 R 的正方形    │
│     2. 将未知 tile 写入 PENDING                               │
│     3. 在移动方向（无移动则用朝向）上，额外排队 lookahead 格     │
│   Worker 从 PENDING 取任务时：距离近 + 朝向前方的 tile 优先      │
├─────────────────────────────────────────────────────────────┤
│ 阶段 C — 移动撞线兜底（实时）                                  │
│   TileBoundaryGuard：未 MERGED 的 tile 禁止进入               │
│   碰到边界时 ensureQueued + ActionBar「前方地图正在加载…」       │
└─────────────────────────────────────────────────────────────┘
```

### 10.2 推荐生产配置

```yaml
scheduler:
  tick_interval_seconds: 2
  preload_radius_tiles: 2          # 玩家周围 5×5 tile（每 tile 128×128 block）
  lookahead_tiles: 3               # 半径外再往前多排 3 格
  buffer_tiles_omnidirectional: 1  # 最小保底圈（半径设 0 时仍有效）
  block_unloaded_tiles: true
  max_concurrent_generations: 1    # Overpass 友好，勿随意加大
  preload_mode: "center"

dev:
  clear_on_startup: false            # 生产务必关闭
```

### 10.3 参数含义与调参

| 参数 | 含义 | 调大 | 调小 |
|------|------|------|------|
| `preload_radius_tiles` | 每 tick 扫描并排队半径 | 掉头/横向探索更从容，Arnis 调用更频繁 | 省资源，更依赖撞线墙 |
| `lookahead_tiles` | 朝向前方额外排队距离 | 疾跑/骑乘不易顶墙 | 前方更容易短暂被挡 |
| `buffer_tiles_omnidirectional` | 全方向最小缓冲 | 四周都有保底 | — |
| `tick_interval_seconds` | 扫描频率 | 响应更快，CPU 略增 | 排队略滞后 |
| `max_concurrent_generations` | 并行 Arnis 数 | 生成更快，易打满 Overpass | 稳定但慢 |

**经验公式（起步值）：**

```
preload_radius_tiles  ≥ 视距(block) / tile边长(block) / 2  +  buffer
lookahead_tiles       ≥  最大移动速度(block/s) × 单 tile 生成耗时(s) / tile边长
```

例：视距 10 chunk ≈ 160 block，tile 边长 128 block → 半径至少 1，建议 2。
单 tile 生成 ~30s，跑步 ~5 block/s → lookahead ≥ 30×5/128 ≈ 2，建议 3。

### 10.4 方向判定

1. **有移动**（本 tick 所在 tile ≠ 上 tick）：用 tile 位移符号 `(sign Δx, sign Δz)`
2. **站立/原地转向**：用水平朝向 `yaw` 投影到 `(dx, dz)`
3. **Worker 优先级**：`score = 距离 − 0.6×前方分量 + 0.25×后方分量`，分越低越先跑

### 10.5 多人

- 每个玩家独立贡献扫描范围，未知 tile 去重后共用一个 PENDING 池
- `priorityComparator` 取「对所有玩家中最低分」——离任意玩家近、且在有人前方的 tile 先跑
- 玩家分散探索时，各自前方都会被照顾到

### 10.6 与首启 preload_mode 的关系

| 场景 | 行为 |
|------|------|
| 全新服 `preload_mode: center` | 开服只生成 (0,0)，门控通过后玩家才可进 |
| 重启后 DB 有 MERGED 记录 | 跳过首启排队，直接恢复；在线预加载接管 |
| `preload_mode: strip_3` | 首启多预生成 3 格，适合演示/压测，生产一般用 center |

---

## 9. 已知限制(诚实列出,别让Claude Code实现到一半才发现)

- Tile边界处的道路/建筑可能有轻微不连续(overlap裁剪能缓解但不能完全消除)
- Arnis依赖公共Overpass API和地形数据源,你的服务器所在网络环境到这些服务的延迟/稳定性直接决定预生成速度上限——生产环境建议评估自建/镶镶Overpass镜像
- 玩家用鞋子+烟花/精灵巨剑(elytra)等高速移动方式时,缓冲距离需要相应调大,极端情况下兜底机制会更频繁触发
- `ChunkSnapshot`/`setBlockData`逐方块Bukkit API搬运,在Tile较大时性能有上限;如果实测发现merge阶段成为瓶颈,后续优化方向是绕开Bukkit API直接做NBT层面的chunk数据拷贝(用Querz/NBT之类的库),但实现复杂度和Minecraft版本兼容性维护成本都更高,建议先用Bukkit API版本跑通再决定是否有必要做这层优化
