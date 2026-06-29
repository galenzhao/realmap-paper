# RealMap — 真实地图 Minecraft 服务器

PaperMC 插件：**RealMap** 通过 [Arnis](https://github.com/louis-e/arnis) 将 OpenStreetMap 真实地理数据异步生成为可探索的 Minecraft 世界。玩家移动时，前方区域会按真实地图持续预加载扩展。

## 项目结构

```
mc-java-map-gen/
├── docs/design.md          # 架构与设计文档
├── src/                    # RealMap 插件源码 (Java 25)
├── pom.xml                 # Maven 构建
├── build.bat               # 编译插件并复制到 plugins/
├── start.bat               # 启动 Paper 开发服务器
├── mvnw.cmd                # Maven 包装脚本
├── config/                 # Paper 全局配置
└── eula.txt                # 已接受 EULA
```

## 前置要求

| 组件 | 版本 / 说明 |
|------|-------------|
| **JDK** | 25（Paper 26.1 要求） |
| **Maven** | 3.9+（或使用 `mvnw.cmd`） |
| **Paper** | 26.1.2 build 72（与 `pom.xml` 中 API 版本一致） |
| **Arnis** | Windows 可执行文件，放到 `arnis/arnis-windows.exe` |

### 1. 下载 Paper 服务器

从 [Paper 下载页](https://papermc.io/downloads/paper) 获取 **26.1.2** 对应 build，将 jar 放到项目根目录并命名为：

```
paper-26.1.2-72.jar
```

### 2. 安装 Arnis

将 Arnis 可执行文件放到：

```
arnis/arnis-windows.exe
```

或在首次启动后编辑 `plugins/RealMap/config.yml` 中的 `arnis.binary_path`。

### 3. 设置 Java 环境

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-25"
```

`start.bat` 和 `build.bat` 会优先使用 `JAVA_HOME`，未设置时使用脚本内默认路径。

## 快速开始

```powershell
# 1. 编译插件
.\build.bat

# 2. 启动服务器（会自动复制最新 jar）
.\start.bat
```

首次启动会在 `realmap` 维度生成锚点 Tile(0,0)。Tile 合并完成前，玩家会被门控拦截并提示等待。

## 游戏内命令

| 命令 | 说明 |
|------|------|
| `/realmap status` | 查看各状态 Tile 数量与锚点坐标 |
| `/realmap tp` | 传送到 realmap 世界出生点 |

## 配置

默认配置在 `src/main/resources/config.yml`，首次运行会复制到 `plugins/RealMap/config.yml`。

关键项：

```yaml
anchor:
  lat: 34.0019          # GPS 锚点（对应世界坐标 0,0）
  lon: -84.1449
  scale_meters_per_block: 1.0

arnis:
  binary_path: "arnis/arnis-windows.exe"

scheduler:
  preload_radius_tiles: 2   # 玩家周围预加载半径
  lookahead_tiles: 3        # 移动方向额外预加载
  block_unloaded_tiles: true

dev:
  clear_on_startup: false   # 开发时可设为 true，每次启动清空世界
```

开发模式下 `dev.clear_on_startup: true` 时，`start.bat` 会在 JVM 启动**前**删除 `world/`、staging 和 tile 数据库，避免文件锁问题。

详细设计、状态机、调参指南见 [docs/design.md](docs/design.md)。

## 开发与测试

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-25"
.\mvnw.cmd test          # 单元测试
.\mvnw.cmd package       # 打包 shaded jar
```

手动 Arnis 集成测试（需本地 Arnis，非 JUnit）：

```powershell
$env:ARNIS_EXE = "arnis\arnis-windows.exe"
# 在 IDE 中运行 ArnisSubprocessTest / ArnisProjectionTest 的 main()
```

## 实现状态

- [x] M1 坐标转换 (`GeoCoordTransformer` + 测试)
- [x] M2/M3 Arnis 子进程调用与单 Tile 合并
- [x] M4 Void 生成器 + SQLite Tile 状态机
- [x] M5 玩家位置调度与方向预加载
- [x] M6 边界门控、失败重试、开服门控
- [ ] M7 多锚点 / 每玩家独立世界（设计见 docs）

## 已知限制

- Tile 边界处道路/建筑可能轻微不连续
- 生成速度受 Overpass API 与网络延迟影响
- 高速移动（鞘翅等）需调大 `preload_radius_tiles` / `lookahead_tiles`
