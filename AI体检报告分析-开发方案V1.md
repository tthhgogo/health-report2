# AI 体检报告分析与菜品推荐 — 开发方案 V1

> **本文档的目标读者是执行开发的 agent。** 它把《AI体检报告分析-精简设计方案V1.md》
> 落成可直接编码的分层、类、契约和规则；设计意图与权衡的论证仍在设计方案里，本文不重复。
>
> 技术栈：Java 8 / Spring Boot 2.7.x / MyBatis-Plus / MySQL 8.0 / Redis / PDFBox / Apache POI /
> ofdrw / TinyPinyin / xxl-job / Dify 1.6.0 / PaddleOCR / Amazon S3

---

## 0. 优先级与已确认决策

### 0.1 文档优先级

```
1. AGENTS.md            工程与工具链约束（已按精简方案 V1 改写）
2. 本开发方案            分层、类、契约、可执行规则
3. 精简设计方案 V1        产品与架构行为
4. 体检报告分析需求.md     产品需求原文
```

冲突时按上述顺序。**旧文档 `AI体检报告分析与菜品推荐-完整技术方案V1_7_1.md` 自本版起
仅供历史追溯，不再是任何判据。**

### 0.2 本轮已拍板的决策

| # | 决策 |
|---|---|
| 1 | 表名 `ct_` 前缀；类名保留前缀：`ct_health_report_task` → `CtHealthReportTaskEntity` |
| 2 | `create_time` / `update_time` 由数据库维护，代码永不赋值 |
| 3 | `create_by` / `update_by` **不得写入 `userId`**，写固定系统标识 |
| 4 | OCR 文本带 `segmentId` 一并喂给 LLM-A（补偿见 §6.3、§7.5） |
| 5 | 从零建工程 |
| 6 | 全案零重试：不重试模型调用、不重投队列消息 |
| 7 | 只有两个模型角色：**LLM-A**（在线抽取）、**LLM-B**（离线菜品打标）。建议内容是硬编码常量，不存在生成模型 |
| 8 | 所有建表 DDL 的每个字段必须有字段级中文 `COMMENT`，每张表必须有中文 `COMMENT` |
| 9 | 文件表只保存 `cloud_file_key`（Java 字段 `cloudFileKey`）；Bucket 由 `S3FileStorage` 部署配置提供，不保存 `s3_bucket` / `s3_key` |

### 0.3 需要外部提供的占位实现

以下五处**只写接口与 `TODO` 空实现**，由使用方补全：

```
S3FileStorage          对象存储读写删
DifyClient             LLM-A / LLM-B 远程调用
PaddleOcrClient        OCR 远程调用
CurrentUserProvider    获取当前 userId
DishQueryService       查询当日在架菜品与食材
```

### 0.4 不写的东西

**不生成任何中间件或数据源配置类**：不写 `DataSourceConfig`、`RedisConfig`、
`MybatisPlusConfig`、`RedisTemplate` Bean 定义、连接池配置、`application.yml` 的中间件段。
这些由使用方在既有工程规范下自行提供。本文档只声明**需要注入的 Bean 类型**。

---

## 1. 工程初始化

### 1.1 Maven 坐标与依赖

```xml
<properties>
    <java.version>1.8</java.version>
    <maven.compiler.source>1.8</maven.compiler.source>
    <maven.compiler.target>1.8</maven.compiler.target>
    <spring-boot.version>2.7.18</spring-boot.version>
</properties>
```

**全部依赖必须锁定精确版本**，不能写 `3.5.x` 这类范围——不同开发拿到的依赖树不同，
安全默认值、Schema 方言支持和解析行为都会不一样，文档里的代码片段就不再是可编译基线。

Spring Boot 2.7.18 的 `dependencyManagement` 托管了 Jackson、Lettuce、Slf4j 等；
**未被托管的必须在 `<properties>` 里显式钉死**：

```xml
<mybatis-plus.version>3.5.3.1</mybatis-plus.version>
<pdfbox.version>2.0.29</pdfbox.version>
<poi.version>4.1.2</poi.version>
<tinypinyin.version>2.0.3</tinypinyin.version>
<json-schema-validator.version>1.0.87</json-schema-validator.version>
<xxl-job.version>2.3.1</xxl-job.version>
<!-- ofdrw 版本按实际可得，选定后必须钉死并记录在此 -->
```

> 上面的版本号是**建议起点，不是已验证结论**。落地时必须：
> ① 确认所选 `json-schema-validator` 版本确实支持 **draft 2020-12**（本工程 Schema 用的是它）
> 且能在 Java 8 运行；② 跑一次 Java 8 的 `mvn -q test` 契约构建，
> 把 `prompt/` 的示例输出喂给 `schema/` 校验，通过后基线才算成立。

| 依赖 | 用途 | 备注 |
|---|---|---|
| `spring-boot-starter-web` | REST 接口 | |
| `spring-boot-starter-validation` | 入参校验 | |
| `spring-boot-starter-data-redis` | Redis + Stream | 需 Lettuce |
| `mybatis-plus-boot-starter` | 3.5.x | Java 8 兼容版本 |
| `mysql-connector-j` | 8.0.x | |
| `org.projectlombok:lombok` | `@Data` `@Slf4j` | |
| `org.apache.pdfbox:pdfbox` | 2.0.x | **不要用 3.x**，3.x 需要 Java 8+ 但 API 大改 |
| `org.apache.poi:poi` + `poi-ooxml` | 4.1.x / 5.2.x | DOC + DOCX |
| `org.ofdrw:ofdrw-reader` | OFD 解析 | 版本按实际可得 |
| `com.github.promeg:tinypinyin` | 拼音首字母 | |
| `com.networknt:json-schema-validator` | LLM 输出 Schema 校验 | Java 8 兼容 |
| `com.fasterxml.jackson.core:jackson-databind` | JSON | Spring Boot 已带 |
| `com.xuxueli:xxl-job-core` | 定时任务 | 仅 handler 注册 |

**不引入**：Kafka、任何新数据库、任何知识库、`jakarta.*` 系列。

### 1.2 包结构

占位根包 `com.example.healthreport`，接入时整体替换。

```
com.example.healthreport
├── api                       接口层
│   ├── controller            HealthReportController / DishTagJobController
│   ├── dto                   请求与响应 DTO
│   └── error                 BizException / ErrorCode / GlobalExceptionHandler
├── domain                    业务编排（无框架依赖）
│   ├── task                  任务生命周期
│   ├── parse                 文件解析与 Segment
│   ├── extract               LLM-A 抽取与 Java 校验链
│   ├── assemble              四模块组装
│   ├── dish                  菜品匹配
│   └── model                 领域对象（ParsedDocument / Segment / ExtractResult ...）
├── infra                     基础设施
│   ├── db                    entity / mapper / service（MyBatis-Plus）
│   ├── redis                 具体 Redis 操作实现
│   ├── llm                   DifyClient（TODO）+ 请求响应模型
│   ├── ocr                   PaddleOcrClient（TODO）
│   ├── storage               S3FileStorage（TODO）
│   └── external              CurrentUserProvider / DishQueryService（TODO）
├── constants                 全部安全与内容常量
├── worker                    Stream 消费者、巡检、清理、预热
└── HealthReportApplication   启动类
```

**依赖方向**：`api → domain → infra`。`domain` 不得 import `infra` 的具体实现类，
只依赖 `infra` 暴露的接口。`constants` 可被任意层引用。

### 1.3 启动类

```java
@SpringBootApplication
@MapperScan("com.example.healthreport.infra.db.mapper")
@EnableScheduling
public class HealthReportApplication {
    public static void main(String[] args) {
        SpringApplication.run(HealthReportApplication.class, args);
    }
}
```

### 1.4 需要使用方注入的 Bean

```java
DataSource                    // 使用方提供
SqlSessionFactory             // MyBatis-Plus starter 自动装配
StringRedisTemplate           // 使用方提供
RedisTemplate<String, byte[]> // 使用方提供（Stream 与二进制值）
ThreadPoolTaskExecutor llmATaskExecutor   // §7.2 批次并发用，使用方提供或本工程加一个 @Bean
```

---

## 2. 分层约定

### 2.1 命名

| 层 | 规则 | 例 |
|---|---|---|
| Entity | 物理表名转 UpperCamelCase + `Entity` | `CtHealthReportTaskEntity` |
| Mapper | 同上 + `Mapper` | `CtHealthReportTaskMapper` |
| DB Service | 同上 + `Service`，`@Service` 注解 | `CtHealthReportTaskService` |
| 领域服务 | 职责名 + `Service` | `TaskLifecycleService` |
| DTO | 用途 + `Request` / `Response` | `AnalyzeRequest` / `TaskStatusResponse` |
| 集合变量 | 必须以 `List` / `Map` / `Set` 结尾 | `indicatorList` |

**DB Service 只做单表 CRUD，不写业务规则。** 跨表事务、状态机、校验一律在 `domain` 层。
不创建 `Mybatis*Repository` 包装。

### 2.2 占位符实现的统一写法

```java
/**
 * 对象存储访问。
 * <p>实现由接入方提供，本工程只依赖本接口。</p>
 */
public interface S3FileStorage {

    /**
     * 上传对象。
     *
     * @param objectKey 对象键，形如 health-report/{yyyyMMdd}/{fileId}
     * @param content   文件内容
     * @param contentType 真实格式（§6.1 判定结果，不取扩展名）
     */
    void put(String objectKey, byte[] content, String contentType);

    byte[] get(String objectKey);

    /** 删除对象。对象不存在时不得抛异常。 */
    void delete(String objectKey);
}

@Slf4j
@Service
public class S3FileStorageImpl implements S3FileStorage {

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        // TODO 由接入方实现：调用 Amazon S3 SDK 上传到私有 Bucket
        throw new UnsupportedOperationException("TODO S3 上传未实现");
    }

    @Override
    public byte[] get(String objectKey) {
        // TODO 由接入方实现
        throw new UnsupportedOperationException("TODO S3 下载未实现");
    }

    @Override
    public void delete(String objectKey) {
        // TODO 由接入方实现：对象不存在视为成功
        throw new UnsupportedOperationException("TODO S3 删除未实现");
    }
}
```

其余四个占位符同此形式，一律 `TODO` 注释 + 抛 `UnsupportedOperationException`，
**不要写假数据返回**——假数据会让上层逻辑测试通过而掩盖未实现的事实。

### 2.3 五个占位符的完整签名

```java
public interface DifyClient {
    /** 调用 LLM-A 结构化抽取，返回原始 JSON 字符串（不做解析）。 */
    String invokeExtract(LlmARequest request);

    /** 调用 LLM-B 菜品维度打标，返回原始 JSON 字符串。 */
    String invokeDishTag(LlmBRequest request);
}

public interface PaddleOcrClient {
    /**
     * 对单页图像做 OCR。
     * @return 识别块列表，每块含文本与包围盒；顺序应为自然阅读顺序
     */
    List<OcrBlock> recognize(byte[] pageImageJpeg);
}

public interface CurrentUserProvider {
    /** 取当前已认证用户标识。本系统不做认证，只消费结果。 */
    String getCurrentUserId();
}

public interface DishQueryService {
    /** 查当前用户可见的、指定业务日在架的全部菜品（含食材与重量）。 */
    List<DishView> listOnShelfDishes(String userId, LocalDate bizDate);

    /** 离线打标用：查指定业务日全部在架菜品，不限用户。 */
    List<DishView> listAllOnShelfDishes(LocalDate bizDate);
}
```

`DishView` / `DishIngredientView` / `OcrBlock` 是本工程定义的只读视图对象，
接入方负责把自己的表结构映射过来，本工程不感知其真实表名与字段名。

---

## 3. 数据库

### 3.1 DDL

**三张表，无外键，四个审计列，`utf8mb4`。每个字段必须有字段级中文 `COMMENT`，每张表必须有中文 `COMMENT`。**

行尾 `--` 备注、Java 注释或另外的数据字典不能代替 MySQL 字段 `COMMENT`。
Bucket/容器名由 `S3FileStorage` 的部署配置提供，不逐文件落库；文件表只保存
`cloud_file_key`，Java 实体字段固定为 `cloudFileKey`，不得保留 `s3_bucket` / `s3_key`。

```sql
CREATE TABLE ct_health_report_task (
  task_id        VARCHAR(36)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '任务ID，使用UUID',
  user_id        VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '归属用户ID，用于鉴权',
  status         VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '任务状态：QUEUED/PARSING/EXTRACTING/ASSEMBLING/SUCCEEDED/FAILED',
  stage          VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NULL     COMMENT '前端进度阶段',
  progress       TINYINT      NOT NULL DEFAULT 0 COMMENT '任务进度百分比，取值0至100',
  fail_code      VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '任务失败错误码，成功或未失败时为NULL',
  reanalyzable   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '前端是否展示重新解析按钮',
  partial_reason VARCHAR(128) NULL     COMMENT '降级原因，逗号分隔多值',
  result_visible TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '结果是否对查询接口可见，见§4.3',
  heartbeat_at   DATETIME     NULL COMMENT 'Worker最近心跳时间',
  deadline_at    DATETIME     NULL COMMENT '任务执行硬截止时间',
  file_expire_at DATETIME     NOT NULL COMMENT '原始文件保留截止，重试窗口',
  access_expire_at DATETIME   NOT NULL COMMENT '结果可访问截止，与Redis结果TTL对齐',
  purge_at       DATETIME     NOT NULL COMMENT '本行物理删除时间',
  deleted_at     DATETIME     NULL COMMENT '用户删除任务的时间，未删除时为NULL',
  version        INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，由数据库维护',
  create_by      VARCHAR(50)  NULL COMMENT '创建人固定系统标识',
  update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间，由数据库维护',
  update_by      VARCHAR(50)  NULL COMMENT '更新人固定系统标识',
  PRIMARY KEY (task_id),
  KEY idx_user (user_id),
  KEY idx_sweep (status, heartbeat_at),
  KEY idx_purge (purge_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='体检报告分析任务';

CREATE TABLE ct_health_report_file (
  file_id      VARCHAR(36)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '文件ID，使用UUID',
  user_id      VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '归属用户ID，用于鉴权',
  task_id      VARCHAR(36)  CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '未绑定任务时为NULL',
  file_index   INT          NULL COMMENT '绑定后的顺序，0起',
  status       VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '文件状态：UPLOADED',
  origin_name  VARCHAR(255) NOT NULL COMMENT '用户上传时的原始文件名',
  content_type VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '§6.1判定的真实格式，非扩展名',
  size_bytes   BIGINT       NOT NULL COMMENT '文件大小，单位字节',
  content_hash CHAR(64)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '文件内容SHA-256哈希',
  cloud_file_key VARCHAR(255) NOT NULL COMMENT '云存储文件键，用于定位原始文件',
  expire_at    DATETIME     NOT NULL COMMENT '原始文件过期删除时间',
  create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，由数据库维护',
  create_by    VARCHAR(50)  NULL COMMENT '创建人固定系统标识',
  update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间，由数据库维护',
  update_by    VARCHAR(50)  NULL COMMENT '更新人固定系统标识',
  PRIMARY KEY (file_id),
  KEY idx_task (task_id),
  KEY idx_user (user_id),
  KEY idx_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上传的体检报告文件';

CREATE TABLE ct_dish_tag (
  id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  dish_id             BIGINT       NOT NULL COMMENT '食堂菜品ID',
  dish_hash           CHAR(64)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL COLLATE ascii_bin COMMENT '菜名与食材的SHA-256内容哈希',
  tag_policy_version  CHAR(64)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL COLLATE ascii_bin COMMENT '打标策略版本哈希',
  enum_key            VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL COLLATE ascii_bin COMMENT '过敏原或饮食注意维度枚举键',
  verdict             VARCHAR(12)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '打标结论：REJECT/NEUTRAL。RECOMMEND 仅由营养维度的 Java 计算产生，不落本表',
  evidence_type       VARCHAR(12)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '判定证据类型：INGREDIENT/DISH_NAME/COOKING',
  matched_ingredients VARCHAR(512) NULL COMMENT '命中食材名称的JSON数组字符串',
  reason              VARCHAR(256) NULL COMMENT '模型返回的判定理由',
  model_version       VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'LLM-B模型版本',
  prompt_version      VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'LLM-B提示词版本',
  tag_rule_version     VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '打标规则版本，来自 TagRuleVersion.VALUE；展示类内容版本不进本表',
  create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，由数据库维护',
  create_by           VARCHAR(50)  NULL COMMENT '创建人固定系统标识',
  update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间，由数据库维护',
  update_by           VARCHAR(50)  NULL COMMENT '更新人固定系统标识',
  PRIMARY KEY (id),
  UNIQUE KEY uk_tag (dish_id, dish_hash, tag_policy_version, enum_key),
  KEY idx_online (tag_policy_version, enum_key, dish_id, dish_hash),
  KEY idx_dish (dish_id, dish_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜品维度打标结果，离线预热写入';
```

**相对设计方案的三处修正（来自开发评审）：**

1. **`expire_at` 拆成三个字段**（评审 3.5）。原设计一个 `expire_at` 同时承担
   「文件重试窗口 30 分钟」「结果可访问 2 小时」「任务行物理删除」，
   导致任务行 30 分钟后被删、而 Redis 结果还剩 90 分钟无法完成归属鉴权。

   ```
   file_expire_at    = 创建/重解析时刻 + 30min   原始文件与重试窗口
   access_expire_at  = 成功时刻 + 2h             与 Redis 结果 TTL 对齐
   purge_at          = max(file_expire_at, access_expire_at) + 10min   本行物理删除
   ```

2. **`partial_reason` 改为多值**（评审 5.4）。`PAGE_TRUNCATED`、`BATCH_UNREADABLE`、
   `ALLERGEN_SUSPECT_MISS` 可能同时发生，逗号分隔存储，接口按数组下发。

3. **`ct_dish_tag` 加自增主键与 `evidence_type`**。前者便于分页清理，
   后者见 §9.4（模型可依据菜名或工艺判定，此时 `matched_ingredients` 合法为空）。

**全部机器标识列已逐列显式 `CHARACTER SET ascii COLLATE ascii_bin`**：
`task_id` / `user_id` / `file_id` / `status` / `stage` / `fail_code` / `content_type` /
`content_hash` / `dish_hash` / `tag_policy_version` / `enum_key` / `verdict` /
`evidence_type` / 三个 `*_version`。

