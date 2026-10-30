package mtr.realrail.dispatch;

import mtr.realrail.MtrRealRail;
import mtr.realrail.RealRailConfig;

/**
 * 通过 tsc-dispatch 的 {@code POST /v1/command} 下发逐车指令，由其转发给执行桥 mtbridge。
 *
 * <p>这样"改停站台 / 派车 / 回场"都由执行桥在服务器线程上真正落地，
 * 本模组不需要（也无法）直接改写运行中列车的进路——这正是解除
 * "immutablePath 运行中不可替换"限制的方式。</p>
 */
public final class TscDispatchCommands {

	private TscDispatchCommands() {
	}

	/**
	 * 下发一条指令。
	 *
	 * @param kind     指令类型（{@code set_platform} / {@code deploy_train} / {@code return_to_depot} / {@code dwell_control}）
	 * @param vehicleId 车辆 id（十六进制）
	 * @param target   目标（站台十六进制 / 车厂 id，可为空）
	 * @param reason   决策原因（写入 tsc-dispatch 的决策日志）
	 * @return 是否已成功提交（不保证执行成功，执行结果见 tsc-dispatch 的决策/日志）
	 */
	public static boolean send(String kind, String vehicleId, String target, String reason) {
		if (!TscDispatchBridge.isChainActive()) {
			return false;
		}
		final String base = stripTrailingSlash(RealRailConfig.INSTANCE.tscDispatchUrl);
		final int timeout = Math.max(300, RealRailConfig.INSTANCE.tscDispatchTimeoutMillis);
		final String body = "{\"kind\":\"" + kind + "\",\"vehicleId\":\"" + vehicleId + "\","
				+ "\"args\":{"
				+ (target == null || target.isEmpty() ? "" : "\"platform\":\"" + target + "\"")
				+ "},\"reason\":\"" + reason + "\","
				+ "\"key\":\"" + kind + "|" + vehicleId + "|" + (System.currentTimeMillis() / 60_000L) + "\"}";
		try {
			final String response = TscDispatchClient.post(base + "/v1/command", body, timeout);
			MtrRealRail.LOGGER.info("[{}] 已下发调度指令 {} → 车辆 {}（{}）：{}", MtrRealRail.MOD_ID, kind, vehicleId, reason, response);
			return true;
		} catch (Exception exception) {
			MtrRealRail.LOGGER.debug("[{}] 下发调度指令失败 {} → {}：{}", MtrRealRail.MOD_ID, kind, vehicleId, exception.toString());
			return false;
		}
	}

	private static String stripTrailingSlash(String url) {
		final String trimmed = url == null ? "" : url.trim();
		return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
	}
}
