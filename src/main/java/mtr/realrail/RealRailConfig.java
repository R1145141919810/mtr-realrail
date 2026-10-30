package mtr.realrail;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * 全局配置（config/mtrrealrail.properties）。
 *
 * <p>配置在服务端启动时读取；运行中修改需重启（与 MTR 自身配置行为一致）。</p>
 */
public final class RealRailConfig {

	public static final RealRailConfig INSTANCE = new RealRailConfig();

	/** 轨道节点限速变更的判定方式。 */
	public enum NodeMode {
		/** 车头进入新轨道段立即变更速度（MTR 原版行为）。 */
		HEAD,
		/** 车尾完全经过轨道连接器后才变更速度（本模组默认，需求一）。 */
		TAIL
	}

	/** 未被刷子单独配置的节点所使用的默认判定方式（需求一：默认车尾判定）。 */
	public NodeMode defaultNodeMode = NodeMode.TAIL;

	/** 是否启用多信号系统隔离（需求二）。 */
	public boolean signalIsolation = true;

	/**
	 * 新增调度功能（动态轨道占用 / 自动最优路径规划 / 动态发车避让）的全局默认值。
	 * 每个车厂可在"铁路仪表板 → 车厂 → 优化线路配置"中单独覆盖
	 * （见 {@code mtr.realrail.traffic.OptimizeConfigStore}）。
	 */
	public mtr.realrail.traffic.OptimizeSettings optimizeDefaults = mtr.realrail.traffic.OptimizeSettings.defaults();

	private static final String KEY_DEFAULT_NODE_MODE = "default_node_mode";
	private static final String KEY_SIGNAL_ISOLATION = "signal_isolation";
	private static final String KEY_OPT_DYNAMIC_BLOCK = "optimize_dynamic_block_release";
	private static final String KEY_OPT_SAFETY_MARGIN = "optimize_safety_margin_meters";
	private static final String KEY_OPT_ROUTE = "optimize_route_enabled";
	private static final String KEY_OPT_LOOKAHEAD = "optimize_path_lookahead_meters";
	private static final String KEY_OPT_GUARD = "optimize_departure_guard";
	private static final String KEY_OPT_DWELL = "optimize_dwell_threshold_seconds";
	private static final String KEY_OPT_WINDOW = "optimize_conflict_window_seconds";
	private static final String KEY_OPT_HOLD = "optimize_max_hold_seconds";

	// ---- 车站站台智能调度：依赖外置调度服务 tsc-dispatch 1.0.0 ----
	/** 是否启用 tsc-dispatch 集成（关闭后站台智能调度整体不可用）。 */
	public boolean tscDispatchEnabled = true;
	/** tsc-dispatch 管理接口地址（默认本机 8787）。 */
	public String tscDispatchUrl = "http://127.0.0.1:8787";
	/** 轮询间隔（秒）。 */
	public int tscDispatchPollSeconds = 2;
	/** 是否"必须依赖"：为 true 时本地不再自行选台，选台决策完全来自 tsc-dispatch。 */
	public boolean tscDispatchRequired = true;
	/** 维度序号（tsc-dispatch 的 --dimension，默认 0）。 */
	public int tscDispatchDimension = 0;
	/** HTTP 超时（毫秒）。 */
	public int tscDispatchTimeoutMillis = 1200;
	/** 导出平台允许集合的 dispatch.yml 片段路径（相对服务器根目录，可为空=不导出）。 */
	public String tscDispatchExportPath = "../tsc-dispatch/config/dispatch.generated.yml";

	// ---- 执行桥 mtbridge-2.0.1+1.20.1（由 tsc-dispatch 间接驱动）----
	/** 执行桥地址（mtbridge 默认 8889）。 */
	public String bridgeUrl = "http://127.0.0.1:8889";
	/** 执行桥令牌；留空则自动读取 config/mtbridge.json 中的 token。 */
	public String bridgeToken = "";
	/** 执行桥探测间隔（秒）。 */
	public int bridgeProbeSeconds = 5;
	/** 是否把执行桥地址推送给 tsc-dispatch（bridge.base_url），让它把指令下发到桥。 */
	public boolean bridgePushEnabled = true;
	/** 是否要求"完整执行链"（tsc-dispatch + mtbridge 都在线）才执行站台调度；否则本地兜底。 */
	public boolean executionChainRequired = true;

