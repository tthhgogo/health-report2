# AGENTS.md — AI 体检报告分析与菜品推荐

## 1. 文档真源与优先级

产品需求是 `体检报告分析需求.md`。技术真源是这两份，**不是** `AI体检报告分析与菜品推荐-完整技术方案V1_7_1.md`：

- `AI体检报告分析-精简设计方案V1.md` —— 产品与架构行为的真源。
- `AI体检报告分析-开发方案V1.md` —— 分层、类、接口契约、可执行规则。

冲突时优先级：

1. 本 `AGENTS.md`（工程与工具链约束）
2. `AI体检报告分析-开发方案V1.md`
3. `AI体检报告分析-精简设计方案V1.md`
4. `体检报告分析需求.md`
5. 已有仓库约定，只要不违反 1–4

> **`AI体检报告分析与菜品推荐-完整技术方案V1_7_1.md` 及全部 V1.5.x/V1.6.x/V1.7 文档
> 仅供历史追溯，永远不是判据。** 它们描述的是一套已被取代的架构：任务状态在 Redis、
> 双 Redis 实例、`diet_advice_cache` 表、LLM-B 在线生成建议内容、Lua 原子入队、
> 单页预览端点、模型调用重试。以上全部已废止，照做即为错误实现。

若某个未决事项会改变安全、持久化、接口契约或医疗/饮食推荐行为，不要自行选一条新规则。
把不冲突的部分实现掉，留清晰 TODO，并报告阻塞点。

## 2. 硬性工具链约束 —— 不得升级

- Java：**JDK 8 / source=1.8 / target=1.8**
- Spring Boot：**2.7.x**
- 使用 `javax.*`，**绝不**迁移到 `jakarta.*`
- 构建工具：保持仓库既有选择；新仓库用 Maven
- 既定技术栈：MyBatis-Plus、PDFBox 2.0.x、Apache POI、ofdrw、TinyPinyin、MySQL 8.0、
  Redis、xxl-job、Dify **1.6.0**、PaddleOCR、Amazon S3
- **不引入 Kafka**，队列用 Redis Stream + Consumer Group
- 未经明确批准不引入新数据库或知识库
- 未经明确批准不替换对象存储（S3 → OSS/MinIO 等）

### Java 8 兼容红线

禁止生成以下语法与 API：`var`、records、sealed classes、text blocks、
switch 表达式与模式匹配、`Map.of` / `List.of` / `Set.of`、`Stream.toList()`、
`Optional.isEmpty()`、`String.isBlank()`。

用 Java 8 替代：显式类型、可变集合构造、`Collectors.toList()`、`StringUtils` 等。

## 3. 架构不变量

### 3.1 流程与任务

- 逐个上传文件 → 点击分析 → 异步处理 → 轮询取四模块结果。
  **不得改成一次长同步 HTTP 请求。**
- 上传只创建未关联的文件记录并返回 `fileId`，**不创建也不接受 `taskId`**。
  `analyze` 提交 1~5 个有序 `fileIds`，创建 `taskId`，按提交顺序分配 0 起的 `fileIndex`，入队。
- **任务状态的真源是 MySQL `ct_health_report_task`，不是 Redis。** Redis 只存分析结果。
- 状态机**纯单向无回边**：`QUEUED → PARSING → EXTRACTING → ASSEMBLING → SUCCEEDED|FAILED`。
  删除用 `deleted_at` 正交标志表达，**不是**状态迁移。
- **「重新解析」没有专用接口**：用同一批 `fileIds` 重调 `analyze`，产生**新的 `taskId`**。
  文件绑定条件允许从「`FAILED` 且 `reanalyzable=1`」的任务上解绑。
- 入队用 `XADD`，**必须在创建任务的 MySQL 事务提交之前执行**。没有 outbox 表，没有 Dispatcher。

### 3.2 零重试

**全案不存在任何"执行失败后自动再跑一遍"的逻辑**：

