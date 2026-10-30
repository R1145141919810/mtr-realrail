package mtr.realrail.mixin;

import mtr.realrail.RealRailConfig;
import mtr.realrail.SignalIsolationRegistry;
import mtr.realrail.traffic.DepotSettingsResolver;
import mtr.realrail.traffic.DynamicOccupancy;
import mtr.realrail.traffic.OptimizeSettings;
import mtr.realrail.traffic.TrafficRegistry;
import mtr.realrail.traffic.VehicleSnapshot;
import org.mtr.core.data.Rail;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2LongAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 需求二（多信号系统隔离）+ 新功能（动态轨道占用 / 移动闭塞）。
 *
 * <p>目标类：{@code org.mtr.core.data.Rail}。原版
 * {@code boolean isBlocked(long vehicleId, BlockReservation blockReservation)} 对该 rail 上
 * <b>所有</b>颜色的信号区间进行检查与预留（{@code signalColors.forEach(color -> reserveRail(...))}）。</p>
 *
 * <p>本 mixin 做两件事：</p>
 * <ol>
 *   <li><b>信号隔离</b>：只检查/预留列车所属信号系统的颜色（需求二）。</li>
 *   <li><b>动态闭塞放行</b>：当固定闭塞判定为"被占用"时，用 {@link DynamicOccupancy} 计算
 *       本车与前车（同信号系统）的真实间距；若间距 ≥ 制动距离 + 安全间距，则允许进入该闭塞区间，
 *       且<b>不抢占前车的预留</b>（避免把前车反判为被阻塞）。
 *       这样固定闭塞之外多了一层"移动闭塞"式的精细分配；不同信号系统之间仍然互不干扰。</li>
 * </ol>
 *
 * <p>安全性：MTR 的物理防撞（{@code VehiclePosition.getClosestOverlap}）与
 * {@code railBlockedDistance} 制动逻辑未被改动，两层叠加后列车不会相撞。</p>
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

	/**
	 * 捕获服务端 {@code Simulator} 实例（MTR 每 tick 会对每条轨道调用 {@code tick1}）。
	 * 这样即使一列车都没运行，GUI 的"刷新路线"也能拿到路网数据入口。
	 */
	@Inject(method = "tick1", at = @At("HEAD"))
	private void mtrrealrail$captureSimulator(Simulator simulator, CallbackInfo callbackInfo) {
		TrafficRegistry.setData(simulator);
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

		if (blocked && mtrrealrail$dynamicRelease(vehicleId)) {
			// 动态闭塞放行：允许进入，但不预留（前车的预留保持不变）
			callbackInfo.setReturnValue(false);
			callbackInfo.cancel();
			return;
		}

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
	 * 动态闭塞放行判定：仅当前方存在<b>同信号系统</b>列车，且真实间距足够（制动距离 + 安全间距）时放行。
	 */
	private static boolean mtrrealrail$dynamicRelease(long vehicleId) {
		final VehicleSnapshot self = TrafficRegistry.get(vehicleId);
		if (self == null) {
			return false;
		}

		final OptimizeSettings settings = DepotSettingsResolver.settingsForVehicle(vehicleId);
		if (!settings.dynamicBlockRelease) {
			return false;
		}

		final double searchDistance = Math.max(settings.pathLookaheadMeters, settings.safetyMarginMeters * 4);
		final DynamicOccupancy.Leader leader = DynamicOccupancy.findLeaderAhead(self, searchDistance);
		if (leader == null) {
			// 前方没有同信号系统的列车 → 该闭塞的占用并非来自本车前方
			// （可能是后方、其它分歧方向，或异常残留的预留）。允许进入且不抢占预留，
			// 由 MTR 的物理防撞与制动逻辑兜底；否则列车会被这种"看不见的占线"永久卡死。
			return true;
		}

		return leader.distanceMeters() >= self.brakingDistance() + settings.safetyMarginMeters;
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
