package mtr.realrail.mixin;

import mtr.realrail.MtrRealRail;
import mtr.realrail.PlatformNetwork;
import mtr.realrail.client.OptimizeScreenBridge;
import mtr.realrail.traffic.PlatformDispatchStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import org.mtr.core.data.Station;
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
 * 在 MTR <b>车站配置界面</b>（{@code EditStationScreen}）中追加"站台智能调度"开关，
 * 仅当该车站拥有 ≥2 个站台时显示（少于 2 个站台没有可调度的余地）。
 *
 * <p>按钮状态来自本模组的站台调度配置（以服务端为准，客户端通过 {@link PlatformNetwork} 同步）；
 * 点击后切换并立即保存到存档。</p>
 */
@Mixin(targets = "org.mtr.mod.screen.EditStationScreen")
public abstract class EditStationScreenMixin extends ScreenExtension {

	@Inject(method = "init2", at = @At("TAIL"))
	private void mtrrealrail$addStationDispatchButton(CallbackInfo callbackInfo) {
		final Screen currentScreen = Minecraft.getInstance().screen;
		final Station station = OptimizeScreenBridge.readObject(currentScreen, Station.class);
		if (station == null) {
			MtrRealRail.LOGGER.debug("[{}] 车站界面未能读取 Station 对象（{}）", MtrRealRail.MOD_ID,
					currentScreen == null ? "null" : currentScreen.getClass().getName());
			return;
		}
		final int platformCount = platformCount(station);
		if (platformCount == 1) {
			MtrRealRail.LOGGER.info("[{}] 车站 {}（{}）只有 1 个站台，不显示\"站台智能调度\"按钮", MtrRealRail.MOD_ID,
					station.getId(), station.getName());
			return; // 只有 1 个站台：无需智能调度
		}
		mtr.realrail.client.StationScreenBridge.onOpen(currentScreen, station.getId());

		final boolean enabled = PlatformDispatchStore.isStationEnabled(station.getId());
		final ButtonWidgetExtension button = new ButtonWidgetExtension(0, 0, 0, 20,
				TextHelper.literal("站台智能调度: " + (enabled ? "开" : "关")), mtrrealrail$pressAction(currentScreen, station));
		addChild(new ClickableWidget(button));

		// 位置：右下角，且必须让开 MTR 底部那条通栏按钮（"新增车站出口"，即 buttonAddExitParent）——
		// 它横跨整个界面底部，直接放 height-26 会被它盖住/与之重叠。
		final Object bottomAnchor = OptimizeScreenBridge.readFieldObject(this, "buttonAddExitParent");
		int bottomLimit = height - 26;
		if (bottomAnchor instanceof AbstractWidget bottomWidget) {
			bottomLimit = Math.min(bottomLimit, bottomWidget.getY() - 4);
		}
		final int x = Math.max(0, width - 174);
		final int y = Math.max(4, bottomLimit - 20);
		IDrawing.setPositionAndWidth(button, x, y, 168);
		if (mtr.realrail.client.StationScreenBridge.shouldLog()) {
			MtrRealRail.LOGGER.info("[{}] 车站界面已添加\"站台智能调度\"按钮：车站={}（{}），站台数={}，位置=({}, {})",
					MtrRealRail.MOD_ID, station.getId(), station.getName(), platformCount, x, y);
		}

		// 关键：状态请求每个界面实例只发一次，否则会形成"初始化→请求→回包→重新初始化"的死循环
		if (mtr.realrail.client.StationScreenBridge.shouldRequest(station.getId())) {
			PlatformNetwork.requestStation(station.getId());
		}
	}

	private org.mtr.mapping.holder.PressAction mtrrealrail$pressAction(Screen currentScreen, Station station) {
		return new org.mtr.mapping.holder.PressAction() {
			@Override
			public void onPress2(org.mtr.mapping.holder.ButtonWidget widget) {
				toggle();
			}

			@Override
			public void onPress(net.minecraft.client.gui.components.Button widget) {
				toggle();
			}

			private void toggle() {
				final boolean next = !PlatformDispatchStore.isStationEnabled(station.getId());
				PlatformDispatchStore.setStationEnabled(station.getId(), next);
				PlatformNetwork.toggleStation(station.getId(), next);
				if (currentScreen != null) {
					// rebuildWidgets 是 protected：用 setScreen 重新初始化同一界面即可刷新按钮文字
					Minecraft.getInstance().setScreen(currentScreen);
				}
			}
		};
	}

	/**
	 * 统计车站的站台数量。
	 * 优先用 {@code Station.savedRails}（MTR 自身的关联）；若客户端数据尚未建立该关联（返回 0），
	 * 则退化为扫描 {@code Data.platforms} 中 area 指向该车站的站台；两者都拿不到时返回 0（= 未知），
	 * 此时依然显示按钮，避免"明明是双站台车站却看不到按钮"。
	 */
	private static int platformCount(Station station) {
		int count = station.savedRails.size();
		if (count > 0) {
			return count;
		}
		try {
			final org.mtr.core.data.Data data = ((mtr.realrail.mixin.NameColorDataBaseSchemaAccessor) (Object) station).mtrrealrail$getData();
			if (data != null) {
				for (final org.mtr.core.data.Platform platform : data.platforms) {
					if (platform.area != null && platform.area.getId() == station.getId()) {
						count++;
					}
				}
			}
		} catch (Throwable ignored) {
			// 客户端数据不可用时按"未知"处理
		}
		return count;
	}

}
