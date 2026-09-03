# AI 体检报告分析 — 开发方案 V1

> **本文档是设计方案的可执行落地**（`AGENTS.md` §1 的第 2 顺位）。
> 它回答「代码怎么落地」：包结构、类、方法签名、DDL、错误码、执行顺序、测试清单。
>
> **`AI体检报告分析-精简设计方案V1.md`（下称设计方案）是最高真源。**
> 本文不复述设计理由，任何「为什么这么设计」一律看设计方案；
> **两者冲突时无条件以设计方案为准，并回改本文**，不得反向覆盖，也不得自行折中出第三套规则。
>
> 工程规范（工具链、编码风格、测试、交付）参照 `AGENTS.md`，本文不重复，只在落地处引用条款号。
> **`AGENTS.md` 不具有高于设计方案的独立优先级**——它与设计方案冲突时同样以设计方案为准，
> 并在交付报告中指出需要同步修订的条款。

---

## 0. 开工前必读

### 0.1 文档优先级（照 `AGENTS.md` §1）

| 顺位 | 文档 | 回答什么 |
|---|---|---|
| **1** | **设计方案** | 产品、架构、分层边界与系统行为的**最高真源** |
| 2 | **本文** | 设计方案的可执行落地：类、接口、DDL、执行顺序、测试清单 |
| 3 | `体检报告分析需求.md` | 产品需求原文 |
| — | `AGENTS.md` | 工具链、编码风格、测试与交付；**不具有高于设计方案的独立优先级** |

```
本文与设计方案冲突   → 无条件以设计方案为准，回改本文
AGENTS.md 与设计方案冲突 → 以设计方案为准，在交付报告里指出待同步的条款
任何三方冲突          → 【不得自行折中出第四套规则】
客观工具链阻塞        → 保留清晰 TODO 并报告，但不得让过期条款反向覆盖设计方案
```

历史文档（`…完整技术方案V1_7_1.md` 及全部 V1.5.x / V1.6.x / V1.7）**仅供追溯，永远不是判据**。

### 0.2 已登记的冲突

**本文与设计方案当前无冲突。** 设计方案相对产品需求的偏离统一登记在设计方案 §12；其中
§12-12 明确覆盖所有进入 `rejectSet` 的菜，包括过敏原 `REJECT` 和饮食注意 `REJECT`，不能缩写成
只有过敏冲突。

> 曾登记的 C1（`AGENTS.md` 要求「队列用 Redis Stream + Consumer Group」vs 设计方案已改本机线程池）
> **已消除**：`AGENTS.md` §2 现在写的是「是否使用消息队列以及任务调度方式完全以设计方案为准，
> 本文件不另行指定」。本文按设计方案实现线程池（§4.2），无需再做例外说明。

### 0.3 三条贯穿全文的硬边界

违反其一即为错误实现，代码评审必须打回：

```
① 分层职责（AGENTS.md §3、设计方案 §0-2）
   Java 只做：Schema 校验、来源引用校验、安全降级、集合运算、数值计算、排序
   Java 不做：改写模型的语义结论（status / isFoodBorne / includeInHealthProblems / enumKey）
             版面推断（相邻块配对、bbox 同行、表格行列还原、按坐标聚类）
             为「只告警」而扫语义词表

② 生产链路里的词表只有三类、四个执行点，全部是「往安全方向降级或拦截」
   过敏漏抽类   §6.5-A 高风险交叉扫描  +  §6.5-C 阳性行覆盖扫描
   高危表述类   §7.4  高危表述安全闸
   过敏兜底类   §7.5.5 过敏关键词兜底（与模型结果取并集，只增不减 REJECT）
   除此之外不得出现 ConclusionLabelWords / NormalStatementWords / AllergenSectionWords
   的任何生产代码引用（§11.1-R1 用 ArchUnit 断言）

③ MySQL 不存报告正文、OCR 文本和结构化健康结论；这些不进普通应用日志，
   排障期仅可进入 §9.2 规定的独立敏感 DEBUG logger
   —— 注意措辞：**不是「MySQL 不含任何敏感信息」**，`origin_name` 就是已登记的例外
   姓名 / 性别 / 完整 OCR 文本：只在工作线程内存，从不写 Redis
   四模块要展示的原文片段：随结果写 Redis，TTL 2h
   ⚠️ 【已登记的例外】ct_health_report_file.origin_name 是敏感元数据
      （常含姓名与体检属性），约束见 §3.2 —— 不要再说"MySQL 不含任何敏感信息"
   日志白名单见 AGENTS.md §6
```

#### 0.3.1 边界层的值必须显式校验，不能靠 Java 类型（2026-08-27 补）

**领域对象在构造器里把非法值拒掉，这套习惯在本仓库是有效的**——`Dish`、`Segment`、
`OcrResult`、`DishTagInput` 都这么做，所以它们的消费方可以按「构造即安全」写，
不必到处补 null 判断。

**但这套保护在边界层是失效的**，因为那里的值不是 Java 造出来的：

| 边界 | 可空性由谁决定 | 出过的事故 |
|---|---|---|
| 数据库实体 | **DDL 的 NULL 约束**，不是 Java 字段类型 | `ct_health_report_file.file_index` 是 `NULL` 列（上传时置空、绑定时才写），`TaskParseService` 直接 `.intValue()` 拆箱 |
| 外部接口返回值 | **对方的实现**，我们只有一个接口声明 | `DishQueryService` 分页结果为 `null`、游标不前进、元素含 `null` 或企业归属错乱 |

```
两条规则：

① 从数据库实体读【DDL 允许为 NULL】的列时，使用点必须显式判空
   —— 包装类型的自动拆箱是隐形的，编译器不会提醒
   —— 失败信息里要带【列名与主键】。裸 NPE 的问题不是它会崩，
      是它崩得没有信息：Worker 兜底成 SERVER_ERROR，日志里只有一行
      NullPointerException，看不出是哪一列、哪一行、为什么

② 外部接口的返回值契约写在【接口本身】上，并提供一个可复用的校验方法
   —— 写在各消费方内部就会各写各的，实现方也看不到
   —— 消费方在边界上校验一次，不让不合法的值漏进下游
```

**校验要放在做任何有代价的事情之前。** `TaskParseService` 的绑定完整性检查放在
`S3FileStorage.read` 之前——不能先把文件从对象存储拉下来，再发现这行根本没绑定完。

> **⚠ 不要把「空」也当成非法。** 空列表往往是合法业务状态，拦掉它是另一个 bug：
> `DishQueryService` 第一页为空表示该企业当日无在架菜品；末页为空表示分页正常结束；
> `Dish` 的空食材列表是常态
> （§7.5.1 调味料本就不入表）。这两条各配了正向用例，防止后来者"顺手"收紧。

**当前落点**：`TaskParseService.assertBindingComplete`、`DishQueryService.assertValidPage`、
`Dish` 构造器的食材元素判空。

### 0.4 裁决状态及阻塞范围

2026-08-27 已完成内容证据裁决和工程同步；下表区分“工程已完成”与仍需外部完成的上线门槛：

| 阻塞项 | 阻塞范围 | 出处 |
|---|---|---|
| 内容常量证据审核 | **工程已完成**：四类无 `DRAFT`；逐条来源见证据台账。若组织要求具名执业签字，签字仍阻塞模块三、模块四 | 设计方案 §10 P0-15 |
| `MOLLUSK` / `SESAME` 两组过敏原补齐 | **工程已完成**：常量、Schema、Prompt、版本和测试已同步；部署需全量重打 | 设计方案 §7.2.1、§10 P0-15a |
| 「酱油→小麦」「做法词入 WHEAT」口径 | **已裁决**：明确酱油/豉油为高可信线索；红烧/酱爆/卤不入硬词表，配方不明为 `UNKNOWN` | `constants/内容常量说明V3.md` §4.3 |
| §12 全部产品确认项 | 对应模块展示 | 设计方案 §12 |
| **`origin_name` 是否保留原始文件名** | 敏感元数据面，非阻塞但需表态 | 本文 §3.2 |
| **对象存储 `health-report/` 前缀的 Bucket 生命周期规则** | **上线阻断**——它是「孤儿对象永久残留」的最后兜底，不依赖应用代码正确。主控制是 §4.1 的写入顺序（先插库行再写对象），但那只能兜住我们想到的路径 | 本文 §4.1 |
| **`S3FileStorage.delete` 对不存在的 key 必须幂等成功** | **阻塞编码之外的正确性**——§4.1 的四种失败形态里有两种会去删不存在的对象；抛异常会让 file 行永远删不掉，孤儿清理原地卡死 | 本文 §4.1 |
| **网关是否透传 qwen3 的关闭思考参数**（LLM-B） | **不阻塞正确性**——剥离逻辑无条件保留（§13.2.3）；只影响 token 成本与 `max-tokens` 该配多大 | 本文 §13.2.3 |
| **LLM-A 直连的整条出网链路是否留存请求体** | **上线阻断** | 见下的六项核查；LLM-A 是全案最敏感的一次出网 |
| **§6.2.4 的 ⛔ 三项**（base-url / model / apiKey）与服务端限额 | **只阻塞端到端联调与上线**，不阻塞编码——协议已选定，代码可写完并用 WireMock 验（§6.2.1.1） | 本文 §6.2.4 |
| **OCR 的单图字节上限与请求体字节上限**（`ocr.max-encoded-image-bytes`、`ocr.max-request-body-bytes`） | **阻塞上线，不再阻塞编码**——协议与编码方式已由接入截图确认（§5.6.7），代码已按 JSON+Base64 写完并用 WireMock 验过；但这两个数推出 `effectiveOcrImageBytes`，进而决定 §5.1 的实际上传上限，**没有真值就不能上线**。`OcrProperties` 无默认值，缺失即启动失败 | 本文 §5.6.2.1、§5.6.7 |
| **OCR 网关是否接受 `data:image/png;base64,…` 形式的 `image_url`** | **阻塞端到端联调**——平台示例给的是 `http://…/nan.png` 外链，我们**不能用外链**（§5.6.7 的理由）。若网关只认外链，等于要求把报告页图发布到可按 URL 取回的位置，那是 §6.2.1 拒绝 Dify 的同一条理由，需要重新决策而不是改代码 | 本文 §5.6.7 |
| **扫描版 OFD 目前无法走 OCR**——`parse/ofd` 没有页面渲染器，`RenderedPageImageProcessor` 要的 `BufferedImage` 只有 `PdfPageRenderer` 能产出 | **功能缺口，非上线阻断**：这类文件会以零 segment 落到 `UNREADABLE`，是显式失败不是静默降级。要支持须用 ofdrw 补一个渲染器并补 bbox/尺度契约验证 | 本文 §5.7 |
| **OCR 出网链路是否留存请求体**——与 §0.4.1 的六项同源，但 OCR 是**第二次**把报告图像发出去 | **上线阻断** | 见 §0.4.1；把 OCR 网关一并纳入那六项核查 |

**实现时按「未审核 = 该内容不注入」处理**：`ReviewStatus != REVIEWED` 的常量条目不进提示词、
不参与匹配（现有 `AllergenGroups` 等常量类已有该字段）。这不是临时妥协，是长期机制。

#### 0.4.1 「不落 Dify」不等于整条链路不落盘

直连消掉的只是 Dify 存储那一处。**Base64 图片现在完整存在 JVM 堆里、完整走过出网链路**，
以下六项必须逐条核查并留档，**任一项没答案就不上线**：

```
□ 模型服务端是否留存请求？留存多久？能否关闭？是否用于训练？
□ 是否跨境传输？
□ 中间的模型网关 / 反向代理 / API 网关是否缓存或落盘请求体？
□ APM / Sentry / 链路追踪是否采集 HTTP body？—— 默认常常是采集的
□ JVM OOM 时是否生成 heap dump？【直连后堆里就是完整的 Base64 报告图像】
□ 容器崩溃转储 / core dump 是否包含这些内容？
```

> **heap dump 这一条比直连之前更紧迫。** 走 Dify 时图像上传完就可以释放，
> 直连时它必须在堆里活到请求发完；而 `-XX:+HeapDumpOnOutOfMemoryError` 是很多部署的默认开关，
> 一次 OOM 就会把整份报告图像写进磁盘。**必须显式关闭或加密隔离**（§11.1 已有此项，
> 直连后它从"一般风险"升级为"必查项"）。

---

## 1. 工程基线

照抄 `AGENTS.md` §2，此处只列落地时要写进 `pom.xml` 的部分：

```
JDK 8（source=8 / target=8，已在 pom 中）      Spring Boot 2.7.18（已在 pom 中）
javax.*，绝不 jakarta.*                        Maven
```

**需新增的依赖**（`AGENTS.md` §2 要求新增依赖在报告中说明）：

| 依赖 | 用途 | 备注 |
|---|---|---|
| `spring-boot-starter-web` | HTTP 接口 | |
| `spring-boot-starter-validation` | 请求校验 | |
| `spring-boot-starter-data-redis` | 结果缓存、打标缓存 | **不写 `RedisConfig`**（`AGENTS.md` §5） |
| `mybatis-plus-boot-starter` | 持久化 | 3.5.x 最后一个支持 Boot 2.7 的版本 |
| `mysql-connector-java` | 驱动 | 8.0.x |
| `org.apache.pdfbox:pdfbox` | PDF 解析 | **2.0.x**，不升 3.x |
| `org.apache.poi:poi-ooxml` + `poi-scratchpad` | DOCX / DOC | |
| `org.ofdrw:ofdrw-reader` | OFD | |
| `com.github.promeg:tinypinyin` | 菜名拼音 | |
| `com.networknt:json-schema-validator` | LLM 输出 Schema 校验 | Java 8 兼容版本 |
| `com.xuxueli:xxl-job-core` | 离线打标调度 | |
| HTTP 客户端 | **LLM-A 直连模型 API**（§6.2.1）与 OCR 调用 | 优先用 `spring-boot-starter-web` 自带的 `RestTemplate`，**不新增第三方 HTTP 库**（`AGENTS.md` §2） |
| `com.tngtech.archunit:archunit-junit4` | §11.1-R1 架构断言 | **test scope** |
| `com.github.tomakehurst:wiremock-jre8` | R57~R65 的真实 HTTP 红线测试 | **test scope**，`jre8` 版本才兼容 Java 8 |

**Java 8 语法红线**（`AGENTS.md` §2）：本文所有示例代码都已避开 `var` / `List.of` /
text blocks / switch 表达式 / `Optional.isEmpty` / `String.isBlank` / `Stream.toList`。
照抄示例不会踩线；自行扩写时按同样口径。

---

## 2. 包结构与命名

> **包名与类名按职责取，不用 `a` / `b` / `1234` 这类序号**（`AGENTS.md` §6）。
> 文中「LLM-A」「LLM-B」「模块一~四」是架构叙述用语，与设计方案保持一致；
> 落到代码里一律用下表的职责名，两者的对应关系是：
>
> | 叙述用语 | 包 | 主要类前缀 |
> |---|---|---|
> | LLM-A | `llm.extraction` | `Extraction*` |
> | LLM-B | `llm.dishtag` | `DishTag*` |
> | 模块一 健康指标 | `assemble.indicator` | `Indicator*` |
> | 模块二 健康问题 | `assemble.problem` | `Problem*` |
> | 模块三 饮食建议 | `assemble.dietadvice` | `DietAdvice*` |
> | 模块四 菜品推荐 | `assemble.dishrecommend` | `DishRecommend*` |
>
> Prompt 与 Schema 的**文件名也按职责取**，与上表的包名对齐：
>
> | 资源 | 抽取 | 菜品打标 |
> |---|---|---|
> | 提示词 | `prompt/extraction.md` | `prompt/dish_tag.md` |
> | 输出契约 | `schema/extraction_output.schema.json` | `schema/dish_tag_output.schema.json` |
> | `promptVersion` 前缀 | `extraction-` | `dishtag-` |

```
com.example.healthreport
├── api                     Controller + 请求/响应 DTO
│   └── dto
├── task                    任务生命周期：创建、线程池、状态机、巡检
├── parse                   文件解析 → Segment
│   ├── pdf | ofd | word | ocr
│   └── segment             Segment 模型、规范化、密度闸
├── llm                     模型链路
│   ├── extraction          LLM-A（报告结构化抽取）：分批、编址、调用、Schema 校验、展开、来源校验
│   ├── dishtag             LLM-B（菜品打标）：离线打标契约与校验
│   └── schema              两条链路共用的输出契约：Schema 的唯一加载点与校验入口
├── assemble                四模块组装
│   ├── indicator           模块一 健康指标
│   ├── problem             模块二 健康问题
│   ├── dietadvice          模块三 饮食建议
│   ├── dishrecommend       模块四 菜品推荐
│   └── sort                排序总则的唯一实现
├── dish                    菜品查询、主料推导、标签读取与裁决
├── safety                  安全扫描、降级决策、词表（仅三处，见 §0.3）
├── constants               已存在，内容常量真源（不动结构）
├── persistence             Entity / Mapper / Service（`AGENTS.md` §4 命名）
├── cache                   Redis 键位与读写
├── infra                   五个占位符（`AGENTS.md` §5）
└── support                 IdCanonicalizer、计数器、错误码、共用工具
```

**源码之外的交付物目录**（§13）：

```
sql/schema.sql              建表语句，与 §3.1 一字不差（R54 锁）
sql/alter/*.sql             上线后的结构变更，一次一个文件
（无 Dify DSL 交付物）      LLM-A、LLM-B、OCR 三条链路全部直连，接入契约见 §6.2.1.1 / §13.2 / §5.6.7
dify/README.md               记录 LLM-B 曾经的编排形态与改直连的理由；【不是交付物】
prompt/*.md                 提示词真源（已存在）
schema/*.json               LLM 输出契约（已存在）
constants/*.md              内容常量说明（已存在）
```

**命名规则**（`AGENTS.md` §4/§6）：

```
表 ct_health_report_task
  → CtHealthReportTaskEntity / CtHealthReportTaskMapper / CtHealthReportTaskService
  Service 是 Spring @Service，不建 Mybatis*Repository 包装，不用概念别名

集合变量以 List / Map / Set 结尾；已知容量时 new ArrayList<>(sourceList.size())
所有控制流语句体加花括号，包括单语句 if
枚举类型与每个枚举常量都要中文注释
```

---

## 3. 持久化

### 3.1 DDL（全量，直接可执行）

**三张表由本方案创建**（`ct_dish` / `ct_dish_ingredient` 是食堂系统的表，只读，不在此列）。
每个字符列逐列声明字符集、每列有中文 `COMMENT`、表上无任何 `CONSTRAINT`（`AGENTS.md` §4）。

```sql
CREATE TABLE ct_health_report_task (
  task_id        VARCHAR(36)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '任务ID，UUID小写规范形式，由IdCanonicalizer生成',
  company_id     VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '归属企业ID，创建任务时从可信认证上下文固化，模块四据此选择企业菜品集合',
  user_id        VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '归属用户ID，用于鉴权，值由上游用户系统提供',
  status         VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '任务状态：QUEUED待执行/PARSING解析中/EXTRACTING抽取中/ASSEMBLING组装中/SUCCEEDED成功/FAILED失败',
  stage          VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '前端进度阶段，仅三个取值：UPLOADING上传中对应QUEUED/PARSING识别中对应PARSING与EXTRACTING/ASSEMBLING生成中对应ASSEMBLING',
  progress       TINYINT      NOT NULL DEFAULT 0 COMMENT '任务进度百分比，取值0至100',
  fail_code      VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '失败错误码，与 FailCode 枚举一一对应，成功或未失败时为NULL',
  reanalyzable   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否允许重新解析：1允许0不允许，同时是文件解绑条件',
  partial        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否为部分结果：1是0否，命中时模块三四按partial_reason降级',
  partial_reason VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '降级原因：PAGE_TRUNCATED页数截断/BATCH_UNREADABLE批次不可读/ALLERGEN_SUSPECT_MISS疑似漏抽过敏原/SCHEMA_ITEM_DROPPED个别条目不合Schema已剔除/DIET_REQUIREMENT_DROPPED剔除的条目含饮食注意需抑制菜品推荐',
  heartbeat_at   DATETIME     NULL COMMENT '工作线程最近心跳时间，巡检据此判断进程存活',
  deadline_at    DATETIME     NULL COMMENT '任务执行硬截止时间，领取时置为当前时间加10分钟，此后不再顺延',
  expire_at      DATETIME     NOT NULL COMMENT '任务行过期时间，创建时为30分钟后，成功时顺延为2小时后以对齐结果TTL',
  deleted_at     DATETIME     NULL COMMENT '用户删除任务的时间，未删除时为NULL，一旦置上不可撤销',
  version        INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，由数据库维护，代码永不赋值',
  create_by      VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '创建人标识，固定系统标识，绝不写用户标识',
  update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间，由数据库维护，代码永不赋值',
  update_by      VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '更新人标识，固定系统标识，绝不写用户标识',
  PRIMARY KEY (task_id),
  KEY idx_company_user (company_id, user_id),
  KEY idx_sweep (status, heartbeat_at),
  KEY idx_deadline (status, deadline_at),
  KEY idx_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='体检报告分析任务';

CREATE TABLE ct_health_report_file (
  file_id        VARCHAR(36)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '文件ID，UUID小写规范形式',
  company_id     VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '归属企业ID，上传时从可信认证上下文固化，绑定任务时必须与任务企业精确一致',
  user_id        VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '归属用户ID，用于鉴权',
  task_id        VARCHAR(36)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '关联任务ID，未绑定任务时为NULL',
  file_index     INT          NULL COMMENT '文件在任务内的顺序，从0开始，即用户提交fileIds的顺序',
  status         VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '文件状态：UPLOADED已上传，当前仅此一个取值',
  origin_name    VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '敏感元数据：用户上传的原始文件名，可能含姓名与体检属性如张三-2026体检报告.pdf。仅用于前端回显，禁止进日志与外部系统，随file行一起删除。是否改为安全生成的展示名待产品确认',
  content_type   VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '按内容判定的真实格式：PDF/JPG/PNG/OFD/DOC/DOCX，不信任扩展名',
  size_bytes     BIGINT       NOT NULL COMMENT '文件大小，单位字节',
  precheck_pages INT          NOT NULL COMMENT '创建任务容量预检页数：PDF与OFD为真实页数，图片为1，Word为原生segment数除以40向上取整的下界且不含OCR块，图片型Word可为0',
  content_hash   CHAR(64)     CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '文件内容SHA-256哈希，小写十六进制',
  cloud_file_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '对象存储文件键，用于定位原始文件，桶名由部署配置提供不入库',
  expire_at      DATETIME     NOT NULL COMMENT '原始文件过期删除时间，上传后30分钟',
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，由数据库维护，代码永不赋值',
  create_by      VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '创建人标识，固定系统标识',
  update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间，由数据库维护，代码永不赋值',
  update_by      VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '更新人标识，固定系统标识',
  PRIMARY KEY (file_id),
  KEY idx_task (task_id),
  KEY idx_company_user (company_id, user_id),
  KEY idx_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='上传的体检报告文件';

CREATE TABLE ct_dish_tag (
  company_id          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '菜品所属企业ID，离线打标、查询与Redis发布的租户隔离键',
  dishes_id           BIGINT       NOT NULL COMMENT '食堂菜品ID，在同一企业内唯一，与company_id共同确定菜品',
  tag_hash            CHAR(64)     CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '打标输入哈希：规则版本+提示词版本+模型版本+菜名+食材，四段用竖线拼接后取SHA-256，食材先按名称字典序排序',
  enum_key            VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '打标维度枚举键：13个食入性过敏原或9个饮食注意，取值见constants包',
  verdict             VARCHAR(12)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '打标结论：REJECT含或可能含/UNKNOWN数据不足判不出/NEUTRAL确认不含。RECOMMEND仅由营养维度Java计算产生不落本表，TAG_MISSING是查不到行的推导结果不入库',
  evidence_type       VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '证据类型：INGREDIENT食材表明确列出/DISH_NAME菜名明确说明/COOKING菜名直接表达且足以证明成分的工艺证据，仅REJECT时有值；通常做法推断不得产生REJECT',
  matched_ingredients VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '命中食材名称的JSON数组字符串，仅供离线契约校验与排障，不写Redis、不用于推荐理由',
  reason              VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '模型返回的判定理由，仅排障用，不展示给用户',
  model_version       VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'LLM-B模型版本，冗余存储仅供排障，不参与任何键与查询条件',
  prompt_version      VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'LLM-B提示词版本，冗余存储仅供排障',
  tag_rule_version    VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '内容常量版本，冗余存储仅供排障',
  last_seen_date      DATE         NOT NULL COMMENT '最后一次被预热确认为当前有效的业务日，清理只看这一列',
  create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间即打标时间，由数据库维护，代码永不赋值',
  create_by           VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '创建人标识，固定为DISH_TAG_JOB',
  update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间，由数据库维护，代码永不赋值',
  update_by           VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '更新人标识，固定为DISH_TAG_JOB',
  UNIQUE KEY uk_company_dish_hash_enum (company_id, dishes_id, tag_hash, enum_key),
  KEY idx_build (company_id, enum_key, dishes_id, tag_hash),
  KEY idx_last_seen (last_seen_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='菜品维度打标结果';
```

**没有 outbox 表、没有队列表、没有 segment 表、没有 lease 表。**
segment 与 bbox 只在内存（设计方案 §10「明确不做」），Worker lease 用
`heartbeat_at` + `deadline_at` 表达。

### 3.1.1 审计列与实体映射

四个审计列每张表都有，实体映射 `createBy` / `createTime` / `updateBy` / `updateTime`。
**两个时间列必须显式声明永不写入**（`AGENTS.md` §4）：

```java
@TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
private LocalDateTime createTime;

@TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
private LocalDateTime updateTime;
```

```
禁止 MetaObjectHandler 自动填充这两列
禁止任何 SQL / UpdateWrapper 里出现 update_time = now()
create_by / update_by 取值：
    ct_health_report_task    HEALTH_REPORT_API（在线创建） / HEALTH_REPORT_WORKER（工作线程写回）
    ct_health_report_file    HEALTH_REPORT_API
    ct_dish_tag              DISH_TAG_JOB（按 company_id 隔离）
常量定义在 support.SystemActor，不得散落字面量
```

### 3.1.2 业务规则的唯一 Java 执行点（DDL 无兜底）

表上无 `CONSTRAINT`，以下规则**各有且只有一个 Java 执行点**，实现处必须写注释
「DDL 无兜底」（`AGENTS.md` §4）：

| # | 规则 | 唯一执行点 |
|---|---|---|
| B1 | 一个文件同时只能属于一个「活着的」任务 | `task.FileBindingService#bindFiles`（§4.2 的两步锁定） |
| B2 | `verdict = REJECT` 时 `evidence_type` 必填；其他状态必须为空 | `dish.DishTagWriteService#write` |
| B3 | `evidence_type = INGREDIENT` 时 `matched_ingredients` 必须非空且 ⊆ 该菜食材表 | 同上 |
| B4 | `fail_code` 仅在 `status = FAILED` 时非空 | `task.TaskStateService` 的全部终态方法 |
| B5 | `partial_reason` 仅在 `partial = 1` 时非空 | `task.TaskStateService#markPartial` |
| B6 | `expire_at` 只在成功时顺延，失败任务不动 | `task.TaskStateService#markSucceeded` |
| B7 | `deleted_at` 一旦非空不可改回 | `task.TaskDeleteService#delete`（条件更新带 `deleted_at IS NULL`） |
| B8 | 菜品标签的企业归属必须与构建中的 `companyId` 精确一致 | `dish.DishTagSnapshotBuildService#acceptPage` |

### 3.1.3 ID 与大小写规范化

`utf8mb4_general_ci` 大小写不敏感，唯一键会把大小写变体当同一行（`AGENTS.md` §4）。

```java
support.IdCanonicalizer
    String newTaskId()          // UUID.randomUUID().toString()，去横线与否统一，一律小写
    String newFileId()          // 同上
    String canonicalize(String) // 入口断言用：小写化后与原值不等则抛 IllegalArgumentException
                                // 【只断言，不静默纠正】
```

```
我们生成的 ID     一律小写规范形式，入口 assert 不纠正
外部传入的 user_id / company_id 不由我们生成，归属校验【必须在 Java 侧再做一次精确 equals】
                  不能只靠 WHERE（_ci 会把大小写变体判成同一个值）
                  执行点：task.TaskOwnershipGuard#assertOwned(taskOrFile, currentUserId, currentCompanyId)
                  所有读写任务/文件的入口都要过它，见 §4.1
```

### 3.2 Redis 键位

**Redis 只有两个用途，不承载任何调度状态**（设计方案 §9.1）。

> **⚠️ 「MySQL 不存健康数据」有一个例外：`ct_health_report_file.origin_name`。**
> 它原样保存用户上传的文件名，而真实世界里这个字段经常长这样：
> `张三-2026年度体检报告.pdf`——**姓名 + 体检属性都在里面**。
>
> ```
> 本版口径：承认它是【敏感元数据】，按四条约束
>     ① 仅用于前端回显"你上传了哪些文件"，不参与任何业务判定
>     ② 【禁止进入普通应用日志】—— 排障期仅可进入 §9.2 默认关闭的独立敏感 DEBUG logger
>     ③ 【禁止传给任何外部系统】—— 不进模型请求、不进 Dify 请求、不进 OCR 请求、不进对象存储元数据
>     ④ 随 file 行一起删除（§4.5 清理矩阵），不单独延长留存
>
> 待产品确认（§0.4）：是否改成不存原始文件名、只存安全生成的展示名（「报告1.pdf」+ content_type）
>     彻底消除这个面；代价是前端回显失去用户熟悉的文件名，属产品取舍
> ```

| Key | 类型 | 内容 | TTL |
|---|---|---|---|
| `result:{taskId}` | String(JSON) | 四模块结果 | 2h |
| `dish:recommend:{companyId:bizDate}:allergen:reject:<enumKey>` | Set | Member=`dishId\tdishName`，过敏原不推荐集合 | 3d |
| `dish:recommend:{companyId:bizDate}:diet:recommend:<enumKey>` | Set | Member=`dishId\tdishName`，饮食推荐集合；仅 `LOW_PURINE`、`HIGH_FIBER` | 3d |
| `dish:recommend:{companyId:bizDate}:diet:reject:<enumKey>` | Set | Member=`dishId\tdishName`，饮食不推荐集合 | 3d |
| `dish:recommend:{companyId:bizDate}:nutrition:recommend:<enumKey>` | Set | Member=`dishId\tdishName`，营养推荐集合 | 3d |

同一企业同一天共 33 个正式 SET：13 个过敏 reject、2 个饮食 recommend、9 个饮食 reject、
9 个营养 recommend。`{companyId:bizDate}` 是 Redis Cluster hash tag，确保集合运算
与 staging 原子发布不跨 slot。不创建 `active`、`all`、`dishId -> dishName` 或
`dishId + 标签 -> matchedIngredients` Key；在线只读当前用户企业的当天集合。

```
没有 task:{taskId} 状态 Hash        任务状态真源是 MySQL
没有 Redis 墓碑                     删除标志用 deleted_at
没有 q:analysis                     没有队列
Redis 整个挂掉时：正在跑的任务仍能跑完，只是写结果失败 → 任务判 FAILED

【结果里不得包含】姓名、性别、完整 OCR 文本、全部 segment 的 rawText（§0.3-③）
【结果里包含】四模块实际展示的原文片段
```

---

## 4. 任务链路

### 4.1 HTTP 接口契约

**归属校验逐接口不同，不能一句话概括**（§3.1.3）：

| 接口 | 校验什么 | 用什么 |
|---|---|---|
| `POST /file` | **无 taskId 也无 fileId**，只校验调用者已认证 | `CurrentUserProvider` |
| `POST /analyze` | 每个 `fileId` 的 `user_id`、`company_id` 分别精确等于当前上下文 | `FileOwnershipGuard`（§4.2 的 ⓪ 与事务内各做一次） |
| `GET /task/{id}`、`GET /result/{id}`、`DELETE /task/{id}` | `taskId` 同时归属当前 `userId`、`companyId` 且 `deleted_at IS NULL` | `TaskOwnershipGuard` |

**两个 Guard 都必须对 userId 和 companyId 在 Java 侧分别做精确 `equals`**，不能只靠 SQL
——`utf8mb4_general_ci` 大小写不敏感，`Abc` 和 `abc` 会被判成同一人（§3.1.3）。

#### `POST /api/health-report/file` 上传单文件

```
入参   multipart/form-data，字段名 file
出参   200 { "fileId": "..." }
```

| 校验 | 规则 | 失败码 |
|---|---|---|
| 格式 | 逐格式判定，**不信任扩展名**（§5.1） | `UNSUPPORTED_FORMAT` |
| 大小 | PDF/OFD/DOC/DOCX ≤ 20MB；**JPG/PNG ≤ min(10MB, `effectiveOcrImageBytes`)**（§5.6.2.1） | `FILE_TOO_LARGE` |
| 可读性 | 按 §5.1 逐格式判定 | `FILE_UNREADABLE` |

落 `ct_health_report_file`：`status='UPLOADED'`、`task_id=NULL`、
`user_id=当前用户`、`company_id=当前企业`、`expire_at=now+30min`、**`precheck_pages`（见下）**，
原文件存对象存储私有桶。

##### ⛔ 写入顺序：先插库行，再写对象（2026-08-27 定）

```
① fileService.insertFromApi(fileEntity)     ← file 行【就是账本】
② fileStorage.write(objectKey, bytes)
```

**反过来会留下永久残留的健康数据。** 孤儿清理是**从 file 行出发**去找对象的
（`selectExpiredOrphans` 查的是 `ct_health_report_file`）。先写对象再插库时，
只要两步之间失败，那个 S3 对象就**没有任何一行指向它**——不是清理得晚，是永远发现不了。
而它是一份体检报告原文，与 §0.3、§4.5 的数据生命周期直接冲突。

**这不需要两次故障。** 「插库失败 + 补偿删除也失败」只是其中一条路径；
**进程在两步之间被杀**（OOM kill、滚动发布、`kill -9`）时补偿代码根本不会执行，
一次中断就够。滚动发布期间每次实例下线都开着这个窗口。

**现在的顺序下每种失败都落到同一条已有路径上：**

| 失败点 | 结果 | 谁来收 |
|---|---|---|
| 插库失败 | 无行、无对象 | 无需清理 |
| 插库后进程被杀 | 有行、无对象 | 孤儿清理（删一个不存在的 key） |
| 写对象失败 | 有行、对象状态未知 | 孤儿清理（无条件删一次） |
| 写对象中进程被杀 | 有行、可能有对象 | 孤儿清理 |

> **⛔ 写对象失败时不得删除 file 行。** 写失败可能是**假阴性**——超时的 PUT 在服务端
> 可能已经成功。删掉行就回到了「对象存在但无人指向」的原问题。
> 保留行、让孤儿清理无条件去删一次对象，这正是账本存在的意义。

**由此产生两条对外要求：**

```
① S3 接入契约：delete 一个不存在的 key 必须【视为成功，不得抛异常】
   —— 上面四种失败形态里有两种会去删不存在的对象，抛异常会让 file 行永远删不掉
   —— S3 协议本身 DELETE 不存在的 key 返回 204，容易满足，但必须写死

② 部署兜底：health-report/ 前缀配置 Bucket 生命周期规则（列入 §0.4）
   —— 它不依赖我们的代码正确，能兜住所有还没想到的孤儿路径
   —— 过期时间要 > 合法对象最长寿命（上传 30min + 任务 deadline 10min，1 天足够宽）
   —— 这是【部署级】控制，测试断言不了，与 §13.2 原 D4 同一类
```

**HEIC 由前端转 JPEG 后上传**，后端不引 Native 库。

#### 4.1.1 `precheck_pages` 在上传时算定

它只服务于创建任务前的**容量下界预筛**，不承诺 Word 的最终等效页数。PDF / OFD / 图片是精确值；
Word 的内嵌图片尚未 OCR，只能先数原生 segment，精确值在工作线程计算（§4.1.2）。

```java
// parse.CapacityPrecheckService —— 上传时调用，不做 OCR、不保存 segment
// Word 原生 segment > 1200 或 ≥300×300px 的内嵌图片 > 30 时直接抛 PAGE_LIMIT_EXCEEDED
int countPrecheckPages(byte[] content, ContentType type);
```

| 格式 | `precheck_pages` | 同步拒绝条件 |
|---|---|---|
| PDF / OFD | 真实页数 | 与任务内其他文件累计后 > 60，由 analyze 拒绝 |
| JPG / PNG | 恒为 1 | 同上 |
| DOC / DOCX | `ceil(nativeSegmentCount / 40)`，**不含 OCR 块** | `nativeSegmentCount > 1200` 或 `embeddedImageCount > 30`，上传直接拒绝 |

