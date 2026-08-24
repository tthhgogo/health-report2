package com.example.healthreport.constants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 过敏原组与词条常量。
 * <p><b>本类是过敏词表的唯一真源</b>，展示（模块三）与 Layer 1 硬匹配共用它，
 * 不另建关键词表。增删词条会改变 Layer 1 的拦截行为，属安全变更，须走医学评审并 bump
 * {@link TagRuleVersion}。</p>
 * <p>当前全部词条 {@code reviewStatus = DRAFT}——枚举照常存在，但规则不生效（见
 * {@link ReviewStatus}）。</p>
 */
public final class AllergenGroups {

    private AllergenGroups() {
    }

    /** 虾蟹类，19 个词条 */
    public static final AllergenGroup SHRIMP_CRAB = new AllergenGroup(
            AllergenKey.SHRIMP_CRAB, "虾蟹类", true,
            Arrays.asList(
                    AllergenWord.of("虾", "虾", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("蟹", "蟹", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("龙虾", "龙虾", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("小龙虾", "小龙虾", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("皮皮虾", "皮皮虾", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("虾仁", "虾仁", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("虾米", "虾米", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("虾皮", "虾皮", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("蟹肉", "蟹肉", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("虾滑", "虾滑", Bucket.HIDDEN, EvidenceLevel.LIKELY, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("虾丸", "虾丸", Bucket.HIDDEN, EvidenceLevel.LIKELY, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("虾饺", "虾饺", Bucket.HIDDEN, EvidenceLevel.LIKELY, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("蟹柳", "蟹柳", Bucket.HIDDEN, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY, ReviewStatus.DRAFT),   // ⚠️ 市售仿蟹肉棒主料是鱼糜，多数不含真蟹；主风险应在 FISH。待医务裁决
                    AllergenWord.of("蟹棒", "蟹棒", Bucket.HIDDEN, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY, ReviewStatus.DRAFT),   // ⚠️ 市售仿蟹肉棒主料是鱼糜，多数不含真蟹；主风险应在 FISH。待医务裁决
                    AllergenWord.of("虾酱", "虾酱", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("xo酱", "XO酱", Bucket.HIDDEN, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY, ReviewStatus.DRAFT),   // 配方差异大，名称不保证含有；只作为线索进 LLM-B 提示词
                    AllergenWord.of("海鲜酱", "海鲜酱", Bucket.HIDDEN, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY, ReviewStatus.DRAFT),   // ⚠️ 传统 hoisin 是发酵豆酱，可能根本不含海鲜；真实风险是 SOY/WHEAT。待医务裁决是否移组
                    AllergenWord.of("蟹粉", "蟹粉", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("虾油", "虾油", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT)
            ));

    /** 鱼类，6 个词条 */
    public static final AllergenGroup FISH = new AllergenGroup(
            AllergenKey.FISH, "鱼类", true,
            Arrays.asList(
                    AllergenWord.of("鱼", "鱼", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("鱼肉", "鱼肉", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("鱼丸", "鱼丸", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("鱼露", "鱼露", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("鱼松", "鱼松", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("鱼籽", "鱼籽", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT)
            ));

    /** 牛奶及乳制品，12 个词条 */
    public static final AllergenGroup MILK = new AllergenGroup(
            AllergenKey.MILK, "牛奶及乳制品", true,
            Arrays.asList(
                    AllergenWord.of("牛奶", "牛奶", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("奶油", "奶油", Bucket.AVOID, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY, ReviewStatus.DRAFT),   // 配方差异大，名称不保证含有；只作为线索进 LLM-B 提示词
                    AllergenWord.of("黄油", "黄油", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("芝士", "芝士", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("奶酪", "奶酪", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("淡奶", "淡奶", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("炼乳", "炼乳", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("酸奶", "酸奶", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("乳酪", "乳酪", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("奶昔", "奶昔", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("布丁", "布丁", Bucket.HIDDEN, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY, ReviewStatus.DRAFT),   // 配方差异大，名称不保证含有；只作为线索进 LLM-B 提示词
                    AllergenWord.of("奶盖", "奶盖", Bucket.HIDDEN, EvidenceLevel.LIKELY, MatchMode.SUBSTRING, ReviewStatus.DRAFT)
            ));

    /** 鸡蛋，8 个词条 */
    public static final AllergenGroup EGG = new AllergenGroup(
            AllergenKey.EGG, "鸡蛋", true,
            Arrays.asList(
                    AllergenWord.of("鸡蛋", "鸡蛋", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("蛋液", "蛋液", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("蛋清", "蛋清", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("蛋黄", "蛋黄", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("滑蛋", "滑蛋", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("蛋饺", "蛋饺", Bucket.HIDDEN, EvidenceLevel.LIKELY, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("蛋黄酱", "蛋黄酱", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("蛋挞", "蛋挞", Bucket.HIDDEN, EvidenceLevel.LIKELY, MatchMode.SUBSTRING, ReviewStatus.DRAFT)
            ));

    /** 花生，4 个词条 */
    public static final AllergenGroup PEANUT = new AllergenGroup(
            AllergenKey.PEANUT, "花生", true,
            Arrays.asList(
                    AllergenWord.of("花生", "花生", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("花生米", "花生米", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("花生酱", "花生酱", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("花生油", "花生油", Bucket.HIDDEN, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY, ReviewStatus.DRAFT)   // 配方差异大，名称不保证含有；只作为线索进 LLM-B 提示词
            ));

    /** 大豆，6 个词条 */
    public static final AllergenGroup SOY = new AllergenGroup(
            AllergenKey.SOY, "大豆", true,
            Arrays.asList(
                    AllergenWord.of("大豆", "大豆", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("黄豆", "黄豆", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("豆浆", "豆浆", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("豆腐", "豆腐", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("豆皮", "豆皮", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("腐竹", "腐竹", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT)
            ));

    /** 小麦麸质，5 个词条 */
    public static final AllergenGroup WHEAT = new AllergenGroup(
            AllergenKey.WHEAT, "小麦麸质", true,
            Arrays.asList(
                    AllergenWord.of("小麦", "小麦", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("面粉", "面粉", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("面包", "面包", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("面条", "面条", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("麸质", "麸质", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT)
            ));

    /** 坚果，6 个词条 */
    public static final AllergenGroup NUTS = new AllergenGroup(
            AllergenKey.NUTS, "坚果", true,
            Arrays.asList(
                    AllergenWord.of("坚果", "坚果", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("核桃", "核桃", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("腰果", "腰果", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("杏仁", "杏仁", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("榛子", "榛子", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("开心果", "开心果", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT)
            ));

    /** 芒果，1 个词条 */
    public static final AllergenGroup MANGO = new AllergenGroup(
            AllergenKey.MANGO, "芒果", true,
            Arrays.asList(
                    AllergenWord.of("芒果", "芒果", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT)
            ));

    /** 牛肉，3 个词条 */
    public static final AllergenGroup BEEF = new AllergenGroup(
            AllergenKey.BEEF, "牛肉", true,
            Arrays.asList(
                    AllergenWord.of("牛肉", "牛肉", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("牛腩", "牛腩", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("牛排", "牛排", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT)
            ));

    /** 羊肉，3 个词条 */
    public static final AllergenGroup MUTTON = new AllergenGroup(
            AllergenKey.MUTTON, "羊肉", true,
            Arrays.asList(
                    AllergenWord.of("羊肉", "羊肉", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("羊排", "羊排", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT),
                    AllergenWord.of("羊蝎子", "羊蝎子", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.DRAFT)
            ));

    /** 尘螨（非食物过敏原），0 个词条 */
    public static final AllergenGroup DUST_MITE = new AllergenGroup(
            AllergenKey.DUST_MITE, "尘螨", false,
            new ArrayList<AllergenWord>(0));

    /** 花粉（非食物过敏原），0 个词条 */
    public static final AllergenGroup POLLEN = new AllergenGroup(
            AllergenKey.POLLEN, "花粉", false,
            new ArrayList<AllergenWord>(0));

    /** 动物皮屑（非食物过敏原），0 个词条 */
    public static final AllergenGroup ANIMAL_DANDER = new AllergenGroup(
            AllergenKey.ANIMAL_DANDER, "动物皮屑", false,
            new ArrayList<AllergenWord>(0));

    /** 霉菌（非食物过敏原），0 个词条 */
    public static final AllergenGroup MOLD = new AllergenGroup(
            AllergenKey.MOLD, "霉菌", false,
            new ArrayList<AllergenWord>(0));

    /** 蟑螂（非食物过敏原），0 个词条 */
    public static final AllergenGroup COCKROACH = new AllergenGroup(
            AllergenKey.COCKROACH, "蟑螂", false,
            new ArrayList<AllergenWord>(0));

    /** 全部组，按声明顺序。 */
    public static final Map<AllergenKey, AllergenGroup> ALL;

    static {
        Map<AllergenKey, AllergenGroup> map = new EnumMap<>(AllergenKey.class);
        map.put(AllergenKey.SHRIMP_CRAB, SHRIMP_CRAB);
        map.put(AllergenKey.FISH, FISH);
        map.put(AllergenKey.MILK, MILK);
        map.put(AllergenKey.EGG, EGG);
        map.put(AllergenKey.PEANUT, PEANUT);
        map.put(AllergenKey.SOY, SOY);
        map.put(AllergenKey.WHEAT, WHEAT);
        map.put(AllergenKey.NUTS, NUTS);
        map.put(AllergenKey.MANGO, MANGO);
        map.put(AllergenKey.BEEF, BEEF);
        map.put(AllergenKey.MUTTON, MUTTON);
        map.put(AllergenKey.DUST_MITE, DUST_MITE);
        map.put(AllergenKey.POLLEN, POLLEN);
        map.put(AllergenKey.ANIMAL_DANDER, ANIMAL_DANDER);
        map.put(AllergenKey.MOLD, MOLD);
        map.put(AllergenKey.COCKROACH, COCKROACH);
        ALL = Collections.unmodifiableMap(map);
    }

    /** 参与菜品匹配的食入性组。非食物过敏原只展示，不进菜品链路。 */
    public static List<AllergenGroup> foodBorneGroups() {
        List<AllergenGroup> resultList = new ArrayList<>(ALL.size());
        for (AllergenGroup group : ALL.values()) {
            if (group.isFoodBorne()) {
                resultList.add(group);
            }
        }
        return resultList;
    }
}