	private static final String KEY_BRIDGE_URL = "bridge_url";
	private static final String KEY_BRIDGE_PROBE = "bridge_probe_seconds";
	private static final String KEY_BRIDGE_PUSH = "bridge_push_enabled";
	private static final String KEY_CHAIN_REQUIRED = "execution_chain_required";

	private static final String KEY_TSC_ENABLED = "tsc_dispatch_enabled";
	private static final String KEY_TSC_URL = "tsc_dispatch_url";
	private static final String KEY_TSC_POLL = "tsc_dispatch_poll_seconds";
	private static final String KEY_TSC_REQUIRED = "tsc_dispatch_required";
	private static final String KEY_TSC_DIMENSION = "tsc_dispatch_dimension";
	private static final String KEY_TSC_TIMEOUT = "tsc_dispatch_timeout_ms";
	private static final String KEY_TSC_EXPORT = "tsc_dispatch_export_path";

	private RealRailConfig() {
	}

	/** 加载配置；文件不存在或损坏时保留当前值（首次运行为默认值）。 */
	public void load(Path file) {
		if (file == null || !Files.isRegularFile(file)) {
			return;
		}
		final Properties properties = new Properties();
		try (final var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			properties.load(reader);
		} catch (IOException exception) {
			MtrRealRail.LOGGER.warn("[{}] 读取配置失败：{}", MtrRealRail.MOD_ID, exception.getMessage());
			return;
		}

		try {
			defaultNodeMode = NodeMode.valueOf(properties.getProperty(KEY_DEFAULT_NODE_MODE, "TAIL").trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException ignored) {
			defaultNodeMode = NodeMode.TAIL;
		}
		signalIsolation = Boolean.parseBoolean(properties.getProperty(KEY_SIGNAL_ISOLATION, "true").trim());

		final mtr.realrail.traffic.OptimizeSettings optimize = mtr.realrail.traffic.OptimizeSettings.defaults();
		optimize.dynamicBlockRelease = Boolean.parseBoolean(properties.getProperty(KEY_OPT_DYNAMIC_BLOCK, Boolean.toString(optimize.dynamicBlockRelease)).trim());
		optimize.safetyMarginMeters = parseDouble(properties, KEY_OPT_SAFETY_MARGIN, optimize.safetyMarginMeters);
		optimize.routeOptimize = Boolean.parseBoolean(properties.getProperty(KEY_OPT_ROUTE, Boolean.toString(optimize.routeOptimize)).trim());
		optimize.pathLookaheadMeters = parseDouble(properties, KEY_OPT_LOOKAHEAD, optimize.pathLookaheadMeters);
		optimize.departureGuard = Boolean.parseBoolean(properties.getProperty(KEY_OPT_GUARD, Boolean.toString(optimize.departureGuard)).trim());
		optimize.dwellThresholdSeconds = parseInt(properties, KEY_OPT_DWELL, optimize.dwellThresholdSeconds);
		optimize.conflictWindowSeconds = parseInt(properties, KEY_OPT_WINDOW, optimize.conflictWindowSeconds);
		optimize.maxHoldSeconds = parseInt(properties, KEY_OPT_HOLD, optimize.maxHoldSeconds);
		optimizeDefaults = optimize;

		tscDispatchEnabled = Boolean.parseBoolean(properties.getProperty(KEY_TSC_ENABLED, Boolean.toString(tscDispatchEnabled)).trim());
		tscDispatchUrl = properties.getProperty(KEY_TSC_URL, tscDispatchUrl).trim();
		tscDispatchPollSeconds = Math.max(1, parseInt(properties, KEY_TSC_POLL, tscDispatchPollSeconds));
		tscDispatchRequired = Boolean.parseBoolean(properties.getProperty(KEY_TSC_REQUIRED, Boolean.toString(tscDispatchRequired)).trim());
		tscDispatchDimension = Math.max(0, parseInt(properties, KEY_TSC_DIMENSION, tscDispatchDimension));
		tscDispatchTimeoutMillis = Math.max(200, parseInt(properties, KEY_TSC_TIMEOUT, tscDispatchTimeoutMillis));
		tscDispatchExportPath = properties.getProperty(KEY_TSC_EXPORT, tscDispatchExportPath).trim();
		bridgeUrl = properties.getProperty(KEY_BRIDGE_URL, bridgeUrl).trim();
		bridgeToken = properties.getProperty("bridge_token", bridgeToken).trim();
		bridgeProbeSeconds = Math.max(1, parseInt(properties, KEY_BRIDGE_PROBE, bridgeProbeSeconds));
		bridgePushEnabled = Boolean.parseBoolean(properties.getProperty(KEY_BRIDGE_PUSH, Boolean.toString(bridgePushEnabled)).trim());
		executionChainRequired = Boolean.parseBoolean(properties.getProperty(KEY_CHAIN_REQUIRED, Boolean.toString(executionChainRequired)).trim());
	}

	private static double parseDouble(Properties properties, String key, double fallback) {
		try {
			return Double.parseDouble(properties.getProperty(key, Double.toString(fallback)).trim());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static int parseInt(Properties properties, String key, int fallback) {
		try {
			return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)).trim());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	/** 写出配置（带注释，便于玩家手改）。 */
	public void save(Path file) {
		if (file == null) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
		} catch (IOException ignored) {
		}
		try (final PrintWriter writer = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
			writer.println("# 更好的列车限速及信号判定（MTR RealRail）配置");
			writer.println("#");
			writer.println("# default_node_mode: TAIL = 车尾完全经过轨道连接器后才变更速度（默认，需求一）");
			writer.println("#                    HEAD = 车头进入新轨道段立即变更速度（MTR 原版行为）");
			writer.println("# 单个轨道节点可用刷子（mtr:brush）右击轨道节点方块：先打开 MTR 原版轨道界面，");
			writer.println("# 点击其中的“节点限速判定”按钮即可单独覆盖此设置。");
			writer.println(KEY_DEFAULT_NODE_MODE + "=" + defaultNodeMode.name());
			writer.println("#");
			writer.println("# signal_isolation: true = 16 色多信号系统各自独立判定区间占用，互不干扰（需求二）");
			writer.println(KEY_SIGNAL_ISOLATION + "=" + signalIsolation);
			writer.println("#");
			writer.println("# ---- 新增调度功能（可在\"铁路仪表板 -> 车厂 -> 优化线路配置\"中按车厂覆盖）----");
			writer.println("# " + KEY_OPT_DYNAMIC_BLOCK + ": true = 同一信号系统内按实时车距做移动闭塞放行（动态轨道占用）");
			writer.println(KEY_OPT_DYNAMIC_BLOCK + "=" + optimizeDefaults.dynamicBlockRelease);
			writer.println("# " + KEY_OPT_SAFETY_MARGIN + ": 动态闭塞安全间距（米）");
			writer.println(KEY_OPT_SAFETY_MARGIN + "=" + optimizeDefaults.safetyMarginMeters);
			writer.println("# " + KEY_OPT_ROUTE + ": true = 自动最优路径规划（进路打分 + 必要时重新生成车厂路径）");
			writer.println(KEY_OPT_ROUTE + "=" + optimizeDefaults.routeOptimize);
			writer.println("# " + KEY_OPT_LOOKAHEAD + ": 进路打分 / 冲突检测前瞻距离（米）");
			writer.println(KEY_OPT_LOOKAHEAD + "=" + optimizeDefaults.pathLookaheadMeters);
			writer.println("# " + KEY_OPT_GUARD + ": true = 动态发车与避让（停站满阈值后按实时交通决定等待/发车）");
			writer.println(KEY_OPT_GUARD + "=" + optimizeDefaults.departureGuard);
			writer.println("# " + KEY_OPT_DWELL + ": 停站达到该秒数后才开始做发车决策");
			writer.println(KEY_OPT_DWELL + "=" + optimizeDefaults.dwellThresholdSeconds);
			writer.println("# " + KEY_OPT_WINDOW + ": 冲突窗口（秒），同信号系统列车在该时间内到达则考虑让行");
			writer.println(KEY_OPT_WINDOW + "=" + optimizeDefaults.conflictWindowSeconds);
			writer.println("# " + KEY_OPT_HOLD + ": 最长等待让行时间（秒），超过则强制发车并重规划");
			writer.println(KEY_OPT_HOLD + "=" + optimizeDefaults.maxHoldSeconds);
		} catch (IOException exception) {
			MtrRealRail.LOGGER.warn("[{}] 写出配置失败：{}", MtrRealRail.MOD_ID, exception.getMessage());
		}
	}
}
