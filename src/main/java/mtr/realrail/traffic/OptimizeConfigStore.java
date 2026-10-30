package mtr.realrail.traffic;

import mtr.realrail.MtrRealRail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 车厂级「优化线路配置」存储：{@code 车厂 id → OptimizeSettings}。
 *
 * <p>与 {@code mtr.realrail.NodeModeStore} 相同的方式持久化到存档目录
 * （{@code world/mtrrealrail/optimize.json}），未单独配置的车厂使用全局默认值
 * （来自 {@code config/mtrrealrail.properties}）。</p>
 */
public final class OptimizeConfigStore {

	private static final Map<Long, OptimizeSettings> OVERRIDES = new ConcurrentHashMap<>();
	private static volatile Path savePath;

	private OptimizeConfigStore() {
	}

	/** 取某车厂的生效配置（无覆盖时返回全局默认值的副本）。 */
	public static OptimizeSettings get(long depotId) {
		final OptimizeSettings override = OVERRIDES.get(depotId);
		return override == null ? mtr.realrail.RealRailConfig.INSTANCE.optimizeDefaults.copy() : override.copy();
	}

	/** 直接读写用（GUI 更新后调用）。 */
	public static void put(long depotId, OptimizeSettings settings) {
		OVERRIDES.put(depotId, settings.copy());
		save();
	}

	public static void reset(long depotId) {
		OVERRIDES.remove(depotId);
		save();
	}

	public static void load(Path file) {
		savePath = file;
		OVERRIDES.clear();
		if (file == null || !Files.isRegularFile(file)) {
			return;
		}
		int loaded = 0;
		int skipped = 0;
		try {
			for (final String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				final String line = rawLine.trim();
				// 注释行（文件首行是带 | 的表头说明）与空行必须跳过，否则会被当成数据行
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				final String[] parts = line.split("\\|");
				if (parts.length < 9) {
					skipped++;
					continue;
				}
				try {
					final OptimizeSettings settings = new OptimizeSettings();
					settings.dynamicBlockRelease = Boolean.parseBoolean(parts[1].trim());
					settings.safetyMarginMeters = Double.parseDouble(parts[2].trim());
					settings.routeOptimize = Boolean.parseBoolean(parts[3].trim());
					settings.pathLookaheadMeters = Double.parseDouble(parts[4].trim());
					settings.departureGuard = Boolean.parseBoolean(parts[5].trim());
					settings.dwellThresholdSeconds = Integer.parseInt(parts[6].trim());
					settings.conflictWindowSeconds = Integer.parseInt(parts[7].trim());
					settings.maxHoldSeconds = Integer.parseInt(parts[8].trim());
					if (parts.length > 9) {
						settings.midLineDepot = Boolean.parseBoolean(parts[9].trim());
					}
					if (parts.length > 10) {
						settings.deployLeadMinutes = Integer.parseInt(parts[10].trim());
					}
					if (parts.length > 11) {
						settings.idleReturnMinutes = Integer.parseInt(parts[11].trim());
					}
					OVERRIDES.put(Long.parseLong(parts[0].trim()), settings);
					loaded++;
				} catch (NumberFormatException ignored) {
					// 单行损坏只跳过该行，不影响其它车厂的配置
					skipped++;
				}
			}
		} catch (IOException exception) {
			MtrRealRail.LOGGER.warn("[{}] 读取车厂优化配置失败（{}）：{}", MtrRealRail.MOD_ID, file, exception.getMessage());
			return;
		}
		if (skipped > 0) {
			MtrRealRail.LOGGER.warn("[{}] 车厂优化配置中有 {} 行无法解析，已跳过（文件：{}）", MtrRealRail.MOD_ID, skipped, file);
		}
		MtrRealRail.LOGGER.info("[{}] 车厂优化配置读取完成：成功 {} 条，跳过 {} 行（{}）", MtrRealRail.MOD_ID, loaded, skipped, file);
	}

	public static void save() {
		final Path file = savePath;
		if (file == null) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			final StringBuilder builder = new StringBuilder();
			builder.append("# 每行格式：车厂 id|动态闭塞|安全间距(米)|路径优化|前瞻距离(米)|发车避让|停站阈值(秒)|冲突窗口(秒)|最长等待(秒)|车厂在线路中间|提前派车(分)|回场判定(分)\n");
			builder.append("# 以 # 开头的行是注释，读取时会被跳过；单行损坏只跳过该行。\n");
			final java.util.List<Map.Entry<Long, OptimizeSettings>> entries = new java.util.ArrayList<>(OVERRIDES.entrySet());
			entries.sort(Map.Entry.comparingByKey());
			for (final Map.Entry<Long, OptimizeSettings> entry : entries) {
				final OptimizeSettings settings = entry.getValue();
				builder.append(entry.getKey()).append('|')
						.append(settings.dynamicBlockRelease).append('|')
						.append(settings.safetyMarginMeters).append('|')
						.append(settings.routeOptimize).append('|')
						.append(settings.pathLookaheadMeters).append('|')
						.append(settings.departureGuard).append('|')
						.append(settings.dwellThresholdSeconds).append('|')
						.append(settings.conflictWindowSeconds).append('|')
						.append(settings.maxHoldSeconds).append('|')
						.append(settings.midLineDepot).append('|')
						.append(settings.deployLeadMinutes).append('|')
						.append(settings.idleReturnMinutes).append('\n');
			}
			Files.writeString(file, builder.toString(), StandardCharsets.UTF_8);
		} catch (IOException exception) {
			MtrRealRail.LOGGER.warn("[{}] 写出车厂优化配置失败：{}", MtrRealRail.MOD_ID, exception.getMessage());
		}
	}

	public static int size() {
		return OVERRIDES.size();
	}
}
