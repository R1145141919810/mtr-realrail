package mtr.realrail.mixin;

import mtr.realrail.SignalIsolationRegistry;
import mtr.realrail.SpeedLimitHelper;
import mtr.realrail.traffic.DepotSettingsResolver;
import mtr.realrail.traffic.DepartureCoordinator;
import mtr.realrail.traffic.OptimizeSettings;
import mtr.realrail.traffic.RouteRefresher;
import mtr.realrail.traffic.TrafficRegistry;
import mtr.realrail.traffic.VehicleSnapshot;
import org.mtr.core.data.Data;
import org.mtr.core.data.PathData;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.Vehicle;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2LongAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 需求一 + 需求二 + 新增调度功能的 Vehicle 侧钩子。
 *
 * <p>目标类：{@code org.mtr.core.data.Vehicle}（Transport-Simulation-Core，随 MTR 4.0.x 发行）。</p>
 * <ol>
 *   <li>{@code simulate} HEAD：限速判定状态（需求一）、信号系统归属（需求二）、
 *       <b>动态轨道占用快照</b>与<b>动态发车/避让决策</b>（新功能）。</li>
 *   <li>{@code simulateMoving} 中 {@code PathData.getSpeedLimitMetersPerMillisecond()} 调用：
 *       车尾/节点模式感知的生效限速。</li>
 *   <li>{@code simulateMoving} 中 {@code Siding.getUpcomingSlowerSpeed(...)} 调用：车尾感知预制动。</li>
 *   <li>{@code simulateStopped} RETURN：把停站计时压住（等待让行），即"动态发车"的执行端。</li>
 * </ol>
 *
 * <p>签名依据（javap 核对 MTR 4.0.5 Forge 发行 jar）：{@code simulate(long, ObjectArrayList, Long2LongAVLTreeMap)}、
 * {@code simulateStopped(long, ObjectArrayList, int)}、{@code protected double VehicleSchema.railProgress/speed/elapsedDwellTime}、
 * {@code protected final Data NameColorDataBaseSchema.data}。</p>
 */
@Mixin(Vehicle.class)
public abstract class VehicleSimulateMixin {

	/** Vehicle 自身的私有字段（客户端侧车辆不参与调度逻辑）。 */
	@Shadow
	@Final
	private boolean isClientside;

	/**
	 * 读取车头沿路径的位置。
	 * 字段 railProgress 声明在父类 VehicleSchema 中，因此经 Accessor Mixin 访问（见 {@link VehicleSchemaAccessor}）。
	 */
	private double mtrrealrail$railProgress() {
		return ((VehicleSchemaAccessor) (Object) this).mtrrealrail$getRailProgress();
	}

	/**
	 * 站台停站时长（毫秒）：与 MTR {@code simulateStopped} 中"停在节点后"分支的取值一致
	 * （{@code currentIndex - 1} 段的 dwellTime）。
	 */
	private static long mtrrealrail$totalDwellMillis(ObjectList<PathData> path, int headIndex, double railProgress) {
		final PathData currentPathData = Utilities.getElement(path, headIndex);
		if (currentPathData == null || railProgress != currentPathData.getStartDistance()) {
			return 0;
		}
		final PathData previousPathData = Utilities.getElement(path, headIndex - 1);
		return previousPathData == null ? 0 : previousPathData.getDwellTime();
	}

	@Inject(method = "simulate", at = @At("HEAD"))
	private void mtrrealrail$onSimulateHead(long millisElapsed, ObjectArrayList<?> vehiclePositions, Long2LongAVLTreeMap vehicleTimesAlongRoute, CallbackInfo callbackInfo) {
		final Vehicle vehicle = (Vehicle) (Object) this;
		if (vehicle.vehicleExtraData == null || vehicle.vehicleExtraData.immutablePath == null || vehicle.vehicleExtraData.immutablePath.isEmpty()) {
			return;
		}

		final long vehicleId = vehicle.getId();
		final ObjectList<PathData> path = vehicle.vehicleExtraData.immutablePath;
		final double railProgress = mtrrealrail$railProgress();
		final int headIndex = Utilities.getIndexFromConditionalList(path, railProgress);
		final double totalVehicleLength = vehicle.vehicleExtraData.getTotalVehicleLength();

		// 需求一：边界跨越检测 + 车尾通过判定
		SpeedLimitHelper.updateState(vehicleId, path, railProgress, totalVehicleLength, headIndex);

		// 需求二：登记列车当前所属信号系统（车身覆盖各段 signalColors 的并集）
		final TransportMode transportMode = vehicle.getTransportMode();
		if (transportMode != null && !transportMode.continuousMovement) {
			final IntAVLTreeSet colors = new IntAVLTreeSet();
			final double tailProgress = railProgress - totalVehicleLength;
			int tailIndex = Utilities.getIndexFromConditionalList(path, tailProgress);
			if (tailIndex < 0) {
				tailIndex = 0;
			}
			final int endIndex = Math.max(headIndex, 0);
			for (int index = tailIndex; index <= endIndex && index < path.size(); index++) {
				final PathData pathData = Utilities.getElement(path, index);
				if (pathData != null) {
					colors.addAll(pathData.getSignalColors());
				}
			}
			SignalIsolationRegistry.update(vehicleId, colors);
		}

		if (isClientside) {
			return;
		}

		// 新功能：动态轨道占用快照 + 自动最优路径规划/动态发车避让决策
		final Data data = ((NameColorDataBaseSchemaAccessor) (Object) this).mtrrealrail$getData();
		final long nowMillis = data == null ? System.currentTimeMillis() : data.getCurrentMillis();
		final VehicleSchemaAccessor accessor = (VehicleSchemaAccessor) (Object) this;

		final VehicleSnapshot snapshot = new VehicleSnapshot(vehicleId, path, railProgress, accessor.mtrrealrail$getSpeed(),
				totalVehicleLength, vehicle.vehicleExtraData.getDeceleration(), headIndex, accessor.mtrrealrail$getElapsedDwellTime(),
				mtrrealrail$totalDwellMillis(path, headIndex, railProgress), vehicle.vehicleExtraData.getThisRouteId(),
				vehicle.vehicleExtraData.getSidingId(), nowMillis);
		TrafficRegistry.update(snapshot, data);

		// 新功能：车站站台智能调度（站台占用 → 进站前引导/等待）
		mtr.realrail.traffic.PlatformDispatcher.tick(snapshot, data, System.currentTimeMillis());

		final OptimizeSettings settings = DepotSettingsResolver.settingsForVehicle(vehicleId);
		// 注意：等待计时用墙钟（System.currentTimeMillis），与游戏内时钟解耦，
		// 避免伺服/暂停/时间跳变时"等待上限"永远不到达而把列车压死在站台。
		if (DepartureCoordinator.evaluate(snapshot, settings, System.currentTimeMillis()) == DepartureCoordinator.Decision.REPLAN_AND_DEPART) {
			// 等待超时 → 强制发车，并让 MTR 用最新路况重新生成该车厂路径（"变更路径"）
			RouteRefresher.refreshForVehicle(data, vehicleId);
		}
	}

