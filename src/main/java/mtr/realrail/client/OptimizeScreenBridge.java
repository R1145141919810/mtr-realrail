package mtr.realrail.client;

import mtr.realrail.traffic.OptimizeSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.mtr.core.data.Depot;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * 客户端界面桥接：「MTR 车厂配置界面（EditDepotScreen）」与「优化线路配置界面」之间的数据传递。
 *
 * <p>流程：</p>
 * <ol>
 *   <li>车厂配置界面初始化时 {@code EditDepotScreenMixin} 通过反射读取该界面的 {@code Depot} 对象，
 *       按 MTR 自身的布局在右栏追加一个"优化线路配置"按钮（或按快捷键 O）；</li>
 *   <li>点击/按键 → 记住车厂配置界面（用于 ESC 返回）与车厂 id/名称 → 立即打开界面，
 *       同时向服务端请求该车厂的已保存配置，收到后刷新界面；</li>
 *   <li>配置界面关闭（ESC）时返回此前记住的车厂配置界面。</li>
 * </ol>
 *
 * <p><b>反射要点</b>：MTR 的 {@code EditNameColorScreenBase.data} 字段是泛型
 * {@code T extends NameColorDataBase}，编译后描述符是
 * {@code Lorg/mtr/core/data/NameColorDataBase;}（不是 {@code Depot}）。
 * 因此这里**不做字段类型过滤**，而是遍历所有非静态字段读取实际值并用 {@code instanceof Depot} 判定，
 * 否则永远匹配不到、按钮不会出现。</p>
 */
public final class OptimizeScreenBridge {

	private static Screen depotScreen;
	private static long depotId;
	private static String depotName = "";
	private static OptimizeSettings settings = OptimizeSettings.defaults();
	private static String status = "";

	private OptimizeScreenBridge() {
	}

	/** 在 MTR 车厂配置界面中调用：记住界面与车厂信息。 */
	public static void rememberDepot(Screen screen, long id, String name) {
		depotScreen = screen;
		depotId = id;
		depotName = name == null ? "" : name;
	}

	public static Screen depotScreen() {
		return depotScreen;
	}

	public static long depotId() {
		return depotId;
	}

	public static String depotName() {
		return depotName;
	}

	public static OptimizeSettings settings() {
		return settings;
	}

	public static void setSettings(OptimizeSettings newSettings) {
		settings = newSettings;
	}

	public static String status() {
		return status;
	}

	public static void setStatus(String newStatus) {
		status = newStatus == null ? "" : newStatus;
	}

	/** 打开本模组的"优化线路配置"界面。 */
	public static void openOptimizeScreen() {
		Minecraft.getInstance().setScreen(new DepotOptimizeScreen(depotScreen));
	}

	/** 返回 MTR 车厂配置界面（ESC 行为）。 */
	public static boolean restoreDepotScreen() {
		final Screen screen = depotScreen;
		if (screen == null) {
			return false;
		}
		Minecraft.getInstance().setScreen(screen);
		return true;
	}

	/**
	 * 反射读取 MTR 车厂界面中的 {@code Depot} 对象（失败返回 null）。
	 *
	 * @see OptimizeScreenBridge 类注释中的"反射要点"
	 */
	public static Depot readDepot(Screen screen) {
		return readObject(screen, Depot.class);
	}

	private static Depot readDepotLegacy(Screen screen) {
		if (screen == null) {
			return null;
		}
		Class<?> clazz = screen.getClass();
		while (clazz != null && clazz != Object.class) {
			for (final Field field : clazz.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
					continue;
				}
				try {
					if (!field.trySetAccessible()) {
						continue;
					}
					if (field.get(screen) instanceof Depot depot) {
						return depot;
					}
				} catch (Throwable ignored) {
					// 反射受限时安静跳过：按钮不显示，MTR 原版界面不受影响
				}
			}
			clazz = clazz.getSuperclass();
		}
		return null;
	}

	/** 反射读取对象的 int 字段（用于复用 MTR 自身的布局参数，如 rightPanelsX）。 */
	public static int readIntField(Object target, String fieldName, int fallback) {
		Class<?> clazz = target == null ? null : target.getClass();
		while (clazz != null && clazz != Object.class) {
			try {
				final Field field = clazz.getDeclaredField(fieldName);
				if (field.trySetAccessible() && field.getType() == int.class) {
					return field.getInt(target);
				}
			} catch (Throwable ignored) {
				// 继续向父类查找
			}
			clazz = clazz.getSuperclass();
		}
		return fallback;
	}

	/** 反射读取界面中第一个属于指定类型的字段值（用于车站/线路/车厂等 MTR 数据对象）。 */
	public static <T> T readObject(Screen screen, Class<T> type) {
		if (screen == null) {
			return null;
		}
		Class<?> clazz = screen.getClass();
		while (clazz != null && clazz != Object.class) {
			for (final Field field : clazz.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
					continue;
				}
				try {
					if (!field.trySetAccessible()) {
						continue;
					}
					final Object value = field.get(screen);
					if (type.isInstance(value)) {
						return type.cast(value);
					}
				} catch (Throwable ignored) {
					// 反射受限时安静跳过
				}
			}
			clazz = clazz.getSuperclass();
		}
		return null;
	}

	/** 反射读取对象的任意字段（用于复用 MTR 自身的布局控件，例如复选框的可见性）。 */
	public static Object readFieldObject(Object target, String fieldName) {
		Class<?> clazz = target == null ? null : target.getClass();
		while (clazz != null && clazz != Object.class) {
			try {
				final Field field = clazz.getDeclaredField(fieldName);
				if (field.trySetAccessible()) {
					return field.get(target);
				}
			} catch (Throwable ignored) {
				// 继续向父类查找
			}
			clazz = clazz.getSuperclass();
		}
		return null;
	}

	/** 反射读取对象的 boolean 字段。 */
	public static boolean readBooleanField(Object target, String fieldName, boolean fallback) {
		Class<?> clazz = target == null ? null : target.getClass();
		while (clazz != null && clazz != Object.class) {
			try {
				final Field field = clazz.getDeclaredField(fieldName);
				if (field.trySetAccessible() && field.getType() == boolean.class) {
					return field.getBoolean(target);
				}
			} catch (Throwable ignored) {
				// 继续向父类查找
			}
			clazz = clazz.getSuperclass();
		}
		return fallback;
	}
}