```
上传时只落 precheck_pages，不落 nativeSegmentCount、embeddedImageCount 或任何 segment 原文。
工作线程仍从原文件重新解析；Word 的 OCR 块数只有那时才知道。
```

#### 4.1.2 Word 精确容量在 Worker 裁决

```java
// parse.word.WordCapacityGuard —— Word 完成内嵌图片 OCR、全部 segment 生成后调用
// 只做数量统计与阈值比较，不做版面或语义判断
WordCapacityResult check(List<Segment> orderedSegmentList, int embeddedImageCount);
```

固定顺序：

```
① 按 Word 源码顺序生成原生 segment；图片 OCR 块插入图片所在位置
② embeddedImageCount > 30 或 orderedSegmentList.size() > 1200
     → FAILED / PAGE_LIMIT_EXCEEDED / reanalyzable=false，且不调用 LLM-A
③ exactWordPages = ceil(orderedSegmentList.size() / 40)
④ 与其他文件的精确页数累计：
     exactTotalPages > 60
       → FAILED / PAGE_LIMIT_EXCEEDED / reanalyzable=false，且不调用 LLM-A
     31 <= exactTotalPages <= 60
       → PageBudgetService 按 fileIndex 和文件内顺序保留前 30 等效页（§5.4）
```

单个 Word 超过 1200 segment 时直接失败，不把它截成 30 页；但**任务累计截断仍可能落在 Word 内部**。
例如 PDF 20 页 + Word 800 segment（20 等效页）总计 40 页，应保留 PDF 20 页和 Word 前 400 个
有序 segment。多个 Word 文件同理。

这是设计方案允许的时机让步：Word 因 OCR 块导致的超限可以在任务创建后异步发现。
容量超限是确定性输入问题，`reanalyzable=false`；OCR 服务调用失败则是
`SERVER_ERROR / reanalyzable=true`，不得把两者混用。

#### `POST /api/health-report/analyze` 创建任务

```
入参   { "fileIds": ["...", "..."] }        顺序即 fileIndex（0 起）
出参   200 { "taskId": "..." }
      409 { "code": "FILE_ALREADY_BOUND", "taskId": "已绑定的那个" }
```

**逐文件校验，缺一不可：**

```
file.userId   == 当前已认证 userId     ← 不校验 = 拿到别人的 fileId 就能读别人的报告
file.companyId == 当前已认证 companyId ← 不校验会跨企业绑定报告
file.status   == 'UPLOADED'
file.expireAt >  now
可绑定        = task_id IS NULL 或（原 task 为 FAILED 且 reanalyzable = 1）
```

**其余校验：** `fileIds` 数量 1~5、累计 ≤ 60MB、累计 `precheck_pages` ≤ 60（**不是 30**，
且含 Word 时只是下界预筛，见 §4.1.2、§5.4）。
**没有队列深度校验**——背压由线程池有界队列 + `AbortPolicy` 承担（§4.2）。

`FILE_ALREADY_BOUND` **必须把已绑定的 taskId 一并返回**，兜住「任务创建成功但响应丢包」。

#### `GET /api/health-report/task/{taskId}` 轮询状态与进度

**只返回状态，不返回结果**（结果走下一个接口）。

```
出参   { "status":"QUEUED|PARSING|EXTRACTING|ASSEMBLING|SUCCEEDED|FAILED",
        "stage":"UPLOADING|PARSING|ASSEMBLING",
        "progress":0-100,
        "failCode":"..."|null, "reanalyzable":bool }
```

**`QUEUED` 必须能返回**——任务提交后到工作线程领取 CAS 之间就是这个状态，
前端在这段时间照样要拿到 `stage=UPLOADING` / `progress=0`。

#### `GET /api/health-report/result/{taskId}` 取四模块结果

```
出参   { "partial":bool, "partialReason":"..."|null,
        "processedPages":30, "totalPages":45,
        "suppressDietAdvice":bool, "suppressDishRecommend":bool,
        "modules": { ... 见 §7 ... } }
```

##### `modules` 的形状：四个字段各是**一个对象**，不是数组（2026-08-27 定）

```json
"modules": {
  "healthIndicators":    { "overview": {...}, "groupList": [...] },
  "healthProblems":      { "itemList": [...] },
  "dietAdvice":          { "allergenSection": {...}, ... },
  "dishRecommendations": null
}
```

**模块被抑制或未产出时该字段为 `null`**，与同级的 `suppressDietAdvice` /
`suppressDishRecommend` 布尔位对齐；前端判空只需判 `null`，不必再区分「空数组」和「不存在」。

> **为什么不用长度恒为 1 的数组。** 四个组装器各产出恰好一个 `Result`，
> 用数组包起来在契约上表达不出「只能有一个」——将来有人往里塞第二个元素不会有任何东西拦住，
> 而前端每个模块都得写 `[0]`。
>
> **抑制时曾经会产出 `[null]`。** `DishRecommendAssembler.assemble` 在抑制时返回 `null`，
> 若直接包成单元素数组就是 `[null]`——前端拿到的是「有一个模块，但它是空的」，
> 与「没有这个模块」是两回事。改成对象后这个形态不再可能，
> `AnalysisModules` 也不再需要为它写防御。

**降级裁剪仍然只有一处**：`AnalysisResult.create` 把被抑制的模块字段置 `null`，
组装编排类照常产出完整四模块（§5.7）。

**读结果的顺序不能反**（§4.4）：

```
① 查 MySQL 任务行
② 任务不存在 / 归属不符 / deleted_at 非空 / 已过期  →  404 RESULT_EXPIRED（四种同码，不泄露差异）
③ status != SUCCEEDED  →  【不读 Redis】，返回 409 TASK_NOT_FINISHED（前端应去查 task 接口）
④ status == SUCCEEDED  →  才读 Redis；读不到同样返回 RESULT_EXPIRED
```

`processedPages` / `totalPages` 两个字段**必须下发**，前端据此解释 `PAGE_TRUNCATED`（§5.4）。

#### `DELETE /api/health-report/task/{taskId}` 删除

见 §4.5。

> **除上传接口外，全部接口必须校验 `taskId` 同时归属当前 `userId`、`companyId`，且
> `deleted_at IS NULL`。**
> `taskId` 难猜是混淆，不是鉴权。上传接口虽无 `taskId`，但创建任务时必须校验文件归属。

### 4.2 创建与执行：先提交事务，后提交线程池

```java
// task.AnalysisTaskCreateService
@Transactional
String createInTransaction(List<String> fileIdList, String userId, String companyId); // ① ~ ④
// 事务【外】：
executor.submit(taskId);                                              // ⑤
```

```
⓪ 【事务外预检】SELECT file_id, precheck_pages, size_bytes, status, expire_at, task_id
                  FROM ct_health_report_file
                 WHERE file_id IN (?) AND user_id = ? AND company_id = ?
   逐条校验 status / expire_at / 可绑定；再算 SUM(precheck_pages) 与 SUM(size_bytes)
       SUM(precheck_pages) > 60  →  PAGE_LIMIT_EXCEEDED，【直接返回，不建任务、不绑文件】
       SUM(size_bytes) > 60MB     →  FILE_TOO_LARGE，同上
   —— 纯算术，不解析任何文件；含 Word 时这是下界预筛，最终容量由 Worker 裁决（§4.1.2）
① 开启事务
② INSERT ct_health_report_task (status='QUEUED', expire_at=now+30min, ...)
③ 绑定文件（下面的两步，B1 的唯一执行点）
   —— ⓪ 的校验在事务内【重做一遍】：预检是快速失败，事务内那次才是判据（防 TOCTOU）
④ 提交事务                                ← 提交在前
⑤ analysisExecutor.submit(taskId)         ← 提交在后，【事务之外】
     RejectedExecutionException / 任何异常
       → 事务外把该任务 CAS 为 FAILED / SERVER_ERROR
       → 返回 SERVER_ERROR
⑥ 返回 taskId
```

**顺序不能反。** 先 submit 后提交事务，工作线程会在事务提交前去读任务行，读不到、
领取 CAS 影响 0 行、任务被当成已失效丢弃，随后事务提交——**任务行存在而没有任何人在跑它**。

**文件绑定的两步（事务内）：**

```sql
-- ① 锁定并校验：FOR UPDATE OF f 只锁文件行，不锁任务行（否则与工作线程的状态 CAS 争锁）
SELECT f.*, t.status, t.reanalyzable, t.deleted_at
  FROM ct_health_report_file f
  LEFT JOIN ct_health_report_task t ON t.task_id = f.task_id
 WHERE f.file_id IN (?) AND f.user_id = ? AND f.company_id = ?
 FOR UPDATE OF f;

-- ② 条件更新，把 oldTaskId 带进 WHERE 防两个请求同时抢
UPDATE ct_health_report_file
   SET task_id = :newTaskId, file_index = ?, expire_at = ?
 WHERE file_id = ? AND user_id = ? AND company_id = ? AND status = 'UPLOADED'
   AND (task_id IS NULL OR task_id = :oldTaskId);
-- 不写 update_time（§3.1.1）
-- 受影响行数必须 == 1，否则整个事务回滚，返回 FILE_ALREADY_BOUND
```

**两个线程池，必须分开：**

```java
// task.ExecutorConfig  —— 这不是「中间件配置类」，是业务线程池，允许写
@Bean("analysisExecutor")
ThreadPoolExecutor analysisExecutor() {
    return new ThreadPoolExecutor(
        W, W, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<Runnable>(QUEUE_CAPACITY),   // 有界
        new ThreadPoolExecutor.AbortPolicy());             // 满了直接抛
}

@Bean("llmBatchExecutor")
ThreadPoolExecutor llmBatchExecutor() { /* 大小 W × 4，供 §6.1 批次并发使用 */ }
```

```
不得用无界队列        用户排到文件 expire_at（30min）都到了，排到也没文件可读
不得用 CallerRunsPolicy 分析会跑在 Tomcat 请求线程上，分钟级占死，拖垮 Web 层
不得静默丢弃          任务永远停在 QUEUED，只能等 5 分钟巡检兜底
不得共用一个池        W 个任务各占一线程、又各自等待提交到同一池的 4 个批次
                     → 4×W 个批次全排队 → 线程饥饿死锁
                     → 心跳线程仍活着，心跳巡检【扫不出来】，只能等 deadline 兜底

W = min( floor(C / (4 × 实例数)),                    ← 模型配额，C 待确认（§12）
         floor((堆预算 - Web 层占用) / 单任务峰值) )   ← 内存预算，单任务峰值见 §6.2.5
【两个上界哪个小取哪个】—— 只按配额算会在内存上翻车（百 MB/任务 量级）
【实例数是必须项】本机线程池下每个实例独立跑满自己的 W，漏掉它会成倍超用配额
QUEUE_CAPACITY 需实测校准（设计方案 §11-14）
```

### 4.3 状态机、领取 CAS 与心跳

```
QUEUED ──→ PARSING ──→ EXTRACTING ──→ ASSEMBLING ──→ SUCCEEDED
             │             │              │
             └─────────────┴──────────────┴──────────────→ FAILED

纯单向，无回边。SUCCEEDED / FAILED 都是终态。
「重新解析」= 用同一批 fileIds 建一个全新 taskId，不是让旧任务回队列。
deleted_at 是正交标志，任何状态下都可置，一旦置上不可撤销。
```

**工作线程的第一件事是领取 CAS：**

```sql
UPDATE ct_health_report_task
   SET status='PARSING', heartbeat_at=now(), deadline_at=now()+INTERVAL 10 MINUTE,
       version=version+1
 WHERE task_id=? AND status='QUEUED' AND deleted_at IS NULL;
```

**影响 0 行 → 直接结束该任务的执行，不做任何事、不写终态。** 覆盖两种情况：
任务已被删除（含提交线程池之后用户立刻删）、状态已不是 `QUEUED`。

**`stage` 与 `progress` 的映射**（前端进度条用）：

| `stage` | 对应 `status` | `progress` | 前端文案 |
|---|---|---|---|
| `UPLOADING` | `QUEUED` | 0~30% | 正在上传文件... |
| `PARSING` | `PARSING` / `EXTRACTING` | 30~80% | 正在识别报告内容... |
| `ASSEMBLING` | `ASSEMBLING` | 80~100% | 正在生成分析结果... |

```
stage 只有三个取值，【不是六个】—— PARSING 与 EXTRACTING 合并成一个 stage
每阶段进入时写区间起点（0 / 30 / 80），前端在区间内做平滑动画
【后端不做区间内的细粒度递增】—— 那要求 Worker 频繁写库，而进度条的平滑归前端
SUCCEEDED 时 progress = 100，stage 不再有意义
```

**心跳：** 每 30s 更新 `heartbeat_at`，**由独立调度线程执行**——批次并行等待期间主流程
是阻塞的，心跳挂在主流程里会被巡检误杀。**心跳只更新 `heartbeat_at`，绝不顺延 `deadline_at`。**

**巡检（`TaskSweepJob`，每 5 分钟，xxl-job）三条并列：**

```sql
-- ① 进程死了：心跳停更
UPDATE ct_health_report_task SET status='FAILED', fail_code='SERVER_ERROR', reanalyzable=1
 WHERE status IN ('PARSING','EXTRACTING','ASSEMBLING') AND heartbeat_at < now() - INTERVAL 15 MINUTE;

-- ② 进程活着但跑不完：超过硬截止
UPDATE ct_health_report_task SET status='FAILED', fail_code='EXECUTION_TIMEOUT', reanalyzable=1
 WHERE status IN ('PARSING','EXTRACTING','ASSEMBLING') AND now() > deadline_at;

-- ③ 提交线程池前进程崩溃：卡在 QUEUED
UPDATE ct_health_report_task SET status='FAILED', fail_code='SERVER_ERROR', reanalyzable=1
 WHERE status='QUEUED' AND create_time < now() - INTERVAL 5 MINUTE;
```

①②的 `fail_code` 不同：①是「没人在跑了」，②是「在跑但跑不完」，合成一条排障时
分不清该查进程还是查模型延迟。

**重启即失败、不自动恢复**（本版接受的代价）：进程没了任务就没了，靠①在 15 分钟内收敛。
单实例部署可在启动时把非终态任务一次判失败以加速；**多实例绝对不可以**——
任务在哪个实例上跑没有落库，那会把别的实例正在跑的任务一起判死。

### 4.4 成功写入的顺序（跨存储，无事务）

**MySQL 与 Redis 之间没有事务，「同一逻辑步骤内」做不到。** 定顺序，让 MySQL 单方面决定可见性：

```java
// task.TaskStateService#markSucceeded
① redis.set("result:"+taskId, json, 2h)          // 此时结果【尚不可见】
② int rows = mapper.casSucceeded(taskId);        // 唯一的「提交点」
③ if (rows == 0) {
       redis.delete("result:"+taskId);
       // 超过 deadline → FAILED/EXECUTION_TIMEOUT；已删除/已被巡检判失败 → 什么都不做
   }
```

```sql
UPDATE ct_health_report_task
   SET status='SUCCEEDED', progress=100,
       expire_at = now() + INTERVAL 2 HOUR,   -- ★ 与结果 TTL 对齐，只有成功才顺延
       version = version + 1
 WHERE task_id = ?
   AND status = 'ASSEMBLING'
   AND deleted_at IS NULL
   AND deadline_at >= now();                  -- ★ 硬截止
```

**四个 WHERE 条件都不能少：**

```
status='ASSEMBLING'    幂等
deleted_at IS NULL     和用户删除抢同一行
deadline_at >= now()   否则超时后、巡检跑到前的窗口里任务照样能成功，deadline 从未拦住过东西
expire_at 顺延         否则第 30 分钟任务行被删，归属校验失去依据，结果的后 90 分钟读不到
```

**反过来做（先 CAS 再写 Redis）有真实坏窗口**：CAS 成功后崩溃 → 状态是 `SUCCEEDED`
而 Redis 没结果，用户轮到「成功」却拿不到内容，且状态机无回边、重试不了。

### 4.5 删除与清理

```
① UPDATE task SET deleted_at=now()
    WHERE task_id=? AND user_id=? AND company_id=? AND deleted_at IS NULL
   —— 任何状态下都允许，包括 SUCCEEDED 和执行中
② 删除：对象存储原文件、ct_health_report_file 行（整行删）、Redis result:{taskId}
③ 【不中断】已经在跑的工作线程 —— 不 Future.cancel、不发中断
④ 工作线程每次 CAS、每次写结果都带 deleted_at IS NULL；写回被④拦下
```

**正确性靠写回条件保证，不靠「能不能及时停下来」**——与 §6.1「任一批失败不取消其余批次」同源。

**清理任务（`CleanupJob`，每 5 分钟）必须按状态逐类判定，不能笼统「终态即删」：**

| 任务状态 | 原文件 | file 行 | task 行 | Redis 结果 |
|---|---|---|---|---|
| 执行中 | 保留 | 保留 | 保留 | — |
| `SUCCEEDED` | **立即删** | **立即删** | 至 `expire_at`（已顺延为 2h） | TTL 2h |
| `FAILED` 且 `reanalyzable=1` | **至 `expire_at`** | 至 `expire_at` | 至 `expire_at` | 无 |
| `FAILED` 且 `reanalyzable=0` | 立即删 | 立即删 | 至 `expire_at` | 无 |
| `deleted_at` 非空 | 立即删 | 立即删 | 至 `expire_at` | 立即删 |
| 孤儿上传（`task_id IS NULL`） | `expire_at` 到期删 | 同左 | — | — |

**原文件在任务整体 `SUCCEEDED` 后才删，不是解析成功后就删**——否则后续任一步失败产生
`reanalyzable=1` 时原文件已没了，「重新解析」在绑定校验处直接被拒。

##### 候选查询必须有单轮上限（2026-08-27 补）

```sql
-- 两条候选查询都是这个形状
... WHERE 〈宽口径谓词〉 ORDER BY expire_at, <主键> LIMIT #{batchSize}
```

**加 `LIMIT` 的理由不是省 CPU，是防故障放大。** 稳态下候选集被两小时保留期钳住，
根本不会随历史数据增长；但 `task` 行**只有在文件与结果都清干净之后**才会被物理删除：

```
对象存储不可用
  → 文件删不掉 → task 行删不掉
  → 候选集不再被两小时钳住，开始【无限增长】
  → 而这个巡检正是唯一能恢复它的东西，却随候选数线性变慢
  → 最终单轮超时或 OOM ——【越是需要它工作的时候它越跑不动】
```

有了上限，最坏情况从「巡检死掉」变成「每轮推进固定条数」。

**`ORDER BY expire_at` 不只是稳定顺序。** 已过期的 `expire_at` 小、排在前面；
非过期 `SUCCEEDED` 的是成功时刻 +2h、排在最后。于是真正有活干的天然优先，
**成功后无事可做的那批自然落在 `LIMIT` 之外**——单轮上限与减少空转由同一个改动解决。
不加排序的话，积压时真正该清的会被一批空转任务永久挤出候选。

**代价（不是白捡的）**：非过期 `SUCCEEDED` 且成功那一刻删文件失败的那种，
重试会被推后，最坏等到它过期（≤2h）才轮到。这是用「原文件多留最多两小时」
换「清理链路在存储故障下不会自己噎死」。

> **不要把清理矩阵收窄进 SQL。** 例如再加一个 `EXISTS` 去查「还有没有文件行」看似能省掉
> 空转，但那等于把上面那张六类矩阵复制成 SQL 谓词，**判断从一处变成两处，迟早判得不一样**。
> `SUCCEEDED` 之所以无条件入选，正是为了兜住「成功那一刻删文件失败」的重试路径。
> 精确判定只留在 `CleanupJob.cleanupTask` 一处。

**单轮候选数打满时打日志标记 `是否截断=true`**——存储故障时这是最早能看到的信号。

##### ⛔ 清理不得放在事务里，也不得对 file 行加锁（2026-08-27 补）

`TaskResourceCleanupService.deleteFiles` 的循环里每一步都要调对象存储。

**两条独立的结论，任缺一条都会有人把它加回来：**

```
① 事务会把数据库行锁的持有时间【绑到 S3 的响应时间上】
   存储抖动时 = 文件数 × 超时（5 个文件 × 5s = 25 秒一把锁）
   而同一张表上 lockForBinding 也在用 FOR UPDATE —— 真会互相撞
   这和「候选查询无上限」是同一场故障的两个面：一边越跑越慢，一边长时间占着锁

② 这个事务【原本就什么也没保证】
   异常在循环内部就被 catch 了，不会触发回滚，提交的本来就是部分删除的结果
   真要回滚反而更糟 —— 已经从 S3 删掉的对象【回滚不了】，
   数据库却把行恢复，留下指向不存在对象的行
```

**并发安全由 `deleteByFileAndTask` 的条件删返回行数保证**（`affectedRows != 1`），
与事务和行锁都无关。单个文件失败只影响它自己，其余照删，剩下的留给下一轮巡检。

`selectByTaskId` 同样不加 `FOR UPDATE`：另一个调用方 `TaskParseService`
只是把文件读出来解析，更不该锁。

---

## 5. 解析与 Segment

### 5.1 格式判定与路由

**逐格式判定，不信任扩展名，也不能只看 magic number：**

| 格式 | 判定 | 可读性校验 |
|---|---|---|
| PDF | `%PDF-` 头 | PDFBox 能打开、页数 ≥ 1 |
| JPG/PNG | magic number + **实际解码** | 解码成功、宽高 ≥ 100px、总像素 ≤ 8000 万 |
| DOCX | ZIP 容器 + 内含 `word/document.xml` | POI 能打开，且**正文非空或含 ≥1 张合规内嵌图片** |
| OFD | ZIP 容器 + 内含 `OFD.xml` | ofdrw 能打开、页数 ≥ 1 |
| DOC | OLE2 头 `D0CF11E0` + WordDocument 流 | POI 能打开，且**正文非空或含 ≥1 张合规内嵌图片** |

`.zip` 不是支持格式，直接拒。但 DOCX 与 OFD 自身就是 ZIP（magic 都是 `PK\x03\x04`），
必须解开查内部结构才能区分。

**PDF 是否有文本层：** 抽取字符数/页数 ≥ 50 且非空白字符占比 ≥ 30%，任一不满足走 OCR。
**第二道 PDF→OCR 触发条件见 §5.3**，它必须解析之后才判得了。

**解压炸弹防御（DOCX / OFD）：** 流式计数，**不信 `ZipEntry.getSize()`**；
累计解压字节 > 上限或压缩比 > 阈值立即中断。

### 5.2 Segment：回切原文的唯一凭据

```java
// parse.segment.Segment  —— 不可变
String  segmentId;      // f{fileIndex}-p{page}-s{seq}，例 f0-p2-s17
String  rawText;        // 解析器抽出的原始字符，一个字都不动 —— 展示与原文核对用
String  normalizedText; // NFKC → 部首映射 → 全角转半角 —— 送模型、匹配、比对用
TextSource textSource;  // NATIVE | OCR，决定 §6.4 的校验档位
BBox    bbox;           // 全来源保留，随批次输入给 LLM-A；Word 无坐标时 null
```

**切分粒度：**

| 来源 | 一个 segment 是 | 解析器**不做**的事 |
|---|---|---|
| PDF 原生文本层 | 一次 `Tj` / `TJ` 显示操作 | 不按字体/基线/字距/坐标二次合并；不识别表格、不聚类行列、不判断单元格 |
| OFD | ofdrw 的一个原子文本对象 | 同上 |
| OCR | 一个识别块 | 不合并相邻块 |
| DOCX / DOC | 一个段落；表格按 POI 的**显式** `<w:tc>` 切 | 不合并跨行/跨列单元格，不重排行列 |

**实现路径：覆写 `PDFStreamEngine` 的 `showTextString` / `showTextStrings`**，
一次显示操作产出一个 segment。

> **绝不使用 `PDFTextStripper`。** 它内部按行聚类，正是 `AGENTS.md` §3 点名的
> 「按 Y 坐标聚类成逻辑行」——用它等于把版面判断偷偷做回 Java。
> 该库能否稳定拿到绘制单元需实机验证（设计方案 §11-19）；拿不到只剩「全部 PDF 走 OCR」一条路。

```
seq 在文件内单调递增，一经分配不再变化
segmentId 是【进程内的稳定主键】，不落库（§3.1 无 segment 表）
它服务于跨批去重、跨文件不混淆、回切定位；模型侧看不到它（§5.5）
```

**规范化（`normalizedText`）：**

```
NFKC → RadicalNormalizeMap（U+2E80–U+2EFF，约 30 条手工映射）→ 全角转半角
```

> NFKC 只解决一半：康熙部首区（U+2F00–U+2FD5）有兼容分解会被自动还原，
> **CJK 部首补充区（U+2E80–U+2EFF）没有兼容分解，NFKC 对它无效**，必须手工映射。
> 未收录的字符**保留原样，不猜测替换**（§0.3-③）：替错一个字会让 normalizedText 变成看似正常实则错字的文本。

### 5.3 绘制单元密度闸

有些 PDF 生成器逐字发 `Tj`，此时绘制单元就是单个字形。**不退化为字形，整文件改走 OCR：**

```java
// parse.segment.GlyphDensityGate
if (segmentCount / effectivePageCount > MAX_SEGMENTS_PER_PAGE) {   // 暂定 400
    // 判定为「逐字形绘制」→ 该文件整体改走 OCR 路径
    // textSource 记为 OCR，包含性校验走放宽档（§6.4）
}
```

**OCR 路径同样受 400 块/页约束，超限【整任务 FAILED / UNREADABLE】**，不做局部截断：
局部截断要新定义 `partial_reason` 枚举、`processedPages` 算法、模块开关，而定义完风险仍在
——被丢掉的那页恰好是过敏筛查页时，§6.5-A 的关键词扫描也扫不到它（整页没进来）。

阈值 400 需用真实样本校准（设计方案 §11-7b）。

### 5.4 容量限制与页数截断

**三档，不是两档**（`totalPages` = 单任务累计等效页数）：

本节全部发生在 `PARSING` 阶段：所有 Word 完成 OCR 并算出精确容量后，先做上限裁决，
再做前 30 页保留，最后才能进入 `EXTRACTING` 调用 LLM-A。

| `totalPages` | 处理 | 结果 |
|---|---|---|
| ≤ 30 | 全部处理 | 四个模块正常输出 |
| **31 ~ 60** | **只处理前 30 页** | `partial=true`、`partial_reason=PAGE_TRUNCATED`，**模块三四不输出** |
| > 60 | 无 Word 时创建任务前拒绝；含 Word 且仅 OCR 后确认超限时，Worker 失败 | `failCode = PAGE_LIMIT_EXCEEDED` |

```java
// parse.PageBudgetService —— 31~60 档的唯一执行点
// 输入：解析后的精确 FileCapacity 列表；Word 必须已经完成 OCR 与 §4.1.2 的独立上限检查
// 按 fileIndex 升序、文件内按页序累计，累计到第 30 等效页为止；其后内容不进送给 LLM-A 的保留序列
// 【不是按文件整份丢弃】—— 第 3 个文件的前半截该处理就处理
int processedPages;   // 实际进入解析的等效页数，≤ 30
int totalPages;       // 精确累计等效页数；含 Word 时可能在 Worker 才确认 > 60
```

```
单个 Word：exactSegmentCount > 1200 或 embeddedImageCount > 30
    → FAILED / PAGE_LIMIT_EXCEEDED / reanalyzable=false，不截断、不调用 LLM-A

任务累计：Word 仍参与 31~60 档。
    Word 每 40 个有序 segment 为一个逻辑页；截断点落在 Word 内时，只保留对应的前 N 个 segment。
    例：PDF 20 页 + Word 800 segment = 40 页 → 保留 PDF 20 页 + Word 前 400 segment。

精确 totalPages > 60：
    不含 Word却在 Worker 命中 → 上游预检违约，FAILED / SERVER_ERROR
    含 Word且由 OCR 块推高后命中 → FAILED / PAGE_LIMIT_EXCEEDED / reanalyzable=false
    两种都不调用 LLM-A
```

两个字段**必须落进结果并下发前端**（§4.1 result 接口）。

> 截断后关模块三四的理由：总检结论与过敏筛查几乎总在报告末尾（设计方案 §3.3.2）。

```
Word 不按页计算：ceil(segment 数 / 40) 记为等效页（设计方案 §3.3.1，系数待校准 §11-8）
Word 内嵌图片：≥300×300px 提取走 OCR，【产出的识别块也是 segment】，计入上面的分母
              <300×300px 视为装饰图，忽略
```

### 5.5 批次编址：模型侧只见块号

**模型看不到也用不着 `segmentId`。** 每页一个页眉，每块一个批内块号 `blockRef`（0 起连续）：

```
每行格式：[块号] (textSource, bbox=x,y,w,h) 文本

=== 第 2 页 ===
[15] (NATIVE, bbox=72,110,180,22)  血脂检查
[16] (NATIVE, bbox=72,168,120,20)  甘油三酯
[17] (NATIVE, bbox=200,168,40,20)  2.8
=== 第 3 页 ===
[18] (NATIVE, bbox=72,110,180,22)  肝功能
```

```java
// llm.a.BatchAddressing
List<Segment> renderOrder;                    // 按 seq 升序，【渲染顺序是契约的一部分】
String render(List<Segment> segments);        // 生成上面的文本
String expand(int blockRef);                  // blockRef → segmentId，查数组
```

```
页眉必须给【报告上的真实页码】，不是「本批第几页」
    —— sectionRelation 的 CONTINUATION 判断依赖模型知道自己在第几页（§6.2）
bbox 必须逐块给，不能只给页面图
    —— 解析器不聚类行列，模型判断「这五块是同一行」只看渲染顺序判不出来：
       双栏页上左右两栏的块会交替出现
映射表由 Java 在发请求时构造并持有，收到响应立刻展开（§6.3）
展开之后 blockRef 不再出现在任何下游逻辑里
```

**（2026-09-02）「每批输入预算 ≤ 60k token（硬约束）」已删除**，见设计方案 §4.1.5。
实测 8 页带图 **89k~101k token**，超了 1.7 倍而链路一路跑通——那个数既不准也从未被执行。
原估算 ≈49k/批 偏低约 2 倍，主因是没把每块的 `bbox=` 前缀算进去（每页文本实测 ≈14,577 token）。

解析阶段的 **400 块/页** 上限继续有效，它防的是 segment 数失控，与 token 预算无关。

---

### 5.6 图像渲染与压缩（发 LLM-A 之前的唯一入口）

**只有一个类产出发给 LLM-A 的图**：`parse.ExtractionImageCompressor`。
其余任何地方都不得自己渲染或编码 JPEG——否则参数会分叉，而分叉后没人发现。

#### 5.6.1 两档压缩，档位固定不可调

```
档 1（默认）   长边 2000px，JPEG quality 0.85
   压缩后 ≤ maxImageBytes(1MB) → 用它
档 2（回退）   长边 1600px，JPEG quality 0.80
   压缩后 ≤ 1MB → 用它
仍 > 1MB      → 抛 ImageTooLargeException，【调用前失败】，不进批次
```

```java
// parse.ExtractionImageCompressor
CompressedPageImage compressForExtraction(BufferedImage source);   // 不落盘、不入 S3

/** 压缩结果。<b>必须返回实际宽高</b>——bbox 换算要用它（§5.6.6）。 */
class CompressedPageImage {
    byte[] jpegBytes;
    int    width;        // 压缩后实际像素宽（可能是 2000 档、1600 档，或小图保持原尺寸）
    int    height;       // 同上
}
```

> **只返回 `byte[]` 是不够的**：调用方无从知道最终用了哪一档、小图有没有被放大、
> 旋转归一化后的实际宽高是多少——而这三样都直接决定 `bbox` 的换算系数。

**为什么只有两档、且到此为止**：再往下压就要牺牲小字可读性。
**全案宁可失败也不发一张糊图**——压糊了模型会读错，而那是**静默错误**：
页面正常显示、数值是错的。

**但失败时用 `IMAGE_TOO_LARGE`，不是 `UNREADABLE`**（§5.6.5）：
压不下去与看不清是两回事，给用户的提示也不同。

#### 5.6.2 压缩发生在 OCR **之后**，两者用不同的图

**那一次渲染按 OCR 的规格来，不是按 LLM-A 的规格来：**

```
渲染分辨率 = OCR 档（300 DPI，A4 约 2480×3508；长边上限 3600px）
   —— 【不能按 LLM-A 的 2000px 渲染】：OCR 是识别精度的源头，
      2000px 长边对 A4 只有约 170 DPI，小字号会掉字，而掉的字后面全链路都补不回来
   —— LLM-A 那 2000/1600px 是从这张图【降下来】的，不是重新渲染
```

```
渲染一次 BufferedImage（PDFBox / ofdrw，OCR 档分辨率）
   ├─→ ① OcrImageEncoder 编码 → OCR（§5.6.2.1），【不压质量】
   └─→ ② ExtractionImageCompressor 降采样 + 压缩 → BatchPage.jpegBytes
   ③ 立即释放 BufferedImage
```

> **内存要按 OCR 档重算**：2480×3508×3B ≈ **26MB/页**（不是 2000×2800 的 16MB）；
> 长边 3600px 时约 **39MB/页**。**必须逐页处理、用完即释放**，
> 一个批次 8 页若同时持有 BufferedImage 就是 200~300MB——`W` 的内存上界（§4.2）要把它算进去。

**顺序不能颠倒，也不能只渲染压缩图给 OCR 用。** ①② 共用同一次渲染，渲染只做一次；
但 **OCR 拿高清编码、LLM-A 拿压缩图**。

#### 5.6.2.1 `OcrImageEncoder`：给 OCR 的编码，参数独立于 LLM-A

`recognize(byte[])` 要字节，而 PDF/OFD 渲染出来的是 `BufferedImage`——中间这一步必须有明确规格：

```java
// parse.OcrImageEncoder —— 与 ExtractionImageCompressor 【完全独立】的两套参数
byte[] encodeForOcr(BufferedImage source);
```

```
格式    PNG 无损优先 —— OCR 的输入不做有损压缩，JPEG 在小字周围的振铃会直接吃掉笔画
        PNG 超过 effectiveOcrImageBytes → 回退 JPEG quality 0.95（仍远高于 LLM-A 的 0.85）
        再超 → FAILED / IMAGE_TOO_LARGE，与 §5.6.5 同一条
尺寸    不缩放，就用渲染出来的那张
释放    编码完立刻释放 BufferedImage 与编码器（ImageWriter / ImageOutputStream，finally）
```

##### 三个上限是三件事，不能合成一个

**上一版把「OCR 单图上限」和「OCR 请求体上限」当成同一个数，那是错的**：
OCR 若用 JSON + Base64，8MB 原图会变成约 10.7MB 的 Base64，再加 JSON 骨架还要更大
——**「请求体最大 8MB」不等于「能发 8MB 原图」**。multipart 也有边界与请求头开销，只是小得多。

```
PRODUCT_IMAGE_UPLOAD_MAX_BYTES = 10MB        产品规定的图片上传上限，【不随 OCR 变】
ocr.maxEncodedImageBytes                     OCR 声明的单张原图字节上限
ocr.maxRequestBodyBytes                      OCR 完整 HTTP 请求体上限
```

**先由后两者推出「有效 OCR 图片上限」，编码方式决定怎么推：**

```
JSON + Base64   effectiveOcrImageBytes = min( ocr.maxEncodedImageBytes,
                                              (ocr.maxRequestBodyBytes - JSON骨架预留) × 3 / 4 )
multipart       effectiveOcrImageBytes = min( ocr.maxEncodedImageBytes,
                                              ocr.maxRequestBodyBytes - multipart开销预留 )
```

**再取产品上限与它的较小者，作为实际上传上限：**

```
实际上传上限 = min( PRODUCT_IMAGE_UPLOAD_MAX_BYTES, effectiveOcrImageBytes )

【方向只能往下，不能往上】
    OCR 允许 20MB  → 上传上限仍是 10MB（产品说了算，不因 OCR 宽松就放宽）
    OCR 只允许 6MB → 上传上限降到 6MB，【这是产品可见的降级，必须报给产品】
```

**三条送图进 OCR 的路，全部用同一个 `effectiveOcrImageBytes`：**

| 入口 | 字节从哪来 | 检查点 |
|---|---|---|
| 上传的 JPG / PNG | 原始上传字节，直传（§5.6.3-④） | **上传接口**按上面的「实际上传上限」拒 |
| Word 内嵌图片 | 从 docx 抽出的原始字节，直传 | **解析时**逐张比 `effectiveOcrImageBytes` |
| PDF / OFD 渲染图 | `OcrImageEncoder` 产出 | 编码时比 `effectiveOcrImageBytes`（上面那段） |