	/**
	 * 动态发车与避让的执行端：需要等待让行时，把停站计时压在"关门时刻"之前，
	 * MTR 的 {@code simulateStopped} 就不会触发 {@code startUp}（发车）。
	 * 列车保持开门等待，直到 {@link DepartureCoordinator} 改变决策。
	 */
	@Inject(method = "simulateStopped", at = @At("RETURN"))
	private void mtrrealrail$applyDepartureHold(long millisElapsed, ObjectArrayList<?> vehiclePositions, int currentIndex, CallbackInfo callbackInfo) {
		if (isClientside) {
			return;
		}
		final Vehicle vehicle = (Vehicle) (Object) this;
		final long vehicleId = vehicle.getId();
		if (!DepartureCoordinator.shouldHold(vehicleId) && !mtr.realrail.traffic.PlatformDispatcher.shouldWaitForPlatform(vehicleId)) {
			return;
		}

		final PathData currentPathData = Utilities.getElement(vehicle.vehicleExtraData.immutablePath, currentIndex - 1);
		final long totalDwellMillis = currentPathData == null ? 0 : currentPathData.getDwellTime();
		if (totalDwellMillis <= 0) {
			return;
		}

		final long doorCloseTime = Math.max(totalDwellMillis / 2, totalDwellMillis - 3200L - 1000L);
		final VehicleSchemaAccessor accessor = (VehicleSchemaAccessor) (Object) this;
		if (accessor.mtrrealrail$getElapsedDwellTime() >= doorCloseTime) {
			accessor.mtrrealrail$setElapsedDwellTime(Math.max(0, doorCloseTime - 1));
		}
	}

	/**
	 * 替换车头段限速读取（simulateMoving ATO 分支中唯一一处
	 * {@code immutablePath.get(currentIndex).getSpeedLimitMetersPerMillisecond()} 调用）。
	 */
	@Redirect(method = "simulateMoving", at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/PathData;getSpeedLimitMetersPerMillisecond()D"))
	private double mtrrealrail$getEffectiveSpeedLimit(PathData pathData) {
		final Vehicle vehicle = (Vehicle) (Object) this;
		return SpeedLimitHelper.getEffectiveSpeedLimit(vehicle.getId(), vehicle.vehicleExtraData.immutablePath, pathData);
	}

	/**
	 * 原版预制动 → 车尾感知预制动（TAIL 判定节点前不预减速）。
	 *
	 * <p>注意两点（均已在真实 Forge 1.20.1 服务端 + MTR 4.0.5 上验证）：</p>
	 * <ol>
	 *   <li>调用点描述符为 {@code (Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectList;IDDD)D}
	 *       （ObjectList + int + 3 个 double）。少写一个 D 只会得到
	 *       {@code failed injection check, (0/1) succeeded}，不会提示描述符不匹配，务必按字节码核对。</li>
	 *   <li>handler 的 static 修饰符必须与<b>被注入的方法</b>（{@code simulateMoving}，实例方法）一致，
	 *       写成 static 会报 {@code 'static' modifier of handler method does not match target}。</li>
	 * </ol>
	 */
	@Redirect(method = "simulateMoving", at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/Siding;getUpcomingSlowerSpeed(Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectList;IDDD)D"))
	private double mtrrealrail$getUpcomingSlowerSpeed(ObjectList<PathData> path, int currentIndex, double checkRailProgress, double currentSpeed, double deceleration) {
		return SpeedLimitHelper.getUpcomingSlowerSpeed(path, currentIndex, checkRailProgress, currentSpeed, deceleration);
	}
}
