# 开发任务 06：LLM-A 校验、安全扫描与合并

> 判据优先级：`AGENTS.md` > 本任务文件 > `AI体检报告分析-开发方案V1.md` > `AI体检报告分析-精简设计方案V1.md` > `体检报告分析需求.md`
> 与更高优先级的安全、持久化、公开接口或医疗规则冲突时停止为 BLOCKED，不得自行选规则。

> 覆盖开发方案 §6.3（① / ①a / ①b / 6.3.1）/ §6.4 / §6.5（A~F）/ §6.6。
> **这是全案安全红线最密集的一批，评审时逐条对照 §0.3。执行顺序不可调换。**

## 1. 目标

落地 Schema 校验、`blockRef` 展开、引用完整性、来源校验、六道安全扫描与降级、
多批多文件合并与同一性校验。

## 2. 范围

- 要修改的模块：`llm/a`、`safety`
- 允许修改的接口：新增 `LlmAValidationPipeline`、`AllergenSuspectScanner`、
  `PositiveRowCoverageScanner`、`AllergenAdmissionFilter`
- 允许修改的数据库表/字段：`partial` / `partial_reason`
- 允许修改的 Prompt/Schema/常量：可同步 `schema/extraction_output.schema.json`（若发现契约不一致，先停止并报告）

## 3. 功能要求

### A. 校验流水线（原 17）

1. **① Schema 校验**：任一必填缺失 → **直接 `FAILED/SERVER_ERROR`，不重试**。记 `schemaMissCount`。
2. **①a `blockRef` 展开**（Schema 通过后**第一件事**）：查本批映射表把
   `blockRefs`/`sectionBlockRef`/`nameBlockRefs`/`genderBlockRefs` 展开成 `segmentId`；
   越界/重复/映射不到 → 按「不存在的 segmentId」处理。**展开后 `blockRef` 不再出现在下游**。
3. **①b 引用完整性**：`sections[i].sectionIndex` 必须唯一且 `== i` → 不满足整批 `FAILED/SERVER_ERROR`；
   条目 `sectionIndex` 必须 ∈ 该集合 → 不满足**该条目整条丢弃**、记 `sectionRefMissCount`、
   **其余条目不受影响**；`sourceOrder`/`orderInSection` 只查范围**不要求连续**。
4. **准入三分法**（§6.3.1）：有数值+有结论 → `indicators`；有数值+无结论 → **全部丢弃**；
   无数值+有结论 → `textualFindings`。**Java 不得为对齐总览数字把丢弃的补回来**。
5. **来源校验**（§6.4）：按 `textSource` 分档——`NATIVE` 严格子串；`OCR` 放宽（去空白子串 → 编辑距离 ≤1，
   记 `ocrFuzzyMatchCount`）。**逐字段处理按 §6.4 的表照写**，其中：
   `problemName` 不过 → 降 `null` **不丢条目**；`allergens` 两字段不过 → 丢弃**且触发 `ALLERGEN_SUSPECT_MISS`**；
   `sectionName`(CURRENT/UNSECTIONED) 不过 → 该组 `displayName` 降固定文案**不丢内容**；
   `reportOverview` 两个数字必须都在 → 否则整个降 `null`；
   `patient` 两字段不过 → 降 `null` 且**不得参与同一性判断**。
6. 展示原文类长文本**一律不用模型返回值**，Java 按 `segmentId` 取整段 `rawText`。

### B. 安全扫描与降级（原 18）

7. **A 高风险交叉扫描**：过敏原章节名 / 阳性标记命中而 `allergens` 为空 → `ALLERGEN_SUSPECT_MISS`。
   **没有饮食医嘱词扫描、没有 `dietSuspectMissCount`**——它只记计数不影响输出，属 §0.3-② 禁止的那一类。
8. **B 过敏覆盖集合规则**：`D ⊆ S`、`A ⊆ S` 为结构断言（违约即 `FAILED/SERVER_ERROR`）；
   `S 非空且 D 为空` 或 `D \\ A 非空` → 降级。
   **B 不是独立防线**——三个集合同源，拦不住「一致地漏」。
9. **C 阳性行覆盖扫描**：候选段 = `normalizedText` **同块**命中「阳性标记 + 已知过敏原名」；
   候选段必须 ∈ A，否则降级、记 `allergenPositiveUncoveredCount`。
   **已知盲区不得试图弥补**：名称与结果分属两块时命中不了（PDF 原生绘制单元常态）；
   **禁止用 bbox 同行 / seq 相邻 / 表格还原去配对**——那是版面推断。
