package com.example.healthreport.assemble.dietadvice;

import com.example.healthreport.assemble.sort.DisplayOrder;
import com.example.healthreport.constants.DietRequirementKey;
import com.example.healthreport.constants.NutritionKey;
import com.example.healthreport.llm.extraction.ValidatedExtractionOutput;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 从校验结果建立模块三最小输入的边界适配器。
 * <p>适配器只搬运已校验建议及来源，不读取指标列表，也不产生任何建议。</p>
 */
@Service
public class DietAdviceInputFactory {

    /** 报告打印条号前缀，仅在 itemNo 非空时使用。 */
    private static final String ITEM_NUMBER_PREFIX = "第";

    /** 报告打印条号后缀，仅在 itemNo 非空时使用。 */
    private static final String ITEM_NUMBER_SUFFIX = "条";

    private final DisplayOrder displayOrder;

    public DietAdviceInputFactory(DisplayOrder displayOrder) {
        this.displayOrder = displayOrder;
    }

    /**
     * 建立有序、无指标数据的模块三输入。
     */
    public DietAdviceInput create(ValidatedExtractionOutput output, int fileCount) {
        DisplayOrder.DisplayPlan plan = displayOrder.plan(output, fileCount);
        List<DietAdviceInput.AllergenItem> allergenList =
                new ArrayList<DietAdviceInput.AllergenItem>(plan.getAllergenList().size());
        for (ValidatedExtractionOutput.Allergen allergen : plan.getAllergenList()) {
            allergenList.add(new DietAdviceInput.AllergenItem(
                    new DietAdviceInput.StructuredValue<com.example.healthreport.constants.AllergenKey>(
                            allergen.getEnumKey(), output.rawTextList(allergen.getSegmentIdList()),
                            // 过敏原不过高危安全闸（2026-08-26 产品确认），三个字段留空。
                            null, null, null),
                    new DietAdviceInput.Source(plan.groupOf(allergen).getDisplayName()),
                    allergen.isFoodBorne(), allergen.getRawName(), allergen.getRawResult()));
        }

        List<DietAdviceInput.AdviceItem<NutritionKey>> nutritionList =
                new ArrayList<DietAdviceInput.AdviceItem<NutritionKey>>(
                        plan.getNutritionSupplementList().size());
        for (ValidatedExtractionOutput.AdviceItem<NutritionKey> item
                : plan.getNutritionSupplementList()) {
            nutritionList.add(advice(output, plan, item));
        }

        List<DietAdviceInput.AdviceItem<DietRequirementKey>> dietList =
                new ArrayList<DietAdviceInput.AdviceItem<DietRequirementKey>>(
                        plan.getDietRequirementList().size());
        for (ValidatedExtractionOutput.AdviceItem<DietRequirementKey> item
                : plan.getDietRequirementList()) {
            dietList.add(advice(output, plan, item));
        }
        return new DietAdviceInput(allergenList, nutritionList, dietList);
    }

    private <T extends Enum<T>> DietAdviceInput.AdviceItem<T> advice(
            ValidatedExtractionOutput output, DisplayOrder.DisplayPlan plan,
            ValidatedExtractionOutput.AdviceItem<T> item) {
        String sourceLabel = plan.groupOf(item).getDisplayName();
        // sourceOrder 是模型批内排序字段，不是报告打印条号，绝不能用于来源文案。
        if (item.getItemNo() != null) {
            sourceLabel += ITEM_NUMBER_PREFIX + item.getItemNo() + ITEM_NUMBER_SUFFIX;
        }
        return new DietAdviceInput.AdviceItem<T>(
                new DietAdviceInput.StructuredValue<T>(item.getEnumKey(),
                        output.rawTextList(item.getSegmentIdList()), item.getAdviceQuote(),
                        item.getApplicability(), item.getStructuredSafety()),
                new DietAdviceInput.Source(sourceLabel));
    }
}
