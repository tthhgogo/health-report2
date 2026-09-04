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

**本文与设计方案当前无冲突。** 曾登记的 Word 过渡方案冲突已于 2026-09-03 随裁决消除：
**第一期不支持 DOC/DOCX，上传识别即拒**（设计方案 §3.2.1、§12-16，本文 §5.4）。
设计方案相对产品需求的偏离统一登记在设计方案 §12；其中
§12-12 明确覆盖所有进入 `rejectSet` 的菜，包括过敏原 `REJECT` 和饮食注意 `REJECT`，不能缩写成
只有过敏冲突。

> 曾登记的 C1（`AGENTS.md` 要求「队列用 Redis Stream + Consumer Group」vs 设计方案已改本机线程池）
> **已消除**：`AGENTS.md` §2 现在写的是「是否使用消息队列以及任务调度方式完全以设计方案为准，
> 本文件不另行指定」。本文按设计方案实现线程池（§4.2），无需再做例外说明。

### 0.3 三条贯穿全文的硬边界

违反其一即为错误实现，代码评审必须打回：

```
① 分层职责（AGENTS.md §3、设计方案 §0-2）
   Java 只做：Schema 校验、页码/枚举/方向校验、安全降级、菜品集合运算、数值计算、排序
   Java 不做：改写模型的语义结论（status / sourceType / dimension / enumKey）
             版面推断（相邻块配对、bbox 同行、表格行列还原、按坐标聚类）
             为「只告警」而扫语义词表

② 生产链路里的词表执行点全部是「往安全方向降级或拦截」
   高危表述类   §7.4  高危表述安全闸
   过敏兜底类   §7.5.5 离线菜品过敏关键词兜底（与模型结果取并集，只增不减 REJECT）
   除此之外不得出现 ConclusionLabelWords / NormalStatementWords / AllergenSectionWords
   的任何生产代码引用（§11.1-R1 用 ArchUnit 断言）

③ MySQL 不存报告正文、页面图和结构化健康结论；这些不进普通应用日志，
   排障期仅可进入 §9.2 规定的独立敏感 DEBUG logger
   —— 注意措辞：**不是「MySQL 不含任何敏感信息」**，`origin_name` 就是已登记的例外
   姓名 / 性别 / 页面图 / 三次原始模型响应：只在工作线程内存，从不写 Redis
   四模块要展示的原文片段：随结果写 Redis，TTL 2h
   ⚠️ 【已登记的例外】ct_health_report_file.origin_name 是敏感元数据
      （常含姓名与体检属性），约束见 §3.2 —— 不要再说"MySQL 不含任何敏感信息"
   日志白名单见 AGENTS.md §6
```

#### 0.3.1 边界层的值必须显式校验，不能靠 Java 类型（2026-08-27 补）

**领域对象在构造器里把非法值拒掉，这套习惯在本仓库是有效的**——`Dish`、`PageImage`、
`ValidatedDietAdvice`、`DishTagInput` 都应这么做，所以它们的消费方可以按「构造即安全」写，
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
| **网关是否透传 qwen3 的关闭思考参数**（菜品离线打标模型） | **不阻塞正确性**——剥离逻辑无条件保留（§13.2.3）；只影响 token 成本与 `max-tokens` 该配多大 | 本文 §13.2.3 |
| **体检报告分析模型直连的整条出网链路是否留存请求体** | **上线阻断** | 见下的六项核查；三次报告分析请求组成全案最敏感的出网链路 |
| **§6.4 的 ⛔ 三项**（base-url / model / apiKey）与服务端限额 | **只阻塞端到端联调与上线**，不阻塞编码——协议已选定，代码可写完并用 WireMock 验 | 本文 §6.4 |
| **网关是否支持 `response_format=json_object`**（体检报告分析链路已发送该字段） | **联调前必验**——不支持则三次调用直接 400；届时从客户端 `buildRequestBody` 移除该字段 | 本文 §13.2.2 注 |
| **OFD 页面渲染的保真度** | **上线前必验**：全部 OFD 都要转图，不再有 OCR 回退路径；需用真实扫描版和电子版样本验证页数、旋转、字体和小数点 | 本文 §5.2、设计方案 §11 |

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
| `org.ofdrw:ofdrw-reader` | OFD | |
| `com.github.promeg:tinypinyin` | 菜名拼音 | |
| `com.networknt:json-schema-validator` | 模型输出 Schema 校验 | Java 8 兼容版本 |
| `com.xuxueli:xxl-job-core` | 离线打标调度 | |
| HTTP 客户端 | **体检报告分析模型与菜品离线打标模型直连模型 API** | 优先用 `spring-boot-starter-web` 自带的 `RestTemplate`，**不新增第三方 HTTP 库**（`AGENTS.md` §2） |
| `com.tngtech.archunit:archunit-junit4` | §11.1-R1 架构断言 | **test scope** |
| `com.github.tomakehurst:wiremock-jre8` | R57~R65 的真实 HTTP 红线测试 | **test scope**，`jre8` 版本才兼容 Java 8 |

**Java 8 语法红线**（`AGENTS.md` §2）：本文所有示例代码都已避开 `var` / `List.of` /
text blocks / switch 表达式 / `Optional.isEmpty` / `String.isBlank` / `Stream.toList`。
照抄示例不会踩线；自行扩写时按同样口径。

---

## 2. 包结构与命名

> **包名与类名按职责取，不用 `a` / `b` / `1234` 这类序号**（`AGENTS.md` §6）。
> 设计方案中的字母代号在开发文档与代码中统一改用职责名称；模块一~四仍沿用产品编号：
>
> | 叙述用语 | 包 | 主要类前缀 |
> |---|---|---|
> | 体检报告分析模型 | `llm.extraction` | `Extraction*` / `HealthReportAnalysisModel*` |
> | 菜品离线打标模型 | `llm.dishtag` | `DishTag*` |
> | 模块一 健康指标 | `assemble.indicator` | `Indicator*` |
> | 模块二 健康问题 | `assemble.problem` | `Problem*` |
> | 模块三 饮食建议 | `assemble.dietadvice` | `DietAdvice*` |
> | 模块四 菜品推荐 | `assemble.dishrecommend` | `DishRecommend*` |
>
> Prompt 与 Schema 的**文件名也按职责取**，与上表的包名对齐：
>
> | 资源 | 调用一 健康指标 | 调用二 健康问题 | 调用三 饮食建议与标签 | 离线菜品打标 |
> |---|---|---|---|---|
> | 提示词 | `prompt/indicators.md` | `prompt/health-problems.md` | `prompt/diet-tags.md` | `prompt/dish_tag.md` |
> | 输出契约 | `schema/indicators.schema.json` | `schema/health_problems.schema.json` | `schema/diet_tags.schema.json` | `schema/dish_tag_output.schema.json` |
> | `promptVersion` 前缀 | `indicators-` | `problems-` | `diet-tags-` | `dishtag-` |
>
> **体检报告分析模型固定调用三次**（设计方案 §4.1）。三份提示词与三份 Schema
> 各自独立版本化，`prompt/versions.tsv` 里各占一行。
> 旧的 `prompt/extraction.md` 与 `schema/extraction_output.schema.json` **整体废弃**。
> 当前仓库的 `*-probe.md` / `*_probe.schema.json` 仅用于质量探测，不是上表生产文件；
> 三对生产契约在实现主链路前必须先落地并通过契约测试。

```
com.example.healthreport
├── api                     Controller + 请求/响应 DTO
│   └── dto
├── task                    任务生命周期：创建、线程池、状态机、巡检
├── render                  文件 → 页面图（原 parse 包，职责已收窄）
│   ├── pdf | ofd | word | image
│   └── PageImageSequence   全局图序列 + page → (fileIndex, pageInFile) 映射表
├── llm                     模型链路
│   ├── extraction          体检报告分析模型（三次串行调用）：请求组装、顺序编排、Schema 校验、结构自洽校验
│   ├── dishtag             菜品离线打标模型：离线打标契约与校验
│   └── schema              两条链路共用的输出契约：Schema 的唯一加载点与校验入口
├── assemble                四模块组装
│   ├── indicator           模块一 健康指标
│   ├── problem             模块二 健康问题
│   ├── dietadvice          模块三 饮食建议
│   └── dishrecommend       模块四 菜品推荐
├── dish                    菜品查询、主料推导、标签读取与裁决
├── safety                  安全扫描、降级决策、词表（仅三处，见 §0.3）
├── constants               已存在，内容常量真源（不动结构）
├── persistence             Entity / Mapper / Service（`AGENTS.md` §4 命名）
├── cache                   Redis 键位与读写
├── infra                   三个占位符（`AGENTS.md` §5）与已实现的模型客户端
└── support                 IdCanonicalizer、计数器、错误码、共用工具
```

**源码之外的交付物目录**（§13）：

```
sql/schema.sql              建表语句，与 §3.1 一字不差（R54 锁）
sql/alter/*.sql             上线后的结构变更，一次一个文件
（无 Dify DSL 交付物）      体检报告分析三次调用与菜品离线打标全部直连，接入契约见 §6.4 / §13.2
dify/README.md               记录菜品离线打标曾经的编排形态与改直连的理由；【不是交付物】
prompt/*.md                 提示词真源（三份在线生产文件待创建，probe 不代替生产文件）
schema/*.json               模型输出契约（三份在线生产 Schema 待创建）
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
  partial        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否发生预算内条目剔除：1是0否，具体影响由partial_reason说明',
  partial_reason VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '降级原因：SCHEMA_ITEM_DROPPED普通条目被剔除/DIET_TAG_DROPPED饮食标签被剔除并抑制菜品推荐',
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
  content_type   VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '按内容判定的真实格式：PDF/JPG/PNG/OFD，不信任扩展名；DOC/DOCX识别即拒不落行',
  size_bytes     BIGINT       NOT NULL COMMENT '文件大小，单位字节',
  precheck_pages INT          NOT NULL COMMENT '创建任务容量预检页数：PDF与OFD为真实页数，图片恒为1，全部为精确值',
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
  model_version       VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '菜品离线打标模型版本，冗余存储仅供排障，不参与任何键与查询条件',
  prompt_version      VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '菜品离线打标提示词版本，冗余存储仅供排障',
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
页面图和原始模型响应只在 Worker 内存，Worker lease 用
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
>     ③ 【禁止传给任何外部系统】—— 不进三次体检报告分析请求、不进菜品离线打标请求、不进对象存储元数据
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

【结果里不得包含】姓名、性别、页面图、三次体检报告分析模型原始响应（§0.3-③）
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
| 大小 | PDF/OFD ≤ 20MB；JPG/PNG ≤ 10MB | `FILE_TOO_LARGE` |
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

它只服务于创建任务前的**容量预筛**，全部支持格式都是精确值
（第一期不支持 Word，§5.4——无折算值、无 Worker 二次业务容量裁决）。

```java
// render.CapacityPrecheckService —— 上传时调用，不保存报告原文
int countPrecheckPages(byte[] content, ContentType type);
```

| 格式 | `precheck_pages` | 同步拒绝条件 |
|---|---|---|
| PDF / OFD | 真实页数 | 与任务内其他文件累计后 > 30，由 analyze 拒绝 |
| JPG / PNG | 恒为 1 | 同上 |

上传时只落 `precheck_pages`，不落任何报告原文。

Worker 从对象存储逐文件读回后必须重新执行运行时完整性复核：长度和 SHA-256 分别与
`size_bytes`、`content_hash` 一致；重新识别的真实格式与 `content_type` 一致；重新执行
格式安全检查（OFD 含 ZIP 炸弹扫描）与可读性/精确页数预检，结果与 `precheck_pages` 一致。
任务快照还必须满足文件数、连续 `file_index`、总字节数和总页数上限。任一项漂移按
`SERVER_ERROR` 失败且三次模型调用数为 0，不重新归因成用户输入错误。

#### 4.1.2 【已随 Word 移除】Worker 二次业务容量裁决

第一期不支持 Word（§5.4）后所有格式页数在上传时即精确，`WordCapacityGuard`、
等效页折算与「渲染后再作业务裁决」整体不存在。`PAGE_LIMIT_EXCEEDED` 只在上传与
analyze 创建时**同步**发生，不再是异步任务失败码。§4.1.1 的 Worker 复核只验证对象与
任务快照未发生漂移，命中时是 `SERVER_ERROR`，不是重新引入本机制。恢复 Word 支持时按
设计方案 §3.2.1 的恢复条件一并恢复本节机制。

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

**其余校验：** `fileIds` 数量 1~5、累计 ≤ 60MB、累计 `precheck_pages` ≤ 30
（全部格式精确，§4.1.1，创建时同步裁决即为最终裁决）。
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
        "processedPages":12, "totalPages":12,
        "suppressDishRecommend":bool,
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

**模块被抑制或未产出时该字段为 `null`**，与同级的 `suppressDishRecommend`
布尔位对齐；前端判空只需判 `null`，不必再区分「空数组」和「不存在」。
（曾有 `suppressDietAdvice` 字段，2026-09-03 删除：全案没有任何规则会把它置 true——
`DIET_TAG_DROPPED` 只抑制模块四，模块三照常展示其余已校验条目。）

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

`processedPages` / `totalPages` 两个字段**必须下发**，成功结果中两者必须相等；
精确总页数超过 30 时任务在体检报告分析模型调用前失败，不存在 `PAGE_TRUNCATED` 结果（§5.5）。

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
       SUM(precheck_pages) > 30  →  PAGE_LIMIT_EXCEEDED，【直接返回，不建任务、不绑文件】
       SUM(size_bytes) > 60MB     →  FILE_TOO_LARGE，同上
   —— 纯算术，不解析任何文件；全部格式的 `precheck_pages` 都是精确值（§4.1.1）
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

**只保留一个有界任务线程池：**

```java
// task.ExecutorConfig  —— 这不是「中间件配置类」，是业务线程池，允许写
@Bean("analysisExecutor")
ThreadPoolExecutor analysisExecutor() {
    return new ThreadPoolExecutor(
        W, W, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<Runnable>(QUEUE_CAPACITY),   // 有界
        new ThreadPoolExecutor.AbortPolicy());             // 满了直接抛
}

