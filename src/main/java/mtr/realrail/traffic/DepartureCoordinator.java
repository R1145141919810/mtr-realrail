package mtr.realrail.traffic;

import mtr.realrail.MtrRealRail;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>动态发车与避让</b>：停站列车在达到"停站阈值"后，按实时交通决定是否等待让行。
 *
 * <p>决策表（刻意做成"默认发车"的显式规则，避免任何情况下把列车压死在站台）：</p>
 * <ol>
 *   <li><b>前方没有真正冲突</b>（同信号系统内、间距小于"制动距离 + 安全间距"的列车不存在）→ <b>立即发车</b>；</li>
 *   <li>前方有冲突，但<b>后方有车正在逼近</b>（冲突窗口内到站）→ <b>立即发车让出站台</b>（冲突交给 MTR 自身的信号/物理制动处理）；</li>
 *   <li>刚刚释放过（冷却期内）→ 直接发车，避免"压站—放行—再压站"的抖动；</li>
 *   <li>只有"前方有冲突且后方无车"时才等待避让，且<b>最多等待 maxHoldSeconds</b>，
 *       超时强制发车（必要时重新生成车厂路径，即"变更路径"）。</li>
 * </ol>
 *
 * <p>等待通过把 MTR 的停站计时（{@code VehicleSchema.elapsedDwellTime}）压在"关门时刻"之前实现，
 * 放行后不再写该字段，MTR 立即恢复原版发车流程。</p>
 *
 * <p>计时使用<b>墙钟时间</b>（{@code System.currentTimeMillis()}），不依赖游戏内时钟，
 * 因此即使伺服/暂停/时间跳变也不会出现"永远等下去"。</p>
 */
public final class DepartureCoordinator {

	public enum Decision {
		/** 正常发车（无需干预）。 */
		DEPART,
		/** 等待避让（通过压住停站计时实现）。 */
		HOLD,
		/** 等待超时后强制发车，并要求重新生成车厂路径。 */
		REPLAN_AND_DEPART,
		/** 功能关闭。 */
		DISABLED
	}

	private record HoldState(long startMillis, String reason) {
	}

	/** 释放后的冷却时间：这段时间内不再压站，保证列车一定能开出去。 */
	private static final long HOLD_COOLDOWN_MILLIS = 20_000L;
	/** 日志节流：同一列车同一类日志的最小间隔。 */
	private static final long LOG_THROTTLE_MILLIS = 30_000L;

	private static final Map<Long, HoldState> HOLD_STATES = new ConcurrentHashMap<>();
	private static final Map<Long, Long> HOLD_COOLDOWN_UNTIL = new ConcurrentHashMap<>();
	private static final Map<Long, String> LAST_REASON = new ConcurrentHashMap<>();
	private static final Map<String, Long> LAST_LOG_MILLIS = new ConcurrentHashMap<>();

	private DepartureCoordinator() {
	}

