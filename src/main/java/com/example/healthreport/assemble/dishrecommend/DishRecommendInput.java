package com.example.healthreport.assemble.dishrecommend;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模块四组装输入；生产实例由 {@link DishRecommendInputFactory} 完成方向集合的合并。
 *
 * <p>在线只消费 Redis 的 RECOMMEND / REJECT 方向集合（设计方案 §8.9），
 * 每个命中只有方向、标签文案与推荐理由三样东西——五态裁决只存在于凌晨构建阶段。</p>
 */
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

	/** 一道菜及其全部方向命中；必须先完整裁决后才能参与截断。 */
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

	/** 一个方向集合的命中：方向、标签文案，推荐方向另带报告原文理由。 */
	@Getter
	public static final class Match {

		private final boolean reject;

		private final String tagText;

		/** 推荐理由（报告原文）；拒绝方向恒为 null——不推荐菜只输出标签不输出理由。 */
		private final String rawText;

		public Match(boolean reject, String tagText, String rawText) {
			if (tagText == null || tagText.length() == 0) {
				throw new IllegalArgumentException("标签文案不能为空");
			}
			if (!reject && (rawText == null || rawText.length() == 0)) {
				throw new IllegalArgumentException("推荐方向必须携带报告原文");
			}
			this.reject = reject;
			this.tagText = tagText;
			this.rawText = rawText;
		}

	}

}
