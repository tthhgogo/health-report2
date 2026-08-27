package com.example.healthreport.constants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 过敏原组与词条常量。
 * <p>
 * <b>本类是过敏词表的唯一真源</b>，展示（模块三）与 Layer 1 硬匹配共用它， 不另建关键词表。增删词条会改变 Layer 1
 * 的拦截行为，属安全变更，须走医学评审并 bump {@link TagRuleVersion}。
 * </p>
 * <p>
 * 词条已按 2026-08-27 内容审核快照逐条裁决；被拒条目保留在原位置，防止未经复核重新加入（见 {@link ReviewStatus}）。
 * </p>
 */
public final class AllergenGroups {

	private AllergenGroups() {
	}

	/** 虾蟹类，19 个词条；蟹柳、蟹棒与海鲜酱在本组明确拒绝。 */
	public static final AllergenGroup SHRIMP_CRAB = new AllergenGroup(AllergenKey.SHRIMP_CRAB, "虾蟹类", true,
			Arrays.asList(
					AllergenWord.of("虾", "虾", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
							ReviewStatus.REVIEWED),
					AllergenWord.of("蟹", "蟹", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
							ReviewStatus.REVIEWED),
					AllergenWord.of("龙虾", "龙虾", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
							ReviewStatus.REVIEWED),
					AllergenWord.of("小龙虾", "小龙虾", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
							ReviewStatus.REVIEWED),
					AllergenWord.of("皮皮虾", "皮皮虾", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
							ReviewStatus.REVIEWED),
					AllergenWord.of("虾仁", "虾仁", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
							ReviewStatus.REVIEWED),
					AllergenWord.of("虾米", "虾米", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
							ReviewStatus.REVIEWED),
					AllergenWord.of("虾皮", "虾皮", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
							ReviewStatus.REVIEWED),
					AllergenWord.of("蟹肉", "蟹肉", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
							ReviewStatus.REVIEWED),
					AllergenWord.of("虾滑", "虾滑", Bucket.HIDDEN, EvidenceLevel.LIKELY, MatchMode.SUBSTRING,
							ReviewStatus.REVIEWED),
					AllergenWord.of("虾丸", "虾丸", Bucket.HIDDEN, EvidenceLevel.LIKELY, MatchMode.SUBSTRING,
							ReviewStatus.REVIEWED),
					AllergenWord.of("虾饺", "虾饺", Bucket.HIDDEN, EvidenceLevel.LIKELY, MatchMode.SUBSTRING,
							ReviewStatus.REVIEWED),
					AllergenWord.of("蟹柳", "蟹柳", Bucket.HIDDEN, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY,
							ReviewStatus.REJECTED), // 冻鱼糜制品不能仅凭名称归入甲壳类，主风险线索转入 FISH
					AllergenWord.of("蟹棒", "蟹棒", Bucket.HIDDEN, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY,
							ReviewStatus.REJECTED), // 冻鱼糜制品不能仅凭名称归入甲壳类，主风险线索转入 FISH
					AllergenWord.of("虾酱", "虾酱", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
							ReviewStatus.REVIEWED),
					AllergenWord.of("xo酱", "XO酱", Bucket.HIDDEN, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY,
							ReviewStatus.REVIEWED), // 配方差异大，名称不保证含有；只作为线索进 LLM-B 提示词
					AllergenWord.of("海鲜酱", "海鲜酱", Bucket.HIDDEN, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY,
							ReviewStatus.REJECTED), // 名称不能证明含虾蟹，配方线索转入 SOY/WHEAT
					AllergenWord.of("蟹粉", "蟹粉", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
							ReviewStatus.REVIEWED),
					AllergenWord.of("虾油", "虾油", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
							ReviewStatus.REVIEWED)));

