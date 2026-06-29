# RealMap 设计文档：Arnis 驱动的无限扩展真实地图

> **文档说明**：本文档包含原始设计与**当前实现对照**。带 ⚠ 标记的为设计有、代码未接或未完全实现的部分。

---

## 0. 目标与非目标

**目标**
- 全局 GPS 锚点 (lat/lon) 对应世界坐标 (0, 0)
- 出生区域与探索方向按真实地图（Arnis + OSM）渲染
- 玩家持续移动时前方 tile 异步预生成，避免原版地形或 void 边界

**非目标 (v1)**
- 不做逐 chunk 实时 Overpass 查询
- 不做每玩家独立 GPS 锚点/独立世界（见 §8 扩展方向）

---

## 1. 核心矛盾与解法

| 矛盾 | 解法（当前实现） |
|------|------------------|
| Arnis 最小单位是 bbox，不是 chunk | **Tile**（默认 8×8 chunk）异步预生成 |
| Arnis 输出坐标从 (0,0) 起算 | staging 目录隔离 + `RegionFileMerger` 写入 live 世界对应 `.mca` 并 patch `xPos`/`zPos` |
| 已加载 chunk 不会自动重生成 | `VoidChunkGenerator` + 预加载跑在玩家前面 + region 热刷新（见 §5.5） |
| Arnis/IO 耗时长 | 独立 `ExecutorService` worker 线程池 |
| Tile 边界道路可能不连续 | ⚠ `overlap_blocks` 已在 config，**bbox 尚未扩展 overlap** |

---

## 2. 总体架构（当前实现）

```
                    ┌─────────────────────────────┐
                    │  realmap 世界 (VoidChunkGenerator) │
                    └──────────────▲──────────────┘
                                   │ .mca 写入 live world/region/
                    ┌──────────────┴──────────────┐
                    │  RegionFileMerger            │  ← 异步 worker 内执行
                    │  (NBT xPos/zPos patch)       │     非 Bukkit setBlockData
                    └──────────────▲──────────────┘
                                   │
                    ┌──────────────┴──────────────┐
                    │  TileScheduler.generateAsync │  ExecutorService
                    │  + ArnisCaller (子进程)       │
                    └──────────────▲──────────────┘
                                   │
                    ┌──────────────┴──────────────┐
                    │  TileScheduler.tick          │  主线程，每 N 秒
                    │  scanPlayerPreload + 派发 worker │
                    └──────────────▲──────────────┘
                          ┌────────┴────────┐
                          │ TileBoundaryGuard │  PlayerMoveEvent（撞线）
                          │ PlayerJoinHandler │  开服门控 + 进服传送
                          └─────────────────┘
```

**与初版设计的差异**
- 无独立 `TileMergeService` / `TileGenerationWorker` 类，逻辑集中在 `TileScheduler`
- 合并走 **region 文件直拷贝**，非 staging 世界 load + `ChunkSnapshot`
- 预加载由**定时 tick**驱动，非 `PlayerMoveEvent`（移动事件仅用于边界门控）

---

## 3. 坐标系与 Tile 网格

### 3.1 GPS ↔ 方块

实现类：`GeoCoordTransformer`（平面近似，有单元测试）。

```
metersPerDegLat = 111320.0
metersPerDegLon = 111320.0 * cos(radians(ANCHOR_LAT))
```

锚点 `(ANCHOR_LAT, ANCHOR_LON)` ↔ 世界 `(0, 0)`。

### 3.2 Tile 网格

```
tileOriginBlockX = tileX * size_chunks * 16
tileOriginBlockZ = tileZ * size_chunks * 16
```

默认 `size_chunks = 8` → 128×128 block/tile。

**bbox（当前 `buildBbox`）**：tile 矩形边界转 lat/lon，**不含 overlap**。

⚠ 设计中的 `overlap_blocks: 16` 尚未接入 bbox 与 merge 裁剪。

---

## 4. Tile 状态机

```
PENDING → QUEUED → GENERATING → STAGED → MERGING → MERGED
                                              ↓
                                           FAILED (重试耗尽)
```

| 状态 | 含义 |
|------|------|
| PENDING | 已识别需要，待 worker 领取 |
| QUEUED | 已提交 worker |
| GENERATING | Arnis 子进程运行中 |
| STAGED | Arnis 完成，staging 有 region/ |
| MERGING | 正在写入 live .mca |
| MERGED | 可进入 |
| FAILED | 超过 `max_retries` |
| FALLBACK_FLAT | ⚠ enum 存在，**未实现**平地兜底 |

持久化：`plugins/RealMap/tiles.db`（SQLite）。重启时 in-flight 状态重置，cache 从 DB 加载。

---

## 5. 关键模块（实现对照）

### 5.1 `GeoCoordTransformer` ✅

lat/lon ↔ block 互转，无状态，已测试。

### 5.2 `TileScheduler` ✅

- **主线程 tick**（`scheduler.tick_interval_seconds`，默认 2s）
- `scanPlayerPreload`：Chebyshev 半径 + 方向 lookahead 排队
- `priorityComparator`：**仅按 tile 到最近玩家的欧氏距离**排序  
  ⚠ 设计中的方向加权 score（§10.4）**未实现**
- 限制 `max_concurrent_generations` 并行 Arnis 数

### 5.3 `ArnisCaller` ✅

实际 CLI 参数（`--projection local`）：

```
arnis --bbox <minLat,minLon,maxLat,maxLon>
      --output-dir <staging>
      --scale 1 --projection local
      --interior false --roof false --no-3d --land-cover false
```

