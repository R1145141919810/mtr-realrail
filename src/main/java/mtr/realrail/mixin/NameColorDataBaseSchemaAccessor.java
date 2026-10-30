package mtr.realrail.mixin;

import org.mtr.core.data.Data;
import org.mtr.core.generated.data.NameColorDataBaseSchema;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问 {@link NameColorDataBaseSchema} 中的 {@code protected final Data data}。
 *
 * <p>服务端该字段实际指向 {@code org.mtr.core.simulation.Simulator}（继承自 {@link Data}），
 * 是全网的线路/车厂/车站/侧线数据入口，动态轨道占用与路径优化都需要它。</p>
 */
@Mixin(NameColorDataBaseSchema.class)
public interface NameColorDataBaseSchemaAccessor {

	@Accessor("data")
	Data mtrrealrail$getData();
}