10. **D 过敏原准入过滤**：仅 `POSITIVE` / `BORDERLINE` 进链路；
    `NEGATIVE` **不计数**；`UNKNOWN` **单独计入 `allergenUnknownCount`**。
    `isFoodBorne` **由 `enumKey` 查 `AllergenGroups` 得到，模型返回值直接丢弃**；
    `OTHER` 采信模型、**不校验不告警**。
11. **E 回切失败**：属 `allergens` 的条目被丢弃 → `ALLERGEN_SUSPECT_MISS`。
12. **F 健康问题准入**：只有 `includeInHealthProblems=true` 进模块二；**Java 既不覆写也不扫词表告警**。

### C. 合并、去重与同一性（原 18 §6.6）

13. 跨批去重**只在批次输入确有重叠时执行**，判据是**同 `segmentId` 且同 `itemIndex`**；
    **非重叠页面一律不去重**。
14. 同一性校验取通过来源校验的非空值，不一致 → `IDENTITY_MISMATCH`。

## 4. 验收条件

- [ ] 正常场景：合法输出全部通过
- [ ] 边界场景：**R19** —— `blockRefs` 传字符串/越界/重复/33 条各自被拒
- [ ] 边界场景：**R20** —— `sections[i].sectionIndex != i` → 整批 `FAILED/SERVER_ERROR`
- [ ] 边界场景：**R21** —— 条目 `sectionIndex` 指向不存在的章节 → 该条丢弃、计数 +1、**其余不受影响**
- [ ] 边界场景：**R21a** —— 「有数值无结论」的指标**不出现在任何模块**
- [ ] 边界场景：**R46** —— OCR 认错字时放宽档通过、`NATIVE` 档同样输入应拒绝
- [ ] 边界场景：**R10** —— 某段名称与结果分属两个 segment 时阳性行覆盖扫描**不触发**
      （锁住已知盲区，防止被「优化」成坐标配对）
- [ ] 边界场景：**R12** —— `NEGATIVE` 不计数、`UNKNOWN` 计入 `allergenUnknownCount`
- [ ] 边界场景：**R4/R5** —— 正式枚举 `isFoodBorne` 查表为准、模型值丢弃；`OTHER` 采信模型不告警
- [ ] 边界场景：**R2/R3** —— `status` 与 `includeInHealthProblems` **不被 Java 改写**
- [ ] 边界场景：**R26** —— 去重只认 `segmentId + itemIndex`，非重叠页面不去重
- [ ] 失败场景：**R11** —— `allergens` 来源校验失败 → 丢弃**且**触发 `ALLERGEN_SUSPECT_MISS`
- [ ] 失败场景：**R24/R25** —— `patient` 证据缺失被 Schema 拒；来源校验失败则降 `null` 且**不导致 `IDENTITY_MISMATCH`**
- [ ] 失败场景：**R7** —— 过敏章节名命中而数组为空 → `partial=true`、模块四不输出、模块一二三照常
- [ ] 失败场景：**R8** —— `D \\ A` 非空 → 同上
- [ ] 失败场景：**R9** —— 某段同时含「牛奶」与「阳性(+)」但不在 A 中 → 降级、计数 +1
- [ ] 安全与日志：模型响应不进日志；**R1** —— ArchUnit 断言生产代码**不存在**
      `ConclusionLabelWords`/`NormalStatementWords`/`AllergenSectionWords` 引用
- [ ] Java 8 全量构建通过：是

## 5. 不做什么

- 本任务不包含：模块组装（任务 07）
- 不允许新增：**任何改写模型语义结论的逻辑**（`status`/`isFoodBorne`/`includeInHealthProblems`/`enumKey`）；
  三张语义词表的任何生产引用；饮食医嘱词扫描；bbox/seq 配对
- 不允许改变的既有行为：执行顺序 ① → ①a → ①b → ②；§0.3 的三条硬边界

## 6. 外部依赖与已确认决定

- 外部接口/凭证/测试数据：`schema/extraction_output.schema.json`、`AllergenGroups` 常量均已存在
- 产品已确认决定：Schema 不合法直接失败不重试；三张语义词表已整体下线（§4.4-③）
- 医疗审核结论：`MOLLUSK`/`SESAME` 未补齐，**不影响本任务编码**

## 7. 交付物

- 代码：`LlmAValidationPipeline` 及各校验器、三个扫描器 + 准入过滤 + 合并
- 测试：R1~R5、R7~R12、R19~R21a、R24~R26、R46
- DDL/Prompt/Schema/文档同步：如需改 Schema 必须同步 §4.2 示例并跑 R23