```

```
不得用无界队列        用户排到文件 expire_at（30min）都到了，排到也没文件可读
不得用 CallerRunsPolicy 分析会跑在 Tomcat 请求线程上，分钟级占死，拖垮 Web 层
不得静默丢弃          任务永远停在 QUEUED，只能等 5 分钟巡检兜底
不得创建批次池        三阶段是同一任务内的固定顺序，直接在当前任务线程中顺序调用
                     不提交子 Future，不在阶段间并发

W = min( floor(C / 实例数),                          ← 单任务同时只占 1 个体检报告分析模型在途配额
         floor((堆预算 - Web 层占用) / 单任务峰值) )   ← 内存预算，单任务峰值见 §11.5
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

**心跳：** 每 30s 更新 `heartbeat_at`，**由独立调度线程执行**——转图和三次串行模型调用期间主流程
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

**正确性靠写回条件保证，不靠「能不能及时停下来」**——与单任务同步执行、终态 CAS 的原则同源。

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

## 5. 文件转图

> 对应设计方案 §3。**本节的全部职责是「把各种格式变成 JPEG 页面图」，不做任何判断。**
> 旧的 Segment 切分、文本规范化、密度闸、OCR 调用**整体删除**，相关类一并移除。

### 5.1 格式判定与可读性

**逐格式判定，不信任扩展名，也不能只看 magic number：**

| 格式 | 判定 | 可读性校验 |
|---|---|---|
| PDF | `%PDF-` 头 | PDFBox 能打开、页数 ≥ 1 |
| JPG/PNG | magic number + **实际解码** | 解码成功、宽高 ≥ 100px、总像素 ≤ 8000 万 |
| DOCX | ZIP 容器 + 内含 `word/document.xml` | 不适用——**识别即拒**（§5.4） |
| OFD | ZIP 容器 + 内含 `OFD.xml` | ofdrw 能打开、页数 ≥ 1 |
| DOC | OLE2 头 `D0CF11E0` | 不适用——**识别即拒**（§5.4） |

`.zip` 不是支持格式，直接拒。但 DOCX 与 OFD 自身就是 ZIP，magic number 相同，
必须解开查内部结构才能区分——**DOC/DOCX 不支持但识别逻辑必须保留**：
不区分的话 DOCX 会被当成损坏 OFD 报「文件无法读取」，正确文案是「暂不支持该文件格式」。
识别只读 ZIP 条目名与目标条目，不做全量内容解压。

**PDF 不再判断「有没有文本层」。** 一律转图，字形密度闸随之删除。

**解压炸弹防御（DOCX / OFD）：** 流式计数，**不信 `ZipEntry.getSize()`**；
条目数 ≤ 1000、单条目 ≤ 50MB、总解压 ≤ 200MB、压缩比 ≤ 100:1，任一超限立即中断。

`FormatDetector` / `ReadabilityChecker` / `ZipBombGuard` / `ImageContentInspector` **全部保留**，
职责不变。

### 5.2 类清单

```
render
├── FileToImageService          入口：List<FileRef> → PageImageSequence
├── PageImageSequence           全局图序列 + page → (fileIndex, pageInFile) 映射表（不可变）
├── PageImage                   一页：JPEG 字节 + 宽高 + 全局 page
├── pdf/PdfPageRenderer         已存在，不改：300dpi、长边 ≤3600、Rotate 归一化
├── ofd/OfdPageRenderer         ofdrw 逐页转图
├── image/UploadedImageAdapter  解码 → EXIF Orientation 归一化 → 重编码
└── ExtractionImageCompressor   已存在，不改：长边 2000/q0.85，超 1MiB 降 1600/0.80
```

**删除的类**（连同其测试）：

```
parse/segment/**              Segment、GlyphDensityGate、BBox、TextSource
                              【TextNormalizer 及其依赖不在此列，见下方移包指令】
parse/pdf/PdfSegmentParser    仅保留 RENDER_DPI / MAX_RENDER_LONG_EDGE 常量，移入 PdfPageRenderer
parse/ofd/OfdSegmentParser
parse/word/WordSegmentParser
parse/ocr/**                  OcrBlock、OcrBboxNormalizer、OcrContentSplitter、OcrPageSegmentFactory
parse/Ocr*                    OcrProperties、OcrCapacityCalculator、OcrImageEncoder、OcrRequestEncoding
infra OCR 客户端族             PaddleOcrVlClient、PaddleOcrClient、OcrConnectionProperties、
                              OcrStartupValidator、OcrCallException —— OCR 整体退出在线链路
PdfTextLayerChecker
```

**随 OCR 删除必须同步改造的三处（不是删文件就完事）：**

```
① task/FileUploadService 的图片上传上限
   原取 min(产品上限 10MB, OcrProperties.getEffectiveOcrImageBytes())，OCR 删除后
   后一半失去来源。【已落地口径（2026-09-03）】：上传上限只剩产品 10MB——
   上传图会被重压缩到单页 ≤1MiB 再进请求体，上传字节数与模型请求体积已经解耦，
   反推公式不成立（按公式算会把大部分正常照片拒掉）。模型请求体的约束由两处承担：
   压缩器单页 1MiB 上限 + 客户端 max-request-body-bytes（启动自检要求 ≥30页满载下限，R66k）。
   OcrCapacityCalculator 随之整体删除，无需改名保留
② RenderedPageImageProcessor / PageImageArtifacts 是「一份渲染图双消费者」入口
   （OCR 编码档 + LLM 压缩档）。新链路只剩压缩档一个消费者，简化为单产物；
   【「渲染位图用完立即释放」的约束在这两个类里，简化时必须原样保留】
③ ImageEncodingSupport 是 package-private，被 OcrImageEncoder 与
   ExtractionImageCompressor 共用。它的 resizeRgb / encodeJpeg 是转图核心，【必须留】，
   随 parse → render 移包时一起搬，防止跨包引用编译失败
```

**必须移包、不得删除的三个类**（实测有 5 处消费者，其中 4 处在新链路里仍需要）：

```
parse/segment/TextNormalizer            →  support/text/TextNormalizer
parse/segment/TextNormalizationResult   →  support/text/TextNormalizationResult
parse/segment/RadicalNormalizeMap       →  support/text/RadicalNormalizeMap
```

| 存活的消费者 | 用途 |
|---|---|
| `dish/TagHashCalculator` | 离线打标的 `tagHash` 计算，与本次改动无关 |
| `safety/HighRiskAdviceGate` | 扫 `quote` 前归一化 |
| `llm/extraction/StructuralValidator`（新增） | `name` 逐段校验的 `containsNormalized` |
| `assemble/dietadvice` 的食材差集（§7.4） | 宽松匹配前统一全角半角 |
| 多文件同一性校验（设计方案 §4.5） | 姓名规范化后比较 |

> **新链路里它主要挡的是全角与合字，不是零宽字符。** 这一点相对旧链路变了，
> 类注释里那段「零宽空格让来源校验匹配不到」的论证**只对旧链路成立**——
> 那些字符来自 PDF 文本层与 OCR 输出，而这两条路都没了；模型看的是渲染图，
> **零宽字符画不出来，它读不到自己看不见的东西**。
>
> 现在真正会撞上的是模型照抄报告排版带来的差异：
>
> ```
> 「0.56～1.70」   ～ 是 U+FF5E 全角波浪号，常量表里写的是半角 ~
> 「㎎」「㎏」「℃」  合字，NFKC 拆成 mg / kg / ℃
> 「１.８４」       全角数字
> 「虾（蟹）类」    全角括号
> 「虾 蟹类」       中间是全角空格 U+3000
> ```
>
> **不可见字符的删除仍然保留**，但来源换了：不再是 PDF/OCR，而是
> **人手写的 Java 常量表**（从网页或 Word 粘贴时带进的 NBSP）与
> **Excel 导入的菜品食材数据**。成本为零，覆盖面仍在，只是不该再当作主要论据。
>
> **迁移时同步更新 `TextNormalizer` 的类注释**，否则后人会照着一段已经不成立的理由维护它。

随之删除的是 `ReferenceRangeParser`（参考范围比较改由模型做）与
`SourceEvidenceValidator`（来源引用校验整体删除）。

### 5.3 `PageImageSequence` 是这条链路的关键数据结构

```java
// 不可变；构造时同时建立映射表，之后只读
public final class PageImageSequence {
    private final List<PageImage> pageList;      // 全局顺序，page = 下标 + 1
    private final int[] fileIndexByPage;         // page → fileIndex
    private final int[] pageInFileByPage;        // page → 文件内页码

    public int size();
    public byte[] jpegBytesAt(int page);         // page 从 1 起
    public FileLocation locate(int page);        // 越界抛 IllegalArgumentException
}
```

**`locate` 是查数组，不是推断**（设计方案 §0-2）。模型只报全局 `page`，
「它属于哪个文件的第几页」由这张表回答。越界的 `page` 由调用方按「引用了不存在的页」丢弃条目。

### 5.4 Word：第一期不支持（2026-09-03 裁决）

DOC / DOCX 上传即返回 `UNSUPPORTED_FORMAT`，不落 file 行、不存对象（设计方案 §3.2.1、§12-16）。
裁决理由：纯 Java 开源路线（docx4j+FOP / XDocReport）对重表格医疗文档保真度不合格，
会造成表格串行的静默错读；LibreOffice / 商业库需新增部署依赖或采购，
在 Word 上传占比（设计方案 §11-9）未证实前不值得付出。

**不写 `WordPageRenderer`，也不留抛 `UnsupportedOperationException` 的占位**
——格式在上传口就被拒了，渲染层根本收不到 Word。POI 依赖随之从 pom 移除（§1）。

**随本裁决移除的机制清单**（恢复 Word 支持时按设计方案 §3.2.1 一并恢复）：

```
等效页折算 ceil(字符数/1800)+内嵌图片数     WordCapacityGuard 与 Worker 二次容量裁决
「正文非空或 ≥1 张合规内嵌图片」可读性判据    R43b2~R43b7 全部 Word 容量测试
PAGE_LIMIT_EXCEEDED 的异步失败路径          POI（poi-ooxml / poi-scratchpad）依赖
```

### 5.5 容量限制

```
任务总页数上限       30 页（含全部文件），创建任务时同步拒绝（§4.1.1）
超限                 PAGE_LIMIT_EXCEEDED，不建任务、不绑文件；不截断、不输出部分结果
```

`CapacityPrecheckService` 保留（只处理 PDF / OFD / 图片；上传预检与对象存储读回复核共用）；
`WordCapacityGuard` 与 `PageBudgetService` 删除——前者随 Word 移除（§5.4），后者因不再截断页面。


## 6. 体检报告分析模型链路（三次串行调用）

> 对应设计方案 §4。调用顺序固定为「健康指标 → 健康问题 → 饮食建议与标签」，
> 每次都发送同一份完整 `PageImageSequence`。

### 6.1 类清单

```
llm/extraction
├── ExtractionOrchestrator       在当前任务线程中顺序执行三次调用并 fail-fast
├── ExtractionCall               枚举 INDICATORS / PROBLEMS / DIET_TAGS
│                                每个枚举值携带 Prompt 路径、Schema 路径和 promptVersion
├── ExtractionPromptProvider     启动时加载三份生产 Prompt，发现缺失或加载 probe 立即启动失败
├── ExtractionRequestFactory     组装 system Prompt、最小阶段文本和全部 JPEG 页
├── ExtractionSchemaValidator    按 ExtractionCall 选择三份生产 Schema
├── StructuralValidator          页码、联动字段、枚举与方向校验
├── IndicatorsResult             调用一校验后结果
├── ProblemsResult               调用二校验后结果
├── DietTagsResult               调用三结果：顶层 recommend/reject 标签数组
└── ExtractionOutcome            三份已校验结果，供唯一汇总入口消费

infra
├── HealthReportAnalysisModelClient
├── OpenAiCompatibleHealthReportAnalysisModelClient
├── HealthReportAnalysisModelProperties
└── HealthReportAnalysisCallException
```

删除 `BatchPage`、`ExtractionBatchInput`、批次规划、`blockRef` 展开、
`SourceEvidenceValidator`、旧模型批次执行器以及任何 `Future<CallResult>` 式的阶段并发编排。

### 6.2 顺序编排

```java
public ExtractionOutcome extract(List<FileRef> fileList) {
    PageImageSequence images = fileToImageService.render(fileList);

    IndicatorsResult indicators = executeAndValidate(ExtractionCall.INDICATORS,
            images, ExtractionStageContext.empty());
    identityGuard.check(indicators.getPatientList(), images);

    ProblemsResult problems = executeAndValidate(ExtractionCall.PROBLEMS,
            images, ExtractionStageContext.empty());

    DietTagsResult dietTags = executeAndValidate(
            ExtractionCall.DIET_TAGS,
            images, ExtractionStageContext.empty());

    return new ExtractionOutcome(indicators, problems, dietTags);
}
```