```
上传路径按【实际上传上限】拒，其余两条按【effectiveOcrImageBytes】拒
    —— 前者更严（还叠了产品上限），所以直传路径天然合法，永远不需要为迁就 OCR 而重编码
    —— 而重编码正是最想避免的（要整幅解码，§5.6.3）

Word 内嵌图片超限 → FAILED / IMAGE_TOO_LARGE
    【不静默跳过那张图】—— 跳过等于悄悄丢掉报告的一部分内容（§6.2 零 segment 同源）
```

> **`effectiveOcrImageBytes` 由启动时算出并打日志**，不写死常量——
> 它依赖 §0.4 的两个接入答案，写死就等于把外部约束硬编码进代码。

##### 入口提前拒 + 客户端按真实请求体兜底，两层都要有

`effectiveOcrImageBytes` 是**按协议开销估出来的**，而 JSON 骨架大小、multipart 边界长度
都可能与真实协议对不上。**估算只能提前拒绝，不能保证发出去的请求一定合规。**

```java
// 启动自检（与 §6.2.1.1 的 baseUrl/model/apiKey 自检一起做）
if (ocr.maxEncodedImageBytes <= 0 || ocr.maxRequestBodyBytes <= 0) {
    throw new IllegalStateException("OCR 容量参数未配置");      // 启动失败，不要跑到第一次调用
}
if (ocr.maxRequestBodyBytes <= PROTOCOL_FIXED_OVERHEAD_BYTES) {
    throw new IllegalStateException("OCR 请求体上限小于协议固定开销，配置有误");
}
// effectiveOcrImageBytes 算出后打 INFO 日志，值 <= 0 同样启动失败

// PaddleOcrClient：请求【组装完成之后、发送之前】再兜一次
byte[] requestBody = buildOcrRequest(imageBytes);
if (requestBody.length > ocr.getMaxRequestBodyBytes()) {
    // 说明估算的协议开销偏小 —— 【不发请求】，映射为 IMAGE_TOO_LARGE
    throw new ImageTooLargeException(requestBody.length, ocr.getMaxRequestBodyBytes());
}
```

```
【全部容量计算用 long】
    maxRequestBodyBytes × 3 / 4、字节数累加、Base64 膨胀 —— int 在 2GB 处溢出成负数，
    而负数会让 "> 上限" 的判断恒为假，超限图反而畅通无阻。这类溢出不报错，只会静默放行

两层的分工：
    入口（effectiveOcrImageBytes）  提前拒，让用户在上传时就知道，而不是任务跑一半失败
    客户端（真实请求体）             兜底，防估算偏差把不合规的请求发出去
    —— 只有前者会漏，只有后者用户体验差，两层都要有
```

> **绝对不能复用 LLM-A 的压缩图给 OCR。** 那是 0.85 / 0.80 质量、还可能降到 1600px 的图，
> 拿它识别等于把 §5.6.1「宁可失败也不发糊图」的理由反过来用在 OCR 上——
> 而 OCR 掉的字**后面所有环节都补不回来**（segment 没了、来源校验没了、模型也看不到）。
>
> **上传的 JPG/PNG 不走本编码器**：它们本来就是编码字节，直接交给 OCR（§5.6.3-④），
> 不解码、不重编码。

**上传的 JPG / PNG 同样要过压缩器。** §5.1 允许 8000 万像素的图上传，
原图 base64 之后单张就可能几十 MB——**不压缩必然撞 `maxRequestBodyBytes`**。

**Word 的图片不进这条链路**：内嵌图片只做 OCR，产出识别块作为 segment；
图片本身不发 LLM-A（§6.2.1、设计方案 §3.3.1）。

#### 5.6.3 大图不得先整幅解码

§5.1 的可读性校验要求"实际解码后判断总像素"，而 §5.6.2 又把整幅解成 `BufferedImage`
——**一张 8000 万像素的图 RGB 约 240MB、ARGB 约 320MB，还没进压缩器就可能拖垮共享堆**（§4.2）。

```
① 先用 ImageIO.getImageReaders + reader.getWidth/getHeight 读【尺寸】，不整幅解码
② 总像素 > 上限 → 立即拒绝（§5.1 的 FILE_TOO_LARGE），此时堆里只有几 KB 的文件头
③ 发 LLM-A 的图用【降采样解码】：ImageReadParam#setSourceSubsampling
   直接解成接近目标尺寸的位图，【不先生成 8000 万像素的 BufferedImage 再缩】
④ OCR 接口【锁定为吃编码字节】，不是条件句：
       List<OcrBlock> recognize(byte[] encodedImageBytes);
   上传的 JPG/PNG 与 Word 内嵌图片【直接把原始编码字节交给 OCR】，本地不解码
   只有"发 LLM-A 的图"这条路径才降采样解码
```

**②③ 缺一不可**：只做 ② 挡得住超限图，挡不住"刚好在上限内的 7000 万像素图"；
只做 ③ 则超限图仍会被读到尺寸之后继续走流程。

> **④ 不能留成"若支持"。** 写成条件句的话，OCR 服务不支持时就退回本地整幅解码，
> 8000 万像素照样变成 240~320MB 位图——**那正是 ①②③ 想避免的**。
>
> **若 `PaddleOcrClient` 确实做不到吃编码字节**，正确的应对是
> **调低 §5.1 的像素上限**（按本地解码能承受的堆算），而不是留一条会打爆堆的分支。
> 列入 §0.4 的接入前确认。

#### 5.6.4 压缩实现的确定性细节

这些都是确定性图像处理，不属于 `AGENTS.md` §3 禁止的"复杂语义判断"，但**不写死就会各写各的**：

```
不放大        源图长边 ≤ 目标长边时【保持原尺寸】，只重新编码；放大既费体积又不增信息
透明背景      PNG 带 alpha → 转 JPEG 前【铺白底】；不铺会变黑或编码失败
色彩空间      统一转 TYPE_INT_RGB；不要把 TYPE_BYTE_GRAY / 带 ICC 的图直接交给 JPEG Writer
缩放算法      固定用 Graphics2D + RenderingHints.VALUE_INTERPOLATION_BILINEAR
              —— 换算法会改变输出字节，进而改变"是否超 1MB"的判定，档位就不可复现了
方向归一化    在【渲染阶段】处理：PDF 按页面 /Rotate，上传图按 EXIF Orientation
              压缩器拿到的永远是"正着的"图（§5.6.6 的 bbox 换算依赖这一点）
资源释放      ImageWriter / ImageOutputStream / Graphics2D 都要 dispose/close，
              放在 finally；JPEG Writer 不释放会攒住 native 内存
```

#### 5.6.5 渲染失败与压缩失败的归属

```
渲染失败（损坏页、加密页、字体缺失）  → 该页无图
    该页 imageRequired = true 时       → 整任务 FAILED / UNREADABLE
    —— 不是 SERVER_ERROR：我们确实没能把这一页变成可读的东西，
       与 §6.2 的零 segment 同源，用户换一份清晰文件才有意义

压缩两档都超限                         → FAILED / IMAGE_TOO_LARGE  【不是 UNREADABLE】
```

> **压缩超限和"读不清"是两回事，不能共用一个错误码。**
> 一张高噪声拍照图可能非常清晰，只是 JPEG 压缩率低——系统真正的问题是
> **"无法满足模型的请求大小限制"**，不是"这张图看不清"。
> 归到 `UNREADABLE` 会给用户一句「文件无法读取，请检查文件是否完整」，
> 而他的文件完全没问题，**照着提示做也解决不了**。
>
> `IMAGE_TOO_LARGE` 的 `reanalyzable = 0`：同一份文件重试结果一样，
> 用户需要换一张分辨率更低或噪点更少的图。

**不要让它走到 §6.2.1.1 的 `assertPageListValid`**——那里抛的是
`IllegalStateException`（编程错误语义），而渲染失败是**数据问题**，
应该在解析阶段就判掉并给出正确的 `failCode`。

#### 5.6.6 `bbox` 与渲染尺度必须同源

`bbox` 的基准是「同一批下发的那张页面渲染图」（§5.5），而解析器给的坐标是
**PDF 用户空间的点**，渲染图是**像素**——中间必须换算，且换算系数必须来自
**同一次渲染的实际尺寸**：

**下游只认一种坐标契约，各解析器自己转到这个契约上：**

```
Segment.bbox 的定义 = 【原始渲染图上的像素坐标，原点左上、Y 向下】
    —— 不是 PDF 点、不是 OFD 页面单位、不是任何"原生"坐标系
```

**转换发生在各自的解析器里，下游一律不做来源判断：**

| 来源 | 原生坐标 | 解析器负责做什么 |
|---|---|---|
| PDF 原生文本 | PDF 点，原点**左下**，Y 向上 | 乘渲染 DPI 缩放 + **Y 轴翻转** |
| OFD | OFD 页面单位（毫米），原点见规范 | 换算到渲染图像素 + 按需翻转 |
| OCR | **已经是图像像素、原点左上** | **原样透传，不做任何翻转** |
| 上传图片（走 OCR） | OCR 返回的图像像素 | **取决于 OCR 有没有应用 EXIF，见 §5.6.6.1** |
| Word | 无版面坐标 | `bbox = null` |

> **上一版把「Y 轴翻转」写在下游是错的**：那对 PDF 是对的，对 OCR 就是**把坐标上下颠倒**
> ——而 OCR 路径恰恰是扫描件、拍照件的主流形态。写成"下游统一翻转"会让最常见的那类输入全错。
>
> **每种来源只有它自己的解析器知道原生坐标系长什么样**，转换必须留在那里。
> 下游拿到的永远是同一种东西，这也是 §5.5 把 `bbox` 渲染进提示词时不需要解释坐标系的前提。

**下游唯一要做的换算：原始渲染图 → 压缩图。**

> **（2026-09-02）这条换算此前一直没接。** `BBox.scale()` 写好了却只有测试在调，
> `BatchAddressing` 直接输出原 bbox——PDF 原生文本层路径上，模型拿到的坐标
> 与它看到的压缩图**整体错位**。现已在 `FileParseService.parsePdf` 的页循环里接入
> （`scaleToExtractionImage`），系数取 `CompressedPageImage` 的实际宽高。
> OCR 路径 bbox 恒为 null、OFD 不发页面图，两者都不需要换算。

```java
// 系数【必须来自 CompressedPageImage 的实际宽高】，不是配置里的 2000
double scaleX = (double) compressed.getWidth()  / renderedWidthPx;
double scaleY = (double) compressed.getHeight() / renderedHeightPx;
x_px = bbox.getX() * scaleX;   y_px = bbox.getY() * scaleY;
w_px = bbox.getWidth() * scaleX;  h_px = bbox.getHeight() * scaleY;
// 【没有 Y 轴翻转】—— 两边都是左上原点，翻了就错
```

**两个坑各错一次都会让坐标全错，而且都不报错：**

```
① 用「2000 / 页面长边」硬算 —— 档 2 回退到 1600px 时系数就变了，坐标整体偏 25%
② 在下游翻 Y 轴           —— PDF 对了，OCR 全反
【旋转必须先归一化】：页面 /Rotate 与图片 EXIF 方向都在渲染阶段处理掉，
                     解析器换算时面对的永远是"正着的"页面（§5.6.5）
```

由 R66c 与 R66f 锁住。

##### 5.6.6.1 上传图片的 EXIF 方向（当前协议下不产生任何计算）

> **已由接入截图结论：本节三问在当前 OCR 协议下全部落空，但本节不删。**
> PaddleOCR-VL 走的是 OpenAI 兼容的 `/chat/completions`，响应里只有一个
> `choices[0].message.content` 字符串，**没有任何坐标字段、也没有图像宽高字段**（§5.6.7）。
> 没有坐标就没有坐标系，「要不要做 EXIF 变换」这个问题当前无从谈起：
> `OcrPageSegmentFactory` 产出的每个 OCR segment 的 `bbox` 恒为 `null`。
>
> **保留本节与 `OcrBboxNormalizer` 的理由**：一旦换成回传版面坐标的 OCR 接口（PaddleOCR
> 的版面分析接口本身是有坐标的，只是这个网关没暴露），下面三问会原样回来。
> 删掉分析再重新推导一遍，比留着它贵得多。
>
> **对应断言**：`PaddleOcrVlClientTest#everyBlockShouldHaveNullBboxBecauseProtocolCarriesNoCoordinates`
> ——把「无坐标」钉成契约，避免下游有人按「OCR 有 bbox」写代码。

上传的 JPG/PNG 走两条不同的路，**两条路对 EXIF 的处理必须一致，否则坐标必然错位**：

```
OCR  路径：原始编码字节直接给 OCR（§5.6.3-④）—— EXIF 还在字节里
LLM-A 路径：本地解码时按 EXIF 归一化（§5.6.4）—— 图已经"转正"
```

**`Orientation = 6`（顺时针 90°）时宽高会互换**，两边差的不是几个像素，是整个坐标系。

**接入前必须从 OCR 服务方拿到三个答案**（列入 §0.4）：

| 要问的 | 拿到之后怎么做 |
|---|---|
| OCR **是否读取并应用 EXIF Orientation** | 应用了 → 它的坐标与我们归一化后的图同系，**原样透传** |
| OCR 返回的坐标基于**哪张图的宽高**（旋转前还是旋转后） | 没应用 → 后端按 EXIF 值做一次**确定性坐标变换**，转到归一化后的坐标系 |
| OCR 响应**是否回传它所用图像的宽高** | 回传了才能校验我们的假设；不回传就只能盲信，需在评测集里抽查 |

```
若 OCR 既不应用 EXIF、也不回传图像宽高
    → 只剩一条路：后端在发给 OCR 之前【先按 EXIF 转正并重新编码】
    → 代价是这类图必须本地解码，与 §5.6.3-④「不解码」冲突
    → 那就必须同步【调低 §5.1 的像素上限】，与「OCR 吃不了编码字节」是同一种应对
```

**坐标变换本身是确定性的**（8 个 Orientation 值各对应一个固定的旋转/镜像矩阵），
不属于 `AGENTS.md` §3 禁止的复杂判断；**危险的是不知道该不该做这次变换**。

#### 5.6.7 OCR 调用契约：PaddleOCR-VL 直连（RestTemplate）

**协议与 LLM-A 完全同构**：同一个网关、同一套 OpenAI 兼容 `/chat/completions`、
同样的 `Authorization: Bearer`，只是模型标识与 `base-url` 各走各的配置。
因此 §6.2.1.1 的四条策略（超时、零重试、脱敏、容量上限）在 OCR 侧原样适用，
`StatusOnlyErrorHandler`、`BoundedResponseExtractor`、`CappedByteArrayOutputStream`
三个类直接复用——**它们的职责与是哪个模型无关**。

##### 连接参数

| 项 | 值 | 说明 |
|---|---|---|
| Endpoint | `{ocr.base-url}` + `{ocr.chat-completions-path}` | 路径默认 `/v1/chat/completions` |
| 测试环境 `base-url` | `http://higress-http.region-4-c86-test.test-kzx1.cncb/public` | 网关自身的 Base URL 已含 `/public/v1`，**拆分点在 `/public` 之后**——把 `/v1` 留给路径配置，才能与 LLM-A 用同一个 `chatCompletionsPath` 默认值 |
| 鉴权 | `Authorization: Bearer ${OCR_API_KEY}` | 只由环境变量注入，代码库不保存真值 |
| 模型 | `ocr.model`，测试环境为 `PaddleOCR-VL-0.9B` | 模型广场展示名是 `PaddleOCR-VL-09B-K100`，**请求里要用的是 `PaddleOCR-VL-0.9B`**，两者不同 |
| 机房 / 能力 | 深圳南湾机房 / 视觉理解 | |

##### 请求

```json
{
  "model": "PaddleOCR-VL-0.9B",
  "temperature": 0,
  "messages": [
    {
      "role": "user",
      "content": [
        { "type": "image_url",
          "image_url": { "url": "data:image/png;base64,<页面图的编码字节>" } },
        { "type": "text", "text": "<PaddleOcrVlClient.TRANSCRIBE_INSTRUCTION>" }
      ]
    }
  ]
}
```

**三条不能改的地方：**

```
① 图片用 data URI 内联，【绝不用 http 外链】
   平台示例写的是 "url": "http://28.105.108.85:80/image/nan.png"，
   照抄等于把报告页图发布到一个能按 URL 取回的位置 —— 这正是 §6.2.1 拒绝 Dify 的理由
   （Dify 文件上传没有删除接口），换个存储不会让这条理由消失
② temperature = 0
   同一张图必须得到同一份文本。识别结果有采样波动时，
   §6.4 的来源包含性校验会随机地过或不过，排障时无法复现
③ 媒体类型按文件头判定，只认 PNG 与 JPEG，认不出直接抛
   猜一个类型会让服务端拿到声明与内容不符的图，失败形态取决于服务端实现
```

**只有一条 `user` 消息、没有 system 消息**：这不是省略，是 OCR 不需要提示词工程——
指令写成常量 `PaddleOcrVlClient.TRANSCRIBE_INSTRUCTION`，**正文以代码为准，本文不复述**
——复述必然过期。当前它要求：逐行输出全部文字、不得省略页眉页脚与个人信息、
不要输出 Markdown 或 `<fcel>` 表格标记。

> **（2026-09-02）原先只写「输出图片的文字」。** 实测同一张图三次调用返回三种格式
> （纯文本 / `<fcel>` 表格标记 / Markdown 表格），且出现过页头信息整段漏识别。
> 指令收紧是「尽量」的那一半；不依赖模型配合的那一半在
> `OcrContentSplitter` 的格式归一化里（设计方案 §3.2.1）。
**它不进 `prompt/versions.tsv` 版本台账**：台账管的是会实质影响输出语义、
改一次就要重跑全量的提示词；这一句改了等于换 OCR 接口，会被本节的契约测试直接拦下。

##### 响应

```json
{
  "id": "chatcmpl-2ff4e9b0-e543-92d6-97dd-cca4fd37db50",
  "object": "chat.completion",
  "created": 1787708861,
  "model": "PaddleOCR-VL-0.9B",
  "choices": [
    { "index": 0,
      "message": { "role": "assistant", "content": "<整页文本>", "tool_calls": [] },
      "finish_reason": "stop" }
  ],
  "usage": { "prompt_tokens": 2593, "total_tokens": 3531, "completion_tokens": 938 }
}
```

| 取哪个字段 | 用途 |
|---|---|
| `choices[0].message.content` | **唯一有用的字段**，整页文本 |
| 其余全部字段 | 不读、不落库、不进日志 |

**`content` 不是文本就整批失败**，不做兜底解析：`extractContent` 判 `isTextual()`，
不满足抛 `OcrCallException(SERVER_ERROR, 200)`。

##### 从整页文本到识别块：按行切，不合并

协议只有一个字符串，而 §5.2 要求「一个识别块 = 一个 segment，不合并相邻块」。
本接入下**一行就是一个识别块**：

```
OcrContentSplitter.split(content)
    ① 统一 \r\n 与 \r 为 \n
    ② 按 \n 切，逐行 trim
    ③ 丢弃 trim 后为空的行
    ④ 每块 bbox 恒为 null（§5.6.6.1）
```

这是可穷举输入的确定性字符串处理，按 `AGENTS.md` §3 属于 Java 的职责，
**不交给模型、也不按坐标重排**（本来也没有坐标可排）。

**R43（某页识别块 > 400 → 整任务 `FAILED/UNREADABLE`）在本接入下按行数判**，
判定位置不变，仍在解析编排层，不在客户端里。

##### 容量：入口提前拒 + 客户端兜底，两层都在

§5.6.2.1 定的两层在代码里的落点：

| 层 | 类 | 判什么 |
|---|---|---|
| 入口 | 上传接口 / Word 解析 / `OcrImageEncoder` | 比 `OcrProperties.effectiveOcrImageBytes` |
| 客户端① | `PaddleOcrVlClient.recognize` 开头 | 再比一次 `effectiveOcrImageBytes`，超限**不组装请求** |
| 客户端② | `CappedByteArrayOutputStream` | 组装过程中超 `maxRequestBodyBytes` **立即抛**，不会先生成完再判 |
| 客户端③ | 组装完成后 | 再比一次真实请求体长度——估算的协议开销可能偏小 |

三处都抛 `parse.ImageTooLargeException`（`FailCode.IMAGE_TOO_LARGE`）。
**客户端①看起来与入口重复，但它拦的是「入口漏了」**：Word 内嵌图片、
渲染图、直传图三条路各有各的入口检查，任一条漏写都会在这里被挡住。

##### 内存：OCR 的 base64 峰值比 LLM-A 大一个量级

```
LLM-A：压缩到 2000px / 0.85 质量，约 800KB → base64 String 约 2.13MB（§6.2.5）
OCR  ：300DPI PNG 无损，最大到 effectiveOcrImageBytes（可能接近 8MB）
        → base64 String 约 21MB，瞬时峰值
```

**OCR 是逐张调用、不成批**，所以峰值是「单张」而不是「8 张」；
但 `W` 个任务并发时这个数要乘 `W`，与 Web 层共用同一个堆（§4.2）。
**这是接入 OCR 之后 §4.2 内存预算必须重算的唯一新增项。**

##### 配置键

```
连接三项（在 application.properties，环境变量注入）
    ocr.base-url                    ⛔ 无默认值
    ocr.model                       ⛔ 无默认值
    ocr.api-key                     ⛔ 无默认值，绝不进代码库与日志
    ocr.chat-completions-path        默认 /v1/chat/completions
    ocr.max-response-body-bytes      默认 4MB，有界读取
    ocr.connect-timeout-millis       默认 10s
    ocr.read-timeout-millis          默认 120s，必须 < §4.3 的 deadline_at

契约六项（【不在 application.properties】，是 §0.4 的接入答复）
    ocr.max-encoded-image-bytes     ⛔ 仍未拿到真值
    ocr.max-request-body-bytes      ⛔ 仍未拿到真值
    ocr.request-encoding             = JSON_BASE64（已确认）
    ocr.accepts-encoded-bytes        = true（已确认：data URI 承载的就是编码字节，本地不解码）
    ocr.applies-exif-orientation     当前协议下不参与任何计算（§5.6.6.1）
    ocr.returns-image-dimensions     = false（响应无该字段）
```

**两个绑定类，同一个 `ocr.*` 前缀**：`parse.OcrProperties` 管容量与接口契约，
`infra.OcrConnectionProperties` 管地址与超时。分开不是洁癖——
**前者的启动自检必须与部署环境无关**，混在一起就没法在不给 base-url 的单测里验容量公式。

##### 启动自检的分工（不写重复分支）

```
parse.OcrProperties#afterPropertiesSet   容器刷新阶段：六项契约齐全、accepts-encoded-bytes、
                                         EXIF 与宽高的互斥约束、effectiveOcrImageBytes 计算
infra.OcrStartupValidator                ApplicationRunner：base-url/model/api-key、超时为正、
                                         request-encoding 必须是已实现的 JSON_BASE64、
                                         请求体与响应体上限不超过 int 上限
```

后者**刻意不重复前者的容量判断**——容器刷新在 `ApplicationRunner` 之前，
重复写出来的分支永远执行不到，那是比没有断言更糟的一种断言。

**`request-encoding` 配成 `MULTIPART` 会启动失败**，不会静默按 JSON 发出去：
公式支持两种编码，但客户端只实现了一种，两者的差异必须显式。

**上限的 int 检查有实际后果**：有界缓冲按 `int` 分配，
配一个大于 2GB 的值会被 `Math.min` 静默截断成 2GB，从此容量上限就不是配的那个数了。


### 5.7 阶段编排：从上传字节到四个模块

零件齐了不等于链路通了。**三个阶段各有一个编排类**，`AnalysisTaskWorker`
只负责阶段顺序、状态机推进与失败收敛，一条业务规则都不实现。

```
AnalysisTaskWorker.run
  └─ PARSING（在任何模型调用之前全部完成）
       TaskParseService.parseFiles(taskId)
         ├─ CtHealthReportFileService.findByTaskId  → 按 fileIndex 升序
         ├─ S3FileStorage.read(cloudFileKey)        → 原始字节
         └─ FileParseService.parse(...)             → ParsedFile
       ParseOrchestrator.prepare(fileList, accumulator) → ParsePlan
  └─ EXTRACTING
       ExtractionStageService.extract(parsePlan, accumulator)
         ├─ BatchPlanner.plan                 → 批次不跨文件，≤8 页/批、≤8 批
         ├─ ExtractionBatchExecutor.execute   → 并发 4，零重试
         └─ ExtractionValidationPipeline.validateAndMerge
                                              → Schema、来源、同一性、多批与多文件合并
  └─ ASSEMBLING
       AnalysisAssembleService.assemble(output, fileCount, companyId, bizDate, accumulator)
         ├─ IndicatorAssembler / ProblemAssembler
         ├─ DietAdviceInputFactory → DietAdviceAssembler
         └─ DishRecommendSetService + DishRecommendInputFactory → DishRecommendAssembler
       AnalysisResult.create(...)  ← 降级裁剪在这里统一执行，编排类不重复
```

**`FileParseService` 是 `PaddleOcrClient` 在 PDF 与图片路径上的唯一调用方**；
`ExtractionStageService` 是 `ExtractionModelClient` 的唯一调用方。
`AnalysisTaskWorker` 领取任务后从任务实体读取 `companyId`，并通过注入的 `Clock` 只取一次
`bizDate`，原样传给组装链路；下游不得再次取当前企业或当前日期。

##### 两条容易写错的接缝

```
① 交给校验层的 segment 必须与分批用的【同源】
   两边都读 parsePlan.getReadableFileList() 的页列表。
   若这里换成「解析出来的全部 segment」，被页数预算截断掉的也会进来，
   来源校验就会放过【实际上没有发给模型】的引用 —— 而它恰恰是防幻觉的那道闸
   对应用例 ExtractionStageServiceTest#segmentsGivenToValidationShouldComeFromThePlanOnly

② 模块四被抑制时【不读 Redis 菜品标签集合】
   读取结果也会被 AnalysisResult.create 丢掉；在线链路任何情况下都不得调用 DishQueryService。
   对应用例 AnalysisAssembleServiceTest#suppressedDishRecommendShouldNotReadDishTagSets
```

**降级裁剪只有一处**：`AnalysisResult.create` 按 `DegradeAccumulator` 调用
`withoutDietAdviceAndDishRecommend` / `withoutDishRecommend`。
两个编排类都产出完整四模块，**不各自判一遍**——判两遍就迟早会判得不一样。

**顺序不是随意的**：`fileIndex` 升序是因为 `segmentId` 的 `f{fileIndex}` 与批次编址都依赖它，
数据库返回顺序不作数；而解析、OCR、页数裁决全部发生在 `enterExtracting` 之前，
所以任何解析期失败都不会先花掉一次模型调用。

##### 四条路由

| 格式 | 走法 | 页面图 |
|---|---|---|
| PDF | 文本层阈值 + 密度闸都通过 → 原生 segment；任一不过 → **整文件走 OCR** | 逐页渲染，两个消费者共用同一次渲染 |
| JPG / PNG | **原始编码字节直传 OCR**，本地不解码（§5.6.3-④） | 降采样解码后压缩，只给 LLM-A |
| DOC / DOCX | 按源码顺序产块，内嵌图片走 OCR | **无**，Word 不发页面图 |
| OFD | 只走原生文本对象 | **无**（见下） |

**无论 PDF 走原生还是走 OCR，都要逐页渲染**——原生路径也需要页面图发给 LLM-A（§6.2.1），
所以「有文本层就不用渲染」是错的，省不掉这一步。

##### ⚠ OFD 的两个缺口

```
① 没有 OFD 页面渲染器
   → 扫描版 OFD 拿不到识别块，以【零 segment】落到 ParseOrchestrator 的 UNREADABLE
   → 这是显式失败，不是静默降级；补渲染器之前【不要给 OFD 加 OCR 分支】
     加了就得先本地整幅解码，那正是 §5.6.3 拒绝的
② 原生 OFD 也拿不到页面图（同一个原因）
   → OFD 页的 imageRequired = false、jpegBytes = null
   → LLM-A 只看得到文本，看不到版面
```

两条都已列入 §0.4。

##### ⛔ 按页分组不得静默丢段（2026-08-27 补）

`FileParseService.pagesFromSegments` 的页数**取自解析器，不取 `precheckPages`**：

```
按 precheckPages 建页时
  实际页数更多 → 超出那些页的 segment【直接消失】，无日志、无异常、无降级标记
              → 体检报告少了一页内容，没人知道
  而且 ParsedFile 构造器里 pageList.size() == precheckPages 会【恒为真】
              → 一个永远不会失败的断言，比没有断言更糟
```

改成按解析器页数建页之后，那句构造器断言对 PDF 和 OFD 两条路都真正生效。
本方法自己再加一条：**每个 segment 都必须落进某一页**，页码越界说明解析器的页数与
segment 编址自相矛盾，**炸而不是丢**。对应用例
`segmentBeyondParsedPageCountMustFailInsteadOfBeingDropped`。

##### 每页识别块上限在驱动里判

R43（OCR 某页识别块 > 400 → **整任务 `FAILED/UNREADABLE`**）由 `FileParseService.recognizePage`
在拿到分段结果后立即判，**不做局部截断**。判定放在驱动而不是客户端，
是因为「一页」是解析层的概念，客户端只认识「一张图」。

##### 失败码怎么收敛

```
PdfPageRenderer 渲染失败        → HealthReportException(UNREADABLE)
OcrImageEncoder 超限            → ImageTooLargeException(IMAGE_TOO_LARGE)
OCR 调用失败 / 超时 / 5xx       → OcrCallException（非 HealthReportException）
                                  → Worker 兜底映射为 SERVER_ERROR、reanalyzable=true
库里 contentType 对不上枚举      → UNSUPPORTED_FORMAT
```

**OCR 调用失败必须落到 `SERVER_ERROR` 而不是 `UNREADABLE`**：前者用户重试有意义，
后者告诉用户文件有问题——而文件没问题，是我们的下游挂了。
`OcrCallException` 刻意不继承 `HealthReportException`，靠 Worker 的运行时异常分支兜底，
就是为了不让人顺手给它塞一个确定性失败码。

##### ⛔ 第三方解析库对损坏输入抛的是 unchecked 异常（2026-08-27 补）

PDFBox 解析损坏 xref、POI 读坏 OLE2、ofdrw 读坏 ZIP，抛的**都不是 `IOException`**。
只 `catch (IOException)` 接不住它们，它们会一路逃到 Worker 的通用运行时分支：

```
用户文件损坏 → 逃逸 → SERVER_ERROR / 500 / reanalyzable=true
             → 前端告诉用户「服务端出错，可以重试」
             → 而重试必然再失败，因为坏的是他的文件
正确答案      → UNREADABLE / 400
```

`FileParseService.asUnreadable` 统一做这个映射，**但只包住第三方解析入口那一行**：

```
PDDocument.load / pdfTextLayerChecker+pdfSegmentParser / ofdSegmentParser.parse
/ imageContentInspector.decodeSubsampled / wordSegmentParser.parse
```

**包裹范围不能扩大到后续组装逻辑**——否则我们自己的 NPE 也会被记成「用户文件损坏」，
把 bug 伪装成用户问题。这是本映射的代价，用范围换。

**两类异常必须原样上抛：**

| 异常 | 为什么不能被吞 |
|---|---|
| `HealthReportException` | 自带确定性失败码（Word 超限的 `PAGE_LIMIT_EXCEEDED`、编码超限的 `IMAGE_TOO_LARGE`） |
| `OcrCallException` | **是我们下游挂了**，不是用户文件坏了。吞成 `UNREADABLE` 会误导用户去换文件，还把 `reanalyzable` 一起丢掉（R43b5） |

> `wordSegmentParser.parse` 内部会调 OCR，是上表第二行**唯一**会真实触发的路径。
> 对应用例 `FileParseServiceTest#ocrFailureInsideWordMustStayServerErrorNotUnreadable`
> ——必须走 Word 才测得到，图片路径的 `recognizePage` 在包裹范围之外，用它测等于没测。

**`PDDocument.close()` 要用 `closeQuietly`**：裸放在 `finally` 里时，关闭失败会
**覆盖正在抛出的真实异常**，把「文件损坏」变成一个毫不相关的关闭错误。

##### 内存

**文件字节用完即弃**：`TaskParseService` 每个文件解析完就不再持有它的原始字节，
不缓存、不落盘、不进日志——一份 20MB 的原文件乘上并发任务数就是堆（§4.2）。
渲染位图由 `RenderedPageImageProcessor` 在 `finally` 里释放，图片路径的解码位图由驱动自己释放。

##### 还差什么

```
三个占位符（§10.1）  S3FileStorage / CurrentUserProvider / DishQueryService
OFD 渲染器           见上
```

链路结构已完整——**生产代码里不再有零调用方的业务类**。
剩下的四个占位都是显式抛 `UnsupportedOperationException`，不返回假数据，
跑到那里会当场失败，不会悄悄给出错结果。


## 6. LLM-A 链路

### 6.1 分批与并发

```
一个批次的全部页必须来自【同一个文件】，批次绝不跨文件
每任务调用次数 = Σ ceil(各文件【截断后保留的】等效页数 / 8)，上限 8 批
单任务内批次并发度 4，跑在 llmBatchExecutor 上（不是任务池，§4.2）
任一批调用失败（超时 / 429 / 5xx / 连接中断）→ 整任务立即 FAILED / SERVER_ERROR
```

```
不做批次级重试        服务端出错直接返回错误，由用户决定要不要重新解析
任一批失败不取消其余批次   让它们跑完再丢弃；取消传播要引入 Future.cancel + 中断处理
                      + 半途响应清理，换来的只是几秒模型调用成本
内存：渲染完立即编码 JPEG 并释放 BufferedImage
      BufferedImage 2000×2800×3B ≈ 16MB/页，32 页 ≈ 512MB/任务 → OOM
      编码后 ≈ 300~800KB/页，32 页 ≈ 20MB/任务，差 25 倍
      【分析与 Web 层共用一个堆】，一次 OOM 连 Tomcat 一起带走（§4.2）
```

**`页/批`、`8 批上限`、`W`（§4.2，受模型配额与内存预算双重约束）是一组参数，不得单独调**：
降页数换 token 会撞 8 批上限（30 页最坏分布下 4 页/批需要 11 批）。

### 6.2 调用与批次裁决

```java
// llm.a.ExtractionModelClient  →  委托 infra.ExtractionModelClient（有完整实现，§6.2.1.1）—— 直连，不经过 Dify
ExtractionBatchOutput call(ExtractionBatchInput input);
```

**`ExtractionBatchInput` 字段**（LLM-A 直连，**不存在 DSL 变量映射问题**，见 §6.2.1）：

```java
/** LLM-A 单批输入。【按页组织】，文本与图像同属一个 BatchPage，对应关系是结构性的。 */
class ExtractionBatchInput {
    String  systemPrompt;      // prompt/extraction.md 正文，Java 读文件后传入（§6.2.2）
    String  promptVersion;     // PromptVersions.EXTRACTION，随请求下发供排障对齐（§9.4.1）
    int     fileIndex;         // 本批所属文件下标，模型原样回填
    int     batchIndex;        // 本批序号，模型原样回填
    int     batchCount;        // 本任务总批数，模型原样回填
    List<BatchPage> pageList;  // 【按真实页码升序】，见下
}

/** 批内一页。文本与图像放在同一个对象里，组装时不需要任何配对逻辑。 */
class BatchPage {
    int     page;              // 【报告上的真实页码】，不是本批第几页（§5.5）
    String  renderedText;      // 该页的页眉 + 该页全部块：=== 第 N 页 === \n [块号] (…) 正文
    byte[]  jpegBytes;         // 该页压缩后的渲染图（档位与实际尺寸见 §5.6）；
                               // 【绝不持有 BufferedImage】（§6.1）
                               // 【可为 null】—— 仅当 imageRequired = false
    boolean imageRequired;     // 【本页是否必须有渲染图】，由解析层按来源置位：
                               //   PDF / OFD / 图片 → true
                               //   Word 的全部逻辑页 → false（见下）
}
```

> **不要改回「一个整批 `renderedBlocks` + 一个 `pageImageList`」的形状**：那样实现者必须
> 重新解析页眉字符串才知道哪段文本属于哪页。现在页码、文本、图像绑在同一个对象上，
> 组装只是遍历，**不需要任何配对断言**。
>
> **`imageRequired` 是「缺图」能被拦住的前提。** 只靠 `jpegBytes == null` 分不清
> 「Word 纯文本页合法无图」和「PDF 页渲染图意外丢了」——前者放行、后者必须拦。
> 让客户端去猜文件格式就越界了；**由解析层置位、客户端只做
> `imageRequired && jpegBytes == null` 一个布尔判断**，仍是确定性逻辑。
>
> **Word 的全部逻辑页一律 `imageRequired = false`，`jpegBytes` 恒为 `null`。**
> 设计方案 §3.3.1 规定：内嵌图片提取出来**走 OCR，产出的识别块作为 segment 进正文**
> ——**图片本身不发给 LLM-A**。而且一个 Word 逻辑页可能含多张内嵌图，
> `BatchPage` 只有一个 `jpegBytes`，结构上也表达不了。**不要给 Word 造图片页。**

