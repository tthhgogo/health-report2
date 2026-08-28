package com.example.healthreport.safety;

import com.example.healthreport.constants.AllergenGroup;
import com.example.healthreport.constants.AllergenGroups;
import com.example.healthreport.constants.AllergenKey;
import com.example.healthreport.llm.extraction.AllergenResultStatus;
import com.example.healthreport.llm.extraction.ValidatedExtractionOutput;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 按结果状态过滤过敏条目，并收敛 isFoodBorne 的唯一可信来源。 */
@Component
public class AllergenAdmissionFilter {

	/**
	 * 产品安全策略只保留 POSITIVE/BORDERLINE；NEGATIVE 与 UNKNOWN 都不进入链路。 BORDERLINE
	 * 的准入不表示临床确诊，只用于信息不完整时避免自动推荐潜在风险菜品。
	 * <p>
	 * <b>UNKNOWN 不再单独计数</b>（2026-08-27 计数全部下线）：它不得自动当成阳性，也不得当成阴性，
	 * 与 NEGATIVE 一样静默不进链路。将来要观测它，先把导出口径定清楚，别再加一个只写不读的计数。
	 * </p>
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
