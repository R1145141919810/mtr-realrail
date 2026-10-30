package mtr.realrail.client;

import mtr.realrail.PlatformNetwork;
import mtr.realrail.traffic.PlatformDispatchStore;
import mtr.realrail.traffic.PlatformDispatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.mtr.core.data.Data;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Route;
import org.mtr.core.data.Station;
import mtr.realrail.mixin.NameColorDataBaseSchemaAccessor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「线路智能调度站台」配置界面（客户端）。
 *
 * <p>入口：铁路仪表板 → 线路 → 线路配置界面中的"线路智能调度站台"按钮。</p>
 *
 * <p>界面按<b>线路经过的车站</b>分页展示：每个车站列出该车站的全部站台，
 * 站台按钮可<b>多选</b>（选中 = 允许停靠）；未选择任何站台表示"不限制"。
 * 顶部是线路级开关，底部是 应用 / 应用并刷新路线 / 返回(ESC)。</p>
 */
public class RouteDispatchScreen extends Screen {

	private static final int STATIONS_PER_PAGE = 3;
	private static final int ROW_HEIGHT = 20;
	private static final int PLATFORM_BUTTON_WIDTH = 96;

	private static RouteDispatchScreen openScreen;

	private final Screen previousScreen;
	private final long routeId;
	private final String routeName;
	private final List<Station> stations = new ArrayList<>();
	private final List<List<Platform>> stationPlatforms = new ArrayList<>();
	private final Map<Long, Set<Long>> selection = new HashMap<>();
	private boolean enabled;
	private String status = "正在读取…";
	private int page;

	public RouteDispatchScreen(Screen previousScreen, Route route) {
		super(Component.literal("线路智能调度站台"));
		this.previousScreen = previousScreen;
		this.routeId = route.getId();
		this.routeName = route.getName();
		this.enabled = PlatformDispatchStore.isRouteEnabled(routeId);

		final Data data = ((NameColorDataBaseSchemaAccessor) (Object) route).mtrrealrail$getData();
		for (final Station station : PlatformDispatcher.routeStations(route)) {
			stations.add(station);
			stationPlatforms.add(PlatformDispatcher.stationPlatforms(station));
			selection.put(station.getId(), new HashSet<>(PlatformDispatchStore.allowedPlatforms(routeId, station.getId())));
		}
		openScreen = this;
		PlatformNetwork.requestRoute(routeId);
	}

	/** 服务端下发配置后刷新界面。 */
	public static void applySync(long routeId, boolean enabled, long stationId, Set<Long> platformIds, String status) {
		final RouteDispatchScreen screen = openScreen;
		if (screen == null || screen.routeId != routeId) {
			return;
		}
		screen.enabled = enabled;
		if (stationId != 0) {
			screen.selection.put(stationId, new HashSet<>(platformIds));
		}
		screen.status = status == null ? "" : status;
		screen.rebuildWidgets();
	}

