package com.example.healthreport.constants;

/**
 * 过敏原枚举。食入性 11 组参与菜品匹配，非食物 5 组只展示。
 * <p>新增任何一组都会改变接口契约（枚举数 16 / LLM-B 维度 20），须按契约升级处理。</p>
 */
public enum AllergenKey {

    /** 虾蟹类 */
    SHRIMP_CRAB,

    /** 鱼类 */
    FISH,

    /** 牛奶及乳制品 */
    MILK,

    /** 鸡蛋 */
    EGG,

    /** 花生 */
    PEANUT,

    /** 大豆 */
    SOY,

    /** 小麦麸质 */
    WHEAT,

    /** 坚果 */
    NUTS,

    /** 芒果 */
    MANGO,

    /** 牛肉 */
    BEEF,

    /** 羊肉 */
    MUTTON,

    /** 尘螨（非食物过敏原，不进菜品链路） */
    DUST_MITE,

    /** 花粉（非食物过敏原，不进菜品链路） */
    POLLEN,

    /** 动物皮屑（非食物过敏原，不进菜品链路） */
    ANIMAL_DANDER,

    /** 霉菌（非食物过敏原，不进菜品链路） */
    MOLD,

    /** 蟑螂（非食物过敏原，不进菜品链路） */
    COCKROACH,

    /** 枚举外的过敏原。按 isFoodBorne 拆两条路径处理 */
    OTHER;
}
