package mtr.realrail.client;

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
}
