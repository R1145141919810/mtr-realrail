package mtr.realrail.dispatch;

import mtr.realrail.MtrRealRail;
import mtr.realrail.RealRailConfig;
import mtr.realrail.traffic.PlatformDispatchStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * 把本模组界面里配置的"线路 × 车站 → 允许停靠站台"导出为 tsc-dispatch 可加载的
 * {@code platform_allow} 规则片段，使外置调度服务按同一套允许集合规划。
 *
 * <p>导出格式与 tsc-dispatch 的 {@code config/dispatch.yml} 中 {@code platform_allow.rules} 一致：</p>
 * <pre>
 * platform_allow:
 *   default: DENY
 *   rules:
 *     - route: "0x…"
 *       station: "0x…"
 *       platforms: ["0x…", "0x…"]
 * </pre>
 *
 * <p>失败只记日志，绝不影响服务器运行。</p>
 */
public final class TscDispatchExporter {

	private TscDispatchExporter() {
	}

	public static void export() {
		final String configured = RealRailConfig.INSTANCE.tscDispatchExportPath;
		if (configured == null || configured.isBlank()) {
			return;
		}
		try {
			final Path path = Path.of(configured);
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}
			Files.writeString(path, render(), StandardCharsets.UTF_8);
			MtrRealRail.LOGGER.info("[{}] 已导出站台允许集合到 tsc-dispatch 配置：{}", MtrRealRail.MOD_ID, path.toAbsolutePath());
		} catch (Exception exception) {
			MtrRealRail.LOGGER.warn("[{}] 导出站台允许集合失败：{}", MtrRealRail.MOD_ID, exception.getMessage());
		}
	}

	/** 生成 YAML 文本（不写文件，便于测试/预览）。 */
	public static String render() {
		final StringBuilder builder = new StringBuilder();
		builder.append("# 由「更好的列车限速及信号判定」自动生成：车站站台智能调度的允许停靠集合\n");
		builder.append("# 对应 tsc-dispatch 的 platform_allow 段，可 include/合并进 dispatch.yml。\n");
		builder.append("# 执行桥（mtbridge）参数：令牌只能写在配置文件里（运行时 API 不接受 bridge.token）\n");
		builder.append("bridge:\n");
		builder.append("  base_url: \"").append(RealRailConfig.INSTANCE.bridgeUrl).append("\"\n");
		final String token = TscDispatchBridge.bridgeToken();
		builder.append("  auth_token: \"").append(token).append("\"\n");
		builder.append("  mode: bridge\n");
		builder.append("\n");
		builder.append("platform_allow:\n");
		builder.append("  default: DENY\n");
		builder.append("  rules:\n");
		int count = 0;
		for (final long routeId : PlatformDispatchStore.routeIds()) {
			for (final long stationId : PlatformDispatchStore.stationIds(routeId)) {
				final Set<Long> platforms = PlatformDispatchStore.allowedPlatforms(routeId, stationId);
				if (platforms.isEmpty()) {
					continue;
				}
				builder.append("    - route: \"").append(toHex(routeId)).append("\"\n");
				builder.append("      station: \"").append(toHex(stationId)).append("\"\n");
				builder.append("      platforms: [");
				boolean first = true;
				for (final long platformId : platforms) {
					if (!first) {
						builder.append(", ");
					}
					builder.append('"').append(toHex(platformId)).append('"');
					first = false;
				}
				builder.append("]\n");
				count++;
			}
		}
		if (count == 0) {
			builder.append("    []\n");
		}
		return builder.toString();
	}

	private static String toHex(long id) {
		return "0x" + Long.toHexString(id);
	}
}
