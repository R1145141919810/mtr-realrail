package mtr.realrail;

import mtr.realrail.client.ClientPacketHandler;
import mtr.realrail.traffic.DepotSettingsResolver;
import mtr.realrail.traffic.OptimizeConfigStore;
import mtr.realrail.traffic.OptimizeSettings;
import mtr.realrail.traffic.RouteRefresher;
import mtr.realrail.traffic.TrafficRegistry;
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
 * 「优化线路配置」界面的网络通道（Forge SimpleChannel）。
 *
 * <ul>
 *   <li>C2S {@link RequestSettingsMessage}：客户端打开界面时请求该车厂的配置。</li>
 *   <li>C2S {@link UpdateSettingsMessage}：应用新配置（可选同时让 MTR 重新生成车厂路径）。</li>
 *   <li>C2S {@link ResetSettingsMessage}：恢复全局默认。</li>
 *   <li>S2C {@link SyncSettingsMessage}：下发配置与状态文字（列车数、是否已刷新路线等）。</li>
 * </ul>
 */
public final class OptimizeNetwork {

	private static final String PROTOCOL_VERSION = "1";

	public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
			new ResourceLocation(MtrRealRail.MOD_ID, "optimize"),
			() -> PROTOCOL_VERSION,
			PROTOCOL_VERSION::equals,
			PROTOCOL_VERSION::equals
	);

	private OptimizeNetwork() {
	}

	public static void init() {
		CHANNEL.registerMessage(0, RequestSettingsMessage.class, RequestSettingsMessage::encode, RequestSettingsMessage::decode, RequestSettingsMessage::handle);
		CHANNEL.registerMessage(1, UpdateSettingsMessage.class, UpdateSettingsMessage::encode, UpdateSettingsMessage::decode, UpdateSettingsMessage::handle);
		CHANNEL.registerMessage(2, ResetSettingsMessage.class, ResetSettingsMessage::encode, ResetSettingsMessage::decode, ResetSettingsMessage::handle);
		CHANNEL.registerMessage(3, SyncSettingsMessage.class, SyncSettingsMessage::encode, SyncSettingsMessage::decode, SyncSettingsMessage::handle);
	}

	public static void requestSettings(long depotId) {
		CHANNEL.sendToServer(new RequestSettingsMessage(depotId));
	}

	public static void sendUpdate(long depotId, OptimizeSettings settings, boolean refreshRoutes) {
		CHANNEL.sendToServer(new UpdateSettingsMessage(depotId, settings, refreshRoutes));
	}

	public static void sendReset(long depotId) {
		CHANNEL.sendToServer(new ResetSettingsMessage(depotId));
	}

	/** 服务端 → 客户端：下发配置与状态。 */
	public static void sync(ServerPlayer player, long depotId, OptimizeSettings settings, String status) {
		CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncSettingsMessage(depotId, settings, status));
	}

	/** 服务器状态描述：在线列车数 + 该车厂已保存的配置数量，便于玩家确认数据流是否正常。 */
	private static String statusText(boolean refreshed) {
		final StringBuilder builder = new StringBuilder();
		builder.append(refreshed ? "已保存并请求重新生成车厂路径（刷新路线）" : "已保存");
		builder.append("；当前在线列车 ").append(TrafficRegistry.size()).append(" 列");
		builder.append("，已配置车厂 ").append(OptimizeConfigStore.size()).append(" 个");
		return builder.toString();
	}

	public record RequestSettingsMessage(long depotId) {

		public static void encode(RequestSettingsMessage message, FriendlyByteBuf buffer) {
			buffer.writeLong(message.depotId);
		}

		public static RequestSettingsMessage decode(FriendlyByteBuf buffer) {
			return new RequestSettingsMessage(buffer.readLong());
		}

		public static void handle(RequestSettingsMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
			final NetworkEvent.Context context = contextSupplier.get();
			context.enqueueWork(() -> {
				final ServerPlayer player = context.getSender();
				if (player != null) {
					sync(player, message.depotId, DepotSettingsResolver.settingsForDepot(message.depotId), statusText(false));
				}
			});
			context.setPacketHandled(true);
		}
	}

	public record UpdateSettingsMessage(long depotId, OptimizeSettings settings, boolean refreshRoutes) {

		public static void encode(UpdateSettingsMessage message, FriendlyByteBuf buffer) {
			buffer.writeLong(message.depotId);
			final OptimizeSettings settings = message.settings;
			buffer.writeBoolean(settings.dynamicBlockRelease);
			buffer.writeDouble(settings.safetyMarginMeters);
			buffer.writeBoolean(settings.routeOptimize);
			buffer.writeDouble(settings.pathLookaheadMeters);
			buffer.writeBoolean(settings.departureGuard);
			buffer.writeInt(settings.dwellThresholdSeconds);
			buffer.writeInt(settings.conflictWindowSeconds);
			buffer.writeInt(settings.maxHoldSeconds);
			buffer.writeBoolean(settings.midLineDepot);
			buffer.writeInt(settings.deployLeadMinutes);
			buffer.writeInt(settings.idleReturnMinutes);
			buffer.writeBoolean(message.refreshRoutes);
		}

		public static UpdateSettingsMessage decode(FriendlyByteBuf buffer) {
			final long depotId = buffer.readLong();
			final OptimizeSettings settings = new OptimizeSettings();
			settings.dynamicBlockRelease = buffer.readBoolean();
			settings.safetyMarginMeters = buffer.readDouble();
			settings.routeOptimize = buffer.readBoolean();
			settings.pathLookaheadMeters = buffer.readDouble();
			settings.departureGuard = buffer.readBoolean();
			settings.dwellThresholdSeconds = buffer.readInt();
			settings.conflictWindowSeconds = buffer.readInt();
			settings.maxHoldSeconds = buffer.readInt();
			settings.midLineDepot = buffer.readBoolean();
			settings.deployLeadMinutes = buffer.readInt();
			settings.idleReturnMinutes = buffer.readInt();
			return new UpdateSettingsMessage(depotId, settings, buffer.readBoolean());
		}

		public static void handle(UpdateSettingsMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
			final NetworkEvent.Context context = contextSupplier.get();
			context.enqueueWork(() -> {
				final ServerPlayer player = context.getSender();
				if (player == null) {
					return;
				}
				final boolean midLineChanged = DepotSettingsResolver.settingsForDepot(message.depotId).midLineDepot != message.settings.midLineDepot;
				OptimizeConfigStore.put(message.depotId, message.settings);
				if (midLineChanged) {
					mtr.realrail.traffic.MidLineDepotService.onToggle(message.depotId, message.settings.midLineDepot);
				}
				DepotSettingsResolver.invalidate();
				final boolean refreshed = message.refreshRoutes
						&& RouteRefresher.refreshDepot(TrafficRegistry.data(), message.depotId);
				sync(player, message.depotId, DepotSettingsResolver.settingsForDepot(message.depotId), statusText(refreshed));
			});
			context.setPacketHandled(true);
		}
	}

	public record ResetSettingsMessage(long depotId) {

		public static void encode(ResetSettingsMessage message, FriendlyByteBuf buffer) {
			buffer.writeLong(message.depotId);
		}

		public static ResetSettingsMessage decode(FriendlyByteBuf buffer) {
			return new ResetSettingsMessage(buffer.readLong());
		}

		public static void handle(ResetSettingsMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
			final NetworkEvent.Context context = contextSupplier.get();
			context.enqueueWork(() -> {
				final ServerPlayer player = context.getSender();
				if (player != null) {
					OptimizeConfigStore.reset(message.depotId);
					DepotSettingsResolver.invalidate();
					sync(player, message.depotId, DepotSettingsResolver.settingsForDepot(message.depotId), "已恢复全局默认：" + statusText(false));
				}
			});
			context.setPacketHandled(true);
		}
	}

	public record SyncSettingsMessage(long depotId, OptimizeSettings settings, String status) {

		public static void encode(SyncSettingsMessage message, FriendlyByteBuf buffer) {
			buffer.writeLong(message.depotId);
			final OptimizeSettings settings = message.settings;
			buffer.writeBoolean(settings.dynamicBlockRelease);
			buffer.writeDouble(settings.safetyMarginMeters);
			buffer.writeBoolean(settings.routeOptimize);
			buffer.writeDouble(settings.pathLookaheadMeters);
			buffer.writeBoolean(settings.departureGuard);
			buffer.writeInt(settings.dwellThresholdSeconds);
			buffer.writeInt(settings.conflictWindowSeconds);
			buffer.writeInt(settings.maxHoldSeconds);
			buffer.writeBoolean(settings.midLineDepot);
			buffer.writeInt(settings.deployLeadMinutes);
			buffer.writeInt(settings.idleReturnMinutes);
			buffer.writeUtf(message.status == null ? "" : message.status, 256);
		}

		public static SyncSettingsMessage decode(FriendlyByteBuf buffer) {
			final long depotId = buffer.readLong();
			final OptimizeSettings settings = new OptimizeSettings();
			settings.dynamicBlockRelease = buffer.readBoolean();
			settings.safetyMarginMeters = buffer.readDouble();
			settings.routeOptimize = buffer.readBoolean();
			settings.pathLookaheadMeters = buffer.readDouble();
			settings.departureGuard = buffer.readBoolean();
			settings.dwellThresholdSeconds = buffer.readInt();
			settings.conflictWindowSeconds = buffer.readInt();
			settings.maxHoldSeconds = buffer.readInt();
			settings.midLineDepot = buffer.readBoolean();
			settings.deployLeadMinutes = buffer.readInt();
			settings.idleReturnMinutes = buffer.readInt();
			return new SyncSettingsMessage(depotId, settings, buffer.readUtf(256));
		}

		public static void handle(SyncSettingsMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
			final NetworkEvent.Context context = contextSupplier.get();
			context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
					() -> () -> ClientPacketHandler.onOptimizeSync(message.depotId, message.settings, message.status)));
			context.setPacketHandled(true);
		}
	}
}