MySQL 8 的表级默认 `utf8mb4_0900_ai_ci` **大小写与重音都不敏感**。
UUID 与 hash 落在这个排序规则下，唯一性约束和等值查找的语义就不再是二进制精确匹配
——上一版只在散文里声明了这条，DDL 里只有 `ct_dish_tag` 三列真的写了，其余全部继承表级默认。

**必须逐列写在 DDL 上**，并加一条「大小写变体不能互相命中」的 DDL 测试。
`origin_name`、`matched_ingredients`、`reason` 含中文，保持表级 `utf8mb4`。

### 3.2 Entity

```java
/**
 * 体检报告分析任务。
 * <p>本表是任务状态的唯一真源，Redis 只存分析结果。</p>
 * <p>本表不含任何健康数据。</p>
 */
@Data
@TableName("ct_health_report_task")
public class CtHealthReportTaskEntity {

    @TableId(type = IdType.INPUT)
    private String taskId;

    private String userId;
    private String status;
    private String stage;
    private Integer progress;
    private String failCode;
    private Boolean reanalyzable;
    private String partialReason;
    private Boolean resultVisible;
    private Date heartbeatAt;
    private Date deadlineAt;
    private Date fileExpireAt;
    private Date accessExpireAt;
    private Date purgeAt;
    private Date deletedAt;
    private Integer version;

    /**
     * 创建时间，由数据库 DEFAULT CURRENT_TIMESTAMP 维护。
     * <p>{@code FieldStrategy.NEVER} 让本字段永不出现在 insert / update 语句中，
     * 但仍可正常查询回来。</p>
     */
    @TableField(value = "create_time",
                insertStrategy = FieldStrategy.NEVER,
                updateStrategy = FieldStrategy.NEVER)
    private Date createTime;

    /** 创建者标识。<b>绝不可写入 userId</b>（AGENTS.md §3.3）。 */
    private String createBy;

    /**
     * 更新时间，由数据库 ON UPDATE CURRENT_TIMESTAMP 维护。
     * <p>策略同 createTime。</p>
     */
    @TableField(value = "update_time",
                insertStrategy = FieldStrategy.NEVER,
                updateStrategy = FieldStrategy.NEVER)
    private Date updateTime;

    /** 更新者标识。<b>绝不可写入 userId</b>。 */
    private String updateBy;
}
```

**`create_time` / `update_time` 的 MyBatis-Plus 处理是本工程最容易写错的一处。**

正确做法是 **`FieldStrategy.NEVER`**：该字段永不进入 insert 与 update 语句，
但 select 不受影响，仍能正常查询回来。

```java
@TableField(value = "create_time",
            insertStrategy = FieldStrategy.NEVER,
            updateStrategy = FieldStrategy.NEVER)
private Date createTime;
```

```
必须做到：
1. 用 FieldStrategy.NEVER，不要用 fill = FieldFill.INSERT / INSERT_UPDATE
2. 不要配置 MetaObjectHandler 自动填充这两列
3. 不要在任何手写 SQL 或 UpdateWrapper 里出现 update_time = now()

不这么做的后果：MyBatis-Plus 默认把实体的非空字段全写进 SQL。
实体查出来再 updateById，查回来的 createTime / updateTime 会被原样写回，
覆盖数据库时间源 —— 而且不报错，只是时间戳悄悄变成了应用服务器的时钟。
```

**所有条件更新仍然用 `LambdaUpdateWrapper`**，但理由是 CAS 需要 `WHERE` 条件
（`status = ? AND deleted_at IS NULL`），不是为了保护时间戳
—— 时间戳已由 `FieldStrategy.NEVER` 兜住。

`CtHealthReportFileEntity` / `CtDishTagEntity` 同此模式，字段与 DDL 一一对应。

### 3.3 Mapper 与 DB Service

```java
public interface CtHealthReportTaskMapper extends BaseMapper<CtHealthReportTaskEntity> {
}

/**
 * ct_health_report_task 单表操作。
 * <p>只做 CRUD 与条件更新，不含任何业务规则；状态机在 TaskLifecycleService。</p>
 */
@Service
public class CtHealthReportTaskService extends ServiceImpl<CtHealthReportTaskMapper, CtHealthReportTaskEntity> {

    /**
     * 状态 CAS。用于 Worker 领取任务与阶段推进。
     *
     * @return 受影响行数，0 表示未抢到（消息重复 / 任务已删除 / 状态已变）
     */
    public int casStatus(String taskId, String fromStatus, String toStatus,
                         String stage, int progress, Date deadlineAt) {
        LambdaUpdateWrapper<CtHealthReportTaskEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(CtHealthReportTaskEntity::getTaskId, taskId)
               .eq(CtHealthReportTaskEntity::getStatus, fromStatus)
               .isNull(CtHealthReportTaskEntity::getDeletedAt)
               .set(CtHealthReportTaskEntity::getStatus, toStatus)
               .set(CtHealthReportTaskEntity::getStage, stage)
               .set(CtHealthReportTaskEntity::getProgress, progress)
               .set(CtHealthReportTaskEntity::getHeartbeatAt, new Date())
               .setSql("version = version + 1");
        if (deadlineAt != null) {
            wrapper.set(CtHealthReportTaskEntity::getDeadlineAt, deadlineAt);
        }
        return getBaseMapper().update(null, wrapper);
    }

    /** 心跳续期，由独立调度线程每 30 秒调用。 */
    public int touchHeartbeat(String taskId) { /* 略，同上模式 */ }

    /** 置终态。fail 时同时写 failCode 与 reanalyzable。 */
    public int casTerminal(String taskId, String fromStatus, String toStatus,
                           String failCode, boolean reanalyzable) { /* 略 */ }

    /** 打删除标志。任何状态下都允许，幂等。 */
    public int markDeleted(String taskId, String userId) { /* 略 */ }
}
```

**所有状态变更必须用 `LambdaUpdateWrapper` 显式 `set` 并带 `WHERE` 条件**
——本工程的每次状态写入都是 CAS（校验前态、校验 `deleted_at`），`updateById(entity)` 表达不了。

`CtHealthReportFileService` 需要的专用方法：

```java
/** 条件绑定文件到任务。受影响行数必须等于文件数，否则整个事务回滚。 */
int bindToTask(String fileId, String userId, String newTaskId, String oldTaskId,
               int fileIndex, Date expireAt);

/** 查任务下全部文件，按 file_index 升序。 */
List<CtHealthReportFileEntity> listByTaskId(String taskId);
```

`CtDishTagService` 需要的专用方法：

```java
/** 在线回源：一次 IN 查询取多菜多维度的标签。 */
List<CtDishTagEntity> selectForOnline(String tagPolicyVersion,
                                      Collection<String> enumKeyList,
                                      Collection<DishHashKey> dishHashKeyList);

/** 离线 diff：查已存在的 (dishId, dishHash, enumKey) 组合。 */
Set<String> selectExistingKeys(String tagPolicyVersion, Collection<DishHashKey> dishHashKeyList);

/** 幂等写入。已存在同键行则跳过，绝不覆盖（AGENTS.md：标签只追加不覆盖）。 */
int insertIgnoreBatch(List<CtDishTagEntity> entityList);
```

---

## 4. Redis

### 4.1 Key 清单

| Key | 类型 | 内容 | TTL |
|---|---|---|---|
| `result:{taskId}` | String | 四模块结果 JSON | 2h |
| `q:analysis` | Stream | 队列，消息体只含 `taskId` | 见 §4.4 |
| `dish:tag:{enumKey}:{policyVersion}:{bizDate}` | Hash | Field=`{dishId}:{dishHash}` | 3d |

**只有一个 Redis 实例。** 没有 `task:{taskId}` 状态 Hash（状态在 MySQL），没有墓碑 Key
（用 `deleted_at`），没有 outbox。

### 4.2 结果读写：预写不可见 + CAS 可见

评审 3.6 指出的竞态是真的：MySQL 与 Redis 之间没有原子事务，
「置 SUCCEEDED」和「写 Redis 结果」无论谁先谁后都可能被用户删除插进中间。

**解决办法是让"可见"由 MySQL 单点决定，Redis 只是内容载体：**

```
Worker 完成时：
  ① SET result:{taskId} = JSON, EX 2h        ← 此时 GET 接口仍看不到它
  ② CAS  status ASSEMBLING → SUCCEEDED
         AND deleted_at IS NULL
         SET result_visible   = 1
             access_expire_at = NOW() + INTERVAL 2 HOUR
             purge_at         = NOW() + INTERVAL 2 HOUR + INTERVAL 10 MINUTE   ← ★ 必须一起写
  ③ CAS 受影响行数 = 0（说明用户已删除）→ 立即 DEL result:{taskId}，任务不改状态

GET /result 时：
  ① 查 MySQL：user_id 匹配 AND deleted_at IS NULL AND result_visible = 1
              AND access_expire_at > now
  ② 通过才去 Redis 取；取不到按 RESULT_EXPIRED

DELETE 时：
  ① UPDATE ... SET deleted_at = now() WHERE deleted_at IS NULL   ← 先落库
  ② DEL result:{taskId}
  ③ 删 S3 对象
  ④ 删 ct_health_report_file 行         ← 必须在 ③ 成功之后
```

> **`purge_at` 必须与 `access_expire_at` 在同一条 CAS 里更新。**
> 创建任务时写的是 `now+40min`，成功后若不刷新，`ResourceCleanupJob` 会在约 40 分钟后
> 物理删除任务行——此时 Redis 结果还剩 80 分钟，但 GET 已经无法做归属与 `result_visible`
> 校验，用户会提前拿到 `RESULT_EXPIRED`。上一版只在 §3.1 写了公式，没落到任何 SQL 里。
>
> **两个时间必须基于同一个数据库时间表达式**（`NOW()`），不要用应用时钟，
> 避免应用与 MySQL 的时钟漂移在边界上产生不一致。

**关键性质：`result_visible` 只能在 `deleted_at IS NULL` 的前提下被置 1。**
用户删除后 Worker 的 CAS 必然失败，于是它自己把预写的结果删掉。
即使 ③ 因进程崩溃没执行，那条结果也永远不可见，并在 2 小时后自然消失。

**S3 删除必须先于数据库行删除**（评审 3.6）：反过来的话 S3 失败就丢失了对象定位信息，
形成无法追踪的敏感文件。S3 删除失败时保留数据库行，交给下一轮清理批次重试。

### 4.3 结果存储实现

```java
/**
 * 分析结果的 Redis 读写。
 * <p>可见性由 MySQL 的 result_visible 控制，本类不做鉴权。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisResultRedisStore {

    private static final String KEY_PREFIX = "result:";
    private static final Duration RESULT_TTL = Duration.ofHours(2);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /** 预写结果。此时对 GET 接口尚不可见，见 §4.2。 */
    public void preWrite(String taskId, AnalysisResult result) {
        String json;
        try {
            json = objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            log.error("分析结果序列化失败, taskId={}", taskId, exception);
            throw new BizException(ErrorCode.SERVER_ERROR);
        }
        stringRedisTemplate.opsForValue().set(KEY_PREFIX + taskId, json, RESULT_TTL);
        log.info("分析结果已预写, taskId={}, bytes={}", taskId, json.length());
    }

    public AnalysisResult read(String taskId) {
        String json = stringRedisTemplate.opsForValue().get(KEY_PREFIX + taskId);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, AnalysisResult.class);
        } catch (IOException exception) {
            log.error("分析结果反序列化失败, taskId={}", taskId, exception);
            return null;
        }
    }

    /** 删除结果。用于用户主动删除与 CAS 失败回滚，幂等。 */
    public void remove(String taskId) {
        stringRedisTemplate.delete(KEY_PREFIX + taskId);
    }
}
```

### 4.4 队列：Redis Stream + Consumer Group

```java
/**
 * 分析任务队列。
 * <p>消息体只含 taskId，不含任何业务或健康数据。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisQueue {

    public static final String STREAM_KEY = "q:analysis";
    public static final String GROUP_NAME = "g:worker";
    private static final String FIELD_TASK_ID = "taskId";
    /**
     * 队列深度阈值，超过则拒绝新任务（§5.2 背压）。
     * <p><b>不是拍脑袋的常量，必须与 QUEUED 超时同源推导</b>，见 §4.4.1。</p>
     */
    private final long maxDepth;   // 由容量公式算出，构造时注入

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 创建消费组。启动时调用一次，已存在时忽略 BUSYGROUP 异常。
     */
    @PostConstruct
    public void initGroup() {
        try {
            stringRedisTemplate.opsForStream()
                    .createGroup(STREAM_KEY, ReadOffset.from("0"), GROUP_NAME);
            log.info("消费组创建成功, stream={}, group={}", STREAM_KEY, GROUP_NAME);
        } catch (RedisSystemException exception) {
            // 只有 BUSYGROUP（组已存在）是正常启动路径，其余必须让启动失败
            String message = exception.getMostSpecificCause() == null
                    ? "" : String.valueOf(exception.getMostSpecificCause().getMessage());
            if (!message.contains("BUSYGROUP")) {
                log.error("消费组创建失败, stream={}, group={}", STREAM_KEY, GROUP_NAME, exception);
                throw exception;   // 认证失败、连不上、命令不支持都必须暴露
            }
            log.info("消费组已存在, stream={}, group={}", STREAM_KEY, GROUP_NAME);
        }
    }

    /**
     * 入队。必须在创建任务的 MySQL 事务提交之前调用（§5.2）。
     */
    public void enqueue(String taskId) {
        Map<String, String> body = new HashMap<>(1);
        body.put(FIELD_TASK_ID, taskId);
        stringRedisTemplate.opsForStream()
                .add(StreamRecords.mapBacked(body).withStreamKey(STREAM_KEY));
        log.info("任务已入队, taskId={}", taskId);
    }

    /** 当前待消费深度，用于背压判定。 */
    public long depth() {
        Long size = stringRedisTemplate.opsForStream().size(STREAM_KEY);
        return size == null ? 0L : size;
    }

    public boolean isOverloaded() {
        return depth() > MAX_DEPTH;
    }

    /** 阻塞读取新消息。 */
    public List<MapRecord<String, Object, Object>> read(String consumerName, int count) {
        return stringRedisTemplate.opsForStream().read(
                Consumer.from(GROUP_NAME, consumerName),
                StreamReadOptions.empty().count(count).block(Duration.ofSeconds(2)),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));
    }

    /**
     * 确认并删除。成功与失败路径都必须调用。
     * <p><b>用 Lua 保证原子，不能用 pipeline。</b>pipeline 只减少网络往返，
     * 不保证两条命令都执行——XACK 成功而 XDEL 失败时，该条目已离开 PEL，
     * §reclaimStalePending 再也发现不了它，而 XLEN 又被用作背压深度，
     * 残留积累到最后会持续拒绝新任务。</p>
     */
    private static final RedisScript<Long> ACK_AND_DEL_SCRIPT = new DefaultRedisScript<>(
            "redis.call('XACK', KEYS[1], ARGV[1], ARGV[2]) "
          + "return redis.call('XDEL', KEYS[1], ARGV[2])", Long.class);

    public void ackAndDelete(RecordId recordId) {
        stringRedisTemplate.execute(ACK_AND_DEL_SCRIPT,
                Collections.singletonList(STREAM_KEY), GROUP_NAME, recordId.getValue());
    }

    /**
     * 巡检已 ACK 但残留在 Stream 中的条目。
     * <p>即使用了 Lua，进程在两条命令之间崩溃仍有极小窗口，因此保留兜底：
     * 定期比对 Stream 中最小 ID 与消费组的 last-delivered-id，删除已确认区间内的残留。</p>
     */
    public void trimAckedResidue() {
        // TODO 由实现方按 XINFO GROUPS / XRANGE 实现；每小时一次即可
    }

    /**
     * 清理陈旧 PEL 条目。
     * <p>Worker 崩溃会在 PEL 留下已投递未确认的条目。任务本身由 §8.4 的心跳巡检
     * 置为 FAILED，此处只负责把 Stream 里的残留条目清掉，<b>不重新执行任务</b>。</p>
     */
    public void reclaimStalePending(Duration minIdle) {
        PendingMessages pendingMessages = stringRedisTemplate.opsForStream()
                .pending(STREAM_KEY, GROUP_NAME, Range.unbounded(), 100L);
        for (PendingMessage pendingMessage : pendingMessages) {
            if (pendingMessage.getElapsedTimeSinceLastDelivery().compareTo(minIdle) > 0) {
                log.warn("清理陈旧PEL条目, recordId={}, idle={}",
                        pendingMessage.getIdAsString(),
                        pendingMessage.getElapsedTimeSinceLastDelivery());
                ackAndDelete(pendingMessage.getId());
            }
        }
    }
}
```

**`minIdle` 必须大于 Worker deadline（10 分钟），取 15 分钟**，否则会清掉正在正常执行的任务的
PEL 条目。清理只是清 Stream，不改任务状态——任务状态由 §8.4 的心跳巡检独立判定。

#### 4.4.1 背压阈值与 QUEUED 超时必须同源

上一版 `MAX_DEPTH = 200` 是固定值，而 `QueuedTimeoutSweepJob` 又在 5 分钟后把
`QUEUED` 任务判失败。**两者没有任何关系**——Worker 并发 `W` 较小时，
第 200 个任务根本不可能在 5 分钟内被领取，系统会先接受再批量判失败，
而零重试意味着这些任务只能由用户重新发起。

```
单任务处理耗时 P95 = T          （解析 + LLM-A + 组装，实测得出）
Worker 并发数     = W = floor(C / 4)
领取 SLA          = S = 5 分钟

MAX_DEPTH = floor(W × S / T)     ← 保证已接受的任务都能在超时前被领取
```

举例：`T = 300s`、`W = 4`、`S = 300s` → `MAX_DEPTH = 4`。
**这个数远小于 200**，说明上一版的阈值与超时是矛盾的。

**两个值必须用同一组部署参数、同一个公式算出**，并在启动日志里打印。
`C` 未提供前（§11-15），`MAX_DEPTH` 与 `S` 都不能写死成代码常量。

若算出的 `MAX_DEPTH` 太小导致频繁拒绝，正确的做法是**加 Worker 或降低 T**，
不是单方面调大阈值——调大只会把拒绝变成超时失败，对用户更糟。

