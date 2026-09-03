package com.example.healthreport.llm.dishtag;

import com.example.healthreport.llm.schema.ModelOutputSchemaRegistry;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.healthreport.dish.Dish;
import com.example.healthreport.dish.DishIngredient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** R22：三列表精确覆盖、互斥，以及覆盖问题的 UNKNOWN 修复与 20% 作废上限。 */
class DishTagContractValidatorTest {

	private static final ObjectMapper SCHEMA_OBJECT_MAPPER = new ObjectMapper();

	private final DishTagContractValidator validator = new DishTagContractValidator(SCHEMA_OBJECT_MAPPER,
			new ModelOutputSchemaRegistry(SCHEMA_OBJECT_MAPPER));

	private final java.util.List<Dish> inputDishList = Arrays.asList(dish(1L), dish(2L), dish(3L));

	@Test
	void exactThreeWayCoverageShouldPass() {
		String json = "{\"enumKey\":\"LOW_FAT\",\"neutralDishIds\":[1],"
				+ "\"unknownDishIds\":[2],\"hitList\":[{\"dishId\":3,"
				+ "\"verdict\":\"REJECT\",\"evidenceType\":\"COOKING\","
				+ "\"matchedIngredients\":[],\"reason\":\"明确做法\"}]}";

		assertThat(validator.validate(json, "LOW_FAT", inputDishList).getHitList()).hasSize(1);
	}

	@Test
	void markdownJsonFenceAroundWholeResponseShouldBeRemovedBeforeParsing() {
		String json = "```json\r\n"
				+ "{\"enumKey\":\"LOW_FAT\",\"neutralDishIds\":[1],"
				+ "\"unknownDishIds\":[2],\"hitList\":[{\"dishId\":3,"
				+ "\"verdict\":\"REJECT\",\"evidenceType\":\"COOKING\","
				+ "\"matchedIngredients\":[],\"reason\":\"明确做法\"}]}\r\n```";

		assertThat(validator.validate(json, "LOW_FAT", inputDishList).getHitList()).hasSize(1);
	}

	@Test
	void explanatoryTextOutsideMarkdownFenceShouldStillRejectWholeBatch() {
		String json = "以下是结果：\n```json\n"
				+ "{\"enumKey\":\"LOW_FAT\",\"neutralDishIds\":[1],"
				+ "\"unknownDishIds\":[2],\"hitList\":[{\"dishId\":3,"
				+ "\"verdict\":\"REJECT\",\"evidenceType\":\"COOKING\","
				+ "\"matchedIngredients\":[],\"reason\":\"明确做法\"}]}\n```";

		assertThrows(DishTagBatchRejectedException.class,
				() -> validator.validate(json, "LOW_FAT", inputDishList));
	}

	/**
	 * {@code matchedIngredients} 给成对象数组时，该 hit 被剔除、菜品归入 UNKNOWN，而不是被静默改写成字符串。
	 * <p>UNKNOWN 是安全侧：这道菜不会进任何推荐集合。归入必打日志，不是「悄悄改」。</p>
	 */
	@Test
	void matchedIngredientObjectShouldMoveDishToUnknownInsteadOfBeingSilentlyRewritten() {
		String json = "{\"enumKey\":\"LOW_FAT\",\"neutralDishIds\":[1],"
				+ "\"unknownDishIds\":[2],\"hitList\":[{\"dishId\":3,"
				+ "\"verdict\":\"REJECT\",\"evidenceType\":\"INGREDIENT\","
				+ "\"matchedIngredients\":[{\"name\":\"食材\",\"weightG\":1}],"
				+ "\"reason\":\"食材表明确列出\"}]}";

		DishTagOutput output = validator.validate(json, "LOW_FAT", inputDishList);

		assertThat(output.getHitList()).as("坏 hit 已剔除").isEmpty();
		assertThat(output.getUnknownDishIds()).as("该菜归入 UNKNOWN").containsExactlyInAnyOrder(2L, 3L);
		assertThat(output.getNeutralDishIds()).containsExactly(1L);
	}

	@Test
	void rejectionShouldLogStageAtWarnAndDetailsOnlyAtDebug() {
		// 违规落在 hitList 之外（neutralDishIds 元素类型错）：定位不到「哪道菜」，仍然整批作废。
		String responseMarker = "OBJECT_ARRAY_RESPONSE_MARKER";
		String json = "{\"enumKey\":\"LOW_FAT\",\"neutralDishIds\":[\"" + responseMarker + "\"],"
				+ "\"unknownDishIds\":[2],\"hitList\":[{\"dishId\":3,"
				+ "\"verdict\":\"REJECT\",\"evidenceType\":\"COOKING\","
				+ "\"matchedIngredients\":[],\"reason\":\"明确做法\"}]}";
		Logger logger = (Logger) LoggerFactory.getLogger(DishTagContractValidator.class);
		Level previousLevel = logger.getLevel();
		ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
		appender.start();
		logger.addAppender(appender);
		try {
			logger.setLevel(Level.INFO);
			assertThrows(DishTagBatchRejectedException.class,
					() -> validator.validate(json, "LOW_FAT", inputDishList));

			assertThat(renderedLog(appender))
				.contains("阶段=Schema校验", "enumKey=LOW_FAT", "批次菜品数=3", "Schema违规数=1")
				.doesNotContain(responseMarker);

			appender.list.clear();
			logger.setLevel(Level.DEBUG);
			assertThrows(DishTagBatchRejectedException.class,
					() -> validator.validate(json, "LOW_FAT", inputDishList));

			assertThat(renderedLog(appender))
				.contains("LLM-B Schema违规详情", "LLM-B契约校验失败响应正文", responseMarker);
		}
		finally {
			logger.detachAppender(appender);
			logger.setLevel(previousLevel);
			appender.stop();
		}
	}