	/**
	 * 每 tick 对每辆车评估一次（由 {@code VehicleSimulateMixin} 调用）。
	 *
	 * @param nowMillis 墙钟毫秒（{@code System.currentTimeMillis()}）
	 */
	public static Decision evaluate(VehicleSnapshot self, OptimizeSettings settings, long nowMillis) {
		if (!settings.departureGuard) {
			return release(self.vehicleId, nowMillis, "发车避让已关闭", Decision.DISABLED);
		}

		// 非停站 / 无停站时长：不干预（列车本来就会走）
		if (self.totalDwellMillis <= 0 || !self.isStopped()) {
			return release(self.vehicleId, nowMillis, "非站台停站，按原版逻辑", Decision.DEPART);
		}

		// 停站未达阈值（任务场景为 10 秒）：保持 MTR 原行为
		if (!self.dwellReached(settings.dwellThresholdMillis())) {
			return release(self.vehicleId, nowMillis, "停站未满 " + settings.dwellThresholdSeconds + " 秒", Decision.DEPART);
		}

		final boolean conflictAhead = TrafficEvaluator.hasConflictAhead(self, settings.safetyMarginMeters, settings.pathLookaheadMeters);
		final boolean followerApproaching = TrafficEvaluator.hasFollowerApproaching(self, settings.conflictWindowMillis(), settings.pathLookaheadMeters);

		// 规则 1：前方无冲突 → 立即发车（这条规则保证绝不会无故压站）
		if (!conflictAhead) {
			return release(self.vehicleId, nowMillis, followerApproaching
					? "前方无冲突、后方有车逼近：发车让出站台"
					: "前方无冲突：正常发车", Decision.DEPART);
		}

		// 规则 2：前方有冲突但后方有车逼近 → 让出站台优先
		if (followerApproaching) {
			logThrottled(self.vehicleId, nowMillis, "depart-with-follower",
					"列车 " + self.vehicleId + " 前方存在间距不足的列车，但后方有车即将到站：发车让出站台。"
							+ TrafficEvaluator.describeTraffic(self, settings.safetyMarginMeters, settings.conflictWindowMillis(), settings.pathLookaheadMeters));
			return release(self.vehicleId, nowMillis, "前方有冲突但后方有车逼近：发车让出站台", Decision.DEPART);
		}

		// 规则 3：冷却期内不再压站
		final long cooldownUntil = HOLD_COOLDOWN_UNTIL.getOrDefault(self.vehicleId, 0L);
		if (nowMillis < cooldownUntil) {
			return release(self.vehicleId, nowMillis, "刚释放过（冷却 " + ((cooldownUntil - nowMillis) / 1000) + " 秒）：直接发车", Decision.DEPART);
		}

		// 规则 4：等待避让（有上限，超时强制发车）
		final HoldState state = HOLD_STATES.get(self.vehicleId);
		final long heldMillis = state == null ? 0 : Math.max(0, nowMillis - state.startMillis());

		if (state != null && heldMillis >= settings.maxHoldMillis()) {
			HOLD_STATES.remove(self.vehicleId);
			HOLD_COOLDOWN_UNTIL.put(self.vehicleId, nowMillis + HOLD_COOLDOWN_MILLIS);
			LAST_REASON.put(self.vehicleId, "等待已达上限 " + settings.maxHoldSeconds + " 秒：强制发车"
					+ (settings.routeOptimize ? "，并重新生成车厂路径" : ""));
			logThrottled(self.vehicleId, nowMillis, "hold-timeout",
					"列车 " + self.vehicleId + " 等待避让超过 " + settings.maxHoldSeconds + " 秒，强制发车。"
							+ TrafficEvaluator.describeTraffic(self, settings.safetyMarginMeters, settings.conflictWindowMillis(), settings.pathLookaheadMeters));
			return settings.routeOptimize ? Decision.REPLAN_AND_DEPART : Decision.DEPART;
		}

		if (state == null) {
			final String reason = "前方存在间距不足的列车，等待避让";
			HOLD_STATES.put(self.vehicleId, new HoldState(nowMillis, reason));
			logThrottled(self.vehicleId, nowMillis, "hold-start",
					"列车 " + self.vehicleId + " 开始等待避让（最多 " + settings.maxHoldSeconds + " 秒）。"
							+ TrafficEvaluator.describeTraffic(self, settings.safetyMarginMeters, settings.conflictWindowMillis(), settings.pathLookaheadMeters));
		}
		LAST_REASON.put(self.vehicleId, "前方存在间距不足的列车，等待避让（已等待 " + (heldMillis / 1000)
				+ " 秒 / 上限 " + settings.maxHoldSeconds + " 秒）");
		return Decision.HOLD;
	}

	/** 统一释放：清掉等待状态并记录冷却，保证列车能真正开出去。 */
	private static Decision release(long vehicleId, long nowMillis, String reason, Decision decision) {
		if (HOLD_STATES.remove(vehicleId) != null) {
			HOLD_COOLDOWN_UNTIL.put(vehicleId, nowMillis + HOLD_COOLDOWN_MILLIS);
		}
		LAST_REASON.put(vehicleId, reason);
		return decision;
	}

	private static void logThrottled(long vehicleId, long nowMillis, String key, String message) {
		final String logKey = vehicleId + ":" + key;
		final Long last = LAST_LOG_MILLIS.get(logKey);
		if (last != null && nowMillis - last < LOG_THROTTLE_MILLIS) {
			return;
		}
		LAST_LOG_MILLIS.put(logKey, nowMillis);
		MtrRealRail.LOGGER.info("[{}] {}", MtrRealRail.MOD_ID, message);
	}

	/** 该车当前是否应被压住停站计时（true = 不放行发车）。 */
	public static boolean shouldHold(long vehicleId) {
		return HOLD_STATES.containsKey(vehicleId);
	}

	public static long heldMillis(long vehicleId, long nowMillis) {
		final HoldState state = HOLD_STATES.get(vehicleId);
		return state == null ? 0 : Math.max(0, nowMillis - state.startMillis());
	}

	public static String reason(long vehicleId) {
		return LAST_REASON.getOrDefault(vehicleId, "");
	}

	/** 列车被移除/不再停站时清理状态。 */
	public static void forget(long vehicleId) {
		HOLD_STATES.remove(vehicleId);
		LAST_REASON.remove(vehicleId);
		HOLD_COOLDOWN_UNTIL.remove(vehicleId);
	}

	public static void clear() {
		HOLD_STATES.clear();
		HOLD_COOLDOWN_UNTIL.clear();
		LAST_REASON.clear();
		LAST_LOG_MILLIS.clear();
	}
}