- LLM-A 批次失败 → 整任务立即 `FAILED`
- Schema 校验失败 → 整任务立即 `FAILED`
- 过敏疑似漏抽 → 立即安全降级，不定向重试
- 队列消息投递失败 → 事务回滚，返回错误
- 任务永不重试、永不进 DLQ

唯一保留的是「等待」而非「重跑」：Worker 心跳、`QUEUED` 超时巡检。二者都只把任务置为 `FAILED`，
**不重新执行、不补投消息**。

### 3.3 持久化

- MySQL 业务表**只有三张**：`ct_health_report_task`、`ct_health_report_file`、`ct_dish_tag`。
  **没有 `diet_advice_cache`** —— 建议内容是硬编码常量。
- **表名一律 `ct_` 前缀；Java 类名保留前缀**：`ct_health_report_task` → `CtHealthReportTaskEntity`
  / `CtHealthReportTaskMapper` / `CtHealthReportTaskService`。不得使用概念别名。
- 数据库表**不得声明外键**。保留逻辑关系列与普通索引，关系有效性由 Service 层保证。
- **所有建表 DDL 的每个字段都必须声明字段级中文 `COMMENT`，每张表也必须有中文
  `COMMENT`。** 行尾 `--` 备注、Java 注释、字段名自说明或另外的数据字典都不能代替
  MySQL 字段 `COMMENT`。新建表、新增字段、迁移脚本与文档中的 DDL 示例均不得例外；
  字段注释必须说明业务含义，枚举/状态列同时说明允许值或指向定义。
- 每张表必须有且仅有这四个审计列：`create_by VARCHAR(50)`、`create_time DATETIME`、
  `update_by VARCHAR(50)`、`update_time DATETIME`，实体映射为
  `createBy` / `createTime` / `updateBy` / `updateTime`。
- **`create_time` 与 `update_time` 由数据库维护，代码永远不赋值。**
  `DEFAULT CURRENT_TIMESTAMP` 与 `ON UPDATE CURRENT_TIMESTAMP` 负责。
  实体上用 **`@TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)`**
  —— 该字段永不进入 insert/update 语句，select 不受影响。
  **禁止配置 `MetaObjectHandler` 自动填充这两列，禁止在任何 SQL 或 UpdateWrapper 里写
  `update_time = now()`。**
- **`user_id` 绝不可写入 `create_by` / `update_by`**，那两列写固定系统标识。
- 报告内容与解析/模型中间产物**不入 MySQL**。`ct_health_report_file` 只存
  文件技术元数据、云存储定位键、任务关联、非空 `user_id` 与过期时间。
  云存储定位字段固定为 `cloud_file_key`（实体字段 `cloudFileKey`）；Bucket/容器名由
  `S3FileStorage` 的部署配置提供，表内**不得存在 `s3_bucket` 或 `s3_key` 字段**。
- 数据库操作类是 Spring `@Service`，命名 `<TableName>Service`，**不建 `Mybatis*Repository` 包装**。
- 哈希、ID、枚举列显式 `ascii_bin`（MySQL 8 默认 `utf8mb4_0900_ai_ci` 大小写不敏感）。

### 3.4 Redis

- **只有一个 Redis 实例。** 没有 `task:{taskId}` 状态 Hash，没有墓碑 Key，没有 outbox。
- `result:{taskId}` TTL 2 小时；`q:analysis` Stream + group `g:worker`，消息体只含 `taskId`；
  `dish:tag:{enumKey}:{policyVersion}:{bizDate}` Hash，TTL 3 天。
- **结果可见性由 MySQL 单点决定**：Worker 先预写 Redis 结果，再 CAS 置
  `SUCCEEDED` + `result_visible=1`（带 `deleted_at IS NULL`）；CAS 失败即删掉预写结果。
  GET 接口必须先查 MySQL 可见性，再读 Redis。
- 打标缓存 **Redis 只是加速器，`ct_dish_tag` 才是真源**；未命中必须回源查库，
  否则预热失败当天推荐列表会整个空掉。
