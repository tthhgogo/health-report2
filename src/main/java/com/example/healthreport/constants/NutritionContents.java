package com.example.healthreport.constants;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 营养补充内容常量。
 * <p><b>本维度不调模型</b>：Java 用「菜品主料 ∩ recommendableFoodList」直接判定（§9.2）。</p>
 * <p>当前全部 {@code reviewStatus = DRAFT}，规则不生效。医务审核并逐条填写来源后才可上线。</p>
 */
public final class NutritionContents {

    private NutritionContents() {
    }

    /** 补充IRON。禁忌见 contraindication。 */
    public static final NutritionRule IRON = new NutritionRule(
            NutritionKey.IRON,
            Arrays.asList(
                        "猪肝",
                        "鸡肝",
                        "鸭血",
                        "猪血",
                        "瘦牛肉",
                        "羊肉"),
            Arrays.asList(
                        "菠菜",
                        "黑木耳",
                        "红枣"),
            Arrays.asList(
                        "动物内脏不宜多吃，频率与单次量待医务按适用人群确定（P1-1：卫健委解读口径约每月2~3次，草案原写每周1~2次偏高）"),
            Arrays.asList(
                        "与富含维生素C的蔬果同食可促进铁吸收",
                        "餐前后1小时避免浓茶、咖啡"),
            "血色病、铁过载者不适用", ReviewStatus.DRAFT);

    /** 补充CALCIUM。禁忌见 contraindication。 */
    public static final NutritionRule CALCIUM = new NutritionRule(
            NutritionKey.CALCIUM,
            Arrays.asList(
                        "牛奶",
                        "酸奶",
                        "奶酪",
                        "石膏豆腐"),
            Arrays.asList(
                        "小油菜",
                        "芥蓝",
                        "虾皮",
                        "北豆腐",
                        "南豆腐"),
            Arrays.asList(
                        "每天300~500ml液态奶或相当量奶制品（P1-2 待医务确认上限）；奶酪等固体奶制品不能按毫升直接相加"),
            Arrays.asList(
                        "与维生素D同补有助吸收",
                        "草酸高的蔬菜建议焯水后再与钙源同食"),
            "高钙血症、部分肾结石患者需遵医嘱", ReviewStatus.DRAFT);

    /** 补充PROTEIN。禁忌见 contraindication。 */
    public static final NutritionRule PROTEIN = new NutritionRule(
            NutritionKey.PROTEIN,
            Arrays.asList(
                        "鸡胸肉",
                        "鸡腿肉",
                        "瘦猪肉",
                        "瘦牛肉",
                        "鱼肉",
                        "虾仁",
                        "鸡蛋",
                        "北豆腐",
                        "南豆腐",
                        "黄豆",
                        "牛奶"),
            Collections.<String>emptyList(),
            Arrays.asList(
                        "一般健康成人约1.0g/kg/天；老年人可增至约1.2g/kg/天（P1-3 需拆分适用人群）",
                        "均匀分配到三餐"),
            Arrays.asList(
                        "动物蛋白与植物蛋白搭配，氨基酸互补"),
            "慢性肾病患者按分期与是否透析个体化，须遵医嘱，不适用本条", ReviewStatus.DRAFT);

    /** 补充VITAMIN_D。禁忌见 contraindication。 */
    public static final NutritionRule VITAMIN_D = new NutritionRule(
            NutritionKey.VITAMIN_D,
            Arrays.asList(
                        "三文鱼",
                        "鳟鱼",
                        "金枪鱼",
                        "鲭鱼"),
            Arrays.asList(
                        "蛋黄",
                        "经紫外照射处理的蘑菇"),
            Arrays.asList(
                        "富脂鱼类每周2次作为平衡膳食频次；不作为纠正已确诊缺乏的剂量依据"),
            Arrays.asList(
                        "脂溶性维生素，随餐食用吸收更好",
                        "配合适度日晒"),
            "已确诊维生素D缺乏者，食物摄入通常不足以纠正，需遵医嘱补充", ReviewStatus.DRAFT);

