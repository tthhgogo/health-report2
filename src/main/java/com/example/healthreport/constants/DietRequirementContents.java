package com.example.healthreport.constants;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 饮食注意内容常量。
 * <p>
 * 本维度由 LLM-B 离线打标，<b>LLM-B 侧只产生 REJECT</b>：本类不向模型下发任何正向内容， 因此 LLM-B 的 verdict
 * 枚举生成结果仍是 REJECT-only。
 * </p>
 * <p>
 * 推荐由 Java 按 {@link PositiveMatchPolicy} 做确定性主料交集产出，只有 {@code recommendableFoodList} 参与；
 * 第一期只开放 {@code LOW_PURINE} 与 {@code HIGH_FIBER}，其余七个维度取决于调味料、用油量或酒的实际用量，
 * 菜品接口给不了这些数据，政策一律为 {@code NONE}、正向审核状态为 {@code REJECTED}。
 * </p>
 * <p>
 * 九个维度已按 2026-08-27 内容审核快照裁决；来源与适用边界见内容常量审核台账。
 * </p>
 */
public final class DietRequirementContents {

	private DietRequirementContents() {
	}

	/** LOW_FAT。 */
	public static final DietRequirementRule LOW_FAT = new DietRequirementRule(DietRequirementKey.LOW_FAT, "低脂饮食",
			Arrays.asList("鸡胸肉", "鱼肉", "虾仁", "北豆腐", "南豆腐", "脱脂牛奶"), Arrays.asList("肥肉", "五花肉", "奶油", "黄油", "肥牛"),
			Arrays.asList("油炸类", "干煸类", "酥皮点心"), Arrays.asList("优先少油的蒸、煮、炖、白灼；避免煎、炸、干煸"),
			Collections.<String>emptyList(), "", ReviewStatus.REVIEWED, PositiveMatchPolicy.NONE,
			Collections.<String>emptyList(), "", ReviewStatus.REJECTED);

	/** LOW_SODIUM。 */
	public static final DietRequirementRule LOW_SODIUM = new DietRequirementRule(DietRequirementKey.LOW_SODIUM, "低盐饮食",
			Arrays.asList("新鲜蔬菜", "新鲜肉类", "鲜鱼"),
			Arrays.asList("咸菜", "腌肉", "腊肠", "火腿", "酱菜", "榨菜", "腐乳", "咸鱼", "腌制海鲜", "咸鸭蛋"),
			Arrays.asList("重度使用酱油/蚝油/豆瓣酱/辣椒酱/甜面酱/鸡精的菜", "方便面类"), Arrays.asList("出锅前再放盐；用醋、柠檬、香辛料提味"),
			Collections.<String>emptyList(), "", ReviewStatus.REVIEWED, PositiveMatchPolicy.NONE,
			Collections.<String>emptyList(), "", ReviewStatus.REJECTED);

	/** LOW_ADDED_SUGAR。 */
	public static final DietRequirementRule LOW_ADDED_SUGAR = new DietRequirementRule(
			DietRequirementKey.LOW_ADDED_SUGAR, "限制添加糖", Collections.<String>emptyList(),
			Arrays.asList("含糖饮料", "蜜饯", "蜂蜜", "糖浆"), Arrays.asList("糕点", "拔丝类", "糖醋类", "甜羹", "勾芡挂糖浆的菜"),
			Arrays.asList("烹调不加糖"), Collections.<String>emptyList(), "", ReviewStatus.REVIEWED, PositiveMatchPolicy.NONE,
			Collections.<String>emptyList(), "", ReviewStatus.REJECTED);

	/**
	 * LOW_PURINE。第一期开放推荐：正向食材沿用本维度已审核的展示食材，全部是公认的低嘌呤主料。
	 * <b>不得因为「没命中高嘌呤词」就推荐</b>——配料表不含调味料，看不见浓汤、料酒，缺证据不等于证据表明安全。
	 */
	public static final DietRequirementRule LOW_PURINE = new DietRequirementRule(DietRequirementKey.LOW_PURINE, "低嘌呤饮食",
			Arrays.asList("鸡蛋", "牛奶", "白米", "冬瓜", "黄瓜", "白菜", "生菜"), Arrays.asList("动物内脏", "沙丁鱼", "凤尾鱼"),
			Arrays.asList("浓肉汤", "老火汤", "火锅汤底", "啤酒"), Arrays.asList("肉类先焯水弃汤再烹调（只降低嘌呤，不能把高嘌呤食材翻转为推荐）"),
			Arrays.asList("在心、肾功能正常且无液体限制的前提下，保证充足饮水"), "", ReviewStatus.REVIEWED,
			PositiveMatchPolicy.MAIN_INGREDIENT_INTERSECTION,
			Arrays.asList("鸡蛋", "牛奶", "白米", "冬瓜", "黄瓜", "白菜", "生菜"), "低嘌呤", ReviewStatus.REVIEWED);