```
不传的东西，逐条都有理由：
    taskId / userId    模型不需要，且日志白名单禁止它与报告内容同现（§9.2）
    segmentId          模型侧只用块号，segmentId 是进程内主键（§5.5）
    rawText            模型只看 normalizedText（§5.2）
    bbox 单独字段      已内联在 renderedBlocks 的每一行里，不再重复传
```

**`DishTagInput` 字段：**

```java
class DishTagInput {
    String  systemPrompt;      // prompt/dish_tag.md 正文，同上，仅方案 a 时存在
    String  promptVersion;     // PromptVersions.DISH_TAG
    String  enumKey;           // 本批打标维度，模型必须原样回填（Schema 已约束）
    String  enumDisplayName;   // 该枚举的展示名，来自 constants
    // ── 内容常量注入，【只取 reviewStatus == REVIEWED 的条目】（§0.4）──
    // 四个列表【各自独立】，与提示词的四个占位符一一对应；不用的维度传空列表，不复用字段
    List<String> avoidFoodList;         // 两类维度都用：过敏=需避免的食材；饮食注意=需避免食材
    List<String> hiddenFoodList;        // 【仅过敏维度】易忽略的含该成分食物；饮食注意传空列表
    List<String> avoidDishPatternList;  // 【仅饮食注意】需避免的菜式；过敏维度传空列表
    List<String> cookingTipList;        // 【仅饮食注意】烹饪方式建议；过敏维度传空列表

    List<DishForTagging> dishList;      // ≤ 40 道，仅指LLM-B单次调用批量，与Redis固定33个集合无关
}

class DishForTagging {
    long   dishId;                     // 模型必须在三个输出列表之一里原样回填
    String dishName;
    List<IngredientItem> ingredientList;
}

class IngredientItem {
    String  name;        // 食材名，规范化前的原始值（模型侧看原文）
    Double  weightG;     // 克，四舍五入到 1 位小数；【未知给 null，不给 0】（§9.5.1）
}
```

```
食材列表【不含调味料】—— 食堂数据本就不记录（§7.5.1）。
这一点必须由提示词讲清「列表里没有 ≠ 这道菜没放」，而不是靠 Java 补数据。
```

#### 6.2.0 批次裁决与零 segment

**`batchStatus` 三态，不是两态：**

| 值 | 含义 |
|---|---|
| `OK` | 正常识别 |
| `NO_REPORT_FEATURE` | 读得清，但这几页看不出体检报告特征（封面、须知、广告页） |
| `UNREADABLE` | **读不清**，可能有内容但看不出来 |

**文件级与任务级裁决：**

```
某文件全部批次 NO_REPORT_FEATURE  → 该文件不是体检报告
全部文件都不是体检报告            → 整任务 FAILED / NOT_HEALTH_REPORT
全部批次都 UNREADABLE             → 整任务 FAILED / UNREADABLE
任一批 UNREADABLE（非全部）        → 该批内容丢弃，其余批照常
                                    partial=true, partial_reason=BATCH_UNREADABLE
```

**降级矩阵：**

| partial_reason | 模块一 | 模块二 | 模块三 | 模块四 |
|---|---|---|---|---|
| `PAGE_TRUNCATED` | ✅ | ✅ | ❌ | ❌ |
| `BATCH_UNREADABLE` | ✅ | ✅ | ❌ | ❌ |
| `ALLERGEN_SUSPECT_MISS` | ✅ | ✅ | ✅ | ❌ |

##### 零 segment 的裁决必须在分批【之前】做

```java
// parse.ParseOrchestrator —— 全部文件解析完成后立刻判
// 放这里而不是 llm.a.BatchPlanner：它判的是解析结果，不是分批逻辑；
// 对应的 R43b10 / R43b11 也在批次 4（解析）验收，类归属与批次必须一致

if (allSegmentList.isEmpty()) {
    // 整任务 FAILED / UNREADABLE，【LLM-A 调用次数为 0】
    // 不是 NOT_HEALTH_REPORT —— 我们不知道它是不是体检报告，只知道读不出字
}
for (FileParseResult r : fileResultList) {
    if (r.getSegmentList().isEmpty()) {
        // 【部分文件零 segment】：不静默忽略
        degradeAccumulator.setBatchUnreadable(true);   // → partial=true，模块三四不输出
        // 该文件不贡献任何 segment，其余文件照常进入分批
    }
}
```

**为什么不能等批次裁决**：上面的裁决全部建立在「有批次」之上，零 segment 时批次数是 0，
「全部批次 `UNREADABLE`」在空集上不成立，任务会带着四个空模块走到 `SUCCEEDED`。

**部分文件零 segment 必须降级**，理由同批次不可读：读不出的那份**恰好可能含过敏原和医嘱**。
**复用 `BATCH_UNREADABLE`，不新增 `partial_reason` 枚举**——三者后果相同（关模块三四）。

**触发场景不是边角**：正文为空但有内嵌图片的 Word（§5.1 允许它通过可读性校验）、
纯图片上传、扫描版 PDF/OFD——只要 OCR 调用**成功**却一个文字块都没识别出来。
OCR **调用失败**是另一回事，走 `SERVER_ERROR`。

结果接口下发 `partial` / `partialReason` / `suppressDietAdvice` / `suppressDishRecommend`
/ `processedPages` / `totalPages`，**四个开关字段全部取自 `DegradeAccumulator`**（§6.2.3）。

#### 6.2.1 为什么三条链路全部直连，不走 Dify

**分界不是「A 特殊」，是按数据敏感度分：**

| | 请求里有什么 | 接入方式 |
|---|---|---|
| **LLM-A** | 报告页面图、OCR 全文、姓名、健康结论 | **直连模型 API** |
| **LLM-B** | 菜名、食材名、重量、枚举展示名 | **直连**（§13.2），2026-08-27 由 Dify 改为直连 |

**LLM-B 的请求里一条健康数据都没有**，是食堂公开数据。它曾经走 Dify，
2026-08-27 改直连——理由不是隐私，是提示词与全部校验都已在 Java 侧，
Dify 工作流退化成纯转发（§13.2.0）。

**LLM-A 走 Dify 是个死结**：Dify 的文件上传**没有删除接口**（已确认），
每一页报告渲染图上传后**永久留在它的存储里**，与 §4.5「原文件在任务成功后立即删除」
无法调和。直连时图像内联在请求体里，**不上传 Dify、不写本地临时文件**。

> **措辞收窄：不是「不在任何中间系统落地」。** 模型网关、反向代理、APM、
> 以及模型服务端本身都可能留存请求体，heap dump 也会把它写进磁盘
> ——这六项由 §0.4.1 逐条核查。直连消掉的是「Dify 存储且删不掉」这一处，不是整条链路。

**因此全案不存在这些**：Dify 文件上传与删除、`user` 标识、DSL 交付物、R55/R56、
提示词双真源取舍、Dify 侧隐藏重试。**但 R55a / R55b 对两个模型都适用**
——版本号与正文摘要与走不走 Dify 无关，排障时要能分辨结果出自哪版提示词。

##### 图片内联：页码与图片是结构性对应，不靠顺序约定

直连时按「文本 → 该页的图 → 下一页文本 → 下一页的图」交替组装消息，
**每页的图紧跟在它自己的页眉之后**：

```
[text]  === 第 2 页 ===
        [15] (NATIVE, bbox=…) 血脂检查
        [16] (NATIVE, bbox=…) 甘油三酯
        …
[image] <第 2 页的 JPEG，base64 内联>
[text]  === 第 3 页 ===
        …
[image] <第 3 页的 JPEG>
```

> 文本与图像同属一个 `BatchPage`，从构造那刻起就绑在一起，**不存在「配错页」这种错误**。
> **仍要断言的只剩列表本身是否合法：**
>
> ```java
> // llm.a.BatchMessageAssembler 组装前校验，任一不满足直接抛，【不发出请求】
> if (pageList.isEmpty()) { throw new IllegalStateException("批次无页面"); }
> int previous = Integer.MIN_VALUE;
> for (BatchPage p : pageList) {
>     if (p.getPage() <= previous) {            // 严格递增 ⇒ 同时排除乱序与重复
>         throw new IllegalStateException("页码未严格递增：" + p.getPage());
>     }
>     previous = p.getPage();
> }
> // 【没有「文本页数 == 图片数」这条】—— jpegBytes 允许为 null（Word 纯文本逻辑页）
> ```
>
> 对应用例 R60、R61。

#### 6.2.1.1 `ExtractionModelClient` 实现（RestTemplate）

> **协议：OpenAI 兼容的 `/chat/completions`**，多模态用 `content` 数组。
> **换服务商时**：请求体与响应解析在 `buildRequestBody` / `extractContent` 两个方法里；
> **但鉴权头、endpoint 路径、配置项也可能一起变**（Anthropic 用 `x-api-key` + `anthropic-version`，
> 不是 `Authorization: Bearer`）。**不要以为只动两个方法就够**——
> 不变的是超时、零重试、脱敏、容量上限这四条策略。
>
> **术语**：本实现是**增量序列化到有界内存缓冲**，**不是 HTTP 流式发送**。
> 请求体带 `Content-Length` 一次性发出（§6.2.5）。

```java
package com.example.healthreport.infra;

/**
 * LLM-A 直连模型客户端。
 * <p>只做一件事：把一批页面（文本 + 图像）发给模型，取回原始 JSON 字符串。
 * <b>不解析业务语义、不校验 Schema、不重试</b>——校验在 {@code ExtractionValidationPipeline}（§6.3）。</p>
 *
 * <p><b>安全边界</b>：请求体含报告图像与 OCR 文本，响应体含健康结论。
 * 本类<b>绝不把请求体或响应体写进日志</b>，向上只抛不含正文的 {@link LlmCallException}。</p>
 */
@Slf4j
@Component
public class OpenAiCompatibleExtractionModelClient implements ExtractionModelClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ExtractionProperties properties;

    public OpenAiCompatibleExtractionModelClient(ObjectMapper objectMapper, ExtractionProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.restTemplate = buildRestTemplate(properties);
    }

    /**
     * 构造专用 RestTemplate；不复用全局 Bean，避免别处挂上打印 body 的拦截器。
     * <ul>
     *   <li>请求体保持缓冲（默认值，显式写出防误改）：带 {@code Content-Length}，不依赖 chunked</li>
     *   <li>不注册任何 {@code ClientHttpRequestInterceptor}：拦截器会读 body，常被用来打日志（§9.2）</li>
     *   <li><b>替换错误处理器</b>：默认的 {@code DefaultResponseErrorHandler} 会把 4xx/5xx 的
     *       <b>完整 body 读进内存</b>再抛异常，绕过响应上限；换成只看状态码的实现</li>
     * </ul>
     */
    private RestTemplate buildRestTemplate(ExtractionProperties p) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(p.getConnectTimeoutMillis());
        factory.setReadTimeout(p.getReadTimeoutMillis());
        factory.setBufferRequestBody(true);
        RestTemplate template = new RestTemplate(factory);
        template.setInterceptors(new ArrayList<ClientHttpRequestInterceptor>(0));
        template.setErrorHandler(new StatusOnlyErrorHandler());
        return template;
    }

    @Override
    public String call(ExtractionBatchInput input) {
        assertPageListValid(input);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.getApiKey());

        long startMillis = System.currentTimeMillis();
        byte[] bodyBytes;
        try {
            bodyBytes = buildRequestBody(input);
        } catch (RequestTooLargeException e) {
            // 超限说明分批策略或密度闸没兜住（§5.3、§6.1）。异常里只有数字，可安全记录
            log.error("LLM-A 请求体超限，fileIndex={}，batchIndex={}",
                    input.getFileIndex(), input.getBatchIndex(), e);
            throw new LlmCallException(FailCode.SERVER_ERROR, 0, 0L);
        } catch (IOException e) {
            // 【请求】序列化失败：异常来自我们自己的数据，不含模型响应，可安全记录
            log.error("LLM-A 请求体序列化失败，fileIndex={}，batchIndex={}",
                    input.getFileIndex(), input.getBatchIndex(), e);
            throw new LlmCallException(FailCode.SERVER_ERROR, 0, 0L);
        }

        String rawResponse;
        try {
            rawResponse = restTemplate.execute(
                    properties.getBaseUrl() + properties.getChatCompletionsPath(),
                    HttpMethod.POST,
                    bodyWriter(bodyBytes, headers),
                    new BoundedResponseExtractor(properties.getMaxResponseBodyBytes()));

            log.info("LLM-A 调用完成，fileIndex={}，batchIndex={}，请求体={}字节，耗时={}ms",
                    input.getFileIndex(), input.getBatchIndex(),
                    bodyBytes.length, System.currentTimeMillis() - startMillis);

        } catch (LlmCallException e) {
            throw e;                                   // StatusOnlyErrorHandler 已经脱敏过
        } catch (ResponseTooLargeException e) {
            log.error("LLM-A 响应体超限，fileIndex={}，batchIndex={}",
                    input.getFileIndex(), input.getBatchIndex(), e);
            throw new LlmCallException(FailCode.SERVER_ERROR, 200,
                    System.currentTimeMillis() - startMillis);
        } catch (ResourceAccessException e) {
            // 连接/读超时、连接中断。异常消息含 URL 不含正文，可安全记录
            log.warn("LLM-A 调用网络异常，fileIndex={}，batchIndex={}，耗时={}ms",
                    input.getFileIndex(), input.getBatchIndex(),
                    System.currentTimeMillis() - startMillis, e);
            throw new LlmCallException(FailCode.SERVER_ERROR, 0,
                    System.currentTimeMillis() - startMillis);
        }
        // 【没有 catch 之后的重试】—— 全案零重试（§6.1）。这里加一行 retry 就破了整条口径

        return extractContent(rawResponse, input);
    }

    /** 写请求头与请求体。显式设 {@code Content-Length}，与 §6.2.5 的非流式口径一致。 */
    private RequestCallback bodyWriter(final byte[] body, final HttpHeaders headers) {
        return new RequestCallback() {
            @Override
            public void doWithRequest(ClientHttpRequest request) throws IOException {
                request.getHeaders().putAll(headers);
                request.getHeaders().setContentLength(body.length);
                StreamUtils.copy(body, request.getBody());
            }
        };
    }

    /**
     * 发送前校验页列表。全部是确定性判断。
     * <p>{@code imageRequired} 由解析层按来源置位（§6.2.1），客户端只做
     * {@code required && jpegBytes == null} 这一个布尔判断，不需要知道文件格式。</p>
     */
    private void assertPageListValid(ExtractionBatchInput input) {
        List<BatchPage> pageList = input.getPageList();
        if (pageList == null || pageList.isEmpty()) {
            throw new IllegalStateException("批次无页面");
        }
        int previous = Integer.MIN_VALUE;
        for (BatchPage page : pageList) {
            if (page.getPage() <= previous) {          // 严格递增 ⇒ 同时排除乱序与重复
                throw new IllegalStateException("页码未严格递增：" + page.getPage());
            }
            if (page.isImageRequired() && page.getJpegBytes() == null) {
                throw new IllegalStateException("应有渲染图但缺失，page=" + page.getPage());
            }
            previous = page.getPage();
        }
    }

    /**
     * 组装请求体。<b>协议相关，换服务商时改本方法。</b>
     * <p>缓冲区按 {@link #estimateBodyBytes} 分配；估算不足时<b>至多扩容到 {@code maxBytes} 封顶</b>；
     * 写入超过上限立即抛，不会先生成完整个巨大请求体（§6.2.5）。</p>
     * <p>消息结构：system 一条 + user 一条。user 的 content 数组
     * <b>第一项是批次信息</b>，其后按页交替「该页文本 → 该页图像」。</p>
     */
    private byte[] buildRequestBody(ExtractionBatchInput input) throws IOException {
        // 【必须在任何 base64 之前判】—— 一张 1MB 的图 base64 后是 1.4MB String（2.8MB 堆），
        // 等写进流里再触发上限，几十 MB 临时对象已经分配掉了
        long estimated = estimateBodyBytes(input);          // 【未截断】，否则下面这个 if 永远不成立
        if (estimated > properties.getMaxRequestBodyBytes()) {
            throw new RequestTooLargeException(estimated, properties.getMaxRequestBodyBytes());
        }
        // 比较通过后才夹到上限，用作初始容量
        int capacity = (int) Math.min(estimated, properties.getMaxRequestBodyBytes());
        CappedByteArrayOutputStream out = new CappedByteArrayOutputStream(
                capacity, properties.getMaxRequestBodyBytes());
        JsonGenerator gen = objectMapper.getFactory().createGenerator(out);
        gen.writeStartObject();
        gen.writeStringField("model", properties.getModel());
        gen.writeNumberField("temperature", 0);              // 抽取任务，不要随机性
        gen.writeBooleanField("stream", false);              // 客户端读取单个 JSON 信封，不接受 SSE
        gen.writeArrayFieldStart("messages");

        gen.writeStartObject();
        gen.writeStringField("role", "system");
        gen.writeStringField("content", input.getSystemPrompt());
        gen.writeEndObject();

        gen.writeStartObject();
        gen.writeStringField("role", "user");
        gen.writeArrayFieldStart("content");

        // 【批次信息必须真的发出去】—— 模型要原样回填这三个值（提示词铁律 8）。
        // 不发就等于让它照抄提示词里的占位符，Java 侧的串号校验会全批作废
        gen.writeStartObject();
        gen.writeStringField("type", "text");
        gen.writeStringField("text",
                "【批次信息】fileIndex=" + input.getFileIndex()
                        + "  batchIndex=" + input.getBatchIndex()
                        + "  batchCount=" + input.getBatchCount()
                        + "  promptVersion=" + input.getPromptVersion()
                        + "\n（本批全部页面来自同一个文件；前三个值请原样回填进输出）");
        gen.writeEndObject();

        for (BatchPage page : input.getPageList()) {
            gen.writeStartObject();
            gen.writeStringField("type", "text");
            gen.writeStringField("text", page.getRenderedText());
            gen.writeEndObject();
            if (page.getJpegBytes() != null) {
                gen.writeStartObject();
                gen.writeStringField("type", "image_url");
                gen.writeObjectFieldStart("image_url");
                // 每张图的 base64 String 是【瞬时的】：一次一张、约 2MB，写完即可回收
                gen.writeStringField("url", "data:image/jpeg;base64,"
                        + Base64.getEncoder().encodeToString(page.getJpegBytes()));
                gen.writeEndObject();
                gen.writeEndObject();
            }
        }
        gen.writeEndArray();
        gen.writeEndObject();

        gen.writeEndArray();
        // 结构化输出：服务端支持就绑，不支持删掉这三行也能跑（Java 侧还会校验，§6.3-①）
        gen.writeObjectFieldStart("response_format");
        gen.writeStringField("type", "json_object");
        gen.writeEndObject();
        gen.writeEndObject();
        gen.flush();
        gen.close();
        return out.toByteArray();
    }

    /**
     * 估算请求体字节数，用于一次性分配缓冲区、避免扩容翻倍。
     * <p>宁可估大：估小会触发扩容（翻倍后可能超过上限），估大只是多占一点。</p>
     */
    private long estimateBodyBytes(ExtractionBatchInput input) {
        long total = 4096;                                     // JSON 骨架与批次信息
        total += input.getSystemPrompt().length() * 3L;        // UTF-8 最坏 3 字节/字符
        for (BatchPage page : input.getPageList()) {
            total += page.getRenderedText().length() * 3L;
            if (page.getJpegBytes() != null) {
                total += (page.getJpegBytes().length + 2) / 3 * 4 + 64;   // base64 + 前缀
            }
        }
        // 【JSON 转义的理论上界是 6 倍】（控制字符写成 \uXXXX），按 6 倍估会让缓冲区大得离谱。
        // 折中按 1.2 倍；估小了也不会失控——CappedByteArrayOutputStream 至多扩到 maxBytes 封顶
        total = total * 120 / 100;
        return total;      // 【不在这里夹上限】——夹了的话调用方的超限判断就永远为假
    }

    /**
     * 从 content / reasoning_content 中选择唯一合法 JSON 对象。<b>协议相关，换服务商时改本方法。</b>
     * <p>当前网关把 LLM-A 结构化结果放进 reasoning_content，标准 OpenAI 兼容实现通常放在
     * content。不能按字段名盲选：只有一个通道是合法 JSON 对象时采用它；两边都合法但内容不同
     * 时整批拒绝，避免把思考内容或过期结果静默送入医疗数据链路。</p>
     * <p><b>解析异常必须在这里就地脱敏</b>：Jackson 的异常消息会带上出错位置附近的原文片段，
     * 而那是模型响应——含健康结论。直接把它抛出去或记进日志就等于泄露（§9.2）。</p>
     */
    private String extractContent(String responseBody, ExtractionBatchInput input) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode message = root.path("choices").path(0).path("message");
            JsonNode content = message.path("content");
            JsonNode reasoningContent = message.path("reasoning_content");
            JsonNode contentObject = parseJsonObjectOrNull(content);
            JsonNode reasoningContentObject = parseJsonObjectOrNull(reasoningContent);
            if (contentObject != null && reasoningContentObject != null) {
                if (!contentObject.equals(reasoningContentObject)) {
                    log.error("LLM-A 响应结构不符合预期，fileIndex={}，batchIndex={}，响应长度={}",
                            input.getFileIndex(), input.getBatchIndex(), responseBody.length());
                    throw new LlmCallException(FailCode.SERVER_ERROR, 200, 0L);
                }
                return content.asText();
            }
            if (contentObject != null) {
                return content.asText();
            }
            if (reasoningContentObject != null) {
                return reasoningContent.asText();
            }
            log.error("LLM-A 响应结构不符合预期，fileIndex={}，batchIndex={}，响应长度={}",
                    input.getFileIndex(), input.getBatchIndex(), responseBody.length());
            throw new LlmCallException(FailCode.SERVER_ERROR, 200, 0L);
        } catch (IOException e) {
            // 【绝不把 e 传给 log】—— Jackson 异常消息含响应片段
            log.error("LLM-A 响应 JSON 解析失败，fileIndex={}，batchIndex={}，响应长度={}，异常类型={}",
                    input.getFileIndex(), input.getBatchIndex(),
                    responseBody.length(), e.getClass().getName());
            throw new LlmCallException(FailCode.SERVER_ERROR, 200, 0L);
        }
    }

    private JsonNode parseJsonObjectOrNull(JsonNode candidate) {
        if (!candidate.isTextual() || candidate.asText().trim().isEmpty()) {
            return null;
        }
        try {
            JsonNode parsed = objectMapper.readTree(candidate.asText());
            return parsed != null && parsed.isObject() ? parsed : null;
        } catch (IOException | RuntimeException e) {
            return null;    // 这里只用于选择另一通道；不得记录可能含健康数据的异常
        }
    }
}
```

**配套的五个类：**

```java
/**
 * 只看状态码的错误处理器。
 * <p><b>为什么必须替换默认实现</b>：{@code DefaultResponseErrorHandler} 在抛
 * {@code RestClientResponseException} 之前会把 4xx/5xx 的<b>完整 body 读进内存</b>并塞进异常，
 * 于是超大错误响应绕过了 {@link BoundedResponseExtractor} 的上限，异常对象本身也带上了正文。</p>
 * <p>本实现<b>不调用 {@code response.getBody()}</b>，只取状态码。</p>
 */
public class StatusOnlyErrorHandler implements ResponseErrorHandler {
    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        int series = response.getRawStatusCode() / 100;
        return series == 4 || series == 5;
    }
    @Override
    public void handleError(ClientHttpResponse response) throws IOException {
        int status = response.getRawStatusCode();      // 【只读状态码，不碰 body】
        throw new LlmCallException(FailCode.SERVER_ERROR, status, 0L);
    }
}

/**
 * 容量封顶的字节输出流。<b>不继承 {@code ByteArrayOutputStream}</b>——它的扩容按翻倍走且
 * 私有不可控：12MB 写到 13MB 会扩成 24MB、25MB 会扩成 48MB，即使上限只有 32MB。
 * <p>本实现自管数组：扩容目标 {@code min(max(需要, 当前×2), maxBytes)}，
 * <b>底层数组永不超过 {@code maxBytes}</b>；写不下时抛，不静默扩。</p>
 */
public class CappedByteArrayOutputStream extends OutputStream {
    private byte[] buf;
    private int count;
    private final int maxBytes;

    public CappedByteArrayOutputStream(int initialCapacity, int maxBytes) {
        this.maxBytes = maxBytes;
        this.buf = new byte[Math.max(1, Math.min(initialCapacity, maxBytes))];
    }
    @Override
    public void write(int b) {
        ensureCapacity((long) count + 1);
        buf[count++] = (byte) b;
    }
    @Override
    public void write(byte[] b, int off, int len) {
        ensureCapacity((long) count + len);
        System.arraycopy(b, off, buf, count, len);
        count += len;
    }
    private void ensureCapacity(long required) {
        if (required > maxBytes) {
            throw new RequestTooLargeException(required, maxBytes);
        }
        if (required <= buf.length) {
            return;
        }
        // 【封顶扩容】翻倍但不超 maxBytes，所以底层数组最大就是 maxBytes
        int newCapacity = (int) Math.min(Math.max(required, (long) buf.length * 2), maxBytes);
        buf = Arrays.copyOf(buf, newCapacity);
    }
    public int size() { return count; }
    public byte[] toByteArray() { return Arrays.copyOf(buf, count); }
}

/** 响应有界读取：超过上限即中断，避免服务端异常时返回巨大 body 打爆内存（§6.2.4-6）。 */
public class BoundedResponseExtractor implements ResponseExtractor<String> {
    private final int maxBytes;
    public BoundedResponseExtractor(int maxBytes) { this.maxBytes = maxBytes; }
    @Override
    public String extractData(ClientHttpResponse response) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        byte[] chunk = new byte[8192];
        InputStream in = response.getBody();
        int read;
        while ((read = in.read(chunk)) != -1) {
            if ((long) out.size() + read > maxBytes) {
                throw new ResponseTooLargeException(maxBytes);
            }
            out.write(chunk, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}

/** 请求体超限。<b>消息里只有字节数</b>，无任何正文，可安全记录。 */
@Getter
public class RequestTooLargeException extends RuntimeException {
    private final long attemptedBytes;
    private final int maxBytes;
    public RequestTooLargeException(long attemptedBytes, int maxBytes) {
        super("LLM-A 请求体超限：" + attemptedBytes + " > " + maxBytes);
        this.attemptedBytes = attemptedBytes;
        this.maxBytes = maxBytes;
    }
}

/** 响应体超限。<b>消息里只有上限值</b>，无任何正文，可安全记录。 */
@Getter
public class ResponseTooLargeException extends RuntimeException {
    private final int maxBytes;
    public ResponseTooLargeException(int maxBytes) {
        super("LLM-A 响应体超过上限 " + maxBytes + " 字节");
        this.maxBytes = maxBytes;
    }
}
```

```
两个容量异常都继承 RuntimeException，且【都在 call() 里被捕获并转成 LlmCallException】，
不会作为未处理异常逃逸；对上层而言 LLM-A 的失败只有一种类型（§6.1 的整任务 FAILED）。
```

**`ExtractionProperties` 与 `LlmCallException`：**

```java
/**
 * 直连接入参数。<b>必须显式注册</b>——只写 {@code @ConfigurationProperties} 不会成为 Bean，
 * 构造器注入会失败。本类加 {@code @Component}，
 * 或在配置类上加 {@code @EnableConfigurationProperties(ExtractionProperties.class)}，二选一。
 */
@Data
@Component
@ConfigurationProperties(prefix = "llm.a")
public class ExtractionProperties {
    private String baseUrl;                      // ⛔ 无默认值，启动自检失败
    private String chatCompletionsPath = "/v1/chat/completions";
    private String model;                        // ⛔ 无默认值；同时是 §9.4.1 的 llm.model-version-extraction
    private String apiKey;                       // ⛔ 无默认值；环境变量注入，绝不进代码库
    private int connectTimeoutMillis = 10_000;
    private int readTimeoutMillis = 180_000;     // 必须 < §4.3 的 deadline_at(10min)，留出组装余量
    private int maxRequestBodyBytes = 32 << 20;  // 【保守兜底值】真实上限见 §6.2.4-5，确认后改小
    private int maxResponseBodyBytes = 4 << 20;
}

/** LLM-A 调用异常。<b>刻意不持有任何请求体或响应体</b>，因此可以安全地进日志。 */
@Getter
public class LlmCallException extends RuntimeException {
    private final FailCode failCode;
    private final int httpStatus;      // 网络异常时为 0
    private final long elapsedMillis;
    public LlmCallException(FailCode failCode, int httpStatus, long elapsedMillis) {
        super("LLM-A 调用失败，httpStatus=" + httpStatus);   // 消息里只有状态码
        this.failCode = failCode;
        this.httpStatus = httpStatus;
        this.elapsedMillis = elapsedMillis;
    }
}
```

**启动自检**（`ApplicationRunner`，与 §6.2.2 的提示词自检一起做）：

```
baseUrl / model / apiKey 任一为空 → 直接启动失败，不要等到第一次调用
```

**`application.yml` 里只有这一段，不写任何中间件配置（`AGENTS.md` §5）：**

```yaml
llm:
  a:
    base-url: ${EXTRACTION_BASE_URL}
    model: ${EXTRACTION_MODEL}
    api-key: ${EXTRACTION_API_KEY}        # 环境变量注入，不落配置文件
```


#### 6.2.4 直连接入参数：12 项

**协议已选定为 OpenAI 兼容，代码据此写完（§6.2.1.1），可用 WireMock 完成本地 HTTP 测试。**

> **第 1、7 项已由接入截图确认，不再是待确认项**：模型走的是与 OCR 同一个网关
> （`higress-http.…/public/v1/chat/completions`），OpenAI 兼容、`Authorization: Bearer`、
> 多模态用 `content` 数组、`image_url.url` 承载图片。**因此 `buildRequestBody` /
> `extractContent` 不需要改**，鉴权头与 endpoint 路径也与代码里写的一致。
> 第 2、3 项仍是 ⛔：截图给的是测试环境地址与 `${API_KEY}` 占位，生产值另取。
>
> **图片内联格式有一处必须与平台示例背离**：示例用 `http://…/nan.png` 外链，
> 我们用 `data:image/jpeg;base64,…` 内联，理由见 §5.6.7——外链等于把报告图发布出去。
> **网关是否接受 data URI 需要联调确认**，已列入 §0.4。

```
⛔ 三项（base-url / model / apiKey）  无默认值，启动自检失败 → 只阻塞【端到端联调与上线】
其余各项                              有保守默认值，代码能跑 → 确认后按实际值改，改完才能上线
第 1 项若结论不是 OpenAI 兼容          主要改 buildRequestBody / extractContent，
                                      鉴权头与 endpoint 也可能一起变
```

| # | 待确认 | 影响什么 |
|---|---|---|
| 1 | ~~**模型服务商与 API 协议**~~ **已确认：OpenAI 兼容自研网关**（§5.6.7 同一个网关） | 代码按 OpenAI 兼容写，与实际一致，无需改动。保留本行是为了记住**换服务商时要一起看鉴权头与 endpoint**，不是只改两个方法 |
| 2 | ⛔ **base-url、endpoint 路径、模型标识** | 无此不能发请求；模型标识同时是 `llm.model-version-extraction` 的取值（§9.4.1） |
| 3 | ⛔ **API Key 从哪读**（环境变量 / 配置中心 / KMS） | 代码按 `${EXTRACTION_API_KEY}` 环境变量注入。**绝不能进代码库与日志** |
| 4 | **connect / read timeout** | read timeout 必须 < §4.3 的 `deadline_at`（10 分钟），否则任务先被判超时而请求还挂着 |
| 5 | **最大请求体上限** | 直接决定 8 页/批是否可行（§6.2.5）。代码给了 32MB 保守兜底值，**写入过程中就会超限即抛**（`CappedByteArrayOutputStream`），不会先生成完再判；拿到真实上限后必须改小 |
| 5a | 是否接受 `Transfer-Encoding: chunked` | **已不阻塞**：本方案决定请求体**保持缓冲**、带 `Content-Length` 发送（§6.2.5），不依赖 chunked。本项保留仅供将来若要改流式时参考 |
| 6 | **最大响应体上限** | 一份 30 页报告的抽取结果约上百 KB；代码给了 4MB 兜底并**有界读取**（`BoundedResponseExtractor`），防止服务端异常时返回巨大 body |
| 7 | **图片内联格式** | 已确认字段是 `content[].image_url.url`，代码按 `data:image/jpeg;base64,…` 写。**平台示例用的是 http 外链**——我们不采用（§5.6.7），但由此产生一个新的联调确认项：网关是否接受 data URI |
| 8 | **单次请求是否支持 8 张图 + 文本图片交替** | 不支持就要降到更小的批，而这会连锁改 §6.1 的 8 批上限与 `W`（§4.2） |
| 9 | **是否支持 `response_format`** | 代码写了 `json_object`。不支持就删那三行，仍能跑（Java 侧还会校验，§6.3-①），但 Schema 校验失败会明显变多 |
| 10 | **是否强制 HTTPS / TLS 版本与证书校验要求** | 报告图像出网，这条不能靠默认值 |
| 11 | **429 / 5xx / 超时的响应体形状** | 决定怎么在**不泄露正文**的前提下映射成内部错误码（见下） |

**第 11 项有一条硬要求，写进实现要点：**

```java
// 原始 HTTP 异常【可能持有模型响应正文】，而正文里有报告内容
// AGENTS.md §6 要求「异常对象作为最后一个参数」，但【不能直接记录 RestClientResponseException】
catch (RestClientResponseException e) {
    // 只取状态码与耗时，【丢弃 getResponseBodyAsString()】
    throw new LlmCallException(mapErrorCode(e.getRawStatusCode()), e.getRawStatusCode());
    // LlmCallException 不持有任何响应正文；日志记它是安全的
}
```

#### 6.2.5 直连之后的内存预算必须重算

**原估算「编码后 20MB/任务」只算了 JPEG 字节，直连后它严重低估。** 逐项加上：

```
一张 800KB 的 JPEG
  → base64 编码            ≈ 1.07 MB（膨胀 4/3）
  → Java 8 String 持有     ≈ 2.13 MB（char[] 每字符 2 字节）  ← 最大的一项
8 张 / 批                  ≈ 17 MB  仅 base64 字符串
  + 组装后的 JSON 请求体（又一份 String）        ≈ +17 MB
  + 序列化成 UTF-8 字节                          ≈ +9 MB
  + RestTemplate 默认【缓冲整个请求体】           ≈ +9 MB
单批峰值                                        ≈ 50 MB
× 单任务 4 批并发                                ≈ 200 MB / 任务
× W 个任务并发                                   ← 与 Web 层【共用同一个堆】（§4.2）
```

**两条硬要求（原第 ② 条「关闭缓冲」已按决策去掉，见下）：**

```
① 【禁止把整个请求体拼成 String】
   用 Jackson 的 JsonGenerator【增量序列化到有界字节缓冲】
   —— 注意这【不是 HTTP 流式发送】：请求体仍带 Content-Length 一次性发出
   —— 砍掉上面 50MB 里的 26MB（整请求体 String + 它的 UTF-8 字节）
   【逐张图的 base64 String 仍会产生，但它是瞬时的、峰值只有单张约 2MB】
② JPEG 字节用完即释放，不在 ExtractionBatchInput 里长期持有
```

##### 请求体**保持缓冲**，不走流式（2026-08-25 决策）

```
RestTemplate 保持 bufferRequestBody = true（默认值，代码里显式写出防误改）
⇒ 带 Content-Length 发送，不依赖服务端与中间网关对 chunked 的支持
⇒ 代价：内存里同时存在两份请求体
```

**峰值逐项重算（上一版漏了三项，这里全部列出）：**

| 项 | 大小 | 何时释放 |
|---|---|---|
| 8 张原始 JPEG `byte[]`（`BatchPage` 持有） | 6.4 MB | 请求发完 |
| `CappedByteArrayOutputStream` 内部缓冲（按估算分配；估小则**至多扩到 `maxBytes` 封顶**） | ≈ 9.5 MB，最坏 32 MB | 请求发完 |
| `toByteArray()` 的拷贝（**一定发生**） | ≈ 9 MB | 交给 RestTemplate 后 |
| 逐张 base64 的瞬时 `String`（一次一张） | 2.1 MB | 下一张覆盖 |
| RestTemplate 缓冲的那一份 | 9 MB | 请求发完 |
| **单批峰值** | **约 39 MB** | |
| **× 单任务 4 批并发** | **约 156 MB / 任务** | |

