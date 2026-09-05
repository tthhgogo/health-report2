package com.example.healthreport.assemble.dietadvice;

import com.example.healthreport.constants.DisclaimerConstants;
import com.example.healthreport.constants.EmptyStateConstants;
import com.example.healthreport.llm.extraction.DietTagsResult;
import com.example.healthreport.safety.HighRiskAdviceGate;
import com.example.healthreport.support.text.TextNormalizer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/** 模块三三维度卡片分区：内容、来源标注、过敏原红线差集与维度独立性（需求 §7-3 / §7-5）。 */
class DietAdviceAssemblerTest {

    private final DietAdviceAssembler assembler =
            new DietAdviceAssembler(new HighRiskAdviceGate(new TextNormalizer()));

    @Test
    void shouldBuildThreeDimensionCardsWithSource() {
        DietTagsResult dietTags = new DietTagsResult("OK",
                Arrays.asList(tag("NUTRITION", "IRON", "总检结论", 2, "建议补充铁剂")),
                Arrays.asList(
                        tag("ALLERGEN", "SHRIMP_CRAB", "过敏原筛查", null, "虾蟹类 阳性(+)"),
                        tag("DIET", "LOW_FAT", "总检结论", 3, "建议低脂饮食")));

        DietAdviceAssembler.Result result = assembler.assemble(dietTags);

        assertThat(result.getAllergyReminderList()).hasSize(1);
        DietAdviceAssembler.AllergyReminder allergy = result.getAllergyReminderList().get(0);
        assertThat(allergy.getAllergenName()).isEqualTo("虾蟹类");
        assertThat(allergy.getFoodBorne()).isTrue();
        assertThat(allergy.getAvoidFoodList()).contains("虾", "蟹");
        assertThat(allergy.getHiddenFoodList()).contains("虾丸");
        assertThat(allergy.getSource().getDisplayText()).isEqualTo("来源：过敏原筛查–虾蟹类 阳性(+)");

        assertThat(result.getNutritionSupplementList()).hasSize(1);
        DietAdviceAssembler.NutritionSupplement nutrition =
                result.getNutritionSupplementList().get(0);
        assertThat(nutrition.getNutritionName()).isEqualTo("铁");
        assertThat(nutrition.getRecommendFoodList()).contains("猪肝");
        assertThat(nutrition.getIntakeNoteList()).isNotEmpty();
        assertThat(nutrition.getPairingTipList()).isNotEmpty();
        assertThat(nutrition.getSource().getItemNo()).isEqualTo(2);
        assertThat(nutrition.getSource().getDisplayText()).isEqualTo("来源：总检结论–建议补充铁剂");

        assertThat(result.getDietAttentionList()).hasSize(1);
        DietAdviceAssembler.DietAttention diet = result.getDietAttentionList().get(0);
        assertThat(diet.getRequirementName()).isEqualTo("低脂饮食");
        assertThat(diet.getRecommendFoodList()).contains("鸡胸肉");
        assertThat(diet.getAvoidFoodList()).contains("肥肉");
        assertThat(diet.getCookingTipList()).isNotEmpty();

        assertThat(result.getAllergyEmptyState()).isNull();
        assertThat(result.getNutritionEmptyState()).isNull();
        assertThat(result.getDietAttentionEmptyState()).isNull();
        assertThat(result.getDisclaimer()).isEqualTo(DisclaimerConstants.MODULE_THREE);
        assertThat(result.getEntryList()).hasSize(3);
    }

    @Test
    void allergenRedLineShouldRemoveConflictingRecommendFoods() {
        // 牛肉过敏 + 补铁：铁卡片的推荐食材里凡含过敏 matchWord「牛肉」的一律移除，其余保留。
        DietTagsResult dietTags = new DietTagsResult("OK",
                Arrays.asList(tag("NUTRITION", "IRON", "总检结论", null, "建议补充铁剂")),
                Arrays.asList(tag("ALLERGEN", "BEEF", "过敏原筛查", null, "牛肉 IgE 阳性(+)")));

        DietAdviceAssembler.Result result = assembler.assemble(dietTags);

        DietAdviceAssembler.NutritionSupplement nutrition =
                result.getNutritionSupplementList().get(0);
        assertThat(nutrition.getRecommendFoodList()).doesNotContain("瘦牛肉");
        assertThat(nutrition.getRecommendFoodList()).contains("猪肝", "羊肉");
    }

    @Test
    void allergenRedLineShouldAlsoRemoveConflictingNoteAndTipLines() {
        // 牛奶过敏 + 补钙：食材被移除后，摄入量说明「每天300~500ml液态奶」这类说明文案
        // 同样是把过敏原推给用户，含过敏 matchWord 的说明整条移除，不含的保留。
        DietTagsResult dietTags = new DietTagsResult("OK",
                Arrays.asList(tag("NUTRITION", "CALCIUM", "总检结论", null, "建议补钙")),
                Arrays.asList(tag("ALLERGEN", "MILK", "过敏原筛查", null, "牛奶 IgE 阳性(+)")));

        DietAdviceAssembler.Result result = assembler.assemble(dietTags);

        DietAdviceAssembler.NutritionSupplement nutrition =
                result.getNutritionSupplementList().get(0);
        assertThat(nutrition.getRecommendFoodList()).doesNotContain("牛奶", "酸奶", "奶酪");
        for (String intakeNote : nutrition.getIntakeNoteList()) {
            assertThat(intakeNote).as("摄入量说明不得再出现奶制品建议").doesNotContain("奶");
        }
        // 不含过敏词的搭配小贴士保留，卡片不因过滤而整体消失。
        assertThat(nutrition.getPairingTipList()).contains("与维生素D同补有助吸收");
    }

