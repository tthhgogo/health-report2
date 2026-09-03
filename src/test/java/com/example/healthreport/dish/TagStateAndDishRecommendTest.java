package com.example.healthreport.dish;

import com.example.healthreport.assemble.dishrecommend.DishNameSorter;
import com.example.healthreport.assemble.dishrecommend.DishRecommendAssembler;
import com.example.healthreport.assemble.dishrecommend.DishRecommendInput;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R16、R33：拒绝优先净化正向标签、全量裁决后再排序截断。
 * <p>在线只有 RECOMMEND/REJECT 两个方向（设计方案 §8.9）：五态裁决只存在于凌晨构建，
 * 原 TagStateResolver / DishDisposition 已随之删除。</p>
 */
class TagStateAndDishRecommendTest {

	private final DishRecommendAssembler assembler = new DishRecommendAssembler(new DishNameSorter());

	@Test
	void allergyRejectShouldRemoveEveryPositiveTagAndReason() {
		DishRecommendInput.Match allergy = new DishRecommendInput.Match(true, "虾蟹过敏", null);
		DishRecommendInput.Match nutrition = new DishRecommendInput.Match(false, "补铁", "建议补充铁");

		DishRecommendAssembler.Result result = assembler.assemble(new DishRecommendInput(false, true, Collections
			.singletonList(new DishRecommendInput.Candidate(1L, "菠菜虾仁", Arrays.asList(allergy, nutrition)))));

		// 拒绝优先：进不推荐列表，且不携带任何正向标签或理由。
		assertThat(result.getRecommendList()).isEmpty();
		assertThat(result.getRejectList()).hasSize(1);
		assertThat(result.getRejectList().get(0).getNotRecommendTagList()).containsExactly("虾蟹过敏");
	}

	@Test
	void allCandidatesShouldBeDecidedBeforePinyinSortAndTruncation() {
		List<DishRecommendInput.Candidate> candidateList = new ArrayList<DishRecommendInput.Candidate>();
		candidateList.add(recommended(1L, "白菜"));
		candidateList.add(recommended(2L, "冬瓜"));
		candidateList.add(recommended(3L, "番茄"));
		candidateList.add(new DishRecommendInput.Candidate(4L, "菠菜",
				Arrays.asList(new DishRecommendInput.Match(false, "补铁", "建议补充铁"),
						new DishRecommendInput.Match(true, "过敏", null))));
		candidateList.add(recommended(5L, "油菜"));

		DishRecommendAssembler.Result result = assembler.assemble(new DishRecommendInput(false, true, candidateList));

		// 先全量裁决再截断：菠菜被拒后，前三名推荐是拼音序的白菜、冬瓜、番茄。
		assertThat(result.getRecommendList()).extracting("dishName").containsExactly("白菜", "冬瓜", "番茄");
		assertThat(result.getRejectList()).extracting("dishName").containsExactly("菠菜");
		assertThat(result.getRecommendList()).hasSize(3);
	}

	@Test
	void suppressionShouldRemoveWholeModuleAndNonChineseShouldSortLast() {
		assertThat(assembler
			.assemble(new DishRecommendInput(true, true, Collections.<DishRecommendInput.Candidate>emptyList())))
			.isNull();

		DishRecommendAssembler.Result result = assembler.assemble(
				new DishRecommendInput(false, true, Arrays.asList(recommended(1L, "A套餐"), recommended(2L, "白菜"))));
		assertThat(result.getRecommendList()).extracting("dishName").containsExactly("白菜", "A套餐");
	}

	@Test
	void recommendationWithoutRawTextShouldFailSafe() {
		assertThatThrownBy(() -> new DishRecommendInput.Match(false, "补充", null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private DishRecommendInput.Candidate recommended(long id, String name) {
		return new DishRecommendInput.Candidate(id, name, Collections.singletonList(
				new DishRecommendInput.Match(false, "补充", "建议补充")));
	}

}