	/** LOW_CHOLESTEROL。 */
	public static final DietRequirementRule LOW_CHOLESTEROL = new DietRequirementRule(
			DietRequirementKey.LOW_CHOLESTEROL, "低胆固醇饮食", Arrays.asList("深海鱼", "去皮禽肉", "燕麦", "黑木耳"),
			Arrays.asList("动物内脏", "蟹黄", "鱼籽", "肥肉", "黄油", "奶油"), Collections.<String>emptyList(),
			Arrays.asList("以蒸煮为主；用植物油不等于用油量合适"), Collections.<String>emptyList(), "", ReviewStatus.REVIEWED, PositiveMatchPolicy.NONE,
			Collections.<String>emptyList(), "", ReviewStatus.REJECTED);

	/** LOW_CALORIE。 */
	public static final DietRequirementRule LOW_CALORIE = new DietRequirementRule(DietRequirementKey.LOW_CALORIE, "控制体重",
			Arrays.asList("绿叶蔬菜", "菌菇", "魔芋", "冬瓜", "鸡胸肉"), Arrays.asList("肥肉", "含糖饮料", "奶油制品"),
			Arrays.asList("油炸类", "糕点"), Arrays.asList("蒸煮凉拌为主，少油少糖"), Arrays.asList("控制主食份量", "根据体重目标控制总能量与主食、油脂份量"),
			"", ReviewStatus.REVIEWED, PositiveMatchPolicy.NONE,
			Collections.<String>emptyList(), "", ReviewStatus.REJECTED);

	/**
	 * HIGH_FIBER。第一期开放推荐：正向食材与 {@code NutritionContents.DIETARY_FIBER} 的 recommendableFoodList 保持一致，
	 * 同一份食材表已作为推荐触发器通过审核。<b>白菜只进展示、不进推荐</b>，与营养侧的既有裁决一致，不得因为两处字段名不同就放宽。
	 */
	public static final DietRequirementRule HIGH_FIBER = new DietRequirementRule(DietRequirementKey.HIGH_FIBER, "高纤维饮食",
			Arrays.asList("燕麦", "糙米", "玉米", "红薯", "芹菜", "西兰花", "黑木耳", "白菜"), Collections.<String>emptyList(),
			Collections.<String>emptyList(), Arrays.asList("粗粮杂豆替换部分精白主食；增加纤维应循序渐进"), Arrays.asList("如无液体摄入限制，适量增加饮水"),
			"", ReviewStatus.REVIEWED,
			PositiveMatchPolicy.MAIN_INGREDIENT_INTERSECTION,
			Arrays.asList("燕麦", "糙米", "玉米", "红薯", "芹菜", "西兰花", "黑木耳"), "高纤维", ReviewStatus.REVIEWED);

	/** LIMIT_ALCOHOL。 */
	public static final DietRequirementRule LIMIT_ALCOHOL = new DietRequirementRule(DietRequirementKey.LIMIT_ALCOHOL, "限制饮酒",
			Collections.<String>emptyList(), Arrays.asList("白酒", "啤酒", "黄酒", "米酒"),
			Arrays.asList("醉蟹", "醉虾", "啤酒鸭", "酒焖类", "酒炖类"), Collections.<String>emptyList(),
			Collections.<String>emptyList(), "", ReviewStatus.REVIEWED, PositiveMatchPolicy.NONE,
			Collections.<String>emptyList(), "", ReviewStatus.REJECTED);

	/** LIGHT_DIET。 */
	public static final DietRequirementRule LIGHT_DIET = new DietRequirementRule(DietRequirementKey.LIGHT_DIET, "清淡饮食",
			Arrays.asList("北豆腐", "南豆腐", "冬瓜", "瘦肉", "鲜鱼", "绿叶蔬菜"), Collections.<String>emptyList(),
			Arrays.asList("麻辣类", "烧烤", "油炸", "火锅", "干锅类"), Arrays.asList("蒸、煮、炖、白灼为主；少辣、少油、少盐"),
			Collections.<String>emptyList(), "", ReviewStatus.REVIEWED, PositiveMatchPolicy.NONE,
			Collections.<String>emptyList(), "", ReviewStatus.REJECTED);

	/** 全部饮食注意维度。 */
	public static final Map<DietRequirementKey, DietRequirementRule> ALL;
	static {
		Map<DietRequirementKey, DietRequirementRule> map = new EnumMap<>(DietRequirementKey.class);
		map.put(DietRequirementKey.LOW_FAT, LOW_FAT);
		map.put(DietRequirementKey.LOW_SODIUM, LOW_SODIUM);
		map.put(DietRequirementKey.LOW_ADDED_SUGAR, LOW_ADDED_SUGAR);
		map.put(DietRequirementKey.LOW_PURINE, LOW_PURINE);
		map.put(DietRequirementKey.LOW_CHOLESTEROL, LOW_CHOLESTEROL);
		map.put(DietRequirementKey.LOW_CALORIE, LOW_CALORIE);
		map.put(DietRequirementKey.HIGH_FIBER, HIGH_FIBER);
		map.put(DietRequirementKey.LIMIT_ALCOHOL, LIMIT_ALCOHOL);
		map.put(DietRequirementKey.LIGHT_DIET, LIGHT_DIET);
		ALL = Collections.unmodifiableMap(map);
	}

}
