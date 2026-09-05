package com.example.healthreport.constants;

/**
 * 分析结果缓存的结构版本，进 Redis key（{@code result:{本值}:{taskId}}）。
 * <p>
 * {@code AnalysisResult}、{@code AnalysisModules}、四个模块 Result 及其嵌套 DTO 发生
 * <b>旧 JSON 无法按新类型读出</b>的结构变更（字段增删、改名、类型变化）时必须 bump 本值；
 * 纯内容值变化（如食材清单增删）不 bump。
 * </p>
 * <p>
 * <b>忘记 bump 的后果</b>：滚动发布窗口内，新 Pod 用严格反序列化读旧 Pod 写入的同 key 旧 JSON，
 * 结果接口抛 500，而不是干净的 404/RESULT_EXPIRED（引导重新分析）。bump 之后新旧版本各写各的 key，
 * 旧 key 随 2 小时 TTL 自然过期，不需要清理脚本。
 * </p>
 * <p>
 * <b>bump 时必须同步</b>：把上一版键格式追加到 {@code TaskResultCache#legacyKeys}——
 * 用户删除要立即清掉该任务的全部历史版本结果，不能等 TTL（删除契约优先于自然过期）。
 * </p>
 */
public final class ResultSchemaVersion {

	/** 2026-09-04 模块三改为三卡片列表 + 四模块字段强类型化后的版本；v1 = 无版本前缀的历史时期。 */
	public static final String VALUE = "v2";

	private ResultSchemaVersion() {
	}

}
