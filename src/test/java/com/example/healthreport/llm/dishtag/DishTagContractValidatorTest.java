package com.example.healthreport.llm.dishtag;

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

/** R22：三列表精确覆盖、互斥与整批作废。 */
class DishTagContractValidatorTest {

	private final DishTagContractValidator validator = new DishTagContractValidator(new ObjectMapper());

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

	@Test
	void matchedIngredientObjectShouldRejectInsteadOfBeingSilentlyRewritten() {
		String json = "{\"enumKey\":\"LOW_FAT\",\"neutralDishIds\":[1],"
				+ "\"unknownDishIds\":[2],\"hitList\":[{\"dishId\":3,"
				+ "\"verdict\":\"REJECT\",\"evidenceType\":\"INGREDIENT\","
				+ "\"matchedIngredients\":[{\"name\":\"食材\",\"weightG\":1}],"
				+ "\"reason\":\"食材表明确列出\"}]}";

		assertThrows(DishTagBatchRejectedException.class,
				() -> validator.validate(json, "LOW_FAT", inputDishList));
	}

	@Test
	void rejectionShouldLogStageAtWarnAndDetailsOnlyAtDebug() {
		String responseMarker = "OBJECT_ARRAY_RESPONSE_MARKER";
		String json = "{\"enumKey\":\"LOW_FAT\",\"neutralDishIds\":[1],"
				+ "\"unknownDishIds\":[2],\"hitList\":[{\"dishId\":3,"
				+ "\"verdict\":\"REJECT\",\"evidenceType\":\"INGREDIENT\","
				+ "\"matchedIngredients\":[{\"name\":\"" + responseMarker + "\",\"weightG\":1}],"
				+ "\"reason\":\"食材表明确列出\"}]}";
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

	@Test
	void missingExtraOverlapAndDuplicateShouldRejectWholeBatch() {
		assertRejected("[1]", "[2]", "[]");
		assertRejected("[1,4]", "[2]", hit(3));
		assertRejected("[1]", "[1,2]", hit(3));
		assertRejected("[1,1]", "[2]", hit(3));
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
