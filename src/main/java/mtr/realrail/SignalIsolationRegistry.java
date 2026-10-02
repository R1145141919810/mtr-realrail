package mtr.realrail;

import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntAVLTreeSet;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 列车 id → 当前所属信号系统颜色 的注册表（需求二核心）。
 *
 * <p>MTR 4.0.x 中 {@code Rail.isBlocked(vehicleId, BlockReservation)} 只拿到 vehicleId，
 * 无法得知"这列车属于哪个信号系统"。本注册表由 {@code VehicleSimulateMixin}
 * 在每 tick 开头写入：列车车身覆盖的各轨道段 {@code signalColors} 的并集，
 * 即"列车当前实际所在的信号系统"。</p>
 *
 * <p>{@code RailSignalIsolationMixin} 在检查/预留信号区间时读取本表，
 * 只检查并预留列车所属颜色的区间，从而实现多信号系统互不干扰。</p>
 *
 * <p>条目带 TTL：列车被移除后残留条目自动过期，避免污染后续逻辑；
 * 未注册或已过期的列车回退为原版行为（全颜色）。</p>
 */
public final class SignalIsolationRegistry {

	private record Entry(IntAVLTreeSet colors, long lastUpdatedMillis) {
	}

	private static final Map<Long, Entry> ENTRIES = new ConcurrentHashMap<>();
	/** 注册表条目有效期：覆盖任何正常 tick 间隔，又能让已消失的列车快速失效。 */
	private static final long TTL_MILLIS = 10_000L;

	private SignalIsolationRegistry() {
	}

	/** 每 tick 由 Vehicle 模拟开头调用；colors 允许为空集合（表示列车不属于任何信号系统）。 */
	public static void update(long vehicleId, IntAVLTreeSet colors) {
		ENTRIES.put(vehicleId, new Entry(new IntAVLTreeSet(colors), System.currentTimeMillis()));
	}

	/**
	 * 获取列车当前所属的信号颜色集合。
	 *
	 * @return 颜色集合（可能为空集合，表示列车不属于任何信号系统）；未注册或已过期返回 null（调用方回退原版逻辑）
	 */
	public static IntAVLTreeSet get(long vehicleId) {
		final Entry entry = ENTRIES.get(vehicleId);
		if (entry == null) {
			return null;
		}
		if (System.currentTimeMillis() - entry.lastUpdatedMillis() > TTL_MILLIS) {
			ENTRIES.remove(vehicleId, entry);
			return null;
		}
		return entry.colors();
	}

	public static void remove(long vehicleId) {
		ENTRIES.remove(vehicleId);
	}
}
