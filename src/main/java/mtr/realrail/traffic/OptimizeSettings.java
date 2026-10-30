package mtr.realrail.traffic;

/**
 * 「优化线路配置」的可调参数（车厂级，可在 GUI 中修改）。
 *
 * <p>三组功能开关：</p>
 * <ul>
 *   <li><b>动态轨道占用</b>：{@link #dynamicBlockRelease} + {@link #safetyMarginMeters}
 *       —— 用列车实际位置/速度计算的安全间距，在同一信号系统内放行跟车（移动闭塞），
 *       补充 MTR 的固定闭塞（同色连通区间）。</li>
 *   <li><b>自动最优路径规划</b>：{@link #routeOptimize} + {@link #pathLookaheadMeters}
 *       —— 依据实时占用与预计到达时间对进路打分；需要时重新生成车厂路径。</li>
 *   <li><b>动态发车与避让</b>：{@link #departureGuard} + {@link #dwellThresholdSeconds} +
 *       {@link #conflictWindowSeconds} + {@link #maxHoldSeconds}
 *       —— 列车停站满 dwellThresholdSeconds 后，若同信号系统内有列车将在
 *       conflictWindowSeconds 内到达/占用前方区间，则等待让行，最长等待 maxHoldSeconds。</li>
 * </ul>
 */
public final class OptimizeSettings {

	/** 需求二/动态闭塞：同一信号系统内是否允许“移动闭塞”式跟车放行。 */
	public boolean dynamicBlockRelease = true;
	/** 动态闭塞的安全间距（米）：前车车尾与本车车头之间至少保持该距离才放行。 */
	public double safetyMarginMeters = 30.0;
	/** 是否启用自动最优路径规划（进路打分 + 必要时重新生成车厂路径）。 */
	public boolean routeOptimize = true;
	/** 进路打分前瞻距离（米）。 */
	public double pathLookaheadMeters = 400.0;
	/** 是否启用动态发车/避让（停站满阈值后按实时交通决定是否等待）。 */
	public boolean departureGuard = true;
	/** 停站达到该秒数后才开始做发车决策（任务场景：A 停站满 10 秒）。 */
	public int dwellThresholdSeconds = 10;
	/** 冲突窗口（秒）：同信号系统内若有列车在该时间内到达/占用前方，则本车等待让行。 */
	public int conflictWindowSeconds = 5;
	/** 最长等待让行时间（秒），超过则强制发车（必要时重新生成路径）。默认 15 秒，避免长时间占用站台。 */
	public int maxHoldSeconds = 15;

	/**
	 * <b>车厂在线路中间</b>：开启后车辆按最短进路驶向最近的可用车站并投入运行；
	 * 时刻表进入"无车发出的时段"时自动回场。依赖 tsc-dispatch（规划）+ mtbridge（执行）。
	 */
	public boolean midLineDepot = false;
	/** 发车提前量（分钟）：距下一班的该时间内开始派车。 */
	public int deployLeadMinutes = 5;
	/** 回场判定（分钟）：距下一班超过该时间（或当日已无班次）即视为"无车发出的时段"，车辆回场。 */
	public int idleReturnMinutes = 30;

	public OptimizeSettings copy() {
		final OptimizeSettings copy = new OptimizeSettings();
		copy.dynamicBlockRelease = dynamicBlockRelease;
		copy.safetyMarginMeters = safetyMarginMeters;
		copy.routeOptimize = routeOptimize;
		copy.pathLookaheadMeters = pathLookaheadMeters;
		copy.departureGuard = departureGuard;
		copy.dwellThresholdSeconds = dwellThresholdSeconds;
		copy.conflictWindowSeconds = conflictWindowSeconds;
		copy.maxHoldSeconds = maxHoldSeconds;
		copy.midLineDepot = midLineDepot;
		copy.deployLeadMinutes = deployLeadMinutes;
		copy.idleReturnMinutes = idleReturnMinutes;
		return copy;
	}

	public long dwellThresholdMillis() {
		return dwellThresholdSeconds * 1000L;
	}

	public long conflictWindowMillis() {
		return conflictWindowSeconds * 1000L;
	}

	public long maxHoldMillis() {
		return maxHoldSeconds * 1000L;
	}

	/** 全局默认值（config/mtrrealrail.properties 中的 optimize_* 项）。 */
	public static OptimizeSettings defaults() {
		return new OptimizeSettings();
	}
}