输出在 staging 下的 `Arnis World N/region/`。

### 5.4 `RegionFileMerger` ✅（替代原 TileMergeService）

- 从 staging `r.0.0.mca` 读取 chunk
- patch NBT 中 `xPos`/`zPos` 为 live 世界坐标
- 写入 live `r.{regionX}.{regionZ}.mca`
- **在 async worker 中一次性完成**，非主线程分 tick  
  ⚠ `merge.chunks_per_tick` config **未读取**

### 5.5 `VoidChunkGenerator` ✅

所有 `shouldGenerate*` 返回 false → 纯 void，不铺基岩。

### 5.6 Region 热刷新 ✅

`finishOnMainThread` 合并完成后：

| 条件 | 行为 |
|------|------|
| tile = (0,0) | 始终 `reloadWorldThenSetSpawn()` |
| 其他 tile + `isRegionHot()` = false | 仅标记 MERGED，**不传送玩家** |
| 其他 tile + `isRegionHot()` = true | 玩家暂存 overworld → unload/recreate world → 传回 + `refreshChunk` |

`isRegionHot`：该 tile 对应 `.mca` region 内**任意 chunk 已 `isChunkLoaded`**。

**理想路径**：预生成先于视距加载 → region cold → 无感扩展。

### 5.7 `TileBoundaryGuard` ✅

- `block_unloaded_tiles: true` 时拦截进入非 terminal tile
- 取消移动 / 推回 / ActionBar 提示 + `ensureQueued`

Terminal = `MERGED` 或（未实现的）`FALLBACK_FLAT`。

### 5.8 `PlayerJoinHandler` ✅

1. `AsyncPlayerPreLogin`：Tile(0,0) 未 MERGED → 踢出 + 状态提示
2. `PlayerJoinEvent`：传送到 `TileScheduler.getCachedSpawnLoc()`

### 5.9 命令

| 命令 | 状态 |
|------|------|
| `/realmap status` | ✅ |
| `/realmap tp` | ✅ 传到 spawn |
| `/realmap setanchor <lat> <lon>` | ❌ 规划 |
| `/realmap teleport <lat> <lon>` | ❌ 规划 |
| `/realmap regen <tx> <tz>` | ❌ 规划 |

---

## 6. 配置项（与代码一致）

见 `src/main/resources/config.yml`。下列 config **存在但未接入代码**：

- `tile.overlap_blocks`
- `merge.chunks_per_tick`
- `retry.backoff_seconds`
- `arnis.staging_dir`（硬编码为 `getDataFolder()/staging`）

---

## 7. 里程碑

| # | 内容 | 状态 |
|---|------|------|
| M1 | GeoCoordTransformer | ✅ |
| M2 | Arnis 子进程 | ✅ |
| M3 | 单 tile 端到端 merge | ✅（RegionFileMerger） |
| M4 | Void + SQLite 状态机 | ✅ |
| M5 | 调度 + 预加载 | ✅ |
| M6 | 边界门控、重试、region 刷新 | ✅（overlap/FALLBACK 除外） |
| M7 | 多锚点 / 每玩家世界 | ❌ |

---

## 8. 后续扩展：每玩家独立 GPS

- 每玩家独立 Bukkit World + `player_anchors` 表
- Tile 流程不变，世界生命周期与卸载策略需单独设计

---

## 9. 生产预加载策略

### 9.1 三阶段

```
阶段 A — 开服门控
  preload_mode: center → 首启只 queue (0,0)
  MERGED 前 AsyncPlayerPreLogin 拦截

阶段 B — 在线预加载（定时 tick）
  每玩家：半径 R 扫描 → PENDING
  移动/朝向方向 +lookahead 额外 queue
  Worker：距最近玩家最近的 PENDING 优先

阶段 C — 撞线兜底（实时）
  TileBoundaryGuard + ensureQueued
```

### 9.2 推荐生产配置

```yaml
scheduler:
  tick_interval_seconds: 2
  preload_radius_tiles: 2
  lookahead_tiles: 3
  buffer_tiles_omnidirectional: 1
  block_unloaded_tiles: true
  max_concurrent_generations: 1
  preload_mode: "center"

dev:
  clear_on_startup: false
```

### 9.3 调参

| 参数 | 含义 |
|------|------|
| `preload_radius_tiles` | 每 tick Chebyshev 扫描半径 |
| `lookahead_tiles` | 方向条带额外距离 |
| `buffer_tiles_omnidirectional` | 最小半径下限 |
| `tick_interval_seconds` | 扫描频率 |
| `max_concurrent_generations` | 并行 Arnis 上限 |

经验：`preload_radius_tiles ≥ viewDistance(blocks) / 128 / 2 + buffer`

### 9.4 方向判定（lookahead 用）

1. 有移动：tile 位移符号
2. 站立：yaw → (dx, dz)

⚠ Worker **优先级**目前不用方向，仅距离。

### 9.5 preload_mode

| 模式 | 首启 queue |
|------|------------|
| `center` | (0,0) |
| `strip_N` | (0,0)…(N-1,0) |
| `grid_R` | (-R..R)² 正方形 |

---

## 10. 已知限制

- Tile 边界道路/建筑可能不连续（overlap 未实现）
- Arnis 依赖 Overpass / 外部地形 API，网络决定生成速度上限
- 鞘翅等高速移动需加大预加载半径
- merge 已在 .mca 层完成，性能优于逐方块 Bukkit API；region 热刷新仍有短暂传送
- 失败 tile 不会自动 FALLBACK_FLAT，玩家可能被边界墙长期挡住该区域
