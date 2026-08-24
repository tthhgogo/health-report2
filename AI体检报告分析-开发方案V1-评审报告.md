# AI 体检报告分析开发方案 V1——开发基线评审报告

> 评审对象：`AI体检报告分析-开发方案V1.md` 及《开发方案评审指令》指定的配套材料  
> 评审日期：2026-08-24  
> 判定标准：不了解历史背景的后端开发，仅依据当前文档，能否写出正确、安全、可联调的代码。

## 结论

**当前版本不能直接作为冻结后的开发基线。**

主要原因不是已知的五个外部占位实现或待医务填写的内容常量，而是核心实现规则本身仍有断点：PDF/OCR 的逻辑行算法会在常见版式下产生错误 segment；过敏阳性漏抽检测的示例代码与文字规则不一致且实际会失效；任务成功后的生命周期、Worker 硬截止和终态所有权没有闭合。此外，过敏 Layer 1 的机器可读数据结构、失败码和结果 DTO 仍存在互相冲突或缺失，开发人员需要自行猜测。

建议先修复本报告 P0，再允许并行开发解析层、任务编排和模块四；P1 可在接口冻结前一并关闭。

## 已验证通过的重点项

### MySQL/Redis 结果可见性协议的核心时序成立

对开发方案 §4.2 指定的五种时序逐一推演后，**没有证伪“删除后结果不会再次对 GET 可见”这一核心性质**：

| 时序 | 结论 | 原因 |
|---|---|---|
| Worker 预写 → DELETE → Worker CAS 失败 → Worker 在 DEL 前崩溃 | 可见性安全 | `deleted_at` 已落 MySQL，GET 前置判断失败；残留值最多存活至 Redis TTL |
| DELETE 与 Worker CAS 同时发生 | 可见性安全 | 两个更新竞争同一 InnoDB 行锁；CAS 先赢则 DELETE 随后隐藏并删除，DELETE 先赢则 CAS 的 `deleted_at IS NULL` 不成立 |
| `deleted_at` 已写、Redis 尚未 DEL 时 GET | 可见性安全 | GET 先查 MySQL，不会读取 Redis |
| Redis 主从切换导致 DEL 丢失 | 可见性安全 | Redis 残值仍受 MySQL 删除标志遮蔽，且有 2 小时 TTL |
| 同一 taskId 被两个 Worker 处理 | 可见性安全 | 只有一个 Worker 能把 `QUEUED` CAS 为 `PARSING` |

该协议外围仍有两个问题：成功 CAS 没有同步更新 `purge_at`，以及预写后出现异常时没有统一删除预写值，分别见 P0-3、P0-4。这两点不推翻可见性协议本身，但会破坏生命周期和数据清理契约。

### 指定框架 API 的核查结果