- 打标缓存**只追加不覆盖**。菜品变化产生新的 `dishHash` 即新 Field，旧的靠 TTL 过期。
- 在线路径对打标缓存与 `ct_dish_tag` 的**写入次数必须为 0**。
- 成功与失败路径都必须 `XACK` + `XDEL`，两者在同一次 pipeline 内。
- 陈旧 PEL 清理的 `minIdle` 必须大于 Worker deadline（取 15 分钟），且**只清 Stream 条目，
  不重新执行任务**。

### 3.5 模型角色

**只有两个模型角色，不存在 LLM-C：**

- **LLM-A**：在线，每任务必调，结构化抽取。分批 ≤8 页，批次**并行**执行。
- **LLM-B**：离线，菜品维度打标。在线链路对它的调用次数必须为 0。

**建议内容（食材清单、摄入量、搭配贴士、烹饪方式）是硬编码常量，没有生成模型、
没有内容管理后台、没有版本激活机制。**

- LLM-B 输入不含任何用户数据 —— 这是打标结果可跨用户复用的前提。
- 打标维度 **20 个**：11 食入性过敏原 + 9 饮食注意。数量由 §3.6 的枚举真源推导，不得手写。
  营养补充 9 个维度是**纯 Java 确定性交集匹配**，不调模型。
  吸入性过敏原 5 个不参与菜品链路。
- 缓存键必须含 `dishHash` 与 `tagPolicyVersion`
  （= `hash(modelVersion + promptVersion + tagRuleVersion)`）。少任一个都会产生静默的陈旧标签。
- 打标批大小 40。失败批次留空到下次 diff，**绝不写回 `NEUTRAL` 占位**。
- 重跑只补缺失标签，不覆盖同版本已通过校验的标签。

### 3.6 证据与解析

- **内容常量的真源是 `com.example.healthreport.constants` 下的 Java 常量类**
  （`AllergenGroups` / `AllergenExceptions` / `NutritionContents` / `DietRequirementContents`）。
  **没有 CSV、没有生成器、没有运行时加载**——改词表就是改代码，走 code review 与发版。
  这与本文件「词表与阈值硬编码、不做配置化」一致。
- **Schema 与 Prompt 是独立文件，靠契约测试与 Java 枚举保持一致**，不是生成出来的。
  必须有测试断言 `AllergenKey` / `NutritionKey` / `DietRequirementKey` 与
  两份 Schema 的 enum、两份 Prompt 的枚举表逐一相等——**对不上即构建失败**。
  前两轮评审抓到的 12/21 vs 11/20 不一致，根因就是三处各写各的、没人拦。
- **判定效果由字段位置表达，不靠布尔标记列。**
  `NutritionRule` 拆 `recommendableFoodList` / `displayOnlyFoodList`；
  `DietRequirementRule` **结构上没有可推荐字段**。
  字段放错位置会编译报错，布尔值填错不会。
- **审核状态只影响「规则是否生效」，不影响「枚举是否存在」。** 三层分开：
  **枚举身份**（Java 枚举 / Schema enum / Prompt 枚举表）不受 `reviewStatus` 影响，
  常量存在枚举就存在；**生效规则**只用 `REVIEWED` 的常量；
  **发布激活**按整个常量类原子生效，前置条件是该类**全部常量已裁决、无 `DRAFT` 残留**。
  审完一条就改一条会产生「半个过敏原维度」——看起来在防护，实际漏拦。
  `REJECTED` 的常量保留在类里但不生效，**必须出现在负向回归测试里**。
- **`displayOnlyFoodList` 绝不注入 LLM-B 提示词，也绝不参与营养交集。**
  它们只用于模块三展示。混进去等于「展示内容变成了推荐规则」，
  正是拆分 `verdictEffect` 要防的事。
