package mtr.realrail.mixin;

import mtr.realrail.MtrRealRail;
import mtr.realrail.OptimizeNetwork;
import mtr.realrail.client.OptimizeScreenBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.mtr.core.data.Depot;
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
 * 在 MTR 原版<b>车厂配置界面</b>（{@code org.mtr.mod.screen.EditDepotScreen} —— 铁路仪表板 → 车厂 →
 * 点击车厂后打开的配置菜单）中追加一个"优化线路配置"按钮。
 *
 * <p>位置与控件类型都<b>复用 MTR 自身的布局与控件</b>，因此与 MTR 原版按钮完全一致：</p>
 * <ul>
 *   <li>控件：{@link ButtonWidgetExtension} + {@code ScreenExtension.addChild(ClickableWidget)}
 *       + {@code IDrawing.setPositionAndWidth}——与 MTR 自己创建按钮的方式相同；</li>
 *   <li>位置：MTR 右栏（x = {@code rightPanelsX}，宽度 = 屏宽 - rightPanelsX），
 *       y 取"复选框下方"：{@code 104 + (显示巡航高度 ? 24 : 0) + 24}。
 *       这一段是 MTR 右栏唯一不被占用的空档（上方是编辑指令/即时发车/生成路线/清除列车/复选框，
 *       下方是 MTR 在 render 末尾绘制的线路生成信息文本），避免按钮被遮挡而"几乎不可见"；</li>
 *   <li>键盘：界面中无控件获得焦点时把焦点给本按钮，可直接回车打开；另外注册了快捷键 O 兜底
 *       （见 {@code OptimizeScreenHook}）。</li>
 * </ul>
 *
 * <p>目标类用字符串 {@code targets} 指定，配合非必需 mixin 配置
 * （{@code mtrrealrail.railgui.mixins.json}：required=false），注入失败只记日志、不影响游戏。</p>
 */
@Mixin(targets = "org.mtr.mod.screen.EditDepotScreen")
public abstract class EditDepotScreenMixin extends ScreenExtension {

	@Inject(method = "init2", at = @At("TAIL"))
	private void mtrrealrail$addOptimizeButton(CallbackInfo callbackInfo) {
		final Screen currentScreen = Minecraft.getInstance().screen;
		final Depot depot = OptimizeScreenBridge.readDepot(currentScreen);
		if (depot == null) {
			MtrRealRail.LOGGER.warn("[{}] 未能从车厂界面（{}）读取到 Depot，跳过\"优化线路配置\"按钮",
					MtrRealRail.MOD_ID, currentScreen == null ? "null" : currentScreen.getClass().getName());
			return;
		}

		// 复用 MTR 的布局参数：右栏起点，以及"MTR 文字块之间的空档"
		final int rightPanelsX = OptimizeScreenBridge.readIntField(this, "rightPanelsX", -1);
		final boolean showCruisingAltitude = OptimizeScreenBridge.readBooleanField(this, "showCruisingAltitude", false);
		final int panelX = rightPanelsX > 0 ? rightPanelsX : Math.max(0, width - 200);
		final int panelWidth = Math.max(40, width - panelX);

		// MTR 在 render 末尾按下面的公式绘制"本车厂的侧线数目"一行文字；
		// 其下方 40 px 处才开始绘制线路生成信息块（"xx 分钟前刷新路径"等）。
		// 因此把按钮放在这两行文字之间：textY + 15（按钮高 20，上下各留约 5 px）。
		final Object checkbox = OptimizeScreenBridge.readFieldObject(this, "checkboxRepeatIndefinitely");
		final boolean checkboxVisible = checkbox instanceof net.minecraft.client.gui.components.AbstractWidget widget && widget.visible;
		final int textY = 44 + (checkboxVisible ? 4 : 3) * 20 + (showCruisingAltitude ? 24 : 0) + 6;
		final int buttonY = Math.min(Math.max(0, height - 22), textY + 15);

		// 注意：不能用 lambda —— MTR 的 PressAction 同时继承原版 Button.OnPress，
		// 而其默认实现用的是 SRG 名（m_93750_），在开发环境（官方映射）下接口会有两个抽象方法，
		// javac 因此判定"不是函数接口"。这里显式实现两个方法（reobf 后两者都会指向同一入口）。
		final org.mtr.mapping.holder.PressAction pressAction = new org.mtr.mapping.holder.PressAction() {
			@Override
			public void onPress2(org.mtr.mapping.holder.ButtonWidget widget) {
				mtrrealrail$openOptimize(currentScreen, depot);
			}

			@Override
			public void onPress(net.minecraft.client.gui.components.Button widget) {
				mtrrealrail$openOptimize(currentScreen, depot);
			}
		};
		final ButtonWidgetExtension button = new ButtonWidgetExtension(0, 0, 0, 20, TextHelper.literal("优化线路配置"), pressAction);
		addChild(new ClickableWidget(button));
		IDrawing.setPositionAndWidth(button, panelX, buttonY, panelWidth);

		// 键盘可达性：界面打开时若无控件获得焦点，则把焦点给本按钮（此时回车即可打开）
		if (getFocused() == null) {
			setFocused(button);
		}

		MtrRealRail.LOGGER.debug("[{}] 车厂界面（{}）已添加\"优化线路配置\"按钮：x={}, y={}, w={}",
				MtrRealRail.MOD_ID, depot.getName(), panelX, buttonY, panelWidth);
	}

	/** 打开"优化线路配置"界面：记住车厂界面（ESC 返回用）→ 立即打开 → 再请求服务端已保存配置。 */
	private void mtrrealrail$openOptimize(Screen currentScreen, Depot depot) {
		OptimizeScreenBridge.rememberDepot(currentScreen, depot.getId(), depot.getName());
		OptimizeScreenBridge.openOptimizeScreen();
		OptimizeNetwork.requestSettings(depot.getId());
	}
}
