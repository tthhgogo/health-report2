package com.example.healthreport.dish;

import com.example.healthreport.assemble.dishrecommend.DishRecommendAssembler;
import com.example.healthreport.assemble.dishrecommend.DishRecommendInput;
import com.example.healthreport.assemble.sort.DisplayOrder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R13、R16、R33：五态门槛、过敏标签净化和全量裁决后截断。 */
class TagStateAndDishRecommendTest {

    private final TagStateResolver resolver = new TagStateResolver();
    private final DishRecommendAssembler assembler = new DishRecommendAssembler(resolver, new DisplayOrder());

    @Test
    void missingRejectCapableDimensionShouldHideEvenWithNutritionRecommendation() {
        List<TagStateResolver.Fact> factList = Arrays.asList(
                new TagStateResolver.Fact(TagState.TAG_MISSING, true, true),
                new TagStateResolver.Fact(TagState.RECOMMEND, false, false));

        assertThat(resolver.resolve(factList)).isEqualTo(DishDisposition.HIDDEN);
    }

    @Test
    void allergyRejectShouldRemoveEveryPositiveTagAndReason() {
        Dish dish = dish(1L, "菠菜虾仁");
        DishRecommendInput.Match allergy = match(TagState.REJECT, true, true,
                DishRecommendInput.TagType.ALLERGY, "虾蟹过敏", Collections.<String>emptyList(), null);
        DishRecommendInput.Match nutrition = match(TagState.RECOMMEND, false, false,
                DishRecommendInput.TagType.NUTRITION, "补铁", Collections.singletonList("菠菜"),
                "建议补充铁");

        DishRecommendAssembler.Result result = assembler.assemble(new DishRecommendInput(false, true,
                Collections.singletonList(new DishRecommendInput.Candidate(dish,
                        Arrays.asList(allergy, nutrition)))));

        assertThat(result.getRejectList()).hasSize(1);
        assertThat(result.getRejectList().get(0).getTagList()).extracting("text")
                .containsExactly("虾蟹过敏");
        assertThat(result.getRejectList().get(0).getSupplementalTagList()).isEmpty();
        assertThat(result.getRejectList().get(0).getReasonList()).isEmpty();
    }

    @Test
    void allCandidatesShouldBeDecidedBeforePinyinSortAndTruncation() {
        List<DishRecommendInput.Candidate> candidateList = new ArrayList<DishRecommendInput.Candidate>();
        candidateList.add(recommended(1L, "白菜"));
        candidateList.add(recommended(2L, "冬瓜"));
        candidateList.add(recommended(3L, "番茄"));
        candidateList.add(new DishRecommendInput.Candidate(dish(4L, "菠菜"), Arrays.asList(
                match(TagState.RECOMMEND, false, false, DishRecommendInput.TagType.NUTRITION,
                        "补铁", Collections.singletonList("菠菜"), "建议补充铁"),
                match(TagState.REJECT, true, true, DishRecommendInput.TagType.ALLERGY,
                        "过敏", Collections.<String>emptyList(), null))));
        candidateList.add(recommended(5L, "油菜"));

        DishRecommendAssembler.Result result = assembler.assemble(
                new DishRecommendInput(false, true, candidateList));

        assertThat(result.getRecommendList()).extracting("dishName")
                .containsExactly("白菜", "冬瓜", "番茄");
        assertThat(result.getRejectList()).extracting("dishName").containsExactly("菠菜");
        assertThat(result.getRecommendList()).hasSize(3);
    }

    @Test
    void suppressionShouldRemoveWholeModuleAndNonChineseShouldSortLast() {
        assertThat(assembler.assemble(new DishRecommendInput(true, true,
                Collections.<DishRecommendInput.Candidate>emptyList()))).isNull();

        DishRecommendAssembler.Result result = assembler.assemble(new DishRecommendInput(false, true,
                Arrays.asList(recommended(1L, "A套餐"), recommended(2L, "白菜"))));
        assertThat(result.getRecommendList()).extracting("dishName")
                .containsExactly("白菜", "A套餐");
    }

    @Test
    void recommendationWithoutEvidenceOrRawTextShouldFailSafe() {
        assertThatThrownBy(() -> new DishRecommendInput.Match(TagState.RECOMMEND, false, false,
                DishRecommendInput.TagType.NUTRITION, "补充", Collections.<String>emptyList(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private DishRecommendInput.Candidate recommended(long id, String name) {
        return new DishRecommendInput.Candidate(dish(id, name), Collections.singletonList(
                match(TagState.RECOMMEND, false, false, DishRecommendInput.TagType.NUTRITION,
                        "补充", Collections.singletonList("食材"), "建议补充")));
    }

    private DishRecommendInput.Match match(TagState state, boolean rejectCapable, boolean allergy,
                                     DishRecommendInput.TagType tagType, String text,
                                     List<String> ingredientList, String rawText) {
        return new DishRecommendInput.Match(state, rejectCapable, allergy, tagType, text,
                ingredientList, rawText);
    }

    private Dish dish(long id, String name) {
        return new Dish(id, name, Collections.singletonList(new DishIngredient("食材", null)));
    }
}
