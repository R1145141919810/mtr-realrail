package mtr.realrail.mixin;

import mtr.realrail.SignalIsolationRegistry;
import mtr.realrail.SpeedLimitHelper;
import org.mtr.core.data.PathData;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.Vehicle;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2LongAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 需求一 + 需求二的 Vehicle 侧钩子。
 *
 * <p>目标类：{@code org.mtr.core.data.Vehicle}（Transport-Simulation-Core，随 MTR 4.0.x 发行）。
 * 三个注入点：</p>
 * <ol>
 *   <li>{@code simulate} HEAD：更新限速判定状态（边界跨越检测）与信号系统归属注册（需求一、二）。</li>
 *   <li>{@code simulateMoving} 中 {@code PathData.getSpeedLimitMetersPerMillisecond()} 调用
 *       （发行版 4.0.5 该方法内唯一一处）：重定向为车尾/节点模式感知的生效限速。</li>
 *   <li>{@code simulateMoving} 中 {@code Siding.getUpcomingSlowerSpeed(...)} 调用（唯一一处）：
 *       重定向为车尾感知的预制动。</li>
 * </ol>
 *
 * <p>安全制动（stoppingPoint 分支：信号红灯、平台停车、前方占用）与物理防撞
 * （VehiclePosition 重叠检测）完全不受影响。</p>
 *
 * <p>签名依据（已用 javap 核对 MTR 4.0.5 Forge 发行 jar）：</p>
 * <ul>
 *   <li>{@code public void simulate(long, ObjectArrayList, Long2LongAVLTreeMap)}</li>
 *   <li>{@code private void simulateMoving(long, ObjectArrayList, int)}</li>
 *   <li>{@code public double PathData.getSpeedLimitMetersPerMillisecond()}</li>
 *   <li>{@code public static double Siding.getUpcomingSlowerSpeed(ObjectList, int, double, double, double)}</li>
 *   <li>{@code protected double VehicleSchema.railProgress}（父类字段，经 {@link VehicleSchemaAccessor} 访问）</li>
 * </ul>
 *
 * <p>注意：MTR 发行 jar 把 fastutil 重定位到了 {@code org.mtr.libraries.it.unimi.dsi.fastutil}，
 * 因此描述符必须使用重定位后的类型（这也是本模组编译依赖使用发行 jar 而非 dev jar 的原因）。</p>
 */
@Mixin(Vehicle.class)
public abstract class VehicleSimulateMixin {

	/**
	 * 读取车头沿路径的位置。
	 * 字段 railProgress 声明在父类 VehicleSchema 中，因此经 Accessor Mixin 访问（见 {@link VehicleSchemaAccessor}）。
	 */
	private double mtrrealrail$railProgress() {
		return ((VehicleSchemaAccessor) (Object) this).mtrrealrail$getRailProgress();
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

		// 需求一：边界跨越检测 + 车尾通过判定
		SpeedLimitHelper.updateState(vehicleId, path, railProgress, vehicle.vehicleExtraData.getTotalVehicleLength(), headIndex);

		// 需求二：登记列车当前所属信号系统（车身覆盖各段 signalColors 的并集）。
		// 连续移动模式（飞机/船只/缆车）不参与信号逻辑，跳过。
		final TransportMode transportMode = vehicle.getTransportMode();
		if (transportMode != null && !transportMode.continuousMovement) {
			final IntAVLTreeSet colors = new IntAVLTreeSet();
			final double tailProgress = railProgress - vehicle.vehicleExtraData.getTotalVehicleLength();
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
	}

	/** 车头段限速 → 车尾/节点模式感知的生效限速（需求一）。 */
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
