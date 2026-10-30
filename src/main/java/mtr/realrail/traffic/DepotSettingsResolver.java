package mtr.realrail.traffic;

import mtr.realrail.RealRailConfig;
import org.mtr.core.data.Data;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Route;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「车厂 id → 优化配置」解析器：列车 → 当前线路 → 车厂 → 配置。
 *
 * <p>{@code Rail.isBlocked} 等热路径每 tick 会多次询问配置，因此带 1 秒缓存。</p>
 */
public final class DepotSettingsResolver {

	private record Cached(OptimizeSettings settings, long expiresAtMillis) {
	}

	private static final Map<Long, Cached> VEHICLE_CACHE = new ConcurrentHashMap<>();

	private DepotSettingsResolver() {
	}

	/** 取该列车所属车厂的优化配置（查不到时返回全局默认）。 */
	public static OptimizeSettings settingsForVehicle(long vehicleId) {
		final long now = System.currentTimeMillis();
		final Cached cached = VEHICLE_CACHE.get(vehicleId);
		if (cached != null && cached.expiresAtMillis() > now) {
			return cached.settings();
		}
		final OptimizeSettings settings = resolve(vehicleId);
		VEHICLE_CACHE.put(vehicleId, new Cached(settings, now + 1000L));
		return settings;
	}

	public static OptimizeSettings settingsForDepot(long depotId) {
		return OptimizeConfigStore.get(depotId);
	}

	/** 该列车所属车厂 id（解析失败返回 0）。 */
	public static long depotIdForVehicle(long vehicleId) {
		final VehicleSnapshot snapshot = TrafficRegistry.get(vehicleId);
		final Data data = TrafficRegistry.data();
		if (snapshot == null || data == null || snapshot.thisRouteId == 0) {
			return 0;
		}
		final Route route = data.routeIdMap.get(snapshot.thisRouteId);
		if (route == null || route.depots.isEmpty()) {
			return 0;
		}
		return route.depots.get(0).getId();
	}

	private static OptimizeSettings resolve(long vehicleId) {
		final long depotId = depotIdForVehicle(vehicleId);
		return depotId == 0 ? RealRailConfig.INSTANCE.optimizeDefaults : OptimizeConfigStore.get(depotId);
	}

	public static Depot depotOf(Data data, long depotId) {
		return data == null ? null : data.depotIdMap.get(depotId);
	}

	public static void invalidate() {
		VEHICLE_CACHE.clear();
	}
}