上述伪代码是顺序契约：不使用子线程，不先发后校验。调用一失败时调用二、三的次数均为 0；
调用二失败时调用三次数为 0。任意阶段失败均抛给 Worker 的统一失败映射点，
不创建可下发的部分 `AnalysisResult`。

**三次请求的图必须完全一致：**

```
images.size() 相等
每个 page 的 jpegBytes 引用同一 `PageImageSequence` 元素
消息中的页码文本与紧随图像一一对应
不为任何一次调用重新渲染、重新压缩或重排
```

### 6.3 三次请求的文本输入

| 调用 | 文本输入 | 禁止字段 |
|---|---|---|
| `INDICATORS` | 页数与通用任务说明 | 所有用户/任务/文件标识 |
| `PROBLEMS` | 页数与通用任务说明 | 阶段 1 结果、姓名、性别、overview、原始响应 |
| `DIET_TAGS` | 页数与通用任务说明 | 阶段 1/2 结果、companyId、菜品 ID、菜名、菜品标签、价格、图片 URL |

第三次只返回顶层 `recommend/reject` 数组，每条建议包含原文引用、页码、`dimension`、`enumKey`；所在数组就是方向。
菜品列表不属于该 Schema；Java 必须在三次体检报告分析调用全部完成后再用标签查 Redis 生成模块四。

### 6.4 客户端与响应裁决

`OpenAiCompatibleHealthReportAnalysisModelClient` 保留有界请求/响应、零重试、脱敏日志、
`finish_reason` 判死、content / reasoning_content 双通道裁决和 SSE 支持。流式与非流式共用
同一个 `extractContent` 和校验流水线。请求体固定
`chat_template_kwargs.enable_thinking=false`。

```
llm.extraction.base-url            ⛔ 无默认值
llm.extraction.chat-completions-path  默认 /v1/chat/completions
llm.extraction.model               ⛔ 无默认值
llm.extraction.api-key             ⛔ 无默认值；只允许从部署密钥注入
llm.extraction.stream-enabled       默认 true
llm.extraction.enable-thinking      默认 false
llm.extraction.read-timeout-millis  按三阶段中实测最大单次耗时定档
llm.extraction.max-request-body-bytes
llm.extraction.max-response-body-bytes
```

三次请求都不含 `taskId/userId/companyId/origin_name`。普通日志不得记录请求体、响应体、
页面图、报告引用、饮食建议或模型异常中可能携带的响应摘要。

### 6.5 校验与结果原子性

每次固定执行：`HTTP/finish_reason → 内容提取 → JSON 解析 → Schema 校验 → 结构校验`。
可定位条目的问题可剔除，但同一阶段共用 20% 修复预算（至少允许 1 条）；
超预算、顶层错误或非 `OK` 状态即阶段失败。
阶段 1/2 剔条目与阶段 3 剔标签同时发生时，`partial_reason` 取 `DIET_TAG_DROPPED`
（设计方案 §4.4：单值列，取携带模块四抑制后果的那个）。

| 调用 | 结构校验 |
|---|---|
| 全部 | 每个 `page` 在 `[1, images.size()]`；所有 object 禁止未知字段 |
| 一 | `conclusionGenerated=true` 时 `status=NORMAL` 且 `refRange` 非空；章节唯一 |
| 二 | `INDICATOR` 引用与调用一异常指标的匹配**只决定 `indicatorId` 跳转按钮，匹配不到不丢弃条目**（设计方案 §6.2）；`name` 的原文片段能在 `rawText` 中找到。该匹配发生在调用后，不把阶段 1 输出发给阶段 2 |
| 三 | `enumKey` 属于 `dimension`；方向符合固定表；不从指标/问题输入推导；不得出现任何菜品字段 |

任意调用失败都让整任务 `FAILED`，不写部分 `result:{taskId}`。
`NO_REPORT_FEATURE` 映射 `NOT_HEALTH_REPORT`，`UNREADABLE` 映射 `UNREADABLE`，服务端异常映射
`SERVER_ERROR/reanalyzable=true`。全链路零重试。

### 6.6 Prompt、Schema 与版本

`PromptVersions` 新增三个独立常量：

```java
/** 健康指标生产提示词版本，必须与文件头及摘要历史一致。 */
public static final String INDICATORS = "indicators-1.1.0";
/** 健康问题生产提示词版本，必须与文件头及摘要历史一致。 */
public static final String PROBLEMS = "problems-1.0.0";
/** 饮食建议与标签提示词版本，必须与文件头及摘要历史一致。 */
public static final String DIET_TAGS = "diet-tags-1.1.0";
```

三对生产 Prompt/Schema 是开发前置，各自登记 `prompt/versions.tsv`。
生产加载器必须显式拒绝文件名含 `probe` 的资源。
旧 `PromptVersions.EXTRACTION`、`prompt/extraction.md`、`schema/extraction_output.schema.json` 整体退出主链路。

`indicators.schema.json` 以 probe 为基线并新增顶层必填 `patients`：元素只含
`page / name / gender`，其中 `page` 有效且 `name`、`gender` 不得同时为空。
`diet_tags.schema.json` 的 `ALLERGEN` 正式枚举列 13 个食入性组、
`DUST_MITE / POLLEN / ANIMAL_DANDER / MOLD / COCKROACH` 五个非食入性组与 `OTHER`。
五个非食入性组保留在模块三展示，但 `DishRecommendationSetService` 必须拒绝用它们拼 Redis Key。


## 7. 四模块组装

### 7.1 顺序：数组顺序即展示顺序

**旧的「排序总则」整体删除。** 契约里已经没有任何序号字段（设计方案 §4.2），
顺序由模型给出的数组顺序承载：

```
模块一   sections 数组顺序 → 组内 indicators 数组顺序
模块二   problems 数组顺序（模型已按需求 §6-4 排好，Java 只校验分组边界）
模块三   recommend / reject 各自的数组顺序
模块四   拼音首字母（需求 §8-4/§8-5），与上面三条无关
```

**多文件时先按 `page` 收敛，再按数组顺序。** `page` 是全局图序号，天然递增且跨文件唯一，
它同时承担了旧链路里 `fileIndex` + `groupOrder` + `page` 三个键的作用。

`assemble/sort` 包连同 `GroupOrderCalculator`、`SectionGroupKey` 一并删除。

### 7.2 模块一：健康指标

数据来自 `IndicatorsResult`。**Java 只做取值、拼接与计数。**

```
分组      直接用 sections，一个 section 一个卡片区
分组名    单文件 = section；多文件 = 「报告{fileIndex+1}-」+ section
          fileIndex 由 PageImageSequence.locate(section.page) 得到
展示结论  conclusionGenerated = false → status 的标准文案
          conclusionGenerated = true  → 「在参考范围内」；定性项为「符合报告参考值」
          并把 conclusionGenerated 原样下发，前端据此做视觉区分（需求 §5-3 第 80 行）
状态标签  status 原样下发
总览条    overview 两个数字直接采信；正常项数 = total - abnormal；占比四舍五入到整数
```

**`overview` 不与抽取结果交叉核对**（设计方案 §5.4）：报告口径把身高体重血压也算了进去，
拿它去卡抽取条数只会得到假警报。差额只进埋点。

`IndicatorCardFactory` 保留；`ReferenceRangeParser` / `ConclusionBasisResolver` **删除**
——参考范围比较现在由模型完成，Java 不再解析 `4.0~10.0` 这类原文。

### 7.3 模块二：健康问题

数据来自 `ProblemsResult`。**`problems` 数组即最终展示列表**，Java 只做三件事：

```
① 拼来源标注
   INDICATOR → section + "–" + indicatorName          「血脂检查–甘油三酯」
   SUMMARY   → section + "第" + itemNo + "条"          「总检结论第3条」
               itemNo 为 null 时退化为 section
   多文件加「报告N-」前缀

② 关联跳转
   indicatorName 在模块一的指标里查表匹配 → 命中则下发 indicatorId，未命中则不显示按钮
   【查表匹配，不做模糊匹配】：名称对不上就是对不上，宁可不显示按钮，
   也不能跳到一张不是它的卡片

③ 分组边界校验
   全部 INDICATOR 必须排在全部 SUMMARY 之前，不满足时稳定重排（§6.4）
```

`displayName` 直接用 `name`，**Java 不拼「归一化结论词」**——不把 `status = HIGH`
翻译成「偏高」再拼进问题名，那是拿模型的语义分类生成一句报告里没有的医疗表述。


### 7.4 模块三：饮食建议

**三条硬约束，靠结构保证不是靠提示词：**

```
① 不从指标异常推导建议 —— 甘油三酯偏高 ≠ 低脂饮食，只有报告明文写了才生成
② 不合并同向建议 —— 「低脂饮食」与「控制体重」各自成卡片，各自引用各自原文
③ 不在饮食建议中引用指标数据
   守法方式是结构性的：本模块只接收阶段三的标签与来源字段，结构上看不到指标或健康问题数据
```

**直接后果（产品已确认接受）**：报告只写「血脂偏高，建议复查」时，
饮食建议三个分区全空态、菜品推荐空。**这是需求 §7-5 的明确选择，不是缺陷**，
不做通用建议兜底。

**枚举**（36 个正式 + 各维度共用的 `OTHER` 哨兵）：
13 食入性过敏原 + 5 非食入性过敏原 + 9 营养补充 + 9 饮食注意。
非食入性过敏原进入阶段三与模块三展示，但不参与菜品匹配。
真源是 `constants` 包的 Java 常量类，**没有 CSV、没有生成器、没有运行时加载**。

**来源标注完全由字段拼出，Java 不做任何推断：**

```
来源标注 = 章节展示名 + "–" + quote
    章节展示名 = section（多文件带「报告N-」前缀，fileIndex 由 page 查表得到）
    「第N条」   = itemNo 非 null 时用它；【为 null 时不写条号，不拿数组下标顶替】
排序     = recommend / reject 各自的数组顺序；多文件先按 page 收敛
```

> **`quote` 与 `rawText` 之间不做包含性校验。** 一条原文拆出多个枚举时各条 `quote`
> 各摘各的那一段，不可能都是连续子串（实测「减少酒精和高果糖饮料」→「减少高果糖饮料」）。
> 代价是没有机制能发现编造的建议原文，已登记进设计方案 §11。

**结构化准入政策（2026-08-28 重构，设计方案 §7.3）：**

```
// safety.HighRiskAdviceGate —— 唯一剩下的一层，不可被模型推翻，只扫 quote
低蛋白 / 限蛋白 / 优质低蛋白 / 低钾 / 限钾 / 低磷 / 限磷 / 低碘 / 限碘 / 忌碘 / 高碘

【扫描对象是 quote 不是 rawText】模型摘出的建议本身那一句，上限 100 字
【人群裸词不在词表里】妊娠 / 孕期 / 哺乳期 / 儿童 —— 它们不是限制表述
```

```
命中 → structuredOutputSuppressed = true，该条按 OTHER 路径处理
       只展示报告原文与来源，不生成食材清单、不参与菜品匹配、不进入打标维度
   【不覆写 enumKey】enumKey 是体检报告分析模型的归一化结论，改它就是替模型下另一个结论
```

这道闸只会让系统**少输出内容**，永远不会让它输出一个不同的医疗语义——这是它合法的原因。

> **模型侧的那一层已删除。** 旧契约里的 `applicability` / `structuredSafety`
> 两个枚举不再存在（§6 契约）。后果是「建议家属同查」「孕妇和14岁以下儿童除外」
> 这类**指向不是受检者**的文本，现在只靠提示词的抽取范围规则拦截，**没有机械兜底**。
> 设计方案 §7.3 已登记为已接受风险；`HighRiskAdviceGate` 的类注释里必须写明这一点。

> **【作用范围仅限营养补充与饮食注意，不含过敏原】**（2026-08-26 产品确认）
> 过敏忌口本身就是要展示给用户的安全信息，用人群词连带抑制忌口清单，方向反了。
> 过敏原条目的 `structuredOutputSuppressed` 恒为 `false`。

**`OTHER` 的处理**（含被安全闸抑制的条目）：

```
照常展示该条建议的报告原文与来源标注
不加任何说明文字（产品决策）
不生成食材清单、不参与菜品匹配、不进入打标维度
```

> 这不满足需求 §7-3（要求每条建议都列食材/摄入量/搭配建议），**产品已确认接受该降级**。

**展示形态：两个食材清单**（设计方案 §7.6，产品确认）

```java
// DietAdviceAssembler
Set<String> nutritionSet     = union(recommend, NUTRITION, c -> c.recommendIngredients);
Set<String> dietRecommendSet = union(recommend, DIET,      c -> c.recommendIngredients);
Set<String> allergenSet      = union(reject,    ALLERGEN,  g -> g.avoidIngredients + g.hiddenFoods);
Set<String> dietAvoidSet     = union(reject,    DIET,      c -> c.avoidIngredients);

List<String> avoidList     = ordered(allergenSet, dietAvoidSet);
List<String> recommendList = ordered(nutritionSet, dietRecommendSet);
recommendList.removeIf(food -> hitsAny(food, avoidList));   // 【最后统一减一次】
```

**三条硬约束：**