- **LLM-B 的 `verdict` 枚举当前只有 `REJECT`。**
  `DietRequirementRule` 结构上没有可推荐字段，营养维度又由 Java 计算
  ——所以 LLM-B 的 20 个维度不可能产生推荐。这是类型保证的，不是约定。
  Java 侧对任何维度收到 `RECOMMEND` 一律按 `NEUTRAL` 处理并告警。
- **`constants` 包有 diff 但 `TagRuleVersion.VALUE` 未变 → 构建失败。**
  忘记 bump 的后果是静默的：预热 diff 会认为标签已存在而跳过，新规则永远不生效，
  不报错也不告警。
- `evidenceLevel` 决定是否进 Java 硬匹配：`DIRECT`/`LIKELY` → `SUBSTRING` 硬匹配；
  **`POSSIBLE` 只能配 `MODEL_ONLY`**，只作为线索进 LLM-B 提示词。
  `POSSIBLE + SUBSTRING` 是非法组合，单元测试必须拒绝——它会把「配方不保证含有」的词做成硬拒绝。
- **过敏例外只作用于菜名字段，食材表里的明确命中永远优先、不可被例外覆盖。**
  「鱼香肉丝」的例外只取消菜名中「鱼」的命中；该菜配料若另含鱼露，`FISH` 仍然 `REJECT`。
  「部分为」「视配方」这类条件性复合菜名不进例外表，归 `POSSIBLE + MODEL_ONLY`。
- **`matchMode` 三态语义固定**：`SUBSTRING`（子串，过敏 Layer 1）/
  `CANONICAL_EXACT`（整串相等，营养主料交集）/ `MODEL_ONLY`（不做 Java 匹配）。
  规范化**包含 ASCII 大小写折叠**——NFKC 不做大小写折叠，必须单独执行，否则 `xo酱` 命中不了 `XO酱`。
- **两个版本号，不要合并**：`displayContentVersion`（展示类改动，不重打标）与
  `tagRuleVersion`（影响打标的改动，进 `tagPolicyVersion`，触发全量重打标）。
  合成一个会让改一句展示文案也触发 20 个维度全量重打标。
- **三层职责：OCR / 解析器只负责识别，LLM-A 负责判断，Java 只负责简单判断。**
  解析器输出原子文本块 + 坐标，**不拼行、不切区域、不判断表格结构**；
  「这几个块属于同一个指标 / 同一个章节」由 LLM-A 判断（它看得到渲染图）；
  Java 只做确定性的字符串包含、集合交并、数值比较和排序。
  **新增任何 Java 逻辑前先问：这个判断能不能穷举输入并写成单测？不能就说明它属于 LLM-A。**
  典型反例是「按 Y 坐标聚类成逻辑行」——阈值靠猜，双栏/上下标/跨行单元格/旋转页全会翻车。
  唯一例外是过敏 Layer 1 的关键词硬匹配（安全红线必须由 Java 兜底），但它本身也是简单的子串包含。
- **`segmentIds` 是回切原文的凭据，是数组不是单值。** 解析器给每个原子块分配
  `f{fileIndex}-p{page}-s{seq}`，一经分配不再变化；模型只能原样返回，不得自行构造。
  一个条目的字段分散在多个块时，模型把它们**一起引用**；Java 把引用的块合并后再查字符串包含。
- **章节归属由模型标注 `sectionSegmentId`，Java 不划区间。**
  过敏筛查章节由模型给 `allergenSectionSegmentIds`（含它没抽出条目的数据行），
  Java 只在这些块里数结果标记。**但必须保留一条兜底**：该数组为空而全文有段命中
  `SECTION_TITLES` 时，直接 `ALLERGEN_SUSPECT_MISS`——否则模型漏圈整个章节就没人发现。
- **每个 segment 保存两份文本**：`rawText`（原始字符，用于展示与核对）与
  `normalizedText`（规范化后，用于送模型与一切字符串比对）。**展示的原文永远取 `rawText`。**
- **解析器不拼行。** 表格区按结构给单元格（Word）或按解析器原生块（PDF/OCR）输出，
  由模型引用多个块来表达一条指标——不要在 Java 里做逻辑行重建。