```
【修正三处早先的错误结论】
① 「toByteArrayWithoutCopy 常见情形不产生拷贝」是错的 —— 已删除该方法
② 「继承 ByteArrayOutputStream 就能卡住容量」是错的
   它的扩容翻倍且私有不可控 —— 改成自管数组的 CappedByteArrayOutputStream，扩容封顶
③ 「39MB 是峰值上界」是错的
   估算按 JSON 转义 1.2 倍算，理论上界是 6 倍；估小时缓冲会扩到 maxBytes(32MB)，
   单批最坏约 62MB。【下表是典型值不是上界，W 必须按实测定，不能按这张表定】
```

对比「拼 String」的原始形态（50MB/批、200MB/任务）仍然好，
但**远不是早先写的 80MB/任务**。**上面每一项都是估算，`W` 定稿前必须实测**（§11.4）。

##### `W` 必须同时受两个上界约束

```
W = min( floor(C / (4 × 实例数)),                    ← 模型配额（§4.2）
         floor((堆预算 - Web 层占用) / 单任务峰值) )   ← 内存预算，本节
```

**上一版只写了模型配额那一条**，等于默认内存永远够——按 150MB/任务算，
一个 2GB 堆在扣掉 Web 层之后只放得下个位数的并发任务。
**两个上界哪个小取哪个**，实测数据进 §11.4。

**换来的：**

```
不依赖网关对 chunked 的支持        —— §6.2.4-5a 从"必须确认否则跑不了"降级为"了解即可"
发送前就知道确切大小              —— 可以先判再发（代码里已加），而不是等服务端回 413
排障简单                          —— 抓包能看到完整请求，流式下只能看到分块
```

**`W` 与「4 批并发」必须按 §6.2.5 实测的单任务峰值重算**（当前估算约 156MB/任务），
不能沿用最初的 20MB/任务，也不要沿用中途写过的 80MB/任务。
实测进 §11.4，与 §11-20 的 token 实测一起做。

#### 6.2.2 提示词文件怎么进 JAR

`prompt/extraction.md` 是 LLM-A 提示词的**唯一真源**（直连之后不再有 DSL 那一份），
Java 调用时读它并作为 system 消息传入。

**但 `prompt/` 在仓库根目录，不在 `src/main/resources` 下，默认不会进 JAR**，
生产环境运行时读不到文件。两条路二选一并记在这里：

```
a1  Maven 构建时把 prompt/*.md 复制进 target/classes
    （maven-resources-plugin 的 copy-resources，绑定 process-resources 阶段）
    运行时走 ClassPathResource
a2  把 prompt/*.md 移进 src/main/resources/prompt/，仓库根目录不再放
    —— 更简单，但改变仓库现有布局，需确认没有别的流程依赖根目录路径

【无论哪条都要有启动自检】：应用启动时读一次提示词，读不到【直接启动失败】
    —— 否则问题会推迟到第一次真实调用才暴露，而那时用户已经在等结果了
```

LLM-B 若在 §13.2.2 选方案 b（DSL 内嵌提示词），它那一份不受本节约束；
选方案 a 则与 LLM-A 共用同一套加载机制。

#### 6.2.3 多个降级原因同时命中（必须按此实现）

三个原因**可以同时发生**（超 30 页被截断 + 某批读不清 + 疑似漏抽过敏原），
而 `partial_reason` 只有一列。**抑制标志绝不能从当前 `partial_reason` 反推。**

`PartialReason` 是枚举，**核心逻辑不得出现魔法字符串**（`AGENTS.md` §6）：

```java
// support.PartialReason —— 每个常量带中文注释（AGENTS.md §6「注释」）
public enum PartialReason {
    /** 报告超过 30 等效页，只处理了前 30 页（§5.4） */
    PAGE_TRUNCATED,
    /** 某一批次读不清被整批丢弃（§6.2） */
    BATCH_UNREADABLE,
    /** 疑似漏抽过敏原，模块四不输出（§6.5） */
    ALLERGEN_SUSPECT_MISS
}
```

```java
// task.DegradeAccumulator —— 任务级单例，全程累积，只增不减
class DegradeAccumulator {
    boolean pageTruncated;
    boolean batchUnreadable;
    boolean allergenSuspectMiss;

    // ★ 抑制标志按 OR 累积，与 partial_reason 取哪一个【完全无关】
    boolean suppressDietAdvice()    { return pageTruncated || batchUnreadable; }
    boolean suppressDishRecommend() { return pageTruncated || batchUnreadable || allergenSuspectMiss; }
    boolean partial()               { return pageTruncated || batchUnreadable || allergenSuspectMiss; }

    // 落库的单值，按【严重度】取，不按命中顺序；返回枚举，不返回字符串
    PartialReason primaryReason() {
        if (pageTruncated)        { return PartialReason.PAGE_TRUNCATED; }
        if (batchUnreadable)      { return PartialReason.BATCH_UNREADABLE; }
        if (allergenSuspectMiss)  { return PartialReason.ALLERGEN_SUSPECT_MISS; }
        return null;
    }
}
```

**严重度顺序就是「抑制范围从大到小」**：前两个关掉模块三和四，第三个只关模块四。
取范围最大的那个落库，用户看到的降级说明才不会小于实际发生的降级。

> **不这样做会怎样**：`PAGE_TRUNCATED` 先命中、`ALLERGEN_SUSPECT_MISS` 后命中并覆盖了它，
> 如果 `suppressDietAdvice` 从 `partial_reason` 推导，就会得出「过敏原因不关模块三」
> → **模块三被重新输出**，而实际上报告后 15 页根本没读。
> 这是一条静默错误：页面看起来正常，内容却基于残缺信息。

**本版不把 `partial_reason` 改成多值**（一列 JSON 或多行表），因为它只用于展示与排障，
真正驱动行为的是上面三个布尔。**如果将来前端要逐条列出降级原因，再改成多值。**

结果接口下发 `partial` / `partialReason` / `suppressDietAdvice` / `suppressDishRecommend`
/ `processedPages` / `totalPages`，**四个开关字段全部取自 `DegradeAccumulator`**。

### 6.3 Java 校验层：执行顺序不可调换

```java
// llm.a.ExtractionValidationPipeline —— 严格按 ① → ①a → ①b → ② → ②a → ②b → ③ → ④ 执行
```

#### ① Schema 校验

`schema/extraction_output.schema.json`。**违规能定位到单个条目 → 剔除该条目并标记部分结果；
定位不到 → 直接 `FAILED / SERVER_ERROR`。一律不重试。**
Schema 校验频繁失败时改提示词或换模型，不是加重试。

> **「Schema 通过」不等于「报告已完整识别」**——`"allergens": []` 结构上完全合法。

**（2026-09-02）剔除机制**，依据与完整论证见设计方案 §4.4-①：

```
可剔除    indicators  textualFindings  summaryConclusions  nutritionSupplements
          dietRequirements —— 剔了必须连带抑制模块四（DIET_REQUIREMENT_DROPPED）：
                             每条饮食注意在模块四生成一个 REJECT 方向集合
不可剔除  allergens（一级红线，静默少一条会把格式错误伪装成 ALLERGEN_SUSPECT_MISS 漏抽降级）
          sections（被其他条目按 sectionIndex 引用）
          顶层字段（定位不到「某一条」）

剔除后【重新校验一次】，仍不合法则整批作废——不做猜测式修复
剔除量上限 20%，且至少允许 1 条（否则两三条的小批次剔一条就作废）
翻转 PartialReason.SCHEMA_ITEM_DROPPED（抑制范围为空）+ 记带关键字与 JSON 路径的 WARN
```

落地在 `ExtractionSchemaValidator`，返回 `SchemaValidationOutcome`（输出 + 剔除统计）。
**依据是实测**：单条目不合 Schema 约 1.2%，整批作废下 200 条的任务成功率只有 8%。

#### ①a `blockRef` 展开

Schema 通过后的**第一件事**，早于所有业务校验：

```
blockRefs / sectionBlockRef / nameBlockRefs / genderBlockRefs
    → 查本批映射表逐个展开为 segmentId
    → 越界、重复、映射不到 → 该引用视为「不存在的 segmentId」，按 ④ 处理
    → 展开后 blockRef 不再出现在下游
```

映射表是 Java 自己发请求时构造的，展开是查数组，**不含任何判断**。
下游全部按 `segmentId` 工作——去重、跨批合并、分组都需要跨批唯一，`blockRef` 只在批内有意义。

#### ①b 引用完整性校验

Schema 只能约束「是个 0~199 的整数」，约束不了「这个下标真的存在」：

```
sections 自洽
    sections[i].sectionIndex 必须唯一且 == i（数组下标）
    → 不满足：FAILED / SERVER_ERROR（模型契约违约）

条目引用有效
    每个条目的 sectionIndex 必须 ∈ {sections[].sectionIndex}
    → 不满足：该条目【整条丢弃】
      属 allergens 时按 ④ 触发 ALLERGEN_SUSPECT_MISS

sourceOrder / orderInSection
    只做非负与上限的范围检查，【不要求连续】——模型漏号不影响排序结果
```

没有这一层，`sectionIndex=7` 而本批只有 3 个章节的条目会一路走到分组逻辑，
在 §7.1 变成指向空气的 `groupKey`，**排查时看起来像分组代码的 bug**。

### 6.3.1 准入三分法（决定条目进哪个数组）

模型侧的抽取规则，Java 侧据此知道该读哪个数组，**不得自行搬运条目**：

```
有检查结果 + 有结论              →  indicators      conclusionBasis = REPORT_TEXT
                                                    例：甘油三酯 2.8 mmol/L 0.56~1.70 ↑偏高
有检查结果 + 无结论 + 数值可比    →  indicators      conclusionBasis = REFERENCE_RANGE_IN_RANGE
                                                    例：白细胞 6.2 ×10⁹/L 4.0~10.0（提示列留空）
有检查结果 + 无结论 + 定性可比    →  indicators      conclusionBasis = REFERENCE_VALUE_MATCH
                                                    例：亚硝酸盐 阴性 / 参考值 阴性
有检查结果 + 无结论 + 无法明确比较 →  【全部丢弃】
无检查结果 + 有结论              →  textualFindings 例：肝胆B超：提示脂肪肝
```

**参考范围准入**（2026-08-27 增补，设计方案 §4.3.1）：

```
模型负责：结果/单位/参考范围是否同属一个指标、多套人群范围选哪套、单位是否对齐、
         把区间拆成 lowerBound / upperBound 与开闭标志
Java 负责：只对拆好的十进制数做 BigDecimal.compareTo，见 IndicatorRangeComparison

Java 侧的核验（ExtractionValidationPipeline.referenceRangeAdmits）：
     整组区间必须与 refRange 原文解析出的某个区间完全一致（ReferenceRangeParser）
         下界、上界、两侧开闭一起比；数值用 compareTo，4.0 与 4.00 判等、40 与 4.0 不判等
         ← 防三种绕过：省略一侧边界、拿子串当边界（4.0 在 14.0~20.0 里）、篡改开闭符号
         ← 原文写法认不出（非 a-b / a~b / a至b / <、≤、<=、>、≥、>= 这几种）时一律不展示
     measuredValue 必须与 value 数值相等       ← 用 compareTo，允许 6.2 与 6.20 的标度差异
     上下界自相矛盾、都不设限、非十进制         ← 一律丢弃
     结果不在范围内                            ← 丢弃，【不得改判为 HIGH/LOW】

定性型（REFERENCE_VALUE_MATCH）：
模型负责：结果归一化成 ComparableQualitativeValue 四态枚举；
         参考值【展开成允许取值的集合】（「阴性或弱」→ [NEGATIVE, WEAK_POSITIVE]）
Java 负责：只做集合包含，见 ExtractionValidationPipeline.referenceValueAdmits
     【绝不做字面子串匹配】「阳性」是「弱阳性」的子串，字面包含会把异常判成正常
     【NOT_DETECTED 与 NEGATIVE 不是同义词】——要认等价，模型在归一化时就要统一
     枚举外的取值、两侧不等、valueMatch 为 null → 该指标不展示
     ⚠️ 归一化枚举【无法回溯原文】，这条路径对模型归一化是信任关系而非校验关系
        （数值型的上下界可以逐字核验，两者信任边界不同）
```

**`status = NORMAL` 的语义边界**：走这两条路径时，`NORMAL` 只表示
「符合本报告给出的参考值」，**不表示未发现疾病、更不表示身体正常**。
接口字段说明与页面文案必须同口径（需求 §5-3）。

```
【结论为「正常」的项同样要抽】—— 模块一展示全部有结论的指标，不是只展示异常的
     textualFindings 里同样有正常项：「肝胆B超：未见明显异常」「心电图：窦性心律，正常心电图」
     它们靠 status 区分，status = NORMAL 时 includeInHealthProblems 必须为 false

Java 侧的后果：模块一的「总指标数」仍可能少于报告的「总项目数」
     参考范围准入补回了「有数值无结论但有参考值」的那批（实测样本 A 从 31 项涨到 56 项）
     剩下的缺口是「无结论且无可用参考值」的项，这是设计选择不是 bug（§7.2）
     【Java 不得为了对齐数字而把它们补回来，也不得放宽参考范围准入的四条硬规则】
```

### 6.4 来源校验：凡声明「来自报告原文」的字符串都要能回切

**契约不允许模型返回不受来源约束的展示文案。** 校验档位按该 segment 的 `textSource`：

| `textSource` | 校验方式 |
|---|---|
| `NATIVE` | **严格子串**：规范化(字段值) 必须是合并 `normalizedText` 的子串 |
| `OCR` | **放宽**：去全部空白后子串匹配；仍不中则归一化后编辑距离 ≤ 1 |

> OCR 放宽是必须的：OCR 把 `2.8` 认成 `2.6` 时，模型看图正确读出 `2.8`，
> 严格匹配会把**正确的抽取结果**杀掉，而拍照上传是主流形态。

**逐字段处理表（实现时照此写 `switch`）：**

| 字段 | 校验对象 | 不过时 |
|---|---|---|
| `indicators`：`name` `value` `unit` `refRange` `conclusionText` | 该条目 `segmentIds` 合并文本 | 该指标**整条丢弃** |
| `indicators.problemName`（非 null 时） | 同上 | 降为 `null`，走 §7.3 拼接分支，**不丢条目** |
| `textualFindings`：`title` `conclusionText` | 该条目 `segmentIds` | 该条**整条丢弃** |
| `allergens`：`rawName` `rawResult` | 该条目 `segmentIds` | 该条丢弃，**且触发 `ALLERGEN_SUSPECT_MISS`** |
| `sections.sectionName`（`CURRENT`） | 该章节 `sectionSegmentId` | 该组 `displayName` 降为「未标注章节」，**不丢内容** |
| `sections.sectionName`（`UNSECTIONED`） | **该组覆盖的全部 segment 合并文本** | 同上，降为「未归入章节的内容」 |
| `reportOverview` 两个数字 | `reportOverview.segmentIds` 合并文本，**两个十进制数字都要在** | 整个 `reportOverview` 降为 `null`，回退 §7.2 的 Java 计数 |
| `patient.name` / `gender`（非 null 时） | 各自证据数组展开后的文本 | 对应字段降为 `null`，**不得参与 §6.6 的冲突判断** |

```
展示原文类长文本一律不用模型返回值：健康问题 rawText、饮食建议来源原文
    → Java 按 segmentId 取整段 rawText，模型只负责指路
segmentId 不存在 → 该条整条丢弃
不给指标做字符区间切分：规范化会改变字符数（NFKC 把 ㎎ 拆成 mg），
    字段级 offset 需维护 raw↔normalized 逐字符映射表，成本远高于收益
```

### 6.5 安全扫描与降级（生产链路仅有的词表用法之二）

#### A. 高风险内容交叉扫描

对全部 segment 的 `normalizedText` 扫描：

```
过敏原章节名（过敏原、变应原、IgE、致敏原）命中而 allergens 为空
阳性标记（阳性、(+)、＋）出现在过敏章节 segment 内而 allergens 为空
    → partial=true, partial_reason=ALLERGEN_SUSPECT_MISS
    → 模块一二三照常，模块四整体不输出
```

**没有饮食医嘱词扫描，没有 `dietSuspectMissCount`**——它只记计数不影响输出，
属 §0.3-② 禁止的那一类。饮食建议抽得全不全去跑评测集。

#### B. 过敏覆盖集合规则（模型输出的内部一致性）

```
S = allergenSectionSegmentIds     过敏章节全部段（含没抽出条目的数据行）
D = allergenDataSegmentIds        其中确认读到检测数据行的段
A = allergens 各条目 segmentIds 的并集

结构断言（不满足 → FAILED / SERVER_ERROR，模型契约违约）
    D ⊆ S        A ⊆ S

覆盖判定
    S 非空 且 D 为空   → 有章节、一行数据都没读出来   → ALLERGEN_SUSPECT_MISS
    D \ A 非空         → 有数据行没有对应条目 = 漏抽  → ALLERGEN_SUSPECT_MISS
    D 非空 且 D ⊆ A 且 A 全 NEGATIVE → 「读全了全是阴性」，正常
    S 为空             → 报告没有过敏章节，以 A 的扫描为准
```

> **B 不是独立防线**：`S`/`D`/`A` 全来自同一次模型输出，模型同时漏掉数据行和条目时
> `D \ A` 仍为空。它只拦「自相矛盾」，拦不住「一致地漏」。

#### C. 阳性行覆盖扫描（候选段的发现依据独立于模型）

```
候选阳性段 = { seg | seg.normalizedText 同时命中
                    ① ADMITTED_RESULT_MARKS（阳性/弱阳性/可疑/临界/(+)/＋/± …）
                    ② 任一已知过敏原名称（AllergenGroups 全部 displayName + matchWord） }

每个候选段必须 ∈ A
    命中而不在 A → ALLERGEN_SUSPECT_MISS
```

**分两步，只有第一步独立**：候选段从原文独立找出（模型漏多少都不影响候选集大小），
第二步当然要用模型输出，否则无从谈「漏没漏」。

> **⚠️ 已知盲区，实现时不得试图弥补：** 名称与结果被拆进不同 segment 时命中不了。
> OCR 路径识别块≈一整行（能命中）；PDF 原生绘制单元≈一个单元格（`[牛奶]` `[阳性(+)]`
> 两块，**命中不了**）。要配对只有 bbox 判同基线 / seq 取相邻 / 表格还原三条路，
> **全是版面推断，全部禁止**（§0.3-①、`AGENTS.md` §3）。
> 本层定位是「同块场景的有限兜底」，文档与代码注释都**不得**称其为完整覆盖。

#### D. 过敏原准入过滤

```
仅以下两类进入过敏提醒区与菜品拦截链路：
    resultStatus == POSITIVE
    resultStatus == BORDERLINE      // 作为产品安全信号从严准入，但不等同临床确诊

以下都不进入，但计数口径不同：
    resultStatus == NEGATIVE        // 【不计数】筛查表里的绝大多数，计了没信息量
    resultStatus == UNKNOWN         // 这一行没读明白，
                                    //  而它不触发任何降级，计数是唯一能看见它的地方
```

**`isFoodBorne` 由 `enumKey` 查表得到，不由模型也不由词表决定：**

```
正式枚举（13 食入 + 5 非食物）→ 查 AllergenGroups 常量表，【模型返回值直接丢弃】
enumKey == OTHER              → 采信模型，Java 不校验、不改写、不告警
```

#### E. 回切失败的连带处理

```
被丢弃的条目属于 allergens 时 → partial=true, partial_reason=ALLERGEN_SUSPECT_MISS
```

过敏原条目回切失败**不能简单丢弃后继续推荐**——丢掉的可能正是那条要命的。

#### F. 健康问题准入

只有 `includeInHealthProblems = true` 的条目进入模块二。
**判定权完全在 LLM-A，Java 既不覆写也不扫词表告警。**

### 6.6 多批与多文件合并

```
跨批排序          一律按 §7.1 排序总则，不按批次号，不按批次完成顺序
跨批去重          只在批次输入确有重叠时执行，判据是【同 segmentId 且同 itemIndex】
                  保留 page 较小的一条；【非重叠页面之间一律不去重】
姓名合并          取所有通过 §6.4 来源校验后的非空值；全空视为该文件未识别出姓名
```

> 去重不能用 `sectionName + name + value + unit` 四元组——那会误删
> 「左眼视力 5.0 / 右眼视力 5.0」「静息心率 72 / 运动后心率 72」这类真实结果。

**多文件同一性校验（弱校验，发现冲突则拒绝）：**

```
取所有通过来源校验的 patient.name / gender 非空值比对
姓名：规范化（去空格）后完全相等即通过
> **【繁简转换不做】**（2026-08-26 产品确认）实现只做 NFKC + 去空白。
> 代价是**繁简同名会被判成不同人**（「张伟」vs「張偉」→ `IDENTITY_MISMATCH`，整任务失败）。
> 方向仍是 fail-safe——只会拒绝，不会把两个人的报告合并——所以接受这个限制。
> 补齐需要引入 OpenCC 类生产依赖或一份需医务审核的姓氏映射表，暂不投入。
> **不得用拼音或不完整映射顶替**：那会制造新的误合并，方向就反了。
任一不一致 → FAILED / IDENTITY_MISMATCH，不自动合并、不做确认弹窗
```

放行的情况（产品已确认）：全部识别不出姓名、只有一份识别出、姓名同性别缺一份、同名不同人。

**多文件分组：`groupKey` 用结构化 ID，跨文件绝不合并**（详见 §7.1）。

---

## 7. 四模块组装

### 7.1 排序总则（唯一实现，其余各处只调用）

```java
// assemble.sort.DisplayOrder —— 全案排序只有这一个实现，禁止在模块里另写比较器
```

```
分组顺序   fileIndex → groupOrder
组内顺序   groupOrder → page → orderInSection    （indicators / textualFindings）
条目顺序   groupOrder → page → sourceOrder       （summaryConclusions / allergens /
                                                   nutritionSupplements / dietRequirements）
groupOrder = 该组第一次出现时的 (group.page, batchIndex, sectionIndex)
```

**三条硬约束：**

```
① 顺序字段一律由 LLM-A 给，Java 只比较和排序
② 批内序号（sectionIndex / orderInSection / sourceOrder）跨批会撞号，
   每条排序键都必须先用 page 收敛；sourceOrder 作用域是「章节内、批内」，
   跨章节也会撞，所以前面必须先有 groupOrder
③ 任何排序键都不得使用 segmentId 里的 seq —— 那是解析器产出顺序，不是阅读顺序
```

**`page` 契约里没有，Java 从 `segmentId` 算：**

```java
item.page  = min{ page(segmentId) | segmentId ∈ 该条目展开后的 segmentIds }
group.page = min{ item.page       | item ∈ 该组全部条目 }
```

取 `min` 的理由：跨页续表的指标「属于」它开始的那一页，取 `max` 会让它排到后面、
和同页条目分开。这是从字符串取数字再比大小，**不是版面推断**——
页码是报告的客观属性，`seq` 是解析器的实现细节。

**分组键：**

```
groupKey    = fileIndex + "-" + 有效 sectionSegmentId      跨文件绝不合并
displayName = 单文件：sectionName
              多文件：「报告{fileIndex+1}-」+ sectionName
```

| `sectionRelation` | 有效 `sectionSegmentId` | `displayName` |
|---|---|---|
| `CURRENT` | 模型给的那个 | `sectionName` |
| `CONTINUATION` | **继承**同文件内前一批末章节的 | 被继承章节的 `sectionName` |
| `UNSECTIONED` | `"U-" + 组内最小 segmentId` | `sectionName`，须过 §6.4 来源校验，不过则「未归入章节的内容」 |
| `UNKNOWN` | `"X-" + 组内最小 segmentId` | 固定文案「未标注章节」 |

```
Java 只在 CONTINUATION 一种取值下继承，且只做继承、不做识别
文件的第一批就报 CONTINUATION 时没有可继承对象 → 按 UNKNOWN 处理
                                                【不向前跨文件继承】
跨批的同一章节【不会两批返回同一个 ID】——批次不重叠，后一批看不到前一批的标题块
后两态用最小 segmentId 只是【唯一键】不是顺序键，展示顺序仍由 groupOrder 决定
```

### 7.2 模块一：健康指标

| 卡片字段 | 来源 |
|---|---|
| 指标名称 | `indicators[].name`，报告原文不改写 |
| 检测值 + 单位 | `value` + `unit` |
| 参考正常范围 | `refRange`；报告没写时展示「报告未提供」，**禁止填充通用参考值** |
| 展示结论 | `conclusionText`：`REPORT_TEXT` 直接引用报告原文；`REFERENCE_RANGE_IN_RANGE` 固定为「在参考范围内」；`REFERENCE_VALUE_MATCH` 固定为「符合报告参考值」 |
| 结论生成标志 | `conclusionGenerated`：报告原文为 `false`；两种参考值准入固定文案为 `true`，前端必须做视觉区分 |
| 状态标签 | 见下 |

**状态四态**：`NORMAL`(绿) / `HIGH`(红↑) / `LOW`(蓝↓) / `ABNORMAL`(橙)。
第四态用于承载「阳性(+)」「可疑」「临界」，**需产品确认**（设计方案 §12-1）。

```
status 由 LLM-A 给，Java 在线【不做任何校验】：
    模型给的 status  →  直接采用
    模型漏给 status  →  按 Schema 必填拦下，剔除该条指标（§4.4-①）

展示规则：颜色跟判定，文字按 conclusionBasis 分流
    标签颜色 = status 对应色
    REPORT_TEXT              → conclusionText 报告原文（如「阳性(+)」），conclusionGenerated=false
    REFERENCE_RANGE_IN_RANGE → 「在参考范围内」，conclusionGenerated=true
    REFERENCE_VALUE_MATCH    → 「符合报告参考值」，conclusionGenerated=true
    后两条只陈述与本报告参考值的关系，【不写「正常」「未见异常」】
```

**分组**：按 `groupKey` 分组（**不用 `sectionName` 做键**——多文件时两份报告都有「血脂检查」），
全部平铺展示不折叠。

**总览条：**

```
总指标数 = 本模块展示的条数
正常项数 = status == NORMAL 的条数
异常项数 = HIGH + LOW + ABNORMAL 的条数
异常占比 = 异常项数 / 总指标数，四舍五入到整数百分比

reportOverview 非空（且过了 §6.4 数字校验）→ 展示报告的数字并标注「（报告原文）」
                                            两者不一致不纠错、不告警，以报告为准
reportOverview 为空 → 用上面的公式算
```

**不加任何差值说明文案**——总指标数可能少于报告实际项目数（只列数值不给结论的项不展示，
在生化全套、血常规里常占 30~40%），产品已确认不处理该不一致。

**空态**：展示「本次报告未提取到带明确结论的指标项」，**保留总览条位置展示 0，不隐藏模块**。

**底部声明**：`以上指标数据均来自体检报告原文，仅供参考，如有疑问请咨询医生。`

### 7.3 模块二：健康问题

| 来源类型 | 数据来源 | 准入条件 | 带 `indicatorId` |
|---|---|---|---|
| `INDICATOR_NUMERIC` | `indicators` | `includeInHealthProblems = true` | **是** |
| `INDICATOR_TEXTUAL` | `textualFindings` | `includeInHealthProblems = true` | 否 |
| `SUMMARY` | `summaryConclusions` | `includeInHealthProblems = true` 且 `categories` 含 `HEALTH_PROBLEM` 或 `DIET_ADVICE` | 否 |

```
三类准入统一用 LLM-A 的 includeInHealthProblems
【不得】由 status != NORMAL 派生 —— 准入是语义判断，不是 status 的机械函数
INDICATOR_TEXTUAL 不带 indicatorId：「脂肪肝」这类无数值结论根本不会生成指标卡片，
                                   没有可跳转目标；前端据此只对 INDICATOR_NUMERIC 显示跳转按钮
```

**条目字段：**

| 字段 | 生成方式 |
|---|---|
| `displayName` | `INDICATOR_NUMERIC`：`problemName` 非 null 用它；为 null 时 Java 拼 `name + " " + conclusionText`——**两段都是报告原文，只做字符串连接**<br>`INDICATOR_TEXTUAL`：用 `title`<br>`SUMMARY`：用回切后的原文 |
| `displayNameGenerated` | 布尔。`true` = 走了拼接分支（`problemName == null`） |
| `sourceLabel` | 单文件「血脂检查–甘油三酯」「总检结论第3条」；多文件加「报告2-」前缀。**章节名取 §7.1 的 `displayName`，不写死「总检结论」** |
| `rawText` | 按 `segmentId` 取整段原文；`segmentId` 不存在则该条丢弃 |
| `indicatorId` | 仅 `INDICATOR_NUMERIC` 下发 |

> **Java 不拼「归一化结论词」。** 报告写「↑」就拼成「甘油三酯 ↑」，
> **不得**翻译成「甘油三酯偏高」——那是系统造出报告里没有的表述。

**排序**（调 §7.1）：

```
INDICATOR_NUMERIC + INDICATOR_TEXTUAL 在前，按 fileIndex → groupOrder → page → orderInSection
SUMMARY 在后，按 fileIndex → groupOrder → page → sourceOrder
不做严重程度分级、不做风险排序
```

**空态**：`本次报告未提取到明确的异常结论或健康提示。`
> **不得写成「各项指标均在正常范围内」**——提取不到不等于人是健康的。OCR 糊了、模型漏了、版式没见过都会走到空态，那句话是在替系统下医疗结论。
**底部声明**：`以上内容均为体检报告原文结论的汇总，不构成二次诊断，如有疑问请咨询医生。`

### 7.4 模块三：饮食建议

**三条硬约束，靠结构保证不是靠提示词：**

```
① 不从指标异常推导建议 —— 甘油三酯偏高 ≠ 低脂饮食，只有报告明文写了才生成
② 不合并同向建议 —— 「低脂饮食」与「控制体重」各自成卡片，各自引用各自原文
③ 不在饮食建议中引用指标数据
   守法方式是结构性的：本模块的【输入只有 enumKey + rawText】，结构上看不到指标数据
```

**直接后果（产品已确认接受）**：报告只写「血脂偏高，建议复查」时，
饮食建议三个分区全空态、菜品推荐空。**这是需求 §7-5 的明确选择，不是缺陷**，
不做通用建议兜底。

**枚举**（36 个正式 + `OTHER`）：13 食入性过敏原 + 5 非食物过敏原 + 9 营养补充 + 9 饮食注意。
真源是 `constants` 包的 Java 常量类，**没有 CSV、没有生成器、没有运行时加载**。

**来源标注完全由字段拼出，Java 不做任何推断：**

```
来源标注 = 章节展示名 + "–" + 原文
    章节展示名 = sectionIndex → §7.1 的 displayName
    「第N条」   = itemNo 非 null 时用它；【为 null 时不写条号，不拿 sourceOrder 顶替】
    原文       = blockRefs 展开后按 segmentId 取整段 rawText
排序     = groupOrder → page → sourceOrder
```

**结构化准入政策（2026-08-28 重构，设计方案 §7.3）：**

```java
// safety.StructuredAdmission —— 两层，第一层是模型判定，第二层是 Java 词表兜底
第一层：applicability == CURRENT_PATIENT && structuredSafety == NORMAL 才放行
        两个枚举都由 LLM-A 给；Java【不判断年龄、不解析代词指向、不猜「儿童」在说谁】
        缺失或非法一律按 UNCERTAIN 处理 → 抑制（fail-closed）

// safety.HighRiskAdviceGate —— 不可被模型推翻的兜底，只扫 adviceQuote
低蛋白 / 限蛋白 / 优质低蛋白 / 低钾 / 限钾 / 低磷 / 限磷 / 低碘 / 限碘 / 忌碘 / 高碘
        【人群裸词已移除】妊娠 / 孕期 / 哺乳期 / 儿童 不再在词表里——
        它们不是限制表述，指向谁由 applicability 判断
        【扫描对象是 adviceQuote 不是整段 rawText】模型摘出的建议本身那一句，
        上限 100 字、必须逐字回切；回切不过整条丢弃
```

```
命中任一层 → 打 structuredOutputSuppressed = true，该条按 OTHER 路径处理
       只展示报告原文与来源，不生成食材清单、不参与菜品匹配、不进入打标维度
   【不覆写 enumKey】—— enumKey 是 LLM-A 的归一化结论，改它就是替模型下另一个结论
                   模型原本给的值原样保留，仅用于排障归因
```

这道闸只会让系统**少输出内容**，永远不会让它输出一个不同的医疗语义——这是它合法的原因。

> **【作用范围仅限营养补充与饮食注意，不含过敏原】**（2026-08-26 产品确认）
> 过敏忌口本身就是要展示给用户的安全信息。「妊娠 / 儿童」这类词出现在过敏原原文里是常态，
> 用它们连带抑制忌口清单，等于把最该看见的内容收掉，**方向反了**。
> 实现上过敏原卡片的 `structuredOutputSuppressed` 恒为 `false`，
> 只有 `enumKey == OTHER` 这一个原因会让它走 OTHER 路径。

**`OTHER` 的处理**（含被安全闸抑制的条目）：

```
照常展示该条建议的报告原文与来源标注
不加任何说明文字（产品决策）
不生成食材清单、不参与菜品匹配、不进入打标维度
```

> 这不满足需求 §7-3（要求每条建议都列食材/摄入量/搭配建议），**产品已确认接受该降级**。

**空态**（三个分区独立）：

| 分区 | 文案 |
|---|---|
| 过敏提醒 | 本次报告未提取到明确的过敏原相关内容 |
| 营养补充 | 本次报告未提取到明确的营养补充建议 |
| 饮食注意 | 本次报告未提取到明确的饮食注意要求 |

> **主语必须是「我们没提取到」，不是「报告未涉及」**——后者是在陈述报告的内容，而我们只知道自己没提取到。过敏这一条尤其不能反过来说。

**全模块不出现任何提示、说明或警示文字**，只有报告原文、来源标注、已收录枚举的食材内容、
上表空态句和底部声明。

**底部声明**：`以上建议均基于体检报告原文，不构成医疗或营养处方，具体饮食方案请遵医嘱。`

### 7.5 模块四：食堂菜品推荐

#### 7.5.1 数据前提

```
ct_dish             company_id、dishes_id、dish_name、on_shelf、biz_date
ct_dish_ingredient  company_id、dishes_id、ingredient_name、weight_g
【只读，不写，不改结构】实际表名接入时核对；企业与菜品ID列固定为 company_id、dishes_id
```

只有凌晨任务可以读取这两张表。在线模块从任务记录取得创建时固化的 `companyId`，只读取
`dish:recommend:{companyId:bizDate}:...` 集合；禁止在线调用 `DishQueryService` 或回源标签表。

> **⚠️ `ct_dish_ingredient` 里没有调味料**（已确认）。油、盐、糖、酱油、醋、料酒、蚝油、
> 香油、豆瓣酱、沙拉酱、XO酱、鸡精、淀粉一概不入表。
>
> **全案禁止「食材表里没有 X 所以判 NEUTRAL」的推理**——它只能推出「主料配料里没有 X」。
> 这条要写进 LLM-B 提示词，也要写进 Java 兜底的注释里。

#### 7.5.2 三个维度的判定方式

| 维度 | 枚举数 | 判定方 |
|---|---|---|
| 食入性过敏原 | 13 | 凌晨离线打标，只发布不推荐集合 |
| 营养补充 | 9 | 凌晨 Java 确定性交集打标，只发布推荐集合 |
| 饮食注意 | 9 | 凌晨离线发布 9 个不推荐集合；仅低嘌呤、高纤维按主料确证后发布推荐集合 |
| 吸入性过敏原 | 5 | **不参与** |

LLM-B 实际处理 13 + 9 = **22** 个维度，只输出 `REJECT / UNKNOWN / NEUTRAL`；低嘌呤、高纤维
正向及 9 个营养维度由 Java 在同一凌晨任务按主料计算。在线最终读取
13 + 2 + 9 + 9 = **33** 个方向 SET 中与当前报告相关的部分。

#### 7.5.3 营养补充：凌晨任务中的纯 Java 打标

```java
Set<String> matched = intersect(
    mainIngredients(dish),                                 // §7.5.4
    NUTRITION_CONTENTS.get(enumKey).recommendIngredients   // 内容常量
);
verdict = matched.isEmpty() ? NEUTRAL : RECOMMEND;
```

食材名对不上时（「猪肝」vs「鲜猪肝」）用 `IngredientAliasWords` 别名表兜一层，
未命中即按不匹配处理。命中菜品直接写入 `nutrition:recommend:<enumKey>` staging SET；
在线不运行本段算法，也不读取菜品食材。

#### 7.5.4 主料推导

```java
Set<String> mainIngredients(Dish d) {
    // 1. 排除无重量数据的食材；全部无重量 → 返回空集（该菜营养维度全 NEUTRAL）
    // 2. total = 剩余食材重量之和
    // 规则一：重量占比 >= MAIN_RATIO(0.25)，无论名次
    // 规则二：重量前 2 名，且占比 >= TOP_N_MIN_RATIO(0.15)
    // 两条取并集；都不满足则取最重的一个
}
```

