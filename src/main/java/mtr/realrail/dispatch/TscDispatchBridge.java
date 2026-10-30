package mtr.realrail.dispatch;

import mtr.realrail.MtrRealRail;
import mtr.realrail.RealRailConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * tsc-dispatch 的本地视图：周期性拉取它的站台/决策，供游戏内执行使用。
 *
 * <p>拉取的两个只读接口（tsc-dispatch 1.0.0 管理 API，默认 {@code 127.0.0.1:8787}）：</p>
 * <ul>
 *   <li>{@code GET /v1/platforms?dimension=N} —— 站台 + 租约 + 允许集合；</li>
 *   <li>{@code GET /v1/decisions?dimension=N&limit=…} —— 最近的调度决策（含逐车指令）。</li>
 * </ul>
 *
 * <p>解析一律<b>宽松</b>：字段名多种写法都接受，解析失败不抛异常，只更新状态，
 * 从而保证 tsc-dispatch 升级/字段调整不会把服务器搞崩。</p>
 */
public final class TscDispatchBridge {

	/** 一条逐车决策。 */
	public record VehicleDecision(long atMillis, String vehicleId, String type, String commandKind, String platformHex, String status) {
	}

	private static volatile boolean online;
	private static volatile long lastSuccessMillis;
	private static volatile String lastError = "";
	private static volatile boolean started;
	private static volatile int lastPlatformCount;
	private static volatile int lastDecisionCount;

	/** 执行桥（mtbridge）状态。 */
	private static volatile boolean bridgeOnline;
	private static volatile boolean bridgeReady;
	private static volatile String bridgeFeatures = "";
	private static volatile String pushedBridgeUrl = "";
	private static volatile String bridgeToken = "";

	/** 车辆 id → 最近一次决策（后到的覆盖先到的）。 */
	private static final Map<String, VehicleDecision> DECISIONS = new ConcurrentHashMap<>();
	/** 站台 hex → 是否被租用/占用（tsc-dispatch 视角）。 */
	private static final Map<String, Boolean> PLATFORM_BUSY = new ConcurrentHashMap<>();

	private TscDispatchBridge() {
	}

	public static void start() {
		if (started) {
			return;
		}
		started = true;
		TscDispatchClient.schedule(TscDispatchBridge::poll, 2_000L,
				Math.max(1, RealRailConfig.INSTANCE.tscDispatchPollSeconds) * 1000L);
		MtrRealRail.LOGGER.info("[{}] 已启动 tsc-dispatch 集成（{}，轮询 {} 秒）", MtrRealRail.MOD_ID,
				RealRailConfig.INSTANCE.tscDispatchUrl, Math.max(1, RealRailConfig.INSTANCE.tscDispatchPollSeconds));
	}

	public static boolean isEnabled() {
		return RealRailConfig.INSTANCE.tscDispatchEnabled;
	}

	/** 是否要求必须依赖 tsc-dispatch（true 时本地不再自行选台）。 */
	public static boolean isRequired() {
		return RealRailConfig.INSTANCE.tscDispatchRequired;
	}

	public static boolean isOnline() {
		return online;
	}

	public static String statusText() {
		if (!isEnabled()) {
			return "tsc-dispatch 已关闭";
		}
		if (online) {
			return "tsc-dispatch 已连接（站台 " + lastPlatformCount + "，决策 " + DECISIONS.size() + "）";
		}
		return "tsc-dispatch 未连接" + (lastError.isEmpty() ? "" : "（" + lastError + "）");
	}

	/** 该车辆最近一次的"改停站台"决策；没有则返回 null。 */
	public static VehicleDecision decisionFor(String vehicleId) {
		return DECISIONS.get(vehicleId);
	}

	/** 以游戏内的 long 车辆 id 查询决策（自动尝试十进制/十六进制/0x 三种写法）。 */
	public static VehicleDecision decisionFor(long vehicleId) {
		final String hex = Long.toHexString(vehicleId);
		return decisionForOrNull(String.valueOf(vehicleId), hex, "0x" + hex, hex.toUpperCase(Locale.ROOT));
	}

	private static VehicleDecision decisionForOrNull(String... keys) {
		for (final String key : keys) {
			final VehicleDecision decision = DECISIONS.get(key);
			if (decision != null) {
				return decision;
			}
		}
		return null;
	}

	public static Map<String, Boolean> platformBusy() {
		return PLATFORM_BUSY;
	}

	public static int decisionCount() {
		return DECISIONS.size();
	}

