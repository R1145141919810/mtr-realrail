package mtr.realrail;

import org.mtr.core.data.Position;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 每个轨道节点（轨道连接器位置）的限速判定方式存储。
 *
 * <p>MTR 4.0.x 中节点没有独立对象（节点 = 两段 Rail 共用的 {@link Position}），
 * 因此本存储以坐标为键，与 MTR 自身的 {@code positionsToRail} 索引方式一致。</p>
 *
 * <p>只有被刷子显式切换过的节点才进入 {@link #OVERRIDES}；其余节点使用
 * {@link RealRailConfig#defaultNodeMode}。</p>
 *
 * <p>持久化：存档目录下的 {@code mtrrealrail/nodes.json}，内容为
 * {@code {"x,y,z": "TAIL|HEAD"}} 形式的简单 JSON。</p>
 */
public final class NodeModeStore {

	private static final Map<String, RealRailConfig.NodeMode> OVERRIDES = new ConcurrentHashMap<>();
	private static final Pattern JSON_ENTRY = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"(TAIL|HEAD)\"");

	private static volatile Path savePath;

	private NodeModeStore() {
	}

	public static String key(long x, long y, long z) {
		return x + "," + y + "," + z;
	}

	public static String key(Position position) {
		return key(position.getX(), position.getY(), position.getZ());
	}

	/** 该节点的生效判定方式（刷子显式设置优先，否则取全局默认值）。 */
	public static RealRailConfig.NodeMode getMode(int x, int y, int z) {
		return OVERRIDES.getOrDefault(key(x, y, z), RealRailConfig.INSTANCE.defaultNodeMode);
	}

	public static RealRailConfig.NodeMode getMode(Position position) {
		return OVERRIDES.getOrDefault(key(position), RealRailConfig.INSTANCE.defaultNodeMode);
	}

	/** 该节点是否为"车尾判定"（需求一核心判定入口）。 */
	public static boolean isTailMode(Position position) {
		return getMode(position) == RealRailConfig.NodeMode.TAIL;
	}

	public static boolean isTailMode(int x, int y, int z) {
		return getMode(x, y, z) == RealRailConfig.NodeMode.TAIL;
	}

	/** 切换节点判定方式并立即持久化，返回切换后的模式。 */
	public static RealRailConfig.NodeMode toggle(int x, int y, int z) {
		final RealRailConfig.NodeMode next = getMode(x, y, z) == RealRailConfig.NodeMode.TAIL
				? RealRailConfig.NodeMode.HEAD
				: RealRailConfig.NodeMode.TAIL;
		OVERRIDES.put(key(x, y, z), next);
		save();
		return next;
	}

	public static int size() {
		return OVERRIDES.size();
	}

	public static Path savePath() {
		return savePath;
	}

	/** 服务端世界启动时调用；找不到文件则清空并返回。 */
	public static void load(Path file) {
		savePath = file;
		OVERRIDES.clear();
		if (file == null || !Files.isRegularFile(file)) {
			return;
		}
		try {
			final String content = Files.readString(file, StandardCharsets.UTF_8);
			final Matcher matcher = JSON_ENTRY.matcher(content);
			while (matcher.find()) {
				OVERRIDES.put(matcher.group(1), RealRailConfig.NodeMode.valueOf(matcher.group(2)));
			}
		} catch (IOException | IllegalArgumentException exception) {
			MtrRealRail.LOGGER.warn("[{}] 读取节点判定数据失败：{}", MtrRealRail.MOD_ID, exception.getMessage());
		}
	}

	public static void save() {
		save(savePath);
	}

	public static void save(Path file) {
		if (file == null) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			final StringBuilder builder = new StringBuilder("{\n");
			boolean first = true;
			for (final Map.Entry<String, RealRailConfig.NodeMode> entry : OVERRIDES.entrySet()) {
				if (!first) {
					builder.append(",\n");
				}
				builder.append("\t\"").append(entry.getKey()).append("\": \"").append(entry.getValue().name()).append('"');
				first = false;
			}
			builder.append("\n}\n");
			Files.writeString(file, builder.toString(), StandardCharsets.UTF_8);
		} catch (IOException exception) {
			MtrRealRail.LOGGER.warn("[{}] 写出节点判定数据失败：{}", MtrRealRail.MOD_ID, exception.getMessage());
		}
	}
}