	/**
	 * 缺失与跨列表相交：意图不明或缺判定，问题菜归入 UNKNOWN。
	 * <p>三道菜的批次上限是 {@code max(1, floor(3×20%)) = 1} 条，以下每例都只有一个问题菜。</p>
	 */
	@Test
	void missingAndCrossListOverlapShouldMoveDishToUnknown() {
		// 缺失：模型压根没提 dishId=3，等于没判定
		assertRepairedToUnknown("[1]", "[2]", "[]", 3L);
		// 跨列表相交：dishId=1 同时在 neutral 与 unknown，两个判定自相矛盾
		assertRepairedToUnknown("[1]", "[1,2]", hit(3), 1L);
	}

	/** hitList 里同一道菜出现两次也只去重：verdict 只有 REJECT 一个取值，两条结论必然一致。 */
	@Test
	void duplicateWithinHitListShouldOnlyBeDeduplicatedNotDowngraded() {
		String json = "{\"enumKey\":\"LOW_FAT\",\"neutralDishIds\":[1],"
				+ "\"unknownDishIds\":[2],\"hitList\":[{\"dishId\":3,\"verdict\":\"REJECT\","
				+ "\"evidenceType\":\"COOKING\",\"matchedIngredients\":[],\"reason\":\"明确做法\"},"
				+ "{\"dishId\":3,\"verdict\":\"REJECT\",\"evidenceType\":\"COOKING\","
				+ "\"matchedIngredients\":[],\"reason\":\"明确做法\"}]}";

		DishTagOutput output = validator.validate(json, "LOW_FAT", inputDishList);

		assertThat(output.getHitList()).as("去重后只剩一条 REJECT").hasSize(1);
		assertThat(output.getHitList().get(0).getDishId()).isEqualTo(3L);
		assertThat(output.getUnknownDishIds()).as("REJECT 结论不得被降级成 UNKNOWN").containsExactly(2L);
	}

	/**
	 * 同一列表内重复只去重，<b>不改判</b>：模型把同一道菜写了两遍，意图是明确的。
	 * <p>与跨列表相交区别对待——那种是两个判定打架，这种只是抄重了。</p>
	 */
	@Test
	void duplicateWithinOneListShouldOnlyBeDeduplicated() {
		String json = "{\"enumKey\":\"LOW_FAT\",\"neutralDishIds\":[1,1],"
				+ "\"unknownDishIds\":[2],\"hitList\":" + hit(3) + "}";

		DishTagOutput output = validator.validate(json, "LOW_FAT", inputDishList);

		assertThat(output.getNeutralDishIds()).as("去重后仍是 NEUTRAL").containsExactly(1L);
		assertThat(output.getUnknownDishIds()).as("不该被改判").containsExactly(2L);
		assertThat(output.getHitList()).hasSize(1);
	}

	/** 不属于本批的 dishId 直接丢弃——UNKNOWN 也只能装本批的菜。 */
	@Test
	void dishIdOutsideTheBatchShouldBeDiscardedNotMovedToUnknown() {
		String json = "{\"enumKey\":\"LOW_FAT\",\"neutralDishIds\":[1,4],"
				+ "\"unknownDishIds\":[2],\"hitList\":" + hit(3) + "}";

		DishTagOutput output = validator.validate(json, "LOW_FAT", inputDishList);

		assertThat(output.getNeutralDishIds()).as("非本批的 4 已消失").containsExactly(1L);
		assertThat(output.getUnknownDishIds()).containsExactly(2L);
		assertThat(output.getHitList()).hasSize(1);
	}

	/**
	 * 大量「不属于本批且格式不合法」的 hit 必须计入同一预算，不能先被删光再判零问题。
	 *
	 * <p>Schema 阶段无限量剔 hit、覆盖阶段单独算预算时，这类响应会以「零问题」通过——
	 * 而它明显整体跑偏。属于本批的被剔 hit 会以「缺失」的身份计入，不重复计；
	 * 不属于本批的剔完就消失了，必须在这里单独计上。</p>
	 */
	@Test
	void malformedHitsOutsideTheBatchMustCountAgainstTheBudget() {
		StringBuilder hitArray = new StringBuilder("[");
		for (int index = 0; index < 3; index++) {
			// dishId 9001~9003 都不在本批；matchedIngredients 给成对象数组，Schema 必然拒
			hitArray.append(index == 0 ? "" : ",")
					.append("{\"dishId\":").append(9001 + index)
					.append(",\"verdict\":\"REJECT\",\"evidenceType\":\"INGREDIENT\","
							+ "\"matchedIngredients\":[{\"name\":\"食材\"}],\"reason\":\"x\"}");
		}
		hitArray.append("]");
		// 本批三道菜自身覆盖完整，只有那三条非本批坏 hit 是问题；上限是 1 条。
		String json = "{\"enumKey\":\"LOW_FAT\",\"neutralDishIds\":[1,2,3],"
				+ "\"unknownDishIds\":[],\"hitList\":" + hitArray + "}";

		assertThrows(DishTagBatchRejectedException.class,
				() -> validator.validate(json, "LOW_FAT", inputDishList));
	}

