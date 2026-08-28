package com.example.healthreport.parse.segment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 经真实污染样本确认的 CJK 部首补充区规范化表。
 * <p>不得按 Unicode 块批量推导；新增映射必须同时提供原始样本和回归测试。</p>
 */
public final class RadicalNormalizeMap {

    private static final Map<Integer, String> MAPPING_MAP;

    static {
        Map<Integer, String> mappingMap = new LinkedHashMap<Integer, String>(20);
        // 实际样本中确认的污染：CJK RADICAL JADE 被用在“王”的字形位置。
        mappingMap.put(0x2EA9, "王");

        // 以下为简化部首形，其目标字本身就是独立汉字，映射无歧义；
        // 逐条核对过码位、Unicode 名称与所属区块，且 NFKC 对它们均无兼容分解。
        // 【只收这一类】：形近但语义不等的部首（⺘ 提手旁 ≠ 手、⺖ 竖心旁 ≠ 心、
        // ⺙ 反文旁 ≠ 攵）一律不收——把部首替换成整字会改变文本含义，
        // 未收录时保留原字符并计入 residualNonStandardCount 才是安全方向。
        mappingMap.put(0x2EC4, "西");   // CJK RADICAL WEST TWO
        mappingMap.put(0x2EC5, "见");   // CJK RADICAL C-SIMPLIFIED SEE
        mappingMap.put(0x2EC6, "角");   // CJK RADICAL SIMPLIFIED HORN
        mappingMap.put(0x2EC9, "贝");   // CJK RADICAL C-SIMPLIFIED SHELL
        mappingMap.put(0x2ECB, "车");   // CJK RADICAL C-SIMPLIFIED CART
        mappingMap.put(0x2ED3, "长");   // CJK RADICAL C-SIMPLIFIED LONG
        mappingMap.put(0x2ED4, "门");   // CJK RADICAL C-SIMPLIFIED GATE
        mappingMap.put(0x2ED8, "青");   // CJK RADICAL BLUE
        mappingMap.put(0x2ED9, "韦");   // CJK RADICAL C-SIMPLIFIED TANNED LEATHER
        mappingMap.put(0x2EDB, "风");   // CJK RADICAL C-SIMPLIFIED WIND
        mappingMap.put(0x2EDD, "食");   // CJK RADICAL EAT ONE
        mappingMap.put(0x2EE2, "马");   // CJK RADICAL C-SIMPLIFIED HORSE
        mappingMap.put(0x2EE3, "骨");   // CJK RADICAL BONE
        mappingMap.put(0x2EE5, "鱼");   // CJK RADICAL C-SIMPLIFIED FISH
        mappingMap.put(0x2EE6, "鸟");   // CJK RADICAL C-SIMPLIFIED BIRD
        mappingMap.put(0x2EE9, "黄");   // CJK RADICAL SIMPLIFIED YELLOW
        mappingMap.put(0x2EEC, "齐");   // CJK RADICAL C-SIMPLIFIED EVEN
        mappingMap.put(0x2EEE, "齿");   // CJK RADICAL C-SIMPLIFIED TOOTH
        mappingMap.put(0x2EF0, "龙");   // CJK RADICAL C-SIMPLIFIED DRAGON
        MAPPING_MAP = Collections.unmodifiableMap(mappingMap);
    }

    private RadicalNormalizeMap() {
    }

    /** 返回已确认的替换字符；未收录时返回 null 并由规范化入口保留原字符、只累计数量。 */
    public static String replacement(int codePoint) {
        return MAPPING_MAP.get(codePoint);
    }

}