	public static boolean isBridgeOnline() {
		return bridgeOnline;
	}

	public static boolean isBridgeReady() {
		return bridgeOnline && bridgeReady;
	}

	public static String bridgeFeatures() {
		return bridgeFeatures;
	}

	/** 执行链是否完整：tsc-dispatch（规划）+ mtbridge（执行）都在线。 */
	public static boolean isChainActive() {
		return online && isBridgeReady();
	}

	public static String chainStatusText() {
		if (!isEnabled()) {
			return "执行链已关闭";
		}
		final String planner = online ? "tsc-dispatch 已连接" : "tsc-dispatch 未连接";
		final String actuator = bridgeOnline
				? ("执行桥 mtbridge 已连接" + (bridgeFeatures.isEmpty() ? "" : "（" + bridgeFeatures + "）"))
				: "执行桥 mtbridge 未连接";
		return planner + " / " + actuator;
	}

	/** 清空缓存（换世界/服务器关闭时调用）。 */
	public static void clear() {
		DECISIONS.clear();
		PLATFORM_BUSY.clear();
		online = false;
		bridgeOnline = false;
		bridgeReady = false;
		bridgeFeatures = "";
		pushedBridgeUrl = "";
		lastSuccessMillis = 0L;
		lastError = "";
	}

	// ------------------------------------------------------------------ 轮询

	private static void poll() {
		if (!isEnabled()) {
			online = false;
			return;
		}
		final String base = stripTrailingSlash(RealRailConfig.INSTANCE.tscDispatchUrl);
		final int dimension = RealRailConfig.INSTANCE.tscDispatchDimension;
		final int timeout = Math.max(300, RealRailConfig.INSTANCE.tscDispatchTimeoutMillis);
		try {
			final String platforms = TscDispatchClient.get(base + "/v1/platforms?dimension=" + dimension, timeout);
			lastPlatformCount = parsePlatforms(platforms);
			final String decisions = TscDispatchClient.get(base + "/v1/decisions?dimension=" + dimension + "&limit=50", timeout);
			lastDecisionCount = parseDecisions(decisions);
			final boolean wasOffline = !online;
			online = true;
			lastSuccessMillis = System.currentTimeMillis();
			lastError = "";
			if (wasOffline) {
				MtrRealRail.LOGGER.info("[{}] 已连接 tsc-dispatch：{}（站台 {}，决策 {}）", MtrRealRail.MOD_ID, base,
						lastPlatformCount, DECISIONS.size());
			}
			// 把执行桥地址推给规划服务（tsc-dispatch 的 bridge.base_url），使指令下发到 mtbridge
			pushBridgeUrl(base, timeout);
		} catch (Exception exception) {
			online = false;
			lastError = exception.getClass().getSimpleName();
			MtrRealRail.LOGGER.debug("[{}] tsc-dispatch 轮询失败：{}", MtrRealRail.MOD_ID, exception.toString());
		}
	}

	/** 探测执行桥并（首次/地址变化时）把 bridge.base_url 推给 tsc-dispatch。 */
	private static void pushBridgeUrl(String base, int timeout) {
		probeBridge(timeout);
		if (!RealRailConfig.INSTANCE.bridgePushEnabled) {
			return;
		}
		final String bridgeUrl = RealRailConfig.INSTANCE.bridgeUrl;
		if (bridgeUrl == null || bridgeUrl.isBlank() || bridgeUrl.equals(pushedBridgeUrl)) {
			return;
		}
		try {
			final String token = bridgeToken();
			final String response = TscDispatchClient.post(base + "/v1/config",
					"{\"bridge.base_url\":\"" + bridgeUrl + "\""
							+ (token.isEmpty() ? "" : ",\"bridge.auth_token\":\"" + token + "\"") + "}", timeout);
			pushedBridgeUrl = bridgeUrl;
			MtrRealRail.LOGGER.info("[{}] 已把执行桥地址与令牌推送给 tsc-dispatch：{}（令牌 {}）→ {}", MtrRealRail.MOD_ID,
					bridgeUrl, token.isEmpty() ? "未配置" : "已提供", response);
		} catch (Exception exception) {
			MtrRealRail.LOGGER.debug("[{}] 推送执行桥地址失败：{}", MtrRealRail.MOD_ID, exception.toString());
		}
	}