- 未发现 `var`、`List.of`、`Optional.isEmpty`、`String.isBlank`、文本块等 Java 8 不支持的语法。
- Spring Data Redis 2.7 的 `StreamOperations.createGroup/read/pending`、`RedisStreamCommands.xAck/xDel` 示例签名可用；问题在 pipeline 不具备原子性，而不是签名不存在。参见 [Spring Data Redis 2.7 StreamOperations](https://docs.spring.io/spring-data-redis/docs/2.7.15/api/org/springframework/data/redis/core/StreamOperations.html) 与 [RedisStreamCommands](https://docs.spring.io/spring-data-redis/docs/2.7.15/api/org/springframework/data/redis/connection/RedisStreamCommands.html)。
- `@XxlJob` 标注无参 `void` 方法是当前 xxl-job 方法任务支持的写法。参见 [xxl-job 官方示例](https://github.com/xuxueli/xxl-job/blob/master/xxl-job-executor-samples/xxl-job-executor-sample-frameless/src/main/java/com/xxl/job/executor/sample/frameless/jobhandler/SampleXxlJob.java)。
- `ZipSecureFile.setMinInflateRatio`、`setMaxEntrySize` 和 `IOUtils.setByteArrayMaxOverride` 在 POI 4.1.x 已存在；但 `setByteArrayMaxOverride` 只限制单次分配，不限制总分配量，不能代替任务内存上限。参见 [POI 4.1 IOUtils](https://poi.apache.org/apidocs/4.1/org/apache/poi/util/IOUtils.html) 与 [POI 配置说明](https://poi.apache.org/components/configuration.html)。
- `ImageIO.getImageReaders` 后先读 `getWidth/getHeight` 的方向能够在像素解码前读取尺寸；当前缺陷是阈值和后续资源上限不完整，不是这段 API 调用必然触发全量解码。

## P0 必须修复

### 1. PDF/OCR 按 Y 坐标聚行不能保证得到“逻辑行”

**位置** `AI体检报告分析-开发方案V1.md` §6.3，第 1068～1085 行

**问题** 文档规定“按 Y 坐标聚类，容差 = 该页平均字号 × 0.6；同一行按 X 排序后拼接”，并据此断言各格式都能得到“一行一个 segment”。这个断言不成立：

- PDFBox 的 `TextPosition#getXDirAdj/getYDirAdj/getHeightDir` 能给出方向校正后的字形坐标，但字符的 Y 往往是基线位置，不等同于逻辑行中心。PDFBox 官方坐标示例也需要显式 `setSortByPosition(true)` 并逐个处理 `TextPosition`，见 [PrintTextLocations](https://github.com/apache/pdfbox/blob/trunk/examples/src/main/java/org/apache/pdfbox/examples/util/PrintTextLocations.java)。当前方案未定义具体取值、页面旋转和书写方向处理。
- `10⁹/L`、脚注、上下箭头等上下标会偏离主基线，可能被拆成独立 segment；小字号脚注又会拉低“全页平均字号”。
- 跨行单元格会被拆成两行，指标名与数值/结论不再同段。
- 左右双栏各有一张指标表时，同一 Y 上的两条指标会被拼为一个 segment；按 X 排序只能决定拼接顺序，不能识别两个独立表格区域。
- OCR 没有 PDF 字号，只有 block bbox；复用“平均字号 × 0.6”没有可计算定义。OCR block 可能本身是一行、一个单元格或一个段落，粒度也不等同于 PDF 字符。
- DOCX 的 `<w:tr>` 是结构化表格行，而 PDF 的视觉基线聚类只是视觉行，二者证据粒度并不天然等价。
- 跨页表头可以作为独立 segment，但方案没有定义续页没有重复章节标题、重复表头被误判为章节标题、以及标题前指标应归入哪个默认分组。

**后果** 双栏报告会把两项指标串成一段，模型可能交叉配错名称和值；上下标或多行单元格会让五字段不在同一 segment，随后被 §7.5 整条丢弃。模块一的正确性依赖该算法，因此不能把它留给开发人员自行发挥。

**建议** 把 §6.3 改成可实现的两阶段重建协议：

1. PDF 每页使用 `PDFTextStripper#setSortByPosition(true)`，在 `writeString` 中采集 `getXDirAdj()`、`getYDirAdj()`、`getWidthDirAdj()`、`getHeightDir()`、字体大小和旋转角；先按页面旋转归一化成字形 bbox。
2. 先按大水平空白带、重复列边界或表格线切成独立 `columnRegion/tableRegion`，**区域之间绝不聚成同一行**。
3. 区域内使用“字形 bbox 的垂直重叠率 + 基线差”聚行，阈值取该区域字形高度中位数，而不是全页平均字号；把明显小字号的上下标附着到最近主基线。
4. 按 X 间距恢复 cell span；连续视觉行若列边界一致、下一行只有某些文本列且无数值列，则按明确的续行规则合并成一个 `logicalRow`。
5. OCR 单独用 bbox 高度中位数和垂直重叠率实现，不共用 PDF 字号阈值；DOCX/DOC 保留结构化 `<w:tr>` 路径。各适配器最终都输出 `logicalRowId + cellTextList + rawText`，而不是假定底层算法相同。
6. 无法可靠分栏/合行的页面应安全降级：允许一个指标引用同一 `logicalRowId` 下的多个 evidence segment，或关闭该页指标抽取；不能把错误拼接的行当作已验证证据。
7. 在进入编码前用至少“双栏表、上下标、跨行单元格、旋转页、续页表头、OCR block 粒度不同”六类真实样本固化输入和期望 segment。当前 §11 只有一句验收描述，不足以决定算法。

### 2. 过敏证据覆盖代码实际检测不到大多数阳性漏抽

**位置** `AI体检报告分析-开发方案V1.md` §7.7，第 1304～1338 行；`constants/README.md` §2.4；`schema/llm_a_output.schema.json` allergens 定义

**问题** 示例代码有两处与文字规则直接冲突：

1. `riskSegmentIdSet` 要求**同一个 segment**同时包含 `SECTION_TITLES` 和 `POSITIVE_MARKS`。一行一个 segment 后，章节标题通常是“过敏原筛查”，数据行是“虾蟹类 阳性(+)”；两者不会处于同一 segment，风险集合因此为空。
2. `coveredSegmentIdSet` 直接从完整 `allergenList` 取 segmentId，没有过滤 `POSITIVE/BORDERLINE`。Prompt 和 Schema 又要求完整返回阴性项，所以同一 segment 中任意 `NEGATIVE` 或 `UNKNOWN` 条目都可能被错误当成有效覆盖，与第 1335～1336 行“必须关联准入条目、UNKNOWN 不算覆盖”相反。

另外，`POSITIVE_MARKS` 当前只有“阳性、(+)、＋、强阳性”，没有把 `可疑/临界/±` 作为明确风险标记；而 §7.7 又规定这些 `BORDERLINE` 必须从严准入。只扫描显式阳性也无法发现“模型漏掉整条过敏数据行”的情况。

**后果** 典型筛查表“标题一行、每个过敏原一行”会让风险集合始终为空。模型漏掉唯一阳性项时，模块四仍会生成菜品推荐，正好重现上一轮 3.12 要解决的 fail-open 场景。

**建议** 明确并实现以下顺序：

1. 先按章节标题建立 `allergenSectionRange`，从标题 segment 延伸到下一个同级标题；再在该范围的数据行内扫描结果标记，不能要求标题词和结果词同段。
2. 风险标记至少覆盖 `阳性/(+)/+~+++ /弱阳性/可疑/临界/±` 及全半角变体；匹配对象是结果列或归一化后的整行。
3. `coveredSegmentIdSet` 只能来自**完成 segment 存在性、字段回切且 `resultStatus ∈ {POSITIVE, BORDERLINE}`** 的条目。`NEGATIVE/UNKNOWN` 不能覆盖风险行。
4. 对可识别为过敏筛查数据行的 segment 统计“候选行数/结果标记数/LLM 返回条数”。候选行未得到任何条目、结果列无法识别、或 OCR 丢失结果标记时，统一加入 `ALLERGEN_SUSPECT_MISS`；宁可关闭模块四，不把“扫描不到标记”解释为安全。
5. 一行含多项时按 `segmentId + itemIndex` 对齐，至少保证风险标记数量不大于有效准入条目数量；不能只比较 segmentId 集合。

### 3. 成功发布没有更新 `purge_at`，两小时结果窗口实际仍会在约 40 分钟中断

**位置** `AI体检报告分析-开发方案V1.md` §3.1 第 337～340 行、§4.2 第 538～543 行、§5.2 第 863～868 行

**问题** 创建任务时写入 `access_expire_at = now+30min, purge_at = now+40min`。文档随后规定成功时把 `access_expire_at` 改为成功时刻 +2h，但成功 CAS 的赋值列表没有更新 `purge_at`。§3.1 虽写了公式，却没有落实到任何 Service 方法或 SQL。

**后果** `ResourceCleanupJob` 按初始 `purge_at` 物理删除任务行后，GET 无法再做归属和 `result_visible` 校验；Redis 虽仍有结果，用户会在大约 40 分钟后提前得到 `RESULT_EXPIRED`。上一轮 3.5 的生命周期问题因此没有真正关闭。

**建议** 成功发布必须用一条 CAS 同时写：

```text
status = SUCCEEDED
result_visible = 1
access_expire_at = db_now + 2h
purge_at = db_now + 2h + 10min
deadline_at = null（或保留为审计值，但清理逻辑不得误用）
```

`access_expire_at` 和 `purge_at` 应基于同一个数据库时间表达式，避免应用时钟与 MySQL 时钟产生边界漂移；增加“成功 41 分钟后仍可 GET、2 小时后才失效”的确定性事务测试。

### 4. Worker 示例没有形成单一终态写入者，也没有真正执行 10 分钟硬截止

**位置** `AI体检报告分析-开发方案V1.md` §7.2 第 1164～1173 行、§8.4 第 1498～1532 行、§8.5 第 1537～1543 行

**问题** 当前伪代码无法直接实现且存在状态机漏洞：

- `process(String taskId)` 中使用了未入参、未声明的 `recordId` 和 `currentStatus`；没有定义 consumer loop、consumerName 唯一规则以及消息到主流程的上下文。
- 除领取和最终成功外，`PARSING → EXTRACTING`、`EXTRACTING → ASSEMBLING` 的 CAS 返回 0 后如何停止没有规则。若删除或巡检先置终态，开发人员可能继续调用 Dify、组装并预写结果。
- `deadline_at = now+10min` 只在领取时写入，没有任何 Job 检查 `deadline_at < now`。心跳线程持续更新时，15 分钟“陈旧心跳”条件永远不成立，因此任务可以无限超过 deadline。
- PEL 清理按消息投递后的 idle 15 分钟判断，不看任务心跳；如果硬 deadline 没执行，仍在运行的任务会被 XACK/XDEL。
- 4 个批次“任一失败但等待其余跑完”的 Future 汇合方式没有定义。若每个回调各自写 FAILED，会重复争抢终态；若主线程 `join` 的异常处理不当，可能漏等或丢失真实失败原因。
- `heartbeatScheduler.start(taskId)` 位于 `try` 之前。若 start 已注册 Future 后抛异常，finally 不会执行；`stop` 是否 cancel、是否从内部 Map 移除也没有契约。
- Redis 预写成功后，只处理了“成功 CAS 返回 0”的 DEL；若 CAS 抛数据库异常或后续通用异常，catch 置 FAILED，但预写结果没有统一删除。

**后果** 删除后的任务仍可能继续消耗 OCR/LLM 配额；任务可能超过 10 分钟仍保持执行态；心跳线程泄漏；失败任务在 Redis 残留健康数据到 TTL；不同开发人员会写出不同的终态竞争逻辑。

**建议** 给出完整的 `WorkerMessageContext(recordId, taskId, consumerName)` 和唯一编排模板：

1. 只有消费主线程/任务编排线程能写业务终态；批次 Future 只返回 `BatchOutcome`，不得写数据库。
2. 每个阶段 CAS 必须检查影响行数，0 行立即终止；终止前若已预写则 DEL。
3. 巡检条件改为“执行中且 (`deadline_at <= db_now` **或** heartbeat 陈旧)”，前者写 `FAILED/EXECUTION_TIMEOUT`，后者写 `FAILED/SERVER_ERROR`。心跳只能更新执行中且未删除的任务，不能延长 deadline。
4. 使用 `CompletableFuture.allOf` 或等效 barrier 等待全部批次完成，再由主线程一次性裁决；明确失败优先级和唯一 failCode。
5. `ScheduledFuture<?> heartbeatFuture` 在 try 内创建，在 finally 中 `cancel(false)` 并移除注册；无论 start、业务还是 stop 失败，都必须进入 ACK 清理路径。
6. 维护 `boolean resultPreWritten` 和 `boolean resultPublished`；finally 中对“已预写但未发布”的情况幂等 DEL。
7. `ackAndDelete(recordId)` 必须由包含 recordId 的外层 finally 调用；初始 CAS 为 0 也要走同一清理出口。

### 5. 过敏 Layer 1 只有一句“取并集”，没有可实现且一致的数据契约

**位置** `AGENTS.md` §3.6/§4；开发方案 §9.1 第 1682～1689 行、§10 第 1852～1858 行；`constants/README.md` 第 31～80、130～136 行；`constants/内容常量草案.md` §1～§3；`allergen_display_split.csv` 第 1～3 行

**问题** 最高优先级的 `AGENTS.md` 要求 Layer 1 Java 过敏兜底“先于且独立于模型，对全部在架菜品运行”。开发方案只写“LLM-B + Java 关键词取并集”和“从 CSV 派生”，没有给出匹配输入、例外词、证据等级、匹配顺序和伪代码。配套材料又互相冲突：

- `constants/README.md` 声称 CSV 包含 `matchMode/evidenceLevel`，但当前 CSV 只有 `allergenKey,matchWord,displayName,bucket,判定说明` 五列。
- README 仍写“12 个食入性枚举、每组 5～8 个词”，开发方案和正式 Schema 是 11 组、CSV 全量 73 词。
- README 的 `NormalStatementWords` 仍含“阴性”，违反 `AGENTS.md` 和开发方案“不含阴性”的硬规则。
- 内容草案要求 `DIRECT/LIKELY/POSSIBLE`、`EXACT/MODEL_ONLY` 和例外词典，并计划迁移为两个 registry；这些 registry 和字段当前均不存在。
- CSV 第 3 行写“不得增删 matchWord”，而 `AGENTS.md` 明确它只是可经评审扩充的参考基线。
- LLM-B Prompt 仍使用旧的 `recommendIngredients/avoidIngredients/cookingTips` 占位符，开发方案和内容草案已经把饮食内容拆成六类字段。

**后果** 一名开发可能把 73 个词全部做无边界子串硬拒绝，导致“鱼香肉丝”“蟹味菇”等误拦；另一名开发可能按 README 另造 12 组、每组选 5～8 个词，造成漏拦。Layer 1 是模型失误后的最后安全线，不能依赖开发者自行裁决。

**建议** 在编码前定稿一个实际存在的机器可读 registry，并由它生成 Java/Schema/Prompt：

```text
allergenKey, matchWord, displayName, bucket,
evidenceLevel(DIRECT|LIKELY|POSSIBLE),
matchMode(EXACT|ALIAS|MODEL_ONLY), foodBorne, exceptionWords
```

明确 Layer 1 顺序为：规范化菜名和**全部**食材名 → 先匹配例外词 → 仅对 `DIRECT/LIKELY` 且非 `MODEL_ONLY` 的词做 Java REJECT → 与 LLM-B REJECT 取并集 → 模型永远不能覆盖 Java REJECT。然后删除 README 的 12 组、旧字段和“阴性”残留，更新 LLM-B Prompt 的六类占位符。若暂不采用草案的新 registry，也必须在开发方案内明确当前五列 CSV 的哪一列决定硬匹配，不能同时保留两套解释。

### 6. 上传和解析资源上限仍允许在校验前耗尽堆或线程

**位置** 开发方案 §5.2 第 829～837 行、§6.1～§6.2 第 963～1027 行、§6.5 第 1120～1129 行

**问题** 上传顺序是“先读取字节，再按真实格式检查大小”。在没有 multipart 请求级硬上限时，攻击者可以先让应用把超大请求读入内存，业务校验才发现超限。即使文件大小合法，当前资源上限也未闭合：

- 图片允许 8000 万像素；常见 `BufferedImage` 实际可能按 4 字节/像素占约 320MB，一张图即可显著压迫堆，更没有乘以 Worker 并发后的总预算。
- PDF/OFD 渲染只写了“逐页释放”和一个 2000×2800 示例，没有规定最大页宽高、最大渲染像素或 DPI；恶意超大 MediaBox 仍可先分配巨图。
- `IOUtils.setByteArrayMaxOverride(50MB)` 是单次分配上限，不限制 POI 任务总分配；`MAX_TOTAL_SIZE=200MB` 对 POI/ofdrw 内部解压没有对应实现。
- “Future 超过 5 分钟失败”不能保证 PDFBox/POI/ofdrw 线程响应中断。主流程可以失败，但不可中断的解析线程仍会占用 CPU/内存，持续任务会耗尽线程池。
- OFD 防护仍写“需实际查证”，这不是可直接编码的规则。

**后果** 未认证上传接口可被超大 body、巨像素图片、超大 PDF 页面或复杂矢量内容耗尽堆/解析线程；业务大小限制和 5 分钟超时都可能在资源已经消耗后才生效。

**建议** 不需要在本工程写配置类，但必须把外部配置和代码内限制写成接入契约：

1. multipart 层的单请求硬上限必须先于 Controller 生效；Controller 再使用有界流读取，先读少量 magic bytes 判格式，随后最多读取该格式上限 +1 字节，禁止无界 `getBytes()`。
2. 按部署堆和 Worker 数反推 `MAX_DECODED_PIXELS_PER_TASK`；对 JPEG/PNG、PDF/OFD 渲染页使用同一个任务像素预算，并给出明确数值。渲染前先校验 MediaBox × DPI 的目标像素。
3. PDFBox 使用有界内存/临时文件策略；POI 同时设置 entry、text、单次 allocation 限制，并在外层实际流式累计 entry 数和总解压字节；OFD 必须在交给 ofdrw 前完成相同预扫描。
4. 解析放到专用有界线程池。超时后任务立即终态且不再接收该线程结果；对经实测不响应中断的解析器，必须限制单任务并发和进程级资源，不能声称 `Future.cancel` 已释放资源。

### 7. 对外错误码和结果 DTO 仍不足以完成联调

**位置** 开发方案 §5.1 第 790～816 行、§8.7 第 1619～1673 行；设计方案 §2.5 第 280～289 行

**问题** ErrorCode 表只有上传类错误、`RESULT_EXPIRED` 和 `SERVER_ERROR`，但 Worker 明确会写 `NOT_HEALTH_REPORT`、`UNREADABLE`、`IDENTITY_MISMATCH`、`PAGE_LIMIT_EXCEEDED`，设计方案还定义 `FILE_EXPIRED`、`EXECUTION_TIMEOUT`。开发方案没有给这些 failCode 的 HTTP/任务状态接口语义、用户 message 和 `reanalyzable` 映射。

结果结构也不是正式契约：`adviceModule` 只有注释占位，`dishModule` 只展示部分字段，没有给 allergen/nutrition/diet item、拒绝标签、来源字段、`OTHER`、null/空数组约束的完整 DTO 或 JSON Schema。五个外部接口虽允许 TODO，但其入参模型 `LlmARequest/LlmBRequest/OcrBlock/DishView/DishIngredientView` 也只出现名称，没有字段定义。

**后果** 后端无法确定失败任务应该返回哪个 message 和是否允许重解析；前后端、Dify/OCR/菜品接入方会各自猜字段名与 null 语义，直到联调才发现不兼容。

**建议** 在开发方案内补齐：

- `TaskStatus/Stage/FailCode/PartialReason` 枚举及每个 failCode 的 message、`reanalyzable` 和状态接口响应；核心逻辑改用枚举而非魔法字符串。
- 为五个 REST 接口提供完整 Java DTO 或 JSON Schema，尤其补全模块三、模块四和所有 null/空数组/字段必填规则。
- 为五个占位接口补全请求/响应视图字段、长度/单位/顺序约束；实现仍可保持 TODO。
- 把 `prompt/` 和 `schema/` 加入契约测试，保证文档示例可被正式 Schema 接受。

## P1 应当修复

### 1. 5 分钟 QUEUED 超时与固定深度 200 没有容量关系，会误杀正常排队任务

**位置** 开发方案 §4.4 第 627～669 行、§7.2 第 1164～1170 行、§8.5 第 1537～1541 行

**问题** `MAX_DEPTH=200` 是固定值，Worker 数 `W=floor(C/4)` 尚未确定，任务最长模型耗时按 180 秒设计。即使队列和 Worker 完全正常，只要 `W` 较小，第 200 个任务也远不可能在 5 分钟内领取，巡检会把它置 FAILED。

**后果** 系统会先接受任务，再在正常负载下批量返回 `SERVER_ERROR`；零重试使这些任务只能由用户重新发起。

**建议** 保持零重试不变，但必须让背压阈值由可验证容量推导：以“当前运行数 + 等待数、W、解析/模型 P95、5 分钟领取 SLA”计算最大可接收等待数，保证所有已接受任务在超时前有领取容量。队列阈值和 queued timeout 必须使用同一公式及同一组部署参数；在 C 未提供前不能把 200 作为代码常量。

### 2. XADD 在 MySQL 事务内缺少超时边界，ACK+DEL 的 pipeline 也不是原子操作

**位置** 开发方案 §4.4 第 650～689 行、§5.2 第 863～868 行

**问题** 按已定方案，XADD 必须在提交前执行；但文档没有要求 Redis command timeout、MySQL transaction timeout 和连接池等待上限。Redis 阻塞会让文件行锁和数据库连接被长时间占用。另一方面，pipeline 只减少网络往返，不保证 XACK 与 XDEL 原子；仍可能出现 XACK 成功、XDEL 失败，且该消息已不在 PEL，当前 stale PEL 清理不会再发现它。

**后果** Redis 抖动可能拖垮 MySQL 连接池；已确认但未删除的 Stream 条目可永久累积，`XLEN` 又被用作背压深度，最终持续拒绝新任务。

**建议** 明确“Redis 命令超时 < analyze 事务超时 < HTTP 超时”，XADD 超时立即抛异常回滚 MySQL。ACK+DEL 改为 Redis `MULTI/EXEC` 或一段只做 XACK+XDEL 的原子脚本；若仍保留 pipeline，必须有能发现并删除已 ACK 残留条目的定期清理，不能声称 pipeline 已消除该失败窗口。

### 3. 消费组初始化把所有 RedisSystemException 都当成 BUSYGROUP

**位置** 开发方案 §4.4 第 635～647 行

**问题** `initGroup()` 捕获任何 `RedisSystemException` 后都打印“消费组已存在”。认证失败、连接失败、命令不支持等也会被吞掉。

**后果** 应用可能以“初始化成功”状态启动，随后所有 Worker read 持续失败；QUEUED 任务只能等待 5 分钟后失败。

**建议** 只识别 Redis 返回的 BUSYGROUP 错误并忽略；其他异常让应用启动失败或健康检查失败。`createGroup` 在 Spring Data Redis 2.7 会创建不存在的 Stream，这部分 API 无需另造逻辑。

### 4. DDL 声称 ID/哈希/枚举使用 `ascii_bin`，实际只有菜品标签三列设置了

**位置** 开发方案 §3.1 第 252～350 行

**问题** 第 349 行要求哈希、ID、枚举列显式 `ascii_bin`，但 `ct_health_report_task.task_id/status/stage/fail_code`、`ct_health_report_file.file_id/task_id/status/content_hash` 以及菜品表的 verdict/evidence/model/prompt/content version 都继承表级 `utf8mb4` 默认排序规则。

**后果** DDL 与 `AGENTS.md` 明文约束不一致；UUID、hash 和枚举会按大小写/重音不敏感规则比较，唯一性和等值查找语义不再是文档声称的二进制精确匹配。

**建议** 对所有机器标识列统一指定 `CHARACTER SET ascii COLLATE ascii_bin`（或经项目确认统一使用 `utf8mb4_bin`），并用实际 DDL 测试大小写变体不能互相命中。

### 5. Maven 依赖仍是版本范围，不能保证示例与最终依赖组合一致

**位置** 开发方案 §1.1 第 57～86 行

**问题** `mybatis-plus 3.5.x`、`POI 4.1.x / 5.2.x`、`PDFBox 2.0.x`、json-schema-validator“Java 8 兼容”、ofdrw“按实际可得”都不是可复现构建；同时正式 Schema 使用 draft 2020-12，需要选择确实支持该 draft 且兼容 Java 8 的 networknt 版本。

**后果** 不同开发人员会得到不同依赖树；安全默认值、Schema 方言支持和解析行为可能不同，真实项目无法以文档中的代码片段做编译基线。

**建议** 给出完整 Maven 坐标和精确版本，使用 Spring Boot 2.7.18 dependency management 后锁定其余未托管依赖；至少增加一次 Java 8 `mvn test` 契约构建。POI 三个示例 API 在 4.1.x 可用，但其安全阈值是 JVM 全局静态值，仍需在启动阶段一次性设置并禁止请求内修改。

### 6. OCR 编辑距离规则对短字段会退化为几乎无约束匹配

**位置** 开发方案 §7.5 第 1248～1274 行

**问题** 所有字段统一使用“子串不中则编辑距离 ≤1”。对 `H/L`、单字符单位、短数值等长度 1～2 的字段，允许一次编辑意味着无关字符也能通过；代码也没有说明 null 字段是否跳过和匹配窗口如何截取。

**后果** 模型把短字段抄错或构造出来时仍可能通过“原文包含性校验”，削弱证据回切价值。

**建议** 分字段定义规则：null 不校验；数值仅允许已知 OCR 形近字符映射和受限小数点/空格差异；名称、结论要求达到最小长度后才允许编辑距离，并使用长度归一化阈值；长度 ≤2 的字段禁止通用编辑距离。保留“图像优先”的已定决策，但不能用同一模糊规则覆盖所有字段。

### 7. Prompt/Schema 仍残留 `clauseIndex`，零重试下会放大为整任务失败

**位置** `prompt/llm_a_extract.md` 第 171～175 行；`schema/llm_a_output.schema.json` 第 292～297 行

**问题** Prompt 先写“共享 segmentId 和 clauseIndex”，下一行又改成不同 `itemIndex`；Schema 描述也仍写 `clauseIndex`，但对象只允许 `itemIndex` 且 `additionalProperties=false`。

**后果** 模型若遵循前一句返回 `clauseIndex`，正式 Schema 会拒绝整个批次；全案零重试使任务直接失败。

**建议** 删除所有 `clauseIndex` 残留，只保留 `itemIndex`，并用 Prompt 示例输出跑正式 Schema 的自动契约测试。

### 8. xxl-job 的两个“单机串行”不能保证两个不同 Handler 互斥

**位置** 开发方案 §8.5.1 第 1580～1590 行、§9.9 第 1833～1844 行

**问题** xxl-job 的阻塞策略约束同一个 Job 的重复触发，不会让 `dishTagPrewarmJob` 与 `dishTagCleanupJob` 两个不同 Handler 自动互斥。仅靠时间错开也挡不住预热超时、补跑或人工触发。第 1843 行“用 xxl-job 的分片串行保证”没有对应可配置机制。

**后果** 仍可能发生上一轮 3.14 描述的时序：预热 diff 看到旧标签而跳过，清理随后删除它，当天变为 `TAG_MISSING`。

**建议** 两个 Job 使用同一个 Redis 互斥锁 Key（限定 owner token 和最大租期，释放时校验 owner），或合并为同一 Handler 内顺序执行；同时明确清理如何通过 `DishQueryService.listAllOnShelfDishes(date)` 查询连续 30 天历史。无需引入新中间件。

### 9. Schema 只做结构校验，缺少必要的全局语义上限和一致性裁决

**位置** `schema/llm_a_output.schema.json` reportOverview/数组上限；开发方案 §8.1、§8.7

**问题** Schema 允许 `abnormalCount > totalCount`；每批允许 500 个 indicators，4 批合并后最多 2000 个，但结果接口声称指标上限 500且不分页。章节标题、总览数字和 `itemIndex` 重复等语义校验也没有出现在 §7.4 的明确清单中。

**后果** 结构合法但语义不合法的模型输出会进入结果页，或产生远超接口声明的结果体。

**建议** 在 Java 校验链加入并写明：overview 非负且 abnormal≤total；每种数组合并后的全局上限及超限处理；同一 segment 内 itemIndex 的唯一性；sectionTitleId 必须存在且按文件归属；无章节标题时的默认分组。超限是整任务失败还是安全截断必须统一规定，不能由开发自行选择。

## P2 建议

### 1. 核心状态仍以字符串示例表达，容易违反 AGENTS 的枚举要求

`casStatus` 和 Worker 全部传入 `"QUEUED"/"PARSING"` 等魔法字符串，`FileFormat`、`TagState` 等枚举也没有逐常量中文注释。建议正式代码签名使用 `TaskStatus/TaskStage/FailCode` 枚举，持久化时显式映射字符串；这同时减少非法迁移拼写错误。

### 2. 审计操作者的固定值和写入点没有定稿

开发方案已正确规定 `create_by/update_by` 不得写 userId，但没有列出三张表分别使用什么固定标识，也没有说明条件更新如何同步设置 `update_by`。建议定稿如 `HEALTH_REPORT_API`、`HEALTH_REPORT_WORKER`、`DISH_TAG_JOB`，并写入每个 INSERT/CAS 模板。

### 3. 文档中的定时任务数量有残留

§8.5 和注册参数表实际列出 5 个 Job，§12 阶段 2 仍写“四个定时任务”。应统一为 5 个，避免开发漏建 `DishTagCleanupJob` 或 `QueuedTimeoutSweepJob`。

## 上一轮评审关闭情况

| 编号 | 主题 | 本版是否解决 | 依据 |
|---|---|---|---|
| 3.1 | 表格 Segment 与五字段证据冲突 | ❌ 未解决 | 改成逻辑行方向正确，但 Y 聚类算法不能处理双栏、上下标和跨行单元格，见 P0-1 |
| 3.2 | 并行分批无法生成全局序号 | ✅ 已解决 | 模型只返回 segmentId/itemIndex，排序与 sectionId 由 Java 生成 |
| 3.3 | 去重键无法实现 | ✅ 已解决 | 六类对象均有 itemIndex，§7.9 给出类型化去重键 |
| 3.4 | 正式 Schema 与正文不一致 | △ 部分解决 | 两份 Schema 的结构性枚举和条件约束已修；Prompt/Schema 仍有 clauseIndex 残留，且 Java 全局语义校验不完整 |
| 3.5 | 任务行与 Redis 结果生命周期冲突 | ❌ 未解决 | 字段已拆分，但成功 CAS 未刷新 purge_at，见 P0-3 |
| 3.6 | MySQL/Redis/DELETE 竞态 | △ 核心已解决 | 可见性协议经五类时序验证成立；异常预写清理和成功生命周期仍需补齐 |
| 3.7 | Redis 投递恢复 | ✅ 按已定替代方案关闭 | 采用 QUEUED 超时失败而非重投，符合本轮“零重试”决定；但容量阈值需修，见 P1-1 |
| 3.8 | 上传进度与任务状态错位 | ✅ 已解决 | 0～30% 由上传端计算，任务从 30% 开始，QUEUED 独立显示 |
| 3.9 | 创建任务时无法获得 Word 页数 | ✅ 已解决 | 页数裁决移至 Worker，Word 用 segment/内嵌图上限 |
| 3.10 | 正常词兜底误杀 | △ 主方案已解决 | §7.8 改为 conclusionText、最长短语优先且不含阴性；README 仍保留旧规则，见 P0-5 |
| 3.11 | 空态形成无依据正常结论 | ✅ 按产品决定关闭 | 本轮明确接受简单空态，不再重开 |
| 3.12 | 过敏原部分漏抽无法发现 | ❌ 未解决 | 标题词与阳性词被错误要求同段，coverage 又未过滤阴性/UNKNOWN，见 P0-2 |
| 3.13 | LLM-B 菜名/工艺证据被清除 | ✅ 已解决 | evidenceType 已进入 DDL、Schema、Prompt 和 Java 校验规则 |
| 3.14 | 菜品标签陈旧清理 | △ 部分解决 | 已取消按 create_time 清理，但两个不同 xxl-job Handler 并未真正互斥，30 天数据来源也未展开 |
| 3.15 | PDF 文本层应逐页判定 | ✅ 已解决 | §6.4 允许同一 PDF 混合 NATIVE/OCR 页 |
| 3.16 | 图片和文档资源防御不完整 | △ 部分解决 | ImageIO 两阶段、ZIP/XXE 方向正确；上传前置限流、总内存、PDF 页像素和不可中断解析仍未闭合 |
| §4 | 产品确认事项 | ✅ 按本轮指令视为关闭 | ABNORMAL、静默降级、简单空态、同一人等均已作为已定决策写入，不在本报告重开 |
| §5 | 接口和数据契约缺失 | △ 部分解决 | 五个端点、归属校验和主要响应已给出；失败码与模块三/四完整 DTO 仍缺，见 P0-7 |
| §6 | 菜品数据与打标链路 | △ 部分解决 | 主料、dishHash、TAG_MISSING 和 evidenceType 已明确；Layer 1 registry 与清理互斥仍未闭合 |

## 数字与枚举一致性核对

| 位置 | 期望值 | 实际值 | 是否一致 |
|---|---|---|---|
| `AGENTS.md` §3.6 | 11 食入性 + 9 饮食 = 20 打标维度 | 11 + 9 = 20 | ✅ |
| 开发方案 §9.1 | 11 食入性、9 饮食、20 LLM-B 维度 | 11、9、20 | ✅ |
| 开发方案 §10/§11 | 过敏词表 11 组 73 词，测试无 12/21 残留 | 11 组 73 词；测试清单无旧数字 | ✅ |
| 设计方案 §7.2 | 34 个正式枚举 + OTHER：11 食入 + 5 非食入 + 9 营养 + 9 饮食 | 明确写 34，分项为 11+5+9+9 | ✅ |
| 设计方案 §8.2/§8.3 | LLM-B 11 + 9 = 20 | 11 + 9 = 20，成本与 Key 组织均按 20 | ✅ |
| LLM-A Schema allergenKey | 11 食入 + 5 非食入 + OTHER | 16 个正式值 + OTHER | ✅ |
| LLM-A Schema nutritionKey | 9 + OTHER | 9 + OTHER | ✅ |
| LLM-A Schema dietRequirementKey | 9 + OTHER | 9 + OTHER | ✅ |
| LLM-A Schema 合计 | 34 个正式枚举（OTHER 为各类别兜底字面值） | 16 + 9 + 9 = 34 | ✅ |
| LLM-B Schema enumKey | 20 个；allOf 的过敏条件 11 个 | enumKey 20；条件分支 11 | ✅ |
| LLM-A Prompt 枚举表 | 11 食入 + 5 非食入 + 9 营养 + 9 饮食 | 列表逐项一致 | ✅ |
| LLM-B Prompt 维度描述 | 11 + 9 = 20 | 明确写 20 | ✅ |
| `constants/内容常量草案.md` §1 | 当前口径 11/20/34，新组仅为待评审候选 | 当前口径按 11/20/34 描述 | ✅ |
| `constants/README.md` §2.1 | 11 个食入性枚举 | 写成 12 个 | ❌ |
| `allergen_display_split.csv` | 11 组 73 个数据词条 | 实测 11 组、73 行；avoid 51、hidden 22 | ✅ |

数字主链目前只有 `constants/README.md` 的“12 个”是明确旧残留；更深层问题不是数量，而是 README 声称存在的 `matchMode/evidenceLevel` 列在 CSV 中并不存在，已列为 P0-5。

## 追加评审结论：模块四数量上限与 LLM-B 紧凑输出（2026-08-24）

### 1. 推荐与不推荐列表均最多输出 3 道菜

这是已确认的产品输出约束，开发和验收统一按以下规则执行：

```text
recommendList 最大长度 = 3
rejectList    最大长度 = 3
```

先按既有的多维度合并规则完成 `NOT_RECOMMENDED / HIDDEN / RECOMMENDED / NEUTRAL`
裁决，再分别对 `NOT_RECOMMENDED` 和 `RECOMMENDED` 菜品按现有规则排序，最后各取前 3 道。
不得在过敏、饮食注意和营养标签尚未合并完成前截断，否则可能提前留下应被过敏规则剔除的菜。

当前方案没有菜品评分模型，因此继续沿用“菜名拼音首字母排序、非汉字开头排在汉字之后”的
确定性顺序，然后执行 `limit(3)`。若后续需要按推荐程度、菜品热度或营养价值排序，必须另行
定义评分字段和并列规则，不能由开发人员自行发挥。

接口与测试必须补充以下约束：

- `dishModule.recommendList` 长度为 `0..3`；
- `dishModule.rejectList` 长度为 `0..3`；
- 全量候选超过 3 道时，截断不得改变原排序；
- `HIDDEN` 与最终全 `NEUTRAL` 的菜仍不进入任何列表；
- 推荐、不推荐列表分别不足 3 道时按实际数量返回，不用占位菜补满。

### 2. LLM-B 输出可以紧凑，但不能缩小菜品核验覆盖范围

离线打标不携带用户报告和用户过敏信息，无法预先按某个用户的过敏原剔除菜品。因此逻辑覆盖
仍然是“当日在架全部菜品 × 20 个 LLM-B 维度”；稳定运行时可以依据 MySQL diff 跳过已有且
`dishHash/tagPolicyVersion` 均未变化的组合，但本次送入模型的每道菜都必须在响应中有明确归属。

不再要求每个 `NEUTRAL` 菜品重复返回完整对象，建议将单维度批次响应改为：

```json
{
  "enumKey": "LOW_FAT",
  "neutralDishIds": [10001, 10003, 10005],
  "hitList": [
    {
      "dishId": 10002,
      "verdict": "REJECT",
      "evidenceType": "COOKING",
      "matchedIngredients": [],
      "reason": "油炸菜品"
    },
    {
      "dishId": 10004,
      "verdict": "RECOMMEND",
      "evidenceType": "INGREDIENT",
      "matchedIngredients": ["西兰花"],
      "reason": "符合低脂饮食要求"
    }
  ]
}
```

语义如下：

- `neutralDishIds` 只放已经核验且结论为 `NEUTRAL` 的菜品 ID；
- `hitList` 只放 `RECOMMEND` 或 `REJECT`，只有这些命中项携带证据、食材和理由；
- 食入性过敏原维度的 `hitList.verdict` 只允许 `REJECT`，不得返回 `RECOMMEND`；
- 饮食注意维度的 `hitList.verdict` 允许 `RECOMMEND` 或 `REJECT`；
- 9 个营养补充维度继续由 Java 确定性计算，不进入此 LLM-B 响应。

Java 只做集合完整性校验，不增加复杂推断：

```text
expected = 本批输入 dishId 集合
neutral  = neutralDishIds 集合
hit      = hitList.dishId 集合

neutral ∩ hit = 空集
neutral ∪ hit = expected
neutral、hit 内部均不得重复
响应不得包含 expected 之外的 dishId
```

任一条件不成立，整批响应判为无效，本批不写 MySQL/Redis；按全案零重试规则让本次预热失败并
告警，不得把遗漏菜品静默补成 `NEUTRAL`。校验通过后，Java 将 `neutralDishIds` 展开为
`verdict=NEUTRAL` 的 `ct_dish_tag` 行，与 `hitList` 一并持久化，因此数据库仍然保留完整的
`(dishId, dishHash, tagPolicyVersion, enumKey)` 覆盖，在线路径无需增加覆盖表或特殊查询逻辑。

### 3. 需要同步修改的正式材料

本节是评审结论，尚不能代替正式契约。进入开发前还必须同步修改：

1. 开发方案 §8.7、§9.4、§9.8：列表上限、截断顺序和紧凑响应；
2. 设计方案 §8.4、§8.10 及结果 DTO：相同业务语义；
3. `schema/llm_b_output.schema.json`：增加 `neutralDishIds` / `hitList`，删除旧的全对象
   `results` 契约，并保持过敏维度 verdict 限制；
4. `prompt/llm_b_dish_tag.md`：要求两个集合精确覆盖全部输入 dishId；
5. 契约测试：覆盖重复 ID、漏 ID、越界 ID、两集合交叉、过敏返回 `RECOMMEND`、列表分别
   超过 3 道后的稳定截断。
