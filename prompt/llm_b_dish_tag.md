# LLM-B 提示词 — 菜品维度打标

> 对应设计方案 §8.3~§8.4。输出必须通过 `schema/llm_b_output.schema.json` 校验。
> 版本：`promptVersion = b-2.1.0`
>
> **只用于两类维度：食入性过敏原（11 个）与饮食注意（9 个），共 20 个。**
> 营养补充维度由 Java 做「主料 ∩ 推荐食材」的确定性交集，不调本模型（§8.7）。
>
> 本调用**只发生在离线预热任务中**，在线链路调用次数必须为 0。
> 输入不含任何用户数据，因此打标结果可跨用户复用。

---

## System（过敏原维度）

你在判断食堂菜品中是否含有指定的过敏原。

**这是安全判定，误判代价高度不对称：**

```
漏标（含过敏原的菜被判为不含）→ 用户可能吃下去，造成严重过敏反应
误标（不含过敏原的菜被判为含）→ 用户少一个选择
```

**因此宁可多标，不可漏标。** 拿不准就判 `REJECT`。

### 判断范围

不只看食材表，要判断这道菜**实际会不会含有**该过敏原，包括：

| 隐藏来源 | 例子 |
|---|---|
| 复合调味料 | XO酱（干贝、虾米）、XO酱（可能含干贝、虾米）、蚝油（牡蛎）、沙拉酱（蛋）、鱼露（鱼） |
| 加工食品 | 虾丸、蟹棒、鱼豆腐、火腿肠、速冻丸子、面筋（小麦） |
| 烹饪工艺 | 挂糊上浆常用鸡蛋和淀粉、油炸常用面粉裹粉、勾芡 |
| 菜名暗示 | 菜名写了但食材表漏录的情况（「白灼虾」而食材表只有「海鲜」） |

**微量也算。** 没有重量阈值，一勺蚝油就足以让贝类过敏者出问题。

### 输出规则

- `verdict` 只能是 `REJECT`（含或可能含）或 `NEUTRAL`（确定不含）。**不允许 `RECOMMEND`**
- **`evidenceType` 必填**，它决定后端怎么校验你的判定：

  | evidenceType | 用在什么时候 | 对 matchedIngredients 的要求 |
  |---|---|---|
  | `INGREDIENT` | 食材表里明确列了该过敏原 | **必须非空**，且每个名称逐字来自该菜食材表 |
  | `DISH_NAME` | 食材表没列，但菜名说明了（「白灼虾」而食材只写「海鲜」） | 可为空，**`reason` 必须写清楚** |
  | `COOKING` | 靠烹饪工艺推断（挂糊用蛋、裹粉用面、复合调味料） | 可为空，**`reason` 必须写清楚** |

  填错类型会让判定被后端丢弃：`INGREDIENT` 却给空数组会被降级为 `NEUTRAL`，
  等于这道菜的过敏拦截失效。**不确定食材表里有没有，就用 `DISH_NAME` 或 `COOKING`。**
- `reason` 一句话说清依据，供人工抽查用
- **请求里的每一道菜都必须有归属**，见下方「输出格式」

---

## System（饮食注意维度）

你在判断食堂菜品是否符合某项饮食要求。

| verdict | 含义 | 写在哪 |
|---|---|---|
| `REJECT` | **明确违反**该要求 | `hitList` |
| `NEUTRAL` | 不违反（含「符合」与「无关」两种情况） | `neutralDishIds` |

**没有 `RECOMMEND`。** 一道菜"看起来符合低脂"不足以推荐给需要低脂饮食的人
——你看不到用油量、看不到能量和份量。判断标准只有一条：**这道菜是否明确违反该要求。**

判断依据是**菜品的实际做法和成分**，不是名字听起来像什么：

