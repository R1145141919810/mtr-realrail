package mtr.realrail.traffic;

import mtr.realrail.MtrRealRail;
import org.mtr.core.data.Data;
import org.mtr.core.data.Depot;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「重新生成车厂路径」：直接复用 MTR 自身的路径生成逻辑
 * （{@code Depot.generateDepots(Simulator, depots)}——铁路仪表板里"生成路线/刷新路线"按钮
 * 与 {@code /mtr generate} 命令背后的同一入口）。
 *
 * <p>因此本模组的"自动最优路径规划"不是重写 MTR 的寻路器，而是：</p>
 * <ol>
 *   <li>用实时交通状态（动态占用 + ETA）评估当前进路代价；</li>
 *   <li>通过发车时序（等待/避让）改变列车进入路网的时刻；</li>
 *   <li>必要时让 MTR 重新生成该车厂的路径，使列车带着最新路况出发。</li>
 * </ol>
 *
 * <p>刷新动作通过 {@code Simulator.run(Runnable)} 投递到服务端安全时机执行，
 * 避免在列车模拟迭代过程中修改路网数据。</p>
 */
public final class RouteRefresher {

	private static final Map<Long, Long> LAST_REFRESH = new ConcurrentHashMap<>();
	/** 同一车厂的刷新冷却（毫秒），避免每 tick 重复生成。 */
	private static final long COOLDOWN_MILLIS = 10_000L;

	private RouteRefresher() {
	}

	/** 刷新指定车厂的路径；返回是否真正执行。 */
	public static boolean refreshDepot(Data data, long depotId) {
		if (depotId == 0 || !(data instanceof Simulator simulator)) {
			return false;
		}
		final Depot depot = data.depotIdMap.get(depotId);
		if (depot == null) {
			return false;
		}
		final long now = System.currentTimeMillis();
		if (now - LAST_REFRESH.getOrDefault(depotId, 0L) < COOLDOWN_MILLIS) {
			return false;
		}
		LAST_REFRESH.put(depotId, now);

		final ObjectArrayList<Depot> depots = new ObjectArrayList<>();
		depots.add(depot);
		simulator.run(() -> Depot.generateDepots(simulator, depots));
		MtrRealRail.LOGGER.info("[{}] 已请求重新生成车厂 {}（{}）的路径", MtrRealRail.MOD_ID, depotId, depot.getName());
		return true;
	}

	public static boolean refreshForVehicle(Data data, long vehicleId) {
		return refreshDepot(data, DepotSettingsResolver.depotIdForVehicle(vehicleId));
	}

	/** 刷新某条线路所属车厂的路径（站台智能调度改变后使用）。 */
	public static boolean refreshForRoute(Data data, long routeId) {
		if (data == null) {
			return false;
		}
		final org.mtr.core.data.Route route = data.routeIdMap.get(routeId);
		if (route == null || route.depots.isEmpty()) {
			return false;
		}
		boolean refreshed = false;
		for (final Depot depot : route.depots) {
			refreshed |= refreshDepot(data, depot.getId());
		}
		return refreshed;
	}
}
