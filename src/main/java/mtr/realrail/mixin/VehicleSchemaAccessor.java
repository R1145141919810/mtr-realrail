package mtr.realrail.mixin;

import org.mtr.core.generated.data.VehicleSchema;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问 {@link VehicleSchema} 中受保护字段 {@code railProgress} 的 Accessor Mixin。
 *
 * <p>为什么不用 {@code @Shadow}：该字段声明在 {@code Vehicle} 的<b>父类</b>
 * （生成类 {@code org.mtr.core.generated.data.VehicleSchema}）中，Mixin 的
 * {@code @Shadow} 只在目标类自身的字段里查找，实测会报
 * {@code @Shadow field railProgress was not located in the target class org.mtr.core.data.Vehicle}
 * （已在真实 Forge 1.20.1 服务端 + MTR 4.0.5 复现并确认修复）。</p>
 *
 * <p>使用方式：{@code ((VehicleSchemaAccessor) (Object) vehicle).mtrrealrail$getRailProgress()}——
 * 该接口会被注入到 VehicleSchema 上，因此所有 Vehicle 子类实例都可强转。</p>
 */
@Mixin(VehicleSchema.class)
public interface VehicleSchemaAccessor {

	/** 车头沿整条路径的位置（行驶方向最前端）。 */
	@Accessor("railProgress")
	double mtrrealrail$getRailProgress();
}