	@Override
	protected void init() {
		openScreen = this;
		addRenderableWidget(Button.builder(Component.literal("线路智能调度: " + (enabled ? "开" : "关")), button -> {
			enabled = !enabled;
			sendRouteOnly(false);
			rebuildWidgets();
		}).bounds(6, 30, 150, ROW_HEIGHT).build());

		final int pageCount = Math.max(1, (stations.size() + STATIONS_PER_PAGE - 1) / STATIONS_PER_PAGE);
		if (page > pageCount - 1) {
			page = pageCount - 1;
		}
		addRenderableWidget(Button.builder(Component.literal("<"), button -> {
			page = Math.max(0, page - 1);
			rebuildWidgets();
		}).bounds(width - 60, 30, 24, ROW_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.literal(">"), button -> {
			page = Math.min(pageCount - 1, page + 1);
			rebuildWidgets();
		}).bounds(width - 30, 30, 24, ROW_HEIGHT).build());

		// 每个车站一行：站台多选按钮
		int y = 58;
		for (int i = 0; i < STATIONS_PER_PAGE; i++) {
			final int index = page * STATIONS_PER_PAGE + i;
			if (index >= stations.size()) {
				break;
			}
			final Station station = stations.get(index);
			final List<Platform> platforms = stationPlatforms.get(index);
			final Set<Long> selected = selection.computeIfAbsent(station.getId(), key -> new HashSet<>());
			int x = 6;
			for (final Platform platform : platforms) {
				final boolean on = selected.contains(platform.getId());
				addRenderableWidget(Button.builder(Component.literal((on ? "√ " : "") + platform.getName()), button -> {
					if (selected.contains(platform.getId())) {
						selected.remove(platform.getId());
					} else {
						selected.add(platform.getId());
					}
					sendStation(station.getId(), selected, false);
					rebuildWidgets();
				}).bounds(x, y + 12, PLATFORM_BUTTON_WIDTH, ROW_HEIGHT).build());
				x += PLATFORM_BUTTON_WIDTH + 4;
				if (x + PLATFORM_BUTTON_WIDTH > width) {
					break;
				}
			}
			addRenderableWidget(Button.builder(Component.literal("全部/清空"), button -> {
				if (selected.isEmpty()) {
					platforms.forEach(platform -> selected.add(platform.getId()));
				} else {
					selected.clear();
				}
				sendStation(station.getId(), selected, false);
				rebuildWidgets();
			}).bounds(width - 84, y + 12, 78, ROW_HEIGHT).build());
			y += 46;
		}

		// 底部动作
		final int actionY = height - 48;
		addRenderableWidget(Button.builder(Component.literal("应用"), button -> sendStation(0, Set.of(), false))
				.bounds(6, actionY, 152, ROW_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.literal("应用并刷新路线"), button -> sendStation(0, Set.of(), true))
				.bounds(162, actionY, 152, ROW_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.literal("返回线路配置（ESC）"), button -> onClose())
				.bounds(6, actionY + ROW_HEIGHT + 4, 152, ROW_HEIGHT).build());
	}

	private void sendRouteOnly(boolean refreshRoutes) {
		PlatformNetwork.updateRoute(routeId, enabled, 0, Set.of(), refreshRoutes);
	}

	private void sendStation(long stationId, Set<Long> platformIds, boolean refreshRoutes) {
		PlatformNetwork.updateRoute(routeId, enabled, stationId, platformIds, refreshRoutes);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		renderBackground(guiGraphics);
		guiGraphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
		guiGraphics.drawCenteredString(font, Component.literal("线路: " + routeName + "（id " + routeId + "）"), width / 2, 19, 0xAAAAAA);

		final int pageCount = Math.max(1, (stations.size() + STATIONS_PER_PAGE - 1) / STATIONS_PER_PAGE);
		guiGraphics.drawString(font, Component.literal("第 " + (page + 1) + " / " + pageCount + " 页（每页 " + STATIONS_PER_PAGE + " 个车站）"),
				162, 36, 0xFFFF80);

		int y = 58;
		for (int i = 0; i < STATIONS_PER_PAGE; i++) {
			final int index = page * STATIONS_PER_PAGE + i;
			if (index >= stations.size()) {
				break;
			}
			final Station station = stations.get(index);
			guiGraphics.drawString(font, Component.literal("车站: " + station.getName() + "（站台 " + stationPlatforms.get(index).size() + " 个）"),
					6, y, 0xFFFFFF);
			y += 46;
		}

		guiGraphics.drawString(font, Component.literal("提示：选中 = 允许停靠（可多选）；全部不选 = 不限制。改动后请点\"应用并刷新路线\"并等车厂重新生成路径。"),
				6, height - 70, 0xFFFF80);
		guiGraphics.drawString(font, Component.literal("状态: " + status + "　|　" + mtr.realrail.dispatch.TscDispatchBridge.chainStatusText()),
				6, height - 58, 0x80FF80);
		super.render(guiGraphics, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		openScreen = null;
		if (previousScreen != null) {
			Minecraft.getInstance().setScreen(previousScreen);
		} else {
			super.onClose();
		}
	}
}
