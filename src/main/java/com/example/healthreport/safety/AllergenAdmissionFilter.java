package com.example.healthreport.safety;

import com.example.healthreport.constants.AllergenGroup;
import com.example.healthreport.constants.AllergenGroups;
import com.example.healthreport.constants.AllergenKey;
import com.example.healthreport.llm.extraction.AllergenResultStatus;
import com.example.healthreport.llm.extraction.ExtractionValidationCounters;
import com.example.healthreport.llm.extraction.ValidatedExtractionOutput;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 按结果状态过滤过敏条目，并收敛 isFoodBorne 的唯一可信来源。 */
@Component
public class AllergenAdmissionFilter {

	private final ExtractionValidationCounters counters;

	public AllergenAdmissionFilter(ExtractionValidationCounters counters) {
		if (counters == null) {
			throw new IllegalArgumentException("过敏准入计数器不能为空");
		}
		this.counters = counters;
	}

	/**
	 * 产品安全策略只保留 POSITIVE/BORDERLINE；NEGATIVE 静默丢弃，UNKNOWN 只计数。 BORDERLINE
	 * 的准入不表示临床确诊，只用于信息不完整时避免自动推荐潜在风险菜品。
	 */
	public List<ValidatedExtractionOutput.Allergen> filter(
			List<ValidatedExtractionOutput.Allergen> sourceAllergenList) {
		if (sourceAllergenList == null) {
			throw new IllegalArgumentException("过敏条目列表不能为空");
		}
		List<ValidatedExtractionOutput.Allergen> admittedAllergenList = new ArrayList<ValidatedExtractionOutput.Allergen>(
				sourceAllergenList.size());
		for (ValidatedExtractionOutput.Allergen allergen : sourceAllergenList) {
			if (allergen.getResultStatus() == AllergenResultStatus.POSITIVE
					|| allergen.getResultStatus() == AllergenResultStatus.BORDERLINE) {
				admittedAllergenList.add(allergen);
			}
			else if (allergen.getResultStatus() == AllergenResultStatus.UNKNOWN) {
				counters.recordAllergenUnknown();
			}
		}
		return admittedAllergenList;
	}

	/** 正式枚举查常量表；OTHER 是唯一采信模型值的分支。 */
	public boolean resolveFoodBorne(AllergenKey enumKey, boolean modelFoodBorne) {
		if (enumKey == AllergenKey.OTHER) {
			return modelFoodBorne;
		}
		AllergenGroup group = AllergenGroups.ALL.get(enumKey);
		if (group == null) {
			throw new IllegalArgumentException("正式过敏枚举未配置");
		}
		return group.isFoodBorne();
	}

}
