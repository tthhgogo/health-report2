package com.example.healthreport.assemble.dishrecommend;

import com.example.healthreport.dish.TagState;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 模块四组装输入；生产实例由 {@link DishRecommendInputFactory} 完成三类标签事实的合并。 */
@Getter
public final class DishRecommendInput {

	private final boolean suppressDishRecommend;

	private final boolean formalAdvicePresent;

	private final List<Candidate> candidateList;

	/** 创建模块四输入；抑制开关为真时组装器不读取候选内容。 */
	public DishRecommendInput(boolean suppressDishRecommend, boolean formalAdvicePresent,
			List<Candidate> candidateList) {
		if (candidateList == null) {
			throw new IllegalArgumentException("候选菜品不能为空");
		}
		this.suppressDishRecommend = suppressDishRecommend;
		this.formalAdvicePresent = formalAdvicePresent;
		this.candidateList = Collections.unmodifiableList(new ArrayList<Candidate>(candidateList));
	}

	/** 一道菜及其全部维度事实；必须先完整裁决后才能参与截断。 */
	@Getter
	public static final class Candidate {

		private final long dishId;

		private final String dishName;

		private final List<Match> matchList;

		public Candidate(long dishId, String dishName, List<Match> matchList) {
			if (dishId <= 0L || dishName == null || dishName.length() == 0 || matchList == null) {
				throw new IllegalArgumentException("菜品候选字段不能为空");
			}
			this.dishId = dishId;
			this.dishName = dishName;
			this.matchList = Collections.unmodifiableList(new ArrayList<Match>(matchList));
		}

	}

	/** 一个维度用于裁决和展示的确定性事实。 */
	@Getter
	public static final class Match {

		private final TagState state;

		private final boolean rejectCapable;

		private final boolean allergy;

		private final TagType tagType;

		private final String tagText;

		private final String rawText;

		public Match(TagState state, boolean rejectCapable, boolean allergy, TagType tagType, String tagText,
				String rawText) {
			if (state == null || tagType == null || tagText == null) {
				throw new IllegalArgumentException("维度匹配字段不能为空");
			}
			if (state == TagState.RECOMMEND && (rawText == null || rawText.length() == 0)) {
				throw new IllegalArgumentException("推荐维度必须携带报告原文");
			}
			this.state = state;
			this.rejectCapable = rejectCapable;
			this.allergy = allergy;
			this.tagType = tagType;
			this.tagText = tagText;
			this.rawText = rawText;
		}

	}

	/** 模块四标签类型。 */
	public enum TagType {

		/** 营养补充正面标签。 */
		NUTRITION,

		/** 过敏拒绝标签。 */
		ALLERGY,

		/** 饮食注意拒绝标签。 */
		DIET_AVOID,

		/** 饮食注意正面标签，由凌晨 Java 主料确证结果产出。 */
		DIET_OK

	}

}
