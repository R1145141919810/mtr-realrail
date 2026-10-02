package mtr.realrail.client;

import mtr.realrail.MtrRealRail;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Set;

/**
 * 刷子（mtr:brush）右击轨道节点 → 记录节点坐标，供 MTR 原版轨道界面中的"节点限速判定"按钮使用。
 *
 * <p><b>本模组不再消费该交互</b>：右键后 MTR 原版界面（轨道形状 / 轨道样式）照常打开，
 * 原版逻辑完全不变；只有点击追加的按钮才会进入本模组的节点判定界面。</p>
 *
 * <p>按<b>注册表名称</b>匹配 MTR 的刷子物品与轨道节点方块，因此对 MTR 内部类名改动天然容错。
 * 4.0.x 实测：刷子为物品 {@code mtr:brush}，轨道节点方块为 {@code mtr:rail}
 * （缆车/飞机/船只节点为 {@code mtr:cable_car_node_*} / {@code mtr:airplane_node} /
 * {@code mtr:boat_node}）。</p>
 *
 * <p>仅注册在物理客户端（{@link Dist#CLIENT}），服务端不参与该交互。</p>
 */
@Mod.EventBusSubscriber(modid = MtrRealRail.MOD_ID, value = Dist.CLIENT)
public final class RailNodeInteractionHandler {

	private static final ResourceLocation MTR_BRUSH = new ResourceLocation("mtr", "brush");
	private static final Set<String> MTR_NODE_BLOCKS = Set.of(
			"rail",
			"cable_car_node_station",
			"cable_car_node_lower",
			"cable_car_node_upper",
			"airplane_node",
			"boat_node"
	);

	private RailNodeInteractionHandler() {
	}

	@SubscribeEvent
	public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		final Level level = event.getLevel();
		if (!level.isClientSide()) {
			return;
		}

		final ItemStack stack = event.getItemStack();
		if (!MTR_BRUSH.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
			return;
		}

		final BlockPos pos = event.getPos();
		final ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
		if (!"mtr".equals(blockId.getNamespace()) || !MTR_NODE_BLOCKS.contains(blockId.getPath())) {
			return;
		}

		// 只记录坐标；不调用 setUseBlock/setUseItem，事件照常继续 → MTR 原版界面正常打开。
		// 主手/副手都会触发，重复记录同一坐标无副作用。
		RailScreenBridge.setPendingNodePos(pos);
	}
}