```
① 差集放在最后，不在各维度内部做
   报告同时说「补铁」和「低脂饮食」时，猪肝在 nutritionSet 也在 dietAvoidSet；
   只在营养补充维度内部判断的话，另一个维度的禁忌会漏掉

② 差集用宽松匹配，不用 displayName 精确相等
   命中判据：适宜多吃里的食材【包含】忌吃少吃侧任意一个 matchWord
   过敏原「虾蟹类」的 matchWord 含「虾」→「虾仁」「基围虾」全部移除
   误判代价不对称：少推荐一条只是少条信息，把过敏原推给用户是一级红线（§0-6），宁可多减

③ hiddenFoods 必须并进忌吃少吃
   虾丸、蟹棒、XO 酱：需求 §7-3 要求展示「易忽略的含该过敏原的常见食物」，
   两个清单形态下它们没有别的地方可去
```

**`OTHER` 与被安全闸抑制的条目不产生任何食材**，因此在本形态下不可见
——它们仍在结果对象里下发（带 `quote` / `section`），前端当前不渲染。
**`OTHER` 条数进埋点**，占比异常时重新评估本形态。

**空态**（两个清单各自独立）：


| 清单 | 文案 |
|---|---|
| 适宜多吃 | 本次报告未提取到明确的饮食推荐内容 |
| 忌吃少吃 | 本次报告未提取到需要避免的食材 |

**「适宜多吃」被差集减空与「本来就没有推荐」走同一句文案**，不解释原因。

> **主语必须是「我们没提取到」，不是「报告未涉及」**——后者是在陈述报告的内容，而我们只知道自己没提取到。过敏这一条尤其不能反过来说。

**全模块不出现任何提示、说明或警示文字**，只有报告原文、来源标注、已收录枚举的食材内容、
上表空态句和底部声明。

**底部声明**：`以上建议均基于体检报告原文，不构成医疗或营养处方，具体饮食方案请遵医嘱。`

### 7.5 模块四：Java 根据第三次体检报告分析结果中的标签生成食堂菜品推荐

#### 7.5.1 数据前提

```
ct_dish             company_id、dishes_id、dish_name、on_shelf、biz_date
ct_dish_ingredient  company_id、dishes_id、ingredient_name、weight_g
【只读，不写，不改结构】实际表名接入时核对；企业与菜品ID列固定为 company_id、dishes_id
```

只有凌晨任务可以读取这两张表。在线链路从任务记录取得创建时固化的 `companyId`，
只读取 `dish:recommend:{companyId:bizDate}:...` 集合；禁止在线调用 `DishQueryService` 或回源标签表。
第三次体检报告分析调用不接收这些集合、菜品 ID、菜名或菜品标签；Java 只在第三次输出校验通过后，
用其中的标准化标签选择 Redis 集合。

> **⚠️ `ct_dish_ingredient` 里没有调味料**（已确认）。油、盐、糖、酱油、醋、料酒、蚝油、
> 香油、豆瓣酱、沙拉酱、XO酱、鸡精、淀粉一概不入表。
>
> **全案禁止「食材表里没有 X 所以判 NEUTRAL」的推理**——它只能推出「主料配料里没有 X」。
> 这条要写进菜品离线打标提示词，也要写进 Java 兜底的注释里。

#### 7.5.2 三个维度的判定方式

| 维度 | 枚举数 | 判定方 |
|---|---|---|
| 食入性过敏原 | 13 | 凌晨离线打标，只发布不推荐集合 |
| 营养补充 | 9 | 凌晨 Java 确定性交集打标，只发布推荐集合 |
| 饮食注意 | 9 | 凌晨离线发布 9 个不推荐集合；仅低嘌呤、高纤维按主料确证后发布推荐集合 |
| 吸入性过敏原 | 5 | **不参与** |

菜品离线打标模型实际处理 13 + 9 = **22** 个维度，只输出 `REJECT / UNKNOWN / NEUTRAL`；低嘌呤、高纤维
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

该逻辑由凌晨 `DietPositiveMatcher` 执行。其余 7 个饮食枚举即使菜品离线打标模型认为“看起来符合”，也不得
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
> **一个都硬匹配不到**；菜品离线打标模型也不能猜成实际配方，缺少明确证据时必须给 `UNKNOWN`，
> 由凌晨完整性门槛阻止该菜进入正向集合。
> 因此词表必须收录调味料在**菜名**里的写法，且 `MOLLUSK` / `SESAME` 是上线阻断项（§0.4）。

**已知代价：过杀。**「鱼香肉丝」在鱼过敏时会被误标，主动接受。
误杀集中在少数词时加一个 ≤20 条的例外词典（`AllergenExceptions`，已存在）。

#### 7.5.6 非食入性过敏原与枚举外过敏原

```
五个非食入性正式枚举保留在模块三，但不进入 33 个 Redis 集合
OTHER 无论食入性与否都没有稳定的离线标签维度，也不进入 33 个 Redis 集合
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

#### 7.5.8 按第三次标签生成菜品推荐

```text
positiveSet  = SUNION(第三次正向标签对应的 diet:recommend 与 nutrition:recommend 集合)
rejectSet    = SUNION(第三次反向标签中 13 个食入性正式过敏原的 allergen:reject 与 diet:reject 集合)
recommended = positiveSet - rejectSet
rejected    = rejectSet
```

集合计算必须按当前任务的 `companyId + bizDate` 执行，禁止混入其他企业 Key。
Java 的唯一裁决点依次执行：

```
只用已通过 Schema/枚举/方向校验且存在正式方向集合的第三次标签拼 Redis Key
五个非食入性过敏原与 OTHER 只进模块三，不得拼 Redis Key
一道菜同时命中正反集合 → 只保留不推荐
进入不推荐列表的菜 → 移除所有正向标签与推荐理由
按菜名拼音首字母稳定排序，两列表各取前 3
```

只存在过敏原时 `positiveSet` 为空，但过敏命中菜仍进入 `rejected`；
未命中任何标签的普通菜不进入任何列表。

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

**推荐理由由 Java 通过命中的标签查回第三次响应里同一标签的报告原文，
不接受模型自由文本，不拼命中食材：**

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

## 8. 菜品离线打标模型链路

**在线链路对菜品离线打标模型的调用次数必须为 0，对标签的写入次数也必须为 0。**
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
③ 当前页内按模型批量上限继续拆批：菜品离线打标模型完成 13 个过敏 reject 与 9 个饮食 reject 安全判定；
   Java 完成 LOW_PURINE、HIGH_FIBER 两个饮食 recommend 和 9 个营养 recommend，
   并增量写入该企业当天的 33 个 staging SET
④ 菜品离线打标模型的 22 个维度结果仍可按 companyId 写 MySQL 供离线排障与增量判断；
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

**成本按企业实际菜品量计算**：数据库分页大小与菜品离线打标模型每次最多 40 道菜是两个独立参数；
这里的 40 是模型调用批量，**Redis 方向集合数仍固定为 33**。
改提示词/词表/模型会让全部 `tagHash` 变化，触发按企业全量重打，需在发版计划里预留窗口。

### 8.2 菜品离线打标模型契约与校验

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
（菜品离线打标模型是全案唯一允许记录完整请求与响应的链路，§13.2.7），以及<b>归入量超过 20%
即整批作废</b>（且至少允许 1 道，避免小批次修一道就作废）——大比例出问题说明这一批
整体跑偏，不是个别抖动，那时候放行会得到一份大面积 UNKNOWN 的快照。

**`NEUTRAL` 与 `UNKNOWN` 的语义差别必须在提示词里讲清**（`prompt/dish_tag.md` 已同步）：

```
REJECT    菜名、食材或标准产品名称提供明确或高可信成分证据
UNKNOWN   数据不足判不出 —— 食材表没调味料、菜名看不出、做法不确定
NEUTRAL   有完整配方或调味料标签，能确认不含
```

菜品离线打标模型契约只有三态。饮食正向由凌晨 `DietPositiveMatcher` 根据主料确定，且只接受
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
当天集合；只选择第三次体检报告分析结果中已校验且存在正式方向集合的标签对应 Key，不需要全集。
五个非食入性过敏原与 `OTHER` 在映射前过滤，只保留模块三展示。
这些集合直接用于 Java 组装最终模块四，不进入任何体检报告分析模型请求。

```text
positiveSet  = SUNION(第三次正向标签对应的 diet:recommend, nutrition:recommend)
rejectSet    = SUNION(第三次反向标签中 13 个食入性正式过敏原的 allergen:reject, diet:reject)
recommended = positiveSet - rejectSet
rejected    = rejectSet
```

当前实现把全部相关方向的 `SMEMBERS` 命令一次入 pipeline，以一次网络往返取回后在 Java 做并集、差集和标签归属恢复；
不得按维度串行往返，更不得逐菜访问 Redis。冲突裁决后按菜名拼音首字母排序，推荐与不推荐各取前 3。
正向 Key 列表为空时直接使用空 `Set`，排除 Key 列表为空时同理；不得调用零参数
`SUNION`，也不得为此引入 `all` 或占位 Key。

在线禁止调用 `DishQueryService`、查询菜品/食材表、计算主料、计算 `tagHash`、回源 `ct_dish_tag`
或调用菜品离线打标模型。当天 Key 缺失时返回空态并告警，不读取前一天数据。

职责拆分固定如下，避免重新长回一个在线大编排类：

| 类 | 职责 |
|---|---|
| `DishTagSnapshotBuildService` | 按企业驱动数据库分页、当前页打标与完整性计数 |
| `DietPositiveMatcher` | 凌晨仅对 `LOW_PURINE`、`HIGH_FIBER` 做主料交集并受菜品离线打标结论约束 |
| `DishTagSetPublisher` | staging SET 增量写入、33 个方向校验后的 Lua 原子发布与 TTL |
| `DishRecommendSetKeyFactory` | 企业编码、日期和标签方向 Key 的唯一生成点 |
| `DishSetMemberCodec` | `dishId\tdishName` 唯一编解码点 |
| `DishRecommendSetService` | 按已校验用户标签批量取集合，在线做并集、差集、冲突裁决和标签归属恢复 |
| `DishRecommendAssembler` | 关联第三次响应的原文理由，按拼音稳定排序、各取前 3 并组装 DTO |

`DishRecommendSetService` / `DishRecommendAssembler` 及其在线依赖包不得依赖 `DishQueryService`、`DishTagModelClient`、
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
| `FILE_TOO_LARGE` | 400 | §4.1 上传：**字节超限**（PDF/OFD ≤ 20MB；JPG/PNG ≤ 10MB）**或图片总像素超限**（§5.1，用 `ImageReader` 只读尺寸即可判，不整幅解码） | — |
| `FILE_UNREADABLE` | 400 | §5.1 可读性 | — |
| `MALFORMED_REQUEST` | 400 | §4.1 上传：multipart 报文本身无法解析（边界符损坏、`Content-Type` 与实际内容不符等）。**与字节超限区分**——超限是 `FILE_TOO_LARGE`，让用户压缩文件重试有意义；报文畸形压多小都没用，必须让前端知道是请求构造错了 | — |
| `FILE_ALREADY_BOUND` | 409 | §4.2 绑定（**必须回已绑定的 taskId**） | — |
| `FILE_EXPIRED` | 409 | §4.2 绑定时 `file.expire_at <= now`（上传后 30 分钟未提交） | — |
| `PAGE_LIMIT_EXCEEDED` | 400 | §4.1.1 上传预筛或 §4.2 创建预筛，全部同步（Word 移除后无异步路径） | — |
| `TASK_NOT_FINISHED` | 409 | §4.1 result 接口在任务未到 `SUCCEEDED` 时被调用 | — |
| `NOT_HEALTH_REPORT` | — | §6.5 任一阶段返回 `NO_REPORT_FEATURE` | 0 |
| `UNREADABLE` | — | §5 页面渲染失败，或 §6.5 任一阶段返回 `UNREADABLE` | 0 |
| `IMAGE_TOO_LARGE` | — | `ExtractionImageCompressor` 两档压缩后仍超单图上限；唯一映射点是 `FileToImageService` | 0 |
| `IDENTITY_MISMATCH` | — | §6.2 调用一校验后的多文件同一性校验 | 0 |
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
普通应用 logger 绝不记录：报告原文、页面图、证据文本、姓名、原始过敏或医嘱文本、
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

schemaMissCount / evidenceMissCount /
allergenSuspectMissCount / allergenPositiveUncoveredCount / allergenUnknownCount /
adviceOtherCount / sectionRefMissCount / sectionUnknownCount /
highRiskSuppressedCount / glyphLevelPdfCount / residualNonStandardCount /
statusJudgedByModelCount
        —— 2026-08-27 因只写不读整体下线
```

**下线理由**：这些计数每个都有自增点，却**没有任何读取点**——
既不进日志、也没有导出方式，`src/main` 里连一次 getter 调用都没有，只有单测在读。
读不到的计数不提供信息，只增加一处并发状态与一处维护负担。

随之删除的还有单次调用的 `residualNonStandardCount` 传递链：
`TextNormalizationResult` 只保留 `normalizedText`，
相关解析结果类已随 §5 的 Segment 链路整体删除，此条自然失效。

