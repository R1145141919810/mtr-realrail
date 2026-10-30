package mtr.realrail.traffic;

import mtr.realrail.MtrRealRail;
import mtr.realrail.RealRailConfig;
import mtr.realrail.dispatch.TscDispatchBridge;
import mtr.realrail.dispatch.TscDispatchCommands;
import org.mtr.core.data.Data;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.data.Siding;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>车厂在线路中间</b>（车厂级开关，位于「车厂 → 优化线路配置」）。
 *
 * <p>开启后：车辆按<b>最短进路</b>驶向最近的可用车站并投入运行；当时刻表进入
 * "无车发出的时段"（距下一班超过 {@link OptimizeSettings#idleReturnMinutes} 分钟，或当日已无班次）时，
 * 车辆自行回场。</p>
 *
 * <p>执行完全交给 <b>tsc-dispatch（规划）+ mtbridge（执行）</b>：
 * 派车 = {@code deploy_train}，回场 = {@code return_to_depot}，改停站台 = {@code set_platform}。
 * 这样运行中的列车也能被重新规划进路，绕开了"MTR 列车路径 immutablePath 不可替换"的限制。</p>
 *
 * <p>开关切换（开/关）时自动<b>清除车辆并刷新路线</b>：先 {@code Siding.clearVehicles()} 清空该车厂车辆，
 * 再 {@link RouteRefresher#refreshDepot(Data, long)} 让 MTR 重新生成车厂路径。</p>
 */
public final class MidLineDepotService {

	private static final long TICK_INTERVAL_MILLIS = 5_000L;
	private static final long COMMAND_DEDUPE_MILLIS = 30_000L;

	private static final Map<String, Long> LAST_COMMAND = new ConcurrentHashMap<>();
	private static volatile long lastTickMillis;

	private MidLineDepotService() {
	}

	/** 每 5 秒评估一次（由 {@link PlatformDispatcher} 在 tick 中低频调用）。 */
	public static void tick(Data data) {
		if (data == null) {
			return;
		}
		final long now = System.currentTimeMillis();
		if (now - lastTickMillis < TICK_INTERVAL_MILLIS) {
			return;
		}
		lastTickMillis = now;
		if (!TscDispatchBridge.isChainActive()) {
			return; // 依赖执行链：链不通时不动任何列车
		}
		for (final Depot depot : data.depots) {
			final OptimizeSettings settings = DepotSettingsResolver.settingsForDepot(depot.getId());
			if (settings.midLineDepot) {
				evaluate(data, depot, settings);
			}
		}
	}

	private static void evaluate(Data data, Depot depot, OptimizeSettings settings) {
		final long now = data.getCurrentMillis();
		final LongArrayList departures = depot.getRealTimeDepartures();
		long next = Long.MAX_VALUE;
		if (departures != null) {
			for (int i = 0; i < departures.size(); i++) {
				final long time = departures.getLong(i);
				if (time >= now - 60_000L && time < next) {
					next = time;
				}
			}
		}
		final long leadMillis = Math.max(1, settings.deployLeadMinutes) * 60_000L;
		final long idleMillis = Math.max(1, settings.idleReturnMinutes) * 60_000L;
		final Platform nearest = nearestPlatform(depot);

		if (next != Long.MAX_VALUE && next - now <= leadMillis) {
			// 临近发车：把车厂里的车按最短进路派到最近车站
			for (final VehicleSnapshot snapshot : TrafficRegistry.snapshots()) {
				if (DepotSettingsResolver.depotIdForVehicle(snapshot.vehicleId) != depot.getId() || !snapshot.isStopped()) {
					continue;
				}
				sendOnce("deploy_train", snapshot.vehicleId, nearest == null ? "" : toHex(nearest.getId()),
						"车厂在线路中间：临近发车，派往最近车站");
			}
		} else if (next == Long.MAX_VALUE || next - now > idleMillis) {
			// 无车发出的时段：车辆自行回场
			for (final VehicleSnapshot snapshot : TrafficRegistry.snapshots()) {
				if (DepotSettingsResolver.depotIdForVehicle(snapshot.vehicleId) != depot.getId()) {
					continue;
				}
				sendOnce("return_to_depot", snapshot.vehicleId, toHex(depot.getId()), "车厂在线路中间：无车发出的时段，自动回场");
			}
		}
	}

	/** 开关切换：清除该车厂车辆 + 刷新路线（随后由 tsc-dispatch/mtbridge 接管调度）。 */
	public static void onToggle(long depotId, boolean enabled) {
		final Data data = TrafficRegistry.data();
		if (data == null) {
			return;
		}
		final int cleared = clearVehicles(data, depotId);
		final boolean refreshed = RouteRefresher.refreshDepot(data, depotId);
		MtrRealRail.LOGGER.info("[{}] 车厂 {} 的「车厂在线路中间」已{}：清除车辆 {} 辆，刷新路线 {}",
				MtrRealRail.MOD_ID, depotId, enabled ? "开启" : "关闭", cleared, refreshed ? "已请求" : "未请求");
		if (enabled && TscDispatchBridge.isChainActive()) {
			MtrRealRail.LOGGER.info("[{}] 执行链已就绪（{}），该车厂将由 tsc-dispatch → mtbridge 调度",
					MtrRealRail.MOD_ID, TscDispatchBridge.chainStatusText());
		}
	}

	private static int clearVehicles(Data data, long depotId) {
		final Depot depot = data.depotIdMap.get(depotId);
		if (depot == null) {
			return 0;
		}
		final String depotName = depot.getName();
		int cleared = 0;
		for (final Siding siding : data.sidings) {
			if (siding == null || depotName == null || !depotName.equals(siding.getDepotName())) {
				continue;
			}
			try {
				siding.clearVehicles();
				cleared++;
			} catch (Throwable throwable) {
				MtrRealRail.LOGGER.debug("[{}] 清除侧线车辆失败：{}", MtrRealRail.MOD_ID, throwable.toString());
			}
		}
		return cleared;
	}

	/** 最近的车站站台：取该车厂第一条线路的第一个站台作为派车目标，具体进路由 tsc-dispatch 规划。 */
	private static Platform nearestPlatform(Depot depot) {
		for (final Route route : depot.routes) {
			for (final RoutePlatformData platformData : route.getRoutePlatforms()) {
				final Platform platform = platformData.getPlatform();
				if (platform != null && platform.isValid()) {
					return platform;
				}
			}
		}
		return null;
	}

	private static void sendOnce(String kind, long vehicleId, String target, String reason) {
		final String key = kind + "|" + vehicleId;
		final long now = System.currentTimeMillis();
		final Long last = LAST_COMMAND.get(key);
		if (last != null && now - last < COMMAND_DEDUPE_MILLIS) {
			return;
		}
		if (TscDispatchCommands.send(kind, toHex(vehicleId), target, reason)) {
			LAST_COMMAND.put(key, now);
		}
	}

	private static String toHex(long id) {
		return "0x" + Long.toHexString(id);
	}

	public static void clear() {
		LAST_COMMAND.clear();
	}
}
