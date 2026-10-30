package mtr.realrail.mixin;

import mtr.realrail.MtrRealRail;
import mtr.realrail.client.OptimizeScreenBridge;
import mtr.realrail.client.RouteDispatchScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import org.mtr.core.data.Route;
import org.mtr.mapping.holder.ClickableWidget;
import org.mtr.mapping.mapper.ButtonWidgetExtension;
import org.mtr.mapping.mapper.ScreenExtension;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.client.IDrawing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 MTR <b>线路配置界面</b>（{@code EditRouteScreen}）中追加"线路智能调度站台"按钮，
 * 点击打开本模组的专用界面（{@link RouteDispatchScreen}）：
 * 按车站多选允许停靠的站台。
 */
@Mixin(targets = "org.mtr.mod.screen.EditRouteScreen")
public abstract class EditRouteScreenMixin extends ScreenExtension {

	@Inject(method = "init2", at = @At("TAIL"))
	private void mtrrealrail$addRouteDispatchButton(CallbackInfo callbackInfo) {
		final Screen currentScreen = Minecraft.getInstance().screen;
		final Route route = OptimizeScreenBridge.readObject(currentScreen, Route.class);
		if (route == null) {
			MtrRealRail.LOGGER.debug("[{}] 线路界面未能读取 Route 对象（{}）", MtrRealRail.MOD_ID,
					currentScreen == null ? "null" : currentScreen.getClass().getName());
			return;
		}

		final ButtonWidgetExtension button = new ButtonWidgetExtension(0, 0, 0, 20,
				TextHelper.literal("线路智能调度站台"), mtrrealrail$pressAction(currentScreen, route));
		addChild(new ClickableWidget(button));

		// 位置：放在"路线号码"输入框下方一段距离（该输入框在所有线路类型下都存在且不与其他控件重叠）
		final Object anchor = OptimizeScreenBridge.readFieldObject(this, "textFieldLightRailRouteNumber");
		final int x;
		final int y;
		if (anchor instanceof AbstractWidget widget) {
			x = widget.getX();
			y = widget.getY() + widget.getHeight() + 10;
		} else if (OptimizeScreenBridge.readFieldObject(this, "buttonRouteType") instanceof AbstractWidget typeButton) {
			// 退化 1：线路类型按钮下方再让开一行，避免与复选框重叠
			x = typeButton.getX();
			y = typeButton.getY() + typeButton.getHeight() + 46;
		} else {
			x = Math.max(0, width - 174);
			y = Math.max(0, height - 26);
		}
		IDrawing.setPositionAndWidth(button, x, y, 170);
		MtrRealRail.LOGGER.info("[{}] 线路界面已添加\"线路智能调度站台\"按钮：线路={}（{}），位置=({}, {})，锚点={}",
				MtrRealRail.MOD_ID, route.getId(), route.getName(), x, y, anchor == null ? "无（右下角兜底）" : anchor.getClass().getSimpleName());
	}

	private org.mtr.mapping.holder.PressAction mtrrealrail$pressAction(Screen currentScreen, Route route) {
		return new org.mtr.mapping.holder.PressAction() {
			@Override
			public void onPress2(org.mtr.mapping.holder.ButtonWidget widget) {
				Minecraft.getInstance().setScreen(new RouteDispatchScreen(currentScreen, route));
			}

			@Override
			public void onPress(net.minecraft.client.gui.components.Button widget) {
				Minecraft.getInstance().setScreen(new RouteDispatchScreen(currentScreen, route));
			}
		};
	}
}