> **注意区分：`DegradeAccumulator` 不是计数器，不在下线之列。**
> 它记录 `SCHEMA_ITEM_DROPPED` / `DIET_TAG_DROPPED`，直接决定任务的
> `partial`、`partial_reason` 以及是否抑制模块四，**影响输出**。
> §7.4 安全闸的抑制行为保留，只是不再计数；密度闸已随 §5 整体删除。
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
| `IngredientAliasWords` | §7.5.3 食材别名 | 新增，工程侧，不需医学审核 |
| `DisclaimerConstants` / `EmptyStateConstants` | 四个模块的声明与空态文案 | 新增，工程侧 |
| `SystemActor` | `create_by` / `update_by` 取值 | 新增，工程侧 |
| `PromptVersions` | 三份在线体检报告分析与一份菜品离线打标提示词版本，各自独立 | 新增，见 §9.4.1 |

#### 9.4.1 提示词版本与模型版本的真源

三份在线体检报告分析 Prompt 与菜品离线打标 Prompt 各自演进，不能共用一个版本常量。
`tagHash` 仅使用离线 `DISH_TAG`；三个在线版本用于结果追溯和发布契约，不进菜品标签哈希。

```java
// constants.PromptVersions —— 新增常量类
public final class PromptVersions {
    /** 健康指标提示词版本，必须与生产文件头和摘要历史一致。 */
    public static final String INDICATORS = "indicators-x.y.z";
    /** 健康问题提示词版本，必须与生产文件头和摘要历史一致。 */
    public static final String PROBLEMS = "problems-x.y.z";
    /** 饮食建议与标签提示词版本，必须与生产文件头和摘要历史一致。 */
    public static final String DIET_TAGS = "diet-tags-x.y.z";
    /** 菜品打标提示词版本，必须与 prompt/dish_tag.md 头部和摘要历史一致 */
    public static final String DISH_TAG = "dishtag-x.y.z";
    private PromptVersions() { }
}
```

**`modelVersion` 不做成常量类，它来自配置。**

```
理由：换模型是【运维动作】，不该要求改代码重新发版；
     而且同一份代码可能在不同环境跑不同模型（灰度、压测）
落地：配置项 `llm.extraction.model` / `llm.model-version-dishtag`
     ——【这不是「中间件配置」】，AGENTS.md §5 禁的是数据源/Redis/MyBatis 配置类，
       业务参数照常走配置
     体检报告分析模型直连（§6.4），`llm.extraction.model` 就是请求里的模型标识；
     菜品离线打标模型直连，`llm.model-version-dishtag` 就是请求里的 model，【没有第二处真源可对不上】

⚠️ 代价必须写明：modelVersion 走配置意味着【改配置就会让全部 tagHash 变化】，
   触发一次全量重打标（§8.4）。因此它必须与发布流程绑定：
   改这个值等同于一次内容变更，要预留凌晨重打窗口，不能随手改。
```

**三个版本的一致性由三条测试锁住，缺一条就有一类漂移拦不住：**

```
R55   【已撤销】原为「Dify DSL 里的版本号与 Java 常量/配置一致」。菜品离线打标改直连后
      DSL 不存在，版本号只有 Java 一处真源，本条无对象可断言（§13.2.0）
      体检报告分析模型直连不产出 DSL，它的版本对齐只由 R55a 保证
R55a  PromptVersions 的 INDICATORS / PROBLEMS / DIET_TAGS / DISH_TAG
      与四份对应生产 Prompt 头部版本逐字一致
R55b  【版本-摘要基线历史】prompt/versions.tsv 的四条断言 —— 见 §9.4.2。
      三份在线体检报告分析契约在批次 5 验收，菜品离线打标契约在批次 7 验收
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
prompt/versions.tsv                追加式，一行一个版本，每份生产 Prompt 各自维护历史
    indicators-1.0.0  <sha256(初版正文)>
    indicators-1.1.0  <sha256(indicators.md 正文)>    ← 2026-09-03 性别归一化（男/女/null 三值）后追加
    problems-1.0.0    <sha256(health-problems.md 正文)>
    diet-tags-1.0.0   <sha256(初版正文)>
    diet-tags-1.1.0   <sha256(diet-tags.md 正文)>    ← 2026-09-03 收编非食入性过敏原后追加
    dishtag-2.2.1  <sha256(dish_tag.md 正文)>

constants/tag-rule-versions.tsv    同构，对象换成内容常量
    tag-0.1.0-DRAFT  <sha256(全部内容常量的结构化序列化)>
    tag-1.0.0        d260666e595954828fa99ab8f87c3183d612cae2c42ea8226a6c184e6c6106c7
    tag-1.1.0        33f8773bbd3b9277e5bffce9e9de4db379bde3d4e63da60705dd3a421c40e40f

R55b / R55c 各断言四条：
    ① 每个前缀的末行 version == 对应 PromptVersions 常量；内容常量则对应 TagRuleVersion.VALUE
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

> `AGENTS.md` §5 已同步：只有三个占位符。`DifyClient` 已删除；
> `HealthReportAnalysisModelClient` 有完整的 OpenAI 兼容协议实现，不是占位符。
> OCR 已退出在线链路，不再定义或保留 `PaddleOcrClient`。
> **本节与 `AGENTS.md` §5 必须一致，改一处就改另一处。**

只写接口 + `TODO` 空实现，抛 `UnsupportedOperationException`，**绝不写假数据返回**
——假数据会让上层测试通过而掩盖未实现。

```java
infra.S3FileStorage          对象存储读写删

【infra.HealthReportAnalysisModelClient 不在占位符之列 —— 它有完整实现，见 §6.4】
    协议按 OpenAI 兼容写死；换服务商主要改 buildRequestBody / extractContent，
    鉴权头与 endpoint 也可能一起变（§6.4）
    base-url / model / apiKey 走配置（HealthReportAnalysisModelProperties），【是部署参数不是代码问题】
    因此它可以先写完并用 WireMock 跑通全部红线测试（R57~R65），
    只有【真实端到端联调】需要等接入方给出凭据（§6.4 的三个 ⛔ 项）
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
页面图 / 模型原始响应落库    ← 与 §3.1 和数据生命周期直接冲突，
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
| **R2** | 构造模型 `conclusionGenerated=false`、`status=NORMAL`，但 `value` 原文含 `↑` | Java 原样保留 `status=NORMAL`，不存在箭头词表纠正；该语义错误由离线评测拦截 |
| **R3** | 阶段二返回一条 `name` 可在 `rawText` 中找到、但语义上属于正常表述的问题 | Java 不用正常语句词表改写或剔除；该准入错误由阶段二评测拦截 |
| **R4** | 捕获阶段二、三请求 DTO | 类型上无法设置阶段一/二结果或菜品字段；序列化结果只含通用文本与完整页面图 |
| **R5** | 阶段三返回食入性枚举外过敏原 `enumKey=OTHER` | 模块三展示原文；模块四不为该条拼 Redis Key，Java 不猜对应食材 |
| **R6** | ArchUnit 扫描 `parse` 包 | 不存在 `PDFTextStripper` 引用；不存在按坐标聚类的方法 |

#### 安全红线

| # | 用例 | 断言 |
|---|---|---|
| **R7** | 阶段三有一个可定位的非法标签，剔除比例仍在 20% 预算内 | `partial=true / DIET_TAG_DROPPED`；模块三保留其余合法条目，模块四整体不输出 |
| **R8** | 阶段三非法标签超过 20% 修复预算 | 阶段三失败，整任务 `FAILED/SERVER_ERROR`，不写任何部分结果 |
| **R9** | 第三次返回过敏原 `enumKey` 不属于 `ALLERGEN` 维度 | 该条剔除并进入阶段修复预算，不得用它拼 Redis Key |
| **R10** | 第三次输出中出现 `dishId` / `dishName` / `dishRecommendations` | Schema 拒绝；证明菜品数据与菜品选择均不属于体检报告分析模型契约 |
| **R11** | 阶段三样本含尘螨、花粉、动物皮屑、霉菌或蟑螂阳性 | 对应正式标签保留在 `reject` 并进入模块三；Java 不为它们拼 Redis Key，模块四不受其影响 |
| **R12** | 阶段三评测样本的食物过敏原结果为阴性或未检出 | 不产生 ALLERGEN 条目；弱阳性、可疑、临界样本另测为保守收录 |
| **R13** | 某企业分页完成数少于目标数，或任一标签维度覆盖不完整 | 该企业当天 33 个正式 SET 全部不发布；在线不回源、不读昨天 |
| **R14** | 遍历 `AllergenGroups` 全部组 | `avoidIngredients ∩ hiddenFoods = ∅`；两者 `matchWord` 并集 == 该 key 全部 `matchWord` |
| **R15** | 高危表述「低蛋白饮食」映射到 `PROTEIN` | `structuredOutputSuppressed=true`、走 `OTHER` 路径、**`enumKey` 原值保留未被覆写** |
| **R15a** | 常规建议「低脂、低糖饮食」 | 正常进入结构化链路 |
| **R15b** | 同段落里含「儿童」但 `quote` 只摘了「低脂、低糖饮食」 | **不得抑制**——F3 的误杀现场，词表不含人群裸词且只扫 `quote` |
| **R15c** | 科普段落里的饮食表述（「血尿酸长期增高可致痛风」邻近的「低嘌呤饮食」） | **已知盲区，不抑制**——模型侧的 `applicability` 已删除，只靠提示词抽取范围拦截（§7.4） |
| **R15f** | `quote` 含「优质低蛋白」 | 抑制；词表兜底不可被模型推翻 |
| **R15g** | `quote` 缺失或为空 | 该条建议**整条丢弃**，不进任何模块 |
| **R15h** | 原文「建议低蛋白、低脂饮食」，模型摘成「低脂饮食」 | **不抑制**——`quote` 里没有高危词。这是删除模型侧那一层之后的已知盲区，与 R15c 同源 |
| **R15j** | 原文整块含「儿童除外」，`quote` 是「低脂、低糖饮食」 | **不抑制**——只扫 `quote`，不扫 `rawText` |
| **R16** | 过敏维度 `REJECT` 的菜同时有营养 `RECOMMEND` | 只下发过敏标签，**无任何正面标签**（灰色附注也不行） |
| **R16a** | `LOW_PURINE` 离线安全判定为 `NEUTRAL`、主料命中低嘌呤白名单 | 凌晨把复合成员写入 `diet:recommend:LOW_PURINE`；在线理由只返回报告原文，不含命中主料 |
| **R16b** | 同一维度离线判 `REJECT` / `UNKNOWN`，主料仍命中白名单 | `REJECT` 只进饮食不推荐 SET；`UNKNOWN` 不得进入正向 SET |
| **R16c** | 遍历 9 个饮食注意维度的离线输出 | 9 个维度都允许 `REJECT`；只有 `LOW_PURINE`、`HIGH_FIBER` 可由凌晨 `DietPositiveMatcher` 产出 `RECOMMEND`，且同一道菜不得同时进入正反集合；其余 7 个维度不存在 recommend Key |
| **R16c1** | `LOW_FAT` 安全判定为 `NEUTRAL`，菜品主料命中低脂推荐食材 | 不写 `diet:recommend:LOW_FAT`，在线不得出现“低脂”推荐标签；执行时点改为离线不能扩大需求正向范围 |
| **R16d** | 报告同时给出「高纤维饮食」与「补充膳食纤维」，菜品同时命中两个推荐 SET | 正面标签按枚举文案去重；推荐理由直接取并去重报告原文，不读取食材 |
| **R16e** | 菜品离线打标模型在任一维度返回 `recommendDishIds` 或 `RECOMMEND` | Schema 或契约拒绝整批，不写库、不写 staging SET；正向结果只能由 Java 匹配器产生 |
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
| **R18a** | 造 `last_seen_date` 为 8 天前与 6 天前的行，跑清理 | 只删前者（保留期 7 天，§8.1、设计方案 §8.9）；且清理必须在打标任务**之后**执行（先删后打会误删当天要用的行） |
| **R18b** | 直接调 `tagService.run(d)` 与 `cleanupService.run(d)` 并传入固定日期 `d` | 企业/菜品分页、Redis `{companyId:bizDate}` Key、`last_seen_date` 与清理比较全部使用入参；除调度入口外不出现 `LocalDate.now()` / `CURRENT_DATE` / `now()` |
| **R18c** | xxl-job Handler 注册数 | `dish` 包内只有**一个** `@XxlJob` Handler，打标与清理是它的两步（防止有人拆成两个 Handler 后 `bizDate` 又分叉） |
| **R18d** | 两个企业存在相同 dishId、菜名和标签 | Redis Key 必须不同；企业 A 用户只能得到 A 集合结果，任何 B 成员进入即测试失败 |
| **R18e** | 一个企业有 1201 道按 `dishes_id` 递增的在架菜，查询页容量 500 | 首批输入 `lastDishesId=null`；三批分别返回本批最后一条的 `dishes_id`，下一批原样使用上批返回值；菜品主表恰好查询 3 页，游标严格前进、无重复无遗漏；每页只执行一次食材批量查询，无逐菜查询 |
| **R18f** | 菜品一页中每道菜有多条食材 | 分页基于菜品主表而非 JOIN 行，页大小按菜品数计算；食材行数不改变页边界 |
| **R18g** | 第 2 批返回其他企业菜品、`lastDishesId` 不等于本批最后一条 `dishes_id`、游标未前进或元素为 `null` | 构建立即失败，该企业正式 SET 不发布，其他企业已完成快照不受影响 |
| **R18h** | 33 个 staging SET 中有空集合和非空集合 | Lua 原子发布后空集合对应正式 Key 不存在、非空集合全部替换成功；并发在线读取只能看到发布前或发布后的完整企业快照 |
| **R18i** | 企业分页前 `COUNT=1201`、处理 1201 道、分页后 `COUNT=1202` | 判定菜单构建期间发生漂移，该企业当天不发布 |
| **R18j** | 一个企业恰有 1000 道在架菜，查询批容量 500 | 前两批各返回最后一条 `dishes_id`；第三批返回空 `dishList` 与 `lastDishesId=null` 后结束，不得重查上一批或循环不止 |
| **R19** | `page` 越界（0 / 负数 / 大于图序列长度） | 该条目丢弃，行为符合 §6.5 |
| **R20** | 指标章节的 `page` 越界，或同页同名章节重复 | 越界章节丢弃；重复章节按首次出现顺序合并，其他章节不受影响 |
| **R21** | 指标条目 `conclusionGenerated=true`，但 `refRange=null` 或 `status!=NORMAL` | 该指标整条丢弃；Java 不补造报告结论 |
| **R21a** | 构造「有数值无结论**且无参考值**」的指标行 | **不出现在任何模块**；Java 不得为对齐总览数字把它补回来（§6.3.1） |
| **R21b** | 模型返回 `conclusionGenerated=true`、`status=NORMAL` 且 `refRange` 非空 | 进模块一；Java 只展示模型结构化结果，不二次解析区间或改写 `status` |
| **R21c** | 评测输入为「有数值、有参考值、无报告结论且结果超范围」 | Prompt 评测要求该指标不输出；Java 不得自行改判为 `HIGH`/`LOW` |
| **R21d** | 评测样例中模型把超范围结果标成 `conclusionGenerated=true` | 评测失败并阻止 Prompt/Schema 发版；在线 Java 不通过猜测区间修正医疗语义 |
| **R21e** | 任一在线响应出现 Schema 未声明字段 | `additionalProperties=false` 拦截；按条目修复预算处理，超预算则该阶段失败 |
| **R21f** | 必填短字符串为 `""` 或全空白 | 结构校验剔除该条目并计入修复预算，空白内容不得进入展示 |
| **R21g** | 健康问题 `sourceType=INDICATOR` 但 `indicatorName=null` | 该条目丢弃；不得自动改成 `SUMMARY` |
| **R21h** | 饮食标签 `recommend` 中出现过敏原标签，或 `reject` 中出现营养方向标签 | 方向校验剔除非法条目并计入修复预算；非法条目不得查询 Redis |
| **R21i** | 同一正式 `enumKey` 同时出现在 `recommend` 与 `reject` | 拒绝优先：只保留到 `reject`，最终候选做差集时也必须排除 |
| **R21j** | 某阶段被修复或剔除条目比例超过 20%（至少允许 1 条） | 该阶段失败，任务 `FAILED / SERVER_ERROR`，不调用后续阶段 |
| **R21k** | 某阶段返回非 JSON、顶层字段缺失或 `reportStatus` 非法 | 该阶段整体失败，任务不返回部分分析结果 |
| **R21l** | 阶段二 `sourceType=INDICATOR` 的条目无法匹配阶段一异常指标 | **条目保留**，仅不下发 `indicatorId` 跳转按钮（设计方案 §6.2）。准入完全由阶段二模型判定，Java 不得因跨调用匹配失败丢弃条目——两次调用相互独立，阶段一可能在修复预算内恰好剔掉了该指标 |
| **R21m** | 捕获阶段二、阶段三的实际请求体 | 都只包含相同的完整有序页面图与各自 Prompt/Schema，不含任何前序阶段响应 |
| **R21n** | 阶段三只有 `reject` 标签 | Java 只计算排除集合，推荐集合为空，不向体检报告分析模型请求补充菜品 |
| **R21o** | 阶段三 `recommend=[]` 且 `reject=[]` | 模块三、模块四按空态返回；禁止零参数 `SUNION`，也不得默认推荐全量菜品 |
| **R21p** | 饮食标签 `enumKey` 不在正式枚举中 | Schema/枚举校验剔除该条目并计入修复预算，Java 不做近义词猜测 |
| **R22** | 菜品离线打标模型返回缺一个 `dishId` / 多一个 / 列表有交集 / 同列表内重复 | 按 §8.2 修复:缺失与跨列表相交归入 `UNKNOWN`、同列表内重复只去重不改判、非本批 `dishId` 丢弃;**修复后覆盖必须精确成立**。修复量超 20%(至少允许 1 道)或违规定位不到某道菜 → 整批作废,不写库、不重试(2026-09-02 前为一律整批作废) |
| **R23** | 用 `schema/*.json` 校验文档 §4.2 与 §8.2 的示例 | 全部 PASS；三份在线 Schema 和一份离线 Schema 自身都通过 `check_schema` |
| **R24** | 生产指标响应中的临时患者条目 `name`、`gender` 均为空，或 `page` 越界 | 丢弃该患者条目，不进入最终结果、缓存或日志 |
| **R25** | 两个文件都给出明确患者身份但互相冲突；另测某文件身份缺失 | 明确冲突时 `IDENTITY_MISMATCH`；缺失时不猜测、不因缺失误报冲突 |