**没有「排除调味料」这一步，没有 `SEASONING` 常量**——食材表本来就不含调味料。
**阈值不用重新校准**：旧公式的分母本来就是「排除调味料后的重量和」，而排除是空操作。
两个阈值仍需按设计方案 §11-2 用 50 道真实菜品校准。

饮食正向匹配复用本节主料结果，但固定只处理两个枚举：

```java
EnumSet<DietRequirementKey> positiveDietTypeSet = EnumSet.of(
    DietRequirementKey.LOW_PURINE,
    DietRequirementKey.HIGH_FIBER
);
Set<String> matchedMainIngredientSet = intersect(
    mainIngredients(dish),
    DietRequirementContents.ALL.get(enumKey).getRecommendableFoodList()
);
boolean recommend = safetyVerdict == TagState.NEUTRAL
    && positiveDietTypeSet.contains(enumKey)
    && !matchedMainIngredientSet.isEmpty();
```

该逻辑由凌晨 `DietPositiveMatcher` 执行。其余 7 个饮食枚举即使 LLM-B 认为“看起来符合”，也不得
写 `diet:recommend`；低脂、低盐、限糖、限酒等缺少调味料、用油量、酒精或完整配方证据，只允许
根据明确反向证据写 `diet:reject`。执行时点从在线移到离线不构成扩大推荐维度的依据。

#### 7.5.5 过敏的 Java 关键词兜底（生产链路词表用法之四）

```java
// safety.AllergenKeywordFallback
// AllergenKeywords 直接由 AllergenGroup 的 avoidIngredients ∪ hiddenFoods 得出，与展示同源
// 匹配范围：菜名 + 全部食材名（不只主料）；无重量阈值，微量即命中
任一来源判 REJECT → 该菜在该过敏维度 REJECT，【模型不可推翻】
```

> **食材表没有调味料之后，这一层实际只剩「菜名」一条通路。**
> 「蚝油生菜」能拦住是因为菜名里写着蚝油；「红烧肉」里的酱油、「凉拌黄瓜」里的香油
> **一个都硬匹配不到**；LLM-B 也不能猜成实际配方，缺少明确证据时必须给 `UNKNOWN`，
> 由凌晨完整性门槛阻止该菜进入正向集合。
> 因此词表必须收录调味料在**菜名**里的写法，且 `MOLLUSK` / `SESAME` 是上线阻断项（§0.4）。

**已知代价：过杀。**「鱼香肉丝」在鱼过敏时会被误标，主动接受。
误杀集中在少数词时加一个 ≤20 条的例外词典（`AllergenExceptions`，已存在）。

#### 7.5.6 枚举外过敏原（`OTHER` 且 `isFoodBorne = true`）

```
OTHER 没有稳定的离线标签维度，不进入 33 个 Redis 集合
（2026-09-02：`AllergenKeywordFallback.matchesOther` 已随本条删除，它是这条规则确立前的遗留）
在线没有食材数据，也不得临时查库做字符串匹配
模块三继续展示报告原文；模块四不据此推荐或排除菜品
```

#### 7.5.7 发布前完整性门槛

`UNKNOWN`、`NEUTRAL` 与批次缺失只用于凌晨构建校验，不写在线 Redis 状态对象。
模型对每批输入的覆盖必须完整且互斥；任一可拒绝维度为 `UNKNOWN` 时，该菜不得进入正向集合。
企业全部分页完成后再校验处理数、31 个维度覆盖、2 个饮食正向维度的正反集合互斥、其余 7 个
饮食维度不存在正向集合和企业归属，失败时该企业当天 33 个正式集合全部不发布。在线 Key 缺失
只返回空态并告警，不回源、不补算、不读昨天。

#### 7.5.8 合并裁决

```text
positiveSet = SUNION(当前报告命中的 diet:recommend 与 nutrition:recommend 集合)
rejectSet   = SUNION(当前报告命中的 allergen:reject 与 diet:reject 集合)

notRecommendedSet = rejectSet
recommendedSet    = positiveSet - rejectSet
```

只存在过敏原时 `positiveSet` 为空，推荐列表为空，过敏命中的菜仍进入不推荐列表；未命中过敏的
普通菜不进入任何列表。不推荐优先由差集保证，进入 `rejectSet` 的菜不得返回任何正向标签或
推荐理由；该规则同样适用于过敏原 `REJECT` 与饮食注意 `REJECT`。
集合计算必须按当前任务的 `companyId + bizDate` 执行，禁止混入其他企业 Key。

#### 7.5.9 标签、理由、排序、空态

| tagType | 来源 | 示例 | 色 |
|---|---|---|---|
| `NUTRITION` | 营养补充 `RECOMMEND` | 补铁、高蛋白 | 绿 |
| `ALLERGY` | 过敏命中 | 虾蟹过敏 | 红 |
| `DIET_AVOID` | 饮食注意 `REJECT` | 高脂、高盐 | 橙 |
| `DIET_OK` | 饮食注意主料确证 `RECOMMEND` | 低嘌呤、高纤维 | 蓝 |

**只有 `LOW_PURINE`、`HIGH_FIBER` 两个 `DIET_OK` 维度和 9 个 `NUTRITION` 维度会写推荐 SET。**
旧的在线 `positiveMatchPolicy` 删除；`DietPositiveMatcher` 移到凌晨任务并固定只处理上述两个维度，
在线组装包不得依赖任何食材匹配器。

**推荐理由直接返回对应维度的报告原文，不拼命中食材：**

```
菜品名称：菠菜猪肝汤
推荐标签：补铁
推荐理由：建议补铁
```

一道菜命中多条时返回全部推荐标签及其对应 `rawText`，完全相同的标签与原文分别去重。
推荐菜 DTO 只有 `dishName + recommendTags + recommendReasons`；不推荐菜 DTO 只有
`dishName + notRecommendTags`，不得返回价格、图片、分类或其他菜品信息。

```java
class RecommendedDishCard {
    String dishName;
    List<String> recommendTagList;
    List<String> recommendReasonList; // 当前报告对应维度的 rawText
}

class NotRecommendedDishCard {
    String dishName;
    List<String> notRecommendTagList;
}
```

```
两个列表都按【菜名拼音首字母】排序（TinyPinyin，请求时实时计算，不落库）
非汉字开头的菜名统一排在汉字之后
排序后各取前 3 道，recommendList 与 rejectList 最大长度均为 3
【截断必须在全部维度合并裁决之后】—— 先取 3 再判过敏，会把该拦的菜留下、该推的菜丢掉
不足 3 道按实际返回，不用占位菜补满
```

| 情况 | 处理 |
|---|---|
| 饮食建议中无任何正式枚举内容 | `本食堂菜品暂无个性化推荐。` |
| 有饮食建议但两列表都空 | `本次未匹配到符合建议的食堂菜品，菜品以食堂实际上架为准。` |
| `suppressDishRecommend = true` | **整个模块不输出** |

**底部声明**：`推荐菜品基于体检报告内容及食堂菜品数据自动匹配，菜品信息以食堂实际上架为准。`

---

## 8. 离线打标（LLM-B）

**在线链路对 LLM-B 的调用次数必须为 0，对标签的写入次数也必须为 0。**
只允许离线预热任务调用。

### 8.1 打标任务（xxl-job，每日凌晨）

**打标与清理是【同一个 xxl-job Handler】里的两步，不是两个 Handler。**

```java
// dish.DishTagJob —— xxl-job 只注册这一个 Handler
@XxlJob("dishTagJob")
public void execute() {
    LocalDate bizDate = LocalDate.now();     // ★ 整条链路只在这里取一次时间
    tagService.run(bizDate);                 // 第 ①~⑥ 步
    cleanupService.run(bizDate);             // 清理，必须在打标【之后】
}
// 【所有下游方法都接受 bizDate 参数，不得自己调 now() / CURRENT_DATE】
//   涉及：企业与菜品分页、Redis Key 的 {companyId:bizDate} 段、last_seen_date、清理基准
```

> **为什么必须合成一个 Handler。** 两个独立 Handler 拿不到彼此的局部变量，
> 只能各自取 `now()`——打标 23:59 开始、清理 00:01 执行时，两者基准差一天，
> 清理会拿「今天」的基准去删打标刚按「昨天」写的行。
> 要跨 Handler 共享就得把 `bizDate` 持久化或走调度参数，那是为一个不存在的需求加机制。
>
> **本版不支持补跑**，`bizDate` 恒等于执行当天，`execute()` 不接受日期参数。
> 将来要补跑时改这一处即可：把 `LocalDate.now()` 换成解析 `jobParam`，下游全部无需改动
> ——这正是「下游只接受参数、自己不取时间」这条约束的价值。

```
① 分页枚举【bizDate】存在在架菜品的 companyId
② 每个企业按 dishes_id Keyset 分页查询菜品主表，当前页菜品 ID 一次批量查询食材
③ 当前页内按模型批量上限继续拆批：LLM-B 完成 13 个过敏 reject 与 9 个饮食 reject 安全判定；
   Java 完成 LOW_PURINE、HIGH_FIBER 两个饮食 recommend 和 9 个营养 recommend，
   并增量写入该企业当天的 33 个 staging SET
④ LLM-B 的 22 个维度结果仍可按 companyId 写 MySQL 供离线排障与增量判断；
   在线不查询本表。营养推荐标签由 Java 在凌晨计算
⑤ 企业全部分页完成后校验处理数量、维度覆盖、正反互斥与企业归属
⑥ Lua 原子替换该企业当天 33 个正式 SET，统一 TTL 3 天；失败则该企业当天不发布
⑦ 上报 company_total / dish_target_total / dish_processed_total / set_publish_total；数量不等即告警
```

**数据库分页契约：**

```sql
SELECT company_id, dishes_id, dish_name
  FROM ct_dish
 WHERE company_id = :companyId
   AND biz_date = :bizDate
   AND on_shelf = 1
   AND dishes_id > :lastDishesId
 ORDER BY dishes_id
 LIMIT :pageSize
```

`dish.tag-query-page-size` 默认 500，属于待压测校准值。禁止 OFFSET；禁止对菜品与食材的一对多
JOIN 结果直接分页；禁止逐菜查食材。每页先取菜品，再用本页 ID 集合一次批量取食材，只保留
当前页内存。第一页的 `lastDishesId` 传 `null`，查询实现不得生成 `dishes_id > null`；后续批次必须
使用上一个非空批次返回的 `lastDishesId`。不同企业独立构建与发布，一个企业失败不能污染其他企业。

每个企业分页前后各按相同条件执行一次 `COUNT(*)`，并维护去重后的 `processedDishCount`；
`countBefore == processedDishCount == countAfter` 才能发布。菜品系统应在凌晨任务窗口冻结当天菜单；
双计数用于发现明显漂移，不宣称能在持续写入的数据源上构造严格时间点快照。

`DishQueryService` 是待接入食堂数据源的占位接口，只写接口和 `TODO` 空实现并抛
`UnsupportedOperationException`，不得返回假数据。边界契约至少包含以下三个分页/批量能力，
接口返回值必须先校验再进入领域层：

```java
CompanyCursorPage queryPreheatCompanyPage(LocalDate bizDate, String lastCompanyId, int pageSize);
DishCursorPage queryOnShelfDishPage(String companyId, LocalDate bizDate, Long lastDishesId, int pageSize);
Map<Long, List<DishIngredient>> queryIngredientListMap(String companyId, List<Long> dishIdList);
```

其中 `queryOnShelfDishPage` 只查询指定 `companyId + bizDate` 的当日上架菜品；其返回页对象契约为：

```java
class DishCursorPage {
    List<Dish> dishList; // 本批菜品，按数据库 dishes_id 严格递增
    Long lastDishesId;   // 本批最后一条记录的 dishes_id；空批次为 null
}
```

第一页传 `lastDishesId = null`。非空批次的 `lastDishesId` 必须等于 `dishList` 最后一条菜品对应的
数据库 `dishes_id`；调用方发起下一批查询时必须把它原样传回，不能自行按列表长度、偏移量或其他字段
计算游标。输入游标非空时，返回的每个 `dishes_id` 及 `lastDishesId` 都必须严格大于输入游标。
空批次必须返回空 `dishList` 和 `lastDishesId = null`，表示分页结束；返回条数少于 `pageSize` 时
调用方也可直接结束，等于 `pageSize` 时允许再查询一次并以空批次结束。

每条菜品和食材的 `companyId` 都必须与入参精确 `equals`。食堂库需确认存在等价索引
`(company_id, biz_date, on_shelf, dishes_id)` 与 `(company_id, dishes_id)`；索引缺失是上线阻断项，
不能用增大页容量掩盖全表扫描。

**清理（`DishTagCleanupService`，`DishTagJob#execute` 的第二步，在打标【之后】跑）：**

> **它不是独立的 `@XxlJob` Handler**，是 §8.1 那个唯一 Handler 里的第二次方法调用
> ——独立 Handler 拿不到同一个 `bizDate`（R18c 断言此事）。命名用 `Service` 而非 `Job`。

```sql
-- :bizDate 由 §8.1 的调度入口传入，【不用 CURRENT_DATE / now()】
DELETE FROM ct_dish_tag
 WHERE last_seen_date < DATE_SUB(:bizDate, INTERVAL 7 DAY)
 LIMIT 5000;                      -- 分批删，避免长事务锁表；循环到影响行数为 0
```

```
必须在打标任务之后跑 —— 打标的第 ② 步会把命中行的 last_seen_date 刷成 bizDate，
                       先删后打会把当天还要用的行误删
必须用【同一个 bizDate】—— 用 CURRENT_DATE 的话，打标在 23:59 开始、清理在 00:01 执行时，
                          两者基准差一天，清理会用「今天」的基准去删打标刚按「昨天」写的行
走 idx_last_seen
不清理会怎样：每次 bump 版本全量重打都留下一整代旧行，
             表持续增长，离线 idx_build 查询逐渐变慢
```

**成本按企业实际菜品量计算**：数据库分页大小与 LLM-B 每次最多 40 道菜是两个独立参数；
这里的 40 是模型调用批量，**Redis 方向集合数仍固定为 33**。
改提示词/词表/模型会让全部 `tagHash` 变化，触发按企业全量重打，需在发版计划里预留窗口。

### 8.2 LLM-B 契约与校验

饮食维度的模型请求只渲染反向与安全判定内容，禁止渲染 `recommendableFoodList`、
`PositiveMatchPolicy` 或其他正向触发规则；正向规则只供凌晨 Java 匹配器读取。

```jsonc
{
  "enumKey": "LOW_FAT",
  "neutralDishIds": [10001],            // 能确认不含/不违反的，只回 ID
  "unknownDishIds": [10003, 10004],     // 判不出的，只回 ID —— 过敏维度下这类是常态
  "hitList": [                          // 只有 REJECT 携带离线校验证据
    { "dishId": 10002, "verdict": "REJECT",
      "evidenceType": "COOKING", "matchedIngredients": [], "reason": "油炸菜品" }
  ]
}
```

**覆盖与互斥断言（JSON Schema 表达不了，Java 在写库前断言）：**

```
neutralDishIds ∪ unknownDishIds ∪ hitList
    必须精确等于本批全部输入 dishId
三者【两两不相交】；任何维度都不接受模型输出 RECOMMEND
【遗漏的菜绝不补成 NEUTRAL】——那会把「没判定」伪装成「确认安全」
```

**（2026-09-02）覆盖问题由整批作废改为归入 UNKNOWN。** 实测真实菜单会出现重复标签与缺失标签；
整批作废下任一批出问题就导致该企业当天 33 个集合一个都不发布，失败率随批数指数放大
（一家 1000 道菜的企业是 25 批）。修复规则：

```
缺失（模型压根没提）        → 归入 unknownDishIds     等于「没判定」，UNKNOWN 正是这个意思
跨列表相交（判定自相矛盾）   → 归入 unknownDishIds     两个判定打架，取安全侧
同列表内重复（抄重了）       → 【只去重，不改判】       意图明确，去重是忠实修复
多余 dishId（不属于本批）    → 直接丢弃                UNKNOWN 也只能装本批的菜
hitList 某条不合 Schema     → 剔除该条，菜品随之归入 unknownDishIds
违规定位不到某道菜           → 整批作废（顶层、enumKey）
```

**为什么 UNKNOWN 是安全侧**：任一可拒绝维度为 `UNKNOWN` 的菜不进任何推荐 staging SET，
不会被推荐出去；它只是不出现在「不推荐」列表里，那是少展示一条，不是安全问题。

**原「绝不补成 UNKNOWN」的顾虑由两点承接**：归入<b>必打日志</b>并带上具体 dishId
（LLM-B 是全案唯一允许记录完整请求与响应的链路，§13.2.7），以及<b>归入量超过 20%
即整批作废</b>（且至少允许 1 道，避免小批次修一道就作废）——大比例出问题说明这一批
整体跑偏，不是个别抖动，那时候放行会得到一份大面积 UNKNOWN 的快照。

**`NEUTRAL` 与 `UNKNOWN` 的语义差别必须在提示词里讲清**（`prompt/dish_tag.md` 已同步）：

```
REJECT    菜名、食材或标准产品名称提供明确或高可信成分证据
UNKNOWN   数据不足判不出 —— 食材表没调味料、菜名看不出、做法不确定
NEUTRAL   有完整配方或调味料标签，能确认不含
```

LLM-B 契约只有三态。饮食正向由凌晨 `DietPositiveMatcher` 根据主料确定，且只接受
`LOW_PURINE`、`HIGH_FIBER`；不得通过提示词或解析兼容把其他饮食维度扩展为推荐。

**当前数据条件下，过敏维度的 `NEUTRAL` 应当罕见。**「白灼西兰花」的正确答案是 `UNKNOWN`
——白灼确实没有明显调料路径，但那只是「我没看出来」，不是「厨房没放」。

三态只用于当前页离线聚合和 MySQL 排障记录，不直接写 Redis。页面内任一可拒绝维度为
`UNKNOWN` 的菜不得进入任何推荐 staging SET；覆盖问题按上面的规则修复，
修复量超预算或定位不到某道菜时才整批作废，进而导致该企业快照完整性校验失败而不发布。在线不再构造 `TAG_MISSING` 或 `HIDDEN` 状态。

### 8.3 缓存结构与在线读取

正式缓存是按企业、日期、标签方向组织的 Redis SET：

```text
dish:recommend:{<companyId>:<bizDate>}:allergen:reject:<enumKey>
dish:recommend:{<companyId>:<bizDate>}:diet:recommend:<enumKey>
dish:recommend:{<companyId>:<bizDate>}:diet:reject:<enumKey>
dish:recommend:{<companyId>:<bizDate>}:nutrition:recommend:<enumKey>
```

`diet:recommend` 只生成 `LOW_PURINE`、`HIGH_FIBER` 两个方向；其余 7 个饮食维度只生成
`diet:reject`。禁止用空 Key 补齐九个正向维度，发布清单固定为 33 个方向。

构建 Key 为 `dish:recommend:{<companyId>:<bizDate>}:build:<buildId>:...`，与正式 Key 同 slot。
全部校验通过后 Lua 一次处理 33 个方向：非空 staging Key 执行 `RENAME` 覆盖正式 Key，合法空集合
执行 `DEL formalKey`，再统一设置 TTL。Lua 失败时不得留下部分正式集合，staging Key 自身也必须有
短 TTL 防止任务崩溃后残留。
脚本返回已处理的方向数，Java 只把它作为发布清单与 Lua 脚本的契约握手，用于发现脚本漂移；
它不是成功写入的成员数，也不应命名或描述成发布结果数量校验。

Member 统一为 `<dishId>\t<dishName>`。实现 `DishSetMemberCodec` 作为唯一编解码点，拒绝空值、
制表符和换行符；同一道菜在所有集合中的成员必须字节级一致。复合成员同时解决集合身份和名称返回，
所以不建名称 Hash；推荐理由只用报告原文，所以不建 `matchedIngredients` 缓存。
在线解码遇到畸形成员时只跳过该成员并累计告警数量，日志不得包含成员原文；不得让格式异常冒泡至
任务 Worker 而把整份分析标成 `SERVER_ERROR`。合法成员继续组装，全部无效时模块四返回空态。

Key 中的 `<companyId>` 是逻辑占位，实际值必须由 `CompanyRedisKeyCodec` 使用 UTF-8
Base64URL 无填充编码；禁止把未经编码的外部企业标识直接放进 `{...}` hash tag。任务、菜品页和
食材页中的原始 companyId 仍必须先做精确 `equals` 归属校验，编码不能代替鉴权。

不建 `active` 和 `all` Key。在线用任务创建时固化的 `companyId` 与本次统一 `bizDate` 直接定位
当天集合；正向候选来自饮食推荐与营养推荐集合并集，不需要全集。只有过敏原时推荐集合为空，
过敏命中集合仍进入不推荐列表。

```text
positiveSet = SUNION(相关 diet:recommend, nutrition:recommend)
rejectSet   = SUNION(相关 allergen:reject, diet:reject)
recommended = positiveSet - rejectSet
rejected    = rejectSet
```

当前实现把全部相关维度的 `SMEMBERS` 命令一次入 pipeline，以一次网络往返取回后在 Java 做并集、
差集和标签归属恢复；不得按维度串行往返，更不得逐菜访问 Redis。排序、冲突裁决后两个列表各取前 3。
推荐列表只恢复正向标签，不推荐列表只恢复过敏原和饮食注意 reject 标签。
推荐理由从当前报告对应枚举条目的 `rawText` 取得；
不推荐菜即使内部仍命中正向 SET，也不得恢复或组装任何正向标签与推荐理由。
相关正向 Key 列表为空时直接使用空 `Set`，相关排除 Key 列表为空时同理；不得调用零参数
`SUNION`，也不得为此引入 `all` 或占位 Key。

在线禁止调用 `DishQueryService`、查询菜品/食材表、计算主料、计算 `tagHash`、回源 `ct_dish_tag`
或调用 LLM-B。当天 Key 缺失时返回空态并告警，不读取前一天数据。

职责拆分固定如下，避免重新长回一个在线大编排类：

| 类 | 职责 |
|---|---|
| `DishTagSnapshotBuildService` | 按企业驱动数据库分页、当前页打标与完整性计数 |
| `DietPositiveMatcher` | 凌晨仅对 `LOW_PURINE`、`HIGH_FIBER` 做主料交集并受 LLM-B 安全结论约束 |
| `DishTagSetPublisher` | staging SET 增量写入、33 个方向校验后的 Lua 原子发布与 TTL |
| `DishRecommendSetKeyFactory` | 企业编码、日期和标签方向 Key 的唯一生成点 |
| `DishSetMemberCodec` | `dishId\tdishName` 唯一编解码点 |
| `DishRecommendSetService` | 在线集合并、差运算及最多 6 道菜的标签归属批量恢复 |
| `DishRecommendAssembler` | 原文理由关联、冲突后排序、各取前 3 和 DTO 组装 |

`DishRecommendAssembler` 及其在线依赖包不得依赖 `DishQueryService`、`DishTagModelClient`、
`NutritionMatcher`、`DietPositiveMatcher` 或任何菜品 Mapper；用 ArchUnit 锁住这条边界。

### 8.4 失效与版本

| 变的是什么 | 谁失效 |
|---|---|
| 某道菜改了食材 | 内部 `tagHash` 变化 → 凌晨重算该菜 → 当天方向 SET 按新结果发布 |
| 换模型 / 改提示词 / 改内容常量 | 版本段变化 → 凌晨全量重打并按企业重新发布 33 个 SET |

**版本段不能从哈希里拿掉。** 只算菜名和食材的话，改了提示词或内容常量之后菜和食材都没变，
diff 会认为标签已存在，永远不会重算——**而且这个 bug 是静默的**，不报错、不告警、监控全绿。

**输出 Schema 的版本不参与**：它只约束返回结构，不改变模型对「这道菜含不含虾」的判断。

三个 `*_version` 列冗余存储，**仅供离线排障，不参与在线 Redis 查询**。
**没有 `tagged_at` 列**——打标时间就是行的 `create_time`。

---

## 9. 横切

### 9.1 错误码

| code | HTTP | 触发点 | `reanalyzable` |
|---|---|---|---|
| `UNSUPPORTED_FORMAT` | 400 | §5.1 格式判定 | — |
| `FILE_TOO_LARGE` | 400 | §4.1 上传：**字节超限**（PDF/OFD/DOC/DOCX ≤ 20MB；JPG/PNG ≤ min(10MB, `effectiveOcrImageBytes`)）**或图片总像素超限**（§5.1，用 `ImageReader` 只读尺寸即可判，不整幅解码，§5.6.3） | — |
| `FILE_UNREADABLE` | 400 | §5.1 可读性 | — |
| `MALFORMED_REQUEST` | 400 | §4.1 上传：multipart 报文本身无法解析（边界符损坏、`Content-Type` 与实际内容不符等）。**与字节超限区分**——超限是 `FILE_TOO_LARGE`，让用户压缩文件重试有意义；报文畸形压多小都没用，必须让前端知道是请求构造错了 | — |
| `FILE_ALREADY_BOUND` | 409 | §4.2 绑定（**必须回已绑定的 taskId**） | — |
| `FILE_EXPIRED` | 409 | §4.2 绑定时 `file.expire_at <= now`（上传后 30 分钟未提交） | — |
| `PAGE_LIMIT_EXCEEDED` | 400 / 异步任务失败 | §4.1.1 上传预筛、§4.2 创建下界预筛，或 §4.1.2 Word OCR 后精确容量超限 | 0 |
| `TASK_NOT_FINISHED` | 409 | §4.1 result 接口在任务未到 `SUCCEEDED` 时被调用 | — |
| `NOT_HEALTH_REPORT` | — | §6.2 文件级裁决 | 0 |
| `UNREADABLE` | — | §6.2 全批不可读；§5.3 OCR 块溢出；§5.6.5 渲染失败 | 0 |
| `IMAGE_TOO_LARGE` | — | 三个来源：① `ExtractionImageCompressor` 两档压缩后仍超单图上限（§5.6.5）；② `OcrImageEncoder` 回退 JPEG 后仍超 `effectiveOcrImageBytes`（§5.6.2.1）；③ Word 内嵌图片原始字节超 `effectiveOcrImageBytes`。三者都抛 `ImageTooLargeException`，**唯一映射点是 `ParseOrchestrator` 的捕获处**。前端提示「图片过大无法处理，请换一张分辨率更低的图片重新上传」——**不要复用 `UNREADABLE` 的「文件无法读取」文案**，用户的图没问题，照那句做也解决不了 | 0 |
| `IDENTITY_MISMATCH` | — | §6.6 同一性校验 | 0 |
| `EXECUTION_TIMEOUT` | — | §4.3 巡检②、§4.4 CAS 撞硬截止 | 1 |
| `SERVER_ERROR` | 500 | Schema 不合法、契约违约、submit 被拒、心跳超时 | 1 |
| `RESULT_EXPIRED` | 404 | §4.1 读结果（**四种情况同码，不泄露差异**） | — |

`reanalyzable = 0` 的失败**必须回上传页重选**——换清晰文件、换对报告、换成同一个人的报告
才有意义，重试同一批文件没用。

### 9.2 日志

按 `AGENTS.md` §6：Lombok `@Slf4j`、中文消息、异常对象作最后一个参数、
禁 `System.out` / `printStackTrace`。

**普通日志内容白名单（红线）：**

```
普通应用 logger 绝不记录：报告原文、证据文本、OCR 文本、姓名、原始过敏或医嘱文本、
                           健康数据、模型完整请求响应、`origin_name` 原始文件名
唯一例外：上述体检隐私内容可进入 HEALTH_REPORT_SENSITIVE 独立 logger 的 DEBUG 事件；
         该 logger 默认 OFF，仅限排障期临时开启，且不得在同一事件携带 taskId / userId
永久禁止：凭证、Authorization 头、图片字节在任何 logger、任何级别都不得记录
taskId / userId 仅用于普通日志关联，也不得进 URL 查询串或分享链接
```

**必须记录的生命周期事件**（只带 ID 与枚举，不带内容）：

```
任务创建 / 领取 CAS / 每次状态迁移 / 降级决策（含 partial_reason）/ 终态
模型调用的开始与结束（只记 batchIndex、耗时、batchStatus，【不记请求响应体】）
清理任务每轮的删除计数
```

### 9.3 生产计数（2026-08-27 全部下线）

**生产环境不存在任何进程级计数。** 不要实现、不要打点、不要配告警。

曾经存在过、现已下线的计数名如下，禁止以任何形式重新出现：

```
statusConflictCount / foodBorneConflictCount / normalAdmitSuspectCount
        —— 2026-08-25 随三张语义词表一起移入离线评测（§11.4）

schemaMissCount / evidenceMissCount / ocrFuzzyMatchCount /
allergenSuspectMissCount / allergenPositiveUncoveredCount / allergenUnknownCount /
adviceOtherCount / sectionRefMissCount / sectionUnknownCount /
highRiskSuppressedCount / glyphLevelPdfCount / residualNonStandardCount /
statusJudgedByModelCount
        —— 2026-08-27 因只写不读整体下线
```

**下线理由**：这 13 个计数每个都有自增点，却**没有任何读取点**——
既不进日志、也没有导出方式，`src/main` 里连一次 getter 调用都没有，只有单测在读。
读不到的计数不提供信息，只增加一处并发状态与一处维护负担。

随之删除的还有单次调用的 `residualNonStandardCount` 传递链：
`TextNormalizationResult` 只保留 `normalizedText`，
`PdfParseResult` / `OcrPageSegmentResult` / `WordParseResult` / `OfdParseResult`
各去掉该字段——进程计数删掉后它就没有任何消费者了。

> **注意区分：`DegradeAccumulator` 不是计数器，不在下线之列。**
> 它记录的 `PAGE_TRUNCATED` / `BATCH_UNREADABLE` / `ALLERGEN_SUSPECT_MISS`
> 直接决定任务的 `partial` 与 `partial_reason`，**影响输出**。
> 同理 §5.3 密度闸的路由判定、§7.4 安全闸的抑制行为都保留，只是不再计数。
> 判据仍然是那一条：**影响输出的留下，只用于观察的下线。**

**将来确有观测需求时，先把导出口径定清楚再实现。** 先加计数、指望以后补导出，
正是这 13 个的来路。契约由 `ProductionCounterContractTest` 断言：
生产类中不得出现任何 `AtomicLong` 计数字段。

### 9.4 常量类

`constants` 包已存在（`AllergenGroups` / `NutritionContents` / `DietRequirementContents` 等），
**结构不动**。落地时注意：

```
真源是 Java 常量类，没有 CSV、没有生成器、没有运行时加载
只取 reviewStatus == REVIEWED 的条目注入提示词与匹配（§0.4）
两条永久不变量，由单测强制（§11.1-R14）：
    avoidIngredients ∩ hiddenFoods = ∅              防重复展示
    两者 matchWord 并集 == 该 key 的全部 matchWord   防「展示了但不匹配」和「匹配了但不展示」
```

**本方案不新增语义词表。** 需要新增的只有：

| 常量类 | 用途 | 归属 |
|---|---|---|
| `AdmittedResultMarks` | §6.5-C 的阳性标记 | 已在内容常量文档中定义 |
| `IngredientAliasWords` | §7.5.3 食材别名 | 新增，工程侧，不需医学审核 |
| `RadicalNormalizeMap` | §5.2 部首映射 | 新增，工程侧 |
| `DisclaimerConstants` / `EmptyStateConstants` | 四个模块的声明与空态文案 | 新增，工程侧 |
| `SystemActor` | `create_by` / `update_by` 取值 | 新增，工程侧 |
| `PromptVersions` | LLM-A / LLM-B 提示词版本，**两个独立常量** | 新增，见 §9.4.1 |

#### 9.4.1 提示词版本与模型版本的真源

`tagHash` 需要 `promptVersion` 与 `modelVersion`（§9.5.1），但现有 `constants` 包
**只有 `TagRuleVersion`**，另两个没有真源。而且 **A 与 B 的提示词各自独立演进**
（版本号以 `constants.PromptVersions` 与 `prompt/versions.tsv` 为准，本节示例不复述具体值——
复述必然过期，R55a/R55b 已经在锁三处一致），
**不能用一个公共版本常量**。

```java
// constants.PromptVersions —— 新增常量类
public final class PromptVersions {
    /** 体检报告抽取提示词版本，必须与 prompt/extraction.md 头部和摘要历史一致 */
    public static final String EXTRACTION = "extraction-x.y.z";
    /** 菜品打标提示词版本，必须与 prompt/dish_tag.md 头部和摘要历史一致 */
    public static final String DISH_TAG = "dishtag-x.y.z";
    private PromptVersions() { }
}
```

**`modelVersion` 不做成常量类，它来自配置。**

```
理由：换模型是【运维动作】，不该要求改代码重新发版；
     而且同一份代码可能在不同环境跑不同模型（灰度、压测）
落地：application.yml 的 llm.model-version-extraction / llm.model-version-dishtag
     ——【这不是「中间件配置」】，AGENTS.md §5 禁的是数据源/Redis/MyBatis 配置类，
       业务参数照常走配置
     LLM-A 直连（§6.2.1），model-version-a 就是我们请求里带的模型标识；
     LLM-B 直连，llm.model-version-dishtag 就是请求里的 model，【没有第二处真源可对不上】

⚠️ 代价必须写明：modelVersion 走配置意味着【改配置就会让全部 tagHash 变化】，
   触发一次全量重打标（§8.4）。因此它必须与发布流程绑定：
   改这个值等同于一次内容变更，要预留凌晨重打窗口，不能随手改。
```

**三个版本的一致性由三条测试锁住，缺一条就有一类漂移拦不住：**

```
R55   【已撤销】原为「Dify DSL 里的版本号与 Java 常量/配置一致」。LLM-B 改直连后
      DSL 不存在，版本号只有 Java 一处真源，本条无对象可断言（§13.2.0）
      LLM-A 直连不产出 DSL，它的版本对齐只由 R55a 保证
R55a  PromptVersions.EXTRACTION / LLM_B 与两份 prompt/*.md 头部声明的版本号逐字一致
R55b  【版本-摘要基线历史】prompt/versions.tsv 的四条断言 —— 见 §9.4.2。
      **A、B 各测一份**：LLM-A 的在批次 5 验收，LLM-B 的在批次 7 验收
R55c  同上机制，对象换成内容常量：constants/tag-rule-versions.tsv
```

#### 9.4.2 光比版本号拦不住「改正文忘 bump」

**R55a 只能证明「版本号写在两处且一致」，证明不了「正文没变过」。**
只改提示词正文时，文件头和常量都不变，R55a 照样通过——
而那恰恰是这套机制声称要防的场景（§8.4：新提示词永远不生效，且静默）。

**只钉一个 `DIGEST` 常量不够。** 那样只能拦住「正文变了而摘要没变」，
拦不住**「正文和摘要一起改、版本号没动」**——后者测试全绿、`tagHash` 也不变，
新提示词照样永远不生效，正是要防的那个场景。

**因此用追加式基线历史，而不是单个常量。**

> **曾考虑把摘要直接放进 `tagHash`（用 `promptDigest` / `tagRuleDigest` 替掉版本段），
> 那样改正文即自动重打、无需任何人记得 bump。设计方已否决**，`tagHash` 保持版本号驱动，
> 因此需要下面这套基线历史来补上强制力。

##### 采用方案：版本-摘要基线历史（**两处同构**）

```
prompt/versions.tsv                追加式，一行一个版本，A 与 B 各占若干行
    extraction-2.3.1  <sha256(extraction.md 正文)>
    a-2.4.0  <…>
    dishtag-2.2.1  <sha256(dish_tag.md 正文)>

constants/tag-rule-versions.tsv    同构，对象换成内容常量
    tag-0.1.0-DRAFT  <sha256(全部内容常量的结构化序列化)>
    tag-1.0.0        d260666e595954828fa99ab8f87c3183d612cae2c42ea8226a6c184e6c6106c7
    tag-1.1.0        33f8773bbd3b9277e5bffce9e9de4db379bde3d4e63da60705dd3a421c40e40f

R55b / R55c 各断言四条：
    ① 末行 version == 对应常量（PromptVersions.EXTRACTION / LLM_B / TagRuleVersion.VALUE）
    ② 末行 digest  == 实测摘要
    ③ 文件内【无重复 version 对应不同 digest】 ← 这一条逼着改正文/改常量必须换版本号
    ④ 文件内【无重复 digest 对应不同 version】 ← 防止空 bump
```

**`TagRuleVersion` 必须走同一套，不能只写「同理」而不建历史文件**——那样内容常量那边
并没有真正强制 bump。`TagRuleVersion` 的 JavaDoc 写着「忘记 bump 的后果是静默的，
建议在 CI 加校验」，`tag-rule-versions.tsv` 就是那个校验。

