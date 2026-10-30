package mtr.realrail.traffic;

import org.mtr.core.data.PathData;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectList;

/**
 * 一辆列车在某一 tick 的运行快照（由 {@code VehicleSimulateMixin} 每 tick 写入）。
 *
 * <p>动态轨道占用、ETA、进路打分与发车决策全部基于该快照，避免直接依赖 MTR 内部集合。</p>
 */
public final class VehicleSnapshot {

	public final long vehicleId;
	/** 列车完整路径（MTR 的 vehicleExtraData.immutablePath，只读）。 */
	public final ObjectList<PathData> path;
	/** 车头沿路径的位置（米）。 */
	public final double railProgress;
	/** 当前速度（米/毫秒）。 */
	public final double speed;
	/** 列车总长（含车钩，米）。 */
	public final double totalLength;
	/** 列车减速度（米/毫秒²，来自车厂设置），用于计算制动距离。 */
	public final double deceleration;
	/** 车头所在段索引。 */
	public final int headIndex;
	/** 当前停站已用时（毫秒，来自 VehicleSchema.elapsedDwellTime）。 */
	public final long elapsedDwellTime;
	/** 当前段（站台停车段）的总停站时长（毫秒）。 */
	public final long totalDwellMillis;
	/** 本次运行所属线路 id（vehicleExtraData.getThisRouteId()）。 */
	public final long thisRouteId;
	/** 所属侧线（车厂）id（vehicleExtraData.getSidingId()）。 */
	public final long sidingId;
	/** 最近一次更新的游戏内毫秒（data.getCurrentMillis()）。 */
	public final long lastUpdateMillis;

	public VehicleSnapshot(long vehicleId, ObjectList<PathData> path, double railProgress, double speed, double totalLength,
	                       double deceleration, int headIndex, long elapsedDwellTime, long totalDwellMillis, long thisRouteId,
	                       long sidingId, long lastUpdateMillis) {
		this.vehicleId = vehicleId;
		this.path = path;
		this.railProgress = railProgress;
		this.speed = speed;
		this.totalLength = totalLength;
		this.deceleration = deceleration;
		this.headIndex = headIndex;
		this.elapsedDwellTime = elapsedDwellTime;
		this.totalDwellMillis = totalDwellMillis;
		this.thisRouteId = thisRouteId;
		this.sidingId = sidingId;
		this.lastUpdateMillis = lastUpdateMillis;
	}

	/** 车尾沿路径的位置。 */
	public double tailProgress() {
		return railProgress - totalLength;
	}

	/** 是否处于停站（速度近似为 0 且有停站时长）。 */
	public boolean isStopped() {
		return speed < 1.0E-4;
	}

	/** 制动距离（米）：0.5 v² / a。 */
	public double brakingDistance() {
		final double safeDeceleration = deceleration <= 0 ? 0.001 : deceleration;
		return 0.5 * speed * speed / safeDeceleration;
	}

	/** 停站是否已达到给定阈值（毫秒）。 */
	public boolean dwellReached(long thresholdMillis) {
		return totalDwellMillis > 0 && elapsedDwellTime >= thresholdMillis;
	}

	/**
	 * 站台发车时间点（与 MTR Vehicle.simulateStopped 中的 doorCloseTime 一致）：
	 * {@code max(totalDwell / 2, totalDwell - DOOR_MOVE_TIME(3200) - DOOR_DELAY(1000))}。
	 */
	public long doorCloseTimeMillis() {
		return Math.max(totalDwellMillis / 2, totalDwellMillis - 3200L - 1000L);
	}
}
