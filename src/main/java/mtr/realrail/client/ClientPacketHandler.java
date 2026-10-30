package mtr.realrail.client;

import mtr.realrail.traffic.OptimizeSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * 客户端侧的数据包处理（仅在物理客户端加载，服务端不会触碰该类）。
 */
public final class ClientPacketHandler {

	private ClientPacketHandler() {
	}

	public static void openNodeModeScreen(BlockPos pos, boolean tailMode) {
		Minecraft.getInstance().setScreen(new NodeModeScreen(pos, tailMode));
	}

	/** 服务端下发「站台智能调度」配置：routeId=0 表示车站级开关，否则为线路级配置。 */
	public static void onPlatformSync(long routeId, boolean enabled, long stationId, java.util.Set<Long> platformIds, String status) {
		if (routeId == 0) {
			mtr.realrail.client.StationScreenBridge.onSync(stationId, enabled);
		} else {
			RouteDispatchScreen.applySync(routeId, enabled, stationId, platformIds, status);
		}
	}

	/** 服务端下发「优化线路配置」：刷新已打开的界面，或打开新界面。 */
	public static void onOptimizeSync(long depotId, OptimizeSettings settings, String status) {
		if (OptimizeScreenBridge.depotId() != depotId) {
			return; // 不是当前正在编辑的车厂（例如界面已关闭）
		}
		OptimizeScreenBridge.setSettings(settings);
		OptimizeScreenBridge.setStatus(status);
		final Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.screen instanceof DepotOptimizeScreen screen) {
			screen.refresh();
		} else {
			OptimizeScreenBridge.openOptimizeScreen();
		}
	}
}
