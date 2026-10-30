package mtr.realrail.traffic;

import mtr.realrail.MtrRealRail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>车站站台智能调度</b>的配置存储。
 *
 * <p>三个层级：</p>
 * <ol>
 *   <li><b>车站级开关</b>（{@link #isStationEnabled}）：车厂…车站配置界面中"站台智能调度"开关，
 *       只有拥有 ≥2 个站台的车站才允许开启（否则没有可调度的余地）；</li>
 *   <li><b>线路级开关</b>（{@link #isRouteEnabled}）：线路配置界面中的"线路智能调度站台"；</li>
 *   <li><b>允许停靠站台集合</b>（{@link #allowedPlatforms}）：线路 × 车站 → 可停靠站台 id 集合（多选）。
 *       集合为空表示"未做限制"（按 MTR 原版行为）。</li>
 * </ol>
 *
 * <p>持久化：{@code world/mtrrealrail/platforms.json}，纯文本、逐行容错（坏行只跳过该行）。</p>
 */
public final class PlatformDispatchStore {

	/** 车站 → 是否启用智能调度。 */
	private static final Map<Long, Boolean> STATION_ENABLED = new ConcurrentHashMap<>();
	/** 线路 → 是否启用智能调度。 */
	private static final Map<Long, Boolean> ROUTE_ENABLED = new ConcurrentHashMap<>();
	/** 线路 × 车站 → 允许停靠的站台 id 集合。 */
	private static final Map<Long, Map<Long, Set<Long>>> ALLOWED = new ConcurrentHashMap<>();

	private static volatile Path savePath;

	private PlatformDispatchStore() {
	}

	public static boolean isStationEnabled(long stationId) {
		return Boolean.TRUE.equals(STATION_ENABLED.get(stationId));
	}

	public static void setStationEnabled(long stationId, boolean enabled) {
		if (enabled) {
			STATION_ENABLED.put(stationId, Boolean.TRUE);
		} else {
			STATION_ENABLED.remove(stationId);
		}
		save();
	}

	public static boolean isRouteEnabled(long routeId) {
		return Boolean.TRUE.equals(ROUTE_ENABLED.get(routeId));
	}

	public static void setRouteEnabled(long routeId, boolean enabled) {
		if (enabled) {
			ROUTE_ENABLED.put(routeId, Boolean.TRUE);
		} else {
			ROUTE_ENABLED.remove(routeId);
		}
		save();
	}

	/** 该线路在該车站允许停靠的站台集合（空集 = 未限制）。 */
	public static Set<Long> allowedPlatforms(long routeId, long stationId) {
		final Map<Long, Set<Long>> byStation = ALLOWED.get(routeId);
		if (byStation == null) {
			return Set.of();
		}
		final Set<Long> platforms = byStation.get(stationId);
		return platforms == null ? Set.of() : platforms;
	}

	public static void setAllowedPlatforms(long routeId, long stationId, Set<Long> platformIds) {
		final Map<Long, Set<Long>> byStation = ALLOWED.computeIfAbsent(routeId, key -> new ConcurrentHashMap<>());
		if (platformIds == null || platformIds.isEmpty()) {
			byStation.remove(stationId);
		} else {
			byStation.put(stationId, new TreeSet<>(platformIds));
		}
		save();
	}

	/** 所有配置过允许集合的线路 id（导出用）。 */
	public static java.util.Set<Long> routeIds() {
		return new TreeSet<>(ALLOWED.keySet());
	}

	/** 该线路配置过的车站 id（导出用）。 */
	public static java.util.Set<Long> stationIds(long routeId) {
		final Map<Long, Set<Long>> byStation = ALLOWED.get(routeId);
		return byStation == null ? Set.of() : new TreeSet<>(byStation.keySet());
	}

	/** 该线路是否对该车站启用了"站台限制"。 */
	public static boolean hasRestriction(long routeId, long stationId) {
		return !allowedPlatforms(routeId, stationId).isEmpty();
	}

	/** 智能调度是否对该（线路, 车站）组合生效：车站开关 + 线路开关 + 至少一个允许站台。 */
	public static boolean isDispatchActive(long routeId, long stationId) {
		return isStationEnabled(stationId) && isRouteEnabled(routeId);
	}

	public static int stationCount() {
		return STATION_ENABLED.size();
	}

	public static int routeCount() {
		return ROUTE_ENABLED.size();
	}

	// ------------------------------------------------------------------ 持久化

	public static void load(Path file) {
		savePath = file;
		STATION_ENABLED.clear();
		ROUTE_ENABLED.clear();
		ALLOWED.clear();
		if (file == null || !Files.isRegularFile(file)) {
			return;
		}
		int loaded = 0;
		int skipped = 0;
		try {
			for (final String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				final String line = rawLine.trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				try {
					final String[] parts = line.split("\\|", -1);
					if ("station".equals(parts[0]) && parts.length >= 2) {
						STATION_ENABLED.put(Long.parseLong(parts[1].trim()), Boolean.TRUE);
					} else if ("route".equals(parts[0]) && parts.length >= 2) {
						ROUTE_ENABLED.put(Long.parseLong(parts[1].trim()), Boolean.TRUE);
					} else if ("allow".equals(parts[0]) && parts.length >= 4) {
						final Set<Long> platforms = new TreeSet<>();
						for (final String platformId : parts[3].split(",")) {
							if (!platformId.isBlank()) {
								platforms.add(Long.parseLong(platformId.trim()));
							}
						}
						setAllowedPlatformsInternal(Long.parseLong(parts[1].trim()), Long.parseLong(parts[2].trim()), platforms);
					} else {
						skipped++;
						continue;
					}
					loaded++;
				} catch (NumberFormatException ignored) {
					skipped++;
				}
			}
		} catch (IOException exception) {
			MtrRealRail.LOGGER.warn("[{}] 读取站台智能调度配置失败（{}）：{}", MtrRealRail.MOD_ID, file, exception.getMessage());
			return;
		}
		MtrRealRail.LOGGER.info("[{}] 站台智能调度配置读取完成：成功 {} 条，跳过 {} 行（{}）", MtrRealRail.MOD_ID, loaded, skipped, file);
	}

	private static void setAllowedPlatformsInternal(long routeId, long stationId, Set<Long> platformIds) {
		final Map<Long, Set<Long>> byStation = ALLOWED.computeIfAbsent(routeId, key -> new ConcurrentHashMap<>());
		if (platformIds.isEmpty()) {
			byStation.remove(stationId);
		} else {
			byStation.put(stationId, platformIds);
		}
	}

	public static void save() {
		final Path file = savePath;
		if (file == null) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			final StringBuilder builder = new StringBuilder();
			builder.append("# 车站站台智能调度配置（以 # 开头的行为注释；坏行只跳过该行）\n");
			builder.append("# station|车站id    —— 该车站启用站台智能调度\n");
			builder.append("# route|线路id      —— 该线路启用线路智能调度站台\n");
			builder.append("# allow|线路id|车站id|站台id,站台id…  —— 该线路上该车站允许停靠的站台\n");
			new TreeSet<>(STATION_ENABLED.keySet()).forEach(stationId -> builder.append("station|").append(stationId).append('\n'));
			new TreeSet<>(ROUTE_ENABLED.keySet()).forEach(routeId -> builder.append("route|").append(routeId).append('\n'));
			new TreeSet<>(ALLOWED.keySet()).forEach(routeId -> {
				final Map<Long, Set<Long>> byStation = ALLOWED.get(routeId);
				if (byStation == null) {
					return;
				}
				new TreeSet<>(byStation.keySet()).forEach(stationId -> {
					final Set<Long> platforms = byStation.get(stationId);
					if (platforms == null || platforms.isEmpty()) {
						return;
					}
					builder.append("allow|").append(routeId).append('|').append(stationId).append('|');
					boolean first = true;
					for (final long platformId : new TreeSet<>(platforms)) {
						if (!first) {
							builder.append(',');
						}
						builder.append(platformId);
						first = false;
					}
					builder.append('\n');
				});
			});
			Files.writeString(file, builder.toString(), StandardCharsets.UTF_8);
		} catch (IOException exception) {
			MtrRealRail.LOGGER.warn("[{}] 写出站台智能调度配置失败：{}", MtrRealRail.MOD_ID, exception.getMessage());
		}
		// 同步给外置调度服务：把允许集合导出为 tsc-dispatch 的 platform_allow 规则（后台线程）
		mtr.realrail.dispatch.TscDispatchClient.runAsync(mtr.realrail.dispatch.TscDispatchExporter::export);
	}
}
