package mtr.realrail;

import mtr.realrail.client.ClientPacketHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * 节点判定模式界面的网络通道（Forge SimpleChannel）。
 *
 * <ul>
 *   <li>S2C {@link OpenNodeModeMessage}：服务端让客户端打开节点配置界面（节点坐标 + 当前判定）。</li>
 *   <li>C2S {@link RequestOpenNodeModeMessage}：客户端在 MTR 原版轨道界面中点击"节点限速判定"按钮时，
 *       请求服务端下发界面（判定方式存于服务端，客户端无法自行得知）。</li>
 *   <li>C2S {@link ToggleNodeModeMessage}：客户端点击按钮 → 服务端切换该节点判定并回发新状态刷新界面。</li>
 * </ul>
 */
public final class NodeModeNetwork {

	private static final String PROTOCOL_VERSION = "1";

	public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
			new ResourceLocation(MtrRealRail.MOD_ID, "node_mode"),
			() -> PROTOCOL_VERSION,
			PROTOCOL_VERSION::equals,
			PROTOCOL_VERSION::equals
	);

	private NodeModeNetwork() {
	}

	public static void init() {
		CHANNEL.registerMessage(0, OpenNodeModeMessage.class, OpenNodeModeMessage::encode, OpenNodeModeMessage::decode, OpenNodeModeMessage::handle);
		CHANNEL.registerMessage(1, ToggleNodeModeMessage.class, ToggleNodeModeMessage::encode, ToggleNodeModeMessage::decode, ToggleNodeModeMessage::handle);
		CHANNEL.registerMessage(2, RequestOpenNodeModeMessage.class, RequestOpenNodeModeMessage::encode, RequestOpenNodeModeMessage::decode, RequestOpenNodeModeMessage::handle);
	}

	/** 服务端 → 客户端：打开（或刷新）节点判定界面。 */
	public static void sendOpen(ServerPlayer player, BlockPos pos, RealRailConfig.NodeMode mode) {
		CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenNodeModeMessage(pos, mode == RealRailConfig.NodeMode.TAIL));
	}

	/** 客户端 → 服务端：请求下发节点判定界面（由 MTR 原版轨道界面中的按钮触发）。 */
	public static void requestOpen(BlockPos pos) {
		CHANNEL.sendToServer(new RequestOpenNodeModeMessage(pos));
	}

	/** 客户端 → 服务端：切换该节点判定方式。 */
	public static void sendToggle(BlockPos pos) {
		CHANNEL.sendToServer(new ToggleNodeModeMessage(pos));
	}

	public record OpenNodeModeMessage(BlockPos pos, boolean tailMode) {

		public static void encode(OpenNodeModeMessage message, FriendlyByteBuf buffer) {
			buffer.writeBlockPos(message.pos);
			buffer.writeBoolean(message.tailMode);
		}

		public static OpenNodeModeMessage decode(FriendlyByteBuf buffer) {
			return new OpenNodeModeMessage(buffer.readBlockPos(), buffer.readBoolean());
		}

		public static void handle(OpenNodeModeMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
			final NetworkEvent.Context context = contextSupplier.get();
			// 只在客户端执行，且通过 DistExecutor 隔离客户端类，避免服务端加载客户端类
			context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
					() -> () -> ClientPacketHandler.openNodeModeScreen(message.pos, message.tailMode)));
			context.setPacketHandled(true);
		}
	}

	public record RequestOpenNodeModeMessage(BlockPos pos) {

		public static void encode(RequestOpenNodeModeMessage message, FriendlyByteBuf buffer) {
			buffer.writeBlockPos(message.pos);
		}

		public static RequestOpenNodeModeMessage decode(FriendlyByteBuf buffer) {
			return new RequestOpenNodeModeMessage(buffer.readBlockPos());
		}

		public static void handle(RequestOpenNodeModeMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
			final NetworkEvent.Context context = contextSupplier.get();
			context.enqueueWork(() -> {
				final ServerPlayer player = context.getSender();
				if (player != null) {
					// 仅回发当前状态，不修改任何数据
					sendOpen(player, message.pos, NodeModeStore.getMode(message.pos.getX(), message.pos.getY(), message.pos.getZ()));
				}
			});
			context.setPacketHandled(true);
		}
	}

	public record ToggleNodeModeMessage(BlockPos pos) {

		public static void encode(ToggleNodeModeMessage message, FriendlyByteBuf buffer) {
			buffer.writeBlockPos(message.pos);
		}

		public static ToggleNodeModeMessage decode(FriendlyByteBuf buffer) {
			return new ToggleNodeModeMessage(buffer.readBlockPos());
		}

		public static void handle(ToggleNodeModeMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
			final NetworkEvent.Context context = contextSupplier.get();
			context.enqueueWork(() -> {
				final ServerPlayer player = context.getSender();
				if (player != null) {
					final RealRailConfig.NodeMode mode = NodeModeStore.toggle(
							message.pos.getX(), message.pos.getY(), message.pos.getZ());
					sendOpen(player, message.pos, mode);
				}
			});
			context.setPacketHandled(true);
		}
	}
}
