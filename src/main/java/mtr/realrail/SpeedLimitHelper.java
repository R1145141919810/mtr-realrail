package mtr.realrail;

import org.mtr.core.data.PathData;
import org.mtr.core.data.Position;
import org.mtr.core.data.Siding;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectList;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 需求一核心：限速变更时机后移（车尾判定）+ 按节点可切换（车头/车尾判定）。
 *
 * <p>算法（纯函数 + 每车状态，全部由 mixin 在服务端 tick 中调用）：</p>
 * <ul>
 *   <li>列车沿路径前进，{@code railProgress} 指向行驶方向最前端；车尾位置 = railProgress - 列车总长。</li>
 *   <li>车头进入新轨道段（headIndex 变化）时，读取该段起点（= 轨道连接器节点位置）的判定模式：
 *       TAIL → 记录 pendingTailBoundary（延迟变更）；HEAD → 立即清除 pending（原版行为）。</li>
 *   <li>车尾所在段索引 ≥ pendingTailBoundary（整列车都进入新段）时清除 pending，限速变更生效。</li>
 *   <li>有效限速 = 有 pending 时取 pending 边界前一段的限速（保持旧限速），否则取车头所在段限速。</li>
 * </ul>
 *
 * <p>预制动（{@code Siding.getUpcomingSlowerSpeed}）：改为"车尾感知"版本——扫描到下一个 TAIL
 * 判定节点即停止（该节点之后的限速须等车尾通过才生效，不可提前减速）；HEAD 判定节点保持原版行为。</p>
 */
public final class SpeedLimitHelper {

	/** 每辆车的速度判定状态。 */
	public static final class VehicleSpeedState {
		/** 上一 tick 车头所在路径段索引（用于检测边界跨越）。 */
		public int lastHeadIndex = -1;
		/** 待生效的 TAIL 边界所在段索引；-1 表示无延迟变更。 */
		public int pendingTailBoundary = -1;
	}

	private static final Map<Long, VehicleSpeedState> STATES = new ConcurrentHashMap<>();

	private SpeedLimitHelper() {
	}

	public static VehicleSpeedState getState(long vehicleId) {
		return STATES.computeIfAbsent(vehicleId, key -> new VehicleSpeedState());
	}

	/**
	 * 每 tick 由 {@code VehicleSimulateMixin} 在 {@code simulate()} 开头调用：更新边界跨越状态。
	 *
	 * @param vehicleId          列车 id
	 * @param path               列车完整路径（vehicleExtraData.immutablePath）
	 * @param railProgress       车头沿路径的位置
	 * @param totalVehicleLength 列车总长（含车钩）
	 * @param headIndex          车头当前所在段索引
	 */
	public static void updateState(long vehicleId, ObjectList<PathData> path, double railProgress, double totalVehicleLength, int headIndex) {
		if (headIndex < 0) {
			return;
		}
		final VehicleSpeedState state = getState(vehicleId);

		if (state.lastHeadIndex >= 0 && headIndex != state.lastHeadIndex) {
			if (headIndex > state.lastHeadIndex) {
				// 车头跨越轨道连接器进入段 headIndex；边界节点 = 该段在行驶方向上的起点
				final PathData enteredPathData = Utilities.getElement(path, headIndex);
				final Position boundary = enteredPathData == null ? null : enteredPathData.getOrderedPosition1();
				state.pendingTailBoundary = boundary != null && NodeModeStore.isTailMode(boundary) ? headIndex : -1;
			} else {
				// 防御：索引回退（正常不应发生）时丢弃延迟状态
				state.pendingTailBoundary = -1;
			}
		}
		state.lastHeadIndex = headIndex;

		// 车尾越过 pending 边界后清除：整列车已进入新轨道段，速度变更生效
		if (state.pendingTailBoundary >= 0) {
			int tailIndex = Utilities.getIndexFromConditionalList(path, railProgress - totalVehicleLength);
			if (tailIndex < 0) {
				// 车尾还在路径起点之前（出库首 tick 等）：视为尚未进入任何段
				tailIndex = 0;
			}
			if (tailIndex >= state.pendingTailBoundary) {
				state.pendingTailBoundary = -1;
			}
		}
	}

	/**
	 * 替换 {@code simulateMoving} 中"车头所在段限速"的读取。
	 *
	 * @param vehicleId    列车 id
	 * @param path         列车完整路径
	 * @param headPathData 原版逻辑中的车头所在段（redirect 传入）
	 * @return 生效限速（米/毫秒）
	 */
	public static double getEffectiveSpeedLimit(long vehicleId, ObjectList<PathData> path, PathData headPathData) {
		final VehicleSpeedState state = STATES.get(vehicleId);
		if (state != null && state.pendingTailBoundary > 0) {
			final PathData previousPathData = Utilities.getElement(path, state.pendingTailBoundary - 1);
			if (previousPathData != null) {
				// 车尾尚未越过连接器：保持旧轨道段限速
				return previousPathData.getSpeedLimitMetersPerMillisecond();
			}
		}
		return headPathData.getSpeedLimitMetersPerMillisecond();
	}

	/**
	 * 车尾感知的预制动。
	 *
	 * <p>算法与 MTR 4.0.5 的 {@link Siding#getUpcomingSlowerSpeed} 完全一致（已逐行对照发行 jar 字节码），
	 * 仅新增一处：扫描到 TAIL 判定节点时立即返回已找到的更慢限速，不再向后预判——
	 * 因为该节点之后的限速必须等车尾通过才生效，提前减速会违背"车尾判定"语义。</p>
	 *
	 * @return 需要提前减速到的速度（米/毫秒），无需减速时返回 -1
	 */
	public static double getUpcomingSlowerSpeed(ObjectList<PathData> path, int currentIndex, double railProgress, double currentSpeed, double deceleration) {
		final double stoppingDistance = 0.5 * currentSpeed * currentSpeed / deceleration;
		int index = currentIndex + 1;
		double railSpeed = -1;
		double bestDistance = 0;

		while (true) {
			final PathData pathData = Utilities.getElement(path, index);
			if (pathData == null) {
				return -1;
			}

			// 需求一：TAIL 判定节点之后（含该段）的限速须等车尾通过，不参与预制动
			if (NodeModeStore.isTailMode(pathData.getOrderedPosition1())) {
				return railSpeed;
			}

			final double newRailSpeed = pathData.getSpeedLimitMetersPerMillisecond();
			final double distance = pathData.getStartDistance() - railProgress;
			if (newRailSpeed < currentSpeed && distance >= bestDistance && distance <= 0.5 * (currentSpeed * currentSpeed - newRailSpeed * newRailSpeed) / deceleration) {
				railSpeed = newRailSpeed;
				bestDistance = distance;
			}

			if (pathData.getEndDistance() >= railProgress + stoppingDistance) {
				return railSpeed;
			}

			index++;
		}
	}
}