	/** 探测 mtbridge：优先 /dispatch/v1/capabilities，退化为 /dispatch/v1/health。 */
	private static void probeBridge(int timeout) {
		final String bridgeUrl = stripTrailingSlash(RealRailConfig.INSTANCE.bridgeUrl);
		if (bridgeUrl.isEmpty()) {
			bridgeOnline = false;
			return;
		}
		try {
			final String capabilities = TscDispatchClient.get(bridgeUrl + "/dispatch/v1/capabilities", timeout, bridgeToken());
			bridgeFeatures = featureList(capabilities);
			bridgeReady = capabilities.replace(" ", "").contains("\"ready\":true");
			final boolean wasBridgeOffline = !bridgeOnline;
			bridgeOnline = true;
			if (wasBridgeOffline) {
				MtrRealRail.LOGGER.info("[{}] 执行桥 mtbridge 已连接：{}（能力：{}，ready={}）", MtrRealRail.MOD_ID,
						bridgeUrl, bridgeFeatures, bridgeReady);
			}
		} catch (Exception capabilitiesFailure) {
			try {
				TscDispatchClient.get(bridgeUrl + "/dispatch/v1/health", timeout, bridgeToken());
				bridgeOnline = true;
				bridgeReady = false;
				bridgeFeatures = "";
			} catch (Exception healthFailure) {
				bridgeOnline = false;
				bridgeReady = false;
			}
		}
	}