    @Test
    void dimensionsShouldStayIndependentWithoutCrossSubtraction() {
        // §7-5：补铁推荐与低嘌呤忌口各自独立成卡，猪肝不因另一维度存在忌口而被移除。
        DietTagsResult dietTags = new DietTagsResult("OK",
                Arrays.asList(tag("NUTRITION", "IRON", "总检结论", null, "建议补充铁剂")),
                Arrays.asList(tag("DIET", "LOW_PURINE", "总检结论", null, "限制嘌呤摄入")));

        DietAdviceAssembler.Result result = assembler.assemble(dietTags);

        assertThat(result.getNutritionSupplementList().get(0).getRecommendFoodList())
                .contains("猪肝");
        assertThat(result.getDietAttentionList().get(0).getAvoidFoodList()).contains("动物内脏");
    }

    @Test
    void nonFoodAllergenShouldShowNameAndSourceWithoutFoods() {
        DietTagsResult dietTags = new DietTagsResult("OK",
                Collections.<DietTagsResult.DietTag>emptyList(),
                Arrays.asList(tag("ALLERGEN", "DUST_MITE", "过敏原筛查", null, "尘螨 阳性(++)")));

        DietAdviceAssembler.Result result = assembler.assemble(dietTags);

        DietAdviceAssembler.AllergyReminder allergy = result.getAllergyReminderList().get(0);
        assertThat(allergy.getAllergenName()).isEqualTo("尘螨");
        assertThat(allergy.getFoodBorne()).isFalse();
        assertThat(allergy.getAvoidFoodList()).isEmpty();
        assertThat(allergy.getHiddenFoodList()).isEmpty();
        assertThat(allergy.getSource().getQuote()).isEqualTo("尘螨 阳性(++)");
    }

    @Test
    void otherAndGateSuppressedShouldRenderSourceOnlyCards() {
        DietTagsResult dietTags = new DietTagsResult("OK",
                Collections.<DietTagsResult.DietTag>emptyList(),
                Arrays.asList(
                        tag("DIET", "OTHER", "总检结论", null, "建议优质低蛋白饮食"),
                        tag("DIET", "LIGHT_DIET", "总检结论", null, "低碘清淡饮食")));

        DietAdviceAssembler.Result result = assembler.assemble(dietTags);

        assertThat(result.getDietAttentionList()).hasSize(2);
        for (DietAdviceAssembler.DietAttention card : result.getDietAttentionList()) {
            assertThat(card.getRequirementName()).isNull();
            assertThat(card.getRecommendFoodList()).isEmpty();
            assertThat(card.getAvoidFoodList()).isEmpty();
            assertThat(card.getCookingTipList()).isEmpty();
            assertThat(card.getSource().getDisplayText()).startsWith("来源：总检结论–");
        }
        assertThat(result.getEntryList())
                .allMatch(DietAdviceAssembler.Entry::isStructuredOutputSuppressed);
    }

    @Test
    void fullCardShouldReplaceEarlierSourceOnlyCardOfSameEnum() {
        DietTagsResult dietTags = new DietTagsResult("OK",
                Collections.<DietTagsResult.DietTag>emptyList(),
                Arrays.asList(
                        tag("DIET", "LIGHT_DIET", "总检结论", null, "低碘清淡饮食"),
                        tag("DIET", "LIGHT_DIET", "科室意见", null, "清淡饮食")));

        DietAdviceAssembler.Result result = assembler.assemble(dietTags);

        assertThat(result.getDietAttentionList()).hasSize(1);
        DietAdviceAssembler.DietAttention card = result.getDietAttentionList().get(0);
        assertThat(card.getRequirementName()).isEqualTo("清淡饮食");
        assertThat(card.getRecommendFoodList()).isNotEmpty();
    }

    @Test
    void duplicateEnumKeyShouldKeepFirstCardOnly() {
        DietTagsResult dietTags = new DietTagsResult("OK",
                Collections.<DietTagsResult.DietTag>emptyList(),
                Arrays.asList(
                        tag("ALLERGEN", "SHRIMP_CRAB", "过敏原筛查", 1, "虾蟹类 阳性(+)"),
                        tag("ALLERGEN", "SHRIMP_CRAB", "总检结论", 5, "对虾蟹过敏")));

        DietAdviceAssembler.Result result = assembler.assemble(dietTags);

        assertThat(result.getAllergyReminderList()).hasSize(1);
        assertThat(result.getAllergyReminderList().get(0).getSource().getItemNo()).isEqualTo(1);
        assertThat(result.getEntryList()).hasSize(2);
    }

    @Test
    void emptyInputShouldProducePerDimensionEmptyStates() {
        DietTagsResult dietTags = new DietTagsResult("OK",
                Collections.<DietTagsResult.DietTag>emptyList(),
                Collections.<DietTagsResult.DietTag>emptyList());

        DietAdviceAssembler.Result result = assembler.assemble(dietTags);

        assertThat(result.getAllergyReminderList()).isEmpty();
        assertThat(result.getAllergyEmptyState())
                .isEqualTo(EmptyStateConstants.MODULE_THREE_ALLERGY);
        assertThat(result.getNutritionEmptyState())
                .isEqualTo(EmptyStateConstants.MODULE_THREE_NUTRITION);
        assertThat(result.getDietAttentionEmptyState())
                .isEqualTo(EmptyStateConstants.MODULE_THREE_DIET);
    }

    private DietTagsResult.DietTag tag(String dimension, String enumKey, String section,
                                       Integer itemNo, String quote) {
        return new DietTagsResult.DietTag(dimension, enumKey, 1, section, itemNo, quote, quote);
    }
}
