# 内容常量

> **真源是 Java 常量类**，不在本目录，在
> `src/main/java/com/example/healthreport/constants/`。
>
> 本目录只有说明文档。**没有 CSV，没有生成器，没有运行时加载。**

| 类 | 内容 |
|---|---|
| `AllergenGroups` | 18 组（13 个食入性）+ 126 词条。123 条 `REVIEWED`、3 条 `REJECTED` |
| `AllergenExceptions` | 16 条误杀例外，12 条 `REVIEWED`、4 条 `REJECTED`；`sourceField` 只支持菜名 |
| `NutritionContents` | 9 个营养维度，含可推荐 / 仅展示两个食材列表 |
| `DietRequirementContents` | 9 个饮食注意维度，**结构上没有可推荐字段** |
| `TagRuleVersion` | 唯一的版本号，进 `tagHash`（打标输入哈希）。展示类内容改动不 bump 它，也不需要 bump 任何别的版本号 |

列语义、裁决结论、契约测试清单见 `内容常量说明V3.md`；逐条来源与版本见
`内容常量证据审核台账V1.md`。台账中的证据复核不替代组织要求的具名执业人员签字。

## 三条最容易写错的

**① `DietRequirementRule` 没有可推荐字段。**
仅凭食材证明不了一道菜真的低脂低盐，所以饮食注意维度只产生 `REJECT`。
LLM-B 的 `verdict` 枚举因此只有 `REJECT`——这是类型保证的，不是约定。

**② 例外只作用于菜名，食材命中永远优先。**
「鱼香肉丝」的例外只取消菜名中「鱼」的命中；该菜配料若另含鱼露，`FISH` 仍然 `REJECT`。

**③ `POSSIBLE` 只能配 `MODEL_ONLY`。**
`POSSIBLE + SUBSTRING` 会把「配方不保证含有」的词做成硬拒绝，单测强制拦截。

## 两条永久不变量（单测强制）

```
同组内  avoid ∩ hidden = ∅
展示用的词与 Layer 1 匹配用的词是同一份数据，不可能出现「展示了但不匹配」
```

## 版本

改 `constants` 包里任何影响打标的内容，必须同时 bump `TagRuleVersion.VALUE`。
**忘记 bump 的后果是静默的**——预热 diff 认为标签已存在而跳过，新规则永远不生效，
不报错也不告警。CI 应校验：本包有 diff 但版本号未变则构建失败。
