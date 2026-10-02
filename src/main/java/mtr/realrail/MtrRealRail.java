package mtr.realrail;

import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 模组主入口（MC 1.20.1 + Forge）。
 *
 * <p>名称：<b>更好的列车限速及信号判定</b>（英文名 MTR RealRail）。</p>
 *
 * <p>职责：加载配置、注册网络通道、注册事件（刷子交互、服务端生命周期）、
 * 在世界载入时读取节点判定数据。核心逻辑见 {@link SpeedLimitHelper}（需求一）与
 * {@link SignalIsolationRegistry}（需求二），注入点见 {@code mtr.realrail.mixin} 包。</p>
 */
@Mod(MtrRealRail.MOD_ID)
@Mod.EventBusSubscriber(modid = MtrRealRail.MOD_ID)
public final class MtrRealRail {

	public static final String MOD_ID = "mtrrealrail";
	/** 模组显示名（中文名即模组正式名称）。 */
	public static final String MOD_NAME = "更好的列车限速及信号判定";
	public static final String MOD_NAME_EN = "MTR RealRail";
	public static final Logger LOGGER = LogManager.getLogger(MOD_NAME_EN);

	/** 全局配置文件名（位于 Forge 的 config 目录）。 */
	private static final String CONFIG_FILE_NAME = "mtrrealrail.properties";
	/** 存档内节点判定数据的目录与文件名（world/mtrrealrail/nodes.json）。 */
	private static final String SAVE_DIR_NAME = "mtrrealrail";
	private static final String SAVE_FILE_NAME = "nodes.json";

	public MtrRealRail() {
		final Path configFile = configPath();
		RealRailConfig.INSTANCE.load(configFile);
		if (!Files.isRegularFile(configFile)) {
			// 首次运行生成默认配置，便于玩家手改
			RealRailConfig.INSTANCE.save(configFile);
		}
		NodeModeNetwork.init();
		LOGGER.info("[{}] {} 已载入：默认节点判定 = {}，信号隔离 = {}",
				MOD_ID, MOD_NAME, RealRailConfig.INSTANCE.defaultNodeMode,
				RealRailConfig.INSTANCE.signalIsolation);
	}

	/** 服务端世界启动：读取该存档的节点判定配置；可选执行 Mixin 目标类自检。 */
	@SubscribeEvent
	public static void onServerStarted(ServerStartedEvent event) {
		RealRailConfig.INSTANCE.load(configPath());
		final Path worldDir = event.getServer().getWorldPath(LevelResource.ROOT);
		NodeModeStore.load(worldDir.resolve(SAVE_DIR_NAME).resolve(SAVE_FILE_NAME));
		LOGGER.info("[{}] 已载入 {} 个节点的判定设置：{}", MOD_ID, NodeModeStore.size(), NodeModeStore.savePath());

		if (Boolean.getBoolean("mtrrealrail.verifyMixins")) {
			verifyMixinTargets();
		}
	}

	/**
	 * 冒烟自检：强制加载两个 Mixin 的目标类。
	 * 目标方法/字段签名不匹配时 Mixin 会在类加载阶段直接抛错，能第一时间暴露版本不兼容。
	 */
	private static void verifyMixinTargets() {
		for (final String className : new String[]{
				"org.mtr.core.data.Vehicle",
				"org.mtr.core.data.VehicleExtraData",
				"org.mtr.core.data.Rail",
				"org.mtr.core.data.PathData",
				"org.mtr.core.data.Siding"}) {
			try {
				final Class<?> clazz = Class.forName(className);
				LOGGER.info("[{}] Mixin 目标类加载成功：{}（{}）", MOD_ID, className, clazz.getClassLoader());
			} catch (Throwable throwable) {
				LOGGER.error("[{}] Mixin 目标类加载失败：{}", MOD_ID, className, throwable);
			}
		}
	}

	public static Path configPath() {
		return FMLPaths.CONFIGDIR.get().resolve(CONFIG_FILE_NAME);
	}
}
