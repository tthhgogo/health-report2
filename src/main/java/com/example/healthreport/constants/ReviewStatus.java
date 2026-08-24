package com.example.healthreport.constants;

/**
 * 医学审核状态。只影响规则是否生效，不影响枚举是否存在。
 */
public enum ReviewStatus {

    /** 未裁决。枚举照常存在，但规则不生效 */
    DRAFT,

    /** 审核通过，规则生效 */
    REVIEWED,

    /** 审核拒绝。不进生产，但保留在负向回归测试中 */
    REJECTED;
}