改正文（或改内容常量）→ ② 红 → 只能追加新行 → ③ 逼着新行必须用新版本号。
**这才是强制中断。** 代价是多两个需要维护的文件，且 `tagHash` 仍靠版本号驱动
——**这是已确认接受的代价**（哈希公式改摘要的方案已被否决）。

**内容常量同理。** `TagRuleVersion` 的 JavaDoc 已经写了「忘记 bump 的后果是静默的，
建议在 CI 加校验」，落地方式相同：

```
R55c  对【全部能改变用户可见结论的内容常量】做结构化摘要，与 TagRuleVersion 的 DIGEST 比对
      覆盖范围 = DishTagInput 的每一个内容字段 + 决定它们取值的东西：
          AllergenGroups（含 displayName —— 它作为 enumDisplayName 进请求）
          AllergenExceptions
          DietRequirementContents.avoidFoodList / avoidDishPatternList / cookingTipList
          每个条目的 reviewStatus            ← 状态从 DRAFT 变 REVIEWED 会改变注入内容，
                                              等价于换了一份常量，必须触发重打
      加上不进模型输入、但直接决定 Java 营养推荐的一组：
          NutritionContents.recommendableFoodList
      【易漏三项】cookingTipList、enumDisplayName、reviewStatus —— 都会改变模型看到的输入
      【口径】摘要不是「进模型的东西」，是「能改结论的东西」；Java 营养内容改了也要 bump
```

> **三条摘要测试的共同点：它们不判断内容对不对，只判断「内容变了而版本没变」。**
> 这是能自动化的部分；内容本身对不对由医务审核负责（§0.4）。

### 9.5 确定性算法

#### 9.5.1 `tagHash` 计算规则

```
tagHash = sha256(
    tagRuleVersion + "|" + promptVersion + "|" + modelVersion + "|" +
    normalize(dishName) + "|" +
    join(",", 食材列表按 normalize(name) 字典序排序后的 "name:weightG")
)
```

```
weightG   统一换算为克，四舍五入到 1 位小数；【未知编码为 null 而不是 0】
name      走 §5.2 的规范化
排序      按 normalize(name) 字典序，【不排序会让外部查询返回顺序一变就触发全量无意义重打】
单位      不统一单位同上
```

**判定语义的每一个输入，都必须被 `tagHash` 覆盖到**——否则输入变了而 hash 没变，
旧标签会被继续复用，而且这个 bug 是静默的。

**但「字段集合完全一致」这个说法不成立**：模型输入里还有 `dishId`、`enumKey`、
枚举展示名、内容常量正文等，它们不在哈希的字面量里。正确的口径是**每个输入必须落进三类之一**：

| 类别 | 例子 | 怎么被覆盖 |
|---|---|---|
| **直接进哈希** | 菜名、食材名、食材重量 | 哈希字面量 |
| **由版本段间接覆盖** | 枚举展示名、内容常量正文（避免食材、避免菜式、烹饪建议） | 改动必须 bump `tagRuleVersion`；提示词改动 bump `promptVersion`；换模型 bump `modelVersion` |
| **纯标识，明确排除** | `dishId`、`enumKey` | 它们**已经在唯一键 `(dishes_id, tag_hash, enum_key)` 里**，再进哈希是重复；且它们不改变"这道菜含不含虾"的判定 |

**R17 断言的是「没有第四类」**：模型输入的每个字段都必须能归到上面三类中的一类，
出现归不进去的字段即失败——那说明有一个会影响判定的输入既没进哈希、也没有版本兜底。

#### 9.5.2 拼音首字母

TinyPinyin，请求时实时计算不落库；非汉字开头统一排在汉字之后。
多音字误排进 P2 人工修正表，不在本版处理。

---

## 10. 占位符与明确不做

### 10.1 三个占位符（`AGENTS.md` §5）

> `AGENTS.md` §5 已同步：三个占位符。`DifyClient` 已随 LLM-B 改直连一并删除（§13.2.0），
> `ExtractionModelClient` 与 `PaddleOcrClient` 明确不在此列（两者都有完整实现，
> 见 §6.2.1.1 与 §5.6.7）。**两处清单必须一致，改一处就改另一处。**

只写接口 + `TODO` 空实现，抛 `UnsupportedOperationException`，**绝不写假数据返回**
——假数据会让上层测试通过而掩盖未实现。

```java
infra.S3FileStorage          对象存储读写删

【infra.ExtractionModelClient 不在占位符之列 —— 它有完整实现，见 §6.2.1.1】
    协议按 OpenAI 兼容写死；换服务商主要改 buildRequestBody / extractContent，
    鉴权头与 endpoint 也可能一起变（§6.2.4-1）
    base-url / model / apiKey 走配置（ExtractionProperties），【是部署参数不是代码问题】
    因此它可以先写完并用 WireMock 跑通全部红线测试（R57~R65），
    只有【真实端到端联调】需要等接入方给出凭据（§6.2.4 的三个 ⛔ 项）
【infra.PaddleOcrClient 不在占位符之列 —— 已有完整实现 PaddleOcrVlClient，见 §5.6.7】
    与 LLM-A 同一个网关、同一套 OpenAI 兼容协议；base-url / model / apiKey 走配置
    （OcrConnectionProperties），容量与接口契约走 OcrProperties，都是部署参数
infra.CurrentUserProvider    获取当前 userId 与 companyId；两者都来自可信登录上下文
infra.DishQueryService       仅供凌晨任务按企业游标分页查询当日在架菜品；每批返回最后一条
                             dishes_id 对应的 lastDishesId，下一批原样传回；并按当前批ID批量查询食材
```

**不生成任何中间件或数据源配置类**：不写 `DataSourceConfig` / `RedisConfig` /
`MybatisPlusConfig` / `RedisTemplate` Bean / 连接池配置 / `application.yml` 的中间件段。
（§4.2 的两个业务线程池 Bean 不在此列，它们是业务组件。）

### 10.2 明确不做

```
用户自填过敏原
由指标异常推导饮食建议
「体检报告不完整」的失败判定
严重程度分级 / 风险排序
历史报告回看（结果 TTL 2 小时）
原图跳转与单页预览
OCR bounding box 落库          ← 与 §3.1「无 segment 表」和数据生命周期直接冲突，
                                 前提是先做隐私评估并重定数据留存策略，不是排期问题
内容管理后台 / 建议内容人工编辑
报告未写饮食建议时的通用建议兜底
菜品标签的 active 指针与 all 全集 Key（正式集合由企业当天 staging Key 原子替换）
消息队列、outbox、Dispatcher   ← 设计方案 §2.3.3 已删除，改本机线程池（§4.2）
```

---

## 11. 测试

`AGENTS.md` §7 的通用要求（每个任务增删单测、先跑最小集再跑全量、保持 Java 8 可编译、
**必须包含负例与 fail-safe 用例**、集成优先写确定性测试、不得删测试/放宽断言/吞异常/
写假数据/改 TODO 规避问题）此处不重复。

### 11.1 必测回归清单

> **`AGENTS.md` §7 指名以本节为准：本节是唯一的【可执行回归清单】。**
> 但它**不是行为真源**——行为真源是设计方案（§0.1）。
> 本节必须与设计方案保持一致；**两者冲突时以设计方案为准，回改本节**。
>
> 新增业务规则必须在此登记，否则它就是一条没人复核的不变量。
> 每条给出「构造什么输入 → 断言什么」，可直接写成用例。

#### 分层边界（违反即架构错误）

| # | 用例 | 断言 |
|---|---|---|
| **R1** | ArchUnit 扫描生产源码 | 不存在对 `ConclusionLabelWords` / `NormalStatementWords` / `AllergenSectionWords` 的任何引用 |
| **R2** | 构造模型 `status=NORMAL` 而 `conclusionText="↑偏高"` | 输出 `status` 仍为 `NORMAL`；**不存在任何纠正逻辑** |
| **R3** | 构造 `includeInHealthProblems=true` 而原文含「未见异常」 | 该条目**进入**模块二；Java 不改写 |
| **R4** | 构造正式枚举 `SHRIMP_CRAB` 而模型给 `isFoodBorne=false` | 以 `AllergenGroups` 查表为准（`true`）；模型值被丢弃、不计数 |
| **R5** | 构造 `enumKey=OTHER` + `isFoodBorne=true` + `rawName="艾蒿"` | 采信模型；Java 不校验、不改写、不告警 |
| **R6** | ArchUnit 扫描 `parse` 包 | 不存在 `PDFTextStripper` 引用；不存在按坐标聚类的方法 |

#### 安全红线

| # | 用例 | 断言 |
|---|---|---|
| **R7** | 过敏章节名命中而 `allergens` 为空 | `partial=true`、`partial_reason=ALLERGEN_SUSPECT_MISS`、模块四不输出、模块一二三照常 |
| **R8** | `D \ A` 非空（有数据行无对应条目） | 同 R7 |
| **R9** | 某 segment 同时含「牛奶」与「阳性(+)」但不在 `A` 中 | 同 R7 |
| **R10** | **同上但名称与结果分属两个 segment** | **不触发**——这是 §6.5-C 的已知盲区，用例存在是为了锁住行为不被「优化」成坐标配对 |
| **R11** | `allergens` 条目来源校验失败 | 该条丢弃 **且**触发 `ALLERGEN_SUSPECT_MISS` |
| **R12** | `resultStatus` 分别为 `NEGATIVE` / `UNKNOWN` | 都不进链路 |
| **R13** | 某企业分页完成数少于目标数，或任一标签维度覆盖不完整 | 该企业当天 33 个正式 SET 全部不发布；在线不回源、不读昨天 |
| **R14** | 遍历 `AllergenGroups` 全部组 | `avoidIngredients ∩ hiddenFoods = ∅`；两者 `matchWord` 并集 == 该 key 全部 `matchWord` |
| **R15** | 高危表述「低蛋白饮食」映射到 `PROTEIN` | `structuredOutputSuppressed=true`、走 `OTHER` 路径、**`enumKey` 原值保留未被覆写** |
| **R15a** | `applicability=CURRENT_PATIENT` + `structuredSafety=NORMAL` 的常规建议 | 正常进入结构化链路 |
| **R15b** | 邻句含「儿童」但 `adviceQuote` 只摘了「低脂、低糖饮食」 | **不得抑制**——F3 的误杀现场，词表已不含人群裸词且只扫 `adviceQuote` |
| **R15c** | `applicability=GENERAL_INFORMATION`（科普段落里的饮食表述） | 抑制；靠适用范围而不是词表 |
| **R15d** | `applicability=CURRENT_PATIENT` + `structuredSafety=SPECIAL_POPULATION` | 抑制；建议确实给本人但涉特殊人群 |
| **R15e** | 任一枚举为 `UNCERTAIN` 或缺失 | 抑制（fail-closed） |
| **R15f** | 模型判 `NORMAL` 但 `adviceQuote` 含「优质低蛋白」 | 抑制；词表兜底不可被模型推翻 |
| **R15g** | `adviceQuote` 缺失或回切不过 | 该条建议**整条丢弃**，不进任何模块 |
| **R15h** | 原文「建议低蛋白、低脂饮食」，模型摘成「低脂饮食」并判 `NORMAL` | **抑制**——`adviceQuote` 与证据段原文两处都扫，任一命中即抑制，摘句绕不过方向性限制 |
| **R15i** | 证据段被 OCR 漏识一字（「低蛋日」），而 `adviceQuote` 写着「低蛋白」 | **抑制**——两个入参各自独立命中 |
| **R15j** | F3 原文整块（含「儿童除外」）作为证据段，建议是「低脂、低糖饮食」 | **不抑制**——扫原文不得变成新的误杀来源，词表里已无人群裸词 |
| **R16** | 过敏维度 `REJECT` 的菜同时有营养 `RECOMMEND` | 只下发过敏标签，**无任何正面标签**（灰色附注也不行） |
| **R16a** | `LOW_PURINE` 离线安全判定为 `NEUTRAL`、主料命中低嘌呤白名单 | 凌晨把复合成员写入 `diet:recommend:LOW_PURINE`；在线理由只返回报告原文，不含命中主料 |
| **R16b** | 同一维度离线判 `REJECT` / `UNKNOWN`，主料仍命中白名单 | `REJECT` 只进饮食不推荐 SET；`UNKNOWN` 不得进入正向 SET |
| **R16c** | 遍历 9 个饮食注意维度的离线输出 | 9 个维度都允许 `REJECT`；只有 `LOW_PURINE`、`HIGH_FIBER` 可由凌晨 `DietPositiveMatcher` 产出 `RECOMMEND`，且同一道菜不得同时进入正反集合；其余 7 个维度不存在 recommend Key |
| **R16c1** | `LOW_FAT` 安全判定为 `NEUTRAL`，菜品主料命中低脂推荐食材 | 不写 `diet:recommend:LOW_FAT`，在线不得出现“低脂”推荐标签；执行时点改为离线不能扩大需求正向范围 |
| **R16d** | 报告同时给出「高纤维饮食」与「补充膳食纤维」，菜品同时命中两个推荐 SET | 正面标签按枚举文案去重；推荐理由直接取并去重报告原文，不读取食材 |
| **R16e** | LLM-B 在任一维度返回 `recommendDishIds` 或 `RECOMMEND` | Schema 或契约拒绝整批，不写库、不写 staging SET；正向结果只能由 Java 匹配器产生 |
| **R16f** | 用户只有虾蟹过敏，Redis 中有 20 道虾蟹 reject 菜 | 推荐列表为空；不推荐集合完整参与排序后只返回前 3 道及全部过敏标签 |
| **R16g** | 同一复合成员同时位于营养 recommend 与过敏 reject SET | 差集后只进不推荐列表，返回中没有任何推荐标签或推荐理由 |
| **R16h** | 一个菜命中两个推荐维度，其报告原文分别为「建议补铁」「建议增加蛋白质摄入」 | 只返回 `dishName`、两个推荐标签及两条原文理由；不得返回 dishId、食材、图片、价格或分类 |
| **R16i** | 同企业存在两道同名但 dishId 不同的菜 | 复合成员保持两道独立；集合运算不合并，最终 DTO 可出现相同名称 |
| **R16j** | 同一复合成员同时位于营养 recommend 与饮食注意 reject SET | 差集后只进不推荐列表，只返回全部命中的饮食不推荐标签；不得返回营养推荐标签或推荐理由 |

#### 契约与确定性

| # | 用例 | 断言 |
|---|---|---|
| **R17** | 枚举模型输入的每一个字段 | 每个字段必须能归入 §9.5.1 的三类之一：**直接进哈希** / **由 `tagRuleVersion`·`promptVersion`·`modelVersion` 覆盖** / **纯标识明确排除**（`dishId`、`enumKey`）。出现归不进去的字段即失败——那说明有一个会影响判定的输入既没进哈希也没有版本兜底 |
| **R18** | 同一批菜品打乱食材返回顺序 | `tagHash` 不变（排序 + 单位统一 + 名称规范化生效） |
| **R18a** | 造 `last_seen_date` 为 31 天前与 29 天前的行，跑清理 | 只删前者；且清理必须在打标任务**之后**执行（先删后打会误删当天要用的行） |
| **R18b** | 直接调 `tagService.run(d)` 与 `cleanupService.run(d)` 并传入固定日期 `d` | 企业/菜品分页、Redis `{companyId:bizDate}` Key、`last_seen_date` 与清理比较全部使用入参；除调度入口外不出现 `LocalDate.now()` / `CURRENT_DATE` / `now()` |
| **R18c** | xxl-job Handler 注册数 | `dish` 包内只有**一个** `@XxlJob` Handler，打标与清理是它的两步（防止有人拆成两个 Handler 后 `bizDate` 又分叉） |
| **R18d** | 两个企业存在相同 dishId、菜名和标签 | Redis Key 必须不同；企业 A 用户只能得到 A 集合结果，任何 B 成员进入即测试失败 |
| **R18e** | 一个企业有 1201 道按 `dishes_id` 递增的在架菜，查询页容量 500 | 首批输入 `lastDishesId=null`；三批分别返回本批最后一条的 `dishes_id`，下一批原样使用上批返回值；菜品主表恰好查询 3 页，游标严格前进、无重复无遗漏；每页只执行一次食材批量查询，无逐菜查询 |
| **R18f** | 菜品一页中每道菜有多条食材 | 分页基于菜品主表而非 JOIN 行，页大小按菜品数计算；食材行数不改变页边界 |
| **R18g** | 第 2 批返回其他企业菜品、`lastDishesId` 不等于本批最后一条 `dishes_id`、游标未前进或元素为 `null` | 构建立即失败，该企业正式 SET 不发布，其他企业已完成快照不受影响 |
| **R18h** | 33 个 staging SET 中有空集合和非空集合 | Lua 原子发布后空集合对应正式 Key 不存在、非空集合全部替换成功；并发在线读取只能看到发布前或发布后的完整企业快照 |
| **R18i** | 企业分页前 `COUNT=1201`、处理 1201 道、分页后 `COUNT=1202` | 判定菜单构建期间发生漂移，该企业当天不发布 |
| **R18j** | 一个企业恰有 1000 道在架菜，查询批容量 500 | 前两批各返回最后一条 `dishes_id`；第三批返回空 `dishList` 与 `lastDishesId=null` 后结束，不得重查上一批或循环不止 |
| **R19** | `blockRefs` 传字符串 / 越界 / 重复 / 33 条 | Schema 或展开层拒绝，行为符合 §6.3 |
| **R20** | `sections[i].sectionIndex != i` | 整批 `FAILED / SERVER_ERROR` |
| **R21** | 条目 `sectionIndex` 指向不存在的章节 | 该条目整条丢弃、**其余条目不受影响** |
| **R21a** | 构造「有数值无结论**且无参考值**」的指标行 | **不出现在任何模块**；Java 不得为对齐总览数字把它补回来（§6.3.1） |
| **R21b** | 「有数值无结论**但有参考值**」且结果落在范围内 | 进模块一，`conclusionBasis=REFERENCE_RANGE_IN_RANGE`、`status=NORMAL`、`conclusionText=null`、卡片 `conclusionGenerated=true` |
| **R21c** | 同上但结果**超出**参考范围 | **不展示**；不得改判为 `HIGH`/`LOW`——报告没写的结论系统不生成 |
| **R21d** | 报告印 `4.0~10.0`，模型只报下界 `4.0`、上界给 `null`，结果 12.5 | **不展示**——省略一侧边界等于那一侧不设限，任何大值都会被判成正常 |
| **R21e** | 报告印 `14.0~20.0`，模型报下界 `4.0`（恰好是原文子串）、上界 `20.0` | **不展示**——子串核验拦不住它，整组区间必须与原文解析结果一致 |
| **R21f** | 报告印 `<3.0`，模型报成闭区间，结果正好 `3.0` | **不展示**——开闭标志参与核验；如实报开区间且结果在范围内时照常展示 |
| **R21g** | `refRange` 是 `阴性`、`详见报告` 等解析不出区间的写法 | **不展示**（fail-closed），不得对着猜出来的范围宣布正常 |
| **R21h** | `refRange`、`title` 等短字段给成 `""` 或全空白 | Schema 拦下并**剔除该条目**（§4.4-①，2026-09-02 前为整批作废）；Java 侧来源校验对空白字段一律返回 false——空串是任意原文的子串。两条都保证它进不了展示 |
| **R21i** | 模型给的上下界在 `refRange` 原文里找不到 | 整条丢弃（防凭空报宽区间） |
| **R21j** | 参考值有多套人群范围、单位不一致或非数值 | 模型给 `rangeComparison=null`，该指标不展示 |
| **R21k** | 结果恰好等于开/闭边界；`1.10` 对上界 `1.1` | 按开闭标志判定；标度差异必须判等（`compareTo` 而非 `equals`） |
| **R21l** | 定性结果「阴性」对参考值「阴性」 | 进模块一，`conclusionBasis=REFERENCE_VALUE_MATCH`、卡片文案「符合报告参考值」 |
| **R21m** | 定性结果「阴性」对参考值「阴性或弱」（尿胆原真实场景） | 展开为 `["NEGATIVE","WEAK_POSITIVE"]`，结果在集合内 → 展示 |
| **R21n** | 定性结果「阳性」对参考值「弱阳性」 | **不展示**；不得因「阳性」是「弱阳性」的子串而放行 |
| **R21o** | 定性结果 `NEGATIVE` 对参考值 `NOT_DETECTED` | **不展示**；Java 不得把两者当同义词 |
| **R21p** | 归一化取值落在四态枚举之外（如 `NEG`） | Schema 拒绝并**剔除该条指标**（§4.4-①，2026-09-02 前为整批契约失败）；枚举是契约的一部分，写错的那一条必须消失，但不该拖垮同批其余几十条。**不是悄悄丢**：翻转 `SCHEMA_ITEM_DROPPED` 并记带关键字与路径的 WARN |
| **R22** | LLM-B 返回缺一个 `dishId` / 多一个 / 列表有交集 / 同列表内重复 | 按 §8.2 修复:缺失与跨列表相交归入 `UNKNOWN`、同列表内重复只去重不改判、非本批 `dishId` 丢弃;**修复后覆盖必须精确成立**。修复量超 20%(至少允许 1 道)或违规定位不到某道菜 → 整批作废,不写库、不重试(2026-09-02 前为一律整批作废) |
| **R23** | 用 `schema/*.json` 校验文档 §4.2 与 §8.2 的示例 | 全部 PASS；两个 Schema 自身通过 `check_schema` |
| **R24** | `patient.name` 非空但证据数组为空 | Schema 拒绝 |
| **R25** | `patient.name` 来源校验失败 | 该字段降 `null` 且**不参与同一性判断**（不得因此 `IDENTITY_MISMATCH`） |

#### 排序与展示

| # | 用例 | 断言 |
|---|---|---|
| **R26** | 同页两个章节，各自第 1 条 `sourceOrder` 都是 0 | 顺序由 `groupOrder` 分开，**不乱序** |
| **R27** | 同一章节跨两批（前批 `CURRENT`、后批 `CONTINUATION`） | 合并为一组；后批**没有**返回相同 `sectionSegmentId` |
| **R28** | 文件第一批就报 `CONTINUATION` | 按 `UNKNOWN` 处理、**不向前跨文件继承** |
| **R29** | 一个条目跨页引用（`blockRefs` 含 p2 和 p3） | `item.page = 2`（取 min） |
| **R30** | 两份报告都有「血脂检查」 | 展示为**两个独立分组**，不合并 |
| **R31** | `problemName = null` 的指标 | `displayName = "甘油三酯 ↑"`（两段原文拼接），**不出现「偏高」**；`displayNameGenerated=true` |
| **R32** | `itemNo = null` 的建议 | 来源标注**不写条号**，不拿 `sourceOrder` 顶替 |
| **R33** | 推荐/不推荐列表各有 5 道候选 | 先全维度裁决再截断到 3；**不是先取 3 再判过敏** |
| **R33a** | 任务处于 `QUEUED` | task 接口返回 `stage=UPLOADING`、`progress=0`，**不是 404 也不是 PARSING** |
| **R33b** | `stage` 全部取值 | 只有 `UPLOADING` / `PARSING` / `ASSEMBLING` 三个；`EXTRACTING` **不作为 stage 出现** |

#### 任务与状态机

| # | 用例 | 断言 |
|---|---|---|
| **R34** | 事务提交后 `submit` 抛 `RejectedExecutionException` | 任务 CAS 为 `FAILED/SERVER_ERROR`，接口返回 `SERVER_ERROR` |
| **R35** | 任务已被删除后工作线程执行领取 CAS | 影响 0 行，**不做任何事、不写终态** |
| **R36** | `deadline_at` 已过但巡检未跑，工作线程完成组装 | 成功 CAS 影响 0 行 → 删 Redis 结果 → `FAILED/EXECUTION_TIMEOUT` |
| **R37** | 写 Redis 成功后、CAS 前进程崩溃 | 结果**不可见**（接口先查 MySQL）；任务被巡检判 `FAILED` |
| **R38** | 任务成功后第 31 分钟读结果 | 能读到（`expire_at` 已顺延为 2h） |
| **R39** | 同一批 `fileIds` 并发调两次 `analyze` | 一个成功，另一个 `FILE_ALREADY_BOUND` 且**返回已绑定的 taskId** |
| **R40** | 任务不存在 / 归属不符 / 已删除 / 已过期 | 四种返回**完全相同**的 `404 RESULT_EXPIRED` |
| **R40a** | 任务未完成时调 result 接口 | `409 TASK_NOT_FINISHED`，**不读 Redis** |
| **R40b** | 绑定时文件已过期 | `FILE_EXPIRED`，事务回滚 |
| **R41** | 巡检对 `heartbeat` 超时与 `deadline` 超时 | `fail_code` 分别为 `SERVER_ERROR` 与 `EXECUTION_TIMEOUT`，**不混用** |

#### 解析与降级

| # | 用例 | 断言 |
|---|---|---|
| **R42** | PDF 每页 segment 数 > 400 | 该文件整体改走 OCR，`textSource=OCR` |
| **R43** | OCR 某页识别块 > 400 | **整任务 `FAILED/UNREADABLE`**，不做局部截断，不新增 `partial_reason` |
| **R43a** | 精确 `totalPages` = 45（三档中的 31~60） | 只处理前 30 等效页；`processedPages=30`、`totalPages=45`；`PAGE_TRUNCATED`；模块三四不输出 |
| **R43b** | 不含 Word，上传文件的 `precheck_pages` 累计为 61 | analyze 直接拒绝 `PAGE_LIMIT_EXCEEDED`，**不建任务行、不绑文件** |
| **R43b1** | 分别上传 PDF / 图片 / 原生 81 个 segment 且无图片的 DOCX | `precheck_pages` 分别为真实页数 / 1 / 3；Word 值明确是下界，不冒充精确页数 |
| **R43b2** | Word 原生 segment 为 1201，或 ≥300×300px 的内嵌图片为 31 张 | 上传直接 `PAGE_LIMIT_EXCEEDED`，不写 file 行、不存对象文件 |
| **R43b3** | Word 上传预筛未超限，但 OCR 后 `exactSegmentCount=1201` | 任务 `FAILED/PAGE_LIMIT_EXCEEDED`、`reanalyzable=false`，**LLM-A 调用次数为 0** |
| **R43b4** | 含 Word 的任务预检下界 ≤60，OCR 后精确累计为 61 | 任务 `FAILED/PAGE_LIMIT_EXCEEDED`、`reanalyzable=false`，**LLM-A 调用次数为 0** |
| **R43b5** | Word OCR 服务调用失败，而不是容量超限 | `FAILED/SERVER_ERROR`、`reanalyzable=true`，不得误报 `PAGE_LIMIT_EXCEEDED` |
| **R43b6** | **上传预筛**：Word 原生 segment 恰好 1200、合规图片恰好 30 张 | **通过**，正常落 file 行——上限是「≤」不是「<」。**注意这只说明文件能创建，不代表 OCR 后一定通过** |
| **R43b6a** | **工作线程**：Word 的 `exactSegmentCount` 恰好 1200、图片恰好 30 张 | **通过**，继续执行；1201 才拒（R43b3） |
| **R43b6b** | Word 原生 1200 segment + 10 张图片，OCR 后每图产出若干块 | 上传**通过**、工作线程**拒绝**（`exactSegmentCount > 1200`）。断言两个阶段的判定**各自独立**，上传通过不隐含运行时通过 |
| **R43b7** | **上传预筛**：Word 恰好 30 张合规图片、正文为空 | **通过**，`precheck_pages = 0`；不得因正文空判 `FILE_UNREADABLE`（§5.1） |
| **R43b10** | 上述文件 OCR 后**一个文字块都没出来** | 整任务 `FAILED / UNREADABLE`，**LLM-A 调用次数为 0**；不是 `SUCCEEDED` 带四个空模块（§6.2） |
| **R43b11** | 纯图片上传 / 扫描版 PDF，OCR **成功但结果为空** | 同 R43b10。而 OCR **调用失败**时是 `SERVER_ERROR`，两者不得混淆 |
| **R43b8** | 多文件 `precheck_pages` 累计**恰好 60** | analyze **通过**，正常建任务；61 才拒（R43b） |
| **R43b9** | 单文件恰好 30 等效页 | 全部处理，`partial = false`；31 才触发 `PAGE_TRUNCATED`（R43a） |
| **R43c** | PDF 20 页在前、Word 800 个有序 segment 在后 | 精确总量40页；保留 PDF 20 页和 Word 前400个segment，`PAGE_TRUNCATED`；不是整份丢弃 Word |
| **R43d** | `PAGE_TRUNCATED` 与 `ALLERGEN_SUSPECT_MISS` **同时命中** | `suppressDietAdvice=true`（来自前者）；`partialReason=PAGE_TRUNCATED`（严重度更高）；**模块三不得因后者被重新输出** |
| **R43e** | 三个降级原因全部命中 | 三个布尔全 `true`；`partialReason=PAGE_TRUNCATED`；模块三四都不输出 |
| **R44** | 一批 `UNREADABLE`、其余正常 | 该批丢弃，`partial_reason=BATCH_UNREADABLE`，模块三四不输出 |
| **R45** | 全部批次 `NO_REPORT_FEATURE` | `FAILED/NOT_HEALTH_REPORT`（**不是 `UNREADABLE`**） |
| **R46** | OCR 文本把 `2.8` 认成 `2.6`，模型给 `2.8` | 放宽档通过（编辑距离 1）；`NATIVE` 档同样输入应拒绝 |
| **R47** | DOCX 解压炸弹 | 流式计数中断，不信 `getSize()` |

#### 数据生命周期与日志

| # | 用例 | 断言 |
|---|---|---|
| **R48** | 任务成功后检查 Redis `result:{taskId}` | **不含**姓名、性别、完整 OCR 文本；含四模块展示片段 |
| **R49** | 全流程日志捕获 | 不含报告原文、姓名、OCR 文本、健康数据；`taskId` 不与上述内容同事件 |
| **R50** | `SUCCEEDED` 后跑清理 | 原文件与 file 行立即删；task 行保留至顺延后的 `expire_at` |
| **R51** | `FAILED` 且 `reanalyzable=1` 后跑清理 | 原文件**保留**至 `expire_at`（否则「重新解析」形同虚设） |
| **R52** | 任意实体的 insert / update | SQL 中**不出现** `create_time` / `update_time` |
| **R53** | 一批指标携带已下线的 `statusJudgedByModel` 字段 | `indicators` 条目是 `additionalProperties:false`，**剔除该条**（§4.4-①）。Java 仍**不做任何 status 校验**，`status` 的四个取值都不影响输出（字段随 §4.4-③ 的 13 个计数于 2026-08-27 下线，2026-09-01 从 Schema 与 DTO 删除） |

#### LLM-A 直连红线（§6.2.1）

| # | 用例 | 断言 |
|---|---|---|
| **R57** | ArchUnit 扫 `llm.extraction`、`llm.dishtag` 与 `infra` 包 | 两条模型链路各自只依赖自己的客户端接口：`llm.extraction` 不依赖 `DishTagModelClient`，`llm.dishtag` 不依赖 `ExtractionModelClient`。**`DifyClient` 已删除**，原「非 LLM-B 不得依赖 DifyClient」一条随之撤销（§13.2.0） |
| **R58** | 捕获一次完整的 LLM-A 请求体 | **不含** `taskId` / `userId` / `origin_name` / `segmentId`；只含 §6.2 定义的字段 |
| **R59** | 全流程监控文件系统与对象存储调用 | 图像**只从 `byte[]` 内联**；**不创建临时文件、不调用 `S3FileStorage`** |
| **R60** | 构造页码 2、5、9 的一批 | 消息序列严格「文本→图→文本→图」；**每对文本与图来自同一个 `BatchPage`**；无漏图、无重复页 |
| **R61** | 打乱输入页序 / 缺一张图 / 重复一页 | 三种都**在组装前失败**，不得静默发出错配的请求（§6.2.1） |
| **R62** | 模型返回 429 / 500 / 读超时 | 各自**调用次数恰好为 1**（零重试，§6.1）；映射成对应错误码 |
| **R63** | 上述失败场景的日志 | **不含请求体、响应体、模型响应正文**；`RestClientResponseException` **不得被直接记录**，只记状态码与耗时（§6.2.4） |
| **R64** | 应用启动后检查 HTTP 客户端配置 | Apache/OkHttp/JDK 的 **wire logging 与 debug 日志为关闭**；`RestTemplate` 未挂任何打印 body 的拦截器 |
| **R65** | 静态检查 `infra` 包 | **禁止** `HttpEntity<String>`、`ObjectMapper#writeValueAsString`、以及把整请求体拼进 `StringBuilder`/`String` 的写法（ArchUnit + 字节码扫描）。**不检查"有没有 String"**——`buildRequestBody` 本来就返回 `byte[]`，而逐图 base64 的临时 `String` 是合法的 |
| **R65p** | **性能测试，不进普通单测**（单独 profile / tag） | 固定 JDK 版本、固定 `-Xmx`、固定 8×800KB 样本，用 `ThreadMXBean#getThreadAllocatedBytes` 记录分配量基线并存档。**回归时与存档基线比对，超 30% 报警而非失败**——分配量随 JDK 小版本波动，做成硬断言会变成噪音 |
| **R65a** | 构造超过 `maxRequestBodyBytes` 的一批 | 抛 `RequestTooLargeException`，WireMock **收不到任何请求**；且**在写入过程中就抛**，不是等整个请求体生成完再判（断言 `CappedByteArrayOutputStream.size() <= maxBytes` 始终成立） |
| **R65b** | WireMock 返回超大响应体（**200 与 500 各一次**） | 两次都不把响应完整读进内存：200 走 `BoundedResponseExtractor`；**500 走 `StatusOnlyErrorHandler`，错误处理器不读取也不缓存正文**（框架关闭响应时仍可能对流做清理，这不算读取）——默认的 `DefaultResponseErrorHandler` 会把 body 读满并塞进异常，绕过上限。异常消息里**只有数字，无正文** |
| **R66** | 一张 3000×4000 的页面图走 `ExtractionImageCompressor` | 输出长边 = 2000px、JPEG、体积 ≤ 1MB；**全程不落盘**（监控文件系统调用） |
| **R66a** | 一张压缩后仍 > 1MB 的高噪图 | 自动回退档 2（长边 1600px、quality 0.80）；再超限抛 `ImageTooLargeException` → 任务 **`FAILED / IMAGE_TOO_LARGE`**、`reanalyzable = 0`。**断言既不是 `UNREADABLE` 也不是 `SERVER_ERROR`**，前端文案走「图片过大」那条 |
| **R66f** | 同一份内容分别走 PDF 原生文本层与 OCR 两条路径 | 两边 `bbox` 都是**原始渲染图的左上原点像素坐标**、指向图上同一位置；**OCR 路径不得再被翻一次 Y 轴**（§5.6.6） |
| **R66b** | 上传一张 8000 万像素的 JPG | ① 请求体内的图 ≤ 1MB，不得直传原图；② Spy/Fake `ImageReader` 断言**读过完整尺寸后调用了 `setSourceSubsampling`**；③ 断言实际解出的 `BufferedImage` 宽×高 **≤ 受控上限**；④ ArchUnit：上传图片路径**禁止直接调 `ImageIO.read`**。**不用"峰值分配量"断言**——`ThreadMXBean` 只能看累计分配，证明不了单个对象大小 |
| **R66g** | 8000 万像素样本，**独立子进程 + 较小 `-Xmx`**（如 512m） | 不 OOM 且正常产出压缩图。**不进普通单测**，与 R65p 同属资源/性能测试 |
| **R66h** | 上传一张 **EXIF `Orientation = 6`** 的 JPG（宽高互换）| **OCR 服务联调测试**，不进本地单测——它验的是真实 OCR 的 EXIF 行为与我们假设是否一致。断言：在原图已知位置放一段可识别文字，校验最终 `bbox` 框住它 |
| **R66h1** | **8 个 `Orientation` 值各一组**已知坐标，跑坐标变换函数 | **纯本地确定性单测**，不依赖 OCR。每个值对应一个固定的旋转/镜像矩阵，逐个校验变换后坐标；`Orientation` = 5~8 时**断言宽高互换**。这条与 R66h 是两回事：它验我们的数学，R66h 验对方的行为 |
| **R66j** | 构造一张恰好 ≤ `effectiveOcrImageBytes`、但组装后请求体超 `ocr.maxRequestBodyBytes` 的图（把协议开销配得偏小） | `PaddleOcrClient` **发送前**抛 `ImageTooLargeException`，Mock OCR **收不到请求**；证明两层兜底都生效（§5.6.2.1） |
| **R66k** | 启动时把 `ocr.maxRequestBodyBytes` 配成 0 / 负数 / 小于协议开销 | **启动直接失败**，不得跑到第一次调用才报错 |
| **R66i** | PDF 页走 `OcrImageEncoder` | 输出 PNG 无损（或超限时 JPEG q0.95）；**断言它不是 `ExtractionImageCompressor` 的产物**（质量参数与尺寸都不同，§5.6.2.1） |
| **R66c** | 档 2 回退后检查 `bbox` | `bbox` 像素坐标按**实际输出尺寸**换算，不是按 2000 硬算；断言坐标与图上位置一致（§5.6.6） |
| **R66d** | Word 含内嵌图片 | 图片**只进 OCR**，`BatchPage.jpegBytes` 恒为 `null`、`imageRequired` 恒为 `false`（§5.6.2） |
| **R66e** | PDF 某页渲染失败（构造损坏页） | 整任务 `FAILED / UNREADABLE`；**不抛 `IllegalStateException`**，即不落到 `assertPageListValid`（§5.6.5） |
| **R65c** | WireMock 返回 200 + 畸形 JSON，正文含「甘油三酯 2.8 阳性」等敏感串 | 抛 `LlmCallException`；**捕获全部日志断言不含该敏感串**——Jackson 解析异常消息会带出错位置附近的原文片段，`extractContent` 必须就地脱敏 |