### 4.5 打标缓存

```java
/**
 * 菜品维度打标缓存。
 * <p>Redis 只是加速器，MySQL ct_dish_tag 才是真源；未命中必须回源（§9.6）。</p>
 * <p>在线路径<b>零写入</b>，只有离线预热任务写。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DishTagRedisCache {

    private static final Duration TTL = Duration.ofDays(3);
    private final StringRedisTemplate stringRedisTemplate;

    private String key(String enumKey, String policyVersion, LocalDate bizDate) {
        return "dish:tag:" + enumKey + ":" + policyVersion + ":"
                + bizDate.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private String field(long dishId, String dishHash) {
        return dishId + ":" + dishHash;
    }

    /**
     * 批量读一个维度下多道菜的标签。
     * @return 与 dishHashKeyList 等长的列表，未命中位置为 null
     */
    public List<String> multiGet(String enumKey, String policyVersion, LocalDate bizDate,
                                 List<DishHashKey> dishHashKeyList) {
        List<Object> fieldList = new ArrayList<>(dishHashKeyList.size());
        for (DishHashKey dishHashKey : dishHashKeyList) {
            fieldList.add(field(dishHashKey.getDishId(), dishHashKey.getDishHash()));
        }
        List<Object> valueList = stringRedisTemplate.opsForHash()
                .multiGet(key(enumKey, policyVersion, bizDate), fieldList);
        List<String> resultList = new ArrayList<>(valueList.size());
        for (Object value : valueList) {
            resultList.add(value == null ? null : String.valueOf(value));
        }
        return resultList;
    }

    /** 离线预热写入。 */
    public void putAll(String enumKey, String policyVersion, LocalDate bizDate,
                       Map<String, String> fieldValueMap) {
        String redisKey = key(enumKey, policyVersion, bizDate);
        stringRedisTemplate.opsForHash().putAll(redisKey, fieldValueMap);
        stringRedisTemplate.expire(redisKey, TTL);
    }

    /**
     * 回源命中后回填。
     * <p>只在当日 Key 已存在时回填，避免预热尚未跑完就凭零散回源
     * 建出一个不完整的当日 Key（评审 6.4）。</p>
     */
    public void backfillIfKeyExists(String enumKey, String policyVersion, LocalDate bizDate,
                                    Map<String, String> fieldValueMap) {
        String redisKey = key(enumKey, policyVersion, bizDate);
        Boolean exists = stringRedisTemplate.hasKey(redisKey);
        if (Boolean.TRUE.equals(exists)) {
            stringRedisTemplate.opsForHash().putAll(redisKey, fieldValueMap);
        }
    }
}
```

**`bizDate` 统一用系统默认时区的当前日期**，跨午夜的请求按请求到达时刻所在自然日取值。
当日预热未完成时不读前一日 Key——旧标签可能对应已下架的菜（评审 6.4）。

---

## 5. 接口层

### 5.1 统一响应与错误码

```java
@Data
public class ApiResponse<T> {
    private String code;      // "OK" 或业务错误码
    private String message;   // 面向用户的中文提示
    private T data;
}
```

| ErrorCode | HTTP | 用户提示 |
|---|---|---|
| `OK` | 200 | — |
| `UNSUPPORTED_FORMAT` | 400 | 暂不支持该文件格式，支持PDF、JPG、PNG、OFD、DOC/DOCX格式 |
| `FILE_TOO_LARGE` | 400 | 文件大小超过限制，请压缩后重新上传 |
| `FILE_CORRUPTED` | 400 | 文件无法读取，请检查文件是否完整 |
| `TOO_MANY_FILES` | 400 | 最多支持上传5个文件 |
| `TOTAL_SIZE_EXCEEDED` | 400 | 上传文件总大小超过限制，请分次上传 |
| `FILE_ALREADY_BOUND` | 409 | （见 §5.2，响应带 `taskId`） |
| `FILE_NOT_FOUND` | 400 | 上传的文件已过期，请重新上传 |
| `QUEUE_OVERLOADED` | 503 | 当前分析人数较多，请稍后重试 |
| `RESULT_EXPIRED` | 404 | 本次分析结果已过期，请重新上传 |
| `SERVER_ERROR` | 500 | 服务暂时不可用，请稍后重试 |

**任务失败码是另一套**，不通过 HTTP 状态表达——任务本身查询成功（200），
`failCode` 在响应体里。每个码的 message 与 `reanalyzable` 必须一一对应，不得由开发临场决定：

| FailCode | 用户 message | `reanalyzable` | 写入方 |
|---|---|---|---|
| `NOT_HEALTH_REPORT` | 未识别到体检报告内容，请确认文件是否为体检报告后重新上传 | false | LLM-A 裁决 |
| `UNREADABLE` | 报告内容识别不清，请上传更清晰的文件 | false | LLM-A 裁决 |
| `IDENTITY_MISMATCH` | 检测到上传的文件属于不同人员，请核对后重新上传 | false | §7.11 |
| `PAGE_LIMIT_EXCEEDED` | 报告页数过多，请分次上传 | false | §6.5 |
| `FILE_EXPIRED` | 上传的文件已过期，请重新上传 | false | Worker 取文件时 |
| `EXECUTION_TIMEOUT` | 服务暂时不可用，请稍后重试 | **true** | `TaskDeadlineSweepJob` |
| `SERVER_ERROR` | 服务暂时不可用，请稍后重试 | **true** | Worker catch / 心跳巡检 / QUEUED 超时 |

**枚举而非魔法字符串**：`TaskStatus` / `TaskStage` / `FailCode` / `PartialReason`
必须是 Java 枚举，每个常量带中文注释；持久化时显式 `name()` 映射到 VARCHAR 列。
`casStatus("QUEUED", "PARSING")` 这种字符串签名不得进入正式代码。

**鉴权失败一律返回与"任务不存在"完全相同的 `404 + RESULT_EXPIRED`**，
不得返回 403 或任何可区分的码，否则 `taskId` 的存在性可被探测。

### 5.2 接口契约

#### `POST /api/health-report/file`

```
Content-Type: multipart/form-data
字段名: file                     单文件，字段名固定为 file
```

响应 `{"code":"OK","data":{"fileId":"...","originName":"...","sizeBytes":123456}}`

```
处理顺序（顺序不可调整）：
① 读取字节，按 §6.1 判定真实格式         → 不支持：UNSUPPORTED_FORMAT
② 按真实格式校验大小上限                  → 超限：FILE_TOO_LARGE
③ 按 §6.1 做可读性校验与解压炸弹防御       → 失败：FILE_CORRUPTED
④ 计算 content_hash（SHA-256）
⑤ 生成 fileId，先写 S3                   → 失败：SERVER_ERROR
⑥ 再插 ct_health_report_file 行           → 失败：删除已写的 S3 对象后返回 SERVER_ERROR
⑦ 返回 fileId
```

**⑤⑥ 的顺序与补偿是必须的**（评审 5.1）：先写库后写 S3 会在 S3 失败时留下指向不存在对象的行；
先写 S3 后写库，库失败时可以立即回删对象，最坏情况是留下一个孤儿对象，
由 S3 生命周期规则兜底。

**同一文件重复上传不做去重**，每次产生新的 `fileId`。`content_hash` 只用于排障。

#### `POST /api/health-report/analyze`

```json
{"fileIds": ["f1", "f2"]}
```

```
① fileIds 非空、去重后数量 1~5           → 违反：TOO_MANY_FILES
   同一 fileId 重复出现视为参数错误，不做静默去重
② 逐文件 SELECT ... FOR UPDATE OF f 并校验：
     user_id = 当前 userId
     status = 'UPLOADED'
     expire_at > now
     task_id IS NULL  或  所属任务 status='FAILED' AND reanalyzable=1 AND deleted_at IS NULL
   任一不满足 → FILE_NOT_FOUND；已绑到活着的任务 → FILE_ALREADY_BOUND（带该 taskId）
③ 累计 size_bytes ≤ 60MB                 → 超限：TOTAL_SIZE_EXCEEDED
④ 队列深度校验                            → 超限：QUEUE_OVERLOADED
⑤ 事务内：
     INSERT ct_health_report_task(status='QUEUED', file_expire_at=now+30min,
                                  access_expire_at=now+30min, purge_at=now+40min)
     逐文件条件 UPDATE 绑定，受影响行数必须 == 1
     AnalysisQueue.enqueue(taskId)        ← XADD 在提交之前
⑥ 提交，返回 {"taskId":"..."}
```

**不做页数校验**（评审 3.9）：DOC/DOCX 的等效页数依赖深度解析和内嵌图 OCR，
上传阶段拿不到。页数限制移到 Worker，超限时任务失败为 `PAGE_LIMIT_EXCEEDED`。

**`FILE_ALREADY_BOUND` 必须返回已绑定的 `taskId`**：

```json
{"code":"FILE_ALREADY_BOUND","message":"该文件已在分析中","data":{"taskId":"..."}}
```

用于兜住"任务创建成功但响应丢包"——前端可据此转去轮询。多个文件分别绑到不同任务时，
返回第一个冲突文件的 `taskId`。

**混入非体检报告文件不在此处拦截**，由 LLM-A 的文件级裁决处理（§7.6）。

#### `GET /api/health-report/task/{taskId}`

```json
{"code":"OK","data":{
  "status":"PARSING",
  "stage":"PARSING",
  "progress":45,
  "failCode":null,
  "reanalyzable":false,
  "suggestPollIntervalMs":3000
}}
```

**进度阶段映射（评审 3.8 修正）：**

| status | stage | progress | 前端文案 |
|---|---|---|---|
| `QUEUED` | `QUEUED` | 30 | 等待处理... |
| `PARSING` | `PARSING` | 30~60 | 正在识别报告内容... |
| `EXTRACTING` | `PARSING` | 60~80 | 正在识别报告内容... |
| `ASSEMBLING` | `ASSEMBLING` | 80~100 | 正在生成分析结果... |
| `SUCCEEDED` | `DONE` | 100 | — |

**0%~30% 由前端按 multipart 上传字节数自行计算**，与任务无关——
调 `analyze` 时文件已经全部传完，任务状态提供不了上传进度。**本工程不涉及前端实现。**

任务不存在、归属不符、已删除、已过期，**四种情况返回完全相同的 `404 + RESULT_EXPIRED`**。

#### `GET /api/health-report/result/{taskId}`

鉴权与可见性判定见 §4.2。响应结构见 §8.7。

#### `DELETE /api/health-report/task/{taskId}`

**幂等，重复删除返回 200。** 响应体只有 `{"code":"OK"}`。

```
① 打 deleted_at（已删过则跳过，仍返回 OK）
② 删 Redis 结果
③ 删 S3 对象 → 失败则保留数据库行，交下一轮清理批次，仍返回 OK
④ ③ 全部成功后删 ct_health_report_file 行
```

**接口不等待 Worker 停止。** Worker 在每次 CAS 和写结果前都带 `deleted_at IS NULL`，
自然会在下一个检查点放弃。**但已经发出的 OCR / Dify 请求不会被取消**——
这属于已知残留，`deleted_at` 只保证结果不落地、不可见。

#### 「重新解析」没有专用接口

`reanalyzable=true` 时前端用**同一批 `fileIds` 再调一次 `POST /analyze`**，得到新 `taskId`。

**前端必须自己保存 `fileIds`**（评审 5.3）。页面刷新丢失后无法重新解析，
只能重新上传——这是当前设计的已知限制，若不可接受需要加一个
「按 taskId 取原 fileIds」的接口，属需求变更。

---

## 6. 文件解析层

### 6.1 格式判定与可读性

```java
public enum FileFormat {
    PDF, JPEG, PNG, DOCX, DOC, OFD
}
```

| 格式 | 判定 | 大小上限 | 可读性 |
|---|---|---|---|
| PDF | `%PDF-` | 20MB | PDFBox 能打开且页数 ≥ 1 |
| JPEG | `FF D8 FF` | 10MB | 见下方两阶段解码 |
| PNG | `89 50 4E 47` | 10MB | 同上 |
| DOCX | ZIP 头 + 含 `word/document.xml` | 20MB | POI 能打开且正文非空 |
| OFD | ZIP 头 + 含 `OFD.xml` | 20MB | ofdrw 能打开且页数 ≥ 1 |
| DOC | `D0 CF 11 E0` + `WordDocument` 流 | 20MB | POI 能打开且正文非空 |

**DOCX 与 OFD 都是 ZIP，必须解开查内部结构才能区分。**

**图片必须两阶段解码**（评审 3.16）：直接 `ImageIO.read` 会在校验像素数之前就完成
巨额内存分配，恶意图片可以据此打爆 Worker。

```java
// 阶段一：只读尺寸元数据，不解码像素
try (ImageInputStream imageInputStream = ImageIO.createImageInputStream(inputStream)) {
    Iterator<ImageReader> readerIterator = ImageIO.getImageReaders(imageInputStream);
    if (!readerIterator.hasNext()) {
        throw new BizException(ErrorCode.FILE_CORRUPTED);
    }
    ImageReader imageReader = readerIterator.next();
    imageReader.setInput(imageInputStream);
    int width = imageReader.getWidth(0);
    int height = imageReader.getHeight(0);
    if (width < 100 || height < 100) {
        throw new BizException(ErrorCode.FILE_CORRUPTED);
    }
    if ((long) width * height > MAX_TOTAL_PIXELS) {   // 8000 万
        throw new BizException(ErrorCode.FILE_CORRUPTED);
    }
    // 阶段二：确认安全后才解码
}
```

### 6.2 解压炸弹与解析器资源防御

```java
public final class ZipGuardConstants {
    public static final int  MAX_ENTRY_COUNT   = 1000;
    public static final long MAX_ENTRY_SIZE    = 50L * 1024 * 1024;
    public static final long MAX_TOTAL_SIZE    = 200L * 1024 * 1024;
    public static final int  MAX_INFLATE_RATIO = 100;
}
```

**绝不使用 `ZipEntry.getSize()`**——该值来自压缩包自己的文件头，攻击者可控。
必须边流式解压边累计实际字节，超限立刻中断。

**解压由 POI / ofdrw 内部完成时，上述限制不生效**，因此：

```java
// DOCX：用 POI 自带防护，不要自己造轮子
ZipSecureFile.setMinInflateRatio(0.01d);          // 100:1
ZipSecureFile.setMaxEntrySize(ZipGuardConstants.MAX_ENTRY_SIZE);
IOUtils.setByteArrayMaxOverride((int) ZipGuardConstants.MAX_ENTRY_SIZE);
// 具体默认值随 POI 版本变化，落地时按实际依赖版本核对

// OFD：ofdrw 是否有等价机制需实际查证。没有则在交给它之前自行流式预扫描一遍
```

**XML 解析必须禁用外部实体**（评审 3.16），DOCX 与 OFD 都涉及：

```java
DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
factory.setXIncludeAware(false);
factory.setExpandEntityReferences(false);
```

**ZIP 条目路径必须校验穿越**：条目名含 `..` 或以 `/` 开头一律拒绝。

#### 6.2.1 资源预算必须闭合到「任务级」和「请求级」

上一版只有单项限制、没有总预算，且校验发生在字节已经读进内存之后。四条补齐：

**① multipart 硬上限必须先于 Controller 生效**

```
接入契约（本工程不写配置类，但必须配）：
  spring.servlet.multipart.max-file-size    = 21MB
  spring.servlet.multipart.max-request-size = 21MB
  网关 / Nginx  client_max_body_size        = 21MB
```

Controller 内**禁止无界 `MultipartFile.getBytes()`**：先读前 16 字节判格式，
再按该格式上限 +1 字节有界读取，超出即中止返回 `FILE_TOO_LARGE`。
否则攻击者可以先让应用把超大请求读进内存，业务校验才发现超限。

**② 像素预算按任务算，不按单图算**

```java
// 按部署堆大小与 Worker 并发数反推，接入时必须给出实际数值
MAX_DECODED_PIXELS_PER_TASK = 堆可用字节 / Worker并发数 / 4字节每像素 / 安全系数3
// JPEG/PNG 解码与 PDF/OFD 页渲染共用同一预算，累计超限即任务失败
```

单图 8000 万像素按 4 字节/像素约 320MB，一张就能显著压迫堆，
而上一版没有乘以 Worker 并发后的总预算。

**PDF/OFD 渲染前必须先校验目标像素**：`MediaBox 宽高 × (DPI/72)²` 超过单页上限直接失败
（建议 4000 万像素），不能等 `renderImage` 分配完巨图才发现。

**③ POI 的静态阈值只在启动时设置一次**

```java
// JVM 全局静态值，必须在启动阶段设置，禁止请求内修改
ZipSecureFile.setMinInflateRatio(0.01d);
ZipSecureFile.setMaxEntrySize(MAX_ENTRY_SIZE);
IOUtils.setByteArrayMaxOverride((int) MAX_ENTRY_SIZE);
```

**`setByteArrayMaxOverride` 只限制单次分配，不限制总分配量**，
所以 `MAX_TOTAL_SIZE = 200MB` 必须由**外层流式累计** entry 数与解压字节实现，POI 内部不认它。
OFD 同理：**在交给 ofdrw 之前完成同样的预扫描**，不能写「需实际查证」当作规则。

**④ 解析放专用有界线程池，且不假定可中断**

```
parseExecutor: 固定大小 = Worker 并发数，队列长度 0（满则拒绝）
单文件解析（含渲染与 OCR）超时 5 分钟 → 主流程立即置终态，不再接收该线程结果
```

**PDFBox / POI / ofdrw 的解析循环不保证响应中断**，`Future.cancel(true)` 可能无效。
超时只能保证**主流程**及时失败，**不能声称资源已释放**。
对实测不响应中断的解析器，只能靠限制单任务并发和进程级资源上限兜底
——这一点要写进容量评估，不要把超时当成释放。

### 6.3 Segment 模型

> **三层职责：解析器只负责识别，LLM-A 负责判断，Java 只负责简单判断。**
> 本节的上一版让 Java 做版面启发式（区域切分、垂直重叠聚行、列恢复、续行合并），
> 违反了这条边界，也正是评审 P0-1 判定不可靠的地方——
> 双栏、上下标、跨行单元格、旋转页每一种都会翻车，而阈值全靠猜。
> **PDFBox 只给得到坐标，硬要从坐标反推逻辑结构，是用最弱的工具做最难的事。**

