package mtr.realrail.client;

import mtr.realrail.OptimizeNetwork;
import mtr.realrail.traffic.OptimizeSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 「优化线路配置」界面（客户端）。
 *
 * <p>入口：铁路仪表板 → 车厂 → 车厂配置界面中的"优化线路配置"按钮
 * （由 {@code EditDepotScreenMixin} 注入），或在车厂界面内按快捷键 O。</p>
 *
 * <p>布局（自上而下，<b>各区域互不重叠</b>）：</p>
 * <ol>
 *   <li>标题 + 车厂信息（y=8 / 19）；</li>
 *   <li>黄色提示文字（3 行，小屏时 2 行）与绿色状态行；</li>
 *   <li>参数区：左列三个开关、右列五个数值（`-` / `+` 步进）；</li>
 *   <li>底部动作按钮：应用 / 应用并刷新路线 / 恢复全局默认 / 返回车厂配置（ESC），
 *       贴着屏幕底部分两行排列。</li>
 * </ol>
 *
 * <p>参数区起点由提示文字块高度决定，动作按钮行由屏高决定；当两者会重叠时自动压缩行距，
 * 因此窗口分辨率 / GUI 缩放变化时文字也不会压到按钮上。</p>
 */
public class DepotOptimizeScreen extends Screen {

	private static final int ROW_HEIGHT = 20;
	private static final int HINT_LINE_HEIGHT = 11;
	private static final int HINT_TOP_Y = 30;
	private static final int COLUMN_A_X = 6;
	private static final int COLUMN_B_X = 162;
	private static final int TOGGLE_WIDTH = 150;
	private static final int STEPPER_LABEL_WIDTH = 96;
	private static final int STEPPER_BUTTON_WIDTH = 24;
	private static final int ACTION_WIDTH = 152;

	private final Screen previousScreen;

	public DepotOptimizeScreen(Screen previousScreen) {
		super(Component.literal("优化线路配置"));
		this.previousScreen = previousScreen;
	}

	/** 服务端下发新配置后重建控件（{@link ClientPacketHandler#onOptimizeSync}）。 */
	public void refresh() {
		rebuildWidgets();
	}

	/** 小屏（GUI 高度较小）时使用紧凑行距并少显示一行提示。 */
	private boolean compact() {
		return height < 220;
	}

	private int rowStep() {
		return compact() ? 16 : 20;
	}

	/** 提示文字占用的行数（含状态行）。 */
	private int hintLineCount() {
		return compact() ? 3 : 4;
	}

	/** 参数区起始 y：紧跟在提示文字块下方。 */
	private int rowsTopY() {
		return HINT_TOP_Y + hintLineCount() * HINT_LINE_HEIGHT + 6;
	}

	@Override
	protected void init() {
		final OptimizeSettings settings = OptimizeScreenBridge.settings();
		final int step = rowStep();
		final int rowsTop = rowsTopY();

		// ---- 左列：三个开关 ----
		int y = rowsTop;
		addRenderableWidget(Button.builder(toggleText("动态闭塞放行（移动闭塞）", settings.dynamicBlockRelease),
				button -> {
					settings.dynamicBlockRelease = !settings.dynamicBlockRelease;
					rebuildWidgets();
				}).bounds(COLUMN_A_X, y, TOGGLE_WIDTH, ROW_HEIGHT).build());
		y += step;
		addRenderableWidget(Button.builder(toggleText("自动最优路径规划", settings.routeOptimize),
				button -> {
					settings.routeOptimize = !settings.routeOptimize;
					rebuildWidgets();
				}).bounds(COLUMN_A_X, y, TOGGLE_WIDTH, ROW_HEIGHT).build());
		y += step;
		addRenderableWidget(Button.builder(toggleText("动态发车与避让", settings.departureGuard),
				button -> {
					settings.departureGuard = !settings.departureGuard;
					rebuildWidgets();
				}).bounds(COLUMN_A_X, y, TOGGLE_WIDTH, ROW_HEIGHT).build());
		y += step;
		// 车厂在线路中间：车辆按最短进路去最近车站投入运行；无班次时段自动回场（依赖 tsc-dispatch + mtbridge）
		addRenderableWidget(Button.builder(toggleText("车厂在线路中间", settings.midLineDepot),
				button -> {
					settings.midLineDepot = !settings.midLineDepot;
					rebuildWidgets();
				}).bounds(COLUMN_A_X, y, TOGGLE_WIDTH, ROW_HEIGHT).build());

		// ---- 右列：五个数值 ----
		int rightY = rowsTop;
		addStepper(rightY, "安全间距", (int) settings.safetyMarginMeters + " m",
				() -> settings.safetyMarginMeters = Math.max(5, settings.safetyMarginMeters - 10),
				() -> settings.safetyMarginMeters = Math.min(200, settings.safetyMarginMeters + 10));
		rightY += step;
		addStepper(rightY, "前瞻距离", (int) settings.pathLookaheadMeters + " m",
				() -> settings.pathLookaheadMeters = Math.max(100, settings.pathLookaheadMeters - 100),
				() -> settings.pathLookaheadMeters = Math.min(2000, settings.pathLookaheadMeters + 100));
		rightY += step;
		addStepper(rightY, "停站阈值", settings.dwellThresholdSeconds + " s",
				() -> settings.dwellThresholdSeconds = Math.max(0, settings.dwellThresholdSeconds - 1),
				() -> settings.dwellThresholdSeconds = Math.min(120, settings.dwellThresholdSeconds + 1));
		rightY += step;
		addStepper(rightY, "冲突窗口", settings.conflictWindowSeconds + " s",
				() -> settings.conflictWindowSeconds = Math.max(1, settings.conflictWindowSeconds - 1),
				() -> settings.conflictWindowSeconds = Math.min(60, settings.conflictWindowSeconds + 1));
		rightY += step;
		addStepper(rightY, "最长等待", settings.maxHoldSeconds + " s",
				() -> settings.maxHoldSeconds = Math.max(0, settings.maxHoldSeconds - 10),
				() -> settings.maxHoldSeconds = Math.min(600, settings.maxHoldSeconds + 10));

		// ---- 底部：动作按钮（贴底两行，若与参数区冲突则整体上移，保证不重叠） ----
		final int rowsBottom = rowsTop + 4 * step + ROW_HEIGHT;
		int actionY = Math.max(rowsBottom + 4, height - 48);
		if (actionY + 2 * ROW_HEIGHT + 4 > height) {
			actionY = Math.max(rowsBottom + 4, height - 2 * ROW_HEIGHT - 4);
		}
		addRenderableWidget(Button.builder(Component.literal("应用"), button -> apply(false))
				.bounds(COLUMN_A_X, actionY, ACTION_WIDTH, ROW_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.literal("应用并刷新路线"), button -> apply(true))
				.bounds(COLUMN_B_X, actionY, ACTION_WIDTH, ROW_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.literal("恢复全局默认"), button -> OptimizeNetwork.sendReset(OptimizeScreenBridge.depotId()))
				.bounds(COLUMN_A_X, actionY + ROW_HEIGHT + 4, ACTION_WIDTH, ROW_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.literal("返回车厂配置（ESC）"), button -> onClose())
				.bounds(COLUMN_B_X, actionY + ROW_HEIGHT + 4, ACTION_WIDTH, ROW_HEIGHT).build());
	}