- **文本层判定逐页进行**，同一份 PDF 允许 `NATIVE` 与 `OCR` 页并存。
- **OCR 文本带 `segmentId` 一并送入 LLM-A**（这是 segmentId 回切机制的硬依赖）。
  两道补偿必须同时存在：提示词写明「文本与图像冲突时以图像为准」，
  且 OCR 段的包含性校验放宽为去空白子串或编辑距离 ≤ 1。
- **模型不生成任何全局序号。** `sectionIndex` / `orderInSection` / `sourceOrder` 一律由 Java
  按 `fileIndex → page → seq` 生成 —— 并行分批下模型不可能稳定生成文件级连续序号。
  模型只返回 `segmentId` + `itemIndex` + 局部分类。
- 按 magic number 判定格式，不信扩展名。DOCX 与 OFD 都是 ZIP，必须查容器内部结构。
- 渲染必须内存有界：渲染完立即编码为 JPEG 字节并释放 `BufferedImage`。
- **没有单页预览、没有预签名 URL、没有原图跳转端点。** 该功能已删除。

### 3.7 安全与降级

- Layer 1 过敏 Java 硬兜底**先于且独立于**模型打标，对全部在架菜品运行，
  与模型结果**取并集**，模型不可推翻。
- **LLM-B 响应用紧凑格式**：`neutralDishIds` + `hitList`。
  `neutralDishIds ∪ hitList` 必须**精确等于**本批全部输入 `dishId`——
  少一个、多一个、重复一个都判整批作废，不写库、不重试。
  **绝不把遗漏的菜静默补成 `NEUTRAL`。**
- 推荐与不推荐列表**各最多 3 道**。截断必须在全部维度合并裁决**之后**执行
  ——先截断再判过敏会把该拦的菜留下、该推的菜丢掉。
- **`TAG_MISSING` 与 `NEUTRAL` 是两回事。** 任一「可产生 REJECT 的维度」缺标签的菜
  **不进推荐列表，也不进不推荐列表**。缺失当成中立会让油炸菜进低脂用户的推荐列表。
- 三种降级：`PAGE_TRUNCATED` / `BATCH_UNREADABLE` 关闭模块三四；
  `ALLERGEN_SUSPECT_MISS` 关闭模块四。`partial_reason` 是多值。
- **全案不出任何提示、说明、警示文字。** 四个模块只展示内容、来源、空态句和底部声明。
  降级时静默隐藏模块，不解释原因。
- 词表匹配只对抽取出的 `conclusionText` 生效，**不对整段原文生效**；
  **最长短语优先**（「未见明显异常」不被「异常」抢先命中）；**「阴性」不在通用正常词表内**。
- 姓名只在 Worker 内存中用于同一性比对，**绝不入库、入 Redis、入日志、返回前端**。
- 词表与阈值是代码常量，**不进 Nacos/Apollo/数据库/运行时配置**。

## 4. 安全不变量 —— 违反即上线阻塞

1. LLM-A 缺顶层字段 **不等于**空数组。
2. `UNKNOWN != NONE`，不确定性必须显式保留。
3. **绝不**从指标异常推导饮食建议，除非报告明文写了。
4. 身份不一致只有两个结果：PASS，或 `FAILED / IDENTITY_MISMATCH`。**没有确认交互。**
   只有姓名/性别冲突才拦；同一人不同日期/机构正常合并；识别不出姓名的文件不参与比对。
