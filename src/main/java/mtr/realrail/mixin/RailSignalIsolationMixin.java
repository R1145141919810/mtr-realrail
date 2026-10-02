package mtr.realrail.mixin;

import mtr.realrail.RealRailConfig;
import mtr.realrail.SignalIsolationRegistry;
import org.mtr.core.data.Rail;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2LongAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 需求二核心：多信号系统（16 色）隔离。
 *
 * <p>目标类：{@code org.mtr.core.data.Rail}。原版
 * {@code boolean isBlocked(long vehicleId, BlockReservation blockReservation)} 对该 rail 上
 * <b>所有</b>颜色的信号区间进行检查与预留（{@code signalColors.forEach(color -> reserveRail(...))}），
 * 因此信号系统 A 的列车会把系统 B 的区间一并标记占用，也会被系统 B 的红灯拦停。</p>
 *
 * <p>本 mixin 用 {@link SignalIsolationRegistry} 中登记的"列车当前所属信号系统"过滤颜色：
 * 只检查/预留列车所属颜色的区间。未登记（回退原版全颜色逻辑）、登记为空集合
 * （列车不属于任何信号系统 → 不受信号约束、不预留）语义明确。</p>
 *
 * <p>物理防撞（{@code Vehicle.railBlockedDistance} 中的 VehiclePosition 重叠检测）与颜色无关，
 * 本 mixin 不影响它——两列车即使在"不同信号系统"下也不会相撞。</p>
 *
 * <p>签名依据（javap 核对 MTR 4.0.5 Forge 发行 jar）：
 * {@code private final Long2LongAVLTreeMap preBlockedVehicleIds/currentlyBlockedVehicleIds(+Old)}、
 * {@code private static void reserveRail(long, long, ObjectOpenHashSet<Rail>, Rail, boolean)}。</p>
 */
@Mixin(Rail.class)
public abstract class RailSignalIsolationMixin {

	/** Rail 中按颜色维护的预留表：颜色 → 车辆 id。 */
	@Shadow
	@Final
	private Long2LongAVLTreeMap preBlockedVehicleIds;

	@Shadow
	@Final
	private Long2LongAVLTreeMap currentlyBlockedVehicleIds;

	@Shadow
	@Final
	private Long2LongAVLTreeMap preBlockedVehicleIdsOld;

	@Shadow
	@Final
	private Long2LongAVLTreeMap currentlyBlockedVehicleIdsOld;

	/** 原版按颜色沿同色连通轨道深度优先预留（每个颜色独立递归）。 */
	@Shadow
	private static void reserveRail(long vehicleId, long color, ObjectOpenHashSet<Rail> visitedRails, Rail rail, boolean currentlyBlocked) {
		throw new AssertionError("mixin shadow");
	}

	@Inject(method = "isBlocked", at = @At("HEAD"), cancellable = true)
	private void mtrrealrail$isBlocked(long vehicleId, Rail.BlockReservation blockReservation, CallbackInfoReturnable<Boolean> callbackInfo) {
		if (!RealRailConfig.INSTANCE.signalIsolation) {
			return; // 配置关闭 → 走原版逻辑
		}

		final IntAVLTreeSet effectiveColors = SignalIsolationRegistry.get(vehicleId);
		if (effectiveColors == null) {
			return; // 列车未登记（首 tick / 异常场景）→ 原版逻辑兜底
		}

		final boolean blocked = mtrrealrail$isBlockedByAnyColor(effectiveColors, vehicleId);

		if (!blocked && blockReservation != Rail.BlockReservation.DO_NOT_RESERVE) {
			final boolean currentlyBlocked = blockReservation == Rail.BlockReservation.CURRENTLY_RESERVE;
			final Rail self = (Rail) (Object) this;
			for (final int color : effectiveColors) {
				// 只预留"列车所属信号系统"的颜色；reserveRail 内部按颜色递归，
				// 不会影响其它颜色系统（原版数据结构本身即按颜色隔离）。
				reserveRail(vehicleId, color, new ObjectOpenHashSet<>(), self, currentlyBlocked);
			}
		}

		callbackInfo.setReturnValue(blocked);
		callbackInfo.cancel();
	}

	/**
	 * 仅当"列车所属颜色"中存在被<b>其它车辆</b>预留的颜色时视为占用。
	 * 原版 isNotBlocked 检查四张表的全部颜色，此处改为按注册颜色过滤。
	 */
	private boolean mtrrealrail$isBlockedByAnyColor(IntAVLTreeSet colors, long vehicleId) {
		for (final int color : colors) {
			if (mtrrealrail$isBlockedBy(preBlockedVehicleIds.getOrDefault(color, 0L), vehicleId)
					|| mtrrealrail$isBlockedBy(currentlyBlockedVehicleIds.getOrDefault(color, 0L), vehicleId)
					|| mtrrealrail$isBlockedBy(preBlockedVehicleIdsOld.getOrDefault(color, 0L), vehicleId)
					|| mtrrealrail$isBlockedBy(currentlyBlockedVehicleIdsOld.getOrDefault(color, 0L), vehicleId)) {
				return true;
			}
		}
		return false;
	}

	private static boolean mtrrealrail$isBlockedBy(long blockedVehicleId, long vehicleId) {
		return blockedVehicleId != 0 && blockedVehicleId != vehicleId;
	}
}