#### 6.3.1 解析器输出原子块，不拼行

```java
/**
 * 解析产出的最小文本单元。
 * <p>解析器<b>不判断</b>哪些块属于同一行、同一个指标、同一个章节
 * ——那是 LLM-A 的职责（它看得到图像）。</p>
 */
@Data
public class Segment {
    /** 形如 f0-p2-s17，一经分配不再变化，模型只能原样返回 */
    private String segmentId;
    private int    fileIndex;
    private int    page;          // Word 为逻辑分块序号
    private int    seq;           // 文件内单调递增，按阅读顺序
    private String rawText;       // 原始字符，用于展示与核对
    private String normalizedText;// 送模型与一切字符串比对
    private SegmentTextSource textSource;   // NATIVE | OCR
    private BoundingBox bbox;     // 页面坐标，随文本一起送给模型
}
```

**各格式的原子块粒度：**

| 来源 | 一个 segment 是 | 说明 |
|---|---|---|
| PDF 原生文本层 | `PDFTextStripper` 在 `setSortByPosition(true)` 下输出的一个文本块 | 不做二次聚类 |
| OCR | 一个识别块 | 原样使用，不按坐标重组 |
| DOCX / DOC | 一个段落；表格的**一个单元格** | 结构本来就有，直接用 |
| OFD | ofdrw 的一个文本对象 | 同 PDF |

**`bbox` 与文本一起送给模型。** 模型据此判断版面关系——
这比 Java 用阈值猜可靠，因为它同时看得到渲染图。

#### 6.3.2 一个条目可以引用多个 segment

契约里 `segmentId` 改为 **`segmentIds` 数组**（§7.3）。
一条指标的名称、数值、单位、参考范围、结论分散在几个块里时，模型把它们**一起引用**：

```jsonc
{ "name": "甘油三酯", "value": "2.8", "unit": "mmol/L",
  "refRange": "0.56~1.70", "conclusionText": "↑偏高",
  "segmentIds": ["f0-p2-s17", "f0-p2-s18", "f0-p2-s19"] }
```

**Java 侧只做一件简单事：**

```
展示原文  = 这些 segment 的 rawText 按 seq 升序拼接（中间补一个空格）
包含性校验 = 字段值必须能在这些 segment 的 normalizedText 合并串中找到
```

仍然是纯字符串包含，没有任何版面推断。

#### 6.3.3 这样改解掉的问题

| 原问题 | 现在 |
|---|---|
| 双栏被拼成一行 → 左栏指标名配右栏数值 | 模型看图知道是两栏，不会跨栏引用 |
| `10⁹/L` 的上下标被拆成独立 segment | 模型把它一起引用进来 |
| 跨行合并单元格 | 同上 |
| 旋转页面 | 模型看的是渲染后的图，本来就是正的 |
| 续页表头被误判为章节标题 | 由模型标注章节归属（§7.3 的 `sectionSegmentId`） |
| 「一个 segment 内出现两组完整指标」的降级分支 | 不再需要 |
| 六类样本用来定阈值 | 不再需要定阈值；样本改为验收**抽取召回率** |

**代价：** 模型少引用一个 segment 会让某个字段的包含性校验不过、该指标被丢弃。
`evidenceMissCount` 需要按「字段类型 × 引用段数」分别打点，观察这类丢弃的占比。

### 6.4 文本层判定逐页进行

设计方案原用文档级平均值判断整个 PDF 有没有文本层。真实报告常是混合的
——前几页原生文本、后几页扫描图，或只有封面带文本（评审 3.15）。

```java
/** 逐页判定，同一份 PDF 允许 NATIVE 与 OCR 页并存 */
boolean hasNativeText(PDPage page) {
    String text = stripPage(page);
    int charCount = text.length();
    if (charCount < 50) {
        return false;
    }
    long nonBlank = text.chars().filter(c -> !Character.isWhitespace(c)).count();
    return (double) nonBlank / charCount >= 0.30d;
}
```

判为 `false` 的页渲染后走 OCR，产出的 segment `textSource = OCR`；
判为 `true` 的页直接抽文本，`textSource = NATIVE`。**同一份文件里两种 segment 可以共存。**

阈值 50 与 0.30 是推演值，需用真实样本校准。

### 6.5 容量与降级

```java
// 等效页数：PDF/OFD/图片按真实页数；Word 按 ceil(segment 数 / 40)
// 内嵌图片 OCR 产出的 segment 计入 Word 的 segment 数
≤ 30 页   → 全部处理
31~60 页  → 只处理前 30 页，partialReason += PAGE_TRUNCATED
> 60 页   → 任务 FAILED / PAGE_LIMIT_EXCEEDED
```

**Word 独立规则**：POI 拿不到渲染页数。表格每行一个 segment；
内嵌图片 ≥ 300×300px 的提取出来走 OCR（贴图形式的扫描报告很常见，只抽正文会全漏），
< 300×300px 视为装饰图忽略；segment 数 > 1200 或内嵌图 > 30 直接 `PAGE_LIMIT_EXCEEDED`。

**渲染必须内存有界**：逐页渲染，渲染完立即编码为 JPEG 字节并释放 `BufferedImage`。

```
BufferedImage 2000×2800 × 3 字节 ≈ 16MB/页 → 32 页并发持有 512MB → OOM
编码后 JPEG ≈ 300~800KB/页       → 32 页 ≈ 20MB
```

### 6.6 文本规范化：全案唯一入口

**所有进入字符串比对的文本都必须先过这一层**，包括报告文本、菜品名、食材名、
以及模型返回的字段值。比对两边用的规范化必须是同一个实现，否则永远对不上。

```java
/**
 * 文本规范化。全案唯一入口，比对两边必须都调它。
 * <p><b>只作用于 normalizedText，绝不作用于 rawText</b>——展示给用户的永远是原文。</p>
 */
public final class TextNormalizer {

    public static String normalize(String raw) {
        // ① NFKC：全角转半角、兼容字符还原（㎎ → mg、① → 1）
        //    注意 NFKC【不做】ASCII 大小写折叠，也【不处理】CJK 部首补充区
        String text = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        // ② 部首补充区映射（U+2E80–U+2EFF）。NFKC 对这个区完全无效，必须手工表
        text = RadicalNormalizeMap.apply(text);
        // ③ ASCII 大小写折叠。没有这一步，词表里的 xo酱 命中不了菜名里的 XO酱
        text = text.toLowerCase(Locale.ROOT);
        // ④ 去除首尾空白，中间连续空白压成一个
        return WHITESPACE.matcher(text.trim()).replaceAll(" ");
    }
}
```

**四步的顺序不能调换。** ③ 必须在 ① 之后——NFKC 会把全角 `Ｘ` 变成 `X`，
先折叠大小写的话全角字符还没归一，`Ｘ` 与 `x` 仍然不等。

**使用位置（缺一处就会产生对不上的比对）：**

| 位置 | 用途 |
|---|---|
| Segment 的 `normalizedText` | §6.3.1，送模型与全部比对的基准 |
| §7.5 包含性校验 | 字段值与 segment 合并串两边都要 normalize |
| §8.5 过敏 Layer 1 | 菜名 + 全部食材名 vs `matchWord` |
| §9.2 营养交集 | 主料名 vs `recommendableFoodList`（`CANONICAL_EXACT`） |
| §9.5 `dishHash` | 菜名与食材名，保证同一道菜每次算出同一个 hash |

**常量侧的自校验：** 每个 `CANONICAL_EXACT` 常量（营养的可推荐食材、饮食注意的避免食材）
`normalize()` 之后必须等于自身，否则它永远匹配不上——由单元测试强制（§10.4）。

`RadicalNormalizeMap` 当前为空，**只能基于真实污染样本逐条加入**，
每条留一个回归样本，且绝不按 Unicode 块批量推导（§10.5）。

---

## 7. LLM-A 抽取层

### 7.1 与设计方案的差异：模型不再生成任何全局序号

并行分批时，后面的批次不知道前面识别出多少章节和条目，
**因此模型不可能稳定生成文件级连续序号**（评审 3.2）。原契约里的
`sectionIndex` / `orderInSection` / `sourceOrder` 全部删除，改由 Java 生成。

```
模型职责：定位（segmentId）+ 局部分类（枚举、状态、类别）
Java 职责：按 fileIndex → page → seq 生成一切排序与分组键
```

**章节的处理**：模型不返回章节序号，只在识别到章节标题时把该 segment 标记出来
（`sectionTitleSegmentIds`）。Java 按文件内 `seq` 升序把标题 segment 之间的区间划成章节，
`sectionId = fileIndex + "-" + 标题segment的seq`。跨批次的章节延续问题自然消失
——章节边界由 Java 在全部批次返回后统一计算。

### 7.2 分批与并发

```java
public final class LlmAConstants {
    /** 每批最多页数 */
    public static final int MAX_PAGES_PER_BATCH = 8;
    /** 每任务最多批数，对应 30 页上限 */
    public static final int MAX_BATCH_COUNT = 4;
    /** 单任务内批次并发度 */
    public static final int BATCH_CONCURRENCY = 4;
}
```

**批次全部并行。** 串行跑不完 10 分钟 deadline：4 批 × 最长 180s = 720s。

```
Worker 并发任务数 W = floor(C / 4)，C 为模型服务并发配额（待确认）
```

不设独立信号量——`W × 4` 已是硬上界，再叠一层就有两个旋钮要调。

**任一批失败不取消其余批次**，让它们跑完丢弃。**心跳必须由独立调度线程更新**
——批次并行等待期间主流程阻塞，心跳挂在主流程里会被巡检误杀。

### 7.3 输出契约

**本版相对上一版的三处变化，都是把判断从 Java 移回模型：**

```
segmentId  → segmentIds          一个条目可引用多个块（§6.3.2）
新增 sectionSegmentId            模型直接标注归属章节，Java 不再自己划区间
新增 allergenSectionSegmentIds   模型圈出过敏筛查章节的全部 segment，含它没抽出条目的行
```

```jsonc
{
  "batchStatus": "OK | NO_REPORT_FEATURE | UNREADABLE",
  "patient": { "name": "张三|null", "gender": "男|女|null" },
  "reportOverview": { "totalCount": 0, "abnormalCount": 0 },

  "sectionTitleSegmentIds": ["f0-p2-s15"],        // 章节标题所在段，用于取 displayName

  // ★ 过敏筛查章节的【全部】segment，包括你没能抽出条目的数据行
  //   这是 §7.7 覆盖检查的输入，漏圈等于关掉了过敏漏抽的最后一道防线
  "allergenSectionSegmentIds": ["f0-p4-s8", "f0-p4-s9", "f0-p4-s10"],

  "indicators": [{
    "name": "甘油三酯", "value": "2.8", "unit": "mmol/L",
    "refRange": "0.56~1.70", "conclusionText": "↑偏高",
    "status": "NORMAL|HIGH|LOW|ABNORMAL",
    "statusJudgedByModel": false,
    "segmentIds": ["f0-p2-s17", "f0-p2-s18"],     // ★ 数组
    "sectionSegmentId": "f0-p2-s15",              // ★ 归属章节，无则 null
    "itemIndex": 0
  }],

  "textualFindings": [{
    "title": "脂肪肝", "conclusionText": "提示脂肪肝",
    "status": "NORMAL|ABNORMAL", "includeInHealthProblems": true,
    "segmentIds": ["f0-p3-s4"], "sectionSegmentId": "f0-p3-s1", "itemIndex": 0
  }],

  "summaryConclusions": [{
    "itemNo": 3,                                   // 报告原文编号，无则 null
    "categories": ["HEALTH_PROBLEM","DIET_ADVICE"],
    "includeInHealthProblems": true,
    "segmentIds": ["f0-p5-s12"], "sectionSegmentId": "f0-p5-s10", "itemIndex": 0
  }],

  "allergens": [{
    "enumKey": "SHRIMP_CRAB|...|OTHER", "isFoodBorne": true,
    "rawName": "虾蟹类", "rawResult": "阳性(+)",
    "resultStatus": "POSITIVE|NEGATIVE|BORDERLINE|UNKNOWN",
    "segmentIds": ["f0-p4-s9"], "sectionSegmentId": "f0-p4-s8", "itemIndex": 0
  }],

  "nutritionSupplements": [{
    "enumKey": "IRON|...|OTHER",
    "segmentIds": ["f0-p5-s14"], "sectionSegmentId": "f0-p5-s10", "itemIndex": 0
  }],
  "dietRequirements": [{
    "enumKey": "LOW_FAT|...|OTHER",
    "segmentIds": ["f0-p5-s15"], "sectionSegmentId": "f0-p5-s10", "itemIndex": 0
  }]
}
```

**模型仍然不生成任何全局序号。** 排序、分组键、`indicatorId` 全部由 Java 按
`fileIndex → min(segmentIds 的 seq) → itemIndex` 生成——纯排序，属简单判断。

### 7.4 Java 校验链（顺序不可调整）

```
① Schema 校验          任一必填字段缺失 → 整任务 FAILED/SERVER_ERROR，不重试
② segmentId 存在性      引用了不存在的 segment → 该条丢弃
③ 包含性校验            见 §7.5
④ 状态一致性校验        词表命中方向词时以 Java 为准
⑤ 过敏原准入过滤        阴性绝不进入，见 §7.7
⑥ 过敏证据覆盖检查      见 §7.7
⑦ 健康问题准入反向兜底  见 §7.8
⑧ 跨批去重             见 §7.9
⑨ 全局语义校验          见下表（Schema 只管结构，管不了这些）
⑩ 排序与分组           Java 生成全部序号
```

**第 ⑨ 步的清单（合并全部批次之后执行）：**

| 校验 | 不通过时 |
|---|---|
| `reportOverview` 两个数非负，且 `abnormalCount ≤ totalCount` | 整个 `reportOverview` 置 null，改用卡片计算（§8.1） |
| 同一 `segmentId` 内 `itemIndex` 唯一（分类型各自唯一） | 重复条目按 §7.9 保留一条 |
| `sectionTitleSegmentIds` 每个 ID 存在且属于同一 `fileIndex` | 剔除无效 ID；全无效则该文件走默认分组 |
| 无任何章节标题的文件 | 全部指标归入 `sectionId = fileIndex + "-0"`，`displayName` 取文件名 |
| **合并后 `indicators` 总数 ≤ 500** | **整任务 `FAILED / SERVER_ERROR`** |
| `textualFindings` ≤ 200、`summaryConclusions` ≤ 100、`allergens` ≤ 100 | 同上 |

**Schema 的 `maxItems` 是每批上限，4 批合并后是 4 倍。**
`indicators` 每批 500 × 4 = 2000，而结果接口声明上限 500 且不分页，
所以必须在合并处再卡一次。**超限选择整任务失败而不是安全截断**
——截断会静默丢指标，用户看到一份不完整却没有任何标记的报告。

**Schema 校验失败不重试。** 设计方案曾有两处口径冲突（一处写"直接失败"、
一处写"按 Schema 必填走重试"），以本条为准：**全案零重试**。

### 7.5 包含性校验按 textSource 分档

```java
boolean containsField(List<Segment> referencedList, String fieldValue, FieldType fieldType) {
    String normalizedField = TextNormalizer.normalize(fieldValue);
    // ★ 把该条目引用的全部 segment 合并后再查，不做任何版面推断
    String haystack = referencedList.stream()
            .sorted(Comparator.comparingInt(Segment::getSeq))
            .map(Segment::getNormalizedText)
            .collect(Collectors.joining(" "));
    // 混合来源时按 OCR 从宽，避免原生块拖累 OCR 块
    boolean anyOcr = referencedList.stream()
            .anyMatch(seg -> seg.getTextSource() == SegmentTextSource.OCR);
    if (!anyOcr) {
        return haystack.contains(normalizedField);          // 严格
    }
    // OCR：先去空白做子串匹配
    String compactHaystack = WHITESPACE.matcher(haystack).replaceAll("");
    String compactField = WHITESPACE.matcher(normalizedField).replaceAll("");
    if (compactHaystack.contains(compactField)) {
        return true;
    }
    // 仍不中时按字段类型分档，不能所有字段共用一个编辑距离（见下表）
    return fuzzyByFieldType(compactHaystack, compactField, fieldType);
}
```

**分档规则。** 统一用「编辑距离 ≤ 1」在短字段上等于没有约束
——`H`、`L`、单字符单位允许一次编辑之后，几乎任何字符都能通过。

| 字段 | null 时 | 放宽规则 |
|---|---|---|
| `unit` / `refRange` | **跳过校验**（本就允许 null） | 长度 ≤ 2 时**禁止**模糊匹配，必须精确子串 |
| `value` | 不允许 null | 只允许**已知 OCR 形近映射**（0↔O、1↔l↔I、6↔8、5↔S、`.`↔`,`）与小数点/空格差异，**不用通用编辑距离** |
| `name` | 不允许 null | 长度 ≥ 3 才允许模糊，阈值按长度归一化：`编辑距离 / 字段长度 ≤ 0.2` |
| `conclusionText` | 不允许 null | 同 `name` |

**长度 ≤ 2 的字段一律禁止通用编辑距离。**
「图像优先」的决定不变（§7.3-6），但不能用同一条模糊规则覆盖所有字段
——那会让模型抄错甚至编造的短字段照样通过「原文包含性校验」，回切就失去意义了。

命中模糊档时记 `ocrFuzzyMatchCount`，按字段类型分别打点。

指标的五个字段任一校验不过 → 该指标整条丢弃。

**为什么 OCR 必须放宽**：OCR 文本是从图片识别出来的降级副本。OCR 把 `2.8` 读成 `2.6` 时，
模型看图正确读出 `2.8`，严格子串校验会把这条**正确的**抽取结果杀掉。
而拍照上传是主流形态。

### 7.6 批次裁决

```
batchStatus 三态：
  OK                  正常识别
  NO_REPORT_FEATURE   读得清，但确实不是体检报告内容（封面、须知、广告）
  UNREADABLE          读不清，可能有内容但看不出来

文件级：某文件全部批次 NO_REPORT_FEATURE → 该文件不是体检报告
任务级：全部文件都不是体检报告            → FAILED / NOT_HEALTH_REPORT
       全部批次都 UNREADABLE             → FAILED / UNREADABLE
       任一批 UNREADABLE（非全部）        → 该批丢弃，partialReason += BATCH_UNREADABLE
```

