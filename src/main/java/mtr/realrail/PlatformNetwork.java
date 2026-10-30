package mtr.realrail;

import mtr.realrail.client.ClientPacketHandler;
import mtr.realrail.traffic.PlatformDispatchStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * 站台智能调度的网络通道。
 *
 * <ul>
 *   <li>C2S {@link ToggleStationMessage}：车站配置界面的"站台智能调度"开关。</li>
 *   <li>C2S {@link RequestRouteMessage}：请求某线路的智能调度配置（打开专用界面时）。</li>
 *   <li>C2S {@link UpdateRouteMessage}：保存线路开关与"车站 → 允许停靠站台集合"（多选）。</li>
 *   <li>S2C {@link SyncRouteMessage}：下发线路配置与状态。</li>
 * </ul>
 */
public final class PlatformNetwork {

	private static final String PROTOCOL_VERSION = "1";

	public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
			new ResourceLocation(MtrRealRail.MOD_ID, "platform"),
			() -> PROTOCOL_VERSION,
			PROTOCOL_VERSION::equals,
			PROTOCOL_VERSION::equals
	);

	private PlatformNetwork() {
	}

	public static void init() {
		CHANNEL.registerMessage(0, ToggleStationMessage.class, ToggleStationMessage::encode, ToggleStationMessage::decode, ToggleStationMessage::handle);
		CHANNEL.registerMessage(1, RequestRouteMessage.class, RequestRouteMessage::encode, RequestRouteMessage::decode, RequestRouteMessage::handle);
		CHANNEL.registerMessage(2, UpdateRouteMessage.class, UpdateRouteMessage::encode, UpdateRouteMessage::decode, UpdateRouteMessage::handle);
		CHANNEL.registerMessage(3, SyncRouteMessage.class, SyncRouteMessage::encode, SyncRouteMessage::decode, SyncRouteMessage::handle);
		CHANNEL.registerMessage(4, RequestStationMessage.class, RequestStationMessage::encode, RequestStationMessage::decode, RequestStationMessage::handle);
	}

	public static void requestStation(long stationId) {
		CHANNEL.sendToServer(new RequestStationMessage(stationId));
	}

	public static void toggleStation(long stationId, boolean enabled) {
		CHANNEL.sendToServer(new ToggleStationMessage(stationId, enabled));
	}

	public static void requestRoute(long routeId) {
		CHANNEL.sendToServer(new RequestRouteMessage(routeId));
	}

	public static void updateRoute(long routeId, boolean enabled, long stationId, Set<Long> platformIds, boolean refreshRoutes) {
		CHANNEL.sendToServer(new UpdateRouteMessage(routeId, enabled, stationId, platformIds, refreshRoutes));
	}

	public static void sync(ServerPlayer player, long routeId, boolean enabled, long stationId, Set<Long> platformIds, String status) {
		CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncRouteMessage(routeId, enabled, stationId, platformIds, status));
	}

	/** 车站配置界面打开时请求该车站的开关状态（复用 SyncRouteMessage：routeId=0 表示车站级同步）。 */
	public record RequestStationMessage(long stationId) {

		public static void encode(RequestStationMessage message, FriendlyByteBuf buffer) {
			buffer.writeLong(message.stationId);
		}

		public static RequestStationMessage decode(FriendlyByteBuf buffer) {
			return new RequestStationMessage(buffer.readLong());
		}

		public static void handle(RequestStationMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
			final NetworkEvent.Context context = contextSupplier.get();
			context.enqueueWork(() -> {
				final ServerPlayer player = context.getSender();
				if (player != null) {
					sync(player, 0, PlatformDispatchStore.isStationEnabled(message.stationId), message.stationId, Set.of(), "车站配置已同步");
				}
			});
			context.setPacketHandled(true);
		}
	}

	public record ToggleStationMessage(long stationId, boolean enabled) {

		public static void encode(ToggleStationMessage message, FriendlyByteBuf buffer) {
			buffer.writeLong(message.stationId);
			buffer.writeBoolean(message.enabled);
		}

		public static ToggleStationMessage decode(FriendlyByteBuf buffer) {
			return new ToggleStationMessage(buffer.readLong(), buffer.readBoolean());
		}

		public static void handle(ToggleStationMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
			final NetworkEvent.Context context = contextSupplier.get();
			context.enqueueWork(() -> {
				if (context.getSender() == null) {
					return;
				}
				PlatformDispatchStore.setStationEnabled(message.stationId, message.enabled);
				MtrRealRail.LOGGER.info("[{}] 车站 {} 的站台智能调度已{}", MtrRealRail.MOD_ID, message.stationId,
						message.enabled ? "开启" : "关闭");
			});
			context.setPacketHandled(true);
		}
	}

	public record RequestRouteMessage(long routeId) {

		public static void encode(RequestRouteMessage message, FriendlyByteBuf buffer) {
			buffer.writeLong(message.routeId);
		}

		public static RequestRouteMessage decode(FriendlyByteBuf buffer) {
			return new RequestRouteMessage(buffer.readLong());
		}

		public static void handle(RequestRouteMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
			final NetworkEvent.Context context = contextSupplier.get();
			context.enqueueWork(() -> {
				final ServerPlayer player = context.getSender();
				if (player != null) {
					sync(player, message.routeId, PlatformDispatchStore.isRouteEnabled(message.routeId), 0, Set.of(), "已读取线路配置");
				}
			});
			context.setPacketHandled(true);
		}
	}

	public record UpdateRouteMessage(long routeId, boolean enabled, long stationId, Set<Long> platformIds, boolean refreshRoutes) {

		public static void encode(UpdateRouteMessage message, FriendlyByteBuf buffer) {
			buffer.writeLong(message.routeId);
			buffer.writeBoolean(message.enabled);
			buffer.writeLong(message.stationId);
			buffer.writeVarInt(message.platformIds.size());
			for (final long platformId : message.platformIds) {
				buffer.writeLong(platformId);
			}
			buffer.writeBoolean(message.refreshRoutes);
		}

		public static UpdateRouteMessage decode(FriendlyByteBuf buffer) {
			final long routeId = buffer.readLong();
			final boolean enabled = buffer.readBoolean();
			final long stationId = buffer.readLong();
			final int size = buffer.readVarInt();
			final Set<Long> platformIds = new TreeSet<>();
			for (int i = 0; i < size; i++) {
				platformIds.add(buffer.readLong());
			}
			return new UpdateRouteMessage(routeId, enabled, stationId, platformIds, buffer.readBoolean());
		}

		public static void handle(UpdateRouteMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
			final NetworkEvent.Context context = contextSupplier.get();
			context.enqueueWork(() -> {
				final ServerPlayer player = context.getSender();
				if (player == null) {
					return;
				}
				PlatformDispatchStore.setRouteEnabled(message.routeId, message.enabled);
				if (message.stationId != 0) {
					PlatformDispatchStore.setAllowedPlatforms(message.routeId, message.stationId, message.platformIds);
				}
				final boolean refreshed = message.refreshRoutes
						&& mtr.realrail.traffic.RouteRefresher.refreshForRoute(mtr.realrail.traffic.TrafficRegistry.data(), message.routeId);
				sync(player, message.routeId, message.enabled, message.stationId, message.platformIds,
						refreshed ? "已保存并请求重新生成车厂路径" : "已保存");
			});
			context.setPacketHandled(true);
		}
	}

	public record SyncRouteMessage(long routeId, boolean enabled, long stationId, Set<Long> platformIds, String status) {

		public static void encode(SyncRouteMessage message, FriendlyByteBuf buffer) {
			buffer.writeLong(message.routeId);
			buffer.writeBoolean(message.enabled);
			buffer.writeLong(message.stationId);
			buffer.writeVarInt(message.platformIds.size());
			for (final long platformId : message.platformIds) {
				buffer.writeLong(platformId);
			}
			buffer.writeUtf(message.status == null ? "" : message.status, 128);
		}

		public static SyncRouteMessage decode(FriendlyByteBuf buffer) {
			final long routeId = buffer.readLong();
			final boolean enabled = buffer.readBoolean();
			final long stationId = buffer.readLong();
			final int size = buffer.readVarInt();
			final Set<Long> platformIds = new TreeSet<>();
			for (int i = 0; i < size; i++) {
				platformIds.add(buffer.readLong());
			}
			return new SyncRouteMessage(routeId, enabled, stationId, platformIds, buffer.readUtf(128));
		}

		public static void handle(SyncRouteMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
			final NetworkEvent.Context context = contextSupplier.get();
			context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
					() -> () -> ClientPacketHandler.onPlatformSync(message.routeId, message.enabled, message.stationId, message.platformIds, message.status)));
			context.setPacketHandled(true);
		}
	}
}
