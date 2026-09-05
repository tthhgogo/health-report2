# AGENTS.md — 工程与编码规范

> **本文件只规定「代码怎么写」**：工具链、分层职责、建表约定、编码风格、测试与交付要求。
>
> **本文件不规定「要做什么」。** 业务流程、模型行为、医疗与饮食规则、降级策略、
> 接口语义一律以需求与方案文档为准。本文件不复述需求，不裁决需求冲突，也不新增需求
> ——需求写进本文件会变成没人复核的「不变量」，实现只会照着它长。

## 1. 适用范围与优先级

**文档真源**（任何范围发生冲突时均按此顺序）：

1. `AI体检报告分析-精简设计方案V1.md` —— 产品、架构、分层边界与系统行为的最高真源
2. `AI体检报告分析-开发方案V1.md` —— 设计方案的可执行落地，包括类、接口、DDL、执行顺序与测试清单
3. `体检报告分析需求.md` —— 产品需求原文

**本文件不具有高于设计方案的独立优先级。** 本文件只补充方案未规定的工具链、编码风格、
测试与交付约定；一旦与设计方案冲突，**无条件以设计方案为准，并同步修正本文件**。
开发方案必须忠实落地设计方案；开发方案与设计方案冲突时，同样以设计方案为准，并回改开发方案。

若方案要求与本文件现有条款不一致，不得用本文件阻止或改写方案行为，也不得自行折中出第三套规则。
按设计方案执行，并在交付报告中指出需要同步修订的文档条款。若存在客观工具链阻塞，保留清晰 TODO
并报告阻塞点，但仍不得让过期的 `AGENTS.md` 条款反向覆盖设计方案。

> `AI体检报告分析与菜品推荐-完整技术方案V1_7_1.md` 及全部 V1.5.x / V1.6.x / V1.7 文档
> **仅供历史追溯，永远不是判据**。它们描述的是一套已被取代的架构，照做即为错误实现。

## 2. 工具链红线 —— 不得升级

- Java：**JDK 8 / source=1.8 / target=1.8**
- Spring Boot：**2.7.x**
- 使用 `javax.*`，**绝不**迁移到 `jakarta.*`
- 构建工具：保持仓库既有选择；新仓库用 Maven
- 既定技术栈：MyBatis-Plus、PDFBox 2.0.x、ofdrw（reader + converter）、TinyPinyin、MySQL 8.0.14+、
  Redis、xxl-job、Amazon S3
  （2026-09-03：OCR/PaddleOCR 与 Dify 已整体退出；Apache POI 随 Word 第一期不支持一并移除，
  见设计方案 §3.2.1、§3.5）
- **不引入 Kafka**；是否使用消息队列以及任务调度方式完全以设计方案为准，本文件不另行指定
- 未经明确批准不引入新数据库或知识库
- 未经明确批准不替换对象存储（S3 → OSS/MinIO 等）
- 不随意新增生产依赖；确需新增要在最终报告中说明

### Java 8 兼容红线

禁止生成以下语法与 API：`var`、records、sealed classes、text blocks、
switch 表达式与模式匹配、`Map.of` / `List.of` / `Set.of`、`Stream.toList()`、
`Optional.isEmpty()`、`String.isBlank()`。

用 Java 8 替代：显式类型、可变集合构造、`Collectors.toList()`、`StringUtils` 等。

## 3. 分层职责

**文件转图层只负责把各种格式统一渲染成页面图；LLM-A 输出结构化总结，负责版面理解、章节归属、语义分类、健康问题准入和枚举归一化；Java 只负责 Schema 校验、页码/枚举/方向校验、简单安全兜底、集合运算、数值计算和排序，不新增或改写医疗语义。**

| 层 | 该做的 | 不该做的 |
|---|---|---|
| 文件转图 | 渲染页面图、压缩、EXIF 归一化 | 任何判断，也不抽取文本 |
| LLM-A | 语义理解、版面理解、分类、归一化 | —— |
| Java | 确定性的字符串包含、集合交并、数值比较、排序、阈值 | 不做版面启发式、不做需要理解上下文的推断 |

**新增任何 Java 逻辑前先问：这个判断能不能穷举输入并写成单测？不能就说明它属于 LLM-A。**
典型反例是「按 Y 坐标聚类成逻辑行」——阈值靠猜，双栏 / 上下标 / 跨行单元格 / 旋转页全会翻车。
Java 的价值在于确定性：能穷举、能单测、结果可复现，超出这个范围就失去了它的意义。

安全兜底是唯一例外——不可被模型推翻的安全红线必须由 Java 兜住。
**但兜底本身也必须是简单判断**（子串包含、集合交并）。
如果兜底逻辑需要词表分类、状态兼容配对、多条折叠规则才能写出来，
那说明它已经越界了：这时候要改需求，不是继续往 Java 里加规则。
**具体兜住哪些规则由设计方案规定，开发方案负责落地，本文件不列举。**

## 4. 建表与持久化约定

- **表名一律 `ct_` 前缀；Java 类名保留前缀**：`ct_health_report_task` →
  `CtHealthReportTaskEntity` / `CtHealthReportTaskMapper` / `CtHealthReportTaskService`。
  不得使用概念别名。