#### 排序与展示

| # | 用例 | 断言 |
|---|---|---|
| **R26** | 三阶段数组均按报告阅读顺序返回，且契约中没有任何序号字段 | 模块一至三保持数组顺序，不根据名称或状态二次排序 |
| **R27** | 同名章节在 `sections` 里出现两次 | 保留第一条、其余并入；页面上仍是一张卡片（§6.5） |
| **R28** | 两个文件的阶段结果都从各自第一页开始 | `page` 通过全局映射确定文件归属，不允许跨文件继承章节或身份 |
| **R29** | 跨页续表在 `sections` 中合并为一个章节，`page` 是章节开始页 | 保留为单一分组，续页不另起同名卡片 |
| **R30** | 两份报告都有「血脂检查」 | 按设计方案 §5.3 章节唯一性处理：**保留第一条、其余并入同一分组**，展示名取首次出现章节的 `page` 归属（与 R27 同一条规则，不区分是否跨文件） |
| **R31** | 健康问题 `name="甘油三酯 ↑"` 且能在 `rawText` 中找到 | `displayName` 直接使用 `name`，Java 不拼「偏高」等归一化措辞 |
| **R32** | 饮食标签 `itemNo = null` | 来源标注**不写条号**，不拿数组下标顶替 |
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

#### 转图、容量与三次调用

| # | 用例 | 断言 |
|---|---|---|
| **R42** | PDF / OFD / 图片混合任务转图 | 按 `fileIndex + pageInFile` 得到唯一完整 `PageImageSequence`，不抽文本、不调 OCR |
| ~~**R43**~~ | **已撤销**（2026-09-03） | 页数全部精确后，面向用户的容量超限只在创建时同步发生，由 R43b 覆盖；Worker 只做对象与任务快照完整性复核（§4.1.1/§4.1.2） |
| **R43a** | 精确 `totalPages` = 30 | 全部 30 页进入同一图序列，`processedPages=totalPages=30`，不标记页数降级 |
| **R43b** | 上传文件的 `precheck_pages` 累计为 31 | analyze 直接拒绝 `PAGE_LIMIT_EXCEEDED`，**不建任务行、不绑文件** |
| **R43b1** | 分别上传 PDF / 图片 | `precheck_pages` 分别为真实页数 / 1 |
| **R43b12** | 分别上传 DOC 与 DOCX | 均返回 `UNSUPPORTED_FORMAT`，不落 file 行、不存对象；**DOCX 不得误判为损坏的 OFD**，OFD 正常通过（两者同为 ZIP 容器，§5.1） |
| **R43b13** | Worker 读回对象存储原文件 | 非空、长度、SHA-256、重新识别的真实格式、格式安全检查、可读性和精确页数必须逐项通过并与 file 行一致；任务快照仍满足文件数、连续 `file_index`、总字节数与总页数上限。任一漂移均 `FAILED/SERVER_ERROR`，三次体检报告分析调用数为 0 |
| ~~**R43b2~R43b7**~~ | **已撤销**（2026-09-03，含 R43b6/b6a/b6b） | Word 第一期不支持（§5.4），全部 Word 预筛/渲染/容量用例随之移除 |
| **R43b10** | 任一页无法解码或渲染 | 整任务 `FAILED/UNREADABLE`，三次体检报告分析调用数均为 0，不用其他页生成部分结果 |
| **R43b11** | 纯图片上传 / 扫描版 PDF 可正常渲染 | 不调 OCR，页面图直接进入三次体检报告分析调用 |
| **R43b8** | 多文件 `precheck_pages` 累计**恰好 30** | analyze **通过**，正常建任务；31 才拒（R43b） |
| **R43b9** | 单文件恰好 30 页 | 全部处理，`partial = false`；31 直接失败，不存在 `PAGE_TRUNCATED` |
| ~~**R43c**~~ | **已撤销**（2026-09-03） | 多文件累计超限在创建时同步拒绝，已由 R43b 覆盖 |
| **R43d** | 阶段 1/2 有预算内条目剔除 | `partial=true / SCHEMA_ITEM_DROPPED`；其余已校验结果继续组装 |
| **R43e** | 阶段 3 有预算内标签剔除 | `partial=true / DIET_TAG_DROPPED`、`suppressDishRecommend=true`；被剔除标签不得用于 Redis 查询，模块四整体不输出 |
| **R44** | 三次任一次返回 `UNREADABLE` | 整任务 `FAILED/UNREADABLE`，不写部分结果，不再发起后续调用 |
| **R45** | 任一必需阶段返回 `NO_REPORT_FEATURE` | `FAILED/NOT_HEALTH_REPORT`（**不是 `UNREADABLE`**），不写部分结果 |
| **R46** | 三次请求捕获图像段 | 页数、顺序、页码和 JPEG 字节全部一致，且不为某一次重新渲染或重新压缩 |
| **R47** | DOCX 解压炸弹 | 流式计数中断，不信 `getSize()` |

#### 数据生命周期与日志

| # | 用例 | 断言 |
|---|---|---|
| **R48** | 任务成功后检查 Redis `result:{taskId}` | **不含**姓名、性别、页面图、三次模型原始响应；只含四模块展示所需字段 |
| **R49** | 全流程日志捕获 | 不含报告原文、姓名、页面图、模型请求/响应正文、健康数据；`taskId` 不与上述内容同事件 |
| **R50** | `SUCCEEDED` 后跑清理 | 原文件与 file 行立即删；task 行保留至顺延后的 `expire_at` |
| **R51** | `FAILED` 且 `reanalyzable=1` 后跑清理 | 原文件**保留**至 `expire_at`（否则「重新解析」形同虚设） |
| **R52** | 任意实体的 insert / update | SQL 中**不出现** `create_time` / `update_time` |
| **R53** | 指标条目携带未声明的 `statusJudgedByModel` 字段 | `additionalProperties:false` 拦截并剔除该条、计入修复预算；Java 不新增箭头或结论词表去纠正 `status` |

#### 体检报告分析模型直连红线（§6.4）