	/** 鱼类，8 个词条。 */
	public static final AllergenGroup FISH = new AllergenGroup(AllergenKey.FISH, "鱼类", true, Arrays.asList(
			AllergenWord.of("鱼", "鱼", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("鱼肉", "鱼肉", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("鱼丸", "鱼丸", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("鱼露", "鱼露", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("鱼松", "鱼松", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("鱼籽", "鱼籽", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("蟹柳", "蟹柳", Bucket.HIDDEN, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY,
					ReviewStatus.REVIEWED),
			AllergenWord.of("蟹棒", "蟹棒", Bucket.HIDDEN, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY,
					ReviewStatus.REVIEWED)));

	/** 牛奶及乳制品，15 个词条。 */
	public static final AllergenGroup MILK = new AllergenGroup(AllergenKey.MILK, "牛奶及乳制品", true, Arrays.asList(
			AllergenWord.of("牛奶", "牛奶", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("奶油", "奶油", Bucket.AVOID, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY,
					ReviewStatus.REVIEWED), // 配方差异大，名称不保证含有；只作为线索进 LLM-B 提示词
			AllergenWord.of("黄油", "黄油", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("芝士", "芝士", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("奶酪", "奶酪", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("淡奶", "淡奶", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("炼乳", "炼乳", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("酸奶", "酸奶", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("乳酪", "乳酪", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("奶昔", "奶昔", Bucket.AVOID, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY,
					ReviewStatus.REVIEWED),
			AllergenWord.of("布丁", "布丁", Bucket.HIDDEN, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY,
					ReviewStatus.REVIEWED), // 配方差异大，名称不保证含有；只作为线索进 LLM-B 提示词
			AllergenWord.of("奶盖", "奶盖", Bucket.HIDDEN, EvidenceLevel.LIKELY, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("奶粉", "奶粉", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("乳清蛋白", "乳清蛋白", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("酪蛋白", "酪蛋白", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED)));

	/** 蛋类及其制品，14 个词条。 */
	public static final AllergenGroup EGG = new AllergenGroup(AllergenKey.EGG, "蛋类及其制品", true, Arrays.asList(
			AllergenWord.of("鸡蛋", "鸡蛋", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("蛋液", "蛋液", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("蛋清", "蛋清", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("蛋黄", "蛋黄", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("滑蛋", "滑蛋", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("蛋饺", "蛋饺", Bucket.HIDDEN, EvidenceLevel.LIKELY, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("蛋黄酱", "蛋黄酱", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("蛋挞", "蛋挞", Bucket.HIDDEN, EvidenceLevel.LIKELY, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("鸭蛋", "鸭蛋", Bucket.AVOID, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY,
					ReviewStatus.REVIEWED),
			AllergenWord.of("鹅蛋", "鹅蛋", Bucket.AVOID, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY,
					ReviewStatus.REVIEWED),
			AllergenWord.of("鹌鹑蛋", "鹌鹑蛋", Bucket.AVOID, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY,
					ReviewStatus.REVIEWED),
			AllergenWord.of("蒸蛋", "蒸蛋", Bucket.HIDDEN, EvidenceLevel.LIKELY, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("炒蛋", "炒蛋", Bucket.HIDDEN, EvidenceLevel.LIKELY, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("蛋花", "蛋花", Bucket.HIDDEN, EvidenceLevel.LIKELY, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED)));

	/** 花生，6 个词条。 */
	public static final AllergenGroup PEANUT = new AllergenGroup(AllergenKey.PEANUT, "花生", true, Arrays.asList(
			AllergenWord.of("花生", "花生", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("花生米", "花生米", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("花生酱", "花生酱", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("花生油", "花生油", Bucket.HIDDEN, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY,
					ReviewStatus.REVIEWED), // 精炼程度决定残余蛋白，只作为模型线索
			AllergenWord.of("花生碎", "花生碎", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("花生粉", "花生粉", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED)));

	/** 大豆，11 个词条。 */
	public static final AllergenGroup SOY = new AllergenGroup(AllergenKey.SOY, "大豆", true, Arrays.asList(
			AllergenWord.of("大豆", "大豆", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("黄豆", "黄豆", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("豆浆", "豆浆", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("豆腐", "豆腐", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("豆皮", "豆皮", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("腐竹", "腐竹", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("豆干", "豆干", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("千张", "千张", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("素鸡", "素鸡", Bucket.HIDDEN, EvidenceLevel.LIKELY, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("酱油", "酱油", Bucket.HIDDEN, EvidenceLevel.LIKELY, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("海鲜酱", "海鲜酱", Bucket.HIDDEN, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY,
					ReviewStatus.REVIEWED)));

	/** 小麦麸质，12 个词条；红烧、酱爆、卤等做法词不进入本组。 */
	public static final AllergenGroup WHEAT = new AllergenGroup(AllergenKey.WHEAT, "小麦麸质", true, Arrays.asList(
			AllergenWord.of("小麦", "小麦", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("面粉", "面粉", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("面包", "面包", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("面条", "面条", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("麸质", "麸质", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("馒头", "馒头", Bucket.HIDDEN, EvidenceLevel.LIKELY, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("包子", "包子", Bucket.HIDDEN, EvidenceLevel.LIKELY, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("面筋", "面筋", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("裹粉", "裹粉", Bucket.HIDDEN, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY,
					ReviewStatus.REVIEWED),
			AllergenWord.of("酱油", "酱油", Bucket.HIDDEN, EvidenceLevel.LIKELY, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("豉油", "豉油", Bucket.HIDDEN, EvidenceLevel.LIKELY, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("海鲜酱", "海鲜酱", Bucket.HIDDEN, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY,
					ReviewStatus.REVIEWED)));

	/** 坚果，9 个词条。 */
	public static final AllergenGroup NUTS = new AllergenGroup(AllergenKey.NUTS, "坚果", true, Arrays.asList(
			AllergenWord.of("坚果", "坚果", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("核桃", "核桃", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("腰果", "腰果", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("杏仁", "杏仁", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("榛子", "榛子", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("开心果", "开心果", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("松子", "松子", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("巴旦木", "巴旦木", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("碧根果", "碧根果", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED)));

	/** 芒果，1 个词条 */
	public static final AllergenGroup MANGO = new AllergenGroup(AllergenKey.MANGO, "芒果", true,
			Arrays.asList(AllergenWord.of("芒果", "芒果", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED)));

	/** 牛肉，3 个词条 */
	public static final AllergenGroup BEEF = new AllergenGroup(AllergenKey.BEEF, "牛肉", true, Arrays.asList(
			AllergenWord.of("牛肉", "牛肉", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("牛腩", "牛腩", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("牛排", "牛排", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED)));

	/** 羊肉，3 个词条 */
	public static final AllergenGroup MUTTON = new AllergenGroup(AllergenKey.MUTTON, "羊肉", true, Arrays.asList(
			AllergenWord.of("羊肉", "羊肉", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("羊排", "羊排", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("羊蝎子", "羊蝎子", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED)));

	/** 软体动物及其制品，17 个词条。 */
	public static final AllergenGroup MOLLUSK = new AllergenGroup(AllergenKey.MOLLUSK, "软体动物及其制品", true, Arrays.asList(
			AllergenWord.of("软体动物", "软体动物", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("贝类", "贝类", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("蛤蜊", "蛤蜊", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("花蛤", "花蛤", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("蛏子", "蛏子", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("生蚝", "生蚝", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("牡蛎", "牡蛎", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("扇贝", "扇贝", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("鲍鱼", "鲍鱼", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("鱿鱼", "鱿鱼", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("章鱼", "章鱼", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("墨鱼", "墨鱼", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("乌贼", "乌贼", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("干贝", "干贝", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("蚝油", "蚝油", Bucket.HIDDEN, EvidenceLevel.LIKELY, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("蚝汁", "蚝汁", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("xo酱", "XO酱", Bucket.HIDDEN, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY,
					ReviewStatus.REVIEWED)));

	/** 芝麻及其制品，8 个词条。 */
	public static final AllergenGroup SESAME = new AllergenGroup(AllergenKey.SESAME, "芝麻及其制品", true, Arrays.asList(
			AllergenWord.of("芝麻", "芝麻", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING, ReviewStatus.REVIEWED),
			AllergenWord.of("白芝麻", "白芝麻", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("黑芝麻", "黑芝麻", Bucket.AVOID, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("芝麻酱", "芝麻酱", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("芝麻油", "芝麻油", Bucket.HIDDEN, EvidenceLevel.DIRECT, MatchMode.SUBSTRING,
					ReviewStatus.REVIEWED),
			AllergenWord.of("麻酱", "麻酱", Bucket.HIDDEN, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY,
					ReviewStatus.REVIEWED),
			AllergenWord.of("香油", "香油", Bucket.HIDDEN, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY,
					ReviewStatus.REVIEWED),
			AllergenWord.of("麻油", "麻油", Bucket.HIDDEN, EvidenceLevel.POSSIBLE, MatchMode.MODEL_ONLY,
					ReviewStatus.REVIEWED)));

	/** 尘螨（非食物过敏原），0 个词条 */
	public static final AllergenGroup DUST_MITE = new AllergenGroup(AllergenKey.DUST_MITE, "尘螨", false,
			new ArrayList<AllergenWord>(0));

	/** 花粉（非食物过敏原），0 个词条 */
	public static final AllergenGroup POLLEN = new AllergenGroup(AllergenKey.POLLEN, "花粉", false,
			new ArrayList<AllergenWord>(0));

	/** 动物皮屑（非食物过敏原），0 个词条 */
	public static final AllergenGroup ANIMAL_DANDER = new AllergenGroup(AllergenKey.ANIMAL_DANDER, "动物皮屑", false,
			new ArrayList<AllergenWord>(0));

	/** 霉菌（非食物过敏原），0 个词条 */
	public static final AllergenGroup MOLD = new AllergenGroup(AllergenKey.MOLD, "霉菌", false,
			new ArrayList<AllergenWord>(0));

	/** 蟑螂（非食物过敏原），0 个词条 */
	public static final AllergenGroup COCKROACH = new AllergenGroup(AllergenKey.COCKROACH, "蟑螂", false,
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
		map.put(AllergenKey.MOLLUSK, MOLLUSK);
		map.put(AllergenKey.SESAME, SESAME);
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