- 数据库操作类是 Spring `@Service`，命名 `<TableName>Service`，**不建 `Mybatis*Repository` 包装**。
- 数据库读写应尽量使用 MyBatis-Plus 提供的 Mapper、Service、Wrapper 等能力实现；
  确实无法用 MyBatis-Plus 清晰表达的复杂 SQL，必须写入 `src/main/resources/mapper/` 下的
  MyBatis XML 映射文件。**禁止在 Java 文件中以注解、字符串常量、字符串拼接等任何形式直接
  书写原生 SQL。**
- **表上不写任何 `CONSTRAINT`：没有外键、没有 `CHECK`、没有触发器。**
  表定义里只允许 `PRIMARY KEY` / `UNIQUE KEY` / `KEY` 三种**索引**声明
  ——它们是查询和幂等的基础设施（`insertIgnore` 靠唯一键），不是业务规则。
  业务规则一律由代码层在写入前保证。代价是校验只对走 Service 入口的写入生效、
  手工 SQL 绕得过去，因此每条这类规则**必须有且只有一个 Java 执行点**，
  并在注释里写明「DDL 无兜底」。
- **每个字段都必须声明字段级中文 `COMMENT`，每张表也必须有中文 `COMMENT`。**
  行尾 `--` 备注、Java 注释、字段名自说明或另外的数据字典都不能代替 MySQL `COMMENT`。
  新建表、新增字段、迁移脚本与文档中的 DDL 示例均不得例外；字段注释必须说明业务含义，
  枚举 / 状态列同时说明允许值或指向定义。
- **字符集与排序规则固定为 `utf8mb4` / `utf8mb4_general_ci`**：
  - 表级 `DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci` 必须显式写在每条 `CREATE TABLE` 上，
    不得依赖库级或版本默认（MySQL 8 自己的默认是 `utf8mb4_0900_ai_ci`，不是同一个排序规则）；
  - **每一个字符列都要逐列写 `CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci`，没有例外**
    ——UUID、hash、枚举名这些只存 ASCII 的列也一样，不用 `ascii_bin`。
  - **代价必须由代码接住**：`_ci` 大小写不敏感，唯一键会把大小写变体当同一行。
    ID 一律生成小写规范形式，入口只断言不静默纠正；外部传入的标识不由我们生成，
    归属校验必须在 Java 侧再做一次精确 `equals`，不能只靠 `WHERE col = ?`。
- **每张表必须有且仅有这四个审计列**：`create_by VARCHAR(50)`、`create_time DATETIME`、
  `update_by VARCHAR(50)`、`update_time DATETIME`，实体映射为
  `createBy` / `createTime` / `updateBy` / `updateTime`。
- **`create_time` 与 `update_time` 由数据库维护，代码永远不赋值。**
  `DEFAULT CURRENT_TIMESTAMP` 与 `ON UPDATE CURRENT_TIMESTAMP` 负责。实体上用
  **`@TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)`**
  ——该字段永不进入 insert / update 语句，select 不受影响。
  **禁止配置 `MetaObjectHandler` 自动填充这两列，禁止在任何 SQL 或 UpdateWrapper 里写
  `update_time = now()`。**
- **`create_by` / `update_by` 写固定系统标识，绝不写入用户标识。**

## 5. 待实现占位符

以下三处只写接口 + `TODO` 空实现，抛 `UnsupportedOperationException`，
**绝不写假数据返回**（假数据会让上层测试通过而掩盖未实现）：

```
S3FileStorage          对象存储读写删
CurrentUserProvider    获取当前 userId
DishQueryService       查询当日在架菜品与食材
```

> **OCR 客户端族已整体删除**（2026-09-03）：纯图片链路下 PaddleOCR 退出，
> `PaddleOcrClient` / `PaddleOcrVlClient` 及其配置不再存在（设计方案 §3.5）。
>
> **`DifyClient` 已删除**：LLM-B 于 2026-08-27 由走 Dify 改为直连模型 API，
> 与体检报告分析模型同一个网关同一套协议，接入契约见开发方案 §13.2。

> **`LlmAModelClient` 不在此列**：LLM-A 直连模型 API，已有完整实现，
> 只有 base-url / model / apiKey 走部署配置。分界与理由以设计方案为准，本文件只登记类清单。

**不生成任何中间件或数据源配置类**：不写 `DataSourceConfig`、`RedisConfig`、
`MybatisPlusConfig`、`RedisTemplate` Bean 定义、连接池配置、`application.yml` 的中间件段。

## 6. 编码规范

- Java 源文件与 `src/main/resources/mapper/` 下的 MyBatis XML 映射文件一律使用 **CRLF** 行尾，
  `.gitattributes` 为仓库强制源。
