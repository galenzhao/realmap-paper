# RealMap — 真实地图 Minecraft 服务器

PaperMC 插件：**RealMap** 通过 [Arnis](https://github.com/louis-e/arnis) 将 OpenStreetMap 真实地理数据异步生成为可探索的 Minecraft 世界。玩家移动时，前方区域会按真实地图持续预加载扩展。

## 项目结构

```
realmap-paper/              # GitHub 仓库名
├── docs/design.md          # 架构与设计文档（含实现对照）
├── src/                    # RealMap 插件源码 (Java 25)
├── pom.xml
├── build.bat               # 编译插件 → plugins/realmap.jar
├── start.bat               # 启动 Paper 开发服务器
├── mvnw.cmd
├── config/                 # Paper 全局配置
└── eula.txt
```

## 前置要求

| 组件 | 版本 / 说明 |
|------|-------------|
| **JDK** | 25（Paper 26.1 要求） |
| **Maven** | 3.9+（或使用 `mvnw.cmd`） |
| **Paper** | 26.1.2 build 72（与 `pom.xml` 中 API 版本一致） |
| **Arnis** | 可执行文件，默认路径 `arnis/arnis-windows.exe` |

### 1. 下载 Paper 服务器

从 [Paper 下载页](https://papermc.io/downloads/paper) 获取 **26.1.2** build 72，将 jar 放到项目根目录：

```
paper-26.1.2-72.jar
```

### 2. 安装 Arnis

```
arnis/arnis-windows.exe
```

或在 `plugins/RealMap/config.yml` 中设置 `arnis.binary_path`（支持绝对路径）。

### 3. Java 与 JVM

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-25"
$env:SERVER_MEMORY = "4G"   # 可选，默认 2G
.\start.bat
```

`start.bat` 使用 [Fill](https://fill-ui.papermc.io/projects/paper/family/26.1) 为 Paper 26.1 / Java 25 推荐的 G1GC 参数（`-Xms` = `-Xmx`）。

## 快速开始

```powershell
.\build.bat
.\start.bat
```

首次启动在 `realmap` 维度生成 Tile(0,0)。合并完成前，`AsyncPlayerPreLogin` 会拦截玩家并显示进度提示。

## 游戏内命令

| 命令 | 说明 |
|------|------|
| `/realmap status` | 各状态 Tile 数量与锚点坐标 |
| `/realmap tp` | 传送到 `realmap` 世界出生点（TileScheduler 缓存的 spawn） |

> 尚未实现：`setanchor`、`teleport <lat> <lon>`、`regen`（见 [docs/design.md](docs/design.md) §8 规划）

## 配置

默认模板：`src/main/resources/config.yml` → 首次运行复制到 `plugins/RealMap/config.yml`。

```yaml
anchor:
  lat: 34.0019              # GPS 锚点 = 世界坐标 (0, 0)
  lon: -84.1449
  scale_meters_per_block: 1.0

tile:
  size_chunks: 8            # 8×8 chunk = 128×128 block
  overlap_blocks: 16        # ⚠ 配置存在，当前代码未用于 bbox（见 design.md）

arnis:
  binary_path: "arnis/arnis-windows.exe"
  staging_dir: "plugins/RealMap/staging"
  timeout_seconds: 300

scheduler:
  tick_interval_seconds: 2
  preload_radius_tiles: 2   # Chebyshev 半径（2 → 5×5 tile）
  lookahead_tiles: 3        # 移动/朝向方向额外排队
  buffer_tiles_omnidirectional: 1
  block_unloaded_tiles: true
  max_concurrent_generations: 1
  preload_mode: "center"    # 首启：center | strip_N | grid_R

merge:
  chunks_per_tick: 1        # ⚠ 配置存在，当前未使用（merge 在 worker 线程一次性完成）

retry:
  max_retries: 3
  backoff_seconds: 30       # ⚠ 配置存在，当前未使用（失败立即重试排队）

dev:
  clear_on_startup: false   # true 时 start.bat 启动前清空 world/ 与 tile 数据
```

## 运行时行为

### 预加载

每 `tick_interval_seconds` 秒，`TileScheduler` 扫描 `realmap` 世界内所有玩家：Chebyshev 半径内未知 tile → `PENDING`；移动方向（无移动则用 yaw）上额外 `lookahead_tiles` 格。Worker 按**离玩家最近距离**取任务（暂无方向加权）。

### 边界门控

`TileBoundaryGuard` 阻止进入未 `MERGED` 的 tile，ActionBar 提示「前方地图正在加载…」，并 `ensureQueued` 目标 tile。

### Region 热刷新（可选，非每次）

Tile merge 写入磁盘后，若该 tile 对应 `.mca` region **已有 chunk 被加载过**（玩家视距内先触发了 void），Paper 会缓存旧数据。此时插件会：

1. 短暂将玩家传到 overworld
2. unload → recreate `realmap` 世界
3. 传回原坐标 + `refreshChunk` 视距内区块

若 region **未被加载**（预生成跑在玩家前面），则**不刷新、不传送**，玩家走近时自然读到新数据。

Tile(0,0) 合并后始终会 reload 世界并扫描地面设置 spawn。

## 开发与测试

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-25"
.\mvnw.cmd test
.\mvnw.cmd package
```

手动 Arnis 测试（非 JUnit，IDE 运行 main）：

```powershell
$env:ARNIS_EXE = "arnis\arnis-windows.exe"
# ArnisSubprocessTest / ArnisProjectionTest
```

## 实现状态

| 里程碑 | 状态 | 说明 |
|--------|------|------|
| M1 坐标 | ✅ | `GeoCoordTransformer` + 单元测试 |
| M2/M3 Arnis + 合并 | ✅ | `ArnisCaller` + `RegionFileMerger`（.mca 直写，非 Bukkit API） |
| M4 Void + 状态机 | ✅ | `VoidChunkGenerator` + SQLite |
| M5 调度 | ✅ | 定时 tick 预加载 + 距离优先 worker |
| M6 边缘情况 | ✅ | 边界门控、开服门控、失败重试、region 热刷新 |
| M7 多锚点 | ❌ | 设计见 docs |
| overlap 接缝优化 | ❌ | config 有，代码未接 |
| FALLBACK_FLAT | ❌ | enum 有，失败仅 → FAILED |
| 管理命令 | ❌ | setanchor / GPS tp / regen |

## 已知限制

- Tile 边界道路/建筑可能不连续（overlap 尚未实现）
- 生成速度受 Overpass API 与网络影响
- 高速移动需调大 `preload_radius_tiles` / `lookahead_tiles`
- 失败 tile 不会自动生成平地，需管理员处理或重试

详细架构见 [docs/design.md](docs/design.md)。
