package mtr.realrail;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * 全局配置（config/mtrrealrail.properties）。
 *
 * <p>配置在服务端启动时读取；运行中修改需重启（与 MTR 自身配置行为一致）。</p>
 */
public final class RealRailConfig {

	public static final RealRailConfig INSTANCE = new RealRailConfig();

	/** 轨道节点限速变更的判定方式。 */
	public enum NodeMode {
		/** 车头进入新轨道段立即变更速度（MTR 原版行为）。 */
		HEAD,
		/** 车尾完全经过轨道连接器后才变更速度（本模组默认，需求一）。 */
		TAIL
	}

	/** 未被刷子单独配置的节点所使用的默认判定方式（需求一：默认车尾判定）。 */
	public NodeMode defaultNodeMode = NodeMode.TAIL;

	/** 是否启用多信号系统隔离（需求二）。 */
	public boolean signalIsolation = true;

	private static final String KEY_DEFAULT_NODE_MODE = "default_node_mode";
	private static final String KEY_SIGNAL_ISOLATION = "signal_isolation";

	private RealRailConfig() {
	}

	/** 加载配置；文件不存在或损坏时保留当前值（首次运行为默认值）。 */
	public void load(Path file) {
		if (file == null || !Files.isRegularFile(file)) {
			return;
		}
		final Properties properties = new Properties();
		try (final var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			properties.load(reader);
		} catch (IOException exception) {
			MtrRealRail.LOGGER.warn("[{}] 读取配置失败：{}", MtrRealRail.MOD_ID, exception.getMessage());
			return;
		}

		try {
			defaultNodeMode = NodeMode.valueOf(properties.getProperty(KEY_DEFAULT_NODE_MODE, "TAIL").trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException ignored) {
			defaultNodeMode = NodeMode.TAIL;
		}
		signalIsolation = Boolean.parseBoolean(properties.getProperty(KEY_SIGNAL_ISOLATION, "true").trim());
	}

	/** 写出配置（带注释，便于玩家手改）。 */
	public void save(Path file) {
		if (file == null) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
		} catch (IOException ignored) {
		}
		try (final PrintWriter writer = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
			writer.println("# 更好的列车限速及信号判定（MTR RealRail）配置");
			writer.println("#");
			writer.println("# default_node_mode: TAIL = 车尾完全经过轨道连接器后才变更速度（默认，需求一）");
			writer.println("#                    HEAD = 车头进入新轨道段立即变更速度（MTR 原版行为）");
			writer.println("# 单个轨道节点可用刷子（mtr:brush）右击轨道节点方块：先打开 MTR 原版轨道界面，");
			writer.println("# 点击其中的“节点限速判定”按钮即可单独覆盖此设置。");
			writer.println(KEY_DEFAULT_NODE_MODE + "=" + defaultNodeMode.name());
			writer.println("#");
			writer.println("# signal_isolation: true = 16 色多信号系统各自独立判定区间占用，互不干扰（需求二）");
			writer.println(KEY_SIGNAL_ISOLATION + "=" + signalIsolation);
		} catch (IOException exception) {
			MtrRealRail.LOGGER.warn("[{}] 写出配置失败：{}", MtrRealRail.MOD_ID, exception.getMessage());
		}
	}
}
