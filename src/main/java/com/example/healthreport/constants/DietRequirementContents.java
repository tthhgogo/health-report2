package com.example.healthreport.constants;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 饮食注意内容常量。
 * <p>
 * 本维度由 LLM-B 离线打标，<b>当前只产生 REJECT</b>：本类中没有任何可触发推荐的规则， 因此 LLM-B 的 verdict 枚举生成结果是
 * REJECT-only。
 * </p>
 * <p>
 * 九个维度已按 2026-08-27 内容审核快照裁决；来源与适用边界见内容常量审核台账。
 * </p>
 */
public final class DietRequirementContents {

	private DietRequirementContents() {
	}

	/** LOW_FAT。 */
	public static final DietRequirementRule LOW_FAT = new DietRequirementRule(DietRequirementKey.LOW_FAT,
			Arrays.asList("鸡胸肉", "鱼肉", "虾仁", "北豆腐", "南豆腐", "脱脂牛奶"), Arrays.asList("肥肉", "五花肉", "奶油", "黄油", "肥牛"),
			Arrays.asList("油炸类", "干煸类", "酥皮点心"), Arrays.asList("优先少油的蒸、煮、炖、白灼；避免煎、炸、干煸"),
			Collections.<String>emptyList(), "", ReviewStatus.REVIEWED);

	/** LOW_SODIUM。 */
	public static final DietRequirementRule LOW_SODIUM = new DietRequirementRule(DietRequirementKey.LOW_SODIUM,
			Arrays.asList("新鲜蔬菜", "新鲜肉类", "鲜鱼"),
			Arrays.asList("咸菜", "腌肉", "腊肠", "火腿", "酱菜", "榨菜", "腐乳", "咸鱼", "腌制海鲜", "咸鸭蛋"),
			Arrays.asList("重度使用酱油/蚝油/豆瓣酱/辣椒酱/甜面酱/鸡精的菜", "方便面类"), Arrays.asList("出锅前再放盐；用醋、柠檬、香辛料提味"),
			Collections.<String>emptyList(), "", ReviewStatus.REVIEWED);

	/** LOW_ADDED_SUGAR。 */
	public static final DietRequirementRule LOW_ADDED_SUGAR = new DietRequirementRule(
			DietRequirementKey.LOW_ADDED_SUGAR, Collections.<String>emptyList(),
			Arrays.asList("含糖饮料", "蜜饯", "蜂蜜", "糖浆"), Arrays.asList("糕点", "拔丝类", "糖醋类", "甜羹", "勾芡挂糖浆的菜"),
			Arrays.asList("烹调不加糖"), Collections.<String>emptyList(), "", ReviewStatus.REVIEWED);

	/** LOW_PURINE。 */
	public static final DietRequirementRule LOW_PURINE = new DietRequirementRule(DietRequirementKey.LOW_PURINE,
			Arrays.asList("鸡蛋", "牛奶", "白米", "冬瓜", "黄瓜", "白菜", "生菜"), Arrays.asList("动物内脏", "沙丁鱼", "凤尾鱼"),
			Arrays.asList("浓肉汤", "老火汤", "火锅汤底", "啤酒"), Arrays.asList("肉类先焯水弃汤再烹调（只降低嘌呤，不能把高嘌呤食材翻转为推荐）"),
			Arrays.asList("在心、肾功能正常且无液体限制的前提下，保证充足饮水"), "", ReviewStatus.REVIEWED);

	/** LOW_CHOLESTEROL。 */
	public static final DietRequirementRule LOW_CHOLESTEROL = new DietRequirementRule(
			DietRequirementKey.LOW_CHOLESTEROL, Arrays.asList("深海鱼", "去皮禽肉", "燕麦", "黑木耳"),
			Arrays.asList("动物内脏", "蟹黄", "鱼籽", "肥肉", "黄油", "奶油"), Collections.<String>emptyList(),
			Arrays.asList("以蒸煮为主；用植物油不等于用油量合适"), Collections.<String>emptyList(), "", ReviewStatus.REVIEWED);

	/** LOW_CALORIE。 */
	public static final DietRequirementRule LOW_CALORIE = new DietRequirementRule(DietRequirementKey.LOW_CALORIE,
			Arrays.asList("绿叶蔬菜", "菌菇", "魔芋", "冬瓜", "鸡胸肉"), Arrays.asList("肥肉", "含糖饮料", "奶油制品"),
			Arrays.asList("油炸类", "糕点"), Arrays.asList("蒸煮凉拌为主，少油少糖"), Arrays.asList("控制主食份量", "根据体重目标控制总能量与主食、油脂份量"),
			"", ReviewStatus.REVIEWED);

	/** HIGH_FIBER。 */
	public static final DietRequirementRule HIGH_FIBER = new DietRequirementRule(DietRequirementKey.HIGH_FIBER,
			Arrays.asList("燕麦", "糙米", "玉米", "红薯", "芹菜", "西兰花", "黑木耳", "白菜"), Collections.<String>emptyList(),
			Collections.<String>emptyList(), Arrays.asList("粗粮杂豆替换部分精白主食；增加纤维应循序渐进"), Arrays.asList("如无液体摄入限制，适量增加饮水"),
			"", ReviewStatus.REVIEWED);

	/** LIMIT_ALCOHOL。 */
	public static final DietRequirementRule LIMIT_ALCOHOL = new DietRequirementRule(DietRequirementKey.LIMIT_ALCOHOL,
			Collections.<String>emptyList(), Arrays.asList("白酒", "啤酒", "黄酒", "米酒"),
			Arrays.asList("醉蟹", "醉虾", "啤酒鸭", "酒焖类", "酒炖类"), Collections.<String>emptyList(),
			Collections.<String>emptyList(), "", ReviewStatus.REVIEWED);

	/** LIGHT_DIET。 */
	public static final DietRequirementRule LIGHT_DIET = new DietRequirementRule(DietRequirementKey.LIGHT_DIET,
			Arrays.asList("北豆腐", "南豆腐", "冬瓜", "瘦肉", "鲜鱼", "绿叶蔬菜"), Collections.<String>emptyList(),
			Arrays.asList("麻辣类", "烧烤", "油炸", "火锅", "干锅类"), Arrays.asList("蒸、煮、炖、白灼为主；少辣、少油、少盐"),
			Collections.<String>emptyList(), "", ReviewStatus.REVIEWED);

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