**两个失败值不可互换**：`NO_REPORT_FEATURE` 是"确定没有"，`UNREADABLE` 是"不知道有没有"。
把后者当前者，就是把"模型没看清"当成"报告里没有"。

**降级矩阵：**

| partialReason | 模块一 | 模块二 | 模块三 | 模块四 |
|---|---|---|---|---|
| `PAGE_TRUNCATED` | ✅ | ✅ | ❌ | ❌ |
| `BATCH_UNREADABLE` | ✅ | ✅ | ❌ | ❌ |
| `ALLERGEN_SUSPECT_MISS` | ✅ | ✅ | ✅ | ❌ |

多个原因同时命中时按最严的取（任一要求关闭某模块即关闭）。

### 7.7 过敏原：准入 + 逐 segment 证据覆盖

```java
// 准入
POSITIVE   → 进入过敏提醒与菜品拦截
BORDERLINE → 进入（弱阳性/可疑/临界，按安全不对称从严）
NEGATIVE   → 绝不进入（一张 30 项筛查表里 28 项是阴性）
UNKNOWN    → 不进入，也不得当成阴性；记 allergenUnknownCount 并触发 §7.7 的覆盖检查
```

**证据覆盖检查 —— 本轮重写，上一版的实现根本不会触发**

上一版要求 `SECTION_TITLES` 与 `POSITIVE_MARKS` 出现在**同一个 segment**。
但 §6.3 把 segment 切成了「一行一个」，真实筛查表是：

```
[f0-p4-s8]  过敏原筛查                  ← 只有标题词，没有阳性标记
[f0-p4-s9]  虾蟹类        阳性(+)       ← 只有阳性标记，没有标题词
[f0-p4-s10] 牛奶          阴性(-)
```

两个条件永远不会同时成立，**风险集合恒为空，整套 fail-safe 从不触发**
——这正是它要堵的 fail-open。本版改为四步：

```java
// ① 章节范围【由模型给出】，Java 不再自己划区间
List<Segment> sectionSegmentList = resolve(response.getAllergenSectionSegmentIds(), segmentList);

// ② Java 只做一件简单事：在这些 segment 里数结果标记
Set<String> riskSegmentIdSet = new HashSet<>();
int riskMarkCount = 0;
for (Segment segment : sectionSegmentList) {
    int markCount = countAny(segment.getNormalizedText(),
                             AllergenSuspectWords.ADMITTED_RESULT_MARKS);
    if (markCount > 0) {
        riskSegmentIdSet.add(segment.getSegmentId());
        riskMarkCount += markCount;
    }
}

// ②a 兜底：模型可能把整个过敏章节漏圈了，前面三条都不会触发
if (sectionSegmentList.isEmpty()
        && anySegmentContains(segmentList, AllergenSuspectWords.SECTION_TITLES)) {
    partialReasonSet.add(PartialReason.ALLERGEN_SUSPECT_MISS);
}

// ③ 有效覆盖只能来自"准入且回切通过"的条目 —— 阴性与 UNKNOWN 不算覆盖
Set<String> coveredSegmentIdSet = new HashSet<>();
int admittedCount = 0;
for (AllergenItem item : allergenList) {
    boolean admitted = item.getResultStatus() == POSITIVE || item.getResultStatus() == BORDERLINE;
    if (admitted && item.isEvidenceResolved()) {
        coveredSegmentIdSet.addAll(item.getSegmentIds());   // ★ 数组，全部展开
        admittedCount++;
    }
}

// ④ 两个判据都要满足，缺一即降级
boolean segmentCovered = coveredSegmentIdSet.containsAll(riskSegmentIdSet);
boolean countCovered   = admittedCount >= riskMarkCount;   // 一行含多项时按数量对齐
if (!segmentCovered || !countCovered) {
    partialReasonSet.add(PartialReason.ALLERGEN_SUSPECT_MISS);   // 关闭模块四
    metrics.increment("allergenSuspectMissCount");
}
```

**`ADMITTED_RESULT_MARKS`（原 `POSITIVE_MARKS`）必须覆盖 `POSITIVE + BORDERLINE` 全部写法。**
`BORDERLINE` 按 §7.7 从严准入，所以它们的标记也必须算作风险
——上一版只有「阳性 / (+) / ＋ / 强阳性」四个，**模型漏掉唯一一条弱阳性时覆盖检查不触发**。

```
阳性  强阳性  弱阳性  可疑  临界  阳
(+)  （+）  ＋  +  ++  +++  ±  (±)  (+/-)  ＋/－
```

匹配前统一归一化：全半角、括号、`+` 号数量、ASCII 大小写。

**只做 segmentId 集合比较不够**（评审 P0-2-5）：一行含多项过敏原时，
集合相等但数量可能对不上，所以加了 `admittedCount >= riskMarkCount` 这一条。

**四种情况一律判为漏抽，不得解释为安全：**

```
过敏章节内识别出候选数据行，但一条准入条目都没有
结果列无法识别（OCR 把「阳性(+)」读成乱码 → 扫描不到标记）
准入条目的 segmentIds 不覆盖某个风险行
allergenSectionSegmentIds 为空，但全文有 segment 命中 SECTION_TITLES   ← ②a
```

**最后一条是本版新增的。** 章节范围改由模型给出之后，
它若把整个过敏章节漏圈了，前三条都不会触发——所以 Java 用
`SECTION_TITLES` 做一次全文粗扫交叉检查。仍是简单字符串匹配，不涉及版面判断。

过敏原条目**回切失败被丢弃时同样触发**——`isEvidenceResolved()` 为 false 的条目不计入覆盖，
自然会让 `admittedCount` 不足。不能静默丢弃后继续推荐。

### 7.8 词表匹配规则

设计方案原写「整段 `normalizedText` 命中 `NormalStatementWords` 则强制
`includeInHealthProblems=false`」，这会误杀（评审 3.10）：

```
「乙肝表面抗体阴性，建议接种乙肝疫苗」→ 整段含"阴性" → 被整条排除
```

**修正为三条规则：**

1. **只对抽取出的 `conclusionText` 匹配，不对整段原文匹配。**
2. **最长短语优先**：先按词长降序排序词表，第一个命中即返回。
   这样「未见明显异常」不会被「异常」抢先命中，「不正常」不会被「正常」命中，`↑↑` 不会被 `↑` 命中。
3. **「阴性」不在通用正常词表内**——它是非方向性结论，走模型判断（§7.3 的 `status`）。

```java
public final class ConclusionLabelWords {
    // 按词长降序排列，匹配时顺序遍历，第一个命中即返回
    private static final List<WordEntry> ORDERED_ENTRIES = buildSortedByLengthDesc(
        entry("未见明显异常", NORMAL), entry("未见异常", NORMAL), entry("正常", NORMAL),
        entry("↑↑", ABNORMAL), entry("↓↓", ABNORMAL), entry("异常", ABNORMAL),
        entry("偏高", HIGH), entry("增高", HIGH), entry("升高", HIGH), entry("↑", HIGH), entry("H", HIGH),
        entry("偏低", LOW), entry("降低", LOW), entry("减低", LOW), entry("↓", LOW), entry("L", LOW)
        // 注意：不含 阳性 / 阴性
    );
}
```

**否定词优先**：`conclusionText` 含「不」「无」「未」且其后紧跟某个正常词时，
该正常词不成立（「不正常」「未见正常形态」）。

`NormalStatementWords` 的反向兜底同样只对 `conclusionText` 生效，不对整段生效。

### 7.9 去重：按类型定键

跨批去重**只在批次输入确有重叠时执行**。V1 分批**不重叠**（按页严格切分），
因此正常路径下不会产生重复；本规则用于防御模型把相邻页内容重复返回。

`segmentKey` = **`segmentIds` 中 `seq` 最小的那个**（数组顺序不参与，避免抖动）。

| 类型 | 去重键 |
|---|---|
| `indicators` | `segmentKey + itemIndex + name + value` |
| `textualFindings` | `segmentKey + itemIndex + title` |
| `summaryConclusions` | `segmentKey + itemIndex` |
| `allergens` | `segmentKey + itemIndex + rawName` |
| `nutritionSupplements` / `dietRequirements` | `segmentKey + itemIndex + enumKey` |

**不用 `sectionName + name + value + unit` 四元组**——它会误删真实结果：
左眼/右眼视力同为 5.0、静息/运动后心率同为 72、报告附带的历史对比值。

### 7.10 排序与分组（全部 Java 生成）

```java
// 章节：直接用模型标注的 sectionSegmentId，Java 不划区间
sectionId   = fileIndex + "-" + (sectionSegmentId != null ? 该段的 seq : 0)
displayName = 单文件 ? 标题原文 : "报告" + (fileIndex + 1) + "-" + 标题原文

// 指标：分组 = 所属 sectionId；组内排序 = segment.seq → itemIndex
// 健康问题：INDICATOR_NUMERIC + INDICATOR_TEXTUAL 在前，按 fileIndex → seq → itemIndex
//          SUMMARY 在后，按 fileIndex → seq → itemIndex
// indicatorId：合并后按最终展示顺序分配全局自增，仅用于健康问题的关联跳转
```

**跨文件绝不合并同名章节**，两份报告的「血脂检查」是两个独立分组。

### 7.11 多文件同一性

```
取所有非空 patient.name / gender 比对，规范化后完全相等即通过
任一不一致 → FAILED / IDENTITY_MISMATCH
```

**这是"发现冲突则拒绝"的弱校验**，拦不住同名不同人，也拦不住双方都识别不出姓名。
**姓名只在 Worker 内存中存在**，不写 MySQL、不写 Redis、不进日志、不返回前端。

---

## 8. 四模块组装与任务编排

### 8.1 模块一：健康指标

```
数据来源：indicators（准入 = 有数值 + 有结论，正常项也要）
分组：    按 §7.10 的 sectionId，组内按 seq → itemIndex
卡片字段：name / value / unit / refRange / conclusionText / status
展示：    全部平铺，不折叠
```

`refRange` 为 null 时展示「报告未提供」，**禁止填充通用参考值**。

**总览条**：`reportOverview` 非空时用报告自带数字并标注「（报告原文）」，否则按卡片计算。
**不加任何差值说明文案**（产品已确认）。

### 8.2 模块二：健康问题

| 来源 | 准入 | 带 `indicatorId` |
|---|---|---|
| `INDICATOR_NUMERIC` | `indicators` 中 `status != NORMAL` | 是 |
| `INDICATOR_TEXTUAL` | `textualFindings` 中 `includeInHealthProblems = true` | 否 |
| `SUMMARY` | `summaryConclusions` 中 `includeInHealthProblems = true` 且 `categories` 含 `HEALTH_PROBLEM` 或 `DIET_ADVICE` | 否 |

`INDICATOR_TEXTUAL` 不带 `indicatorId`——「脂肪肝」这类无数值结论根本不会生成指标卡片，
没有可跳转的目标。

**空态直接用需求原文，不区分完整性**（产品已确认按简单方案处理）：

```java
if (healthProblemList.isEmpty()) {
    emptyText = "本次体检各项指标均在正常范围内，请继续保持良好的生活习惯。";
}
```

> **已知接受的风险：** 三类来源为空还可能来自模型漏抽、回切失败、批次不可读、报告截断。
> 这些情况下仍会展示「各项指标均在正常范围内」，是一句缺乏依据的肯定结论。
> 产品明示接受，兜底仅靠模块底部声明「不构成二次诊断，如有疑问请咨询医生」。

### 8.3 模块三：饮食建议

三个分区独立成卡片，每条带报告原文来源（按 `segmentId` 取整段 `rawText`）。

```
过敏提醒：全部准入过敏原按报告原文顺序混排，不按 isFoodBorne 分组
          isFoodBorne=true  → 列避免食材 + 易忽略加工食品
          isFoodBorne=false → 只有名称和来源，不列食材，不加任何说明文字
营养补充：按 NutritionContents 常量展示（可推荐与仅展示两个食材列表都要展示）
饮食注意：按 DietRequirementContents 常量展示
OTHER：   只展示报告原文与来源，不加任何说明文字，不参与菜品匹配
```

**高危表述的方向矩阵**（Java 兜底，全案唯一一处推翻模型归一化）：

```java
// 命中模式时只阻止对应的那几个枚举，其余归一化照常
Map<Pattern, Set<EnumKey>> BLOCKED_DIRECTIONS;
// 命中则整条转 OTHER，方向无法由关键词确定
Set<Pattern> MANUAL_REVIEW_PATTERNS;
```

**每条 pattern 必须带 `scope`，否则同一条规则会产生完全不同的结果**
——肾病写在报告前文、「建议补钾」在另一段时，只查建议原文的话 `POTASSIUM` 照样通过；
反过来无边界扫全文，「儿童」「流质」这类偶然文字又会把所有建议打成 `OTHER`。

```java
enum PatternScope {
    ADVICE_TEXT,      // 只查该条建议自己的 conclusionText / quotedText
    ADVICE_SECTION,   // 查该建议所属的临床建议段（同一 sectionId 区间）
    PATIENT_CONTEXT   // 查全报告中【已通过准入校验】的诊断类内容，不扫原始全文
}
```

| 原文模式 | scope | 阻止的枚举 |
|---|---|---|
| 低蛋白、限蛋白、优质低蛋白 | `ADVICE_SECTION` | `PROTEIN` |
| 低钾、限钾 | `ADVICE_SECTION` | `POTASSIUM` |
| **高钾血症、低钾血症** | `PATIENT_CONTEXT` | `POTASSIUM`（两者都阻止：高钾不能补，低钾需遵医嘱定量） |
| 低纤维、少渣、低渣、无渣 | `ADVICE_SECTION` | `DIETARY_FIBER`、`HIGH_FIBER` |
| 限液、限水、控制液体 | `PATIENT_CONTEXT` | `DIETARY_FIBER`、`HIGH_FIBER` |

`PATIENT_CONTEXT` 的输入**只能是通过 §7.4 校验链的 `textualFindings` 与
`summaryConclusions`**，不是全报告的原始文本——否则检查须知、科普段落里的词也会命中。

**ASCII 医学缩写（CKD、ESRD）匹配前统一 `toUpperCase(Locale.ROOT)`。**

`MANUAL_REVIEW_PATTERNS`（命中即整条转 `OTHER`），`scope = PATIENT_CONTEXT`：
肾功能不全、慢性肾病、CKD、肾衰、尿毒症、透析、血液透析、腹膜透析、
肝硬化、肝衰竭、肝性脑病、吞咽困难、流质、半流质、鼻饲、
妊娠、孕期、孕妇、哺乳期、乳母、儿童、婴幼儿。

**「肾病在前文、补钾在后文」的产品行为需明确**（§13）：当前实现按
`PATIENT_CONTEXT` 命中即把该建议转 `OTHER`，属保守方向；
若产品认为误伤过多，只能收窄 scope，不能放宽词表。

**不是一刀切转 `OTHER`。** 原设计「命中任何高危词就把任何枚举转 `OTHER`」会丢掉有效建议
——「痛风急性期」与「低嘌呤」方向并不相反，转 `OTHER` 反而让用户什么都拿不到。
只有方向确实可能相反的组合才阻止，词表见 `constants/内容常量草案.md` §6。

**一条原文拆出多个枚举时**（「建议低脂低盐饮食」→ `LOW_FAT` + `LOW_SODIUM`），
两张卡片独立展示，来源标注引用同一段原文。

**全模块不出现任何提示、说明、警示文字**，只有内容、来源、空态句和底部声明。

### 8.4 任务编排与 Worker

#### 8.4.1 唯一终态写入者

**只有消费主线程能写业务终态。** 批次 Future 只返回 `BatchOutcome`，
**绝不允许在回调里写数据库**——4 个批次并发时会互相争抢终态、产生互相矛盾的 failCode。

```java
/** 消费消息的上下文，贯穿整个处理过程。 */
@Data
@AllArgsConstructor
public class WorkerMessageContext {
    private final RecordId recordId;      // 用于 ACK+DEL，必须传到最外层 finally
    private final String   taskId;
    private final String   consumerName;  // 形如 worker-{hostname}-{pid}，同实例内唯一
}
```

#### 8.4.2 编排模板

```java
/**
 * 分析任务主流程。
 * <p>每个阶段 CAS 都必须检查影响行数，0 行立即终止；
 * 终止前若已预写结果必须删除，否则健康数据会残留到 TTL。</p>
 */
public void process(WorkerMessageContext context) {
    String taskId = context.getTaskId();
    ScheduledFuture<?> heartbeatFuture = null;
    boolean resultPreWritten = false;
    boolean resultPublished  = false;
    TaskStatus currentStatus = TaskStatus.QUEUED;
    try {
        // ① 领取
        if (taskService.casStatus(taskId, QUEUED, PARSING, Stage.PARSING, 30, deadline()) == 0) {
            log.info("任务无法领取，丢弃消息, taskId={}", taskId);
            return;                                   // finally 仍会 ACK+DEL
        }
        currentStatus = TaskStatus.PARSING;
        heartbeatFuture = heartbeatScheduler.start(taskId);   // ★ 在 try 内启动

        // ② 解析 → ③ CAS PARSING→EXTRACTING → ④ LLM-A 并发 → ⑤ CAS →ASSEMBLING → ⑥ 组装
        //   每个 CAS 返回 0 都直接 return，不继续调用 OCR/Dify
        //   currentStatus 每次 CAS 成功后同步更新，供 catch 使用

        // ⑦ 预写
        resultStore.preWrite(taskId, result);
        resultPreWritten = true;

        // ⑧ 发布：CAS ASSEMBLING → SUCCEEDED，同时写 result_visible / access_expire_at / purge_at
        if (taskService.casPublishSuccess(taskId) == 1) {
            resultPublished = true;
            fileCleanupService.deleteBySucceededTask(taskId);   // ⑨ S3 先删，再删文件行
        }
        // CAS = 0 说明用户已删除，什么都不做，finally 会清掉预写值
    } catch (BizException exception) {
        taskService.casTerminal(taskId, currentStatus, FAILED,
                exception.getFailCode(), exception.isReanalyzable());
    } catch (Exception exception) {
        log.error("任务执行异常, taskId={}", taskId, exception);
        taskService.casTerminal(taskId, currentStatus, FAILED, FailCode.SERVER_ERROR, true);
    } finally {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatScheduler.unregister(taskId);    // 必须从内部 Map 移除，否则泄漏
        }
        if (resultPreWritten && !resultPublished) {
            resultStore.remove(taskId);               // ★ 幂等，防健康数据残留到 TTL
        }
        queue.ackAndDelete(context.getRecordId());    // 所有出口都走这里，含①的 return
    }
}
```

