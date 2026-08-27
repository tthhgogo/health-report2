package com.example.healthreport.constants;

/**
 * 证据等级。决定该词条是否进入 Layer 1 的 Java 硬匹配。
 */
public enum EvidenceLevel {

    /** 该词本身即该过敏原或其直接制品，如虾仁、芝麻、牛肉。进 Java 硬匹配 */
    DIRECT,

    /** 绝大多数配方含有但存在例外，如虾丸、蛋挞。进 Java 硬匹配 */
    LIKELY,

    /** 配方差异大、名称不能保证含有，如 XO 酱、高汤。只作线索进 LLM-B 提示词，不做 Java 匹配 */
    POSSIBLE;
}
