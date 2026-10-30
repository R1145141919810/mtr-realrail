package mtr.realrail.client;

import mtr.realrail.MtrRealRail;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 「优化线路配置」快捷键（默认 <b>O</b>）。
 *
 * <p>用途：即使界面里的按钮被其它附属模组的控件遮挡、或玩家习惯纯键盘操作，
 * 也能在车厂配置界面中直接打开本模组的界面（见 {@link OptimizeScreenHook}）。</p>
 */
@Mod.EventBusSubscriber(modid = MtrRealRail.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class OptimizeKeyMappings {

	public static final String CATEGORY = "key.categories.mtrrealrail";
	public static final KeyMapping OPEN_OPTIMIZE = new KeyMapping("key.mtrrealrail.optimize", GLFW.GLFW_KEY_O, CATEGORY);

	private OptimizeKeyMappings() {
	}

	@SubscribeEvent
	public static void register(RegisterKeyMappingsEvent event) {
		event.register(OPEN_OPTIMIZE);
	}
}
