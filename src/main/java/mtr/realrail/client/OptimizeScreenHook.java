package mtr.realrail.client;

import mtr.realrail.MtrRealRail;
import mtr.realrail.OptimizeNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.mtr.core.data.Depot;

/**
 * 客户端按键入口：在 MTR 车厂配置界面中按下快捷键（默认 <b>O</b>）即可打开"优化线路配置"界面。
 *
 * <p>{@code ScreenEvent.KeyPressed.Pre} 由 Forge 在界面收到按键之前触发，
 * 因此在 GUI 打开时也能工作（普通 KeyMapping 只在无界面时生效）。正在输入文本时（焦点在输入框）
 * 不拦截，避免影响车厂名称等输入。</p>
 */
@Mod.EventBusSubscriber(modid = MtrRealRail.MOD_ID, value = Dist.CLIENT)
public final class OptimizeScreenHook {

	private OptimizeScreenHook() {
	}

	@SubscribeEvent
	public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
		if (!OptimizeKeyMappings.OPEN_OPTIMIZE.matches(event.getKeyCode(), event.getScanCode())) {
			return;
		}
		final Screen screen = event.getScreen();
		if (screen.getFocused() instanceof EditBox) {
			return;
		}
		final Depot depot = OptimizeScreenBridge.readDepot(screen);
		if (depot == null) {
			return;
		}
		OptimizeScreenBridge.rememberDepot(screen, depot.getId(), depot.getName());
		OptimizeScreenBridge.openOptimizeScreen();
		OptimizeNetwork.requestSettings(depot.getId());
		event.setCanceled(true);
		MtrRealRail.LOGGER.debug("[{}] 通过快捷键打开车厂 {} 的\"优化线路配置\"", MtrRealRail.MOD_ID, depot.getName());
	}
}