**四条必须遵守的：**

```
heartbeatFuture 在 try 内创建、finally 中 cancel + unregister
   —— 放在 try 之前的话，start 抛异常时 finally 根本不会执行

resultPreWritten / resultPublished 两个布尔量必须有
   —— 只处理"CAS 返回 0"漏掉了"CAS 抛异常"和"后续通用异常"，
      那两种情况预写值会残留到 2 小时 TTL

ackAndDelete 由最外层 finally 调用，含第 ① 步 return 的路径
   —— 领取失败也要 ACK，否则消息永远留在 PEL

每个阶段 CAS 返回 0 → 立即 return，不再调用任何外部服务
   —— 用户已删除后继续调 OCR/Dify 是在烧配额，也违反删除语义
```

#### 8.4.3 批次并发的汇合

```java
List<CompletableFuture<BatchOutcome>> futureList = ...;   // 4 个批次
CompletableFuture.allOf(futureList.toArray(new CompletableFuture[0]))
                 .exceptionally(ex -> null)               // 不让单个异常中断等待
                 .join();                                 // 等全部结束，含失败的

// 主线程一次性裁决，failCode 优先级固定：
//   任一批技术失败（超时/429/5xx） → SERVER_ERROR   （最高）
//   全部批 UNREADABLE              → UNREADABLE
//   全部批 NO_REPORT_FEATURE       → NOT_HEALTH_REPORT
//   部分批 UNREADABLE              → 继续，partialReason += BATCH_UNREADABLE
```

**`BatchOutcome` 只带数据与失败原因，不带任何数据库操作。**
「任一批失败但等其余跑完」的语义由 `allOf` + `exceptionally` 实现，
不需要取消传播（§7.2 已定：不取消，跑完丢弃）。

### 8.5 定时任务

| Job 类 | 建议频率 | 职责 |
|---|---|---|
| `TaskDeadlineSweepJob` | 1 分钟 | **两个条件分别处理**，见 §8.5.1 |
| `QueuedTimeoutSweepJob` | 1 分钟 | `status = QUEUED` 且 `create_time < now - 5min` → `FAILED / SERVER_ERROR`，`reanalyzable = 1` |
| `ResourceCleanupJob` | 5 分钟 | 按 §8.6 清理矩阵；顺带 `AnalysisQueue.reclaimStalePending(15min)` |
| `DishTagPrewarmJob` | 每日凌晨 | §9 离线打标 |
| `DishTagCleanupJob` | 每周 | §9.9 标签清理，**与预热串行，不得并发** |

#### 8.5.1 硬 deadline 与心跳是两件事

上一版只写了「心跳陈旧 15 分钟」这一个条件，**漏掉了 10 分钟硬 deadline 的执行**
——心跳线程只要还活着就一直续期，`heartbeat_at` 永远不陈旧，任务可以无限超过 deadline。

```sql
-- 条件一：超过硬截止（Worker 还活着但跑太久）
UPDATE ct_health_report_task
   SET status='FAILED', fail_code='EXECUTION_TIMEOUT', reanalyzable=1, version=version+1
 WHERE status IN ('PARSING','EXTRACTING','ASSEMBLING')
   AND deleted_at IS NULL AND deadline_at <= NOW();

-- 条件二：心跳陈旧（Worker 进程已消失）
UPDATE ct_health_report_task
   SET status='FAILED', fail_code='SERVER_ERROR', reanalyzable=1, version=version+1
 WHERE status IN ('PARSING','EXTRACTING','ASSEMBLING')
   AND deleted_at IS NULL AND heartbeat_at < NOW() - INTERVAL 15 MINUTE;
```

**心跳只更新 `heartbeat_at`，绝不延长 `deadline_at`。** `deadline_at` 在领取时一次写定，
之后只读不写——否则硬截止形同虚设。

**PEL 清理的 `minIdle` 必须大于 deadline + 巡检周期**（15 分钟满足），
否则会在任务还在正常跑的时候把它的 Stream 条目清掉。

#### 8.5.2 Job 类写法

**业务逻辑写在 `domain` 层的 Service 里，Job 类只是薄薄一层入口。**
这样同一段逻辑既能被 xxl-job 触发，也能被单测直接调用、被运维手工触发。

```java
/**
 * 任务心跳巡检。
 * <p>把 Worker 进程消失后卡在执行中状态的任务收敛为 FAILED。
 * 只写终态，<b>不重新执行任务</b>（全案零重试）。</p>
 *
 * <p><b>TODO 待注册到 xxl-job：</b>JobHandler 名称 {@code taskHeartbeatSweepJob}，
 * 建议 Cron {@code 0 * * * * ?}（每分钟），路由策略「第一个」，
 * 阻塞处理策略「丢弃后续调度」，超时 120 秒，失败不重试。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskHeartbeatSweepJob {

    private final TaskSweepService taskSweepService;

    @XxlJob("taskHeartbeatSweepJob")
    public void execute() {
        int sweptCount = taskSweepService.sweepStaleHeartbeat();
        log.info("心跳巡检完成, 收敛任务数={}", sweptCount);
    }
}
```

其余四个同此形式，类注释里写清楚 **JobHandler 名称、建议 Cron、路由策略、阻塞策略、超时、
是否允许失败重试**，让注册的人不用回来问。

**各 Job 的注册参数建议：**

| JobHandler 名称 | Cron | 阻塞策略 | 超时 | 失败重试 |
|---|---|---|---|---|
| `taskHeartbeatSweepJob` | `0 * * * * ?` | 丢弃后续调度 | 120s | 否 |
| `queuedTimeoutSweepJob` | `0 * * * * ?` | 丢弃后续调度 | 120s | 否 |
| `resourceCleanupJob` | `0 0/5 * * * ?` | 丢弃后续调度 | 600s | 否 |
| `dishTagPrewarmJob` | `0 30 2 * * ?` | **单机串行** | 7200s | 否 |
| `dishTagCleanupJob` | `0 0 4 ? * SUN` | **单机串行** | 1800s | 否 |

**`dishTagPrewarmJob` 与 `dishTagCleanupJob` 必须互斥，但 xxl-job 做不到。**

xxl-job 的「单机串行」阻塞策略只约束**同一个 Handler** 的重复触发，
**两个不同 Handler 之间没有任何互斥**；仅靠 Cron 错开也挡不住预热超时、补跑和人工触发。

用一把共享的 Redis 锁：

```java
// Key: lock:dish-tag，两个 Job 用同一个 Key
// Value: owner token（UUID），释放时校验 owner，防止误释放别人的锁
// 租期: 预热 2h，清理 30min；执行中定期续期
boolean acquired = dishTagLock.tryAcquire(ownerToken, leaseDuration);
if (!acquired) {
    log.warn("未取得 dish-tag 互斥锁，本次跳过, job={}", jobName);
    return;
}
try { ... } finally { dishTagLock.releaseIfOwner(ownerToken); }
```

不这么做仍会重现评审 3.14 的时序：预热 diff 看到旧标签而跳过，清理随后把它删掉，
该菜当天直接变成 `TAG_MISSING`。

**全部 Job 失败不重试**：与全案零重试口径一致。巡检类任务下一个周期自然会重新扫到；
预热任务失败留到次日 diff 补，当天缺标签的菜按 §9.7 不进推荐列表，方向安全。

**幂等性**：五个 Job 都必须可重复执行。巡检与清理天然幂等（条件更新）；
预热靠 `ct_dish_tag` 的唯一键 + `insertIgnore` 保证。

**`QueuedTimeoutSweepJob` 是投递恢复的替代方案**（评审 3.7）。
XADD 在事务提交前执行，仍不能排除 Redis 故障切换、异步持久化丢写导致
「任务是 `QUEUED` 但没有消息」。**按零重试口径不补投消息，而是让它超时失败**
——用户看到明确错误并可点「重新解析」，好过永远卡在等待中。

### 8.6 清理矩阵

| 任务状态 | S3 原始文件 | 文件行 | 任务行 | Redis 结果 |
|---|---|---|---|---|
| 执行中 | 保留 | 保留 | 保留 | — |
| `SUCCEEDED` | 立即删 | 立即删 | 至 `purge_at` | TTL 2h |
| `FAILED` 且 `reanalyzable=1` | 至 `file_expire_at` | 至 `file_expire_at` | 至 `purge_at` | 无 |
| `FAILED` 且 `reanalyzable=0` | 立即删 | 立即删 | 至 `purge_at` | 无 |
| `deleted_at` 非空 | 立即删 | 立即删 | 至 `purge_at` | 立即删 |
| 孤儿上传（`task_id IS NULL`） | `expire_at` 到期删 | 同左 | — | — |

**必须逐类判定，不能笼统"终态即删"**——`FAILED` 也是终态，笼统删会在用户点
「重新解析」之前把文件删掉。

**S3 删除必须先于文件行删除**，S3 失败时保留行等下一轮。

### 8.7 结果接口响应结构

```jsonc
{
  "code": "OK",
  "data": {
    "partial": false,
    "partialReasonList": [],                    // 数组，可多值
    "processedPages": 12, "totalPages": 12,

    "indicatorModule": {
      "overview": { "total": 52, "normal": 40, "abnormal": 12,
                    "abnormalRatio": 23, "fromReport": false },
      "groupList": [{
        "sectionId": "0-15", "displayName": "血脂检查",
        "cardList": [{
          "indicatorId": 7, "name": "甘油三酯", "value": "2.8", "unit": "mmol/L",
          "refRange": "0.56~1.70", "conclusionText": "↑偏高", "status": "HIGH"
        }]
      }],
      "disclaimer": "以上指标数据均来自体检报告原文，仅供参考，如有疑问请咨询医生。"
    },

    "problemModule": {
      "itemList": [{
        "sourceType": "INDICATOR_NUMERIC",
        "displayName": "甘油三酯偏高", "displayNameGenerated": true,
        "sourceLabel": "血脂检查–甘油三酯",
        "rawText": "甘油三酯 2.8 mmol/L 0.56~1.70 ↑偏高",
        "indicatorId": 7
      }],
      "emptyText": null,
      "disclaimer": "以上内容均为体检报告原文结论的汇总，不构成二次诊断，如有疑问请咨询医生。"
    },

    "adviceModule": {
      "allergenList": [{
        "enumKey": "SHRIMP_CRAB",          // OTHER 时为 "OTHER"
        "displayName": "虾蟹类",            // OTHER 时取报告原文 rawName
        "isFoodBorne": true,
        "sourceLabel": "过敏原检查–虾蟹类 阳性(+)",
        "rawText": "过敏原检查  虾蟹类  阳性(+)",
        "avoidFoodList": ["虾","蟹","龙虾"],     // isFoodBorne=false 或 OTHER 时为空数组
        "hiddenFoodList": ["虾丸","蟹棒"]        // 同上
      }],
      "nutritionList": [{
        "enumKey": "IRON", "displayName": "补充铁",
        "sourceLabel": "总检结论–建议补充铁剂",
        "rawText": "建议补充铁剂",
        "recommendFoodList": ["猪肝","鸭血"],
        "intakeNoteList": ["动物肝脏每周1~2次，每次约50g"],
        "pairingTipList": ["与富含维生素C的蔬果同食可促进铁吸收"]
      }],
      "dietRequirementList": [{
        "enumKey": "LOW_FAT", "displayName": "低脂饮食",
        "sourceLabel": "总检结论–建议低脂低盐饮食",
        "rawText": "建议低脂低盐饮食",
        "recommendFoodList": [], "avoidFoodList": [],
        "recommendDishPatternList": [], "avoidDishPatternList": [],
        "cookingTipList": [], "behaviorTipList": []
      }],
      "allergenEmptyText": null,           // 该区为空时下发文案，否则 null
      "nutritionEmptyText": null,
      "dietRequirementEmptyText": null,
      "disclaimer": "以上建议均基于体检报告原文，不构成医疗或营养处方，具体饮食方案请遵医嘱。"
    },

    "dishModule": {
      "recommendList": [{ "dishId": 10023, "dishName": "菠菜猪肝汤",
                          "tagList": [{"type":"NUTRITION","text":"补铁"}],
                          "reason": "菠菜猪肝汤——含猪肝、菠菜；报告原文：「建议补充铁剂」" }],
      "rejectList": [{ "dishId": 10088, "dishName": "白灼虾",
                       "tagList": [{"type":"ALLERGY","text":"虾蟹过敏"}],
                       "reason": null }],
      "emptyText": null,
      "disclaimer": "推荐菜品基于体检报告内容及食堂菜品数据自动匹配，菜品信息以食堂实际上架为准。"
    }
  }
}
```

**模块被抑制时该模块字段为 `null`**（不是空对象、不是缺字段）。
**静默隐藏，不下发任何原因文案**（产品已确认）——`partialReasonList` 仅供排障与埋点，
接口可以下发但前端不展示。

> **已知接受的风险：** 用户会看到结果页少两块内容且没有任何解释。产品明示接受。

**空态文案由后端下发**，前端不自行拼装。**不分页**。

**null / 空数组的语义必须严格区分：**

```
模块字段 = null        该模块被抑制（降级），前端隐藏整块
模块字段 = 对象        模块参与，内部列表可能为空
列表 = []              有内容但一条都没命中，配合 emptyText 展示
emptyText = null       该区有内容，不展示空态句
tagList 至少一项       进入任一列表的菜必有标签
reason = null          仅不推荐列表允许（过敏拒绝不下发理由，§9.8）
```

**列表长度：** `dishModule.recommendList` 与 `rejectList` 长度均为 `0..3`（§9.8.1）。

**必填与可空一览：** `partialReasonList` 恒为数组（可空数组）；
`overview.fromReport` 恒有值；`indicatorId` 仅 `INDICATOR_NUMERIC` 类型的健康问题下发；
`displayNameGenerated` 恒有值。

#### 8.7.1 五个占位接口的视图对象

实现可以是 TODO，**字段定义不能是 TODO**——否则 Dify / OCR / 菜品三个接入方会各自猜。

```java
class LlmARequest {
    String promptVersion;              // a-1.1.0
    List<SegmentView> segmentList;     // segmentId + normalizedText，按 seq 升序
    List<byte[]>      pageImageJpegList; // 与本批页面一一对应；DOC/DOCX 为空列表
    int    batchIndex; int batchCount;
}
class SegmentView { String segmentId; String normalizedText; int page; }

class LlmBRequest {
    String promptVersion;              // b-1.1.0
    String enumKey;                    // 本批打标维度
    String enumDisplayName;
    List<String> contentHintList;      // 内容常量的提示词片段（§9.4）
    List<DishForTagView> dishList;     // ≤40 道
}
class DishForTagView { long dishId; String dishName; List<String> ingredientNameList; }

class OcrBlock {
    String text;
    double x; double y; double width; double height;   // 页面坐标系，左上原点
    double confidence;                                 // 0~1，低于阈值的块仍返回，由 Java 决定取舍
}

/**
 * 菜品视图。由 DishQueryService 的实现方按本结构返回，本工程不感知其真实表结构。
 * <p>只需要这四类信息：菜品ID、菜品名称、食材名称、该食材在这道菜里的重量。</p>
 */
@Data
public class DishView {
    /**
     * 菜品ID。<b>全系统唯一</b>，已由菜品数据方确认（2026-08-24）。
     * 这是 §9.5 打标缓存 Key 不含租户/食堂维度的前提——它一旦不成立，跨食堂会读到错误标签。
     */
    private Long dishId;

    /** 菜名。非空。原样返回即可，规范化由本工程做（§6.6）。 */
    private String dishName;

    /**
     * 食材列表。<b>可以为空列表，但不要返回 null</b>。
     * <p>无食材明细的菜品照常返回，本工程按 §9.3 处理（该菜营养维度全 NEUTRAL）。</p>
     */
    private List<DishIngredientView> ingredientList;
}

/**
 * 菜品中的一项食材。
 */
@Data
public class DishIngredientView {
    /** 食材名称。非空。原样返回，不需要贵方做任何归一化。 */
    private String name;

    /**
     * 该食材在这道菜里的重量，<b>单位必须是克</b>。
     * <p>贵方原始数据若是千克/份/勺/两，请在此处换算完毕；本工程不做单位换算。</p>
     * <p><b>未知时给 null，不要给 0</b>——0 会被当成「确实是 0 克」，null 才是「不知道」。</p>
     */
    private Double weightG;
}
```

#### 8.7.2 菜品接口的六条约定

这六条不写清楚，联调时一定会出问题：

| # | 约定 | 不遵守的后果 |
|---|---|---|
| 1 | **`weightG` 单位是克**，贵方换算完毕 | 主料推导（§9.3）按克算占比，单位错则整套阈值失效 |
| 2 | **未知重量给 `null`，不要给 0** | 0 会被当成「确实是 0 克」参与总重计算，压低其他食材占比 |
| 3 | **调味料也要返回**（油、盐、酱油、蚝油…） | 本工程自己用 `SeasoningWords` 排除它们做主料推导，但**过敏判定要看全部食材**——蚝油是贝类来源，漏掉就漏拦 |
| 4 | **同名食材可以出现多行**，本工程按 §9.3 合并求和 | 无需贵方去重 |
| 5 | **食材顺序稳定**（同一道菜每次返回顺序一致） | `dishHash` 会先排序所以不影响正确性，但顺序抖动会让排障日志无法比对 |
| 6 | **不要返回任何用户数据** | 离线打标的输入必须与用户无关，这是打标结果可跨用户复用的前提 |

**两个方法的区别：**

```java
/** 在线组装用：当前用户可见的、指定业务日在架的菜品。 */
List<DishView> listOnShelfDishes(String userId, LocalDate bizDate);

/** 离线打标用：指定业务日全部在架菜品，【不限用户】。 */
List<DishView> listAllOnShelfDishes(LocalDate bizDate);
```

**离线那个方法不能带 `userId`**——它一次给全部菜品打标，结果跨用户复用。
如果贵方的可见范围逻辑复杂，离线方法返回「所有食堂当日在架的并集」即可，宁多勿少：
多打的标签只是没人用，少打的标签会让菜品变成 `TAG_MISSING` 而不进推荐列表（§9.7）。

