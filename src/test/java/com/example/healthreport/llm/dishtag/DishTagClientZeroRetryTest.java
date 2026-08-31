package com.example.healthreport.llm.dishtag;

import com.example.healthreport.dish.Dish;
import com.example.healthreport.dish.DishIngredient;
import com.example.healthreport.infra.DishTagModelClient;
import com.example.healthreport.infra.RequestTooLargeException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 远程失败时只调用一次，不在客户端或编排层重试。 */
class DishTagClientZeroRetryTest {

	@Test
	void remoteFailureShouldBeWrappedAfterExactlyOneCall() {
		DishTagModelClient modelClient = mock(DishTagModelClient.class);
		when(modelClient.call(anyString(), anyString())).thenThrow(new IllegalStateException("远程不可用"));
		ObjectMapper mapper = new ObjectMapper();
		DishTagClient client = new DishTagClient(modelClient, new DishTagPromptProvider(),
				new DishTagContractValidator(mapper));

		assertThrows(DishTagCallException.class, () -> client.tag(input()));
		verify(modelClient, times(1)).call(anyString(), anyString());
	}

	@Test
	void thinkSegmentShouldBeStrippedBeforeContractValidation() {
		DishTagModelClient modelClient = mock(DishTagModelClient.class);
		// 思考段里放一份【格式完全合法但内容错误】的示例 JSON：
		// 宽松提取会把它当成结果，而它 Schema 全过、没有任何一层会报错。
		String decoyJson = "{\"enumKey\":\"LOW_FAT\",\"neutralDishIds\":[999],"
				+ "\"unknownDishIds\":[],\"hitList\":[]}";
		String realJson = "{\"enumKey\":\"LOW_FAT\",\"neutralDishIds\":[1]," + "\"unknownDishIds\":[],\"hitList\":[]}";
		when(modelClient.call(anyString(), anyString()))
			.thenReturn("<think>\n我先试写一下格式：" + decoyJson + "\n</think>\n\n" + realJson);
		ObjectMapper mapper = new ObjectMapper();
		DishTagClient client = new DishTagClient(modelClient, new DishTagPromptProvider(),
				new DishTagContractValidator(mapper));

		DishTagOutput output = client.tag(input());

		// 取到的必须是 </think> 之后的那份，不是思考里的草稿。
		assertThat(output.getNeutralDishIds()).containsExactly(1L);
	}

	@Test
	void truncatedThinkSegmentShouldRejectTheWholeBatch() {
		DishTagModelClient modelClient = mock(DishTagModelClient.class);
		// max_tokens 用尽，思考没结束就被截断，连 </think> 都没有。
		when(modelClient.call(anyString(), anyString())).thenReturn("<think>\n我先看看这批菜里有没有高脂的");
		ObjectMapper mapper = new ObjectMapper();
		DishTagClient client = new DishTagClient(modelClient, new DishTagPromptProvider(),
				new DishTagContractValidator(mapper));

		assertThrows(DishTagBatchRejectedException.class, () -> client.tag(input()));
	}

	@Test
	void oversizedRequestShouldRejectOnlyTheBatchNotTheWholeJob() {
		DishTagModelClient modelClient = mock(DishTagModelClient.class);
		when(modelClient.call(anyString(), anyString())).thenThrow(new RequestTooLargeException(9_999_999L, 1024));
		ObjectMapper mapper = new ObjectMapper();
		DishTagClient client = new DishTagClient(modelClient, new DishTagPromptProvider(),
				new DishTagContractValidator(mapper));

		// DishTagService 只捕获 DishTagBatchRejectedException 与 DishTagCallException；
		// 裸的 RequestTooLargeException 会中止整个夜间打标任务，而不是只丢这一批。
		assertThrows(DishTagBatchRejectedException.class, () -> client.tag(input()));
	}

	private DishTagInput input() {
		Dish dish = new Dish("company-a", 1L, "测试菜", Collections.singletonList(new DishIngredient("食材", null)));
		return new DishTagInput("LOW_FAT", "低脂", Collections.<String>emptyList(), Collections.<String>emptyList(),
				Collections.<String>emptyList(), Collections.<String>emptyList(), Collections.singletonList(dish));
	}

	private static org.assertj.core.api.ListAssert<Long> assertThat(java.util.List<Long> actual) {
		return org.assertj.core.api.Assertions.assertThat(actual);
	}

}
