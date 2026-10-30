# 更好的列车限速及信号判定 · MTR RealRail

> Minecraft **1.20.1** + **Forge 47.x** + **MTR 4.0.5** 附属模组
> 当前版本：**v1.5.0**（`mtrrealrail-1.5.0+1.20.1-forge.jar`）

在 MTR 原版基础上补齐"限速判定 / 信号隔离 / 动态占用 / 路径与发车调度 / 站台智能调度"的一整套运行控制能力，
并可与外置调度服务组成**规划 + 执行**的完整链路。

---

## ⚠️ 可选配套模组（必须成对安装）

本模组的"车站站台智能调度""车厂在线路中间""运行时改停站台"等高级功能依赖下面的**调度 + 执行**两件套，
它们是一套体系里的**两个部分，必须同时安装**；缺任何一个都会退化为"无完整功能"（本模组其余功能不受影响）：

| 仓库 | 作用 | 缺装后果 |
|---|---|---|
| [**mtr-tsc-dispatch-system**](https://github.com/R1145141919810/mtr-tsc-dispatch-system) | 外置调度与动态路径规划服务（规划端） | 站台智能调度进入"等待 tsc-dispatch"待机，不会自行派车/改停 |
| [**mtr-control-execute-brige**](https://github.com/R1145141919810/mtr-control-execute-brige) | 游戏内控制执行桥（执行端，默认 `127.0.0.1:8889`） | 站台智能调度进入"等待执行链"待机；"车厂在线路中间"不会派车/回场 |

> **务必两个一起装**：单独装规划端只能"看"，单独装执行端没有人下指令。
> 两者都就绪后，本模组会自动把执行桥地址/令牌推送给规划服务（`POST /v1/config`），
> 线路调度界面底部会显示 `执行链：tsc-dispatch 已连接 / 执行桥 mtbridge 已连接`。

---

## 功能

| # | 功能 | 说明 |
|---|---|---|
| 1 | **列车限速判定** | 轨道连接器处按"**车尾完全通过**才变更限速"判定（`TAIL`，默认；可切回 MTR 原版 `HEAD`），刷子可逐节点切换 |
| 2 | **多信号系统隔离** | 只对**共享同一信号颜色**的列车做闭塞/让行判断，不同线路互不干扰 |
| 3 | **动态轨道占用** | 用列车实时位置/速度计算米级占用区间与安全间距，同信号系统内允许"移动闭塞"式跟车放行 |
| 4 | **自动最优路径规划** | 依据实时占用与前车关系打分，必要时触发 MTR 重新生成车厂路径 |
| 5 | **动态发车与避让** | 停站满阈值后按实时交通决定等待让行（有硬上限与冷却，绝不压死站台） |
| 6 | **车站站台智能调度** | 车站界面开关（≥2 站台才显示）+ 线路界面"线路智能调度站台"多选 GUI；运行时把列车改停到**允许且空闲**的站台 |
| 7 | **车厂在线路中间**（v1.5.0） | 车厂 → 优化线路配置开关：按最近路线驶向最近车站投入运行；时刻表进入"无车发出的时段"自动回场；开关切换自动**清除车辆 + 刷新路线** |
| 8 | **运行时改停站台**（v1.5.0） | 经 `set_platform` 指令让执行桥为运行中的列车**重新生成进路**，绕开 MTR `immutablePath` 不可替换的限制 |

界面入口：
- 铁路仪表板 → **车厂** → 「优化线路配置」（动态闭塞 / 路径规划 / 发车避让 / **车厂在线路中间**）
- 铁路仪表板 → **车站** → 右下角「站台智能调度: 开/关」（仅 ≥2 站台的车站）
- 铁路仪表板 → **线路** → 「线路智能调度站台」（按车站多选允许停靠站台，底部显示调度链状态）

---

## 安装

1. Minecraft 1.20.1 + Forge 47.4.x + **MTR 4.0.5**（Forge 版）。
2. 把 `dist/mtrrealrail-1.5.0+1.20.1-forge.jar` 放进 `mods/`（**客户端 + 服务端都要放**）。
3. 需要高级调度功能时，**同时**安装配套两件套（见上表），并先启动服务器、再启动 `mtr-tsc-dispatch-system`。

---

## 配置

`config/mtrrealrail.properties`（首次启动自动生成；修改后需重启）：

```properties
# 限速判定
default_node_mode=TAIL            # TAIL（车尾通过才变）/ HEAD（MTR 原版）
signal_isolation=true             # 多信号系统隔离

# 车厂级默认值（每个车厂可在 GUI 中单独覆盖）
optimize_dynamic_block_release=true
optimize_safety_margin_meters=30
optimize_route_enabled=true
optimize_path_lookahead_meters=400
optimize_departure_guard=true
optimize_dwell_threshold_seconds=10
optimize_conflict_window_seconds=5
optimize_max_hold_seconds=15

# 调度链（规划端 tsc-dispatch + 执行桥）
tsc_dispatch_enabled=true
tsc_dispatch_url=http://127.0.0.1:8787
tsc_dispatch_poll_seconds=2
tsc_dispatch_required=true        # true = 选台决策只认调度服务
tsc_dispatch_dimension=0
tsc_dispatch_timeout_ms=1200
tsc_dispatch_export_path=../tsc-dispatch/config/dispatch.generated.yml

bridge_url=http://127.0.0.1:8889  # 执行桥地址
bridge_token=                     # 留空 = 自动读取 config/mtbridge.json 的 token
bridge_push_enabled=true          # 自动把桥地址/令牌推给调度服务
bridge_probe_seconds=5
execution_chain_required=true     # 链不完整时高级调度待机（不本地乱派）
```

存档级配置（随世界保存）：

```
world/mtrrealrail/platforms.json   # 车站开关 + 线路 × 车站 → 允许停靠站台
world/mtrrealrail/nodes.json       # 每个轨道连接器的限速判定方式
world/mtrrealrail/optimize.json    # 每个车厂的优化配置（含「车厂在线路中间」）
```

---

## 构建

环境：**JDK 17**、**Gradle 8.1+**（自带 wrapper）、联网（首次会从 Modrinth 拉 MTR jar）。

```bash
./gradlew setupLibrary   # 从 MTR 4.0.5 发行 jar 提取 org.mtr.* 与重定位 fastutil 到 libs/mtr-compile.jar
./gradlew build          # 产物 build/libs/mtrrealrail-1.5.0+1.20.1.jar
```

> Windows 下若遇到 `Could not initialize native services`，把 `GRADLE_USER_HOME` 指到可写目录即可。
> 自检：服务端加 `-Dmtrrealrail.verifyMixins=true`，启动时会强制加载全部 Mixin 目标类，注入失败直接报错。

---

## 兼容性

- 目标：Minecraft 1.20.1 / Forge 47.4.23 / MTR 4.0.5（Forge）。
- 只依赖 `org.mtr.core` 公开成员与少数 MTR 自有方法名（`init2`、`writePlatformCache` 等）；MTR 大版本改名时主 Mixin 配置会直接报错提示。
- 客户端界面 Mixin 位于 `required=false` 配置：注入失败只记日志，不影响启动。
- 与其它 MTR 附属共存良好；配合两件套时由它们负责规划与执行，本模组提供策略、界面与状态展示。

## 目录结构

```
build.gradle / settings.gradle / gradle.properties   Gradle 工程
src/main/java/mtr/realrail/                          主模组（限速、信号隔离、调度、网络、界面）
src/main/java/mtr/realrail/mixin/                    Mixin（Vehicle / Rail / PathData / 界面注入）
src/main/java/mtr/realrail/traffic/                  动态占用、发车避让、站台调度、车厂中间调度
src/main/java/mtr/realrail/dispatch/                 调度链客户端（tsc-dispatch / 执行桥）
docs/                                                设计、实现与联调记录
dist/                                                发布 jar
```

## 更新日志

### v1.5.0
- 新增：车厂「优化线路配置」开关 **车厂在线路中间**（最近车站派车 / 无班次回场 / 切换自动清车刷新路线）。
- 新增：运行时改停站台 —— 经 `set_platform` 由执行桥重新生成进路，解除"路径不可中途改写"限制。
- 修复：车厂配置表头被当作数据行导致整份配置读取失败。
- 修复：列车被无限期压在站台（车门不关）—— 前后车判定与决策表重写。
- 修复：车站界面 Mixin 含非 private static 方法导致整体不生效；界面重初始化死循环。
- 新增：车站站台智能调度（车站开关 + 线路允许集合多选 GUI + 动态分配）。
- 新增：动态轨道占用 / 自动最优路径规划 / 动态发车避让 + 车厂优化配置 GUI（v1.1.0）。

## 许可

MIT，见 [LICENSE](LICENSE)。
