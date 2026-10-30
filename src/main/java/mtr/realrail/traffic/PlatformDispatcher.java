package mtr.realrail.traffic;

import mtr.realrail.MtrRealRail;
import org.mtr.core.data.Data;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.data.Station;
import org.mtr.core.data.TransportMode;
import org.mtr.core.tool.Utilities;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>车站站台智能调度</b>的服务端实现：站台实时占用 → 进站前引导。
 *
 * <p>数据来源全部是 MTR 自己的对象（无需额外几何计算）：</p>
 * <ul>
 *   <li>车站的站台集合：{@code Station.savedRails}；</li>
 *   <li>站台所属车站：{@code SavedRailBase.area}（{@code Platform.area}）；</li>
 *   <li>列车下一个停靠站台：沿列车路径向前找第一段 {@code dwellTime > 0} 的停车段，
 *       再按该段的端点位置反查站台（{@code Platform.containsPos}）——停车段就是站台段；</li>
 *   <li>站台占用：某辆<b>正在停站</b>的列车，其停车段端点落在该站台上；</li>
 *   <li>信号辅助：只有与本车<b>共享信号颜色</b>（{@link DynamicOccupancy#sharesColor}）的列车
 *       才视为同一信号系统内的占用/冲突，与需求二的隔离语义一致。</li>
 * </ul>
 *
 * <p>决策（每个 tick 对每列车评估一次，由 {@code VehicleSimulateMixin} 调用）：</p>
 * <ol>
 *   <li>目标站台空闲 → 不干预；</li>
 *   <li>目标站台被占，且该（线路, 车站）启用了智能调度、存在<b>允许且空闲</b>的备用站台
 *       → 记录"可改停"决策（路径生成阶段的 {@code RoutePlatformDataMixin} 会把实际使用的站台
 *       约束在允许集合内，因此后续路径生成会自动选择空闲且允许的站台）；</li>
 *   <li>目标站台被占且无空闲备用站台 → 若列车尚未进入该站台（还没开始进站制动到站台段），
 *       则按上限压住它在原地的停站（复用 {@code DepartureCoordinator} 的等待机制），避免进站后堵死。</li>
 * </ol>
 */
public final class PlatformDispatcher {

	/** 列车：等待进站的可选状态。 */
	private static final Map<Long, Long> WAIT_UNTIL = new ConcurrentHashMap<>();
	/** 列车：上一次决策说明（GUI/日志用）。 */
	private static final Map<Long, String> LAST_DECISION = new ConcurrentHashMap<>();
	private static final Map<String, Long> LAST_LOG = new ConcurrentHashMap<>();

	private static final long LOG_THROTTLE_MILLIS = 30_000L;
	/** 进站等待上限（毫秒）：超过就正常进站，绝不死锁。 */
	private static final long MAX_WAIT_MILLIS = 20_000L;

	private PlatformDispatcher() {
	}

	/** 每 tick 每车调用一次。 */
	public static void tick(VehicleSnapshot self, Data data, long nowMillis) {
		if (data == null || self.path == null || self.path.isEmpty()) {
			return;
		}
		final long vehicleId = self.vehicleId;

		// 车厂在线路中间：派车/回场（内部 5 秒节流，链不通时不动车）
		MidLineDepotService.tick(data);

		// ---- 依赖 tsc-dispatch：由外置调度服务给出选台决策，本模组只负责执行与展示 ----
		if (!mtr.realrail.dispatch.TscDispatchBridge.isEnabled()) {
			WAIT_UNTIL.remove(vehicleId);
			LAST_DECISION.put(vehicleId, "tsc-dispatch 集成已关闭（站台智能调度不可用）");
			return;
		}
		// 执行链：tsc-dispatch 负责规划，mtbridge 负责执行；链不完整时不再本地自行执行
		final boolean chainActive = mtr.realrail.dispatch.TscDispatchBridge.isChainActive();
		if (mtr.realrail.dispatch.TscDispatchBridge.isRequired()
				&& mtr.realrail.RealRailConfig.INSTANCE.executionChainRequired && !chainActive) {
			WAIT_UNTIL.remove(vehicleId);
			LAST_DECISION.put(vehicleId, "等待执行链：" + mtr.realrail.dispatch.TscDispatchBridge.chainStatusText());
			return;
		}
		if (chainActive) {
			// 执行链完整：运行中的列车也能改停站台——经 tsc-dispatch 转发给 mtbridge 的 set_platform
			// （mtbridge 会为这辆车重新生成进路，因此不再受"immutablePath 不可替换"的限制）
			final StopInfo chainNext = nextStop(data, self);
			if (chainNext != null && PlatformDispatchStore.isDispatchActive(self.thisRouteId, chainNext.stationId())
					&& isPlatformOccupied(data, chainNext.platform(), vehicleId)) {
				final Platform alternative = findFreeAllowedPlatform(data, self.thisRouteId, chainNext, vehicleId);
				if (alternative != null && sendSwitchOnce(vehicleId, alternative.getId(), nowMillis)) {
					LAST_DECISION.put(vehicleId, "经执行链改停站台：" + alternative.getName()
							+ "（原站台 " + chainNext.platform().getName() + " 被占用）");
					return;
				}
			}
			// 否则只记录/展示规划服务的决策
			final mtr.realrail.dispatch.TscDispatchBridge.VehicleDecision chainDecision =
					mtr.realrail.dispatch.TscDispatchBridge.decisionFor(vehicleId);
			WAIT_UNTIL.remove(vehicleId);
			LAST_DECISION.put(vehicleId, chainDecision == null
					? "执行链已接管（" + mtr.realrail.dispatch.TscDispatchBridge.chainStatusText() + "）"
					: "执行链决策：" + chainDecision.commandKind() + " → 站台 " + chainDecision.platformHex()
							+ "（" + chainDecision.status() + "）");
			return;
		}
		if (!mtr.realrail.dispatch.TscDispatchBridge.isOnline()) {
			if (mtr.realrail.dispatch.TscDispatchBridge.isRequired()) {
				WAIT_UNTIL.remove(vehicleId);
				LAST_DECISION.put(vehicleId, "等待 tsc-dispatch：" + mtr.realrail.dispatch.TscDispatchBridge.statusText());
				return;
			}
		} else {
			final mtr.realrail.dispatch.TscDispatchBridge.VehicleDecision decision =
					mtr.realrail.dispatch.TscDispatchBridge.decisionFor(vehicleId);
			if (decision != null && !decision.platformHex().isEmpty()) {
				LAST_DECISION.put(vehicleId, "tsc-dispatch 决策：" + decision.type()
						+ (decision.commandKind().isEmpty() ? "" : " / " + decision.commandKind())
						+ " → 站台 " + decision.platformHex() + "（" + decision.status() + "）");
				logThrottled(vehicleId, nowMillis, "tsc-decision",
						"列车 " + vehicleId + " 收到 tsc-dispatch 决策：" + decision.commandKind() + " → 站台 " + decision.platformHex()
								+ "，类型：" + decision.type() + "，状态：" + decision.status());
				if (decision.commandKind().contains("set_platform")) {
					WAIT_UNTIL.remove(vehicleId);
					return;
				}
			}
		}

		// 只在"停站中"或"接近站台"的列车上做站台调度
		final StopInfo next = nextStop(data, self);
		if (next == null) {
			WAIT_UNTIL.remove(vehicleId);
			return;
		}
		if (!PlatformDispatchStore.isDispatchActive(self.thisRouteId, next.stationId)) {
			WAIT_UNTIL.remove(vehicleId);
			LAST_DECISION.put(vehicleId, "该线路/车站未启用站台智能调度");
			return;
		}

		final boolean occupied = isPlatformOccupied(data, next.platform, vehicleId);
		if (!occupied) {
			WAIT_UNTIL.remove(vehicleId);
			LAST_DECISION.put(vehicleId, "目标站台空闲：" + next.platform.getName());
			return;
		}

		final Platform alternative = findFreeAllowedPlatform(data, self.thisRouteId, next, vehicleId);
		if (alternative != null) {
			WAIT_UNTIL.remove(vehicleId);
			LAST_DECISION.put(vehicleId, "目标站台被占，改停备用站台：" + alternative.getName());
			logThrottled(vehicleId, nowMillis, "platform-switch",
					"列车 " + vehicleId + " 目标站台 " + next.platform.getName() + " 被占用，"
							+ "线路 " + self.thisRouteId + " 允许的备用站台 " + alternative.getName() + " 空闲，将改停该站台");
			return;
		}

		// 没有可用备用站台：在"尚未进站"时短暂等待，避免堵在站台
		final Long waitUntil = WAIT_UNTIL.get(vehicleId);
		if (waitUntil == null) {
			WAIT_UNTIL.put(vehicleId, nowMillis + MAX_WAIT_MILLIS);
			LAST_DECISION.put(vehicleId, "目标站台被占且无空闲备用站台：等待进站（上限 " + (MAX_WAIT_MILLIS / 1000) + " 秒）");
			logThrottled(vehicleId, nowMillis, "platform-wait",
					"列车 " + vehicleId + " 的目标站台 " + next.platform.getName() + " 被占用且无空闲备用站台，等待进站（上限 "
							+ (MAX_WAIT_MILLIS / 1000) + " 秒）");
		} else if (nowMillis >= waitUntil) {
			WAIT_UNTIL.remove(vehicleId);
			LAST_DECISION.put(vehicleId, "等待超时：按原目标站台进站");
			logThrottled(vehicleId, nowMillis, "platform-wait-timeout",
					"列车 " + vehicleId + " 等待站台 " + next.platform.getName() + " 超时，按原站台进站");
		}
	}

	/** 该列车是否应被"等站台"压住（由 simulateStopped 注入执行）。 */
	public static boolean shouldWaitForPlatform(long vehicleId) {
		return WAIT_UNTIL.containsKey(vehicleId);
	}

	public static String decision(long vehicleId) {
		return LAST_DECISION.getOrDefault(vehicleId, "");
	}

	public static void forget(long vehicleId) {
		WAIT_UNTIL.remove(vehicleId);
		LAST_DECISION.remove(vehicleId);
	}

	public static void clear() {
		WAIT_UNTIL.clear();
		LAST_DECISION.clear();
		LAST_LOG.clear();
	}

	// ------------------------------------------------------------------ 查询

	/** 列车的下一个停靠信息（站台 + 车站）。 */
	public record StopInfo(Platform platform, long stationId, double pathDistance) {
	}

	/**
	 * 找出列车路径上"下一个停车段"对应的站台。
	 * 若列车当前正停在该停车段上（已在站台），返回当前站台（用于占用判断）。
	 */
	public static StopInfo nextStop(Data data, VehicleSnapshot self) {
		int index = Math.max(self.headIndex, 0);
		PathData pathData = Utilities.getElement(self.path, index);
		if (pathData == null) {
			return null;
		}
		// 当前段本身就是站台段（正在停站）→ 用当前段
		if (pathData.getDwellTime() <= 0) {
			// 否则向前找第一段停车段
			for (index = index + 1; index < self.path.size(); index++) {
				final PathData candidate = Utilities.getElement(self.path, index);
				if (candidate == null) {
					return null;
				}
				if (candidate.getDwellTime() > 0) {
					pathData = candidate;
					break;
				}
			}
			if (pathData.getDwellTime() <= 0) {
				return null;
			}
		}
		final Platform platform = findPlatform(data, pathData);
		if (platform == null) {
			return null;
		}
		final long stationId = platform.area == null ? 0 : platform.area.getId();
		if (stationId == 0) {
			return null;
		}
		return new StopInfo(platform, stationId, pathData.getStartDistance());
	}

	/** 按停车段的端点位置反查站台（MTR 的 Platform.containsPos 就是为此设计的）。 */
	public static Platform findPlatform(Data data, PathData pathData) {
		final org.mtr.core.data.Position position1 = pathData.getOrderedPosition1();
		final org.mtr.core.data.Position position2 = pathData.getOrderedPosition2();
		for (final Platform platform : data.platforms) {
			if (platform.containsPos(position1) || platform.containsPos(position2)) {
				return platform;
			}
		}
		return null;
	}

	/** 站台是否被其它列车占用（有列车正在该站台停站）。 */
	public static boolean isPlatformOccupied(Data data, Platform platform, long excludeVehicleId) {
		for (final VehicleSnapshot snapshot : TrafficRegistry.snapshots()) {
			if (snapshot.vehicleId == excludeVehicleId || !snapshot.isStopped()) {
				continue;
			}
			final PathData pathData = Utilities.getElement(snapshot.path, snapshot.headIndex);
			if (pathData == null || pathData.getDwellTime() <= 0) {
				continue;
			}
			final Platform other = findPlatform(data, pathData);
			if (other != null && other.getId() == platform.getId()) {
				return true;
			}
		}
		return false;
	}

	/** 接近该站台的列车数量（用于界面显示；信号辅助：只统计共享信号颜色的列车）。 */
	public static int approachingCount(Data data, Platform platform, VehicleSnapshot self) {
		int count = 0;
		for (final VehicleSnapshot snapshot : TrafficRegistry.snapshots()) {
			if (snapshot.vehicleId == self.vehicleId) {
				continue;
			}
			final StopInfo stop = nextStop(data, snapshot);
			if (stop != null && stop.platform().getId() == platform.getId()) {
				count++;
			}
		}
		return count;
	}

	/** 该线路上该车站"允许且空闲"的站台（排除当前目标站台）。 */
	public static Platform findFreeAllowedPlatform(Data data, long routeId, StopInfo target, long vehicleId) {
		final Set<Long> allowed = new TreeSet<>(PlatformDispatchStore.allowedPlatforms(routeId, target.stationId()));
		if (allowed.isEmpty()) {
			// 未配置允许集合 → 视为"该车站的全部站台都允许"（MTR 原版行为 + 智能调度）
			final Station station = data.stationIdMap.get(target.stationId());
			if (station == null) {
				return null;
			}
			station.savedRails.forEach(platform -> allowed.add(platform.getId()));
		}
		allowed.remove(target.platform().getId());
		for (final long platformId : allowed) {
			final Platform platform = findPlatformById(data, platformId);
			if (platform != null && platform.isValid() && !isPlatformOccupied(data, platform, vehicleId)) {
				return platform;
			}
		}
		return null;
	}

	/** 按 id 找站台（直接扫描 data.platforms，避免依赖具体的 id 索引字段）。 */
	public static Platform findPlatformById(Data data, long platformId) {
		for (final Platform platform : data.platforms) {
			if (platform.getId() == platformId) {
				return platform;
			}
		}
		return null;
	}

	/** 该线路经过的车站（按线路站序，去重）。 */
	public static List<Station> routeStations(Route route) {
		final List<Station> stations = new ArrayList<>();
		for (final RoutePlatformData platformData : route.getRoutePlatforms()) {
			final Platform platform = platformData.getPlatform();
			if (platform != null && platform.area != null && stations.stream().noneMatch(station -> station.getId() == platform.area.getId())) {
				stations.add(platform.area);
			}
		}
		return stations;
	}

	/** 车站的站台（按名称/编号排序，便于界面展示）。 */
	public static List<Platform> stationPlatforms(Station station) {
		final List<Platform> platforms = new ArrayList<>(station.savedRails);
		platforms.sort((a, b) -> a.getName().compareTo(b.getName()));
		return platforms;
	}

	/** 该站台是否是本线路的"原定站台"（MTR 线路配置里的站台）。 */
	public static boolean isRoutePlatform(Route route, long platformId) {
		for (final RoutePlatformData platformData : route.getRoutePlatforms()) {
			if (platformData.getPlatform() != null && platformData.getPlatform().getId() == platformId) {
				return true;
			}
		}
		return false;
	}

	/** 改停站台的去重与下发（同一辆车 20 秒内最多一次，避免刷指令）。 */
	private static final Map<Long, Long> LAST_SWITCH_MILLIS = new ConcurrentHashMap<>();

	private static boolean sendSwitchOnce(long vehicleId, long platformId, long nowMillis) {
		final Long last = LAST_SWITCH_MILLIS.get(vehicleId);
		if (last != null && nowMillis - last < 20_000L) {
			return false;
		}
		final String hex = "0x" + Long.toHexString(vehicleId);
		final boolean sent = mtr.realrail.dispatch.TscDispatchCommands.send("set_platform", hex,
				"0x" + Long.toHexString(platformId), "站台被占用，经执行链改停空闲且允许的站台");
		if (sent) {
			LAST_SWITCH_MILLIS.put(vehicleId, nowMillis);
		}
		return sent;
	}

	private static void logThrottled(long vehicleId, long nowMillis, String key, String message) {
		final String logKey = vehicleId + ":" + key;
		final Long last = LAST_LOG.get(logKey);
		if (last != null && nowMillis - last < LOG_THROTTLE_MILLIS) {
			return;
		}
		LAST_LOG.put(logKey, nowMillis);
		MtrRealRail.LOGGER.info("[{}] {}", MtrRealRail.MOD_ID, message);
	}
}
