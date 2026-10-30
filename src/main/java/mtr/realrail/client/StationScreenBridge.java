package mtr.realrail.client;

import mtr.realrail.traffic.PlatformDispatchStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 车站配置界面的桥接状态。
 *
 * <p>为什么单独放一个普通类：Mixin 规范禁止 mixin 类中出现"非 private 的 static 方法"
 * （{@code InvalidMixinException: contains non-private static method}），
 * 一旦违反会导致整个 mixin 不生效。</p>
 *
 * <p><b>防死循环</b>：车站界面每次初始化都会调用 {@code init2}，早期版本在那里无条件
 * 向服务端请求状态、服务端每次回包又触发界面重新初始化，形成
 * "初始化 → 请求 → 回包 → 重新初始化"的无限循环（渲染线程被打满，客户端无响应、ESC 失效）。
 * 现在：① 状态请求<b>每个界面实例只发一次</b>；② 回包只在<b>状态确实变化</b>时才刷新界面；
 * ③ 刷新期间有重入保护。</p>
 */
public final class StationScreenBridge {

	private static Screen openScreen;
	private static long openStationId;
	private static boolean requested;
	private static boolean refreshed;
	private static boolean refreshing;

	private StationScreenBridge() {
	}

	/** mixin 在车站界面初始化时登记（同一界面实例重复调用不会重置请求标记）。 */
	public static void onOpen(Screen screen, long stationId) {
		if (screen != openScreen) {
			openScreen = screen;
			requested = false;
			refreshed = false;
		}
		openStationId = stationId;
	}

	/** 是否需要向服务端请求该车站的开关状态（每个界面实例仅一次）。 */
	public static boolean shouldRequest(long stationId) {
		if (requested) {
			return false;
		}
		requested = true;
		openStationId = stationId;
		return true;
	}

	/** 是否需要打印一次"按钮已添加"的日志（每个界面实例仅一次）。 */
	public static boolean shouldLog() {
		if (refreshed) {
			return false;
		}
		refreshed = true;
		return true;
	}

	/** 服务端同步车站开关后：更新本地镜像；仅在状态确实变化时刷新界面。 */
	public static void onSync(long stationId, boolean enabled) {
		final boolean changed = PlatformDispatchStore.isStationEnabled(stationId) != enabled;
		PlatformDispatchStore.setStationEnabled(stationId, enabled);
		if (!changed || refreshing) {
			return;
		}
		final Minecraft minecraft = Minecraft.getInstance();
		if (openScreen != null && openStationId == stationId && minecraft.screen == openScreen) {
			refreshing = true;
			try {
				minecraft.setScreen(openScreen);
			} finally {
				refreshing = false;
			}
		}
	}
}