5. 报告引用必须可回溯；`segmentId` 找不到或包含性校验不过的条目一律丢弃。
6. 过敏由 Java 硬兜底，模型不能推翻 Java 的过敏拒绝。
7. **阴性过敏原绝不进入**过敏提醒与菜品拦截（一张 30 项筛查表里 28 项是阴性）。
8. **过敏证据覆盖检查**必须按「章节区间」而不是「同一 segment」实现，
   且风险标记词表 `ADMITTED_RESULT_MARKS` 必须覆盖 `POSITIVE + BORDERLINE` 全部写法
   （阳性/强阳性/弱阳性/可疑/临界/(+)/±/(±)/(+/-)/+~+++，含全半角）
   ——只收「阳性/(+)/＋/强阳性」时，模型漏掉唯一一条弱阳性不会触发降级。
   先由标题 segment 延伸出 `allergenSectionRange`，再在区间内的数据行上扫描风险标记；
   **要求标题词与阳性标记出现在同一 segment 是无效实现**——一行一 segment 之后
   两者永远不同段，风险集合恒为空，整套 fail-safe 从不触发。
   有效覆盖只能来自 `resultStatus ∈ {POSITIVE, BORDERLINE}` 且回切通过的条目，
   `NEGATIVE` / `UNKNOWN` 不算覆盖；除集合包含外还要满足「准入条目数 ≥ 风险标记数」。
   不满足即 `ALLERGEN_SUSPECT_MISS` 并关闭模块四。
9. 过敏原条目回切失败被丢弃时同样触发 `ALLERGEN_SUSPECT_MISS`，不得静默丢弃后继续推荐。
10. 高危表述（低蛋白/限碘/低钾低磷/孕产儿童）强制转 `OTHER`，无论模型给了什么。
11. 缺失/无效的菜品标签绝不变成正面推荐。
12. 安全分类必须发生在建议键归一化之前。
13. 日志白名单：**绝不**记录报告原文、证据文本、OCR 文本、姓名、原始过敏或医嘱文本、
    健康数据、凭证、模型完整请求响应。`taskId` / `userId` 可用于关联，
    但不得与上述内容出现在同一条日志事件中，也不得进 URL 查询串或分享链接。
14. 归属校验失败必须返回与「任务不存在」**完全相同**的 `404 + RESULT_EXPIRED`，
    绝不返回 403 或任何可区分的码，否则 `taskId` 存在性可被探测。
15. 结果在用户删除后**绝不可重新出现**：Worker 的 CAS 带 `deleted_at IS NULL`，
    CAS 失败即删掉预写结果。Worker 还必须用 `resultPreWritten` / `resultPublished`
    两个标志，在 finally 里对「已预写未发布」幂等 DEL——只处理「CAS 返回 0」
    会漏掉「CAS 抛异常」和「后续通用异常」两条路径，健康数据会残留到 TTL。
16. 成功发布的 CAS 必须**同时**刷新 `access_expire_at` 与 `purge_at`，
    且两者基于同一个数据库时间表达式。只刷前者会让任务行在约 40 分钟后被清理，
    Redis 结果还在但已无法做归属校验，用户提前拿到 `RESULT_EXPIRED`。
17. **心跳只更新 `heartbeat_at`，绝不延长 `deadline_at`。** 巡检必须分别处理
    「超过硬截止」与「心跳陈旧」两个条件——只查心跳的话，心跳线程活着就永远不陈旧，
    任务可以无限超过 deadline。
18. `XACK` + `XDEL` 必须用 Lua 原子执行。**pipeline 不保证原子**，
    XACK 成功而 XDEL 失败时该条目已离开 PEL，陈旧巡检再也发现不了它，
    而 `XLEN` 又被用作背压深度。
16. S3 删除必须**先于**数据库文件行删除；S3 失败保留行等下一轮，
    否则会丢失对象定位信息，形成无法追踪的敏感文件。

## 5. 待实现占位符

以下五处只写接口 + `TODO` 空实现，抛 `UnsupportedOperationException`，
**绝不写假数据返回**（假数据会让上层测试通过而掩盖未实现）：

```
S3FileStorage          对象存储读写删
DifyClient             LLM-A / LLM-B 远程调用
PaddleOcrClient        OCR 远程调用
CurrentUserProvider    获取当前 userId
DishQueryService       查询当日在架菜品与食材
```

