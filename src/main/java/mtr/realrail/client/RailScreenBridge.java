package mtr.realrail.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;

/**
 * 客户端界面桥接：在"玩家右键的轨道节点"与"MTR 原版轨道界面中的按钮"之间传递数据。
 *
 * <p>流程：</p>
 * <ol>
 *   <li>玩家手持刷子右键轨道节点 → {@link RailNodeInteractionHandler} 记录节点坐标（不消费事件，
 *       MTR 照常打开原版轨道形状修改界面）；</li>
 *   <li>MTR 原版界面初始化时 {@code RailModifierScreenMixin} 读取该坐标并追加一个
 *       "节点限速判定"按钮；</li>
 *   <li>点击按钮 → 记住当前 MTR 界面（用于返回）→ 请求服务端下发节点判定界面；</li>
 *   <li>{@link NodeModeScreen} 关闭时返回此前记住的 MTR 界面。</li>
 * </ol>
 */
public final class RailScreenBridge {

	/** 右键记录的有效期：超过该时间未使用则视为过期（避免使用陈旧坐标）。 */
	private static final long PENDING_TTL_MILLIS = 600_000L;

	private static BlockPos pendingNodePos;
	private static long pendingNodeMillis;
	private static Screen railScreen;

	private RailScreenBridge() {
	}

	/** 记录玩家右键的轨道节点坐标（仅客户端）。 */
	public static void setPendingNodePos(BlockPos pos) {
		pendingNodePos = pos == null ? null : pos.immutable();
		pendingNodeMillis = System.currentTimeMillis();
	}

	/**
	 * 读取右键记录的节点坐标（不消费，便于界面重建/缩放时重新添加按钮）。
	 *
	 * @return 未记录或已过期时返回 null
	 */
	public static BlockPos getPendingNodePos() {
		if (pendingNodePos == null || System.currentTimeMillis() - pendingNodeMillis > PENDING_TTL_MILLIS) {
			return null;
		}
		return pendingNodePos;
	}

	/** 记住 MTR 原版轨道界面，供节点判定界面关闭后返回。 */
	public static void rememberRailScreen(Screen screen) {
		railScreen = screen;
	}

	/**
	 * 返回此前记住的 MTR 原版轨道界面。
	 *
	 * @return 成功返回 true；无记住的界面返回 false（调用方走默认关闭逻辑）
	 */
	public static boolean restoreRailScreen() {
		final Screen screen = railScreen;
		if (screen == null) {
			return false;
		}
		railScreen = null;
		Minecraft.getInstance().setScreen(screen);
		return true;
	}
}