	/**
	 * 执行桥令牌：优先用本模组配置 {@code bridge_token}，否则自动读取 mtbridge 的
	 * {@code config/mtbridge.json} 中的 token（mtbridge 默认启用令牌认证，缺了会 401）。
	 */
	public static String bridgeToken() {
		final String configured = RealRailConfig.INSTANCE.bridgeToken;
		if (configured != null && !configured.isBlank()) {
			return configured.trim();
		}
		if (!bridgeToken.isEmpty()) {
			return bridgeToken;
		}
		try {
			final String json = java.nio.file.Files.readString(java.nio.file.Path.of("config", "mtbridge.json"));
			final java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"token\"\\s*:\\s*\"([^\"]*)\"")
					.matcher(json);
			if (matcher.find()) {
				bridgeToken = matcher.group(1);
			}
		} catch (Exception ignored) {
			// mtbridge 未安装或尚未生成配置
		}
		return bridgeToken;
	}

	/** 从 capabilities 里提取 features 数组（形如 {@code "features":["set_platform",…]}）。 */
	private static String featureList(String json) {
		final int index = json.indexOf("\"features\"");
		if (index < 0) {
			return "";
		}
		final int start = json.indexOf('[', index);
		final int end = start < 0 ? -1 : json.indexOf(']', start);
		if (start < 0 || end < 0) {
			return "";
		}
		return json.substring(start + 1, end).replace("\"", "").replace(" ", "");
	}

	/** 解析平台列表；返回识别到的站台数量。兼容 {platforms:[…]} 与 {data:[…]} 两种包装。 */
	static int parsePlatforms(String json) {
		final List<Map<String, Object>> items = items(json, "platforms");
		final Map<String, Boolean> next = new LinkedHashMap<>();
		for (final Map<String, Object> item : items) {
			final String hex = firstString(item, "platform", "platformId", "id", "hex");
			if (hex.isEmpty()) {
				continue;
			}
			final Object leased = first(item, "leased", "leasedBy", "vehicle", "occupiedBy");
			next.put(hex, leased != null && !String.valueOf(leased).isEmpty() && !"null".equals(String.valueOf(leased)));
		}
		PLATFORM_BUSY.clear();
		PLATFORM_BUSY.putAll(next);
		return next.size();
	}

	/** 解析决策列表；返回识别到的决策条数。只保留带逐车指令的条目。 */
	static int parseDecisions(String json) {
		final List<Map<String, Object>> items = items(json, "decisions");
		int count = 0;
		for (final Map<String, Object> item : items) {
			final String vehicleId = firstString(item, "vehicle", "vehicleId");
			if (vehicleId.isEmpty()) {
				continue;
			}
			final Map<String, Object> command = asMap(item.get("command"));
			final String kind = command == null ? "" : firstString(command, "kind", "type");
			final Map<String, Object> args = command == null ? null : asMap(command.get("args"));
			final String platform = args == null ? "" : firstString(args, "platform", "platformHex", "platformId", "to");
			final long at = asLong(first(item, "at", "atMillis", "createdAtMillis"));
			final VehicleDecision decision = new VehicleDecision(at, vehicleId,
					firstString(item, "type", "kind"), kind, platform, firstString(item, "status", "outcome"));
			DECISIONS.merge(vehicleId, decision, (oldValue, newValue) -> oldValue.atMillis() > newValue.atMillis() ? oldValue : newValue);
			count++;
		}
		return count;
	}

	// ------------------------------------------------------------------ 极简 JSON 游走

	/** 取出 JSON 文本里第一个名为 key 的数组（或 data 包装下的数组）中的对象列表。 */
	static List<Map<String, Object>> items(String json, String key) {
		final List<Map<String, Object>> result = new ArrayList<>();
		int index = json.indexOf("\"" + key + "\"");
		if (index < 0) {
			index = json.indexOf("\"data\"");
		}
		if (index < 0) {
			return result;
		}
		final int start = json.indexOf('[', index);
		if (start < 0) {
			return result;
		}
		int depth = 0;
		int objectStart = -1;
		boolean inString = false;
		boolean escape = false;
		for (int i = start; i < json.length(); i++) {
			final char c = json.charAt(i);
			if (inString) {
				if (escape) {
					escape = false;
				} else if (c == '\\') {
					escape = true;
				} else if (c == '"') {
					inString = false;
				}
				continue;
			}
			if (c == '"') {
				inString = true;
			} else if (c == '{') {
				if (depth == 0) {
					objectStart = i;
				}
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0 && objectStart >= 0) {
					result.add(parseFlatObject(json.substring(objectStart, i + 1)));
					objectStart = -1;
				}
			} else if (c == ']' && depth == 0) {
				break;
			}
		}
		return result;
	}

	/** 解析"对象里的一层键值"（嵌套对象只保留为 Map，便于取 command/args）。 */
	static Map<String, Object> parseFlatObject(String text) {
		final Map<String, Object> map = new LinkedHashMap<>();
		int i = 0;
		final int length = text.length();
		while (i < length) {
			final int keyStart = text.indexOf('"', i);
			if (keyStart < 0) {
				break;
			}
			final int keyEnd = text.indexOf('"', keyStart + 1);
			if (keyEnd < 0) {
				break;
			}
			final String key = text.substring(keyStart + 1, keyEnd);
			int colon = text.indexOf(':', keyEnd);
			if (colon < 0) {
				break;
			}
			int valueStart = colon + 1;
			while (valueStart < length && Character.isWhitespace(text.charAt(valueStart))) {
				valueStart++;
			}
			if (valueStart >= length) {
				break;
			}
			final char first = text.charAt(valueStart);
			if (first == '{' || first == '[') {
				final int end = matchEnd(text, valueStart);
				if (end < 0) {
					break;
				}
				final String inner = text.substring(valueStart, end + 1);
				map.put(key, first == '{' ? parseFlatObject(inner) : inner);
				i = end + 1;
			} else if (first == '"') {
				final int end = text.indexOf('"', valueStart + 1);
				if (end < 0) {
					break;
				}
				map.put(key, text.substring(valueStart + 1, end));
				i = end + 1;
			} else {
				int end = valueStart;
				while (end < length && ",}] \n\r\t".indexOf(text.charAt(end)) < 0) {
					end++;
				}
				map.put(key, String.valueOf(text.substring(valueStart, end).trim()));
				i = end;
			}
		}
		return map;
	}

	private static int matchEnd(String text, int start) {
		final char open = text.charAt(start);
		final char close = open == '{' ? '}' : ']';
		int depth = 0;
		boolean inString = false;
		boolean escape = false;
		for (int i = start; i < text.length(); i++) {
			final char c = text.charAt(i);
			if (inString) {
				if (escape) {
					escape = false;
				} else if (c == '\\') {
					escape = true;
				} else if (c == '"') {
					inString = false;
				}
				continue;
			}
			if (c == '"') {
				inString = true;
			} else if (c == open) {
				depth++;
			} else if (c == close) {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object value) {
		return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
	}

	private static Object first(Map<String, Object> map, String... keys) {
		for (final String key : keys) {
			final Object value = map.get(key);
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	private static String firstString(Map<String, Object> map, String... keys) {
		final Object value = first(map, keys);
		return value == null ? "" : String.valueOf(value);
	}

	private static long asLong(Object value) {
		if (value == null) {
			return 0L;
		}
		try {
			return Long.parseLong(String.valueOf(value));
		} catch (NumberFormatException ignored) {
			return 0L;
		}
	}

	private static String stripTrailingSlash(String url) {
		final String trimmed = url == null ? "" : url.trim();
		return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
	}
}