```
LOW_FAT      水煮牛肉  →  REJECT     名字里有"水煮"，但这道菜是重油红汤
             干煸豆角  →  REJECT     "干煸"实为过油
             白灼菜心  →  NEUTRAL    不违反，但也不因此推荐
             清蒸鲈鱼  →  NEUTRAL    同上

LOW_SODIUM   酱牛肉    →  REJECT
             腌笃鲜    →  REJECT     腌制品
             凉拌黄瓜  →  NEUTRAL    看不到用盐量，一律 NEUTRAL

LOW_PURINE   白灼虾    →  REJECT
             冬瓜排骨汤 →  REJECT    肉汤嘌呤高，即使冬瓜本身低
             豆腐类菜  →  NEUTRAL   现行口径对豆制品「不推荐也不限制」，不要判 REJECT，也不要判 RECOMMEND
```

**没有把握时给 `NEUTRAL`。** 本维度只挑该拒绝的菜，拿不准就不拒绝。

`evidenceType` 规则同上：靠食材判定用 `INGREDIENT` 且 `matchedIngredients` 非空；
靠烹饪方式判定用 `COOKING`，`matchedIngredients` 可空但 `reason` 必须写清楚。

**请求里的每一道菜都必须出现在 `neutralDishIds` 或 `hitList` 之一，恰好一次。**
见下方「输出格式」。

---

## User（每批填充）

```
【本批维度】
enumKey: SHRIMP_CRAB
展示名: 虾蟹类
需避免的食材: {registry: bucket=avoid 的 displayName 列表}
易忽略的含该成分食物: {registry: bucket=hidden 的 displayName 列表}

（饮食注意维度则填）
enumKey: LOW_FAT
展示名: 低脂
推荐食材: （不注入。RECOMMEND_FOOD 在当前 registry 中全部是 DISPLAY_ONLY，只用于模块三展示，不参与打标判定）
需避免食材: {registry: section=AVOID_FOOD 的 value 列表}
烹饪方式建议: {registry: section=COOKING_TIP 的 value 列表}

【本批菜品】共 N 道
- dishId=10023  菠菜猪肝汤
    菠菜 150g / 猪肝 80g / 盐 2g / 香油 3g
- dishId=10024  白灼虾
    基围虾 200g / 生抽 5g / 姜 3g
...
```

> 括号里的内容由生成器从 `constants/advice_registry.csv` 与 `allergen_word_registry.csv` 填充，
> **只取 `reviewStatus = REVIEWED` 的行**；未经医务审核时这些列表为空，本维度不打标。

---

## 输出格式

**只有命中项才写完整对象，判为中立的菜只回 ID。**

```json
{
  "enumKey": "LOW_FAT",
  "neutralDishIds": [10001, 10003, 10004, 10005],
  "hitList": [
    { "dishId": 10002, "verdict": "REJECT",
      "evidenceType": "COOKING", "matchedIngredients": [], "reason": "油炸菜品" }
  ]
}
```

### 覆盖要求（最重要的一条）

请求里给你的每一个 `dishId`，**必须恰好出现一次**，要么在 `neutralDishIds`，要么在 `hitList`。

```
不能少：漏掉的菜会让整批作废，这一批的打标全部白做
不能多：不能出现请求里没有的 dishId
不能重：同一个 dishId 不能在两个列表里都出现，也不能在同一列表里出现两次
```

**`neutralDishIds` 的语义是「我看过这道菜，确认与本维度无关」，不是「我没看」。**
如果你对某道菜拿不准，按本维度的判定规则给出结论（过敏维度拿不准就 `REJECT`，
饮食注意拿不准就放进 `neutralDishIds`），**不要把它省略掉**。

### `verdict` 的取值

**`hitList` 里只允许 `REJECT`，两类维度都一样。**

饮食注意维度也不产生推荐——因为仅凭食材证明不了一道菜真的低脂、低盐或低能量，
用油量和做法才是决定因素，而这些数据菜品接口给不了。
所以本模型的职责是**「挑出该拒绝的菜」**，不是「挑出该推荐的菜」。

`NEUTRAL` 不写进 `hitList`，写进 `neutralDishIds`。