**不生成任何中间件或数据源配置类**：不写 `DataSourceConfig`、`RedisConfig`、
`MybatisPlusConfig`、`RedisTemplate` Bean 定义、连接池配置、`application.yml` 的中间件段。

## 6. 编码规范

- Java 源文件一律 **CRLF** 行尾，`.gitattributes` 为仓库强制源。
- 小而内聚的服务优于一个巨型编排类；DTO/domain/infra 边界显式。
- 变量名反映实际用途；集合变量必须以 `List` / `Map` / `Set` 结尾。
- 已知元素数量时按需初始化容量：`new ArrayList<>(sourceList.size())`。
  确实未知时才用默认构造，不得为满足规则编造容量。
- 用 Lombok `@Getter` / `@Setter` / `@Data` 替代手写访问器；
  含校验、归一化、防御性拷贝或业务逻辑的方法保留显式实现。
- 用 Lombok `@Slf4j`，**日志消息写中文**。记录生命周期、集成检查点、状态迁移、
  降级决策与异常。捕获后重抛或包装的非业务异常，错误日志必须把异常对象作为最后一个参数
  （`log.error("文件解析异常", exception);`），只记 `getMessage()` 不够。
  禁止 `System.out` / `System.err` / `printStackTrace`。日志字段严格遵守第 4 节白名单。
- **类、重要方法、重要代码路径必须有中文注释。** 类注释说明职责与边界；
  方法注释说明重要入参、返回、副作用或安全语义；行内注释解释非显而易见的业务理由、
  fail-safe 行为、状态迁移与降级决策，而不是复述代码。
  **每个枚举类型和每个枚举常量都必须有中文注释。**
- **所有控制流语句体一律加花括号**，包括单语句的 `if`/`else`/`for`/`while`/`do`。
- 枚举表达业务状态，核心逻辑不得用魔法字符串。
- 安全/业务规则常量集中在指定常量类中。
- 不对安全规则做聪明的抽象，代码要易于审计。
- 不随意新增生产依赖；确需新增要在最终报告中说明。
- 不在未同步更新测试与文档的情况下改动公开 API 字段语义。

## 7. 测试要求

每个实现任务：

1. 为改动的业务规则增删单元测试；
2. 先跑最小相关测试集，再跑模块/全量构建；
3. 保持 Java 8 可编译；
4. 必须包含负例与 fail-safe 用例，不能只有 happy path；
5. Redis/DB 集成优先写确定性集成测试。

必测回归清单见 `AI体检报告分析-开发方案V1.md` §11.1。以下几条无论如何不得缺失：

- LLM-A 缺字段 → 任务失败，不重试，不折叠成空数组；
- 阴性过敏原不进入任何过敏链路；
- 30 项筛查表漏掉唯一阳性项 → `ALLERGEN_SUSPECT_MISS` 且模块四不输出；
- `TAG_MISSING` 的菜既不进推荐也不进不推荐；
- 「乙肝表面抗体阴性，建议接种疫苗」不被正常词表误杀；
- 「未见明显异常」不被「异常」抢先命中；
- 左眼/右眼同值不被去重；
- 用户在 `ASSEMBLING` 阶段删除后，结果永远不可见；
- `SUCCEEDED` 后 2 小时内归属鉴权仍能通过（任务行不得提前删除）；
- 在线路径对 LLM-B 与打标缓存的写入次数为 0；
- 归属不符返回与任务不存在完全相同的响应；
- `create_time` / `update_time` 在 insert 与 update 的 SQL 中都不出现；
- 解压炸弹、超大像素图片、XXE 样本被拒。

## 8. 完成的定义

任务未完成，直到：

- 说明改了什么；
- 列出改动的文件；
- 给出跑过的测试/构建命令及其结果；
- 确认 Java 8 / Spring Boot 2.7.x 兼容；
- 指出任何假设、未决项、跳过的测试或依赖问题；
- 对安全、数据生命周期、日志、鉴权、健康数据意外持久化做最终 diff 复查。

**未经用户明确要求，不得 commit 或 push。**