	private void addStepper(int y, String label, String value, Runnable onMinus, Runnable onPlus) {
		addRenderableWidget(Button.builder(Component.literal(label + ": " + value), button -> {
		}).bounds(COLUMN_B_X, y, STEPPER_LABEL_WIDTH, ROW_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.literal("-"), button -> {
			onMinus.run();
			rebuildWidgets();
		}).bounds(COLUMN_B_X + STEPPER_LABEL_WIDTH + 4, y, STEPPER_BUTTON_WIDTH, ROW_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.literal("+"), button -> {
			onPlus.run();
			rebuildWidgets();
		}).bounds(COLUMN_B_X + STEPPER_LABEL_WIDTH + 4 + STEPPER_BUTTON_WIDTH + 4, y, STEPPER_BUTTON_WIDTH, ROW_HEIGHT).build());
	}

	private static Component toggleText(String label, boolean enabled) {
		return Component.literal(label + ": " + (enabled ? "开" : "关"));
	}

	private void apply(boolean refreshRoutes) {
		OptimizeNetwork.sendUpdate(OptimizeScreenBridge.depotId(), OptimizeScreenBridge.settings(), refreshRoutes);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		renderBackground(guiGraphics);
		guiGraphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
		guiGraphics.drawCenteredString(font,
				Component.literal("车厂: " + (OptimizeScreenBridge.depotName().isEmpty() ? "-" : OptimizeScreenBridge.depotName())
						+ "（id " + OptimizeScreenBridge.depotId() + "）"),
				width / 2, 19, 0xAAAAAA);

		// 提示文字固定在标题下方的独立区域，不会与按钮重叠
		int hintY = HINT_TOP_Y;
		guiGraphics.drawString(font, Component.literal("提示：本页改动作用于该车厂的列车调度（动态轨道占用 / 路径规划 / 发车避让）。"),
				COLUMN_A_X, hintY, 0xFFFF80);
		hintY += HINT_LINE_HEIGHT;
		guiGraphics.drawString(font, Component.literal("操作完成后需重新刷新路线：请点击下方\"应用并刷新路线\"，"),
				COLUMN_A_X, hintY, 0xFFFF80);
		hintY += HINT_LINE_HEIGHT;
		if (!compact()) {
			guiGraphics.drawString(font, Component.literal("或返回车厂配置界面点击 MTR 原版的\"生成路线\"按钮。"),
					COLUMN_A_X, hintY, 0xFFFF80);
			hintY += HINT_LINE_HEIGHT;
		}
		guiGraphics.drawString(font, Component.literal("状态：" + (OptimizeScreenBridge.status().isEmpty() ? "读取中…" : OptimizeScreenBridge.status())),
				COLUMN_A_X, hintY, 0x80FF80);

		super.render(guiGraphics, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** ESC：返回 MTR 车厂配置界面。 */
	@Override
	public void onClose() {
		if (previousScreen != null) {
			Minecraft.getInstance().setScreen(previousScreen);
		} else if (!OptimizeScreenBridge.restoreDepotScreen()) {
			super.onClose();
		}
	}
}