| # | 用例 | 断言 |
|---|---|---|
| **R57** | ArchUnit 扫 `llm.extraction`、`llm.dishtag` 与 `infra` 包 | 两条模型链路各自只依赖自己的客户端接口：`llm.extraction` 不依赖 `DishTagModelClient`，`llm.dishtag` 不依赖 `HealthReportAnalysisModelClient`。`DifyClient` 已删除 |
| **R58** | 捕获三次体检报告分析请求体 | **不含** `taskId` / `userId` / `origin_name`；只含 §6.3 定义的字段，且 `chat_template_kwargs.enable_thinking=false` |
| **R59** | 全流程监控文件系统与对象存储调用 | 图像**只从 `byte[]` 内联**；**不创建临时文件、不调用 `S3FileStorage`** |
| **R60** | 构造全局页码 2、5、9 的 `PageImageSequence` | 消息序列严格「页码文本→图」成对；每对来自同一 `PageImage`，无漏图、无重复页 |
| **R61** | 打乱输入页序 / 缺一张图 / 重复一页 | 三种都**在组装前失败**，不得静默发出错配的请求（§6.2、§6.3） |
| **R62** | 模型返回 429 / 500 / 读超时 | 各自**调用次数恰好为 1**（零重试，§6.1）；映射成对应错误码 |
| **R63** | 上述失败场景的日志 | **不含请求体、响应体、模型响应正文**；`RestClientResponseException` **不得被直接记录**，只记状态码与耗时（§6.4） |
| **R64** | 应用启动后检查 HTTP 客户端配置 | Apache/OkHttp/JDK 的 **wire logging 与 debug 日志为关闭**；`RestTemplate` 未挂任何打印 body 的拦截器 |
| **R65** | 静态检查 `infra` 包 | **禁止** `HttpEntity<String>`、`ObjectMapper#writeValueAsString`、以及把整请求体拼进 `StringBuilder`/`String` 的写法（ArchUnit + 字节码扫描）。**不检查"有没有 String"**——`buildRequestBody` 本来就返回 `byte[]`，而逐图 base64 的临时 `String` 是合法的 |
| **R65p** | **性能测试，不进普通单测**（单独 profile / tag） | 固定 JDK 版本、固定 `-Xmx`、固定 8×800KB 样本，用 `ThreadMXBean#getThreadAllocatedBytes` 记录分配量基线并存档。**回归时与存档基线比对，超 30% 报警而非失败**——分配量随 JDK 小版本波动，做成硬断言会变成噪音 |
| **R65a** | 构造超过 `maxRequestBodyBytes` 的一批 | 抛 `RequestTooLargeException`，WireMock **收不到任何请求**；且**在写入过程中就抛**，不是等整个请求体生成完再判（断言 `CappedByteArrayOutputStream.size() <= maxBytes` 始终成立） |
| **R65b** | WireMock 返回超大响应体（**200 与 500 各一次**） | 两次都不把响应完整读进内存：200 走 `BoundedResponseExtractor`；**500 走 `StatusOnlyErrorHandler`，错误处理器不读取也不缓存正文**（框架关闭响应时仍可能对流做清理，这不算读取）——默认的 `DefaultResponseErrorHandler` 会把 body 读满并塞进异常，绕过上限。异常消息里**只有数字，无正文** |
| **R66** | 一张 3000×4000 的页面图走 `ExtractionImageCompressor` | 输出长边 = 2000px、JPEG、体积 ≤ 1MB；**全程不落盘**（监控文件系统调用） |
| **R66a** | 一张压缩后仍 > 1MB 的高噪图 | 自动回退档 2（长边 1600px、quality 0.80）；再超限抛 `ImageTooLargeException` → 任务 **`FAILED / IMAGE_TOO_LARGE`**、`reanalyzable = 0`。**断言既不是 `UNREADABLE` 也不是 `SERVER_ERROR`**，前端文案走「图片过大」那条 |
| **R66f** | 同一份内容分别以 PDF / OFD / 图片输入 | 都只产生有序 `PageImage`，不产生文本层、OCR 文本或 bbox |
| **R66b** | 上传一张 8000 万像素的 JPG | ① 请求体内的图 ≤ 1MB，不得直传原图；② Spy/Fake `ImageReader` 断言**读过完整尺寸后调用了 `setSourceSubsampling`**；③ 断言实际解出的 `BufferedImage` 宽×高 **≤ 受控上限**；④ ArchUnit：上传图片路径**禁止直接调 `ImageIO.read`**。**不用"峰值分配量"断言**——`ThreadMXBean` 只能看累计分配，证明不了单个对象大小 |
| **R66g** | 8000 万像素样本，**独立子进程 + 较小 `-Xmx`**（如 512m） | 不 OOM 且正常产出压缩图。**不进普通单测**，与 R65p 同属资源/性能测试 |
| **R66h** | 上传一张 **EXIF `Orientation = 6`** 的 JPG（宽高互换）| 转图层先做方向归一化，三次体检报告分析调用收到的 JPEG 方向一致且文字正向 |
| **R66h1** | **8 个 `Orientation` 值各一组**已知图像 | 纯本地确定性单测；逐个校验旋转/镜像后像素方位，`Orientation` = 5~8 时断言宽高互换 |
| **R66j** | 单页都合规，但全部页图组装后请求体超 `llm.extraction.max-request-body-bytes` | `HealthReportAnalysisModelClient` 在发送前抛 `RequestTooLargeException`，WireMock 收不到请求 |
| **R66k** | 启动时把 `llm.extraction.max-request-body-bytes` 配成 0 / 负数 / 小于协议开销 | **启动直接失败**，不得跑到第一次调用才报错 |
| **R66i** | PDF 页走 `ExtractionImageCompressor` | 输出符合长边、JPEG 质量和 1MiB 上限，与其他格式使用同一页图契约 |
| **R66c** | 档 2 回退后检查输出 | 实际长边为 1600px、JPEG q0.80、不超 1MiB，且三次请求复用同一字节 |
| ~~**R66d**~~ | **已撤销**（2026-09-03） | Word 第一期不支持（§5.4），渲染层收不到 Word |
| **R66e** | PDF 某页渲染失败（构造损坏页） | 整任务 `FAILED / UNREADABLE`；**不抛 `IllegalStateException`**，即不落到 `assertPageListValid`（§5.6.5） |
| **R65c** | WireMock 返回 200 + 畸形 JSON，正文含「甘油三酯 2.8 阳性」等敏感串 | 抛 `HealthReportAnalysisCallException`；**捕获全部日志断言不含该敏感串**——Jackson 解析异常消息会带出错位置附近的原文片段，`extractContent` 必须就地脱敏 |

#### 交付物一致性（§13）

| # | 用例 | 断言 |
|---|---|---|
| **R54** | 解析 `sql/schema.sql` 与本文 §3.1 的 DDL 代码块 | 列级八项（列名/类型/可空/DEFAULT/ON UPDATE/CHARACTER SET/COLLATE/COMMENT）+ 表级四项（ENGINE/CHARSET/COLLATE/表 COMMENT）+ 索引三要素（名称/列序列/顺序）逐一相等；`.sql` 里**不出现** `CONSTRAINT` / `FOREIGN KEY` / `CHECK` / `TRIGGER` |
| **R54a** | 空库跑 `schema.sql` vs 空库跑「初版 schema + 全部 alter」 | 两条路径得到的结构**完全一致**（同样比 R54 的十五项）。没有迁移工具，这是唯一能保证「新建库」与「老库升级」不分叉的手段 |
| ~~**R55**~~ | **已撤销**（2026-08-27） | 原为「解析菜品离线打标的 Dify DSL，断言其中的版本号与 Java 常量/配置一致」。改直连后 DSL 不存在，`modelVersion` 只剩 `llm.model-version-dishtag` 一处真源，**没有第二处可对不上**——本条防的漂移已被结构性消除（§13.2.0）。版本号与提示词的对齐由 R55a / R55b 继续保证 |
| **R55a** | `PromptVersions` 常量 vs 四份生产 `prompt/*.md` 头部声明 | `INDICATORS`、`PROBLEMS`、`DIET_TAGS`、`DISH_TAG` 分别与各自文件头逐字一致，不得共用一个版本常量 |
| **R55b** | 解析 `prompt/versions.tsv`（§9.4.2 的采用方案） | 四份 Prompt 都通过：① 末行 version == 对应 `PromptVersions` 常量；② 末行 digest == 实测正文摘要；③ 无重复 version 对应不同 digest；④ 无重复 digest 对应不同 version |
| **R55c** | 解析 `constants/tag-rule-versions.tsv`，规则与 R55b 完全相同 | 摘要覆盖**全部进入模型输入的内容常量**：`AllergenGroups`（含 `displayName`）、`AllergenExceptions`、`NutritionContents.recommendableFoodList`、`DietRequirementContents` 的三个列表、以及每个条目的 `reviewStatus`。**同样靠「无重复 version 对应不同 digest」强制 bump `TagRuleVersion`**，只比 DIGEST 常量拦不住（§9.4.2） |
| ~~**R56**~~ | **已撤销**（2026-08-27） | 原为「解析 DSL，断言零重试与三节点拓扑」。改直连后零重试由两个模型客户端的实现本身保证，不再解析外部 DSL |
| **R56b** | 菜品离线打标关闭思考与兼容剥离 | 请求必须含 `chat_template_kwargs.enable_thinking=false`；网关仍返回 `<think>…</think>` 时正常剥离，**思考段内含示例 JSON 时不得被当成结果**；只有 `</think>` / 有 `<think>` 无 `</think>` / `finish_reason == "length"` 四种形态各自整批作废且不写库（§13.2.3） |

### 11.2 契约测试

```
schema/indicators.schema.json            与文档 §6、prompt/indicators.md 三方一致
schema/health_problems.schema.json       与文档 §6、prompt/health-problems.md 三方一致
schema/diet_tags.schema.json             与文档 §6/§7、prompt/diet-tags.md 三方一致，且不含菜品字段
schema/dish_tag_output.schema.json       与文档 §8.2、prompt/dish_tag.md 三方一致
```

每次改契约必须同时跑：Schema 自身 `check_schema` + 文档示例校验 + 提示词字段名比对（R23）。
**三方任一处漏改都算契约破坏。**

### 11.3 负例与 fail-safe 用例（`AGENTS.md` §7-4 的落地）

上表中 R2~R6、R10、R12、R19~R22、R24、R25、R34~R37、R40、R43b、R43b12、R45 全部是负例或 fail-safe，
**不得只写 happy path**。特别是 **R10**：它要锁住「第三次 Schema 不包含任何菜品字段」，
防止后续又把候选菜或菜品选择塞回体检报告分析模型。

### 11.4 离线评测集（不在生产链路，但发版前必跑）

已退出在线 Java 的语义检查移到这里，评测集是它们唯一的替代：

```
① status 与报告方向标记一致        「↑偏高」不得判 NORMAL
② 非食入性过敏原必须进入阶段三与模块三，但不得进入菜品 Redis Key；枚举外过敏原输出 OTHER
③ 正常语句不得进模块二             「甲状腺结节，余未见异常」的结节要留、「未见明显异常」要滤掉
④ 过敏漏抽率                       样本按 PDF / 扫描件 / 图片 / OFD 输入格式分层
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
| **R66h** EXIF 方向端到端验证 | 需要真实图像解码与体检报告分析请求组装 | 每次改动图像方向归一化或转图路径时 |
| **R66b** 大图不得直传 | 需要真实请求组装 | 批次 5 起纳入常规集成测试 |

**跑过的结论要留档**（分配量基线值、实测峰值内存、EXIF 方向归一化结论），
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
三个占位符（§10.1）
错误码枚举（§9.1）
```

**验收**：能启动；R52、**R54** 通过；占位符全部抛 `UnsupportedOperationException`。

### 批次 2 — 文件入口

> **划分依据：上传接口能不能独立验收。** 它要落 `precheck_pages`，而后者依赖格式判定与文档遍历，
> 所以「文件进得来」所需的能力全在本批。**不要把格式判定挪到后面的批次**，那会让本批验收不了。

```
格式判定与路由、逐格式可读性校验、解压炸弹防御（§5.1）
    —— DOC/DOCX 识别即拒（UNSUPPORTED_FORMAT，§5.4）；DOCX 与 OFD 同为 ZIP 容器，
       两者的区分是本批的重点负例
CapacityPrecheckService / `precheck_pages` 全格式实现（§4.1.1）
POST /file 上传接口（§4.1）+ 对象存储占位符对接
```

**验收**：R43b1、**R43b12**（DOC/DOCX 拒收与 ZIP 容器区分）、R47，
以及上传接口对每种格式的正例与负例。
**不依赖任何任务或解析逻辑**，可独立跑通。

### 批次 3 — 任务链路（不含解析）

```
其余三个接口（§4.1）：`analyze` / `task/{id}` / `result/{id}` + `DELETE task/{id}`
stage 三态与 progress 区间（§4.3）
DegradeAccumulator（§6.5）—— 先建骨架，批次 5 写入条目剔除与模块四抑制标志
创建 + 绑定两步（含 ⓪ 事务外预检）+ 单个 `analysisExecutor`（§4.2）
状态机、领取 CAS、心跳、三条巡检（§4.3）
成功写入顺序、expire_at 顺延（§4.4）
删除与清理矩阵（§4.5）
```

**验收**：R33a、R33b、R34~R41、R40a、R40b、R43b（累计 31 拒）、
**R43b8**（累计恰好 30 通过）、R50~R51 通过。
工作线程内先塞一个 sleep 占位，跑通全链路状态迁移。

### 批次 4 — 统一转图

```
PDF / OFD / 图片的 PageRenderer 与唯一 PageImageSequence（§5.2）
ExtractionImageCompressor + CompressedPageImage（§5.3）：两档压缩、降采样解码、
    不放大 / 铺白底 / RGB / 双线性 / 方向归一化 / 资源释放
EXIF Orientation 的 8 值图像方向归一化
全局页码编址与 `page -> (fileIndex, pageInFile)` 映射（§5.3）
```

**验收**：R6、R42、R43a、**R43b9**（恰好 30 页全部发送）、**R43b10**、**R43b11**，
以及 **R66、R66a、R66c、R66e、R66f、R66h1、R66i**（压缩档位与回退、
EXIF 方向归一化、各格式统一页图契约、渲染失败归属）通过。

```
不在本批：R66b         → 需要真实请求组装，批次 5
          R66h         → 跨格式端到端方向验证
          R65p、R66g   → 性能/资源测试，独立 profile，不进普通单测
                         【但它们是上线前必跑项】—— 见 §11.5
```
**先做 OFD 真实样本采集**，渲染保真度要用真实数据定档（设计方案 §11-21）。

### 批次 5 — 体检报告分析模型链路

```
三次严格串行调用与 fail-fast 编排（§6.2）
三份生产 Prompt/Schema 的独立加载、版本化和契约校验（§6.1、§6.6）
页码、枚举、方向、阶段间零业务结果传递与同一性校验（§6.3~§6.5）
**本批第一件事**：写 `HealthReportAnalysisModelClient`、
`OpenAiCompatibleHealthReportAnalysisModelClient`、`HealthReportAnalysisModelProperties`
与 `HealthReportAnalysisCallException`；完成 §6.6 的三份提示词打包与启动自检；
新增 `PromptVersions` 常量类与 `modelVersion` 配置项（§9.4.1）

> **R57~R65 全部用 WireMock 跑**（真实 HTTP over localhost，`test` scope，需新增依赖）。
> `MockRestServiceServer` 不行——它绕过 `ClientHttpRequestFactory`，
> 而 R64（wire logging 关闭）、R65（保持缓冲、`Content-Length`、分配量）恰恰要验的就是那一层。
>
> **只有「真实端到端联调」需要 §6.4 的三个 ⛔ 项**（`base-url` / `model` / `apiKey`）。
> 它不在本批验收范围内，单列为上线前的一道门；**服务端限额等其余各项另列上线前核查**。
```