**如果贵方的接口天然是扁平行（一行一个食材）**，映射很简单：按 `dishId` 分组即可，
不需要为此改造，本工程只要最终拿到 `DishView` 结构。

---

## 9. 离线打标

### 9.1 维度划分

| 维度 | 枚举数 | 判定方 |
|---|---|---|
| 食入性过敏原 | 11 | LLM-B 打标 + Java 关键词兜底**取并集** |
| 饮食注意 | 9 | LLM-B 打标 |
| 营养补充 | 9 | **纯 Java 确定性交集**，不调模型 |
| 吸入性过敏原 | 5 | 不参与 |

**LLM-B 实际打标 20 个维度。** 枚举与词表同源于 `allergen_display_split.csv`（11 组 73 词）。

### 9.2 营养维度是纯 Java

```java
// 只取 NutritionContents 的 recommendableFoodList（displayOnlyFoodList 永不参与）
// 匹配方式 CANONICAL_EXACT：规范化后整串相等，不是子串
Set<String> eligibleFoodSet = NutritionContents.ALL.get(enumKey).getRecommendableFoodList();
Set<String> matched = intersect(mainIngredients(dish), eligibleFoodSet);   // §9.3
verdict = matched.isEmpty() ? NEUTRAL : RECOMMEND;
```

**两个食材列表是本工程与医务之间的关键分界。**
医务可以把「菠菜」写进 `displayOnlyFoodList`（植物来源，科普有价值），
同时拒绝把它放进 `recommendableFoodList`（非血红素铁生物利用率低，
不足以支撑「这道菜主料是菠菜就推给缺铁用户」）。
**没有这个分界，审一句文案等于批一条推荐规则。**

不能只校验「模型返回的食材属于主料」——模型若认为「大米补铁」而大米确实是主料，
校验会原样放行，一道白米饭就成了补铁推荐菜。

### 9.3 主料推导与异常数据（评审 6.2）

```java
Set<String> mainIngredients(DishView dish) {
    List<DishIngredientView> bodyList = new ArrayList<>(dish.getIngredientList().size());
    Map<String, Double> mergedWeightMap = new LinkedHashMap<>(dish.getIngredientList().size());
    for (DishIngredientView ingredient : dish.getIngredientList()) {
        String normalizedName = TextNormalizer.normalize(ingredient.getName());
        if (SeasoningWords.contains(normalizedName)) { continue; }        // 排除调味料
        Double weight = ingredient.getWeightG();
        if (weight == null || weight <= 0d) { continue; }                 // 负重量、零重量、null 一律跳过
        if (weight > MAX_REASONABLE_WEIGHT_G) { continue; }               // 5000g，异常大值跳过并计数
        mergedWeightMap.merge(normalizedName, weight, Double::sum);       // 同名食材多行合并
    }
    if (mergedWeightMap.isEmpty()) { return Collections.emptySet(); }     // 该菜营养维度全 NEUTRAL
    double total = mergedWeightMap.values().stream().mapToDouble(Double::doubleValue).sum();
    if (total <= 0d) { return Collections.emptySet(); }
    // 规则一：占比 ≥ 25%
    // 规则二：重量前 2 名且占比 ≥ 15%（并列时按名称字典序取前 2，保证确定性）
    // 两条取并集；都不满足则取最重的一个
}
```

**单位统一由 `DishQueryService` 的实现方负责**——本工程只接受克。
接入方若原始数据是千克/份/勺，必须在视图映射时换算完毕。

### 9.4 LLM-B 打标契约与 `evidenceType`

**紧凑格式：`NEUTRAL` 只回 ID，不回完整对象。**

```jsonc
{
  "enumKey": "LOW_FAT",
  "neutralDishIds": [10001, 10003, 10004, 10005],  // 已核验、结论为 NEUTRAL，只回 ID
  "hitList": [                                    // 只有命中项携带证据
    { "dishId": 10002, "verdict": "REJECT",
      "evidenceType": "COOKING", "matchedIngredients": [], "reason": "油炸菜品" },
    { "dishId": 10006, "verdict": "REJECT",
      "evidenceType": "INGREDIENT", "matchedIngredients": ["五花肉"], "reason": "主料为肥肉" }
  ]
}
```

**当前 20 个维度全部只允许 `REJECT`。**

```
DietRequirementRule 结构上【没有可推荐字段】——只有 avoidFoodList / avoidDishPatternList
营养维度的可推荐食材在 NutritionContents，而营养由 Java 确定性交集计算，不走 LLM-B
→ LLM-B 的 20 个维度不可能产生推荐，这是类型保证的
```

上一版 Schema 允许饮食注意维度返回 `RECOMMEND`，而 `DietRequirementRule` 里根本没有对应字段
——**「展示内容不等于推荐规则」这个核心设计因此实际没有生效**。
Prompt 也把全部 `RECOMMEND_FOOD`（在饮食注意维度全是 `DISPLAY_ONLY`）当成「推荐食材」注入了。

**修正后：`hitList.verdict` 的枚举是 `["REJECT"]`**，由 §10.4 的契约测试与
`DietRequirementRule` 的结构保持一致。若将来给该类加上可推荐字段，
契约测试会立刻失败，提醒同步放开 Schema——不会再出现「Schema 允许但没有规则」的错配。

`NEUTRAL` 不出现在 `hitList`，一律进 `neutralDishIds`。

**紧凑不等于缩小覆盖范围。** 离线打标不携带任何用户报告与过敏信息，
无法预先按某个用户的过敏原剔除菜品，所以逻辑覆盖仍是
**当日在架全部菜品 × 20 个维度**；稳态下靠 MySQL diff 跳过
`dishHash` 与 `tagPolicyVersion` 均未变的组合，
但**本次送进模型的每一道菜都必须在响应里有明确归属**。

#### 9.4.1 Java 集合完整性校验

```java
Set<Long> expected = 本批输入 dishId 集合;
Set<Long> neutral  = new HashSet<>(response.getNeutralDishIds());
Set<Long> hit      = response.getHitList().stream()
                             .map(HitItem::getDishId).collect(Collectors.toSet());

// 四条全部成立才有效，任一不成立整批作废
neutral.size() == response.getNeutralDishIds().size()   // neutral 内部无重复
hit.size()     == response.getHitList().size()          // hit 内部无重复
Collections.disjoint(neutral, hit)                       // 两集合不相交
union(neutral, hit).equals(expected)                     // 精确覆盖，不多不少
```

**整批作废时不写 MySQL、不写 Redis**，按全案零重试让本批预热失败并告警。
**绝不把遗漏的菜静默补成 `NEUTRAL`**——那正是「模型没返回 ≠ 没问题」要防的事。

校验通过后 Java 把 `neutralDishIds` 展开成 `verdict = NEUTRAL` 的 `ct_dish_tag` 行，
与 `hitList` 一并落库。**数据库仍保留完整的
`(dishId, dishHash, tagPolicyVersion, enumKey)` 覆盖**，
在线路径（§9.6）无需增加覆盖表或特殊查询，`TAG_MISSING` 的语义也不变。

**`evidenceType` 是本轮新增的**（评审 3.13）。原设计规定「匹配食材全部对不上则降 `NEUTRAL`」，
但提示词又允许模型依据菜名、烹饪工艺、隐藏成分判定，此时 `matchedIngredients` 合法为空
——按原规则这些判定会被全部清除，包括「白灼虾」这种最该拦的。

```java
switch (evidenceType) {
    case INGREDIENT:
        // matchedIngredients 必须非空且全部属于该菜食材表，剔除对不上的
        // 全部对不上 → 降 NEUTRAL
        break;
    case DISH_NAME:
    case COOKING:
        // matchedIngredients 允许为空，但 reason 必须非空
        // reason 为空 → 降 NEUTRAL
        // 单独计数 evidenceTypeInferredCount，供人工抽查
        break;
}
```

**任何维度**的 `hitList` 出现 `RECOMMEND` → Java 一律按 `NEUTRAL` 处理并告警。
**`DISPLAY_ONLY` 的规则绝不注入 LLM-B 提示词**——它们只用于模块三展示。

### 9.5 版本与 `dishHash`

```
tagPolicyVersion = sha256Hex(modelVersion + "|" + promptVersion + "|" + tagRuleVersion)

dishHash = sha256Hex(
    normalize(dishName) + "|" +
    食材按 normalize(name) 字典序排序后 join(",", name + ":" + round(weightG, 1)))
```

**食材别名在算 hash 之前不应用**（评审 6.3）——别名表会随迭代扩充，
应用在 hash 里会导致别名表一变就全量重打。别名只在 §9.2 的交集匹配时应用。

`matched_ingredients` 在 MySQL 里存 **JSON 数组字符串**，用 Jackson 序列化，
天然处理食材名含逗号的情况。

**`tagRuleVersion` 忘记 bump 的后果是静默的**：菜和食材都没变，diff 会认为标签已存在而跳过，
新规则永远不生效。**建议 CI 加校验：`constants/` 有 diff 但 `TagRuleVersion.VALUE` 没变则构建失败。**

### 9.6 在线读取路径

```
① DishQueryService.listOnShelfDishes(userId, today)      TODO 占位
② 逐菜算 dishHash
③ 每个生效维度一次 HMGET（生效维度 = 报告命中的过敏原 ∪ 饮食注意，典型 3~6 个）
④ ③ 返回 null 的回源查 ct_dish_tag（一次 IN 查询，走 idx_online）
     MySQL 8.0.14+ 支持行构造器 IN 的索引区间扫描，需确认部署版本
⑤ 回源命中的按 §4.5 回填 Redis（仅当日 Key 已存在时）
⑥ 仍查不到 → TAG_MISSING
```

**在线路径零写入 `ct_dish_tag`**，只有离线任务写。

### 9.7 `TAG_MISSING ≠ NEUTRAL`

```java
enum TagState { TAG_MISSING, NEUTRAL, RECOMMEND, REJECT }
```

```
可产生 REJECT 的维度 = 生效的食入性过敏原 ∪ 生效的饮食注意
该菜在上述任一维度 TAG_MISSING → 不进推荐列表，也不进不推荐列表（完全不展示）
营养维度不在此列（Java 现算，永不缺失）
```

**为什么不能当 `NEUTRAL`**：报告同时有「补充蛋白质」和「低脂饮食」，某道油炸肉菜的
`LOW_FAT` 标签缺失，Java 因肉是主料判它补蛋白，缺失维度当 `NEUTRAL` → 这道油炸菜进推荐列表。

**为什么不进不推荐列表**：我们并不知道它违规，只是没核验过。放进不推荐是错误指控。

### 9.8 合并裁决

```java
if (任一过敏维度 REJECT)              return NOT_RECOMMENDED;   // 只带过敏标签，无正面标签
if (任一维度 REJECT)                  return NOT_RECOMMENDED;   // 推荐标签作灰色附注
if (任一可REJECT维度 TAG_MISSING)     return HIDDEN;
if (任一维度 RECOMMEND)               return RECOMMENDED;
return NEUTRAL;
```

推荐理由 Java 拼接：`{菜名}——含{命中食材}；报告原文：「{segment原文}」`。
两个列表按菜名拼音首字母排序（TinyPinyin，请求时实时算），非汉字开头排在汉字之后。

#### 9.8.1 推荐列表可能为空，这是预期行为

**正向证据只来自营养维度（Java 交集）。** LLM-B 的 20 个维度只产生 `REJECT`，
所以报告若没有任何营养补充建议，**没有任何菜能进推荐列表**：

```
报告：虾蟹过敏 + 低脂饮食（无营养补充建议）
生效维度：SHRIMP_CRAB + LOW_FAT，两者都只能 REJECT
  含虾蟹 / 违反低脂 → NOT_RECOMMENDED
  其余全部          → NEUTRAL
recommendList     → 空
rejectList        → 有菜
```

**当前规则不是「剔除禁忌后从剩余菜里推荐 3 道」，而是「必须有明确正向证据才进推荐列表」。**
产品已确认保持此口径（2026-08-24）。

两条支撑理由：

```
需求 §8-4 要求每道推荐菜给出「推荐理由：引用对应饮食建议原文」
  一道只是「没被拒绝」的菜，这句话写不出来 —— 没有可引用的建议原文

「没被拒绝」≠「适合你」
  未打标的菜、模型没看出问题的菜都会落在 NEUTRAL，
  把它们当推荐等于重新定义 NEUTRAL 的安全语义
```

**这种报告不罕见**：体检报告写「低脂饮食」的概率远高于写「建议补铁」，
所以推荐区空态会是常见形态，前端与产品需按常态设计而非异常处理。
空态文案见 §9.10.2。

#### 9.8.2 每个列表最多 3 道

```
recommendList 最大长度 = 3
rejectList    最大长度 = 3
```

**截断必须发生在全部维度合并裁决完成之后：**

```java
List<DishVerdict> verdictList = mergeAllDimensions(dishList);   // 含过敏强制改判
List<Dish> recommended = filter(verdictList, RECOMMENDED);
recommended.sort(PINYIN_COMPARATOR);
List<Dish> finalRecommend = recommended.subList(0, Math.min(3, recommended.size()));
```

**绝不能在合并完成前截断**——先取 3 道再判过敏，可能把本该被过敏规则剔除的菜留下，
而真正安全的菜因为排序靠后被丢掉。

当前没有菜品评分模型，所以沿用确定性的拼音排序再 `limit(3)`。
**将来若要按推荐程度、热度或营养价值排序，必须另行定义评分字段与并列裁决规则**，
不能由开发临场发挥。

| 约束 | 值 |
|---|---|
| `recommendList` / `rejectList` 长度 | `0..3` |
| 候选超过 3 道 | 截断**不得改变原排序**，取前 3 |
| 不足 3 道 | 按实际数量返回，**不用占位菜补满** |
| `HIDDEN` 与全 `NEUTRAL` 的菜 | 仍不进任何列表 |

### 9.9 标签清理（评审 3.14）

**不能按 `create_time` 清理。** 一条标签只在菜品或策略版本变化时重算，
长期有效的标签 `create_time` 会越来越早，按时间清会误删仍在用的标签；
更糟的是预热 diff 确认标签存在而跳过之后、清理任务把它删掉，该菜当天直接 `TAG_MISSING`。

```
删除条件（同时满足）：
  tag_policy_version ∉ 保留版本集合（当前版本 + 前一版本）
  或  该 (dish_id, dish_hash) 已连续 30 天未出现在任何一天的在架菜品中
清理任务与预热任务用 §8.5.2 的 Redis 互斥锁保证不并发（xxl-job 的阻塞策略做不到跨 Handler 互斥）
「连续 30 天未在架」通过 `DishQueryService.listAllOnShelfDishes(date)` 逐日回查判定，
或由实现方提供一个「历史在架 dishId 集合」查询；后者更省，属接入契约
```

### 9.10 排序与空态

#### 9.10.1 拼音首字母排序

```java
// TinyPinyin，请求时实时计算，不落库、不加索引
// 取每个汉字的拼音首字母拼成排序键；非汉字字符保持原样
// 排序键相同时按 dishId 升序，保证结果确定
Comparator<Dish> PINYIN_COMPARATOR =
        Comparator.comparing(DishSorter::pinyinKey)
                  .thenComparing(Dish::getDishId);
```

**非汉字开头的菜名统一排在汉字之后**（数字、字母、符号开头的菜名）。
实现方式：`pinyinKey` 对非汉字开头的名称加一个高位前缀。

#### 9.10.2 四个模块的全部文案

**空态与声明由后端下发，前端不自行拼装。** 全部逐字照抄需求，不得改写。

| 模块 | 触发条件 | 文案 |
|---|---|---|
| 一 指标 | 无任何符合准入的指标 | 本次报告未提取到带明确结论的指标项 |
| 一 指标 | 底部声明（恒展示） | 以上指标数据均来自体检报告原文，仅供参考，如有疑问请咨询医生。 |
| 二 问题 | 三类来源全空 | 本次体检各项指标均在正常范围内，请继续保持良好的生活习惯。 |
| 二 问题 | 底部声明 | 以上内容均为体检报告原文结论的汇总，不构成二次诊断，如有疑问请咨询医生。 |
| 三 建议 | 过敏提醒区为空 | 本次体检报告未涉及过敏原相关内容 |
| 三 建议 | 营养补充区为空 | 本次体检报告未涉及营养补充相关内容 |
| 三 建议 | 饮食注意区为空 | 本次体检报告未涉及饮食注意相关内容 |
| 三 建议 | 底部声明 | 以上建议均基于体检报告原文，不构成医疗或营养处方，具体饮食方案请遵医嘱。 |
| 四 菜品 | 饮食建议无任何正式枚举内容 | 本食堂菜品暂无个性化推荐。 |
| 四 菜品 | 有建议但两列表都空，或只有拒绝无推荐（§9.8.1 的常见形态） | 本次未匹配到符合建议的食堂菜品，菜品以食堂实际上架为准。 |
| 四 菜品 | 底部声明 | 推荐菜品基于体检报告内容及食堂菜品数据自动匹配，菜品信息以食堂实际上架为准。 |

**全案不出现任何提示、说明、警示文字**（§13-5 的产品决策）。
上表之外不得添加任何面向用户的文字——包括降级原因、跳过说明、能力边界提示。

**模块被抑制时该模块字段为 `null`**，不下发空态文案（§8.7）——
空态是「模块在但没内容」，抑制是「模块整个不出现」，两者不能混。

---

## 10. 内容常量

**真源是 Java 常量类**，位于 `com.example.healthreport.constants`。
**没有 CSV、没有生成器、没有运行时加载**——改词表就是改代码，走 code review 与发版。

| 类 | 内容 | 状态 |
|---|---|---|
| `AllergenGroups` | 16 组 + 73 词条，展示与 Layer 1 共用 | 🟡 全部 `DRAFT` |
| `AllergenExceptions` | 13 条误杀例外，只作用于菜名 | 🟡 全部 `DRAFT` |
| `NutritionContents` | 9 维度，含可推荐 / 仅展示两个食材列表 | 🟡 全部 `DRAFT` |
| `DietRequirementContents` | 9 维度，**结构上无可推荐字段** | 🟡 全部 `DRAFT` |
| `TagRuleVersion` / `DisplayContentVersion` | 两个版本号 | — |

