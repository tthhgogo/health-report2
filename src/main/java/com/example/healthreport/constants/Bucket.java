package com.example.healthreport.constants;

/**
 * 过敏原词条的展示分桶。只描述展示分组，不代表证据强度。
 */
public enum Bucket {

    /** 该词本身即该过敏原或其直接制品 */
    AVOID,

    /** 该过敏原是其成分之一，但品类名称看不出来 */
    HIDDEN;
}