	/**
	 * {@code dishId} 缺失或给成字符串的坏 hit，剔掉之后覆盖阶段完全看不见——必须单独计入预算。
	 * <p>不计的话，几十条这种垃圾 hit 会以「零问题」通过，而它明显整体跑偏。</p>
	 */
	@Test
	void malformedHitsWithoutUsableDishIdMustCountAgainstTheBudget() {
		String json = "{\"enumKey\":\"LOW_FAT\",\"neutralDishIds\":[1,2,3],"
				+ "\"unknownDishIds\":[],\"hitList\":["
				// 一条没有 dishId，一条 dishId 给成字符串；两条都归不到任何菜
				+ "{\"verdict\":\"REJECT\",\"evidenceType\":\"COOKING\","
				+ "\"matchedIngredients\":[],\"reason\":\"x\"},"
				+ "{\"dishId\":\"9001\",\"verdict\":\"REJECT\",\"evidenceType\":\"COOKING\","
				+ "\"matchedIngredients\":[],\"reason\":\"x\"}]}";

		assertThrows(DishTagBatchRejectedException.class,
				() -> validator.validate(json, "LOW_FAT", inputDishList));
	}

	/** 问题菜超过 20% 上限时整批作废：大比例出错说明这一批整体跑偏，不是个别抖动。 */
	@Test
	void repairBeyondBudgetShouldRejectWholeBatch() {
		// 三道菜只提了一道，缺 2 道 > 上限 1 条
		assertRejected("[1]", "[]", "[]");
	}

	private void assertRepairedToUnknown(String neutralIds, String unknownIds, String hitList,
			long expectedRepairedDishId) {
		String json = "{\"enumKey\":\"LOW_FAT\",\"neutralDishIds\":" + neutralIds
				+ ",\"unknownDishIds\":" + unknownIds + ",\"hitList\":" + hitList + "}";

		DishTagOutput output = validator.validate(json, "LOW_FAT", inputDishList);

		assertThat(output.getUnknownDishIds()).contains(expectedRepairedDishId);
		java.util.Set<Long> covered = new java.util.HashSet<Long>(output.getNeutralDishIds());
		covered.addAll(output.getUnknownDishIds());
		for (DishTagOutput.Hit item : output.getHitList()) {
			covered.add(item.getDishId());
		}
		assertThat(covered).as("修复后覆盖必须精确成立").containsExactlyInAnyOrder(1L, 2L, 3L);
	}

	/**
	 * 批次输入自带重复菜品 ID 是编排层的 bug，不是模型的问题。 两者结果都是整批作废，但错误类型必须分开——否则排障时会去查模型和提示词， 而真正的缺陷在调用方。
	 */
	@Test
	void duplicateInputDishIdShouldSurfaceAsCallerBugNotModelRejection() {
		List<Dish> duplicatedInputList = Arrays.asList(dish(1L), dish(1L));
		String json = "{\"enumKey\":\"LOW_FAT\",\"neutralDishIds\":[1]," + "\"unknownDishIds\":[],\"hitList\":[]}";

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> validator.validate(json, "LOW_FAT", duplicatedInputList));
		assertThat(exception).isNotInstanceOf(DishTagBatchRejectedException.class);
	}

	private void assertRejected(String neutralIds, String unknownIds, String hitList) {
		String json = "{\"enumKey\":\"LOW_FAT\",\"neutralDishIds\":" + neutralIds + ",\"unknownDishIds\":" + unknownIds
				+ ",\"hitList\":" + hitList + "}";
		assertThrows(DishTagBatchRejectedException.class, () -> validator.validate(json, "LOW_FAT", inputDishList));
	}

	private String hit(long dishId) {
		return "[{\"dishId\":" + dishId + ",\"verdict\":\"REJECT\","
				+ "\"evidenceType\":\"COOKING\",\"matchedIngredients\":[]," + "\"reason\":\"明确做法\"}]";
	}

	private Dish dish(long dishId) {
		return new Dish("company-a", dishId, "测试菜" + dishId, Collections.singletonList(new DishIngredient("食材", null)));
	}

	private String renderedLog(ListAppender<ILoggingEvent> appender) {
		StringBuilder rendered = new StringBuilder();
		for (ILoggingEvent event : appender.list) {
			rendered.append(event.getFormattedMessage()).append('\n');
		}
		return rendered.toString();
	}

}
