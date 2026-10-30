package mtr.realrail.mixin;

import mtr.realrail.traffic.PlatformDispatchStore;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>线路智能调度站台</b>的"生效点"：MTR 在生成路径前会先把线路站序里的 platformId
 * 解析成 {@link Platform} 缓存（{@code RoutePlatformData.writePlatformCache}），
 * 路径生成器随后读取这个缓存。
 *
 * <p>本 mixin 在该方法返回后检查：若该线路 + 该车站启用了智能调度，且当前站台
 * <b>不在允许集合内</b>，则把缓存替换为允许集合中的第一个同车站站台——
 * 于是 MTR 之后生成的路径就会停到被允许的站台上。多选集合因此直接决定"哪些站台可以被停靠"。</p>
 */
@Mixin(RoutePlatformData.class)
public abstract class RoutePlatformDataMixin {

	@Inject(method = "writePlatformCache", at = @At("RETURN"))
	private void mtrrealrail$applyAllowedPlatform(Route route, Long2ObjectOpenHashMap<Platform> platformIdMap, CallbackInfo callbackInfo) {
		final RoutePlatformData self = (RoutePlatformData) (Object) this;
		final Platform current = self.getPlatform();
		if (current == null || current.area == null) {
			return;
		}
		final long routeId = route.getId();
		final long stationId = current.area.getId();
		if (!PlatformDispatchStore.isDispatchActive(routeId, stationId)) {
			return;
		}
		final java.util.Set<Long> allowed = PlatformDispatchStore.allowedPlatforms(routeId, stationId);
		if (allowed.isEmpty() || allowed.contains(current.getId())) {
			return; // 未配置允许集合 或 当前站台本就允许 → 不改动
		}
		for (final long platformId : allowed) {
			final Platform candidate = platformIdMap.get(platformId);
			if (candidate != null && candidate.area != null && candidate.area.getId() == stationId && candidate.isValid()) {
				self.platform = candidate;
				return;
			}
		}
	}
}
