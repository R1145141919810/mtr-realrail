package mtr.realrail.traffic;

import org.mtr.core.data.PathData;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>动态轨道占用</b>：把每辆车的实时位置换算成"某条轨道上的一段占用区间"
 * （轨道局部坐标，米），并基于<b>信号颜色</b>做冲突判定。
 *
 * <p>与 MTR 固定闭塞的关系：MTR 的闭塞是"同色连通分量"，粒度粗且只能表达"占用/空闲"。
 * 本类补充一层细分占用：占用区间精确到米级，并可回答"前方最近的障碍在哪、离多远、多快"。</p>
 *
 * <p><b>方向语义（重要）</b>：所有查询都严格区分"前方"与"后方"——
 * 区间完全落在车头之后的不算前车、完全落在车尾之前的不算后车。
 * （早期版本把"跟在后面的车"也当成 0 米处的前车，导致列车在站台被无限期压住。）</p>
 *
 * <p>每 tick 由 {@link TrafficRegistry} 调用 {@link #rebuild(Collection)} 重建索引一次。</p>
 */
public final class DynamicOccupancy {

	/** 某辆车在某条轨道上的一段占用（轨道局部坐标，米；0 = 轨道 position1 端）。 */
	public record Interval(long vehicleId, double min, double max, double speed, int[] colors) {
	}

	/** 前方/后方最近的一辆车。 */
	public record Leader(long vehicleId, double distanceMeters, double speed, int[] colors) {
	}

	private static volatile Map<String, List<Interval>> railOccupancy = Map.of();
	private static volatile Map<Long, VehicleSnapshot> byId = Map.of();

	private DynamicOccupancy() {
	}

	public static void clear() {
		railOccupancy = Map.of();
		byId = Map.of();
	}

	/** 重建全网占用索引（每 tick 一次）。 */
	public static void rebuild(Collection<VehicleSnapshot> snapshots) {
		final Map<String, List<Interval>> next = new HashMap<>();
		final Map<Long, VehicleSnapshot> idMap = new HashMap<>();

		for (final VehicleSnapshot snapshot : snapshots) {
			idMap.put(snapshot.vehicleId, snapshot);
			if (snapshot.path == null || snapshot.path.isEmpty()) {
				continue;
			}
			int tailIndex = Utilities.getIndexFromConditionalList(snapshot.path, snapshot.tailProgress());
			if (tailIndex < 0) {
				tailIndex = 0;
			}
			for (int index = tailIndex; index <= snapshot.headIndex && index < snapshot.path.size(); index++) {
				final PathData pathData = Utilities.getElement(snapshot.path, index);
				if (pathData == null) {
					continue;
				}
				final double from = Math.max(pathData.getStartDistance(), snapshot.tailProgress());
				final double to = Math.min(pathData.getEndDistance(), snapshot.railProgress);
				if (to <= from) {
					continue;
				}
				final double railLength = Math.max(0.001, pathData.getRailLength());
				double localA = from - pathData.getStartDistance();
				double localB = to - pathData.getStartDistance();
				if (pathData.reversePositions) {
					// 轨道局部坐标与行驶方向相反
					final double flippedA = railLength - localB;
					localB = railLength - localA;
					localA = flippedA;
				}
				next.computeIfAbsent(pathData.getRail().getHexId(), key -> new ArrayList<>())
						.add(new Interval(snapshot.vehicleId, localA, localB, snapshot.speed, pathData.getSignalColors().toIntArray()));
			}
		}

		railOccupancy = Map.copyOf(next);
		byId = Map.copyOf(idMap);
	}

	public static VehicleSnapshot snapshotOf(long vehicleId) {
		return byId.get(vehicleId);
	}

	public static List<Interval> intervalsOf(String railHexId) {
		return railOccupancy.getOrDefault(railHexId, List.of());
	}

	/**
	 * 把某条轨道上的占用区间换算到<b>本车路径坐标系</b>，返回 {@code [较近端, 较远端]}（升序）。
	 */
	private static double[] toPathRange(PathData pathData, Interval interval) {
		final double railLength = Math.max(0.001, pathData.getRailLength());
		final double localA = Math.min(interval.min(), interval.max());
		final double localB = Math.max(interval.min(), interval.max());
		final double offsetA = pathData.reversePositions ? railLength - localB : localA;
		final double offsetB = pathData.reversePositions ? railLength - localA : localB;
		final double pathA = pathData.getStartDistance() + Math.min(offsetA, offsetB);
		final double pathB = pathData.getStartDistance() + Math.max(offsetA, offsetB);
		return new double[]{pathA, pathB};
	}

	/**
	 * 前方最近的障碍：沿本车路径向前扫描，找到最近的、只属于"同信号系统其它车辆"的占用区间。
	 *
	 * <p>只统计<b>完全或部分位于车头前方</b>的区间；整段都在车头之后（例如跟在后面的车）会被跳过。</p>
	 *
	 * @return 无前车时返回 null
	 */
	public static Leader findLeaderAhead(VehicleSnapshot self, double maxDistance) {
		final ObjectList<PathData> path = self.path;
		if (path == null || path.isEmpty()) {
			return null;
		}
		Leader best = null;
		for (int index = Math.max(self.headIndex, 0); index < path.size(); index++) {
			final PathData pathData = Utilities.getElement(path, index);
			if (pathData == null) {
				break;
			}
			if (pathData.getStartDistance() > self.railProgress + maxDistance) {
				break;
			}
			final List<Interval> intervals = railOccupancy.get(pathData.getRail().getHexId());
			if (intervals != null && !intervals.isEmpty()) {
				final int[] colors = pathData.getSignalColors().toIntArray();
				for (final Interval interval : intervals) {
					if (interval.vehicleId() == self.vehicleId || !sharesColor(colors, interval.colors())) {
						continue;
					}
					final double[] range = toPathRange(pathData, interval);
					if (range[1] <= self.railProgress) {
						continue; // 整段都在车头之后 → 不是前车
					}
					final double distance = Math.max(0, range[0] - self.railProgress);
					if (distance <= maxDistance && (best == null || distance < best.distanceMeters())) {
						best = new Leader(interval.vehicleId(), distance, interval.speed(), interval.colors());
					}
				}
			}
			if (pathData.getEndDistance() >= self.railProgress + maxDistance) {
				break;
			}
		}
		return best;
	}

	/**
	 * 后方最近逼近的列车：只统计<b>完全或部分位于车尾之后</b>的区间。
	 *
	 * @return 无后车时返回 null
	 */
	public static Leader findFollowerBehind(VehicleSnapshot self, double maxDistance) {
		final ObjectList<PathData> path = self.path;
		if (path == null || path.isEmpty()) {
			return null;
		}
		final double tail = self.tailProgress();
		Leader best = null;
		for (int index = Math.min(self.headIndex, path.size() - 1); index >= 0; index--) {
			final PathData pathData = Utilities.getElement(path, index);
			if (pathData == null) {
				break;
			}
			if (pathData.getEndDistance() < tail - maxDistance) {
				break;
			}
			final List<Interval> intervals = railOccupancy.get(pathData.getRail().getHexId());
			if (intervals != null && !intervals.isEmpty()) {
				final int[] colors = pathData.getSignalColors().toIntArray();
				for (final Interval interval : intervals) {
					if (interval.vehicleId() == self.vehicleId || !sharesColor(colors, interval.colors())) {
						continue;
					}
					final double[] range = toPathRange(pathData, interval);
					if (range[0] >= tail) {
						continue; // 整段都在车尾之前（前方）→ 不是后车
					}
					final double distance = Math.max(0, tail - range[1]);
					if (distance <= maxDistance && (best == null || distance < best.distanceMeters())) {
						best = new Leader(interval.vehicleId(), distance, interval.speed(), interval.colors());
					}
				}
			}
			if (pathData.getStartDistance() < tail - maxDistance) {
				break;
			}
		}
		return best;
	}

	/**
	 * 前方 {@code lookahead} 米内被"同信号系统"其它车辆占用的长度比例（0~1）。
	 * 只统计车头前方的部分（车头后方不计入）。
	 */
	public static double occupancyRatioAhead(VehicleSnapshot self, double lookahead) {
		final ObjectList<PathData> path = self.path;
		if (path == null || path.isEmpty()) {
			return 0;
		}
		double scanned = 0;
		double occupied = 0;
		for (int index = Math.max(self.headIndex, 0); index < path.size() && scanned < lookahead; index++) {
			final PathData pathData = Utilities.getElement(path, index);
			if (pathData == null) {
				break;
			}
			final double segmentLength = Math.max(0.001, pathData.getEndDistance() - pathData.getStartDistance());
			scanned += segmentLength;
			final List<Interval> intervals = intervalsOf(pathData.getRail().getHexId());
			if (intervals.isEmpty()) {
				continue;
			}
			final int[] colors = pathData.getSignalColors().toIntArray();
			double covered = 0;
			for (final Interval interval : intervals) {
				if (interval.vehicleId() == self.vehicleId || !sharesColor(colors, interval.colors())) {
					continue;
				}
				final double[] range = toPathRange(pathData, interval);
				final double from = Math.max(range[0], Math.max(self.railProgress, pathData.getStartDistance()));
				final double to = Math.min(range[1], pathData.getEndDistance());
				if (to > from) {
					covered += to - from;
				}
			}
			occupied += Math.min(covered, segmentLength);
		}
		return scanned <= 0 ? 0 : Math.min(1, occupied / Math.min(scanned, lookahead));
	}

	/** 与给定信号颜色集合存在交集的其它列车数量（信号辅助：只统计同一信号系统）。 */
	public static int conflictingVehicleCount(int[] colors, long excludeVehicleId) {
		final Map<Long, Boolean> seen = new HashMap<>();
		for (final List<Interval> intervals : railOccupancy.values()) {
			for (final Interval interval : intervals) {
				if (interval.vehicleId() != excludeVehicleId && sharesColor(colors, interval.colors())) {
					seen.put(interval.vehicleId(), Boolean.TRUE);
				}
			}
		}
		return seen.size();
	}

	/** 两条占用是否共享信号颜色（无颜色的轨道视为"无信号系统"，与任何车都不共享）。 */
	public static boolean sharesColor(int[] a, int[] b) {
		for (final int colorA : a) {
			for (final int colorB : b) {
				if (colorA == colorB) {
					return true;
				}
			}
		}
		return false;
	}
}
