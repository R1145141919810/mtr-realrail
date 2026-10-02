# 更好的列车限速及信号判定（MTR RealRail）

> [Minecraft Transit Railway](https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway)（MTR **4.0.0 及以后**）的附属模组
> · 平台 **Minecraft 1.20.1 + Forge 47.x** · 协议 **MIT**

模组正式名称：**更好的列车限速及信号判定**　modId：`mtrrealrail`

| 项 | 值 |
|---|---|
| 支持版本 | Minecraft 1.20.1 / Forge 47.x / MTR 4.0.0+ |
| 当前版本 | **v1.0.0**（另含早期预览版 v1.0.0-preview） |
| 产物 | `dist/mtrrealrail-1.0.0+1.20.1.jar`（编译产物在 `build/libs/`） |
| 前置 | 只需 **Forge + MTR**（不依赖 Architectury 等额外 API） |

---

## 功能

### 一：限速变更时机后移（默认"车尾判定"）

原版 MTR 中列车**车头一进入新轨道段就立即按该段限速变速**，前后车厢可能处在不同限速的轨道上。
本模组把限速变更推迟到**车尾完全经过轨道连接器之后**：

- 车头跨越轨道连接器时记录"待生效边界"，限速先保持旧值；
- 车尾所在轨道段索引越过该边界（整列车进入新段）后，才按新段限速加/减速；
- 预制动（`Siding.getUpcomingSlowerSpeed`）同步改为"车尾感知"：扫描到车尾判定节点即停止向后预判，
  避免车头还没过连接器就提前减速；
- 安全制动（信号红灯、平台停车、前方占用）与物理防撞**完全不受影响**。

**按节点单独切换**：手持刷子（`mtr:brush`）右击轨道节点方块（`mtr:rail`）会**照常打开 MTR 原版轨道界面**
（轨道形状 / 轨道样式，原版功能完全保留），界面中会多出一个"**节点限速判定**"按钮；点击后进入本模组的判定界面，
一键切换"车头进入立即变更（原版）/ 车尾通过后变更（推荐）"，关闭后自动返回 MTR 原版轨道界面，
设置立即写入世界存档（`world/mtrrealrail/nodes.json`）。

### 二：多信号系统互不干扰

MTR 的 16 色信号连接器各自是一个独立信号系统。原版 `Rail.isBlocked` 检查并预留该轨道上的**全部颜色**，
导致系统 A 的列车会占用 B 系统的区间、也会被 B 系统的红灯拦停。本模组：

- 每 tick 计算列车车身覆盖轨道段 `signalColors` 的并集，即"列车实际所在的信号系统"；
- `Rail.isBlocked` 只检查/预留列车所属颜色的区间，各信号系统互不干扰；
- 未登记（首 tick 等异常）自动回退原版逻辑；列车不在任何带信号轨道上时不受信号约束、不预留区间；
- 信号灯渲染按颜色打包，隔离后显示自动正确；物理防撞与颜色无关，两列车不会相撞。

---

## 安装

1. 安装 **Minecraft 1.20.1 + Forge 47.x**（服务端/客户端均可）；
2. 放入 **MTR 4.0.0+**（`minecraft-transit-railway-FORGE-4.0.x+1.20.1.jar`）；
3. 放入本模组 `mtrrealrail-1.0.0+1.20.1.jar`（见 [Releases](../../releases) 或 `dist/`）。

## 配置（`config/mtrrealrail.properties`）

| 键 | 默认值 | 说明 |
|---|---|---|
| `default_node_mode` | `TAIL` | 未被刷子单独配置的节点：`TAIL` = 车尾通过后变更；`HEAD` = 原版立即变更 |
| `signal_isolation` | `true` | 是否启用多信号系统隔离 |
| `brush_gui_trigger` | `CLICK` | `CLICK` = 普通右键时在 MTR 原版轨道界面中追加按钮；`SHIFT` = 仅 Shift+右键时追加 |

---

## 构建

需要 **JDK 17**（`gradlew` 自带 Gradle wrapper）：

```bash
gradlew setupLibrary      # 下载/复用 MTR 发行 jar，裁剪出 libs/mtr-compile.jar（编译依赖）
gradlew build             # 产物 build/libs/mtrrealrail-1.0.0+1.20.1.jar
```