**验收**：R2~R5、R7~R12、R19~R25、R43d、R43e、R44~R46、R53，
以及 **R55a、R55b**（版本号与正文摘要）、**R57~R65、R65a~R65c**（直连红线，WireMock）、
**R66b**（大图不得直传）通过。
**R65p 不在本批验收**——它是分配量基线，超基线只报警不失败，属 §11.5 的上线前必跑项。
**R55c / R56b 属于菜品离线打标模型，在批次 7 验收**；R55b 的菜品离线打标部分也在批次 7。
**R55 / R56 已随 Dify 一并撤销**（§13.2.0）。

> **不在本批验收：真实端到端联调**——它依赖 §6.4 的三个 ⛔ 接入参数，
> 拿到凭据后单独跑一次，作为上线前的一道门。**本批不得因为"还没联调"而不交代码。****这是全案安全红线最密集的一批，
建议单独安排一次代码评审，逐条对照 §0.3。**

### 批次 6 — 模块一 / 二

```
排序总则唯一实现（§7.1）
模块一（§7.2）、模块二（§7.3）
```

**验收**：R26~R32 中模块一、二相关用例通过。完整端到端结果需等批次 7、8 完成后再联调。

### 批次 7 — 模块三 + 离线打标

```
模块三（§7.4）含高危安全闸
按企业枚举、菜品 Keyset 分页、当前页食材批量查询（§8.1）
打标任务、tagHash、33 个方向 staging SET 与原子发布（§8）
DishTagCleanupService（§8.1，**必须排在打标任务之后**）
菜品离线打标模型的饮食/过敏三态契约校验，凌晨 Java 仅生成低嘌呤、高纤维饮食正向标签（§8.2）
```

**验收**：R15、R17、R18、R18a、R18b~R18j、R22，
以及 **R55a、R55b、R55c、R56b**（菜品离线打标部分）通过。
**内容证据审核已完成**；若组织制度要求具名执业人员签字，签字未完成前模块三仍不得上线（§0.4）。

### 批次 8 — 模块四

```
按第三次已校验标签选择 Redis Key，完成企业隔离的集合读取与并、差运算（§7.5.7、§7.5.8）
复合成员解码、标签归属批量恢复（§8.3）
报告原文理由、排序各取前 3、精简 DTO 与空态（§7.5.9）
ArchUnit 锁定在线模块不依赖 DishQueryService、菜品 Mapper 与食材匹配器
```

**验收**：R13、R16、R16a~R16j（含 R16c1）、R33 通过。
**`MOLLUSK` / `SESAME` 的工程补齐已完成**（§0.4）；部署该版本时必须按
`tagRuleVersion=tag-1.1.0` 全量重打，不能沿用旧缓存。
本次把 `LOW_PURINE`、`HIGH_FIBER` 的 `DietPositiveMatcher` 从在线移动到凌晨，并把 Redis
方向清单固定为 33 个；提示词、Schema、版本摘要和全量重打必须作为同一批发布，不能沿用
曾允许菜品离线打标模型输出 9 个饮食 `RECOMMEND` 的缓存或四态结果。

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

### 13.2 菜品离线打标模型直连接入契约（**不再产出 Dify DSL**）

> **2026-08-27 变更：菜品离线打标由走 Dify 改为直连模型 API。**
> 至此体检报告分析与菜品离线打标两条模型出网链路走的是**同一个网关、同一套 OpenAI 兼容协议**，
> 只有模型标识与配置前缀不同。

#### 13.2.0 为什么去掉 Dify

改直连**与数据敏感度无关**——菜品离线打标请求里只有菜名、食材名、重量、枚举展示名，
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
| `modelVersion` 双真源 | DSL 环境变量 vs `llm.model-version-dishtag` 必须一致。**这个值进 `tagHash`，不一致不报错，只是换模型再也不触发重打标**——与已修过的菜品打标模型版本绑定缺陷是同一种病 |
| R55 / R56 | 两条只为锁 DSL 而存在的契约测试 |
| Dify 侧隐藏重试（原 D6） | 需要专门确认「平台没在背后帮我重试」，与全案零重试冲突 |
| Dify 运行记录（原 D4） | 部署级、DSL 内无开关、删不掉的一处留存 |
| `infra.DifyClient` | 占位符从四个减到三个（§10.1） |

**放弃的收益，以及为什么可以放弃：**

```
模型路由（换模型不发版）
    modelVersion 进 tagHash，换模型必然按企业触发 22 个菜品离线打标维度的全量重打标。
    这本来就是需要人盯着的运维动作，「不发版」的价值被抵消大半

可观测性（Dify 运行记录）
    菜品离线打标模型是全案【唯一可以安全记录完整请求与响应】的模型调用
    —— 输入是食堂公开数据，输出是标签，无健康数据、无用户标识（§0.3 分界）
    直连后记在自己的日志系统里，排障不必去 Dify 控制台翻，反而更顺手
    【这与体检报告分析模型「正文一个字都不能进普通日志」的链路完全不同，不要混用规则】
```

> **什么情况下这个决定应该被推翻**：① 非工程师要在 GUI 里调打标提示词
> ——那必须**同时**把 D1 改回「提示词正文放进 DSL」，否则他们改了不生效，那是最坏的情况；
> ② 需要多模型路由或供应商降级。两条都不成立时，Dify 在本链路上是纯开销。

#### 13.2.1 连接参数

| 项 | 值 | 说明 |
|---|---|---|
| Endpoint | `{llm.dishtag.base-url}` + `{llm.dishtag.chat-completions-path}` | 路径默认 `/v1/chat/completions`，与体检报告分析模型相同 |
| 测试环境 `base-url` | `http://higress-http.region-4-c86-test.test-kzx1.cncb/public` | 与体检报告分析模型使用同一个网关；拆分点同样在 `/public` 之后 |
| 鉴权 | `Authorization: Bearer ${DISHTAG_API_KEY}` | 只由环境变量注入，代码库不保存真值 |
| 模型 | `llm.model-version-dishtag`，测试环境为 `qwen3-32b-k100` | **同时是 `tagHash` 的输入**，改它等于全量重打标（§9.5.1） |
| 能力 | 文本生成；请求显式关闭深度思考 | 网关忽略关闭参数时的兼容响应见 §13.2.3 |

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
  "chat_template_kwargs": { "enable_thinking": false },
  "messages": [
    { "role": "system", "content": "<prompt/dish_tag.md 正文>" },
    { "role": "user",   "content": "<本批菜品与枚举，见下>" }
  ]
}
```

**`temperature: 0`**：同一批菜必须得到同一批标签。有采样波动时 `tagHash` 的复用语义
就自相矛盾了——哈希相同却可能产出不同标签。

**`chat_template_kwargs.enable_thinking: false`**：菜品标签是有限枚举分类，思考过程不进入
任何业务结果；显式关闭以减少 token、耗时和截断概率。该值固定在客户端请求组装中，
不提供可被部署误开的配置键。

**两条消息，不是一条。** 提示词正文进 `system`，本批数据进 `user`：
真源是 `prompt/dish_tag.md`，随 JAR 打包后读取并缓存，**只有一份**（原 D1 方案 a 的实质保留）。

**`userMessage` 由 Java 渲染**，格式严格照提示词的「User（每批填充）」小节。
这是可穷举输入的确定性字符串拼接，按 `AGENTS.md` §3 本就属于 Java。

**本链路（菜品打标）`response_format` 暂不发送。** 平台示例里没有它，网关是否支持
`json_object` 未确认。**Java 侧的 `DishTagContractValidator` 本来就是最终保证**，
不依赖服务端的结构化输出；确认支持后再加，属于收紧而非补漏。

> **注意与体检报告分析链路口径不同**：在线三次调用的客户端**已经发送**
> `response_format = json_object`（沿承旧客户端行为）。网关对该字段的支持
> **必须在真实端到端联调时验证**——不支持则请求直接 400，届时从
> `OpenAiCompatibleHealthReportAnalysisModelClient.buildRequestBody` 移除该字段即可。
> 已列入 §0.4 的联调前核查。

#### 13.2.3 响应：⛔ 关闭思考后仍必须兼容剥离，再解析 JSON

请求已显式关闭深度思考，但网关可能忽略模板参数，旧模板或兼容异常也可能继续产出思考段。
qwen3 此时会把思考过程内联在 `content` 里，而不是单独的 `reasoning_content` 字段：

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

##### 正确性不依赖网关执行关闭参数

客户端固定发送关闭参数，但**网关是否透传该参数仍须联调确认**（列入 §0.4）。
**剥离逻辑必须无条件保留**：网关忽略参数、模型模板回退或兼容行为改变都不会产生编译错误，
不能让外部能力成为解析正确性的前提。

##### `max_tokens` 以 JSON 为主并为兼容思考段保留余量

一次菜品离线打标调用最多携带 40 道菜（不是 40 个 Redis 集合），其紧凑格式输出本身不大，
正常情况下只需覆盖最终 JSON；但网关忽略关闭参数时思考段长度仍不可控。
`llm.dishtag.max-tokens` 仍需保留兼容余量，否则会在思考还没结束时被截断，
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
chat_template_kwargs.enable_thinking  固定 false，不开放配置；剥离逻辑仍保留
llm.dishtag.max-tokens                以 JSON 为主并为网关忽略关闭参数保留兼容余量，见 §13.2.3
llm.dishtag.max-request-body-bytes    默认 1MiB，有界写入，见下
llm.dishtag.max-response-body-bytes   有界读取
llm.dishtag.connect-timeout-millis    默认 10s
llm.dishtag.read-timeout-millis       离线批量，可比在线链路宽松
```

##### 请求体同样有界，但理由与体检报告分析模型不同

体检报告分析模型的载荷本身就大（Base64 图像），上限是**常态约束**；
菜品离线打标模型的载荷是文本，正常单次调用约 17KB（提示词 12.7KB + 最多 40 道菜渲染），
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
    throw new DishTagBatchRejectedException("菜品离线打标批次请求体超限，整批作废");
}
```

**不翻译会掀掉全场**：`DishTagService` 只捕获 `DishTagBatchRejectedException` 与
`DishTagCallException`，其余异常会中止**整个夜间打标任务**（22 个维度、全部批次），
而不是只丢掉这一批。一个为了隔离单批而加的防护，反倒成了更大的故障源。

也不能翻译成 `DishTagCallException`：请求根本没出过进程，而且重试同一批数据也不会变小。

##### 13.2.5.1 模型输出契约的落地类（`llm.schema`）

```
ModelOutputSchemaRegistry   三份在线 Schema 与一份离线 Schema 的唯一加载点；@Component，启动即加载，失败即启动失败
ModelOutputSchema           单份契约，只做校验
```

**存在的理由是消掉两处重复加载**：`ExtractionSchemaValidator` 与 `DishTagContractValidator`
原先各有一个 `loadSchema`，四份 Schema 的编译入口因此有两个。

> **（2026-09-02）「传输版投影」那一半已删除。** 它原本用于把 Schema 随
> `response_format=json_schema` 发给模型。实测下来这条路没有价值：**菜品离线打标模型用不了
> `json_schema`**；体检报告分析侧只有一个候选模型支持，而它剩下的失败是条件约束（`const`），
> 投影必须剥掉、约束解码看不到。加上条目剔除机制已经把可用性救回来（§6.5），
> 那部分只剩「少剔几条」的边际收益，却要每批多付约 6k token。
>
> 一整块零生产调用的代码留着比删了危险——后来的人会当它在用而去维护、去同步。
> 若要恢复，依据记在设计方案 §4.4；网关侧的探针 `JsonSchemaGatewayVerificationIT` 保留。

#### 13.2.6 落地类

```
infra.DishTagModelClient              直连接口，只返回 content 原文（兼容情况下可能含未剥离的思考段）
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

**不复用**：日志策略。体检报告分析模型的请求响应含健康数据，正文一个字都不进普通应用日志；
排障期仅可进入 §9.2 默认关闭的独立敏感 DEBUG logger；
**菜品离线打标模型允许记录完整请求与响应**（§13.2.0）。这是全案唯一的例外，
写实现时要显式注释出来，避免有人照着体检报告分析模型的写法把排障能力一起抄掉。

### 13.3 交付物与批次的对应

| 交付物 | 产出批次 | 验收 |
|---|---|---|
| `sql/schema.sql` | 批次 1 | R54、R54a |
| `prompt/*.md` + `schema/*.json` 四对生产契约 | 三份在线于批次 5、离线打标于批次 7 | R23、R55a、R55b、R55c、R56b |
| `sql/alter/*.sql` | 每次结构变更 | 与 §3.1、`schema.sql` **三件一起改**，跑 R54 + R54a |

> **不产出任何 Dify DSL**——两条模型链路都直连（§6.4、§13.2），
> R55 / R56 已随之撤销（§13.2.0），版本对齐由 R55a / R55b / R55c 承担。

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
   改了菜品离线打标提示词或换模型 → 同步 PromptVersions / DIGEST / 配置项并跑 R55b + R55c + R56b
   改了体检报告分析提示词或换模型 → 同步 PromptVersions / DIGEST / 配置项并跑 R55a + R55b
   —— 这两处漂移都是静默的，靠事后补一定会漏
```
