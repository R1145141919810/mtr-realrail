package mtr.realrail.mixin;

import mtr.realrail.NodeModeNetwork;
import mtr.realrail.client.RailScreenBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.mtr.mapping.mapper.ScreenExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 MTR 原版轨道界面（{@code org.mtr.mod.screen.RailModifierScreen} —— 手持刷子右键轨道节点后
 * 打开的"轨道形状 / 轨道样式"界面）中追加一个"节点限速判定"按钮。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>右键交互完全交还 MTR：本模组不拦截任何事件，原版界面与全部原版功能保持不变，
 *       只是界面里多出一个按钮；点击后进入本模组的节点判定界面，关闭后自动返回该界面。</li>
 *   <li>目标类用字符串 {@code targets} 指定，不强制加载；配合独立的非必需 mixin 配置
 *       （{@code mtrrealrail.railgui.mixins.json}：required=false），注入失败只记日志、不影响游戏。</li>
 *   <li>mixin 继承 MTR 映射层基类 {@link ScreenExtension}（其继承链末端即原版
 *       {@code net.minecraft.client.gui.screens.Screen}），因此可直接使用<b>原版按钮与控件 API</b>
 *       （{@code addRenderableWidget} + {@code Button.builder}），与原版/映射层控件共用同一套
 *       控件列表与渲染流程，且避免依赖 MTR 的 PressAction 等映射类型。</li>
 *   <li>{@code init2} 是映射层的控件初始化钩子（MTR 自己也在其中添加控件），TAIL 注入保证
 *       追加在原版控件之后；窗口缩放导致界面重建时会再次执行，按钮随之重建。</li>
 * </ul>
 */
@Mixin(targets = "org.mtr.mod.screen.RailModifierScreen")
public abstract class RailModifierScreenMixin extends ScreenExtension {

	@Inject(method = "init2", at = @At("TAIL"))
	private void mtrrealrail$addNodeModeButton(CallbackInfo callbackInfo) {
		final BlockPos nodePos = RailScreenBridge.getPendingNodePos();
		if (nodePos == null) {
			// 不是通过"刷子右键轨道节点"打开的界面（或记录已过期）→ 保持原版界面不变
			return;
		}

		// 位置：MTR 原版两行控件位于 y=0 / y=25，本按钮错开到 y=50，不遮挡原版控件
		addRenderableWidget(Button.builder(Component.literal("节点限速判定"), button -> {
			// 记住当前 MTR 界面，节点判定界面关闭后返回
			final Screen currentScreen = Minecraft.getInstance().screen;
			if (currentScreen != null) {
				RailScreenBridge.rememberRailScreen(currentScreen);
			}
			// 判定方式存于服务端，请求服务端下发界面（携带当前模式）
			NodeModeNetwork.requestOpen(nodePos);
		}).bounds(0, 50, 160, 20).build());
	}
}
