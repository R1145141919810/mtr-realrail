package mtr.realrail.dispatch;

import mtr.realrail.MtrRealRail;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 与<a href="https://github.com/">外置调度服务 tsc-dispatch 1.0.0</a>通信的最小 HTTP 客户端。
 *
 * <p>tsc-dispatch 是一个<b>独立进程</b>（零第三方依赖的单 jar），通过 HTTP 与运行中的服务器交互：
 * 轮询 MTR 只读接口采集遥测、通过控制桥/RCON 下发逐车指令。
 * 本模组的"车站站台智能调度"改为<b>依赖</b>该服务：站台占用与选台决策由它给出，
 * 本模组只负责在游戏内执行（改停站台 / 等待）与界面交互。</p>
 *
 * <p>不引入任何第三方 HTTP 库：使用 JDK 自带的 {@link HttpURLConnection}，全部调用在守护线程上异步执行，
 * 绝不阻塞服务器主线程。</p>
 */
public final class TscDispatchClient {

	public enum Status {
		DISABLED, UNKNOWN, ONLINE, OFFLINE
	}

	private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(runnable -> {
		final Thread thread = new Thread(runnable, "mtrrealrail-tsc-dispatch");
		thread.setDaemon(true);
		return thread;
	});

	private TscDispatchClient() {
	}

	public static void runAsync(Runnable task) {
		EXECUTOR.execute(() -> {
			try {
				task.run();
			} catch (Throwable throwable) {
				MtrRealRail.LOGGER.debug("[{}] tsc-dispatch 异步任务异常：{}", MtrRealRail.MOD_ID, throwable.toString());
			}
		});
	}

	public static void schedule(Runnable task, long initialDelayMillis, long periodMillis) {
		EXECUTOR.scheduleWithFixedDelay(() -> {
			try {
				task.run();
			} catch (Throwable throwable) {
				MtrRealRail.LOGGER.debug("[{}] tsc-dispatch 周期任务异常：{}", MtrRealRail.MOD_ID, throwable.toString());
			}
		}, initialDelayMillis, periodMillis, TimeUnit.MILLISECONDS);
	}

	/** GET 文本（带 Bearer token；token 为空时不带认证头）。 */
	public static String get(String url, String token, int timeoutMillis) throws Exception {
		return read(get(url, timeoutMillis, token));
	}

	/** POST JSON 文本（带 Bearer token）。 */
	public static String post(String url, String token, String body, int timeoutMillis) throws Exception {
		return read(post(url, body, timeoutMillis, token));
	}

	private static String read(String value) {
		return value;
	}

	/** GET 文本；失败抛异常由调用方降级处理。 */
	public static String get(String url, int timeoutMillis) throws Exception {
		return get(url, timeoutMillis, null);
	}

	public static String get(String url, int timeoutMillis, String token) throws Exception {
		final HttpURLConnection connection = open(url, "GET", timeoutMillis, token);
		try (InputStream input = connection.getInputStream()) {
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		} finally {
			connection.disconnect();
		}
	}

	/** POST JSON 文本。 */
	public static String post(String url, String body, int timeoutMillis) throws Exception {
		return post(url, body, timeoutMillis, null);
	}

	public static String post(String url, String body, int timeoutMillis, String token) throws Exception {
		final HttpURLConnection connection = open(url, "POST", timeoutMillis, token);
		connection.setDoOutput(true);
		connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
		try (OutputStream output = connection.getOutputStream()) {
			output.write(body.getBytes(StandardCharsets.UTF_8));
		}
		try (InputStream input = connection.getInputStream()) {
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		} finally {
			connection.disconnect();
		}
	}

	private static HttpURLConnection open(String url, String method, int timeoutMillis, String token) throws Exception {
		final HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
		connection.setRequestMethod(method);
		connection.setConnectTimeout(timeoutMillis);
		connection.setReadTimeout(timeoutMillis);
		connection.setRequestProperty("Accept", "application/json");
		if (token != null && !token.isBlank()) {
			connection.setRequestProperty("Authorization", "Bearer " + token);
		}
		return connection;
	}
}
