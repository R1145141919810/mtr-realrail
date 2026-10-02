package mtr.realrail.client;

import mtr.realrail.NodeModeNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * 轨道节点限速判定配置界面（客户端）。
 *
 * <p>入口：手持刷子（mtr:brush）右击轨道节点 → MTR 原版轨道界面（轨道形状/样式）→ 点击其中的
 * "节点限速判定"按钮 → 服务端通过 {@link NodeModeNetwork#sendOpen} 打开本界面。</p>
 *
 * <p>点击按钮切换该节点的判定方式：车头进入立即变更（原版）/ 车尾通过后变更（推荐），
 * 服务端切换后回发新状态刷新本界面；关闭时返回 MTR 原版轨道界面。</p>
 */
public class NodeModeScreen extends Screen {

	private final BlockPos pos;
	private final boolean tailMode;

	public NodeModeScreen(BlockPos pos, boolean tailMode) {
		super(Component.literal("轨道节点 · 限速变更判定"));
		this.pos = pos;
		this.tailMode = tailMode;
	}

	@Override
	protected void init() {
		final Component modeText = tailMode
				? Component.literal("当前判定：车尾通过后变更速度（推荐）")
				: Component.literal("当前判定：车头进入立即变更速度（原版）");
		addRenderableWidget(Button.builder(modeText, button -> NodeModeNetwork.sendToggle(pos))
				.bounds(width / 2 - 130, height / 2 - 8, 260, 20).build());
		addRenderableWidget(Button.builder(Component.literal("关闭"), button -> onClose())
				.bounds(width / 2 - 40, height / 2 + 20, 80, 20).build());
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		renderBackground(guiGraphics);
		guiGraphics.drawCenteredString(font, title, width / 2, height / 2 - 52, 0xFFFFFF);
		guiGraphics.drawCenteredString(font,
				Component.literal("节点位置: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()),
				width / 2, height / 2 - 38, 0xAAAAAA);
		guiGraphics.drawCenteredString(font,
				Component.literal("点击上方按钮切换判定方式（立即写入世界存档）"),
				width / 2, height / 2 + 48, 0x808080);
		super.render(guiGraphics, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/**
	 * 关闭界面：若本界面是从 MTR 原版轨道界面（刷子右键轨道节点打开）跳转而来，
	 * 则返回该界面；否则走默认逻辑返回游戏。
	 */
	@Override
	public void onClose() {
		if (!RailScreenBridge.restoreRailScreen()) {
			super.onClose();
		}
	}
}