#### 交付物一致性（§13）

| # | 用例 | 断言 |
|---|---|---|
| **R54** | 解析 `sql/schema.sql` 与本文 §3.1 的 DDL 代码块 | 列级八项（列名/类型/可空/DEFAULT/ON UPDATE/CHARACTER SET/COLLATE/COMMENT）+ 表级四项（ENGINE/CHARSET/COLLATE/表 COMMENT）+ 索引三要素（名称/列序列/顺序）逐一相等；`.sql` 里**不出现** `CONSTRAINT` / `FOREIGN KEY` / `CHECK` / `TRIGGER` |
| **R54a** | 空库跑 `schema.sql` vs 空库跑「初版 schema + 全部 alter」 | 两条路径得到的结构**完全一致**（同样比 R54 的十五项）。没有迁移工具，这是唯一能保证「新建库」与「老库升级」不分叉的手段 |
| ~~**R55**~~ | **已撤销**（2026-08-27） | 原为「解析 LLM-B 的 Dify DSL，断言其中的版本号与 Java 常量/配置一致」。改直连后 DSL 不存在，`modelVersion` 只剩 `llm.model-version-dishtag` 一处真源，**没有第二处可对不上**——本条防的漂移已被结构性消除（§13.2.0）。版本号与提示词的对齐由 R55a / R55b 继续保证 |
| **R55a** | `PromptVersions` 常量 vs 两份 `prompt/*.md` 头部声明 | `EXTRACTION` 与 `prompt/extraction.md` 头部逐字一致；`DISH_TAG` 与 `prompt/dish_tag.md` 同理。**两者分别断言，不得用同一个公共版本常量**。三条链路都直连之后，版本号仍是排障时分辨「结果出自哪版提示词」的唯一凭据 |
| **R55b** | 解析 `prompt/versions.tsv`（§9.4.2 的采用方案） | 四条全过：① 末行 version == `PromptVersions.EXTRACTION` / `LLM_B`；② 末行 digest == 实测正文摘要；③ **无重复 version 对应不同 digest**；④ 无重复 digest 对应不同 version。**③ 才是真正拦住「改正文忘 bump」的那条**——只比 `DIGEST` 常量拦不住「正文和 DIGEST 一起改、版本不变」 |
| **R55c** | 解析 `constants/tag-rule-versions.tsv`，规则与 R55b 完全相同 | 摘要覆盖**全部进入模型输入的内容常量**：`AllergenGroups`（含 `displayName`）、`AllergenExceptions`、`NutritionContents.recommendableFoodList`、`DietRequirementContents` 的三个列表、以及每个条目的 `reviewStatus`。**同样靠「无重复 version 对应不同 digest」强制 bump `TagRuleVersion`**，只比 DIGEST 常量拦不住（§9.4.2） |
| ~~**R56**~~ | **已撤销**（2026-08-27） | 原为「解析 DSL，断言零重试与三节点拓扑」。改直连后零重试由 `DishTagModelClient` 的实现本身保证（与 LLM-A、OCR 同一套写法），不再需要去解析一份外部文件来证明它 |
| **R56b** | **新增**：LLM-B 响应剥离思考段 | `<think>…</think>` 正常剥离；**思考段内含示例 JSON 时不得被当成结果**；只有 `</think>` / 有 `<think>` 无 `</think>` / `finish_reason == "length"` 四种形态各自整批作废且不写库（§13.2.3）。**这条是本次改动引入的唯一新静默错误面，必须有能真失败的断言** |

### 11.2 契约测试

```
schema/extraction_output.schema.json    与文档 §6 各节、prompt/extraction.md 三方一致
schema/dish_tag_output.schema.json    与文档 §8.2、prompt/dish_tag.md 三方一致
```

每次改契约必须同时跑：Schema 自身 `check_schema` + 文档示例校验 + 提示词字段名比对（R23）。
**三方任一处漏改都算契约破坏。**

### 11.3 负例与 fail-safe 用例（`AGENTS.md` §7-4 的落地）

上表中 R2~R6、R10、R12、R19~R22、R24、R25、R34~R37、R40、R43、R45 全部是负例或 fail-safe，
**不得只写 happy path**。特别是 **R10**：它断言的是「**不该**触发」，用来锁住已知盲区
不被后来者用坐标配对「优化」掉。

### 11.4 离线评测集（不在生产链路，但发版前必跑）

三处已下线的在线检查移到这里，评测集是它们唯一的替代：

```
① status 与报告方向标记一致        「↑偏高」不得判 NORMAL
② OTHER 过敏原的 isFoodBorne 判定
③ 正常语句不得进模块二             「甲状腺结节，余未见异常」的结节要留、「未见明显异常」要滤掉
④ 过敏漏抽率                       样本【按 textSource 分层】，电子版 PDF 与扫描件各半
⑤ 调味料缺失对过敏召回的影响       50 道真实菜品人工标注，分别测漏标率与过杀率
```

> **生产环境已没有任何模型漂移的实时信号**（§9.3 三个计数已取消）。
> 因此**发版前跑评测集从「建议」变成「必须」**：换模型、改提示词、报告形态变化都要跑。
> 不跑就上线 = 模型静默变差无人知晓。

### 11.5 不进普通单测、但上线前必跑的测试

CI 每次跑全量太慢或不稳定，但**它们没跑过就不许上线**，与 §11.4 的评测集同级：

| 测试 | 为什么不进普通单测 | 什么时候跑 |
|---|---|---|
| **R65p** 请求组装分配量基线 | 分配量随 JDK 小版本波动，做成硬断言是噪音 | 每次发版前；换 JDK 版本时必跑 |
| **R66g** 8000 万像素样本 + `-Xmx512m` 子进程 | 需要独立 JVM 与特定堆参数 | 同上；改动解码/压缩路径时必跑 |
| **R66h** EXIF 方向联调 | 依赖真实 OCR 服务 | OCR 接入后一次；换 OCR 版本时重跑 |
| **R66b** 大图不得直传 | 需要真实请求组装 | 批次 5 起纳入常规集成测试 |

**跑过的结论要留档**（分配量基线值、实测峰值内存、OCR 的 EXIF 行为结论），
下次回归时与留档比对——**没有留档等于没跑过**。

---

## 12. 实施批次

每批**可独立编译并通过自己的测试**，按依赖顺序推进。批次内可并行。

### 批次 1 — 地基

```
pom 依赖（§1）
包结构（§2）
三张表 DDL + Entity + Mapper + Service（§3.1，含 §3.1.1 的 @TableField、`precheck_pages` 列）
**交付物：`sql/schema.sql`**（§13.1）
IdCanonicalizer / TaskOwnershipGuard / SystemActor（§3.1.3）
五个占位符（§10.1）
错误码枚举（§9.1）
```

**验收**：能启动；R52、**R54** 通过；占位符全部抛 `UnsupportedOperationException`。

### 批次 2 — 文件入口

> **划分依据：上传接口能不能独立验收。** 它要落 `precheck_pages`，而后者依赖格式判定与文档遍历，
> 所以「文件进得来」所需的能力全在本批。**不要把格式判定挪到后面的批次**，那会让本批验收不了。

```
格式判定与路由、逐格式可读性校验、解压炸弹防御（§5.1）
    —— 含 Word 的「正文非空 或 ≥1 张合规内嵌图片」判据
CapacityPrecheckService / `precheck_pages` 全格式实现（§4.1.1）
    —— Word 只数原生 segment 与内嵌图片，**不做 OCR**
POST /file 上传接口（§4.1）+ 对象存储占位符对接
```

**验收**：R43b1、**R43b2**（上传即拒）、**R43b6**（上传预筛边界通过）、
**R43b7**（图片型 Word 正文空也放行）、R47，以及上传接口对每种格式的正例与负例。
**不依赖任何任务或解析逻辑**，可独立跑通。

### 批次 3 — 任务链路（不含解析）

```
其余三个接口（§4.1）：`analyze` / `task/{id}` / `result/{id}` + `DELETE task/{id}`
stage 三态与 progress 区间（§4.3）
DegradeAccumulator（§6.2.3）—— 先建骨架，批次 5/4 往里填标志
创建 + 绑定两步（含 ⓪ 事务外预检）+ 两个线程池（§4.2）
状态机、领取 CAS、心跳、三条巡检（§4.3）
成功写入顺序、expire_at 顺延（§4.4）
删除与清理矩阵（§4.5）
```

**验收**：R33a、R33b、R34~R41、R40a、R40b、R43b（累计 61 拒）、
**R43b8**（累计恰好 60 通过）、R50~R51 通过。
工作线程内先塞一个 sleep 占位，跑通全链路状态迁移。

### 批次 4 — 解析与 Segment

```
Segment 模型、PDFStreamEngine 覆写、OFD/Word/OCR 适配（§5.2）
ExtractionImageCompressor + CompressedPageImage（§5.6）：两档压缩、降采样解码、
    不放大 / 铺白底 / RGB / 双线性 / 方向归一化 / 资源释放
OcrImageEncoder（§5.6.2.1）与 effectiveOcrImageBytes 的启动计算（§5.6.2.1）
bbox 坐标契约：各解析器把原生坐标转成"渲染图左上原点像素"，仅 PDF 等左下原点来源翻 Y（§5.6.6）
EXIF Orientation 的 8 值坐标变换函数（§5.6.6.1）
规范化 + RadicalNormalizeMap（§5.2）
WordCapacityGuard（§4.1.2）、密度闸（§5.3）、三档页数规则与 PageBudgetService（§5.4）
批次编址与渲染（§5.5）
```

**验收**：R6、R42、R43、R43a、R43c、R43b3~R43b5、**R43b6a**、**R43b6b**、
**R43b9**（恰好 30 等效页不截断）、**R43b10**、**R43b11**（零 segment → UNREADABLE），
以及 **R66、R66a、R66c、R66e、R66f、R66h1、R66i**（压缩档位与回退；
**各解析器统一 bbox 坐标契约，仅 PDF 等左下原点来源做 Y 轴翻转**；
EXIF 8 值坐标变换的本地数学；渲染失败归属；OCR 独立编码器）通过。

```
不在本批：R66b、R66d   → 需要真实请求组装，批次 5
          R66h         → OCR 服务联调测试，需 §0.4 的 EXIF 答案；R66h1（本地数学）在本批
          R65p、R66g   → 性能/资源测试，独立 profile，不进普通单测
                         【但它们是上线前必跑项】—— 见 §11.5
```
**先做 §11.4-④ 的样本采集**，
`MAX_SEGMENTS_PER_PAGE` 与 Word 折算系数用真实数据定，不要用文档里的暂定值上线。

### 批次 5 — LLM-A 链路

```
分批与并发（§6.1）、调用与批次裁决（§6.2）
校验流水线 ① → ①a → ①b（§6.3）
来源校验（§6.4）
安全扫描 A~F（§6.5）
合并与同一性校验（§6.6）
**本批第一件事**：写 `ExtractionModelClient`（§6.2.1.1 有完整代码）+ `ExtractionProperties`
+ `LlmCallException`；解决 §6.2.2 的提示词打包（a1 或 a2，含启动自检）；
新增 `PromptVersions` 常量类与 `modelVersion` 配置项（§9.4.1）

> **R57~R65 全部用 WireMock 跑**（真实 HTTP over localhost，`test` scope，需新增依赖）。
> `MockRestServiceServer` 不行——它绕过 `ClientHttpRequestFactory`，
> 而 R64（wire logging 关闭）、R65（保持缓冲、`Content-Length`、分配量）恰恰要验的就是那一层。
>
> **只有「真实端到端联调」需要 §6.2.4 的三个 ⛔ 项**（`base-url` / `model` / `apiKey`）。
> 它不在本批验收范围内，单列为上线前的一道门；**服务端限额等其余各项另列上线前核查**。
```

**验收**：R2~R5、R7~R12、R19~R25、R43d、R43e、R44~R46、R53，
以及 **R55a、R55b**（版本号与正文摘要）、**R57~R65、R65a~R65c**（直连红线，WireMock）、
**R66b、R66d**（大图不得直传、Word 不发图）通过。
**R65p 不在本批验收**——它是分配量基线，超基线只报警不失败，属 §11.5 的上线前必跑项。
**R55c / R56b 属于 LLM-B，在批次 7 验收**；R55b 的 LLM-B 那一半也在批次 7。
**R55 / R56 已随 Dify 一并撤销**（§13.2.0）。

> **不在本批验收：真实端到端联调**——它依赖 §6.2.4 的三个 ⛔ 接入参数，
> 拿到凭据后单独跑一次，作为上线前的一道门。**本批不得因为"还没联调"而不交代码。****这是全案安全红线最密集的一批，
建议单独安排一次代码评审，逐条对照 §0.3。**

### 批次 6 — 模块一 / 二

```
排序总则唯一实现（§7.1）
模块一（§7.2）、模块二（§7.3）
```

**验收**：R26~R32 通过。此时端到端可出结果，可接前端联调。

### 批次 7 — 模块三 + 离线打标

```
模块三（§7.4）含高危安全闸
按企业枚举、菜品 Keyset 分页、当前页食材批量查询（§8.1）
打标任务、tagHash、33 个方向 staging SET 与原子发布（§8）
DishTagCleanupService（§8.1，**必须排在打标任务之后**）
LLM-B 饮食/过敏三态契约校验，凌晨 Java 仅生成低嘌呤、高纤维饮食正向标签（§8.2）
```

**验收**：R15、R17、R18、R18a、R18b~R18j、R22，
以及 **R55a、R55b、R55c、R56b**（LLM-B 那份）通过。
**内容证据审核已完成**；若组织制度要求具名执业人员签字，签字未完成前模块三仍不得上线（§0.4）。

### 批次 8 — 模块四

```
企业隔离的 Redis 集合读取与并、差运算（§7.5.7、§7.5.8）
复合成员解码、标签归属批量恢复（§8.3）
报告原文理由、排序各取前 3、精简 DTO 与空态（§7.5.9）
ArchUnit 锁定在线模块不依赖 DishQueryService、菜品 Mapper 与食材匹配器
```

**验收**：R13、R16、R16a~R16j（含 R16c1）、R33 通过。
**`MOLLUSK` / `SESAME` 的工程补齐已完成**（§0.4）；部署该版本时必须按
`tagRuleVersion=tag-1.1.0` 全量重打，不能沿用旧缓存。
本次把 `LOW_PURINE`、`HIGH_FIBER` 的 `DietPositiveMatcher` 从在线移动到凌晨，并把 Redis
方向清单固定为 33 个；提示词、Schema、版本摘要和全量重打必须作为同一批发布，不能沿用
曾允许 LLM-B 输出 9 个饮食 `RECOMMEND` 的缓存或四态结果。

### 批次 9 — 收口

```
日志白名单审计（§9.2）
计数清单落地（§9.3）
离线评测集（§11.4）
ArchUnit 断言（R1、R6）
```

**验收**：R1、R6、R48、R49 通过；全量构建绿。

---

## 13. 交付物清单

除 Java 源码与测试外，以下文件是**必交交付物**，缺一即任务未完成。
两项的共同要求：**它们不是文档的副本，而是文档的可执行形态**，
因此都配了一致性测试——没有测试锁住，它们一定会和文档漂移。

### 13.1 独立建表 SQL 文件

```
sql/schema.sql              【当前完整结构】的可执行形态，永远等于本文 §3.1
sql/alter/YYYYMMDD_xxx.sql  【从旧版本升级】的增量，一次变更一个文件，只追加不修改
```

**三者的关系必须一次说清，否则规则会互相打架：**

| | 代表什么 | 结构变更时 |
|---|---|---|
| 本文 §3.1 | **当前完整结构的真源** | **必改** |
| `sql/schema.sql` | §3.1 的可执行形态，供**新环境从零建库** | **必改**，且改后必须仍等于 §3.1 |
| `sql/alter/*.sql` | 增量，供**已有环境升级** | **新增一个文件**，已有文件永不修改 |

```
所以一次结构变更 = 改 §3.1 + 改 schema.sql + 新增一个 alter 文件，三件一起做
   【不要写成「上线后不改 schema.sql」】—— 那会让它与 §3.1 永久分叉、R54 必然失败，
   schema.sql 也会退化成一份没人敢用的历史快照
新环境建库    只跑 schema.sql，不跑 alter
已有环境升级  只跑新增的 alter，不跑 schema.sql
两条路径必须等价 —— 由 R54a 用「空库跑 schema.sql」vs「空库跑初版 + 全部 alter」比对结构来保证
```

**内容与格式要求**（与 §3.1 逐条对应，`AGENTS.md` §4 是硬约束）：

```
只含本方案创建的三张表：ct_health_report_task / ct_health_report_file / ct_dish_tag
    【不含】ct_dish / ct_dish_ingredient —— 那是食堂系统的表，本方案只读不建（§7.5.1）

每个字符列逐列写 CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci，无例外
每个字段有中文 COMMENT，每张表有中文 COMMENT，枚举列的 COMMENT 要写全允许值
表级显式写 ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
【表上不写任何 CONSTRAINT】：无外键、无 CHECK、无触发器
    只允许 PRIMARY KEY / UNIQUE KEY / KEY 三种索引声明
四个审计列每张表都有，两个时间列带 DEFAULT / ON UPDATE CURRENT_TIMESTAMP
```

**可重复执行**：每条建表用 `CREATE TABLE IF NOT EXISTS`，
文件头写明「本文件是当前完整结构，可在空库重复执行；升级已有环境请走 `sql/alter/`」。

> **本项目没有引入 Flyway / Liquibase**（`AGENTS.md` §2「不随意新增生产依赖」）。
> 两个文件都是人工执行的，因此**执行路径的等价性只能靠测试保证**（R54a），
> 没有迁移工具帮你对账。

**一致性测试（R54）**：解析 `sql/schema.sql` 与本文 §3.1 的 DDL 代码块，逐表比对：

```
列级   列名 / 数据类型与长度 / NULL 与 NOT NULL / DEFAULT 值 /
       ON UPDATE 子句 / CHARACTER SET / COLLATE / COMMENT 原文
表级   ENGINE / DEFAULT CHARSET / COLLATE / 表 COMMENT 原文
索引   PRIMARY KEY / UNIQUE KEY / KEY 的【名称、列序列、顺序】
禁止项 .sql 中不出现 CONSTRAINT / FOREIGN KEY / CHECK / TRIGGER
```

**八项列级 + 四项表级都要比**，任一不符即失败。**别漏 `DEFAULT` / `ON UPDATE` / 表引擎 /
表级字符集 / 表注释**——`create_time` 的 `DEFAULT CURRENT_TIMESTAMP` 与 `update_time` 的
`ON UPDATE` 恰恰是 `AGENTS.md` §4 的硬约束，漏比等于这条约束没有测试。

> 为什么必须测：DDL 同时出现在文档和 `.sql` 里，改一处忘另一处是必然发生的事。
> 而它的后果是静默的——测试环境按 `.sql` 建表跑得好好的，评审按文档看也没问题，
> 直到某天有人依据文档写了个查询去查一个 `.sql` 里叫别的名字的列。

### 13.2 LLM-B 直连接入契约（**不再产出 Dify DSL**）

> **2026-08-27 变更：LLM-B 由走 Dify 改为直连模型 API。**
> 至此三条模型/OCR 出网链路走的是**同一个网关、同一套 OpenAI 兼容协议**，
> 只有模型标识与配置前缀不同。

#### 13.2.0 为什么去掉 Dify

改直连**与数据敏感度无关**——LLM-B 的请求里只有菜名、食材名、重量、枚举展示名，
一条健康数据都没有，留在 Dify 不违反任何隐私约束。真正的理由是**收益归零**：

```
原方案的 D1 已经决定：提示词正文不进 DSL，由 Java 传 systemPrompt
  ⇒ 提示词在 Java
  ⇒ userMessage 拼装在 Java（原 R56 不允许模板转换节点）
  ⇒ allOf / if / then 条件约束结构化输出表达不了，在 DishTagContractValidator
  ⇒ 覆盖与互斥断言 Schema 表达不了，在 DishTagContractValidator
  ⇒ 工作流里【没有任何一条业务逻辑】，只剩 start → llm → end 三个节点的转发
```

而成本仍然全额存在，且其中一项是**已知会静默出错的那一类**：

| 去掉的东西 | 它原本的代价 |
|---|---|
| `dify/dish_tag.workflow.yml` | 必须做一次「导入 → 导出 → diff」往返验证才可信 |
| DSL 内嵌的 output schema 副本 | 与 `schema/dish_tag_output.schema.json` 会漂移，R55 存在的唯一理由就是防它 |
| `modelVersion` 双真源 | DSL 环境变量 vs `llm.model-version-dishtag` 必须一致。**这个值进 `tagHash`，不一致不报错，只是换模型再也不触发重打标**——与已修过的 `modelVersionB` 绑定缺陷是同一种病 |
| R55 / R56 | 两条只为锁 DSL 而存在的契约测试 |
| Dify 侧隐藏重试（原 D6） | 需要专门确认「平台没在背后帮我重试」，与全案零重试冲突 |
| Dify 运行记录（原 D4） | 部署级、DSL 内无开关、删不掉的一处留存 |
| `infra.DifyClient` | 占位符从四个减到三个（§10.1） |

**放弃的收益，以及为什么可以放弃：**

```
模型路由（换模型不发版）
    modelVersion 进 tagHash，换模型必然按企业触发 22 个 LLM-B 维度的全量重打标。
    这本来就是需要人盯着的运维动作，「不发版」的价值被抵消大半

可观测性（Dify 运行记录）
    LLM-B 是全案【唯一可以安全记录完整请求与响应】的模型调用
    —— 输入是食堂公开数据，输出是标签，无健康数据、无用户标识（§6.2.1 分界表）
    直连后记在自己的日志系统里，排障不必去 Dify 控制台翻，反而更顺手
    【这与 LLM-A / OCR 那两条「正文一个字都不能进日志」的链路完全不同，不要混用规则】
```

> **什么情况下这个决定应该被推翻**：① 非工程师要在 GUI 里调打标提示词
> ——那必须**同时**把 D1 改回「提示词正文放进 DSL」，否则他们改了不生效，那是最坏的情况；
> ② 需要多模型路由或供应商降级。两条都不成立时，Dify 在本链路上是纯开销。

#### 13.2.1 连接参数

| 项 | 值 | 说明 |
|---|---|---|
| Endpoint | `{llm.dishtag.base-url}` + `{llm.dishtag.chat-completions-path}` | 路径默认 `/v1/chat/completions`，与 LLM-A / OCR 相同 |
| 测试环境 `base-url` | `http://higress-http.region-4-c86-test.test-kzx1.cncb/public` | 与 LLM-A、OCR 同一个网关；拆分点同样在 `/public` 之后（§5.6.7） |
| 鉴权 | `Authorization: Bearer ${DISHTAG_API_KEY}` | 只由环境变量注入，代码库不保存真值 |
| 模型 | `llm.model-version-dishtag`，测试环境为 `qwen3-32b-k100` | **同时是 `tagHash` 的输入**，改它等于全量重打标（§9.5.1） |
| 能力 | 文本生成 / 深度思考 | 「深度思考」直接影响响应解析，见 §13.2.3 |

**不再有 `user` 字段。** 原方案给 Dify 传固定常量 `health-report-dishtag` 是因为
Dify 要求该字段非空；OpenAI 兼容的 `/chat/completions` 没有这个必填要求，直接不发。
**这条约束的实质没有变化：本链路一律不传用户标识**，打标是按菜品维度离线跑的，
结果跨用户复用，放用户标识既无意义也是泄露面。

#### 13.2.2 请求

```json
{
  "model": "qwen3-32b-k100",
  "temperature": 0,
  "stream": false,
  "messages": [
    { "role": "system", "content": "<prompt/dish_tag.md 正文>" },
    { "role": "user",   "content": "<本批菜品与枚举，见下>" }
  ]
}
```

**`temperature: 0`**：同一批菜必须得到同一批标签。有采样波动时 `tagHash` 的复用语义
就自相矛盾了——哈希相同却可能产出不同标签。

**两条消息，不是一条。** 提示词正文进 `system`，本批数据进 `user`：
真源是 `prompt/dish_tag.md`，随 JAR 打包后读取并缓存，**只有一份**（原 D1 方案 a 的实质保留）。

**`userMessage` 由 Java 渲染**，格式严格照提示词的「User（每批填充）」小节。
这是可穷举输入的确定性字符串拼接，按 `AGENTS.md` §3 本就属于 Java。

**`response_format` 暂不发送。** 平台示例里没有它，网关是否支持 `json_object` 未确认
（与 §6.2.4 第 9 项同一状态）。**Java 侧的 `DishTagContractValidator` 本来就是最终保证**，
不依赖服务端的结构化输出；确认支持后再加，属于收紧而非补漏。

#### 13.2.3 响应：⛔ 必须先剥离思考段，再解析 JSON

**qwen3 把思考过程内联在 `content` 里，不是单独的 `reasoning_content` 字段**：

```json
{
  "id": "chatcmpl-8ac354ed-4239-995f-828d-f97106e0a285",
  "object": "chat.completion",
  "created": 1787794925,
  "model": "qwen3-32b-k100",
  "choices": [
    { "index": 0,
      "message": { "role": "assistant",
                   "content": "<think>\n（思考过程）\n</think>\n\n{ 真正的 JSON }" },
      "tool_calls": [],
      "finish_reason": "stop" }
  ],
  "usage": { "prompt_tokens": 19, "total_tokens": 217, "completion_tokens": 198 }
}
```

**实测样本里一句「你好」就花掉 198 个 completion token，其中绝大部分是思考。**

##### 剥离规则必须严格，不能宽松

```
① content 以 <think> 开头 → 取【最后一个】</think> 之后的部分，trim 后解析
② content 不含 <think> 也不含 </think> → 整体 trim 后解析
③ 其余任何形态（只有 </think>、有 <think> 无 </think>、</think> 出现多次
   而首段不是 <think> 开头）→ 【整批作废】，不写库、不重试
```

> **绝对不允许「找第一个 `{` 到最后一个 `}`」这种提取。**
> 思考段里极常出现示例 JSON——模型会在里面自言自语地试写输出格式。
> 宽松提取会把**示例**当成**结果**写进库，而它 Schema 完全合法、校验全过、
> 没有任何一层会报错。这是本节唯一会造成静默错误数据的分支。
>
> 取**最后一个** `</think>` 而不是第一个：思考段里可能出现被模型引用的 `</think>` 字面量。

##### 正确性不依赖「思考能不能关掉」

qwen3 支持关闭思考，但**网关是否透传该参数未确认**（列入 §0.4）。
即使确认支持并关掉了，**剥离逻辑也必须无条件保留**：
一个部署开关不该成为解析正确性的前提，它被谁改回去都不会有编译错误。

##### `max_tokens` 要按「思考 + JSON」两段预算

一次 LLM-B 调用最多携带 40 道菜（不是 40 个 Redis 集合），其紧凑格式输出本身不大，
但思考段长度不可控。
`llm.dishtag.max-tokens` **必须留出思考的余量**，否则会在思考还没结束时被截断，
`finish_reason` 变成 `length`，`content` 里连 `</think>` 都没有——**按上面的规则 ③ 整批作废**。
这是可接受的失败形态（显式失败），但配小了会让整个打标任务批批失败。

**必须校验 `finish_reason == "stop"`**：`length` 说明被截断，即使侥幸剥出了 JSON 也不可信。

#### 13.2.4 Java 侧校验顺序

任何一步不过即整批作废、不写库、不重试；**唯一的例外是第 ⑤ 步**——
Schema 与覆盖违规能定位到某道菜时按 §8.2 修复（归入 `UNKNOWN`），修复量超 20% 才作废：

```
① HTTP 状态码 2xx，且响应体在 llm.dishtag.max-response-body-bytes 之内
② choices[0].finish_reason == "stop"        ← 不是 stop 就是被截断，下面全不可信
③ choices[0].message.content 是字符串
④ 按 §13.2.3 的三条规则剥离思考段          ← 规则 ③ 命中即作废
⑤ 剩余部分解析为 JSON 并过 DishTagContractValidator
     Schema + 覆盖（三集合并集 == 本批全部 dishId）+ 互斥（两两不相交）
```

**版本号不再需要「回传再比对」。** 走 Dify 时 `promptVersion` / `modelVersion` 是工作流
环境变量、必须原样回传给 Java 校验一致；直连之后它们就是 Java 自己的
`PromptVersions.DISH_TAG` 与 `llm.model-version-dishtag`，**比无可比，双真源消失**。
两者照旧写进 `ct_dish_tag` 行并参与 `tagHash`（§9.5.1）。

#### 13.2.5 配置键

```
llm.dishtag.base-url                 ⛔ 无默认值
llm.model-version-dishtag            ⛔ 无默认值，同时是 tagHash 的输入
llm.dishtag.api-key                  ⛔ 无默认值，绝不进代码库与日志
llm.dishtag.chat-completions-path     默认 /v1/chat/completions
llm.dishtag.max-tokens                必须留出思考段余量，见 §13.2.3
llm.dishtag.max-request-body-bytes    默认 1MiB，有界写入，见下
llm.dishtag.max-response-body-bytes   有界读取
llm.dishtag.connect-timeout-millis    默认 10s
llm.dishtag.read-timeout-millis       离线批量，可比在线链路宽松
```

##### 请求体同样有界，但理由与 LLM-A / OCR 不同

LLM-A 与 OCR 的载荷本身就大（Base64 图像），上限是**常态约束**；
LLM-B 的载荷是文本，正常单次调用约 17KB（提示词 12.7KB + 最多 40 道菜渲染），
1MiB 留了约 60 倍余量——**它防的不是常态，是上游数据异常**：

```
Dish / DishIngredient 只校验非空
    菜名长度、食材名长度、单菜食材条数【都没有上限】
    数据来自食堂系统的只读表 —— 不是用户输入，但一次数据迁移事故就能造出巨大批次
```

不设上限的代价会翻倍：`ByteArrayOutputStream` 扩容按倍增、峰值约 2 倍体积，
加上 `bufferRequestBody=true` 又复制一份，共约 3 倍，落在与 Web 层共享的堆上。
而这样的请求发出去也会被模型按上下文长度拒掉——**提前失败更快也更省**。

##### ⛔ 超限异常必须翻译成「整批作废」

```java
// DishTagClient
catch (RequestTooLargeException exception) {
    throw new DishTagBatchRejectedException("LLM-B 批次请求体超限，整批作废");
}
```

**不翻译会掀掉全场**：`DishTagService` 只捕获 `DishTagBatchRejectedException` 与
`DishTagCallException`，其余异常会中止**整个夜间打标任务**（22 个维度、全部批次），
而不是只丢掉这一批。一个为了隔离单批而加的防护，反倒成了更大的故障源。

也不能翻译成 `DishTagCallException`：请求根本没出过进程，而且重试同一批数据也不会变小。

#### 9.4.2 模型输出契约的落地类（`llm.schema`）

```
ModelOutputSchemaRegistry   两份 Schema 的唯一加载点；@Component，启动即加载，失败即启动失败
ModelOutputSchema           单份契约，只做校验
```

**存在的理由是消掉两处重复加载**：`ExtractionSchemaValidator` 与 `DishTagContractValidator`
原先各有一个 `loadSchema`，两份 Schema 的编译入口因此有两个。

> **（2026-09-02）「传输版投影」那一半已删除。** 它原本用于把 Schema 随
> `response_format=json_schema` 发给模型。实测下来这条路没有价值：**LLM-B 用不了
> `json_schema`**；LLM-A 侧只有一个候选模型支持，而它剩下的失败是条件约束（`const`），
> 投影必须剥掉、约束解码看不到。加上条目剔除机制已经把可用性救回来（§4.4-①），
> 那部分只剩「少剔几条」的边际收益，却要每批多付约 6k token。
>
> 一整块零生产调用的代码留着比删了危险——后来的人会当它在用而去维护、去同步。
> 若要恢复，依据记在设计方案 §4.4-①；网关侧的探针 `JsonSchemaGatewayVerificationIT` 保留。

#### 13.2.6 落地类

```
infra.DishTagModelClient              直连接口，只返回 content 原文（含未剥离的思考段）
infra.OpenAiCompatibleDishTagClient    实现；不剥离、不校验、不重试
infra.DishTagConnectionProperties      llm.dishtag.* 连接参数（不含 model，见 §13.2.1）
llm.dishtag.DishTagPromptProvider      提示词唯一真源，从 JAR 读取并缓存
llm.dishtag.DishTagUserMessageRenderer 批次正文渲染，确定性字符串拼接
llm.dishtag.ThinkSegmentStripper       思考段剥离，R56b 锁
llm.dishtag.DishTagClient              编排：调用 → 剥离 → 契约校验
llm.dishtag.DishTagStartupValidator    启动自检
```

**`DishTagClient` 里的顺序是「先剥离、再校验」，不能反。** 反过来的话 Schema 校验会拿到
带 `<think>` 前缀的字符串直接失败，真正的原因（模型在思考）被一个含糊的
「Schema 不合法」盖掉，排障时得从头猜。

#### 13.2.7 复用与不复用

**复用**（职责与是哪个模型无关）：
`StatusOnlyErrorHandler`（只看状态码、绝不读错误 body）、
`BoundedResponseExtractor`、`CappedByteArrayOutputStream`、零重试策略。

**不复用**：日志策略。LLM-A 与 OCR 的请求响应含健康数据，正文一个字都不进普通应用日志；
排障期仅可进入 §9.2 默认关闭的独立敏感 DEBUG logger；
**LLM-B 允许记录完整请求与响应**（§13.2.0）。这是全案唯一的例外，
写实现时要显式注释出来，避免有人照着 LLM-A 的写法把排障能力一起抄掉。

### 13.3 交付物与批次的对应

| 交付物 | 产出批次 | 验收 |
|---|---|---|
| `sql/schema.sql` | 批次 1 | R54、R54a |
| `dify/dish_tag.workflow.yml` | 已产出设计稿；批次 7 **开工第一件事是导入控制台做一次往返验证**（§13.2.1） | R55、R55a、R55b、R55c、R56 |
| `sql/alter/*.sql` | 每次结构变更 | 与 §3.1、`schema.sql` **三件一起改**，跑 R54 + R54a |

> **没有 `dify/extraction.workflow.yml`**——LLM-A 直连，不产出 DSL（§6.2.1）。

> **字段路径表已补全（§13.2.1），R55 / R56 可以写成可执行断言。**
> 但 D4 那条**不写断言**——核查结论是部署级，DSL 里没有对应字段，
> 硬写出来就是永远为真的空壳，比没有断言更糟。

---

## 14. 完成的定义

照 `AGENTS.md` §8，每个任务未完成直到：说明改了什么、列出改动文件、给出跑过的测试与结果、
确认 Java 8 / Boot 2.7 兼容、指出假设与未决项、**对安全 / 数据生命周期 / 日志 / 鉴权 /
健康数据意外持久化做最终 diff 复查**。

**未经用户明确要求，不得 commit 或 push。**

本方案额外要求三条：

```
① 每个批次结束时，对照 §0.3 的三条硬边界自查一遍并在报告里写明结论
② 任何「为了让测试通过」而放宽的断言、跳过的用例、写死的返回值，
   必须在报告里单独列出 —— 这类改动是 AGENTS.md §7-6 明令禁止的
③ §13 的交付物随代码一起交，【不是收尾时补】：
   改了 DDL   → §3.1 + sql/schema.sql + 新增一个 sql/alter/*.sql，三件一起改，跑 R54 + R54a
   改了 LLM-B 提示词或换模型 → 同步 Dify DSL 的版本号并跑 R55
   改了 LLM-A 提示词或换模型 → 同步 PromptVersions / DIGEST / 配置项并跑 R55a + R55b
   —— 这两处漂移都是静默的，靠事后补一定会漏
```