详见 `constants/内容常量说明V3.md`。

### 10.1 四条关键语义

**① 判定效果由字段位置表达，不靠标记列**

```java
NutritionRule.recommendableFoodList      参与 §9.2 主料交集 → 可触发推荐
NutritionRule.displayOnlyFoodList        只出现在模块三 → 永不触发推荐
DietRequirementRule.avoidFoodList        产生 REJECT
DietRequirementRule.displayOnlyFoodList  只展示（本类没有可推荐字段）
```

**字段放错位置会编译报错，布尔标记填错不会。** 这比上一版的
`recommendationEligible` 布尔列更难写错。

**② `DietRequirementRule` 没有可推荐字段 ⇒ LLM-B 只能返回 `REJECT`**

仅凭食材证明不了一道菜真的低脂、低盐或低能量——用油量与做法才是决定因素，
而菜品接口给不了这些。所以饮食注意维度只挑该拒绝的菜。
`llm_b_output.schema.json` 的 `verdict` 枚举因此是 `["REJECT"]`，
由 §10.4 的契约测试与 Java 类保持一致。

**③ `evidenceLevel` 决定是否进 Java 硬匹配**

```
DIRECT / LIKELY  → Layer 1 SUBSTRING 硬匹配
POSSIBLE         → 只进 LLM-B 提示词，MatchMode 必须是 MODEL_ONLY
```

`AllergenWord.isHardMatchable()` 已把这条固化：只有 `REVIEWED` 且 `SUBSTRING` 才参与硬匹配。

**④ 例外只作用于菜名，食材命中永远优先**

```
「鱼香肉丝」的菜名例外只取消菜名中「鱼」的命中
该菜配料表若另含鱼露 → FISH 仍然 REJECT
```

「部分为」「视配方」这类条件性复合菜名不进例外表，归 `POSSIBLE + MODEL_ONLY`。

### 10.2 `matchMode` 语义

| matchMode | 语义 | 用在哪 |
|---|---|---|
| `SUBSTRING` | 规范化后**子串包含** | 过敏 Layer 1 |
| `CANONICAL_EXACT` | 规范化后**整串相等** | 营养维度主料交集（§9.2） |
| `MODEL_ONLY` | 不做 Java 匹配，只作线索 | `POSSIBLE` 词、无法从食材证明的饮食维度 |
| `NONE` | 纯文案 | 摄入量、搭配贴士、烹饪与行为建议 |

**规范化包含 ASCII 大小写折叠**（`toLowerCase(Locale.ROOT)`），
所以 `xo酱` 能命中 `XO酱`。NFKC 不做大小写折叠，必须单独执行。

### 10.3 审核状态与两个版本号

```
枚举身份   AllergenKey / NutritionKey / DietRequirementKey、Schema enum、Prompt 枚举表
           → 不受 reviewStatus 影响，常量存在枚举就存在，接口契约不因审核进度抖动
生效规则   Layer 1 词表、营养交集集合、LLM-B 提示词注入内容
           → 只用 REVIEWED 的常量
发布激活   整个常量类原子生效，前置是【该类全部常量已裁决、无 DRAFT 残留】
           → 一次 PR 改完，评审时能看到完整 diff
```

`REJECTED` 的常量保留在类里但不生效，**必须出现在负向回归测试中**——
直接删掉会让它有机会下次悄悄被加回来。

```java
TagRuleVersion.VALUE        影响打标的内容改动 → 进 tagPolicyVersion → 全量重打标
DisplayContentVersion.VALUE 展示类内容改动 → 不触发重打标
tagPolicyVersion = sha256(modelVersion + "|" + promptVersion + "|" + tagRuleVersion)
```

### 10.4 契约测试防漂移

**没有生成器，靠测试拦。** 前两轮评审抓到的 12/21 vs 11/20 不一致，
根因就是 Java、Schema、Prompt 各写各的。

```java
@Test
void schema枚举必须与Java枚举一致() {
    assertEquals(enumNames(AllergenKey.class),
                 readEnum("schema/llm_a_output.schema.json", "$defs.allergenKey.enum"));
}
```

必须覆盖的一致性见 `constants/内容常量说明V3.md` §7。其中最关键的一条：
**`constants` 包有 diff 但 `TagRuleVersion.VALUE` 未变 → 构建失败**
——忘记 bump 的后果是静默的，预热 diff 会认为标签已存在而跳过，新规则永远不生效。

### 10.5 不在 `constants` 包的工程侧常量

| 常量类 | 内容 | 状态 |
|---|---|---|
| `AllergenSectionWords` | 螨 尘螨 屋尘 花粉 艾蒿 豚草 皮屑 猫毛 狗毛 霉菌 真菌 蟑螂 | 就绪 |
| `AllergenSuspectWords.SECTION_TITLES` | 过敏原 变应原 IgE 致敏原 过敏原筛查 | 就绪 |
| `AllergenSuspectWords.ADMITTED_RESULT_MARKS` | 见 §7.7，覆盖 `POSITIVE + BORDERLINE` | 就绪 |
| `ConclusionLabelWords` | 见 §7.8，按词长降序，**不含「阳性」「阴性」** | 就绪 |
| `NormalStatementWords` | 未见明显异常 未见异常 正常 无异常 未见占位（**不含「阴性」**） | 就绪 |
| `SeasoningWords` | 油 盐 糖 酱油 生抽 老抽 醋 料酒 淀粉 葱 姜 蒜 花椒 八角 香油 味精 鸡精 蚝油 | 就绪 |
| `HighRiskAdvicePatterns` | §8.3 方向矩阵，每条带 `scope` | 🟡 待医务确认完整性 |
| `IngredientAliasWords` | 见 §7.1，**常量一律写标准名** | 🟡 草案 |
| `RadicalNormalizeMap` | **只能基于真实污染样本加入**，每条留回归样本 | 🟡 空 |
| `DisclaimerConstants` / `EmptyStateConstants` | 需求原文逐字照抄 | 就绪 |

> `SeasoningWords` 含蚝油与香油，分别是软体贝类与芝麻的潜在来源。
> **该表只用于主料推导，不影响过敏判定**——过敏匹配范围是全部食材，不排除调味料。

## 11. 测试要求

### 11.1 必测回归项

```
□ LLM-A 缺字段 → 任务 FAILED，不重试，不折叠成空数组
□ 姓名/性别不一致 → IDENTITY_MISMATCH；同一人不同日期正常合并；姓名全空正常放行
□ 阴性过敏原绝不进入过敏提醒与菜品拦截
□ 30 项筛查表漏抽唯一阳性项 → ALLERGEN_SUSPECT_MISS，模块四不输出
□ 过敏微量命中仍 REJECT；模型给 RECOMMEND 的过敏菜被强制改判
□ TAG_MISSING 的菜既不进推荐也不进不推荐
□ 「乙肝表面抗体阴性，建议接种疫苗」不被 NormalStatementWords 误杀
□ 「未见明显异常」不被「异常」抢先命中；「不正常」不被「正常」命中；↑↑ 不被 ↑ 命中
□ 左眼/右眼同值 5.0 不被去重
□ 表格型 PDF 的指标五字段落在同一 segment，包含性校验通过
□ OCR 段的模糊匹配生效：OCR 读成 2.6、模型给 2.8 时该指标保留
□ 用户在 ASSEMBLING 阶段删除 → Worker 的 CAS 失败并删掉预写结果，GET 永远拿不到
□ 任务行未被提前删除：SUCCEEDED 后 2 小时内 GET /result 仍能通过归属鉴权
□ 重复调 analyze（相同 fileIds，原任务 QUEUED）→ FILE_ALREADY_BOUND 且返回原 taskId
□ 原任务 FAILED 且 reanalyzable → 同批 fileIds 可创建新任务
□ 陈旧 PEL 条目被清理，但正常运行中的任务不被误清
□ QUEUED 超过 5 分钟 → FAILED，不补投消息
□ 在线路径对 ct_dish_tag 的写入次数为 0，对 LLM-B 的调用次数为 0
□ tagRuleVersion 变更后 diff 判定为缺失并重打；只改 displayContentVersion 不触发
□ 标签清理不删仍在保留版本集合内的行
□ 归属不符的四类请求返回与任务不存在完全相同的 404 + RESULT_EXPIRED
□ 任何响应体不含 userId；任何日志不含姓名/报告原文/OCR文本
□ create_time / update_time 在 insert 与 update 的 SQL 中都不出现
□ 解压炸弹样本被拒；超大像素图片在解码前被拒；XXE 样本被拒

本轮评审新增：

□ 过敏章节「标题一行、数据行另一行」时，漏抽唯一阳性项能触发 ALLERGEN_SUSPECT_MISS
□ 一行含多项过敏原时，风险标记数 > 准入条目数同样触发降级
□ 阴性与 UNKNOWN 条目不计入证据覆盖
□ SUCCEEDED 后第 41 分钟仍能 GET，第 2 小时零 1 分才失效（purge_at 已刷新）
□ 任务执行超过 10 分钟被 deadline 巡检置 EXECUTION_TIMEOUT，心跳仍在也拦得住
□ 心跳线程在 start 抛异常、业务抛异常、stop 抛异常三种路径下都不泄漏
□ 预写结果后 CAS 抛异常 → finally 删除预写值，Redis 无残留
□ 领取 CAS 返回 0 的路径同样执行 ACK+DEL
□ ACK+DEL 用 Lua，XACK 成功 XDEL 失败的窗口不存在
□ initGroup 遇到非 BUSYGROUP 异常时启动失败，不静默吞掉
□ 双栏指标表不被聚成同一 logicalRow
□ 上下标（10⁹/L）不被拆成独立 segment
□ OCR 短字段（H/L/单字符单位）禁止模糊匹配
□ value 字段只走形近映射，不走通用编辑距离
□ 大小写不同的 task_id / dish_hash 不互相命中（ascii_bin 生效）
□ LLM-B 响应漏一个 dishId / 多一个 / 重复 / 两集合相交 → 整批作废且不写库
□ 过敏维度 hitList 返回 RECOMMEND → 按 NEUTRAL 处理并告警
□ 候选超过 3 道时截断不改变排序；截断发生在过敏合并之后
□ 合并后 indicators > 500 → 整任务失败，不静默截断
□ reportOverview 的 abnormalCount > totalCount → 置 null 改用卡片计算
□ 两个 dish-tag Job 用同一把 Redis 锁，并发触发时第二个跳过

内容常量（第三轮复审新增）：

□ Java 枚举与两份 Schema、两份 Prompt 的枚举表逐一相等，对不上即构建失败
□ 生产生成物只含 reviewStatus=REVIEWED 的行；全 DRAFT 时模块三四输出为空
□ POSSIBLE + SUBSTRING 组合构建失败
□ 每个 CANONICAL_EXACT 常量规范化后等于自身
□ 正式规则中不出现别名左值（土豆 / 老豆腐 / 西蓝花）
□ xo酱 能命中菜名中的 XO酱（ASCII 大小写折叠生效）
□ 同一 allergenKey 的 foodBorne 在组表中唯一
□ 菜名例外不能覆盖明确配料命中：「鱼香肉丝 + 配料含鱼露」仍 REJECT
□ 鱿鱼 / 章鱼 / 墨鱼 不触发 FISH 硬拒绝
□ 日本豆腐 / 杏仁豆腐 / 鱼豆腐 不因名称被 SOY 硬拒绝
□ 30 项筛查表里唯一一条【弱阳性】被漏抽 → 触发 ALLERGEN_SUSPECT_MISS
□ 章节标题与阳性数据行分属不同 segment 时，覆盖检查仍生效
□ LOW_PURINE 的豆腐、豆干不单独产生 RECOMMEND
□ LOW_FAT / LOW_CALORIE / LIGHT_DIET / LOW_SODIUM 在无油盐能量数据时不产生 RECOMMEND
□ 高危 pattern 的 scope 生效：肾病在前文、补钾在后文时 POTASSIUM 被阻止
□ CKD / ESRD 大小写变体都能命中
□ 只改 displayContentVersion 不触发重打标；改 tagRuleVersion 触发全量重打标
□ 全部行 DRAFT 时，Schema/Prompt 的枚举仍完整（枚举身份不受审核状态影响）
□ 全部行 DRAFT 时，Layer 1 词表为空、营养交集为空（生效规则受审核状态影响）
□ 常量类存在 DRAFT 残留时拒绝发布
□ REJECTED 的词出现在负向回归测试夹具中
□ DISPLAY_ONLY 的食材不注入 LLM-B 提示词、不参与营养交集
□ LLM-B 返回 RECOMMEND（任何维度）→ 按 NEUTRAL 处理并告警
□ Java 枚举与两份 Schema、两份 Prompt 的枚举表逐一相等
□ constants 包有 diff 但 TagRuleVersion.VALUE 未变 → 构建失败

三层职责调整后新增：

□ 一条指标的五个字段分散在 4 个 segment 时，合并后包含性校验通过
□ 双栏报告：模型未跨栏引用时正常出结果；构造跨栏引用的负例应被包含性校验拦下
□ allergenSectionSegmentIds 为空但全文有 SECTION_TITLES → 触发 ALLERGEN_SUSPECT_MISS
□ 模型漏圈过敏章节中一行数据 → 该行的风险标记未被覆盖 → 触发降级
□ sectionSegmentId 为 null 的指标归入默认分组 fileIndex + "-0"
□ 去重键用 min(segmentIds) —— 数组顺序打乱不影响去重结果
□ 混合来源（NATIVE + OCR 块被一起引用）按 OCR 档放宽校验
□ DishView.weightG 为 null 的食材不参与主料推导，为 0 的参与但占比为 0
□ 同名食材多行按 §9.3 合并求和
□ 调味料（蚝油）参与过敏匹配，但不参与主料推导
□ 两个食堂的同名菜品有不同 dishId，打标互不串（依赖全局唯一）
□ 报告只有过敏原+饮食禁忌、无营养建议时，recommendList 为空且不报错
□ 未被任何维度拒绝的菜不会因此进入推荐列表（NEUTRAL 不等于 RECOMMENDED）
```

### 11.2 需要真实样本才能验收的

`§11` 列的 16 条假设全部需要黄金样本集，**编码可以先行，验收不能**。
最低限度：20 份真实报告（覆盖原生 PDF、扫描 PDF、混合 PDF、拍照件、DOCX、内嵌图 Word、OFD）
+ 50 道真实菜品的人工主料标注。

---

## 12. 开发顺序

| 阶段 | 内容 | 依赖 |
|---|---|---|
| 1 | 工程骨架、DDL、Entity/Mapper/Service、五个占位符接口、错误码 | 无 |
| 2 | 五个 REST 接口 + 任务状态机 + 队列 + **五个定时任务** | 阶段 1 |
| 3 | 解析层：格式判定、防御、Segment 切分、逐页文本层判定 | 阶段 1 |
| 4 | LLM-A 层：Schema 校验、Java 校验链、排序去重 | 阶段 3 |
| 5 | 模块一、二组装 | 阶段 4 |
| 6 | 常量骨架 + 模块三组装 | 阶段 4 + 常量内容 |
| 7 | 离线打标 + 模块四组装 | 阶段 6 + `DishQueryService` 实现 |

**阶段 1~5 不依赖任何医疗内容常量，可立即开工。**
阶段 6、7 在常量填完并通过医务审核前只能写到骨架。

---

## 13. 仍需产品确认

| # | 事项 | 影响 |
|---|---|---|
| 1 | 「重新解析」依赖前端保存 `fileIds`，页面刷新后失效（§5.2） | 是否加接口 |
| 2 | 混入一个非体检报告文件时静默接受（只有全部文件都不是才失败） | 需求确认 |
| 3 | **贝类与芝麻不在过敏词表内**，报告写了会落 `OTHER`。增补属安全变更 | 医务评审 + 改 `AllergenGroups` |
| 4 | **高危 pattern 的 `PATIENT_CONTEXT` 行为**：肾病写在报告前文、「建议补钾」在后文时，当前实现把该建议转 `OTHER`（保守方向）。若判断误伤过多，只能收窄 scope，不能放宽词表（§8.3） | 产品 + 医务 |
| 5 | 设计方案 §12 原有的 11 项（第四态、缺页、60MB、饮食来源、同一人限制、总览条口径、问题名称拼接、`OTHER` 降级、过敏隐藏正面标签、新菜当天不推荐、关闭页面的定义） | **全部需回写需求文档**，否则测试按原需求报缺陷 |

> **已关闭：** `dish_id` 全系统唯一（菜品数据方 2026-08-24 确认）；
> 内容常量已落地为 Java 常量类（`com.example.healthreport.constants`，19 个文件），契约测试待实现，属 §14-6。

## 14. 仍需外部提供

| # | 事项 | 阻塞什么 |
|---|---|---|
| 1 | 五个占位符的真实实现 | 阶段 2/3/7 联调。**菜品接口的 bean 已定稿**（§8.7.1），按 `DishView` 返回即可 |
| 2 | **四个常量类的每一条都裁决为 `REVIEWED` 或 `REJECTED`（无 `DRAFT` 残留）+ 发布清单通过 + 填审核台账** | 模块三、四上线。当前全部 `DRAFT`，规则不生效。**完成标准不是「全改成 REVIEWED」**——被拒也是明确结论 |
| 3 | 模型服务并发配额 `C` 与单任务 P95 耗时 `T` | `W`、`MAX_DEPTH`、QUEUED 超时三个值全定不下来（§4.4.1） |
| 4 | 部署堆大小与 Worker 并发 | `MAX_DECODED_PIXELS_PER_TASK` 定不下来（§6.2.1） |
| 5 | 六类版式的真实报告样本（各 ≥3 份） | 版面判断已移交模型，不再需要样本定阈值；但仍需样本**验收抽取召回率**（双栏、上下标、跨行单元格、旋转页、续页表头、OCR 粒度） |
| 6 | **契约测试（Java 枚举 ↔ Schema ↔ Prompt）** | 纯工程活，不卡医务，可立即做（§10.4） |
| 7 | Dify 工作流 ID、模型版本、日志关闭确认 | 联调与合规 |
| 8 | S3 Bucket、加密、生命周期策略 | 部署 |
| 9 | 黄金样本集与人工标注 | 全部验收 |
| 10 | OCR/Dify/APM 的数据留存核查清单（设计方案 §11.1） | 上线阻断项 |
