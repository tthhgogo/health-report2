package com.example.healthreport.constants;

import lombok.Getter;

/**
 * 过敏原词条。
 * <p>匹配用 {@code matchWord}（宽松），展示用 {@code displayName}（已审核）——
 * 永远不要把原始匹配词直接渲染给用户。</p>
 */
@Getter
public final class AllergenWord {

    /** 匹配用的词，规范化后参与子串比对 */
    private final String matchWord;
    /** 展示给用户的名称 */
    private final String displayName;
    /** 展示分桶，只影响页面分组，不代表证据强度 */
    private final Bucket bucket;
    /** 证据等级，决定是否进 Layer 1 硬匹配 */
    private final EvidenceLevel evidenceLevel;
    /** 匹配方式 */
    private final MatchMode matchMode;
    /** 医学审核状态 */
    private final ReviewStatus reviewStatus;

    private AllergenWord(String matchWord, String displayName, Bucket bucket,
                         EvidenceLevel evidenceLevel, MatchMode matchMode, ReviewStatus reviewStatus) {
        this.matchWord = matchWord;
        this.displayName = displayName;
        this.bucket = bucket;
        this.evidenceLevel = evidenceLevel;
        this.matchMode = matchMode;
        this.reviewStatus = reviewStatus;
    }

    /**
     * 构造一个词条。
     * <p>合法组合由单元测试强制：{@code POSSIBLE} 只能配 {@code MODEL_ONLY}，
     * 否则会把「配方不保证含有」的词做成硬拒绝。</p>
     */
    public static AllergenWord of(String matchWord, String displayName, Bucket bucket,
                                  EvidenceLevel evidenceLevel, MatchMode matchMode,
                                  ReviewStatus reviewStatus) {
        return new AllergenWord(matchWord, displayName, bucket, evidenceLevel, matchMode, reviewStatus);
    }

    /** 该词条是否参与 Layer 1 的 Java 硬匹配。审核未通过或 MODEL_ONLY 的一律不参与。 */
    public boolean isHardMatchable() {
        return this.reviewStatus == ReviewStatus.REVIEWED && this.matchMode == MatchMode.SUBSTRING;
    }
}
