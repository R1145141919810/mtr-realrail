package mtr.realrail.traffic;

import org.mtr.core.data.PathData;
import org.mtr.core.tool.Utilities;

/**
 * 实时交通状态评估：ETA、进路拥挤程度、冲突判定。
 *
 * <p>冲突判定只看"<b>前方是否有间距不足的列车</b>"（制动距离 + 安全间距），
 * 不用前车速度推算 ETA——前车多半在<b>同向驶离</b>，用它的速度算 ETA 会得出
 * "马上要撞上"的假冲突。只有后方来车才用 ETA（它确实朝我们开来）。</p>
 */
public final class TrafficEvaluator {

	private TrafficEvaluator() {
	}

	/**
	 * 预计到达时间：以当前速度估算，速度过低时退化为"按限速的一半前进"的保守估计。
	 */
	public static long etaMillis(double distanceMeters, double speedMetersPerMillisecond, double speedLimitMetersPerMillisecond) {
		if (distanceMeters <= 0) {
			return 0;
		}
		final double effectiveSpeed = Math.max(speedMetersPerMillisecond, Math.max(1.0E-5, speedLimitMetersPerMillisecond * 0.5));
		return (long) Math.ceil(distanceMeters / effectiveSpeed);
	}

	/** 车辆当前位置所在段的限速（米/毫秒）。 */
	public static double speedLimitAt(VehicleSnapshot snapshot) {
		final PathData pathData = Utilities.getElement(snapshot.path, snapshot.headIndex);
		return pathData == null ? 0.02 : pathData.getSpeedLimitMetersPerMillisecond();
	}

	/** 前方拥挤程度（0~1），软指标，仅用于界面/日志展示。 */
	public static double congestionAhead(VehicleSnapshot self, double lookaheadMeters) {
		return DynamicOccupancy.occupancyRatioAhead(self, lookaheadMeters);
	}

	/** 前方拥挤折算的预计延误（毫秒，软指标）。 */
	public static long occupancyDelayMillis(VehicleSnapshot self, double lookaheadMeters) {
		final double ratio = congestionAhead(self, lookaheadMeters);
		if (ratio <= 0) {
			return 0;
		}
		final double speed = Math.max(speedLimitAt(self), 1.0E-5);
		return (long) (ratio * lookaheadMeters / speed);
	}

	/**
	 * 前方是否存在"会让本车无法安全进入"的列车：同信号系统、间距小于 制动距离 + 安全间距。
	 * 这是动态发车避让与动态闭塞放行共用的<b>硬判据</b>。
	 */
	public static boolean hasConflictAhead(VehicleSnapshot self, double safetyMarginMeters, double maxDistance) {
		final DynamicOccupancy.Leader leader = DynamicOccupancy.findLeaderAhead(self, maxDistance);
		if (leader == null) {
			return false;
		}
		return leader.distanceMeters() < self.brakingDistance() + Math.max(0, safetyMarginMeters);
	}

	/** 后车逼近：后方最近列车在窗口内到本站则返回 true（提示不要长时间占用站台）。 */
	public static boolean hasFollowerApproaching(VehicleSnapshot self, long windowMillis, double maxDistance) {
		final DynamicOccupancy.Leader follower = DynamicOccupancy.findFollowerBehind(self, maxDistance);
		if (follower == null) {
			return false;
		}
		if (follower.speed() <= 1.0E-5) {
			// 后车已停住（多半在等信号）：按"窗口内到达"处理，提示让出站台
			return true;
		}
		return etaMillis(follower.distanceMeters(), follower.speed(), speedLimitAt(self)) <= windowMillis;
	}

	/** 供 GUI / 日志使用的文字描述。 */
	public static String describeTraffic(VehicleSnapshot self, double safetyMarginMeters, long windowMillis, double lookaheadMeters) {
		final DynamicOccupancy.Leader leader = DynamicOccupancy.findLeaderAhead(self, lookaheadMeters);
		final DynamicOccupancy.Leader follower = DynamicOccupancy.findFollowerBehind(self, lookaheadMeters);
		final StringBuilder builder = new StringBuilder();
		builder.append("列车 ").append(self.vehicleId)
				.append(" 速度 ").append(String.format("%.1f", self.speed * 3600000)).append(" km/h")
				.append("，前方占用 ").append(String.format("%.0f%%", congestionAhead(self, lookaheadMeters) * 100));
		if (leader == null) {
			builder.append("，前方无同信号系统列车");
		} else {
			builder.append("，前方 ").append(String.format("%.0f", leader.distanceMeters())).append(" m 处有列车 ")
					.append(leader.vehicleId())
					.append(hasConflictAhead(self, safetyMarginMeters, lookaheadMeters) ? "（间距不足）" : "（间距充足）");
		}
		if (follower != null) {
			builder.append("，后方 ").append(String.format("%.0f", follower.distanceMeters())).append(" m 处有列车 ")
					.append(follower.vehicleId())
					.append(hasFollowerApproaching(self, windowMillis, lookaheadMeters) ? "（即将到站）" : "");
		}
		return builder.toString();
	}
}