`setupLibrary` 会优先复用本地已下载的 MTR 发行 jar，否则从
[Modrinth Maven](https://api.modrinth.com/maven/maven/modrinth/minecraft-transit-railway/) 下载
`FORGE-4.0.5+1.20.1`（下载后校验 zip 完整性，失败自动重试）。

> 为什么用 MTR **发行 jar** 而不是 dev jar：发行版把 fastutil 重定位到了
> `org.mtr.libraries.it.unimi.dsi.fastutil.*`，Mixin 的注入描述符必须与运行时字节码一致。

### 开发环境注意事项

ForgeGradle 开发环境里模组以"目录"形式加载、没有 MANIFEST，而 Forge 是通过 `MixinConfigs` 清单属性
注册 Mixin 配置的，因此 `runClient`/`runServer` 里 **Mixin 不会生效**；生产版 MTR 也无法直接放进 `run/mods`
（其自带 mixin 使用 SRG 名，开发环境为官方名）。功能验证请把 jar 放进真实客户端/服务端的 `mods/` 进行。
仅让开发环境跑起来可执行 `gradlew buildMtrStubJar`（生成含裁剪 MTR 类的 `run/mods/mtr-stub.jar`）。

服务端可加 `-Dmtrrealrail.verifyMixins=true`：启动时强制加载全部 Mixin 目标类，任何注入不匹配都会立刻报错。

---

## 仓库结构

```
build.gradle / settings.gradle / gradle.properties   # ForgeGradle 6 工程（仓库根 = 项目根）
src/main/java/mtr/realrail/
├─ MtrRealRail.java                  # 模组入口（@Mod）：配置、网络、事件、存档数据、Mixin 自检
├─ RealRailConfig.java               # 全局配置
├─ NodeModeStore.java                # 每节点判定方式存储（world/mtrrealrail/nodes.json）
├─ SignalIsolationRegistry.java      # 列车 id → 所属信号颜色（TTL 10s）
├─ SpeedLimitHelper.java             # 需求一核心：边界状态机 + 生效限速 + 车尾感知预制动
├─ NodeModeNetwork.java              # Forge SimpleChannel：开屏 / 请求 / 切换
├─ client/                           # 仅客户端：NodeModeScreen、RailScreenBridge、RailNodeInteractionHandler
└─ mixin/
   ├─ VehicleSimulateMixin.java      # Vehicle.simulate / simulateMoving 注入
   ├─ VehicleSchemaAccessor.java     # @Accessor：读取父类 VehicleSchema.railProgress
   ├─ RailSignalIsolationMixin.java  # Rail.isBlocked 隔离
   └─ RailModifierScreenMixin.java   # 在 MTR 原版轨道界面注入"节点限速判定"按钮（客户端）
docs/                                # 技术方案、核心实现、环境搭建、风险说明、交付与验证记录
dist/                                # 已构建的发布产物
```

> 说明：`checkouts/`、`libs/mtr-compile.jar`、`build/`、`run/` 均为构建期生成物，已在 `.gitignore` 中忽略。

---

## 版本历史

| 版本 | 说明 |
|---|---|
| **v1.0.0** | 正式版。节点判定界面作为按钮**嵌入 MTR 原版轨道界面**（原版交互与功能完全保留，关闭后自动返回）。核心：车尾判定限速状态机、16 色信号系统隔离、按节点切换与持久化。 |
| v1.0.0-preview | 早期预览版（`dist/history/mtrrealrail-1.0.0-preview-brushgui.jar`）。刷子右击轨道节点**直接打开**本模组界面，会替代 MTR 原版界面。 |

---

## 已验证内容

- `gradlew build` 通过（ForgeGradle 6 + `reobfJar`，产物 `mtrrealrail-1.0.0+1.20.1.jar`）；
- **真实 Forge 1.20.1-47.4.23 独立服务端 + MTR FORGE-4.0.5+1.20.1** 启动成功，
  三个核心 Mixin 全部注入成功（`VehicleSimulateMixin` → `org.mtr.core.data.Vehicle`、
  `VehicleSchemaAccessor` → `VehicleSchema`、`RailSignalIsolationMixin` → `Rail`），
  五个 Mixin 目标类均正常加载；
- 实车行为（限速变更时机、节点切换与持久化、双色信号隔离）请按 `docs/03` §6 的清单在游戏内实测。

---

## 许可

本项目采用 **MIT License**，见 [LICENSE](LICENSE)。

MTR 本体及其核心 Transport-Simulation-Core 同样为 MIT（Copyright Jonathan Ho），
本项目通过 Mixin 对接其接口，未复制其源码。