- **不得创建或保留任何 `package-info.java` 文件。** 包职责与边界统一写在具体类的中文类注释或设计文档中。
- 配置文件尽量使用 `.properties` 格式；仅在工具不支持或既有文件格式必须保持一致时使用 YAML 等其他格式。
- 小而内聚的服务优于一个巨型编排类；DTO / domain / infra 边界显式。
- 类、方法、变量、常量、配置键、数据库对象与测试名称都应尽量准确表达职责或业务含义，保证可读性；禁止使用 `123`、`abc`、`temp1` 等无实际语义的随意命名。
- 变量名反映实际用途；集合变量必须以 `List` / `Map` / `Set` 结尾。
- 已知元素数量时按需初始化容量：`new ArrayList<>(sourceList.size())`。
  确实未知时才用默认构造，不得为满足规则编造容量。
- 用 Lombok `@Getter` / `@Setter` / `@Data` 替代手写访问器；
  含校验、归一化、防御性拷贝或业务逻辑的方法保留显式实现。
- **所有控制流语句体一律加花括号**，包括单语句的 `if` / `else` / `for` / `while` / `do`。
- 枚举表达业务状态，核心逻辑不得用魔法字符串。
- 安全 / 业务规则常量集中在指定常量类中。
- 不对安全规则做聪明的抽象，代码要易于审计。
- 不在未同步更新测试与文档的情况下改动公开 API 字段语义。
- **接口文档注解（Swagger 2 注解，`io.swagger.annotations`）**：Controller 类必须有 `@Api`，
  Controller 方法必须有 `@ApiOperation`，方法参数必须有 `@ApiParam`；
  请求类必须有 `@ApiModel`、其成员必须有 `@ApiModelProperty`，响应类及其依赖的
  bean（嵌套 DTO、枚举）同样如此。注解字段尽量填全（`value` / `notes` / `required` /
  `example` 等），描述写中文并与类/字段注释语义一致；`@JsonIgnore` 的字段标
  `hidden = true`。依赖仅引 `swagger-annotations`，不引入 springfox / knife4j 文档 UI。

### 注释

- **类、重要方法、重要代码路径必须有中文注释。** 类注释说明职责与边界；
  方法注释说明重要入参、返回、副作用或安全语义；行内注释解释非显而易见的业务理由、
  fail-safe 行为、状态迁移与降级决策，而不是复述代码。
- **每个枚举类型和每个枚举常量都必须有中文注释。**
- **每一个常量都必须有中文注释，没有例外**——包括 `static final` 字段、接口常量、
  常量类里的每个成员。注释要说明**这个值的业务含义与来源依据**，而不是复述字面量：
  阈值写清楚它从哪来（实测、方案规定、还是待校准），枚举名与键值写清楚它对应什么，
  容量与上限写清楚单位以及为什么是这个数。
  **`// 最大页数` 这种复述字面量的注释不算数**，
  `// 单文件 30 等效页，超出走截断（设计方案 §3.3）` 才算。
  **暂时没有依据的阈值必须显式写「待校准」**，让它在代码里就能被看见，
  而不是只躺在文档的某一节里。

### 日志

- 用 Lombok `@Slf4j`，**日志消息写中文**。记录生命周期、集成检查点、状态迁移、
  降级决策与异常。
- 捕获后重抛或包装的非业务异常，错误日志必须把异常对象作为最后一个参数
  （`log.error("文件解析异常", exception);`），只记 `getMessage()` 不够。
- 禁止 `System.out` / `System.err` / `printStackTrace`。
- **普通日志内容白名单**：普通应用 logger 绝不记录报告原文、证据文本、模型响应正文、姓名、
  原始过敏或医嘱文本、健康数据与凭证。唯一例外是上述体检隐私内容可进入独立
  `HEALTH_REPORT_SENSITIVE` logger 的 DEBUG 事件；该 logger 默认 `OFF`，仅限排障期临时开启，
  且不得在同一事件中携带 taskId / userId。凭证和图片字节在任何 logger、任何级别都禁止记录。
  体检报告分析模型的完整内容仅允许走该独立敏感 logger；仅处理公开菜品数据且不含用户与健康信息的
  LLM-B 链路，允许在自己的 DEBUG 日志中记录完整请求响应。任务与用户标识可用于普通日志关联，
  但不得进 URL 查询串或分享链接。

## 7. 测试要求

每个实现任务：

1. 为改动的业务规则增删单元测试；
2. 先跑最小相关测试集，再跑模块 / 全量构建；
3. 保持 Java 8 可编译；
4. **必须包含负例与 fail-safe 用例，不能只有 happy path**；
5. Redis / DB 集成优先写确定性集成测试；
6. 不得删除测试、放宽断言、吞异常、写假数据或改成 TODO 来规避问题。

**必测回归清单见 `AI体检报告分析-开发方案V1.md` §11.1**；它必须与设计方案保持一致，
两者冲突时以设计方案为准并回改开发方案。本文件不再另列业务回归项——多处各写一份必然分叉，
分叉之后没人知道该信哪个。

## 8. 完成的定义

任务未完成，直到：

- 说明改了什么；
- 列出改动的文件；
- 给出跑过的测试 / 构建命令及其结果；
- 确认 Java 8 / Spring Boot 2.7.x 兼容；
- 指出任何假设、未决项、跳过的测试或依赖问题；
- 对安全、数据生命周期、日志、鉴权、健康数据意外持久化做最终 diff 复查。

**未经用户明确要求，不得 commit 或 push。**
