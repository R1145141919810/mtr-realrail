package mtr.realrail.traffic;

import org.mtr.core.data.Data;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全网列车快照注册表 + 每 tick 的交通状态重建入口。
 *
 * <p>由 {@code VehicleSimulateMixin} 在 {@code Vehicle.simulate} 开头调用
 * {@link #update(VehicleSnapshot)}；每 tick 第一次更新时触发
 * {@link DynamicOccupancy#rebuild(Collection)}，把快照转成"轨道区间占用"索引。</p>
 *
 * <p>快照带 TTL（默认 5 秒未更新即移除），列车被移除后自动失效。</p>
 */
public final class TrafficRegistry {

	private static final Map<Long, VehicleSnapshot> SNAPSHOTS = new ConcurrentHashMap<>();
	private static final long TTL_MILLIS = 5_000L;

	private static volatile Data data;
	private static volatile long currentTickMillis = Long.MIN_VALUE;

	private TrafficRegistry() {
	}

	/** 每 tick 每车调用一次。 */
	public static void update(VehicleSnapshot snapshot, Data vehicleData) {
		if (vehicleData != null) {
			data = vehicleData;
		}
		SNAPSHOTS.put(snapshot.vehicleId, snapshot);

		// 同一 tick 内只重建一次全网占用索引
		final long tick = snapshot.lastUpdateMillis;
		if (tick != currentTickMillis) {
			currentTickMillis = tick;
			prune();
			DynamicOccupancy.rebuild(SNAPSHOTS.values());
		}
	}

	/** 服务端当前的 MTR 数据/仿真实例（{@code org.mtr.core.simulation.Simulator}）。 */
	public static Data data() {
		return data;
	}

	/** 由 {@code Rail.tick1(Simulator)} 每 tick 捕获（保证无列车时也能拿到路网数据入口）。 */
	public static void setData(Data vehicleData) {
		if (vehicleData != null) {
			data = vehicleData;
		}
	}

	public static VehicleSnapshot get(long vehicleId) {
		return SNAPSHOTS.get(vehicleId);
	}

	public static Collection<VehicleSnapshot> snapshots() {
		return SNAPSHOTS.values();
	}

	public static int size() {
		return SNAPSHOTS.size();
	}

	public static void clear() {
		SNAPSHOTS.clear();
		DynamicOccupancy.clear();
		currentTickMillis = Long.MIN_VALUE;
	}

	private static void prune() {
		final long now = currentTickMillis;
		SNAPSHOTS.entrySet().removeIf(entry -> now - entry.getValue().lastUpdateMillis > TTL_MILLIS);
	}
}