    /** 补充VITAMIN_B12。禁忌见 contraindication。 */
    public static final NutritionRule VITAMIN_B12 = new NutritionRule(
            NutritionKey.VITAMIN_B12,
            Arrays.asList(
                        "猪肝",
                        "鸡肝",
                        "瘦牛肉",
                        "鱼肉",
                        "鸡蛋",
                        "牛奶"),
            Collections.<String>emptyList(),
            Arrays.asList(
                        "每天保证一份动物性食物"),
            Arrays.asList(
                        "天然来源主要为动物性食物；强化食品与补充剂也是来源，长期素食者需特别关注"),
            "缺乏可能由吸收障碍造成（恶性贫血、胃肠手术后、长期服用某些药物），此时增加膳食摄入不足以纠正，需就医", ReviewStatus.DRAFT);

    /** 补充FOLATE。禁忌见 contraindication。 */
    public static final NutritionRule FOLATE = new NutritionRule(
            NutritionKey.FOLATE,
            Arrays.asList(
                        "菠菜",
                        "油菜",
                        "芦笋",
                        "西兰花",
                        "猪肝",
                        "黄豆",
                        "绿豆"),
            Collections.<String>emptyList(),
            Arrays.asList(
                        "摄入量待营养师按现行膳食指南填写"),
            Arrays.asList(
                        "叶酸不耐热易溶于水，建议急火快炒或短时焯烫，避免长时间水煮"),
            "备孕与孕期人群需按医嘱补充，膳食不能替代", ReviewStatus.DRAFT);

    /** 补充DIETARY_FIBER。禁忌见 contraindication。 */
    public static final NutritionRule DIETARY_FIBER = new NutritionRule(
            NutritionKey.DIETARY_FIBER,
            Arrays.asList(
                        "燕麦",
                        "糙米",
                        "玉米",
                        "红薯",
                        "芹菜",
                        "西兰花",
                        "黑木耳",
                        "白菜"),
            Collections.<String>emptyList(),
            Arrays.asList(
                        "全谷物和杂豆占主食的1/3；总量待营养师按指南填写"),
            Arrays.asList(
                        "如无液体摄入限制，增加纤维的同时适量增加饮水"),
            "心衰、肾病等需限液者；肠梗阻、术后需少渣饮食者不适用", ReviewStatus.DRAFT);

    /** 补充ZINC。禁忌见 contraindication。 */
    public static final NutritionRule ZINC = new NutritionRule(
            NutritionKey.ZINC,
            Arrays.asList(
                        "瘦牛肉",
                        "猪肝",
                        "南瓜子",
                        "鸡蛋",
                        "黄豆"),
            Arrays.asList(
                        "牡蛎"),
            Arrays.asList(
                        "红肉每天50~75g（生重）"),
            Arrays.asList(
                        "较高剂量的铁补充剂与锌同时服用可能降低锌吸收，建议错开时间；膳食中的铁钙一般无需刻意错开"),
            "牡蛎属软体贝类，与 MOLLUSK 组的落地状态相关", ReviewStatus.DRAFT);

    /** 补充POTASSIUM。禁忌见 contraindication。 */
    public static final NutritionRule POTASSIUM = new NutritionRule(
            NutritionKey.POTASSIUM,
            Collections.<String>emptyList(),
            Arrays.asList(
                        "马铃薯",
                        "南瓜",
                        "菠菜",
                        "口蘑",
                        "香蕉",
                        "橙子",
                        "紫菜"),
            Arrays.asList(
                        "摄入量待营养师按指南填写"),
            Collections.<String>emptyList(),
            "🔴 肾功能不全、服用ACEI/ARB类降压药、保钾利尿剂、心衰患者等均可能有高钾风险，须遵医嘱。P0-8/§4.9：本维度是否整体退出自动推荐仍为上线阻塞项", ReviewStatus.DRAFT);

    /** 全部营养维度。 */
    public static final Map<NutritionKey, NutritionRule> ALL;

    static {
        Map<NutritionKey, NutritionRule> map = new EnumMap<>(NutritionKey.class);
        map.put(NutritionKey.IRON, IRON);
        map.put(NutritionKey.CALCIUM, CALCIUM);
        map.put(NutritionKey.PROTEIN, PROTEIN);
        map.put(NutritionKey.VITAMIN_D, VITAMIN_D);
        map.put(NutritionKey.VITAMIN_B12, VITAMIN_B12);
        map.put(NutritionKey.FOLATE, FOLATE);
        map.put(NutritionKey.DIETARY_FIBER, DIETARY_FIBER);
        map.put(NutritionKey.ZINC, ZINC);
        map.put(NutritionKey.POTASSIUM, POTASSIUM);
        ALL = Collections.unmodifiableMap(map);
    }
}
