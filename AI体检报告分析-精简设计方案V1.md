# AI 体检报告分析与菜品推荐 — 精简设计方案 V1

> 技术栈：Java 8 / Spring Boot 2.7.x / MyBatis-Plus / PDFBox / Apache POI / ofdrw / TinyPinyin / **MySQL 8.0** / Redis / xxl-job / OCR
>
> **两个模型都直连模型 API，不经过 Dify**（LLM-A 2026-08-25 定，LLM-B 2026-08-27 定）。
> 同一个网关、同一套 OpenAI 兼容 `/chat/completions` 协议，只是模型标识不同。
>
> 对应需求：《体检报告分析需求.md》
> 本文档只描述当前设计，不含版本历史与废止内容。任何一句话都是现行有效的。

---

## 0. 设计原则

1. **原文即事实。** 展示层严格复刻报告原文。LLM-A 可以为结构化汇总进行版面理解、
   语义分类和准入判断，但**不得生成报告中不存在的事实、诊断、医嘱或展示文案**。
2. **三层职责边界，全文任何一节都不得越界。**

   | 层 | 负责 | 不负责 |
   |---|---|---|
   | OCR / 解析 | 还原文本与版面坐标，切 segment | 任何判断 |
   | **LLM-A** | 输出**经过原文引用约束的结构化总结**：版面理解、章节归属、语义分类、健康问题准入、枚举归一化 | 生成原文里没有的内容 |
   | **Java** | Schema 校验、来源引用校验、简单安全兜底、集合运算、数值计算、排序 | **新增或改写医疗语义** |

   **Java 的词表在生产链路里只有一种合法用法：往安全方向降级或拦截。**
   凡是把模型给出的语义结论替换成另一个语义结论的（改 `status`、改 `isFoodBorne`、
   改 `includeInHealthProblems`、改 `enumKey`），一律不允许。

   > **"只告警计数"曾经是第二种合法用法，2026-08-25 已取消**（§4.4-③）。
   > 它不影响输出，但代码长得跟"Java 在判断医疗语义"一模一样，留在生产里迟早被改回覆写；
   > 而且同样的信息评测集里有、还更准。**发现不一致的正确做法是跑评测集、改提示词、走发版，
   > 不是在每一次用户请求里扫一遍词表。**
3. **确定性规则不交给模型。** 但"确定性"指的是**不含医疗语义**的计算——重量占比、集合交集、
   排序、哈希、字符串包含。**"这条结论是正常还是异常"不是确定性规则**，
   不能因为写得出词表就把它收回 Java。
4. **不确定性只在写入时消费一次。** 菜品打标在离线批处理完成并落库，模型波动不影响每次读取。
5. **模型没返回 ≠ 报告里没有。** Schema 强制必填，缺字段走重试或失败，不得折叠成空值。
   但**"Schema 通过"也不等于"报告已完整识别"**——模型返回 `"allergens": []` 结构上完全合法，
   所以高风险内容另有关键词交叉扫描兜一层（§4.4）。
6. **安全红线有两条，处理方向相反，不要混用。**

   | | 红线内容 | 误判代价 | 因此偏向 |
   |---|---|---|---|
   | **一级** | **过敏** | 漏标可能造成过敏反应；误标只是少一个菜 | **高召回**：宁可多拦（§8.5 关键词并集、§4.4-② 交叉扫描） |
   | **二级** | **方向性饮食禁忌**：低蛋白/限蛋白、低钾、低磷、低碘、孕期哺乳期儿童 | 归一化把「限制」映射成「补充」，会直接推荐反向菜品 | **不猜**：判不准就不输出（§7.3 安全闸） |

   两条的共同点是代价不对称，区别是**过敏偏向多拦，禁忌偏向不猜**。
   原则里原先写「过敏是唯一安全红线」，与 §7.3 的存在直接矛盾——照那句话，
   §7.3 那道闸门根本没有合法性依据。现在两条都写明，实现时按各自方向处理。

---

## 1. 整体流程

```
① 用户逐份上传文件（每份只返回 fileId，不创建任务）
        │
        ▼
② 点击「生成体检报告」，提交有序 fileIds → 创建 taskId、绑定文件、提交本机线程池异步执行
        │
        ▼
③ 工作线程：文件解析 → LLM-A 抽取 → 同一性校验 → Java 契约与来源校验、安全降级
        │
        ▼
④ 组装四模块（纯 Java + 读离线打标，不再调用任何模型）
        │
        ▼
⑤ 前端轮询 taskId 取结果

离线：每日凌晨按企业游标分页读取当日在架菜品与食材，完成 13 个过敏原、9 个饮食注意、
      9 个营养补充维度的全部判定；饮食正向仅支持低嘌呤、高纤维，按企业发布 33 个
      Redis 标签集合（§8.3）
在线：只按当前用户 companyId 读取对应企业的 Redis 集合，做并集、差集与排序；
      不查询菜品表、不读取食材、不调用 LLM-B，也不回源 MySQL 标签表
```

**在线链路只调用 LLM-A，不调用 LLM-B。** LLM-A 本身按 ≤8 页/批分批，一份 22 页报告是 3 批（§4.1）。
LLM-B 只允许离线预热任务调用，在线链路对它的调用次数必须为 0，对标签的写入次数也必须为 0。

---

## 2. 上传与任务（需求 §3）

### 2.1 上传接口

`POST /api/health-report/file` —— 单文件上传，返回 `fileId`。

| 校验 | 规则 | 失败提示 |
|---|---|---|
| 格式 | 按 §3.1 的逐格式判定，**不信任扩展名** | 「暂不支持该文件格式，支持PDF、JPG、PNG、OFD、DOC/DOCX格式」 |
| 单文件大小 | PDF/OFD/DOC/DOCX ≤ 20MB，JPG/PNG ≤ 10MB | 「文件大小超过限制，请压缩后重新上传」 |
| 可读性 | 按 §3.1 的逐格式可读性判定 | 「文件无法读取，请检查文件是否完整」 |

存 S3 私有 Bucket，落一条 `ct_health_report_file`（`status = UPLOADED`，`task_id = NULL`，
`user_id = 当前用户`、`company_id = 当前企业`，`expire_at = now + 30min`）。

**HEIC 由前端转码为 JPEG 后上传**，后端不引 Native 库。

### 2.2 创建任务接口

```http
POST /api/health-report/analyze
{"fileIds": ["...", "..."]}
```

**逐文件校验（缺一不可，§3.9）：**

```
file.userId   == 当前已认证 userId     ← 不校验这条 = 拿到别人的 fileId 就能读到别人的报告
file.companyId == 当前已认证 companyId ← 用户与文件必须同时属于当前企业
file.status   == UPLOADED
file.expireAt >  now
file.taskId   IS NULL
```

`taskId` 层面的鉴权**弥补不了**创建阶段的缺失：攻击者若能把他人的 `fileId` 绑到自己的
`taskId` 上，后续所有归属校验都会正常通过。

**其余校验：** `fileIds` 数量 1~5、累计大小 ≤ 60MB（§12-4）、累计容量预检页数（§3.3）。
PDF / OFD / 图片的预检页数是精确值；Word 因内嵌图片尚未 OCR，创建时只能使用上传阶段
保存的原生 segment 页数下界。Word 的精确容量由工作线程在调用 LLM-A 前裁决（§3.3.1）。
**没有队列深度校验**——背压由线程池的有界队列 + `AbortPolicy` 承担，
在 §2.3.3 的第 ⑤ 步一次性表达，不在这里预先查一遍（那会有"查时没满、提交时满了"的竞态）。

**绑定规则：一个文件同时只能属于一个「活着的」任务。**

```
可绑定 = task_id IS NULL                              ← 新上传，从未用过
       ∪ 原 task 处于 FAILED 且 reanalyzable = 1     ← 上次是服务端出错，允许换个新任务再来
```

第二条就是需求 §3-9「使用已上传的文件重新解析」的落地方式：**用同一批 `fileIds`
再调一次本接口即可**，不需要单独的重试接口（§2.5）。

**事务内两步，防并发抢占：**

```sql
-- ① 锁定并校验（MySQL 8：FOR UPDATE OF f 只锁文件行，不锁任务行）
SELECT f.*, t.status, t.reanalyzable, t.deleted_at
  FROM ct_health_report_file f
  LEFT JOIN ct_health_report_task t ON t.task_id = f.task_id
 WHERE f.file_id IN (?) AND f.user_id = ? AND f.company_id = ?
 FOR UPDATE OF f;
-- 逐行校验 status='UPLOADED'、expire_at>now、以及上面的可绑定条件
-- 不加 OF f 会连带锁住 ct_health_report_task 行，与 Worker 的状态 CAS 争锁

-- ② 条件更新，把 oldTaskId 带进 WHERE 防止两个请求同时抢
UPDATE ct_health_report_file
   SET task_id = :newTaskId, file_index = ?, expire_at = ?
 WHERE file_id = ? AND user_id = ? AND company_id = ? AND status = 'UPLOADED'
   AND (task_id IS NULL OR task_id = :oldTaskId);
-- 不写 update_time，由 ON UPDATE CURRENT_TIMESTAMP 维护（§9.1.1）
-- 受影响行数必须 == 1，否则整个事务回滚，返回 FILE_ALREADY_BOUND
```

**`FILE_ALREADY_BOUND` 必须把已绑定的 `taskId` 一并返回。**

```json
{"code": "FILE_ALREADY_BOUND", "taskId": "已绑定的那个"}
```

这是为了兜住"任务创建成功但响应丢包"的情况：前端没拿到 taskId，用户再点一次，
文件已经绑给一个 `QUEUED` 的任务了——如果只回一个错误码，用户就只能重新上传。
带上 taskId，前端直接转去轮询它即可。归属已在 §2.2 开头校验过，不额外泄露信息。

`fileIds` 的提交顺序即 `fileIndex`（0 起）。
被解绑的旧 `FAILED` 任务行不再持有任何文件，随 `expire_at` 自然回收（§2.7）。

### 2.3 任务状态机与持久化

**任务状态的真源是 MySQL，不是 Redis。** 创建任务、文件绑定、状态 CAS、心跳
全部走 MySQL 事务与条件更新；**Redis 不参与任务调度**，只承载四模块结果（§9.1）
和菜品打标读缓存（§8.3.1）。

#### 2.3.1 状态与删除标志是两个正交的东西

```
QUEUED ──→ PARSING ──→ EXTRACTING ──→ ASSEMBLING ──→ SUCCEEDED
             │             │              │
             └─────────────┴──────────────┴──────────────→ FAILED

deleted_at  正交标志，任何状态下都可置，一旦置上不可撤销
```

**状态机是纯单向的，没有任何回边。** `SUCCEEDED` 与 `FAILED` 都是终态，一旦到达不再迁移。

用户的「重新解析」**不是让旧任务回到队列**，而是拿同一批文件创建一个**全新的 taskId**（§2.2、§2.5）。
这样：

- 不需要 `attempt_no` 来区分第几次执行
- 不存在"旧任务的残留投递"——本机线程池里没有排队的旧消息（§2.3.3）
- 状态机没有回边，可穷举、可画图、可单测

**删除不是状态迁移**，用 `deleted_at` 标志表达。

#### 2.3.2 DDL

**建表硬约束：每个字段必须声明字段级中文 `COMMENT`，每张表必须有中文 `COMMENT`。**
行尾 `--` 备注、Java 注释或另外的数据字典不能代替 MySQL 字段 `COMMENT`。

```sql
CREATE TABLE ct_health_report_task (
  task_id        VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci  NOT NULL COMMENT '任务ID，使用UUID',
  user_id        VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci  NOT NULL COMMENT '归属用户ID，用于鉴权',
  company_id  VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci  NOT NULL COMMENT '归属企业ID，创建任务时从可信登录上下文固化，模块四据此选择企业菜品标签集合',
  status         VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci  NOT NULL COMMENT '任务状态：QUEUED/PARSING/EXTRACTING/ASSEMBLING/SUCCEEDED/FAILED',
  stage          VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci  NULL COMMENT '前端进度阶段，见§2.4',
  progress       TINYINT      NOT NULL DEFAULT 0 COMMENT '任务进度百分比，取值0至100',
  fail_code      VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci  NULL COMMENT '任务失败错误码，成功或未失败时为NULL',
  reanalyzable   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否允许重新解析，同时是§2.2的文件解绑条件',
  partial        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否为部分结果，命中时模块三四降级',
  partial_reason VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci  NULL COMMENT '降级原因：PAGE_TRUNCATED/BATCH_UNREADABLE/ALLERGEN_SUSPECT_MISS/SCHEMA_ITEM_DROPPED/DIET_REQUIREMENT_DROPPED',
  heartbeat_at   DATETIME     NULL COMMENT 'Worker最近心跳时间',
  deadline_at    DATETIME     NULL COMMENT '任务执行硬截止时间',
  expire_at      DATETIME     NOT NULL COMMENT '任务及关联数据过期时间',
  deleted_at     DATETIME     NULL COMMENT '用户删除任务的时间，未删除时为NULL',
  version        INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，由数据库维护',
  create_by      VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci  NULL COMMENT '创建人标识',
  update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间，由数据库维护',
  update_by      VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci  NULL COMMENT '更新人标识',
  PRIMARY KEY (task_id),
  KEY idx_company_user (company_id, user_id),
  KEY idx_sweep (status, heartbeat_at),
  KEY idx_deadline (status, deadline_at),
  KEY idx_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='体检报告分析任务';

CREATE TABLE ct_health_report_file (
  file_id        VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci  NOT NULL COMMENT '文件ID，使用UUID',
  user_id        VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci  NOT NULL COMMENT '归属用户ID，用于鉴权',
  company_id  VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci  NOT NULL COMMENT '归属企业ID，上传时从可信登录上下文固化，绑定任务时必须与任务企业精确一致',
  task_id        VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci  NULL COMMENT '关联任务ID，未绑定任务时为NULL',
  file_index     INT          NULL COMMENT '文件在任务内的顺序，从0开始',
  status         VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci  NOT NULL COMMENT '文件状态：UPLOADED',
  origin_name    VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '敏感元数据：用户上传的原始文件名，可能含姓名与体检属性如张三-2026体检报告.pdf。仅用于前端回显，禁止进日志与外部系统，随file行一起删除。是否改为安全生成的展示名待产品确认',
  content_type   VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci  NOT NULL COMMENT '按§3.1判定的真实文件格式，非扩展名',
  size_bytes     BIGINT       NOT NULL COMMENT '文件大小，单位字节',
  precheck_pages INT          NOT NULL COMMENT '创建任务容量预检页数：PDF与OFD为真实页数，图片为1，Word为原生segment数除以40向上取整的下界且不含OCR块，图片型Word可为0',
  content_hash   CHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci     NOT NULL COMMENT '文件内容SHA-256哈希',
  cloud_file_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '云存储文件键，用于定位原始文件',
  expire_at      DATETIME     NOT NULL COMMENT '原始文件过期删除时间',
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，由数据库维护',
  create_by      VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci  NULL COMMENT '创建人标识',
  update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间，由数据库维护',
  update_by      VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci  NULL COMMENT '更新人标识',
  PRIMARY KEY (file_id),
  KEY idx_task (task_id),
  KEY idx_company_user (company_id, user_id),
  KEY idx_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='上传的体检报告文件';
```

Bucket/容器名由 `S3FileStorage` 的部署配置提供，不逐文件落库；文件表只保存
`cloud_file_key`，Java 实体字段固定为 `cloudFileKey`，不得保留 `s3_bucket` / `s3_key`。

**没有消息队列，没有 outbox 表，也没有 Dispatcher 线程。**
执行在创建事务**提交之后**提交给本进程线程池（§2.3.3）。

**Worker 的 lease 不单独建表**，用 `heartbeat_at` + `deadline_at` 表达。

> **表命名与审计列遵循项目约定（§9.1.1）**：表名一律 `ct_` 前缀；
> 四个审计列 `create_time` / `create_by` / `update_time` / `update_by` 每张表都有；
> **两个时间列由数据库默认值和 `ON UPDATE` 维护，代码永远不赋值。**

#### 2.3.3 创建与执行：先提交事务，后提交线程池

**没有消息队列。** 请求进来直接在本进程的线程池里异步跑。

```
① 开启事务
② INSERT ct_health_report_task (status = 'QUEUED', ...)
③ 绑定文件（§2.2 的两步）
④ 提交事务                                ← 提交在前
⑤ analysisExecutor.submit(taskId)         ← 提交在后，【事务之外】
     被拒（队列满/池已关闭）→ 事务外把该任务 CAS 为 FAILED/SERVER_ERROR → 返回 SERVER_ERROR
⑥ 返回 taskId，前端开始轮询（§2.4、§9.3）
```

> **顺序不能反，理由和旧版 XADD 完全一样。** 先 submit 后提交事务的话，工作线程会
> 在事务提交前就去读任务行——读不到，领取 CAS 影响 0 行，任务被当成"已删除/已失效"丢弃；
> 事务随后提交，于是**任务行存在而没有任何人在跑它**。这不是并发 bug，
> 是 InnoDB 可重复读下的正常可见性，线程池空闲时几乎必然复现。
> 换成本机线程池只是把 Redis 换成了 `ExecutorService`，这个陷阱一模一样。

**三条失败路径，都不会留下卡死的任务：**

| 失败点 | 后果 |
|---|---|
| 事务提交失败 | task 行不存在，文件仍未绑定，也没有任务被提交。用户重点一次即可 |
| 提交成功、`submit` 被拒或抛异常 | 事务外把该任务 CAS 为 `FAILED/SERVER_ERROR`，前端立刻拿到错误 |
| 提交成功、进程在 `submit` 前崩溃 | 任务停在 `QUEUED` 且没人跑 → `QueuedTimeoutSweepJob` 5 分钟后判 `FAILED` |

第三行的窗口从"两次网络往返"缩到了"两条相邻语句"，但它没有消失——进程正好死在这两行之间
仍然可能，所以 `QueuedTimeoutSweepJob` 保留。

**必须是两个独立的线程池，不能共用一个。**

```
analysisExecutor   任务池，W 个线程，每个线程跑完整的一个任务（§4.1.4 的 W）
llmBatchExecutor   批次池，独立，供 §4.1.4 的批次并发使用
```

共用一个池会**直接死锁**，而且是必然复现的那种：

```
池大小 W，W 个任务各占一个线程
每个任务要提交 4 个批次到【同一个池】，然后阻塞等待它们的结果
→ 池里没有空闲线程了，4×W 个批次任务全部排在队列里
→ 占着线程的 W 个任务在等永远轮不到执行的批次
→ 线程饥饿死锁，进程活着、心跳正常、任务永不结束
```

心跳线程还在跑（§4.1.4 要求它独立），所以 §2.3.4 的心跳巡检**扫不出来**，
只能靠 §2.3.4 的 `deadline_at` 超时兜底——10 分钟后一批任务集体 `EXECUTION_TIMEOUT`，
而根因藏在线程池配置里，排障要绕很大一圈。

`llmBatchExecutor` 的大小按 `W × 4` 配（§4.1.4），它是 `W` 之外的第二个旋钮，
两者必须一起调；任务池满走 `AbortPolicy`（下面），批次池不应该出现排队。

**任务线程池必须有界，拒绝策略必须是"直接失败"。**

```java
new ThreadPoolExecutor(
    W, W,                                   // 固定 W 个线程，W 见 §4.1.4
    0L, TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue<>(QUEUE_CAPACITY),   // ★ 有界
    new ThreadPoolExecutor.AbortPolicy());      // ★ 满了直接抛，不排队等、不在调用线程跑
```

三个"不能"，每一个都对应一种线上事故：

```
不能用无界队列    突发流量下队列无限涨，用户排在 30 分钟后才被执行，
                  而文件 expire_at 早就到了（§2.2）——排到了也没文件可读
不能用 CallerRuns 拒绝时会在 Tomcat 请求线程里跑完整个分析（分钟级），
                  一个请求线程被占死，压力大时整个 Web 层被拖垮
不能静默丢弃      DiscardPolicy 会让任务永远停在 QUEUED，只能等 5 分钟巡检兜底，
                  而这本来是可以立刻告诉用户的
```

**这就是原来的「队列深度校验」（§2.2）在新方案里的位置**——不再是创建前查一次 Redis 队列长度，
而是线程池自己的有界队列 + `AbortPolicy`，一次拒绝直接变成一次用户可见的失败。
两者作用相同，后者少一次网络往返，也不会出现"查的时候没满、提交的时候满了"的竞态。

`QUEUE_CAPACITY` 按 §4.1.4 的 `W` 和单任务耗时倒推，**需实测校准（§11-14）**。

> **删掉的东西：** `XADD` / `XACK` / `XDEL`、`q:analysis` 流、Consumer Group、
> 创建前的队列深度查询、投递失败与孤儿消息处理，以及更早的 `ct_health_report_task_outbox`
> 表和 Dispatcher 退避重投。**Redis 仍然在用**——四模块结果（§9.1）和菜品打标读缓存（§8.3.1）
> 都没动，去掉的只是"用 Redis 当任务队列"这一件事。

**明确接受的代价：应用重启时正在执行的任务直接失败，不自动恢复。**

```
重启/崩溃 → 该实例的工作线程全部消失 → 这些任务的 heartbeat_at 停止更新
          → §2.3.4 的巡检在 15 分钟后置 FAILED / SERVER_ERROR，reanalyzable = 1
          → 用户按 §2.5 用同一批 fileIds 重新发起
```

用户要干等最多 15 分钟才看到失败，这是本次简单版接受的代价。与「零重试」的口径一致：
服务端出错就直接失败，由用户决定要不要重来（§2.5）。

> **单实例部署可以把这 15 分钟缩到 0**：启动时把全部非终态任务一次性判 `FAILED`。
> **多实例部署绝对不能这么做**——那会把别的实例正在跑的任务一起判死。
> 多实例只能靠心跳巡检收敛，因为任务在哪个实例上跑没有落库，谁都不知道哪些是自己的。
> 要精确到实例，得给任务表加 owner 列，那是恢复机制的开头，不在本次范围内。

#### 2.3.4 领取 CAS 与心跳

工作线程做的第一件事仍然是领取 CAS，**没有消息队列不等于不需要它**：

```sql
UPDATE ct_health_report_task
   SET status = 'PARSING', heartbeat_at = now(), deadline_at = now() + 10min, version = version + 1
 WHERE task_id = ? AND status = 'QUEUED' AND deleted_at IS NULL
```

受影响行数为 0 时**直接结束该任务的执行，不做任何事、不写终态**。两种情况被它覆盖：

- 任务已被删除（`deleted_at` 非空），包括提交线程池之后用户立刻删除（§2.6）
- 状态已不是 `QUEUED`（重复提交、或被巡检提前判了失败）

> **「陈旧消息」和「孤儿消息」两类问题随队列一起消失了。** 没有队列就没有重复投递，
> 也不存在"消息还在、任务早已 FAILED"。留下的 CAS 只承担一件事：
> **和删除操作抢同一行**——这件事和队列无关，去掉队列它照样存在（§2.6）。

**心跳：** 工作线程每 30s 更新 `heartbeat_at`，由**独立的调度线程**执行（§4.1.4）。
巡检任务扫 `status ∈ 执行中` 且 `heartbeat_at < now - 15min` 的，
强制置 `FAILED / SERVER_ERROR`（`reanalyzable = 1`）。

**`deadline_at` 必须真的被巡检执行，不能只写不判。**
原设计只扫「心跳超过 15 分钟没更新」，而心跳是独立线程（§4.1.4）——
**只要心跳线程还活着，一个卡死在模型调用上的任务可以跑一小时也不会被判超时。**
巡检要两条件并列，命中任一即终结：

```sql
-- ① 进程死了：心跳停更
UPDATE ct_health_report_task SET status='FAILED', fail_code='SERVER_ERROR', reanalyzable=1
 WHERE status IN ('PARSING','EXTRACTING','ASSEMBLING') AND heartbeat_at < now() - INTERVAL 15 MINUTE;

-- ② 进程活着但跑不完：超过硬截止
UPDATE ct_health_report_task SET status='FAILED', fail_code='EXECUTION_TIMEOUT', reanalyzable=1
 WHERE status IN ('PARSING','EXTRACTING','ASSEMBLING') AND now() > deadline_at;
```

两条的 `fail_code` 不同，因为它们是两种故障：①是"没人在跑了"，②是"在跑但跑不完"。
合成一条会让排障时分不清该查进程还是该查模型延迟。
`deadline_at` 在领取 CAS 时置为 `now() + 10min`（见上），**之后不再顺延**——
心跳只更新 `heartbeat_at`，绝不碰 `deadline_at`，否则②这条永远不会命中。

**心跳巡检在本方案里的分量变重了。** 用消息队列时，进程崩溃后消息还在，
另一个 Worker 会重新领取；现在任务只存在于那个进程的线程池里，**进程没了任务就没了**。
心跳巡检是唯一能把这些任务从"永远 `EXTRACTING`"里捞出来的机制（§2.3.3）。

**客户端超时不写终态。**

### 2.4 进度（需求 §3-7）

`GET /api/health-report/task/{taskId}` 返回 `{status, stage, progress, failCode, reanalyzable}`：

| stage | 对应状态 | progress | 前端文案 |
|---|---|---|---|
| UPLOADING | QUEUED | 0~30% | 正在上传文件... |
| PARSING | PARSING / EXTRACTING | 30~80% | 正在识别报告内容... |
| ASSEMBLING | ASSEMBLING | 80~100% | 正在生成分析结果... |

每阶段进入时写区间起点，前端在区间内做平滑动画。

### 2.5 失败处理与重新解析（需求 §3-9）

**服务端出错就直接失败返回，不自行重跑。** 全案不存在任何"执行失败后自动再跑一遍"的逻辑
——LLM-A 批次失败不重试、Schema 校验失败不重试、疑似漏抽不定向重试。
失败即写终态、即返回，把决定权交给用户。

| failCode | 谁的问题 | 页面提示 | `reanalyzable` |
|---|---|---|---|
| `NOT_HEALTH_REPORT` | 用户传错文件 | 「未识别到体检报告内容，请确认文件是否为体检报告后重新上传」 | false |
| `UNREADABLE` | 用户拍得糊 | 「报告内容识别不清，请上传更清晰的文件」 | false |
| `IDENTITY_MISMATCH` | 用户混了别人的报告 | 「检测到上传的文件属于不同人员，请核对后重新上传」 | false |
| `PAGE_LIMIT_EXCEEDED` | 文件容量超限；可在上传/创建时同步发现，也可在 Word OCR 后异步发现（§3.3） | 「报告页数过多，请分次上传」 | false |
| `FILE_EXPIRED` | 超时未操作 | 「上传的文件已过期，请重新上传」 | false |
| `SERVER_ERROR` | **我们自己的** | 「服务暂时不可用，请稍后重试」 | **true** |
| `EXECUTION_TIMEOUT` | **我们自己的** | 同上 | **true** |

> 需求 §3-9 的「体检报告不完整（缺页等）」V1 不实现，见 §12-2。

**用户文件的问题一律 `reanalyzable = false`。** 同一份糊照片重跑一百遍还是糊的，
只有换清晰文件、换对报告、换成同一个人的报告才有意义——这类失败必须回上传页重选。

#### 「重新解析」没有专用接口

`reanalyzable = true` 时前端展示「重新解析」按钮，**点击后用同一批 `fileIds`
再调一次 `POST /analyze`**（§2.2 的绑定规则允许从可重解析的失败任务上解绑）。

```
返回一个全新的 taskId，前端换用新 taskId 轮询
旧任务保持 FAILED，其文件已被解绑，随 expire_at 回收
```

**为什么不复用原 taskId：** 复用就要引入 `attempt_no` 来区分第几次执行，
它会渗进 Worker CAS 和清理逻辑，还会给状态机加一条 `FAILED → QUEUED` 的回边。
开新任务把这些全部消掉。

**次数不设上限。** 天然约束已经够了：文件 30 分钟过期、线程池有界队列的背压（§2.3.3）、
每次都要用户主动点。
加一个计数器只是多一处状态。

### 2.6 删除（需求 §3-10）

`DELETE /api/health-report/task/{taskId}` —— 用户关闭结果页时调用。

**删除是打标志，不是状态迁移：**

```
① UPDATE task SET deleted_at = now()
    WHERE task_id=? AND user_id=? AND company_id=? AND deleted_at IS NULL
   —— 任何状态下都允许，包括 SUCCEEDED 和执行中
② 删除以下全部：
     S3 原始文件
     MySQL ct_health_report_file 行（整行删除）
     Redis result:{taskId}（四模块结果）
③ 【不中断】已经在跑的工作线程 —— 不做 Future.cancel、不发中断，由 ④ 拦下它的写回
④ 工作线程每次 CAS、每次写结果都带 deleted_at IS NULL 条件；
   置 SUCCEEDED 与写 Redis 结果按 §2.6.1 的固定顺序执行
```

#### 2.6.1 成功写入的顺序：Redis 在前，MySQL CAS 定生死

**MySQL 与 Redis 之间没有事务，"同一逻辑步骤内"是做不到的**，原文那句话是空话。
能做到的是**定一个顺序，并让其中一方单独决定结果是否可见**：

```
① 写 Redis result:{taskId}，TTL 2h        ← 此时结果【尚不可见】，没有任何接口会读它
② MySQL CAS：ASSEMBLING → SUCCEEDED
        AND deleted_at IS NULL             ← 这一步是唯一的"提交点"
        AND deadline_at >= now()           ← ★ 硬截止，见下
③ CAS 影响 0 行 → 立刻 DEL Redis result，再按原因写终态：
     超过 deadline → FAILED / EXECUTION_TIMEOUT
     已被删除 / 已被巡检判失败 → 什么都不做（终态已经写过了）
④ CAS 成功 → 同一条 UPDATE 里把 expire_at 顺延到 now() + 2h（见下）
```

**`AND deadline_at >= now()` 不能省，否则 10 分钟根本不是硬截止。**
巡检是周期任务（每 5 分钟一轮），**超时之后、巡检跑到之前有一整个窗口**，
一个跑了 12 分钟的任务在这个窗口里成功 CAS，结果就正常下发了：

```
第 0 分钟   领取，deadline_at = 第 10 分钟
第 12 分钟  任务跑完，CAS 成功  ← 没有 deadline 条件的话，这里会成功
第 13 分钟  巡检才跑到，看到状态已是 SUCCEEDED，不动它
→ deadline_at 写了，但从来没有真正拦住过任何东西
```

加上这个条件之后，**超时判定不再依赖巡检的调度时机**——工作线程自己就撞在门上，
巡检只负责收尾那些"根本没走到 CAS"的任务（进程死了、卡在模型调用上）。

**可见性由 MySQL 单方面决定，Redis 只是存储。** 结果接口（§9.2）必须先查 MySQL：

```
task.status != SUCCEEDED  →  不读 Redis，按状态返回进行中 / 失败
task 不存在 / 归属不符 / deleted_at 非空 / 已过期  →  404 RESULT_EXPIRED（§9.2 四种同码）
task.status == SUCCEEDED  →  才去读 Redis；读不到也返回 RESULT_EXPIRED
```

这样两种中间态都是安全的：

| 崩在哪 | 状态 | 用户看到 |
|---|---|---|
| ① 之后、② 之前 | Redis 有结果，MySQL 非 `SUCCEEDED` | 结果**不可见**；任务被 §2.3.4 巡检判 `FAILED`，Redis 那份随 TTL 自然过期 |
| ② 之后、③ 之前 | CAS 已失败但 Redis 没删干净 | 结果**不可见**（MySQL 不是 `SUCCEEDED`），2 小时后自然消失 |

**反过来做（先 CAS 再写 Redis）就有一个真实的坏窗口**：CAS 成功后进程崩溃，
任务是 `SUCCEEDED` 而 Redis 没有结果，用户轮到"成功"却拿不到内容，还没法重试
——状态机没有回边（§2.3.1）。

#### 2.6.2 成功后必须把 `expire_at` 顺延到 2 小时

`ct_health_report_task` 行原本保留到 `expire_at`（创建后 30 分钟），
而结果的 Redis TTL 是 2 小时（§2.7）。**两者不一致的后果是结果的后 90 分钟根本读不到**：

```
第 30 分钟：任务行被清理任务物理删除
第 31 分钟：用户刷新页面 → 结果接口查 MySQL → 任务不存在 → 404 RESULT_EXPIRED
            而 Redis 里那份结果还好好地躺着，再躺 89 分钟然后过期
```

不是"少活一会儿"，是**归属校验没有依据了**——没有任务行就查不到 `user_id`，
接口无法判断这份结果该不该给这个人看，只能拒绝（§9.2）。

**所以 ②④ 的 CAS 必须同时顺延 `expire_at`：**

```sql
UPDATE ct_health_report_task
   SET status = 'SUCCEEDED', progress = 100,
       expire_at = now() + INTERVAL 2 HOUR,   -- ★ 与结果 TTL 对齐
       version = version + 1
 WHERE task_id = ?
   AND status = 'ASSEMBLING'
   AND deleted_at IS NULL
   AND deadline_at >= now();                  -- ★ 硬截止（§2.6.1）
```

**只有成功才顺延。** `FAILED` 任务的 30 分钟不变——它的 30 分钟服务于
「用户点重新解析时文件还在」（§2.7），跟结果可见期没有关系。

**这一步防的是这个竞态：**

```
用户删除 → 结果被删 → 工作线程随后执行完成 → 把结果又写回去
                                            → 健康数据在"已删除"之后重新出现
```

**为什么不直接打断那个线程。** 与 §4.1.4「任一批失败时不取消其余批次」同一条理由：
取消传播要引入 `Future.cancel` + 中断处理 + 半途响应的清理，换来的只是省几秒模型调用。
让它跑完、在写回那一步被 `deleted_at IS NULL` 拦下，是更简单也更可靠的做法
——**正确性靠写回条件保证，不靠"能不能及时停下来"。**

`deleted_at` 在 MySQL 且不随 TTL 消失，比 Redis 墓碑可靠——墓碑 TTL 一过就失去保护。
`ct_health_report_task` 行由 §2.7 的清理任务统一回收。

### 2.7 数据生命周期与清理矩阵

**原始文件在任务整体 `SUCCEEDED` 后才删，不是解析成功后就删。**

原设计写"解析成功后立即删除"，但解析之后还有 LLM-A、组装、结果写入等步骤，任一步失败
都会产生 `reanalyzable = 1` 的 `SERVER_ERROR`——而此时原文件已经没了，
用户点「重新解析」会在 §2.2 的绑定校验处直接被拒，需求 §3-9 形同虚设。

| 任务状态 | 原始文件（S3） | `ct_health_report_file` 行 | `ct_health_report_task` 行 | Redis 结果 |
|---|---|---|---|---|
| 执行中 | 保留 | 保留 | 保留 | — |
| `SUCCEEDED` | **立即删除** | **立即删除** | 保留至 `expire_at`（**成功时顺延为 2h**，§2.6.2） | TTL 2h |
| `FAILED` 且 `reanalyzable=1` | **保留至 `expire_at`** | 保留至 `expire_at` | 保留至 `expire_at` | 无 |
| `FAILED` 且 `reanalyzable=0` | 立即删除 | 立即删除 | 保留至 `expire_at` | 无 |
| `deleted_at` 非空 | 立即删除 | 立即删除 | 保留至 `expire_at` | 立即删除 |
| 孤儿上传（`task_id IS NULL`） | `expire_at` 到期删除 | 同左 | — | — |

清理任务每 5 分钟跑一次，**必须按上表逐类判定，不能笼统地"终态即删"**
——`FAILED` 也是终态，笼统删会在用户点「重新解析」之前把文件删掉。

文件被重新绑定到新任务后，其 `task_id` 已指向新任务，清理按新任务的状态走，
旧的 `FAILED` 任务行不再持有文件。

`ct_health_report_task` 行保留至 `expire_at`（创建时 30 分钟，**成功后顺延为 2 小时**
以对齐结果 TTL，§2.6.2）是为了让 `deleted_at` 在整个 Worker
生命周期内可查，到期后物理删除。该表不含任何健康数据。

| 其他数据 | 生命周期 |
|---|---|
| 渲染图 / OCR 中间产物 | 仅存在于 Worker 内存，用完即释放，**不落盘、不入 S3** |
| 姓名 / 性别 | **仅在 Worker 内存用于同一性比对**，不写 Redis、不进普通应用日志、不返回前端；排障期仅可随 OCR / LLM-A 正文进入默认关闭的独立敏感 DEBUG logger |
| 分析结果（Redis） | TTL 2 小时，过期返回 `RESULT_EXPIRED`，前端提示「本次分析结果已过期，请重新上传」 |

**普通日志红线：** `taskId` 可进普通日志用于排障，但**不得与姓名、报告原文、OCR 文本、
过敏或医嘱原文、模型完整请求响应同时记录**。上述体检隐私内容仅可进入独立
`HEALTH_REPORT_SENSITIVE` logger 的 DEBUG 事件；该 logger 默认 `OFF`、仅限排障期临时开启，
且敏感事件不得携带 `taskId / userId`。凭证和图片字节在任何 logger、任何级别都禁止记录。
异常堆栈、APM、崩溃转储同样受普通日志红线约束（§11.1）。

## 3. 文件解析

### 3.1 格式判定与路由

**逐格式判定，不信任扩展名，也不能只看 magic number**（§6.1）：

| 格式 | 判定 | 可读性校验 |
|---|---|---|
| PDF | `%PDF-` 头 | PDFBox 能打开、页数 ≥ 1 |
| JPG/PNG | magic number + **实际解码** | 解码成功、宽高 ≥ 100px、总像素 ≤ 8000 万 |
| DOCX | ZIP 容器 + 内含 `word/document.xml` | POI 能打开，且**正文非空或含 ≥1 张合规内嵌图片** |
| OFD | ZIP 容器 + 内含 `OFD.xml` | ofdrw 能打开、页数 ≥ 1 |
| DOC | OLE2 复合文档头 `D0CF11E0` + WordDocument 流 | POI 能打开，且**正文非空或含 ≥1 张合规内嵌图片** |

**ZIP 不是支持的上传格式**，用户传 `.zip` 在此处直接被拒。
但 DOCX 与 OFD **自身就是 ZIP 容器**（OOXML 与 GB/T 33190 的规定，不是用户的选择），
所以两者的 magic number 都是 `PK\x03\x04`，只判 magic number 会互相误认，
必须解开查内部结构。DOC 是例外，它是老的二进制 OLE2 复合文档。

Word 没有统一的"页数"概念，可读性判据不是页数。

**判据是「正文非空 **或** 至少有一张 ≥300×300px 的内嵌图片」，两者满足其一即可。**

> **只判「正文非空」会把图片型 Word 在上传阶段就拒掉。**
> 「扫描件贴进 Word」是本方案明确要支持的形态（§3.3.1「只抽正文会漏掉贴图形式的报告」），
> 这类文件的正文恰恰是空的、`precheck_pages` 恰恰是 0——
> 它们本该走工作线程的内嵌图片 OCR，却在 `FILE_UNREADABLE` 上被挡住，永远进不了 OCR。

#### 3.1.1 解压炸弹防御（DOCX / OFD）

攻击路径不是"用户传了个 zip 炸弹"，而是：

```
攻击者构造一个 .docx，内含 word/document.xml → 格式校验通过，在我们眼里是合法 Word 文档
  → 解析时内部解压膨胀到几百 GB → Worker OOM 或写爆磁盘
  → 一个用户就能打掉整个 Worker，影响所有人的分析任务
```

**格式校验挡不住它，防御必须发生在解析阶段。**

| 限制 | 挡什么 |
|---|---|
| 条目数 ≤ 1000 | 几十万个空文件，解压逻辑本身耗尽 CPU |
| 单条目解压后 ≤ 50MB | 单个巨型文件 |
| 总解压体积 ≤ 200MB | 分散成多个中等文件绕过上一条 |
| 压缩比 ≤ 100:1 | 嵌套压缩 |

任一超限直接判为损坏文件，返回「文件无法读取，请检查文件是否完整」。

**两个实现陷阱：**

**① 不能用 `ZipEntry.getSize()` 判断解压后大小。** 该值来自压缩包自己的文件头，
**是攻击者可控的**，可以写一个假的小数字。必须边流式解压边计数：

```java
// 错误：信任声明值
if (entry.getSize() > MAX_ENTRY_SIZE) { reject(); }

// 正确：读多少算多少，超限立刻中断
long total = 0;
while ((n = in.read(buf)) > 0) {
    total += n;
    if (total > MAX_ENTRY_SIZE) { throw new CorruptFileException(); }
    out.write(buf, 0, n);
}
```

**② 直接把文件丢给 POI 或 ofdrw，解压是它们做的，上面的限制根本不会生效。**
两条路线二选一：

```
DOCX  →  配置 POI 自带的防护（ZipSecureFile.setMinInflateRatio /
         setMaxEntrySize、IOUtils.setByteArrayMaxOverride），不要自己造轮子。
         具体默认值随 POI 版本变化，落地时按实际依赖版本核对
OFD   →  ofdrw 是否有等价机制需实际查证；没有则在交给它之前自行预扫描一遍
```

> **优先级取决于威胁模型。** 上传接口若在企业内网、仅食堂员工可达，
> 与公网开放完全是两回事。最低成本的做法是先把 POI 那几个开关配上（几行代码），
> OFD 路径按 §11-5 的可行性验证结论再定。

**解析路由：**

| 格式 | 文本层 | 图像 |
|---|---|---|
| PDF（有文本层） | PDFBox 抽取 | 逐页渲染 |
| PDF（无文本层） | OCR | 逐页渲染 |
| OFD | ofdrw 抽取，失败则 OCR | 转图 |
| JPG / PNG | OCR | 原图 |
| DOC / DOCX | POI 抽取 | **无版面图** |

**PDF 是否有文本层：** 抽取字符数 / 页数 ≥ 50 且非空白字符占比 ≥ 30%，任一不满足走 OCR。

**PDF→OCR 还有第二道触发条件**：文本层存在但绘制单元退化到字形级时同样改走 OCR，
判据与理由见 §3.2.1 的密度闸。它必须在解析之后才判得了，所以不在本节的路由表里。

### 3.2 Segment：回切原文的唯一凭据

需求 §6-3 要展示「报告原文完整描述，便于用户与纸质报告核对」。要做到这点必须解决两件事：
**规范化会改字符**，以及**模型的复述不可信**。

#### 3.2.1 解析器把每个文件切成不可变的 segment

| 来源 | 一个 segment 是 | 解析器**不做**的事 |
|---|---|---|
| PDF 原生文本层 | PDF 内容流原生的**单次文本绘制单元**（一次 `Tj` / `TJ` 显示操作） | 不按字体、基线、字符间距或坐标二次合并；不识别表格、不聚类行列、不判断单元格 |
| OFD | ofdrw 抽出的一个**原子文本对象** | 同上 |
| OCR | 一个识别块 | 不合并相邻块 |
| DOCX / DOC | 一个段落；表格按 POI 给出的**显式**单元格切 | 不合并跨行/跨列单元格，不重排行列 |

**PDF / OFD 不切"单元格"，因为解析器判不了。** PDFBox 只给字形和坐标，没有任何表格结构；
要切出单元格必须做坐标聚类和行列推断——**那是版面理解，属于 LLM-A**（§0-2）。
Java 在这一层做行列推断，双栏、跨行合并单元格、续页表头会各错一种，而且错得静默。

**"同字体、同基线、字符距离足够近"也不是原子块定义。** 这些条件仍然需要坐标容差，
本质上只是把版面聚类缩小到了字符级。PDF 解析器只能沿用文件内容流本身已经声明的绘制边界，
不能靠阈值猜哪些字应该合并。

**实现路径：覆写 `PDFStreamEngine` 的 `showTextString` / `showTextStrings`**，
一次显示操作产出一个 segment。**不要用 `PDFTextStripper`**——它内部按行聚类，
正是这里要避免的那种推断，用它等于把版面判断偷偷做回 Java（§11-19 需实机验证）。

##### 拿不到绘制单元边界时，整文件改走 OCR，不退化为字形

有些 PDF 生成器逐字发 `Tj`，此时绘制单元就是单个字形——**不是解析器的选择，是文件本身如此**。
早先这里写「退化为单字形」，但那条路走不通，它会同时撞破三个硬约束：

```
① segmentIds 上限     一行「甘油三酯 2.8 mmol/L 0.56~1.70 ↑偏高」= 25 个字形
                      > 上限 32 → Schema 拒绝 → §4.4-① 整任务 FAILED，且不重试
                      不是降级，是这类 PDF 每次必失败
② LLM-A 输入体积      每段前缀 segmentId（12~13 字符，§4.1）
                      逐字形 ≈ 每个汉字 1 token 正文 + ~7 token 前缀
                      8 页/批 × ~2000 字/页 → 12~13 万 token/批，另加 8 张页面图
③ seq 容量            segmentId 的 `s` 段是 5 位（上限 99999）
                      30 页密集表格逐字形能到 6~9 万，卡在边界上
```

**因此加一道密度闸，判据落在解析完成之后、送模型之前：**

```
某文件 PDF 原生文本层的 segment 数 / 等效页数 > MAX_SEGMENTS_PER_PAGE（暂定 400）
    → 判定为「逐字形绘制」形态
    → 【该文件整体改走 OCR 路径】，textSource 记为 OCR，包含性校验走放宽档（§3.2.3）
```

**兜底指向 OCR 而不是别的东西，是因为 OCR 识别块天然就是词/行粒度**，
而且 §3.1 已经有 PDF→OCR 的路由，这里只是多一个触发条件，**不引入任何新的聚类逻辑**。
代价是这类文件从"有原生文本层"降级成"按图识别"，精度略降——但它本来也拿不到有意义的结构。

> **（2026-09-02）「天然是词/行粒度」这个前提要靠 Java 归一化保住。** 实测同一张图、
> 同一条指令、三次调用返回三种格式：纯文本流、`<fcel>/<lcel>/<nl>` 表格标记、Markdown 表格。
> **第二种的行分隔符是 `<nl>` 而不是 `\n`**——只按 `\n` 切会把整页切成个位数的块，
> 而 `blockRefs` 的全部意义就是定位到「哪一块」：粒度一垮，证据包含性校验、行归属判断、
> 400 块/页 的密度闸同时失效。
>
> 因此 `OcrContentSplitter` 先归一化再切，**块的粒度是「一行」，行内用制表符保留单元格边界**：
> `<nl>` 当换行，`<fcel>/<lcel>/<ucel>/<xcel>/<ecel>` 与 Markdown 的 `|` 都当单元格边界，
> Markdown 分隔行整行丢弃；未知的第四种格式最坏退回按行切。
>
> **空单元格必须保留成空位**（相邻两个制表符）。曾短暂把每个单元格拆成独立块，
> 那样行边界与空单元格一起丢了：`血糖 | 4.98 | mmol/L | (空) | 3.9~6.1` 拍平成四个块之后，
> 模型既不知道下一行从哪开始，也分不清 `3.9~6.1` 落在「提示」还是「参考值」列。
> **Word 内嵌图尤其致命**——Word 不向 LLM-A 发页面图，OCR 块是那一页仅有的信息。
> 提示词（`PaddleOcrVlClient.TRANSCRIBE_INSTRUCTION`）也改成明确要求逐行输出，
> 但那是「尽量」的那一半——**0.9B 模型的指令遵循有限，不能把正确性押在它听话上**。
>
> 顺带：表格标记与 Markdown 其实**信息更丰富**，它们标出了单元格边界，而那正是
> OCR 页面缺 bbox 时 LLM-A 只能靠页面图去猜的东西。归一化成「一行一块、行内分隔符对齐列」
> 等于把这部分版面信息还给了模型。
>
> 观测用 `OcrOutputFormatProbeIT`：合成图上有页眉、患者信息、日期、章节标题、表格与页尾
> 六个唯一标记，**逐个断言必须被识别出来**——只断言「有输出」的话，模型漏掉整个页头也照样通过，
> 而实测出现过正是这种漏法。

阈值 400 的量级依据：一页密集体检报告约 2000 字符，按字段/单元格发绘制操作时是
200~400 个 segment，逐字形则是 2000+，两者差一个数量级，闸门放在中间。**需用真实样本校准（§11-7b）。**

**DOC/DOCX 是例外，因为 OOXML 里的 `<w:tc>` 是文档自己声明的结构，不是推断出来的**
——读一个已经写明的标签不构成版面判断。但仅限显式单元格：合并跨行跨列、还原逻辑表格
同样不做。

> **哪些块属于同一个单元格、同一行、同一个指标，一律由 LLM-A 判断**（§4.1 给它页面图像
> 和每个 segment 的 `bbox`），它把判断结果表达为 `blockRefs` 数组（§4.1.5）——
> 一个指标的五个字段落在四个原子块里就引用四个块号。

```
segmentId = f{fileIndex}-p{page}-s{seq}     例：f0-p2-s17
```

`page` 对 Word 是逻辑分块序号（§3.3.1）。`seq` 在文件内单调递增，一经分配不再变化。

> **这是进程内的稳定主键，不是送给模型的编址方式。**
> `segmentId` 的全局唯一性服务于跨批去重（§4.1.3）、跨文件不混淆和回切定位；
> **它不落库**——§9.1 的持久化清单里没有 segment 表，segment 只活在任务执行期的内存里
> （§2.7）。而**一次批内调用连全局唯一都不需要**——
> 批次不跨文件，页是有序的，模型只需要区分"本批这几千块里的哪一块"。
> 把主键直接当线上格式用，代价是每块多背 8 个 token 的全局唯一性，
> 一批就是三万 token 的纯记账开销（§4.1.5）。**线上用批内块号 `blockRef`，
> Java 收到响应后查表展开成 `segmentId`。**

每个 segment 保存：

| 字段 | 说明 |
|---|---|
| `rawText` / `normalizedText` | 两份文本，见 §3.2.2 |
| `textSource` | **`NATIVE`**（PDFBox / ofdrw / POI 抽出）或 **`OCR`**。决定 §3.2.3 的校验档位 |
| `bbox` | **全来源保留**（PDF/OFD 取字形包围盒，OCR 取识别框，Word 无版面坐标时为 `null`），随批次输入给 LLM-A，用于把文本块对齐到页面图像。**"保留"= 任务执行期间留在内存里，任务结束即释放；不落库、不落盘、不进 Redis**（§2.7、§10「明确不做」） |

#### 3.2.2 原文与规范化文本

部分 PDF 生成器会把部首区字符混入正文（`⺩` U+2EA9 CJK RADICAL JADE 占了「王」的位置），
所以需要规范化。

> **NFKC 只解决一半问题。** 康熙部首区（U+2F00–U+2FD5）有兼容分解，NFKC 会自动还原；
> **CJK 部首补充区（U+2E80–U+2EFF）没有兼容分解，NFKC 对它们无效**，必须靠手工映射表。
> 这也是这张表只覆盖 U+2E80–U+2EFF 的原因。
但**规范化后的文本已经不是报告原文**——全角数字、波浪线、特殊单位字符都可能被改写。

| 字段 | 内容 | 用途 |
|---|---|---|
| `rawText` | 解析器抽出的**原始字符**，一个字都不动 | **展示、原文核对** |
| `normalizedText` | NFKC → 部首映射表（U+2E80-2EFF，约 30 条）→ 全角转半角 | 送模型、关键词匹配、去重、字符串比对 |

**模型只看到 `normalizedText`，按批内块号编址**（§4.1.5）。模型返回定位时只返回块号，
Java 展开成 `segmentId` 后再进入下游——**模型从头到尾看不到也用不着 `segmentId`。**

#### 3.2.3 两种回切，粒度不同

```
需要展示「报告原文完整描述」的字段（健康问题 rawText、饮食建议来源原文、textualFindings）
  → 直接取该 segment 的整段 rawText，不做字段级切分
  → segmentId 不存在 → 该条整条丢弃

模型复述的短字段（指标五字段、problemName、title、rawName、rawResult、sectionName）
  → 用模型返回值展示，但必须通过包含性校验（档位见下）
  → 校验不过的处理逐字段不同，完整清单见 §4.2 末的表
```

**包含性校验必须按 `textSource` 分档，不能一刀切严格匹配：**

| `textSource` | 校验方式 | 理由 |
|---|---|---|
| `NATIVE` | **严格子串**：规范化(字段值) 必须是 `normalizedText` 的子串 | 原生文本层就是真值，对不上说明模型编了 |
| `OCR` | **放宽**：去除全部空白后子串匹配；仍不中则归一化后编辑距离 ≤ 1 | OCR 本身有误差，严格匹配会杀掉正确结果 |

**为什么 OCR 必须放宽：** 扫描件和拍照件的文本是**从图片 OCR 出来的**，
是同一份信息的降级副本。OCR 把 `2.8` 认成 `2.6` 时：

```
segment.normalizedText  含「甘油三酯 2.6」    ← OCR 错了
模型看图，正确读出       value = "2.8"
严格子串校验             "2.8" 不在该 segment 内 → 这条正确的指标被丢弃
```

**OCR 的错误会把模型正确的抽取结果杀掉**，而拍照上传是主流形态。
放宽的代价是防幻觉强度略降——但模型编造的值通常与 OCR 文本差得远，
编辑距离 1 的窗口拦不住的极少。

**为什么不给指标做字符区间切分：** 规范化会改变字符数（NFKC 把 `㎎` 拆成 `mg`），
字段级 offset 需要维护 raw↔normalized 的逐字符映射表，实现和测试成本远高于收益。
包含性校验已经能保证"这五个值确实出自这个 segment"，而这正是"不是幻觉"的判据。

**一个 segment 含多个指标、一个指标横跨多个 segment，两种都正常**——
原子块粒度下后者是常态（一行表格 = 名称、数值、单位、参考范围、结论五个独立块）。
多条指标共享同一 `segmentId` 不冲突，一条指标引用多个 `segmentId` 也不冲突：
包含性校验把引用块的 `normalizedText` **合并后**再查子串，逐条独立执行。

菜品数据（可能来自 Excel/PDF 导入）同样走规范化，但只用于匹配，展示菜名用原始值。

未收录的字符**保留原样，不猜测替换**——替错一个字会让 normalizedText 变成看似正常实则错字的文本，比留着不认识的字符危险得多。

### 3.3 容量限制与降级

#### 3.3.1 Word 不按页计算（§15 评审）

POI 拿不到 Word 渲染后的页数——分页由渲染引擎决定，与文档结构无关。
因此 DOC/DOCX **不使用物理页数**，用独立规则：

```
逻辑分块：每 40 个 segment 记为 1 个"等效页"，用于统一容量核算与 LLM-A 分批
表格：每个单元格一个 segment，不按行合并
内嵌图片：Word 里常见"扫描件贴进 Word"的形态
          ≥ 300×300px 的内嵌图片提取出来走 OCR，产出的识别块也是 segment
          < 300×300px 视为装饰图，忽略
上限：segment 数 ≤ 1200，内嵌图片 ≤ 30，超出按 PAGE_LIMIT_EXCEEDED 拒绝
```

**只抽正文会漏掉贴图形式的报告**，所以内嵌图片必须提取并 OCR，这是 Word 路径的必做项。

**判定分两段进行：**

```
上传阶段（不做 OCR）
  nativeSegmentCount = 正文段落 + 表格单元格等原生 segment 数
  embeddedImageCount = ≥300×300px 的内嵌图片数
  nativeSegmentCount > 1200 或 embeddedImageCount > 30
      → 立即 PAGE_LIMIT_EXCEEDED，不保存文件记录
  否则保存 precheckPages = ceil(nativeSegmentCount / 40)，允许图片型 Word 在 OCR 前为 0
      → 它是容量下界，不是 Word 的最终等效页数

工作线程 PARSING 阶段
  对内嵌图片做 OCR，OCR 块按图片在 Word 源码中的位置并入 segment 序列
  exactSegmentCount = 原生 segment + OCR segment
  exactSegmentCount > 1200
      → FAILED / PAGE_LIMIT_EXCEEDED / reanalyzable=false，不调用 LLM-A
  否则 exactWordPages = ceil(exactSegmentCount / 40)
```

Word 的 OCR 块数在上传时未知，因此本版接受一个明确例外：**由 OCR 块导致的 Word 文件超限，
以及由此导致的任务累计超 60 页，在工作线程解析完成后异步失败**，不强求创建任务前拒绝。
这是容量判定时机的让步，不是重试或降级；同一文件重新执行仍会超限，所以
`reanalyzable=false`。OCR 服务本身失败仍按 `SERVER_ERROR / reanalyzable=true`，两者不得混用。

#### 3.3.2 页数上限与降级

单任务累计"精确等效页数"（PDF/OFD/图片按真实页数，Word 在 OCR 后按 §3.3.1 折算）：
容量精确裁决与前 30 页保留都在 `PARSING` 阶段完成，通过后才能调用 LLM-A。

```
≤ 30 页   → 全部处理，四个模块正常输出
31~60 页  → 按 fileIndex 与文件内顺序只处理前 30 等效页，
             置 partial = true / partial_reason = PAGE_TRUNCATED
> 60 页   → 无 Word 时创建任务前直接拒绝；含 Word 且仅 OCR 后才确认超限时，
             工作线程置 FAILED / PAGE_LIMIT_EXCEEDED，不调用 LLM-A
```

**单个 Word 文件不进入 31~60 档**：它超过 1200 segment（30 等效页）就直接失败。
但 Word 仍参与**任务累计**截断。例如 PDF 20 页 + Word 800 segment（20 等效页）总计 40 页，
应处理 PDF 20 页和 Word 前 400 个有序 segment，而不是把整份 Word 拒绝或全部丢弃。
多个 Word 文件同理。Word 的逻辑页按 OCR 块并入后的 segment 源码顺序每 40 个切一页；
这只是确定性计数与截断，不做版面或语义判断。

创建任务时先用各文件的 `precheck_pages` 做下界预检：下界累计已大于 60 时直接拒绝；
下界未超限只代表“尚未确定超限”，不代表 Word OCR 后的精确总量必然不超限。

**`partial = true` 的后果按原因区分，见 §4.1.1 的降级矩阵。**
页数截断这一类的后果是：**模块三与模块四整体不输出。**

总检结论、医生建议、过敏原筛查结果**几乎总在报告末尾**。截断后继续出饮食建议和菜品推荐，
等于基于"确定缺失了关键部分"的信息给出饮食指导——可能漏掉过敏原、漏掉低盐低嘌呤医嘱，
而页面上看不出任何异常。

结果接口返回 `partial` 与 `processedPages / totalPages`，前端据此隐藏后两个模块。
降级文案需产品确认（§12-5）。

**LLM-A 按 ≤8 等效页/批**分批调用，图像统一渲染到长边 2000px。

## 4. LLM-A 抽取

### 4.1 分批与批次裁决

每批：该批 segment 的 `normalizedText`（**按批内块号编址**，见 §4.1.5）+ 对应页面图像。
DOC/DOCX 只有文本，加上从内嵌图片 OCR 出来的 segment（§3.3.1）。

```
一个批次的全部页必须来自【同一个文件】，批次绝不跨文件
每任务 LLM-A 调用次数 = Σ ceil(各文件【截断后保留的】等效页数 / 8)，上限 8 批
单任务内批次并发度仍为 4（8 批时分两轮跑）
任一批调用失败（超时 / 429 / 5xx / 连接中断）→ 整任务立即 FAILED / SERVER_ERROR
```

**批次不跨文件是文件级裁决的前提。** 响应只有一个 `batchStatus` 和一组 `patient`，
跨文件批次下「某文件全部批次 `NO_REPORT_FEATURE`」这句话没有对应的数据可算。
上限从 4 提到 8：5 个文件各自向上取整，最坏形态 9/9/9/2/1 页 = 2+2+2+1+1 = 8 批。

> **章节归属由 LLM-A 给出，Java 不推导**（§0-2）。
> 「这一行属于哪个章节」是版面理解——跨页续表、双栏排版、页脚标题、夹在两章之间的小结，
> 只有看得见版面的模型分得清。Java 拿排序后的扁平序列反推「阅读顺序上最近的一个标题」
> 会在上述每一种版面上归错，且错得静默。
>
> **序号的稳定性用批内局部序号 + Java 拼接解决，不用把职责搬走：**
>
> ```
> sectionIndex     由 LLM-A 给出，【批内局部序号】，每批从 0 起
> sectionSegmentId 由 LLM-A 给出，指向印着该章节标题的 segment  ← 跨批对齐的唯一凭据
> sectionRelation  由 LLM-A 给出，四态，说明这个章节和上一批是什么关系  ← 见下
> groupKey         = Java 按 sectionRelation 拼，纯字符串运算（§4.6）
> ```
>
> 并行分批时模型确实生成不了**全局**序号，但它不需要生成——每批只报本批看到的章节，
> 并用 `sectionBlockRef` 指出标题原文在哪。
>
> **「同一章节跨批出现时 ID 相同、天然合并」这句话原先是错的，已删。**
> 批次不重叠（§4.1），**后一批根本看不到前一批的标题块**，不可能再返回同一个 ID。
> 跨批的同一章节走的是另一条路：前一批报 `CURRENT`，后一批报 `CONTINUATION`，
> 由 §4.6 机械继承前一批末章节的 `sectionSegmentId` 而合并。
> 只有批次输入确有重叠时（§4.1.3 的去重前提）才会出现两批报同一个 ID，那属于顺带成立，
> **不是合并机制的依据**。
>
> **批次边界必须由模型显式表态，不能靠 `null` 让 Java 猜。**
> 早先的写法是「`sectionSegmentId = null` 时 Java 继承前一批末章节」，这条不成立——
> `null` 至少压着五种互不相同的情况：
>
> ```
> 承接上一批还没结束的章节（跨页续表）      → 该继承
> 这部分内容本来就不属于任何章节（封面、
>   须知、附录、页末独立声明）              → 【不该继承】，继承会把封面文字挂到「血脂检查」下
> 上一章节已结束、本批开头还没出现新标题     → 【不该继承】
> 标题被裁在批次切分线上，模型没看全         → 不知道，得单独表达
> 模型就是没识别出来                        → 不知道，得单独表达
> ```
>
> Java 无差别继承，等于又在替模型决定「这条内容属于哪个章节」——**换了个入口的同一处越界**。
>
> ```
> sectionRelation = CURRENT       标题就在本批内   → sectionSegmentId 必填
>                 | CONTINUATION  承接上一批的章节 → sectionSegmentId = null，Java 机械继承
>                 | UNSECTIONED   本就不属于任何章节 → null，【不继承】，单独成组
>                 | UNKNOWN       没看清 / 不确定    → null，【不继承】
> ```
>
> **Java 只在 `CONTINUATION` 一种取值下继承，且只做继承、不做识别。**
> `UNSECTIONED` 与 `UNKNOWN` 不并入任何章节，按 §4.6 单独成组，展示名分别取模型给的
> `sectionName`（如「检查须知」，须过 §4.2 来源校验）和固定文案「未标注章节」。二者在展示上都不特殊处理，
> 区别只在 `UNKNOWN` 要打点——它是"模型没看清"，是质量信号，不能和"确实没有章节"混在一起
> （同 §4.1.1 把 `NO_REPORT_FEATURE` 和 `UNREADABLE` 分开的理由，§0-5）。
>
> 字段级契约以 `schema/extraction_output.schema.json` 为准（已同步：`sections` 数组 +
> 逐条目 `sectionIndex`，旧的 `sectionTitleSegmentIds` 已删除）。开发方案凡出现
> 「章节归属由 Java 按最近标题算出」的表述一律作废。模型给 `sectionSegmentId` 的准确率
> 需用评测集验证（§11-17）。

**不做批次级重试。** 服务端出错直接返回错误，由用户决定要不要重新解析（§2.5）。
代价是瞬时抖动也会变成一次用户可见的失败，实际失败率需上线后观测（§11-13）。

> **LLM-A 直连模型 API，不经过 Dify**（2026-08-25 变更）。
> 直连解掉的是一个死结：Dify 的文件上传**没有删除接口**，
> 每一页报告渲染图上传后会永久留在它的存储里，与 §2.7「原文件在任务成功后立即删除」
> 无法调和。直连时图像内联在请求体里，**不在任何中间系统落地**。
>
> **LLM-B 也改为直连**（2026-08-27 变更）。它的理由与数据敏感度无关
> ——LLM-B 的请求里只有菜名和食材，一条健康数据都没有，留在 Dify 不违反任何隐私约束。
> 改直连是因为**提示词正文已经决定放在 Java 侧**（原 §13.2.2 的 D1）：
> 提示词、`userMessage` 拼装、覆盖与互斥校验、条件约束全都在 Java，
> Dify 工作流退化成「输入 → LLM → 输出」三个节点的转发，
> 却仍要付出一份可漂移的 schema 副本、一处 `modelVersion` 双真源、
> 两条只为锁 DSL 而存在的契约测试。**收益归零而成本仍在，就该去掉。**
>
> 详见开发方案 §13.2。

**在线链路只使用 LLM-A**，但 LLM-A 本身分批，一份 22 页报告是 3 批
——"在线只有一次模型调用"的说法是错的，此处更正。

#### 4.1.1 批次状态与降级矩阵

每批返回 `batchStatus`，**三态，不是两态**（§6 评审）：

| batchStatus | 含义 |
|---|---|
| `OK` | 正常识别 |
| `NO_REPORT_FEATURE` | 读得清，但这几页看不出体检报告特征（封面、须知、广告页） |
| `UNREADABLE` | **读不清**，可能有内容但看不出来 |

这两者必须分开。`NO_REPORT_FEATURE` 是"确定没有"，`UNREADABLE` 是"不知道有没有"
——把后者当成前者，就是把"模型没看清"当成"报告里没有"，违反 §0-5。

**零 segment 是一种独立的失败，不能靠批次裁决兜住：**

```
解析全部完成后，某文件的 segment 数 == 0    → 该文件视为不可读
全部文件的 segment 数之和 == 0             → 整任务 FAILED / UNREADABLE
                                            【LLM-A 调用次数为 0】，一批都不发

部分文件零 segment、其余正常                → 【不是静默忽略】：
                                            partial = true
                                            partial_reason = BATCH_UNREADABLE
                                            → 模块三与模块四不输出（§4.1.1 降级矩阵）
                                            其余文件照常抽取，模块一二正常展示
```

> **「一份读不出、另一份正常」必须降级，理由与 `BATCH_UNREADABLE` 完全一样**——
> 读不出来的那一份**恰好可能就是含过敏原和医嘱的那一份**。
> 直接忽略它，等于在"确定缺了一整个文件"的前提下继续输出饮食建议和菜品推荐，
> 而页面上看不出任何异常。这是 §3.3.2 截断降级、§4.1.1 批次降级同一条论证的第三种形态。
>
> **复用 `BATCH_UNREADABLE` 而不新增枚举**：三者的后果完全相同（关模块三四），
> 多一个枚举值只多一处要维护的分支；名字略偏但语义是对的——"有内容没读到"。

> **为什么必须单列一条。** 下面的文件级/任务级裁决全部建立在「有批次」之上，
> 而零 segment 时**批次数是 0**——「全部批次 UNREADABLE」这句话在空集上恒真也恒假，
> 裁决逻辑根本不会被触发，任务会带着四个空模块走到 `SUCCEEDED`。
>
> 触发场景不是边角：**正文为空但有内嵌图片的 Word**（§3.1 允许它通过可读性校验）、
> 纯图片上传、扫描版 PDF/OFD——只要 OCR 调用成功却一个文字块都没识别出来，就是这一条。
> OCR 服务返回空结果与 OCR 服务报错是两回事，后者走 `SERVER_ERROR`，前者走这里。

**文件级与任务级裁决：**

```
某文件全部批次 NO_REPORT_FEATURE  → 该文件不是体检报告
全部文件都不是体检报告            → 整任务 FAILED / NOT_HEALTH_REPORT
全部批次都 UNREADABLE             → 整任务 FAILED / UNREADABLE
任一批次 UNREADABLE（但非全部）    → 该批内容丢弃，其余批照常
                                     partial = true，partial_reason = BATCH_UNREADABLE
```

**降级矩阵：**

| partial_reason | 模块一 指标 | 模块二 问题 | 模块三 饮食建议 | 模块四 菜品推荐 |
|---|---|---|---|---|
| `PAGE_TRUNCATED`（§3.3.2） | ✅ | ✅ | ❌ | ❌ |
| `BATCH_UNREADABLE` | ✅ | ✅ | ❌ | ❌ |
| `ALLERGEN_SUSPECT_MISS`（§4.4-②） | ✅ | ✅ | ✅ | ❌ |
| `SCHEMA_ITEM_DROPPED`（§4.4-①） | ✅ | ✅ | ✅ | ✅ |
| `DIET_REQUIREMENT_DROPPED`（§4.4-①） | ✅ | ✅ | ✅ | ❌ |

`SCHEMA_ITEM_DROPPED` **不抑制任何模块**——它只表示这份结果少了几条。
过敏原与章节不参与剔除（前者是一级红线，后者被 `sectionIndex` 引用）。
它在 `primaryReason()` 里排最后：任何其他原因都比它更需要被展示。

**但剔除的是 `dietRequirements` 时必须抑制模块四**，另记 `DIET_REQUIREMENT_DROPPED`：
每一条饮食注意都会在模块四生成一个 **REJECT 方向集合**（`DishRecommendInputFactory`），
剔掉「低嘌呤」就等于不再排除高嘌呤的菜，而推荐照常输出——那是把一次**格式错误**
变成一次**错误推荐**。营养补充只生成 RECOMMEND 方向，剔掉只是少推荐一条，不在此列。

前两类要关掉模块三，因为**读不清的那一批恰好可能就是含过敏原和医嘱的那一批**
——继续输出饮食建议和菜品推荐，与 §3.3.2 的截断降级原则自相矛盾。

结果接口下发 `partial`、`partialReason`、`suppressDietAdvice`、`suppressDishRecommend`，
前端据此隐藏对应模块。

#### 4.1.2 多批合并

| 项 | 规则 |
|---|---|
| 跨批排序 | 一律按 §4.6 的**排序总则**，不按批次号；本节不重复表述排序键 |
| 跨批去重 | **只在批次输入确有重叠时执行**，判据是 `segmentId` 相同；见 §4.1.3 |
| 姓名只在部分批出现 | 取所有非空值参与 §4.5 比对；全空视为该文件未识别出姓名 |

#### 4.1.3 去重只认 segmentId（§14 评审）

原设计按 `sectionName + name + value + unit` 四元组去重，**这会误删真实检查结果**：

```
左眼视力 5.0  /  右眼视力 5.0          ← 双侧检查，数值本来就相同
静息心率 72   /  运动后心率 72          ← 不同时间点
2023 复查值   /  2024 本次值           ← 报告附历史对比
```

改为：**同一 `segmentId` 且同一 `itemIndex` 才判定为重复**（同一段原文被两批各抽了一次），
保留 `page` 较小的一条。**非重叠页面之间一律不去重。**

#### 4.1.4 并发与资源控制

**批次之间全部并行，不串行。** 这不是优化，是必需——串行跑不完自己的 deadline：

```
一份 30 页报告，串行：
  OCR 30 页              60 ~ 90s     （按 2~3s/页）
  LLM-A 串行 4 批        240 ~ 720s   （每批 60~180s，视觉模型带 8 页图不快）
  组装                   < 10s
  合计                   310 ~ 820s   ← 上界超过 §2.3.4 的 600s deadline

并发后：90 + max(180) + 10 ≈ 280s
```

批次结构上互相独立：每批拿自己的 segment 和图像，返回自己的抽取结果，
没有哪一批需要另一批的输出。合并（§4.1.2）按 **§4.6 的排序总则**排序，
**不按批次完成顺序**，姓名合并、跨批去重、文件级裁决也都是拿到全部批结果之后才算，
乱序返回天然无影响。

**批次并发跑在独立的 `llmBatchExecutor` 上，不是任务池**（§2.3.3）——共用会线程饥饿死锁。

**并发度的上界由模型服务的配额倒推。** `W` 就是 §2.3.3 那个**任务池**的固定线程数：

```
峰值在飞 LLM-A 调用数 = 实例数 N × 单实例线程池大小 W × 单任务批次并发度 4

设模型服务允许的并发配额为 C，则   W = floor(C / (4 × N))
```

> **`N` 这一项是去掉消息队列之后新出现的，别漏。** 用 Redis 队列时，
> 无论部署几个实例，全局在飞任务数由"总消费者数"统一约束；改成本机线程池后，
> **每个实例都独立地跑满自己的 `W`**——两个实例就是 `2 × W × 4` 个在飞调用。
> 按单实例算出来的 `W` 直接乘 2 部署，等于把配额超用一倍，而全案零重试，
> 一个 429 就是一次用户可见失败（§2.5）。
>
> 配套约束：**`N` 变了必须重算 `W`**。扩容不是加机器就完事，它会直接改动模型侧的并发。

**不设独立的全局信号量。** `N × W × 4` 已经是硬上界，再叠一层信号量就有两个旋钮要调，
而且线程池的有界队列本身就是背压（§2.3.3）。信号量的价值是"小任务多跑几个"提高利用率
——但本系统每个用户一份报告只分析一次，吞吐压力极低，那点利用率不值得多一个组件。

> 真要跨实例精确控住配额，得引入分布式信号量或把队列加回来——两者都超出本次简单版的范围。
> **本次的口径是：靠部署纪律（`N` 固定、`W` 按 `N` 算）保证不超配额，不靠运行时机制。**

> 只有当实测发现 `N × W × 4` 明显跑不满配额、且确实存在排队时，才回来加信号量。

**这条尺寸现在是唯一的限流保护。** 全案零重试（§2.5），一个 429 就是一次用户可见失败，
所以 `W` 必须卡在配额之下，宁小勿大。模型服务的并发配额 `C` 需向服务方确认（§11-15）。

**内存：并发后必须只持有编码字节，不持有 `BufferedImage`。**

```
BufferedImage 2000×2800 × 3 字节 ≈ 16MB / 页
  32 页同时持有 ≈ 512MB / 任务          ← 几个任务并发就 OOM

渲染完立即编码 JPEG 并释放 BufferedImage
  编码后 ≈ 300~800KB / 页
  32 页 ≈ 20MB / 任务                   ← 差 25 倍
```

串行时同时只有 8 页，这个问题不明显；并发后是致命的。

**去掉消息队列之后这条更要紧：分析和 Web 层现在共用同一个堆**（§2.3.3）。
以前 Worker 至少有可能独立部署，OOM 只打掉分析；现在一次 OOM 会连 Tomcat 一起带走，
影响的是全部在线请求，而不只是正在分析的那几个用户。
堆的下限按 `W × 单任务峰值` 估，`W` 见上。

**任一批失败时不取消其余批次**，让它们跑完再丢弃结果。取消传播要引入
`Future.cancel` + 中断处理 + 半途响应的清理，换来的只是几秒的模型调用成本。
整任务已经确定要 `FAILED`，早几秒晚几秒无差别。

**心跳独立线程。** Worker 每 30s 更新 `heartbeat_at`（§2.3.4）必须由独立的调度线程执行，
不能挂在批次处理的主流程里——批次并行等待期间主流程是阻塞的，心跳停了会被巡检误杀。

#### 4.1.5 批次编址与输入预算

**渲染格式：每页一个页眉，每块一个批内块号 `blockRef`，从 0 起。**

```
每行格式：[块号] (textSource, bbox=x,y,w,h) 文本

=== 第 2 页 ===
[0] (NATIVE, bbox=72,110,180,22)  血脂检查
[1] (NATIVE, bbox=72,168,120,20)  甘油三酯
[2] (NATIVE, bbox=200,168,40,20)  2.8
[3] (NATIVE, bbox=250,168,60,20)  mmol/L
[4] (NATIVE, bbox=320,168,90,20)  0.56~1.70
[5] (NATIVE, bbox=420,168,60,20)  ↑偏高
=== 第 3 页 ===
[6] (NATIVE, bbox=72,110,180,22)  肝功能
...
```

**`bbox` 必须逐块随文本一起给，不能只给页面图。** §3.2.1 保留 bbox 就是为了这一步：
解析器不聚类行列（§3.2.1），模型要自己判断"这五个块是同一行"，
而**只看渲染顺序是判不出来的**——双栏页面上左右两栏的块可能交替出现，
表格页上块的顺序取决于绘制顺序。给了坐标，同一行的块 `y` 接近、`x` 递增，一眼可辨。

坐标系原点在页面左上角、单位像素、基准是同批下发的那张渲染图，旋转已由后端归一化。
Word 无版面坐标时 `bbox` 为空，此时只能按阅读顺序理解（§3.2.1）。

```
blockRef      批内块号，按【segment 的 seq 升序】渲染，0 起连续，模型只回它
映射表        Java 在发出请求时就持有 blockRef → segmentId 的数组，收到响应立刻展开
渲染顺序      是契约的一部分，不是实现细节 —— 序号的含义完全由它定义
```

**Java 做的是查表，不是推断**（§0-2）：映射表是它自己刚发出去的那一份，
展开过程不含任何语义判断。越界或重复的 `blockRef` 按「引用了不存在的块」处理
（§4.4-⑤ 丢弃），不是新的失败类型。

**为什么不直接发 `segmentId`：**

| 编址 | 前缀开销/批 | 合计/批 | 一份报告（8 批） |
|---|---|---|---|
| `f0-p12-s3456` 全局主键 | 30.4k | ≈65k | ≈521k |
| `[147]` + 每页页眉 | 14.4k | **≈49k** | **≈394k** |

（按 8 页/批、400 块/页上界、每页 2000 字、页面图缩到长边 1568px 估算，**待实测校准，§11-20**）

全局唯一性在批内是纯浪费：`f` 恒定（批次不跨文件）、`p` 已经由页眉承载、
`s` 只需要区分本批内部。省下的 24% 全部来自不再逐块重复这三段。

> **页眉必须给真实页码。** `sectionRelation` 的 `CONTINUATION` 判断依赖模型知道
> 自己在第几页、是不是接着上一批（§4.1），写成「本批第 1 页」会让它失去跨批位置感。

**一个格式，不是两个。** 早先考虑过"输入简写、输出写全"，那会引入一类新的整任务失败
——模型照抄简写、撞上 `segmentId` 的格式校验、整批作废（§4.4-①）。
现在契约里 `blockRefs` 就是整数数组，模型没有"抄错格式"的余地。

**（2026-09-02）原先这里写着「每批输入预算 ≤ 60k token，硬约束」，已删除。**
它既不准也从未被执行：实测 8 页带图是 **89k~101k token**，超了 1.7 倍而模型照常返回、
链路一路跑通——没有任何一层在检查它。留着一个不准又不生效的数字，只会让后续每一次
容量讨论都从一个错误基线出发。

**真正约束一个批次的不是输入侧。** 实测 prefill ≈2,800 token/s、decode ≈106 token/s
（qwen3.5-35B-A3B），输入 101k 只占 24 秒，**时间几乎全花在逐 token 生成上**；
而输出量由这一批有多少条目决定，不是由页数决定（实测同为 4 页，输出从 1.3k 到 10.4k 不等）。
所以真正的天花板是 `llm.extraction.read-timeout-millis` 除以 decode 速率，
按 180 秒算约 19k 输出 token。**要控批次规模，得控预估条目数，不是控输入 token。**

下面这几条解析阶段的上限**继续有效**——它们防的是 segment 数失控，与 token 预算无关：

```
PDF 原生文本层   segment/页 ≤ 400   → §3.2.1 密度闸，超了整文件改走 OCR
OCR 路径         识别块/页 ≤ 400   → 【整任务 FAILED / UNREADABLE】，不做局部截断
Word             逻辑分块 = 40/等效页（§3.3.1 的折算系数），故 30 等效页 = 1200 segment
                 上限直接写在 §3.3.1：segment ≤ 1200 且 内嵌图片 ≤ 30
```

OCR 路径原先没有上界，是个漏写：密度闸只看 PDF 原生文本层，
而**被密度闸赶去 OCR 的恰恰是最碎的那类文件**，不给 OCR 侧设界等于闸门可以被绕过。

**OCR 超限为什么是整任务失败，而不是丢掉那一页。**
局部截断要新定义一整套东西：`partial_reason` 加一个 `OCR_BLOCK_OVERFLOW` 枚举、
`processedPages` 怎么算、模块三四关不关、`PAGE_TRUNCATED` 的语义要不要扩宽
（它现在只表示"超过 30 页"）。而这些定义完之后，**风险仍然在**：

```
被丢掉的那一页恰好是过敏原筛查页
  → allergens 数组少了内容，但 §4.4-② 的关键词扫描也扫不到那页（它整页没进来）
  → 系统认为"报告里没有过敏原"，模块四照常输出
  → 用户拿到一份没有考虑过敏的推荐
```

这与 §4.1.1 「读不清的那一批恰好可能就是含过敏原和医嘱的那一批」是同一个论证。
**一页都读不明白就说明这份文件的 OCR 质量不可信，整任务 `UNREADABLE` 是唯一诚实的结论**
——用户重传一张更清楚的照片，比拿一份缺了一页的分析有用。
不新增 `partial_reason` 枚举，不扩宽 `PAGE_TRUNCATED` 的语义。

**页/批 与 8 批上限是绑死的，不能单独调。** 实测超预算时的直觉反应是降页数，但这条路堵着：

```
任务总等效页数上限 30（§3.3.2）
8 页/批：最坏分布 9/9/9/2/1 → 2+2+2+1+1 =  8 批   ← 正好卡满上限
4 页/批：同样分布            → 3+3+3+1+1 = 11 批   ← 超上限，且单个 30 页文件独占 8 批
```

所以「降页数换 token」必须连着动 §4.1 的 8 批上限和 §4.1.4 的并发度 `W = floor(C/4)`，
三者是一组参数。**不要单独改其中一个。**

### 4.2 输出契约

> **本节是可读示例，不是契约本身。** 正式契约是 `schema/extraction_output.schema.json`，
> 需定义类型、`null` 规则、字符串长度上限、数组条数上限、枚举取值、
> `additionalProperties: false`、各序号字段最小值、以及 `batchStatus != OK` 时各字段的取值要求。
> **该文件是开发前置交付物，并纳入契约测试。**

```jsonc
{
  "batchStatus": "OK | NO_REPORT_FEATURE | UNREADABLE",

  // ★ 所有 blockRef / blockRefs 都是【批内块号】（整数，§4.1.5），不是 segmentId。
  //   Java 收到响应后按映射表展开成 segmentId，本文档其余各节讲的都是展开之后的东西。

  // 仅用于同一性校验，Java 用完即丢，不入任何存储；非空值必须带原文证据
  "patient": {
    "name": "张三|null", "nameBlockRefs": [2],
    "gender": "男|女|null", "genderBlockRefs": [3]
  },

  // 报告自带的汇总数字，没有则为 null（不许模型自己算）
  // blockRefs 必填，指向印着这行数字的原文块；只给两个数会被 Schema 拒绝
  "reportOverview": { "totalCount": 87, "abnormalCount": 12, "blockRefs": [5] },

  // 章节归属由 LLM-A 给出（§0-2、§4.1）。sectionIndex 是【批内局部序号】，每批从 0 起；
  // 跨批对齐靠 sectionSegmentId，全局键由 Java 按 sectionRelation 拼
  "sections": [{
    "sectionName": "血脂检查",   // fileIndex 不在这里，批次不跨文件，取顶层的那个（§4.1）
    "sectionIndex": 2,
    "sectionRelation": "CURRENT | CONTINUATION | UNSECTIONED | UNKNOWN",  // ★ 批次边界必须显式表态
    "sectionBlockRef": 10             // ★ 印着该章节标题的块；CURRENT 必填，另三态必须为 null
  }],

  // 有数值 + 有结论 → 健康指标模块（正常项也要提）
  "indicators": [{
    "name": "甘油三酯",
    "value": "2.8",
    "unit": "mmol/L",
    "refRange": "0.56~1.70",          // 报告没写则 null
    "conclusionText": "↑偏高",
    "status": "NORMAL | HIGH | LOW | ABNORMAL",
    "includeInHealthProblems": true,  // ★ 模块二准入，由 LLM-A 判定，Java 不从 status 派生（§0-2、§6.1）
    "problemName": "甘油三酯偏高",     // ★ 模块二展示名，取报告原文中的自然语言问题表述；
                                      //   报告只有指标名和符号时为 null，由 §6.2 拼两段原文
    "sectionIndex": 2,
    "orderInSection": 3,              // ★ 批内序号，§6.3 排序要用
    "itemIndex": 0,                   // ★ 同一块内第几条，展开后与 segmentId 一起构成去重键（§4.1.3）
    "blockRefs": [17, 18, 19, 20, 21] // ★ 唯一定位凭据，上述原文字段必须都能在这些块的合并文本里找到
  }],

  // 无数值 + 有结论 → 候选健康问题
  "textualFindings": [{
    "title": "脂肪肝",
    "conclusionText": "提示脂肪肝",
    "status": "NORMAL | ABNORMAL",
    "includeInHealthProblems": true,
    "sectionIndex": 5,
    "orderInSection": 1,              // ★ 补：§6.3 排序要用
    "itemIndex": 0,
    "blockRefs": [34]
  }],

  // 总检结论 / 医生建议，逐条
  "summaryConclusions": [{
    "sourceOrder": 2,                 // ★ 0 起连续序号，排序真源
    "itemNo": 3,                      // 报告原文编号，无编号时为 null，仅用于来源标注文案
    "categories": ["HEALTH_PROBLEM", "DIET_ADVICE"],   // ★ 数组，一条可含多种语义
    "includeInHealthProblems": true,
    "sectionIndex": 9,
    "itemIndex": 0,
    "blockRefs": [112]
  }],

  "allergens": [{
    "enumKey": "SHRIMP_CRAB | ... | OTHER",
    "isFoodBorne": true,
    "rawName": "虾蟹类",
    "rawResult": "阳性(+)",
    "resultStatus": "POSITIVE | NEGATIVE | BORDERLINE | UNKNOWN",
    "sectionIndex": 7,
    "sourceOrder": 0,                 // ★ 在过敏章节内的批内顺序，§7.2 要求「按报告原文顺序混排」
    "itemIndex": 0,
    "blockRefs": [89]
  }],

  // ★ 一条原文可拆成多个枚举条目，共享同一块，各自给不同 itemIndex
  //   「建议低脂低盐饮食」→ 两条：LOW_FAT 与 LOW_SODIUM，来源字段完全相同
  // ★ sectionIndex / sourceOrder / itemNo 三个字段是模块三来源标注的唯一依据（§7.6）
  "nutritionSupplements": [{
    "enumKey": "IRON | ... | OTHER",
    "sectionIndex": 9,                // ★ 来源章节，指向 sections 的批内下标
    "sourceOrder": 2,                 // ★ 该条在所属章节内的批内序号，【仅用于排序】
                                      //   「第N条」只能用 itemNo，itemNo 为 null 就不写条号（§7.6）
    "itemNo": 4,                      // ★ 报告原文印着的条目编号，没印则 null
    "itemIndex": 0,
    "blockRefs": [114]
  }],

  "dietRequirements": [{
    "enumKey": "LOW_FAT | ... | OTHER",
    "sectionIndex": 9,
    "sourceOrder": 3,
    "itemNo": 5,
    "itemIndex": 0,
    "blockRefs": [115]
  }, {
    "enumKey": "LOW_SODIUM",
    "sectionIndex": 9,
    "sourceOrder": 3,                 // ★ 同一条原文拆出来的，来源三字段必须一模一样
    "itemNo": 5,
    "itemIndex": 1,
    "blockRefs": [115]
  }],

  // ★ 过敏漏抽覆盖检查的输入（§4.4-②）。前者是过敏章节的【全部】块（含没抽出条目的数据行），
  //   后者是其中【确认读到了检测数据行】的子集。Java 靠两者之差区分
  //   「读全了全是阴性」和「一行都没读出来」——漏圈等于关掉最后一道防线。
  "allergenSectionBlockRefs": [88, 89, 90, 91],
  "allergenDataBlockRefs": [89, 90, 91]
}
```

**契约不允许模型返回不受来源约束的展示文案。** 凡是声明"来自报告原文"的字符串，
都必须能在它自己的 `blockRefs`（或 `sectionBlockRef`）**展开后**对应的文本里找到，
否则该条目不进入展示。

> 下表及本文档其余各节一律按**展开后**的口径写（`segmentIds` / `sectionSegmentId`）。
> 展开发生在 Schema 校验之后、任何业务校验之前（§4.4-①a），此后 `blockRef` 不再出现。

> 早先这里写的是「契约里没有任何"原文字符串"字段」。**这句话不成立**——
> `sectionName` / `name` / `conclusionText` / `problemName` / `title` / `rawName` /
> `rawResult` 都是模型返回的原文串。写成"没有"的后果不是越界，而是**校验范围说不清**：
> 实现时容易只校验被点名的那几个（原先只有指标五字段），剩下的裸奔。

**受来源约束的字段与校验对象（档位一律按该 segment 的 `textSource` 分档，§3.2.3）：**

| 字段 | 校验对象 | 校验不过的处理 |
|---|---|---|
| `indicators`：`name` `value` `unit` `refRange` `conclusionText` | 该条目 `segmentIds` 的合并文本 | 该指标**整条丢弃** |
| `indicators.problemName`（非 null 时） | 同上 | 降级为 `null`，走 §6.2 的拼接分支，不丢条目 |
| `textualFindings`：`title` `conclusionText` | 该条目 `segmentIds` | 该条**整条丢弃** |
| `allergens`：`rawName` `rawResult` | 该条目 `segmentIds` | 该条丢弃，**且触发 `ALLERGEN_SUSPECT_MISS`**（§4.4-⑤） |
| `sections.sectionName`（`sectionRelation = CURRENT`） | 该章节的 `sectionSegmentId` | 该组 `displayName` 降为「未标注章节」，**不丢内容** |
| `sections.sectionName`（`sectionRelation = UNSECTIONED`） | **该组覆盖的全部 segment 的合并文本**（它没有 `sectionSegmentId`，见下） | 同上，降为「未归入章节的内容」，**不丢内容** |
| `reportOverview.totalCount` `abnormalCount` | `reportOverview` 的 `segmentIds` 合并文本；两个十进制数字都必须存在 | 整个 `reportOverview` 降为 `null`，回退到 §5.4 的 Java 计数 |
| `summaryConclusions` | 无原文字符串字段，展示直接取整段 `rawText` | — |
| `patient.name` / `gender`（非 null 时） | 各自的 `nameBlockRefs` / `genderBlockRefs` 展开后的文本 | 对应字段降为 `null`，不得参与 §4.5 的冲突判断 |

`patient.name = null` 时 `nameBlockRefs` 必须为空数组，`gender` 同理；字段非空时对应证据数组
至少有一个元素。**“不展示”不是免校验理由**——姓名和性别虽然不展示，却能把整个任务判成
`IDENTITY_MISMATCH`，因此必须与展示字段使用同一套来源约束。

**`UNSECTIONED` 的 `sectionName` 会展示，所以它同样要有来源。** 这一态按定义没有
`sectionSegmentId`（没有印着标题），但它仍然是要显示在分组标题上的字符串——
不校验就是一句模型自由生成的展示文案，与本节开头那条和 §0-1 直接冲突。
校验对象放宽成**该组覆盖的 segment 合并文本**：封面上真印着「检查须知」四个字时留得住，
模型自己概括的「其他内容」留不住，降为固定文案。`CONTINUATION` 用被继承章节的名字
（已在 `CURRENT` 时校验过），`UNKNOWN` 一律固定文案、不采信模型（§4.6）。

`nutritionSupplements` / `dietRequirements` 没有模型复述的原文串，展示原文由 Java 按
`segmentId` 取整段（§3.2.3）；但它们**必须带 `sectionIndex` / `sourceOrder` / `itemNo`**
——模块三的来源标注（§7.6）要靠这三个字段生成，缺了 Java 就只能猜。

**展示层的长文本一律不用模型返回值。** 健康问题的 `rawText`、饮食建议的来源原文，
都由 Java 按 `segmentId` 取整段 `rawText`，模型只负责指路——这一条没变。

### 4.3 提示词的硬约束

1. **只抽报告里写了的，不推断、不补充、不改写。**
2. **准入四分法**（需求 §5-2，2026-08-27 修订）：
   有检查结果+有结论 → `indicators`（**含结论为正常的**），`conclusionBasis = REPORT_TEXT`；
   **有检查结果+无结论+能与参考值明确比较 → `indicators`，走参考值准入**（见 §4.3.1）；
   有检查结果+无结论+无法明确比较 → 全部丢弃；无检查结果+有结论 → `textualFindings`。
   说「检查结果」而不是「数值」：定性项目（阴性/阳性）也是检查结果。
#### 4.3.1 参考值准入

分两条依据：结果是数值的走 `REFERENCE_RANGE_IN_RANGE`，结果是定性的走 `REFERENCE_VALUE_MATCH`。

##### 数值型：参考范围比较

很多报告用「提示列留空」表示正常，正常项因此一条结论文字都没有。这类指标同样要展示，
`conclusionBasis = REFERENCE_RANGE_IN_RANGE`、`conclusionText = null`。

**职责切分是这条设计的全部要点：**

| LLM-A | Java |
|---|---|
| 判断结果、单位、参考范围是否属于同一个指标 | —— |
| 多套参考范围（男/女、年龄段、孕期、方法学）里选出适用的那一套 | **不理解这些语义** |
| 确认单位已对齐 | **不做任何单位换算** |
| 把区间拆成 `lowerBound` / `upperBound` 与开闭标志 | **不解析 `4.0~10.0` 这种原文** |
| —— | 只对拆好的十进制数做 `BigDecimal.compareTo` |

**为什么必须这么切**：参考值的书写形态穷举不了（`男：40~50 女：35~45`、`0.5-1.0×10⁹/L`、
全角 `＜3.0`、`见报告单`……）。让 Java 去解析，就是 §0-2 禁止的「阈值靠猜、换个版式就翻车」；
而且失败方向最坏——**解析成功但解析错，会把异常判成正常**，还不会报错。
拆成结构化数之后，Java 的输入是四个十进制数加两个布尔，这才真正可穷举、可单测。

**四条硬规则：**

1. **报告已印结论时永远以报告结论为准**，Java 不得改判报告印的 ↑ / H / 异常；
2. **只准入「落在范围内」**。超出范围而报告没给结论的一律不展示——
   系统只做「确认正常」，**绝不生成一个报告没写过的异常结论**；
3. 拆不出唯一比较条件（多套人群范围无法确认适用、单位不一致、参考值非数值）时
   `rangeComparison = null`，该指标不展示，**不猜**；
4. **模型报的整组区间必须与 `refRange` 原文里写着的某一个区间完全一致**
   （下界、上界、两侧开闭一起对上，由 `ReferenceRangeParser` 解析原文得到），
   结果值必须与 `value` 数值相等，核验不过整条丢弃。

   > **只核验单个边界不够**（2026-08-28 修）。原先只要求「边界数字是 `refRange` 的子串」，
   > 三种写法都能绕过：`4.0~10.0` 只报下界（上方随即不设限，12.5 也算正常）、
   > `14.0~20.0` 报一个恰好是子串的 `4.0`、`<3.0` 报成闭区间（3.0 也算正常）。
   > 三种都会让系统对着一份报告没写过的范围宣布「在参考范围内」。
   > 因此 Java 自己解析一遍原文——**只认闭区间 `a-b`/`a~b`/`a至b` 与单边
   > `<`/`≤`/`<=`/`>`/`≥`/`>=` 这几种可穷举写法，认不出就不展示**（fail-closed）。
   > 一段里写了多套人群范围时全部解出，模型选中哪一套都能核验，选一套没写的就不行。
   > 职责边界没变：**挑哪套范围、单位对不对齐仍由 LLM-A 判断**，Java 只回答
   > 「这组边界是不是原文里写着的」。

##### 定性型：参考值精确匹配

报告印了定性结果与定性参考值（「亚硝酸盐 阴性 / 参考值 阴性」）时，
由 LLM-A 把结果归一化成 `ComparableQualitativeValue` 枚举、把参考值展开成允许取值的集合，
Java 只做集合包含判断。

**参考值常常不是单值**：尿胆原的参考值「阴性或弱」允许阴性与弱阳性两种，
所以是 `acceptableReferenceValues: ["NEGATIVE", "WEAK_POSITIVE"]` 而不是单个值。

> **刻意不做字面子串匹配。**「阳性」是「弱阳性」的子串，按字面包含会把阳性结果
> 判成「符合参考值」——这是把异常判成正常，最危险的错法。同理「0」是「0-2/HP」的子串。
> 展开成枚举集合之后，这类误判从根上不存在。

**「阴性」与「未检出」是否等价属于医学等价判断，Java 不碰。** 要认，也得由模型在归一化时
统一成同一个枚举；枚举刻意只收 `NEGATIVE` / `POSITIVE` / `WEAK_POSITIVE` / `NOT_DETECTED`
四个，**新增须走医学评审**，归不进去的给 `valueMatch = null`、该指标不展示。

> **信任边界与数值型不同，这一点必须写明。** 数值型的上下界能回到 `refRange` 原文逐字核验；
> 而归一化枚举**无法回溯原文**——「阴性」被映射成哪个枚举，Java 无从验证。
> 能兜住的只有 `value` 与 `refRange` 本身都已通过来源回切、确实是报告上的原话。
> 因此这条路径对模型归一化的正确性是**信任**关系，不是校验关系。

##### 展示与语义边界

展示上**不得把系统推导冒充报告原文**：数值型用内容常量「在参考范围内」，
定性型用「符合报告参考值」，并下发 `conclusionGenerated = true` 供前端做视觉区分（需求 §5-3）。

**两条路径给出的 `status = NORMAL`，语义都是「符合本报告给出的参考值」，
不是「未发现疾病」、更不是「身体正常」。** 文案刻意停在陈述事实这一层：
写成「未见异常」「正常」都会把「符合参考值」扩大解释成医学结论。
这条语义必须同步出现在需求、接口字段说明与页面文案里（需求 §5-3）。

3. **不许把"没找到"写成空数组。** 每个字段都必须出现；确实没有内容才给空数组，这是主动断言。
4. **枚举归一化只做精确语义匹配，宁可给 `OTHER` 也不要就近映射。**
   **一条原文含多个要求时拆成多条**，共享同一块，各自给不同的 `itemIndex`。
5. **饮食相关表述的抽取范围：总检结论 + 医生建议章节**（含「专家建议」「健康指导」
   「医师建议」等同义章节名）。**排除各科小结、检查须知、科普段落、检查前准备**
   ——「胃镜检查前禁食」「检查前三天低脂饮食」是临时要求，不是长期饮食建议（§12-6）。
6. **`status` 的判定权分级。** 报告原文已给出**明确方向标记**的（↑ ↓ 偏高 偏低 增高
   降低 升高 减低 正常 未见异常 异常 H L），必须照抄词表口径。
   **非方向性结论**——「阳性(+)」「阴性(-)」「弱阳性」「可疑」「临界」——由模型
   结合该指标的临床含义判断。

   > 两级的**区分**只写在提示词里，不再要求模型回传标记字段：
   > 原先的 `statusJudgedByModel` 已随计数一并下线（§4.4-③），见本节下方表格。

   > **「阴性」同样不能无条件当成正常（§16 评审）：**
   > ```
   > 过敏原-虾蟹类   阴性(-)  →  正常
   > 乙肝表面抗体    阴性(-)  →  不是"正常"，是没有抗体，报告通常提示接种疫苗
   > 大便隐血        阴性(-)  →  正常
   > ```
   > 原设计把「阴性」直接放进正常词表，与"阳性不一定异常"的论证是同一个错误的两面。
   > 现在阴性与阳性走同一条路径：报告有明确标记时照抄，没有时交给模型判断。

   判断依据只能是该指标本身的临床含义，不得参考其他指标、不得做整体健康评价。

7. **`textualFindings.status`**：无数值的文字结论**同样有正常项**——
   「肝胆B超：未见明显异常」「心电图：窦性心律，正常心电图」。
   `status = NORMAL` 的条目 `includeInHealthProblems` 必须为 `false`。
8. **`summaryConclusions.categories` 是数组**，一条可以同时命中多种：
   「超重，建议控制体重，低脂饮食」= `[HEALTH_PROBLEM, DIET_ADVICE]`。
   只要含 `HEALTH_PROBLEM` 或 `DIET_ADVICE`，`includeInHealthProblems` 才可以为 `true`。

   | category | 例子 |
   |---|---|
   | `HEALTH_PROBLEM` | 甲状腺结节，建议复查；超重 |
   | `DIET_ADVICE` | 建议低脂低盐饮食 |
   | `LIFESTYLE` | 建议戒烟限酒；保持规律作息 |
   | `ROUTINE` | 建议每年参加健康体检；建议继续保持适量运动 |
   | `NORMAL_STATEMENT` | 各项检查未见明显异常 |

9. **`allergens.resultStatus` 必须逐项给出。** 完整的过敏原筛查表会列 20~40 项，
   绝大多数是阴性。**抽取时全部列出并如实标注结果**，由 Java 按 §4.4 过滤，
   不许模型自行省略阴性项——省略了 Java 就无法核对模型是不是漏抽了阳性项。

10. **章节归属由模型给全，批次边界必须显式表态。** 每批返回本批出现的 `sections`，
    `sectionIndex` 是**批内局部序号**，`sectionSegmentId` 指向印着该章节标题的 segment。
    本批第一个条目之前没有标题时，**必须用 `sectionRelation` 说清是哪种情况**
    （`CONTINUATION` / `UNSECTIONED` / `UNKNOWN`，§4.1），**不许一律给"承接上文"**——
    封面和须知页被判成 `CONTINUATION` 会被挂到上一个检查章节下面。
    看不清或拿不准一律 `UNKNOWN`，这不算失败，`UNKNOWN` 只是不归组。
    每个 `indicators` / `textualFindings` / `summaryConclusions` / `allergens` 条目
    都必须给出本批内的 `sectionIndex`。

10a. **同一表格单元格 / 同一行的判断也归模型**（§3.2.1）。解析器只给原子文本块和 `bbox`，
    不切单元格、不聚行列。一个指标的字段落在几个块里就引用几个 `blockRef`，
    **绝不跨栏、跨行乱引**——Java 只会把它们拼起来查子串，它看不到版面。

11. **`indicators.includeInHealthProblems` 由模型判定，不是 `status != NORMAL` 的同义词。**
    「甘油三酯 3.5 ↑」进模块二；「白细胞 3.9，参考 4.0~10.0，↓」这类临界降低而报告未作提示的，
    模型可以判 `false`。**准入是语义判断，Java 不从 `status` 反推**（§0-2、§6.1）。

12. **`indicators.problemName` 只能是报告原文里的自然语言问题表述**
    （「甘油三酯偏高」「血脂异常」），且必须能在该条目的 `blockRefs` 所指的块里找到。
    报告只有指标名加符号、没有成句表述时，**必须给 `null`**——由 §6.2 拼两段原文，
    **不许模型自己造一个说法**，也不许 Java 事后补一个归一化结论词。

### 4.4 Java 校验层

**① Schema 校验。** 违规能定位到单个条目 → **剔除该条目**并标记部分结果；
定位不到（顶层、`allergens`、`sections`）→ **直接** `FAILED / SERVER_ERROR`。**一律不重试。**

模型返回结构不合法是提示词或模型本身的问题，同样的输入再问一遍多半还是同样的结果；
重试只会把一次确定的失败拖成两倍延迟。Schema 校验频繁失败时应改提示词或换模型，不是加重试。

**（2026-09-02）为什么从「一票否决」改成「剔除条目」。** 用真实报告实测，单条目不合 Schema
的概率约 **1.2%**。整批作废模式下一批的通过率是 `(1-p)^条目数`——一份 24 页报告约 200 条，
**整任务成功率只有 8%**。失败率被条目数指数放大，靠优化提示词压不住这个乘方
（实测三批预测通过率 64% / 59% / 98%，与实际的一败两过吻合）。

```
可剔除    indicators  textualFindings  summaryConclusions  nutritionSupplements
          dietRequirements —— 可剔除，但【剔了就抑制模块四】，见下方 DIET_REQUIREMENT_DROPPED
不可剔除  allergens   —— §0-6 一级红线；静默少一条会让 D \ A 非空、触发 ALLERGEN_SUSPECT_MISS，
                        把【格式错误】伪装成【漏抽降级】，污染那个信号的含义
          sections    —— 被其他条目按 sectionIndex 引用，剔除破坏引用完整性
          顶层字段    —— 定位不到「某一条」，剔除无从下手
```

**剔除后必须重新校验一次**，仍不合法则整批作废：防的是「违规其实不在被剔除的那条上」
和「数组整体约束（如 `minItems`）剔完反而更不满足」——不做任何猜测式修复。

**剔除量上限 20%**（且至少允许 1 条，否则只有两三条的小批次剔一条就超标，
那恰恰是本次改动要消除的失败形态）。超预算说明这一批整体跑偏，放行会得到一份严重残缺
却只标着 partial 的报告，不如响亮地失败。

**剔除不是「悄悄丢」**：翻转 `PartialReason.SCHEMA_ITEM_DROPPED`（**抑制范围为空**，
不影响任何模块输出），并记一条带**违规关键字与 JSON 路径**的 WARN 日志
（`ValidationMessage` 正文可能含患者姓名与检验值，一个字都不记）。
**该日志是本机制唯一的观测手段**——13 个进程级计数已于 2026-08-27 全部下线（§4.4-③），
本次不为它新增计数。

> **"Schema 通过"不等于"报告已完整识别"。** 模型返回 `"allergens": []` 结构上完全合法。
> 字段必填只能区分"模型没返回这个字段"，区分不了"模型漏抽了内容"。

**①a `blockRef` 展开（§4.1.5）。** Schema 通过后的**第一件事**，早于所有业务校验：

```
blockRefs / sectionBlockRef / nameBlockRefs / genderBlockRefs
    → 查本批的 blockRef → segmentId 映射表，逐个展开
    → 越界、重复、或映射不到 → 该引用视为「不存在的 segmentId」，按 ⑤ 处理
    → 展开完成后 blockRef 不再出现在任何下游逻辑里
```

映射表是 Java 自己发请求时构造的，展开是查数组，不含任何判断（§0-2）。
**下游全部按 `segmentId` 工作**——去重（§4.1.3）、跨批合并（§4.1.2）、分组（§4.6）
都需要跨批唯一，而 `blockRef` 只在本批内有意义。

**② 高风险内容交叉扫描，命中即安全降级（§7 评审）。**
对全部 segment 的 `normalizedText` 做关键词扫描：

| 扫描目标 | 对应数组为空时 |
|---|---|
| 过敏原章节名（过敏原、变应原、IgE、致敏原） | **立即降级**，不定向重试 |
| 阳性标记（阳性、(+)、＋）出现在过敏章节 segment 内 | 同上 |

```
过敏类命中而 allergens 为空 → partial = true, partial_reason = ALLERGEN_SUSPECT_MISS
                              → 模块一二三照常，模块四整体不输出（§4.1.1）
                          ```

> **饮食医嘱词扫描（低脂/低盐/低糖/低嘌呤/忌口/忌食）已删除，`dietSuspectMissCount` 不再存在**
> （2026-08-25）。它只记计数、不影响任何输出，正是 §0-2 和 P0-0 明令取消的那一类
> ——**不得为了"只告警"在生产链路里扫词表**。
>
> 它本来也不好用：误报率高（科普段落、检查须知里都会出现"低脂饮食"四个字），
> 而且饮食建议漏抽的后果是模块三少一条，不是安全问题。
> **想知道饮食建议抽得全不全，去跑评测集**（§11-10、§11-18），那里有人工标注的真值。
>
> 本节剩下的过敏类扫描**保留**——它触发安全降级，属 §0-2 唯一允许的那类词表用法。

**①b 引用完整性校验（Schema 管不了的部分）。** 展开 `blockRef` 之后立刻做，
全部是下标与集合包含判断，**不涉及任何版面或语义推断**（§0-2）：

```
sections 自洽
    sections[i].sectionIndex 必须唯一，且 == i（数组下标）
    → 不满足：FAILED / SERVER_ERROR，属模型契约违约

条目引用有效
    每个 indicators / textualFindings / summaryConclusions / allergens /
        nutritionSupplements / dietRequirements 条目的 sectionIndex
    必须 ∈ {sections[].sectionIndex}
    → 不满足：该条目【整条丢弃】
      （不整批失败——一条引错不该毁掉整批；但丢弃时若该条属于 allergens，
        按 §4.4-⑤ 触发 ALLERGEN_SUSPECT_MISS）

sourceOrder / orderInSection 连续性
    只做【非负且不超上限】的范围检查，不要求连续
    → 模型漏号不影响排序结果，不值得为此判失败
```

**Schema 只能约束"是个 0~199 的整数"**，约束不了"这个下标真的存在"。
没有这一层，一个 `sectionIndex = 7` 而本批只有 3 个章节的条目会一路走到分组逻辑，
在 §4.6 那里变成一个指向空气的 `groupKey`——分组标题空白，且**排查时看起来像是分组代码的 bug**。

**②a `allergenSectionBlockRefs` / `allergenDataBlockRefs` 的集合规则。**
这两个数组的用途是区分「读全了，全是阴性」和「有数据行，但一行都没读出来」
——只看"抽出了几条阳性"这两者分不清。展开成 segmentId 后判定：

```
S = allergenSectionSegmentIds   过敏章节的全部段（含没抽出条目的数据行）
D = allergenDataSegmentIds      其中【确认读到了检测数据行】的段
A = allergens 各条目 segmentIds 的并集

结构断言（不满足 → FAILED / SERVER_ERROR，属模型契约违约）
    D ⊆ S            数据行必须是章节内的段
    A ⊆ S            抽出的过敏原条目必须落在过敏章节里

覆盖判定
    S 非空 且 D 为空                → 有章节、一行数据都没读出来
                                      → ALLERGEN_SUSPECT_MISS 降级
    D \ A 非空                     → 有数据行没有对应条目 = 漏抽
                                      → ALLERGEN_SUSPECT_MISS 降级，记漏抽段数
    D 非空 且 D ⊆ A 且 A 全是 NEGATIVE → 「读全了全是阴性」，正常，不降级
    S 为空                          → 报告没有过敏章节；此时以 ② 的关键词扫描为准
```

> **`D \ A 非空` 拦的是"模型读到了那一行，但没把它变成条目"**
> ——这正是 §4.4-① 的 Schema 校验查不出来的那类漏抽：`allergens` 数组非空、
> 结构完全合法，只是少了几条。
>
> **但它不是独立防线——`S`、`D`、`A` 全都来自同一次模型输出。**
> 模型如果**同时**漏掉某个数据行和它对应的条目，那一行既不在 `D` 也不在 `A`，
> `D \ A` 仍然为空，这一层什么都发现不了。**它只是模型输出的内部一致性检查**，
> 拦得住"自相矛盾"，拦不住"一致地漏"。②b 补的正是这一块——
> 它的候选段从原文独立找出，模型漏多少都不影响候选集大小。

原设计在这里安排了一次"定向重试"来压低误报率。去掉之后，误报会直接变成一次
模块四不输出——降级方向仍然安全，但触发频率会上升，需用评测集量化（§11-12）。

**原设计对过敏类只告警不阻断，与"过敏是安全红线"不一致，此处更正。**
不把整份报告判失败，而是关掉唯一会导致用户实际吃错东西的模块——
既躲开误拦正常报告，又不会在"可能漏了过敏原"的前提下继续推荐菜。

**③ 语义词表扫描已整体下线，不在生产链路里跑。**

原设计在这里做三件事，现已全部移出在线链路（2026-08-25）：

| 原检查 | 词表 | 去向 |
|---|---|---|
| `status` 与方向词比对 | `ConclusionLabelWords` | → 离线评测集（§11-18） |
| `isFoodBorne` 与非食物词比对 | `AllergenSectionWords` | → 同上（原 §4.4-④ 的告警分支） |
| 健康问题准入与正常语句比对 | `NormalStatementWords` | → 同上（原 §4.4-⑥ 的告警分支） |

`statusConflictCount` / `foodBorneConflictCount` / `normalAdmitSuspectCount`
**三个计数在生产环境不存在**，不要实现、不要打点、不要配告警。

**为什么下线。** 它们已经不影响任何输出（本轮先把三处 Java 覆写改成了只告警），
剩下的价值只有监控。而为了监控在**每一次用户请求**里跑三遍语义词表扫描，
代价与收益不成比例：

```
它是语义判断的形状      虽然只计数，代码长得跟"Java 在判断医疗语义"一模一样，
                        下一个人很容易把它改回覆写——§0-2 的边界靠代码里没有这段来保证最牢
它逼着词表进生产        三张词表要打包、要发版、要跟着模型版本维护，而它们不产出任何结果
同样的信息评测集就有    评测集是人工标注的真值，比"词表和模型不一致"这种代理信号准得多
```

**替代方案是评测集，不是"没有监控"**（§11-18）：模型换版本、改提示词、报告形态变化时，
跑一轮评测集，用**人工标注的真值**衡量，而不是用词表这个代理信号。
代价写在下面，是明确接受的。

> **明确接受的代价：生产环境没有模型漂移的实时信号。**
> 模型静默变差时，要等到下一次跑评测集、或用户反馈，才会发现。
> **发版前跑评测集因此从"建议"变成"必须"**（§11-18）。
>
> 缓解仍在展示层：§5.2 规定**标签文字永远是 `conclusionText` 原文**，
> 模型把「甘油三酯 ↑偏高」判成 `NORMAL` 时，用户看到的仍是「↑偏高」三个字，
> 只有标签颜色错了，不会被系统的措辞误导。

**（2026-08-27）原先保留的那批「处理过程副产品」计数已全部下线。**
曾经的清单是：`schemaMissCount`、`evidenceMissCount`、`ocrFuzzyMatchCount`、
`allergenSuspectMissCount`、`allergenPositiveUncoveredCount`、`adviceOtherCount`、
`sectionRefMissCount`、`highRiskSuppressedCount`、`allergenUnknownCount`、
`sectionUnknownCount`、`glyphLevelPdfCount`、`residualNonStandardCount`、
`statusJudgedByModelCount`。

**下线理由是它们只写不读**：每个计数都有自增点，却<b>没有任何读取点</b>——
既不进日志，也没有 actuator 或别的导出方式，`src/main` 里连一次 getter 调用都没有。
一个读不到的计数不提供任何信息，只提供一处需要维护的并发状态。
本节开头那句「加一个计数器只是多一处状态」，对它们同样成立。

**现在生产环境不存在任何进程级计数**，由 `ProductionCounterContractTest` 断言兜住。
将来确有观测需求时，**先把导出口径定清楚再实现**——先加计数、指望以后补导出，
正是这 13 个的来路。

> **§4.4-② 的高风险交叉扫描不在下线之列。** 它也是词表，但它是**安全降级的触发条件**
> （§0-6 一级红线），不是计数器——关掉它等于关掉"可能漏了过敏原"的最后一道检查。
> 判据很简单：**影响输出的留下，只用于观察的下线。**

**④ 过敏原准入过滤：**

```java
// 仅以下两类进入过敏提醒区与菜品拦截链路：
resultStatus == POSITIVE
resultStatus == BORDERLINE      // 弱阳性/可疑/临界进入产品安全过滤，但不等同临床确诊（§12-7）

// 以下都不进入过敏提醒区与菜品拦截链路：
resultStatus == NEGATIVE        // 阴性绝不能进 —— 一张 30 项的筛查表里 28 项是阴性
resultStatus == UNKNOWN         // 不得自动当成阳性，也不得当成阴性
```

**`UNKNOWN` 与 `NEGATIVE` 都不进链路，也都不计数。** 两者含义相反：
`NEGATIVE` 是"读清楚了，是阴性"，`UNKNOWN` 是"这一行没读明白"，后者升高说明报告质量或识别质量在变差，
而它恰恰**不会**触发任何降级（§4.4-② 只在 `allergens` 整个为空时才响）。
**但这不构成加一个进程级计数的理由**：`allergenUnknownCount` 正是本节前面下线的那 13 个之一
——只写不读的计数看不见任何变差。要观测它，按下线那节的要求**先定清楚导出口径再实现**。

`isFoodBorne` **不由模型自由判定，也不由 Java 词表推翻——它由 `enumKey` 查表得到**（§0-2）：

```
enumKey 是正式枚举（13 食入性 + 5 非食物，§7.2）
    → isFoodBorne 是该枚举的固有属性，Java 直接查 AllergenGroups 常量表
    → 模型返回的 isFoodBorne 直接丢弃，不比对、不计数（§4.4-③ 已下线）
enumKey == OTHER
    → 采信模型的 isFoodBorne（枚举外过敏原只有模型判得了）
    → Java 不做任何词表校验
```

查表不是医疗判断，是常量属性——`SHRIMP_CRAB` 是不是食物，在枚举表定义那一刻就定死了，
运行时没有任何需要重新判断的余地。

原设计让 Java 用「螨、花粉、皮屑、霉、蟑螂、尘、屋尘」子串把模型的 `true` 改成 `false`，
有两处错：一是它在做语义判断；二是**方向不安全**——`isFoodBorne = false` 会让该过敏原
整个退出菜品拦截链路（§7.2），而「霉」是子串，霉豆腐、霉干菜、腐乳都是食物。
报告写「霉菌 / 食物霉变过敏原 阳性」时，这条规则会亲手关掉拦截，与 §0-6 完全相反。

**②b 阳性行覆盖扫描——候选段的发现依据独立于模型，但只覆盖"名称与结果在同一块"的情况。**

前面几层之间有一大片空白：② 只在 `allergens` **整个为空**时才响，②a 的三个集合又都来自模型。
**模型抽出 3 条、而报告上有 5 条阳性**时——数组非空、结构合法、`S`/`D`/`A` 自洽——
每一层都会放行。

**"独立"指的是候选段怎么找出来的，不是整个检查都不碰模型输出。**
这一层分两步，只有第一步独立：

```
① 发现候选阳性段   输入是【解析器产出的全部 segment】，与模型输出完全无关
② 覆盖比较         把候选段与【模型抽出的 A】比对，看有没有漏
```

价值在第一步：候选段是从原文里独立找出来的，**模型漏抽多少都不会让候选段变少**
——这正是 ②a 做不到的（那三个集合全来自模型，模型一致地漏时集合一起变小，比什么都发现不了）。
第二步当然要用模型输出，否则无从谈"漏没漏"。

具体规则：

```
候选阳性行 = { seg | seg.normalizedText 同时命中
                     ① ADMITTED_RESULT_MARKS（阳性/弱阳性/可疑/临界/(+)/＋/± …，见内容常量）
                     ② 任一已知过敏原名称（AllergenGroups 全部 displayName + matchWord） }

每个候选阳性行必须 ∈ A（模型 allergens 条目展开后的 segmentIds 并集）
    命中而不在 A → partial = true, partial_reason = ALLERGEN_SUSPECT_MISS
                 → 模块四不输出
```

**它不做版面推断，所以不越界**（§0-2）：不判断"过敏章节从哪到哪"，
只在每一段内做两类关键词的共现匹配，再做集合包含判断。段的边界是解析器给的，不是推出来的。
它属于 §0-2 唯一允许的那类词表用法——**往安全方向降级**，与 §4.4-② 同源。

---

##### ⚠️ 已知盲区：名称与结果被拆进不同 segment 时，本层命中不了

**这不是边角情况，它取决于 `textSource`，而且在原生文本层是常态：**

```
OCR 路径        识别块 ≈ 一整行
                [「牛奶        阳性(+)」]        ← 名称与结果同块 → 本层【能】命中

PDF 原生文本层   绘制单元 ≈ 一个单元格（§3.2.1）
                [牛奶] [阳性(+)]                 ← 分属两块 → 本层【命中不了】
```

也就是说：**②b 在扫描件/拍照件上基本有效，在电子版 PDF 上基本无效。**

**不修，因为修不起。** 要把 `[牛奶]` 和 `[阳性(+)]` 配成一行，只有三条路：
按 `bbox` 判同一基线、按 `seq` 取相邻块、或做表格行列还原——
**三条全都是版面推断，全都是 §0-2 划给 LLM-A 的职责**（§3.2.1 已论证过 Java 在这一层
做行列推断会在双栏、跨行合并单元格、续页表头上各错一种，而且错得静默）。
为了补一个兜底层而把主职责边界破掉，是亏的。

**因此本层的定位必须写清楚：它是"同块场景的有限兜底"，不是完整的阳性行覆盖检查。**
文档任何地方都不得把它描述成"过敏漏抽已被堵死"。

**这一层之外，过敏漏抽还剩这些缺口，全部列为已知接受：**

| 缺口 | 谁能发现 |
|---|---|
| 名称与结果分属不同 segment（电子版 PDF 常态） | **无人**——只能靠 §11-12 的评测集事后量化 |
| OCR 根本没读出那一行 | 无人；由 `UNREADABLE`（§4.1.1）与密度闸（§3.2.1）从上游拦 |
| 模型一致地漏（数据行与条目一起漏）且名称结果同块 | **本层能发现** |
| 模型自相矛盾（读到了数据行却没抽条目） | §4.4-②a 的 `D \ A` |
| 模型整个没抽过敏原 | §4.4-② |

**误报是设计内的，方向安全。**「乙肝表面抗原 阳性」不会命中——那个词不在 `AllergenGroups` 里；
但「牛奶」这类词出现在别处又恰好同段有「阳性」会误报。误报的后果是模块四不输出，
与 ② 的口径一致，**误报率并入 §11-12 一起量化**。

**⑤ 原文回切（§3.2.3）。**

```
展示原文类字段：segmentId 不存在 → 该条整条丢弃
模型复述短字段：包含性校验不过 → 按 §4.2 末表逐字段处理（档位按 textSource，§3.2.3）
                （指标五字段、textualFindings 两字段 → 整条丢弃；problemName → 降为 null；
                  sectionName → 该组降为「未标注章节」；allergens 两字段 → 见下）

★ 被丢弃的条目属于 allergens 时 → 同 ② 的处理：
   partial = true, partial_reason = ALLERGEN_SUSPECT_MISS，模块四不输出
```

过敏原条目回切失败**不能简单丢弃后继续推荐**——丢掉的可能正是那条要命的。

**⑥ 健康问题准入。** 只有 `includeInHealthProblems = true` 的条目进入模块二。
**准入判定权完全在 LLM-A，Java 不做任何检查**（§0-2）——不覆写，也不扫词表告警
（§4.4-③ 已整体下线）。

原设计对**整段** `normalizedText` 做子串匹配并强制改 `false`，有三处错：

- 健康问题准入本身就是 LLM-A 的职责（§0-2）；
- 扫描范围是整段——「甲状腺结节 3mm，余各项未见异常」命中「未见异常」，**结节被删掉**；
- 词表里带「阴性」，与 §4.3-6、§5.2、§10-21 刚论证完的「阴性 ≠ 正常」直接冲突。
  「乙肝表面抗体 阴性，建议接种疫苗」会被这条规则踢出模块二。

`NormalStatementWords` 现在只存在于离线评测集里（§11-18），生产代码不引用它。
评测集内的词表同样**不含「阴性」**——「阴性 ≠ 正常」这条论证与它跑在哪里无关。

### 4.5 多文件同一性校验

> **这是一条"发现冲突则拒绝"的弱校验，不是"确认同一人"的强校验**（§6.2 评审）。
> 它拦得住"两份报告写着不同名字"，拦不住"同名不同人"，也拦不住"两份都没识别出姓名"。
> 文档不把它称为同一性证明。

```
取所有【通过 §4.2 来源校验后】的 patient.name / patient.gender 非空值比对
姓名：规范化（去空格）后完全相等即通过
> **【繁简转换不做】**（2026-08-26 产品确认）见开发方案 §6.6 同处说明。
性别：同上
任一不一致 → FAILED / IDENTITY_MISMATCH，不自动合并、不做确认弹窗
```

**已知放行的情况（产品已确认接受，§12-8）：**

| 情况 | 结果 |
|---|---|
| 全部文件都识别不出姓名 | 通过 |
| 只有一份识别出姓名 | 通过 |
| 姓名相同、性别一份缺失 | 通过 |
| 同名不同人 | 通过（无法识别） |
| OCR 把姓名读错一个字 | **拒绝**（误拦，用户重传即可） |

产品已确认：**一次只能分析一个人的报告，不支持代家人分析**，需求文档需补充该限制。

### 4.6 多文件合并（§5.9 评审）

**分组键用结构化 ID，不用章节名字符串，也不用批内局部序号：**

```
groupKey     = fileIndex + "-" + 有效 sectionSegmentId   ← 唯一键，跨文件绝不合并
displayName  = 单文件：sectionName
               多文件：「报告{fileIndex+1}-」+ sectionName
groupOrder   = 该组第一次出现时的 (page, batchIndex, sectionIndex)   ← 见下
```

#### 全案排序总则（其余各节一律引用本节，不再各自表述）

```
分组顺序   fileIndex → groupOrder
组内顺序   groupOrder → page → orderInSection   （indicators / textualFindings）
条目顺序   groupOrder → page → sourceOrder      （summaryConclusions / allergens /
                                                  nutritionSupplements / dietRequirements）
groupOrder = 该组第一次出现时的 (group.page, batchIndex, sectionIndex)
```

**三条硬约束：**

```
① 顺序字段一律由 LLM-A 给，Java 只比较和排序（§0-2）
② 批内序号（sectionIndex / orderInSection / sourceOrder）跨批会撞号，
   所以每条排序键都必须先用 page 收敛，再用批内序号
③ 任何排序键都不得使用 segmentId 里的 seq —— 那是解析器产出顺序，不是阅读顺序
```

**`page` 从哪来：模型不给，Java 从 `segmentId` 算。**
契约里没有 `page` 字段（模型不需要也不应该报它），但 `segmentId` 的格式是
`f{fileIndex}-p{page}-s{seq}`（§3.2.1），页码就在里面。一个条目可能跨页引用，所以取最小值：

```
item.page  = min{ page(segmentId) | segmentId ∈ 该条目展开后的 segmentIds }
group.page = min{ item.page       | item ∈ 该组全部条目 }
```

**取 `min` 不是随便定的**：一条跨页续表的指标，它"属于"的是它开始的那一页；
取 `max` 会让跨页条目排到后面去，和相邻的同页条目分开。
这一步是**从字符串里取数字再比大小**，不是版面推断——§4.6 总则第 ③ 条禁止的是拿 `seq` 排序，
`page` 不在此列：**页码是报告的客观属性，`seq` 是解析器的实现细节。**

**`sourceOrder` 的作用域是"章节内、批内"**（§4.2、Schema `$defs.sourceOrder`），
不是全批唯一。所以它**只能在同章节内比较**，跨章节比较会撞号：

```
同一批、同一页、两个章节
   总检结论   第 1 条 → sourceOrder = 0
   专家建议   第 1 条 → sourceOrder = 0     ← 同页同批，两个 0
```

因此凡是用到它的排序键，前面必须先有把章节分开的项：

```
条目顺序   fileIndex → groupOrder → page → sourceOrder      ← groupOrder 已经把章节分开
【错误】   fileIndex → page → sourceOrder                    ← 同页多章节会乱序
```

下面各节的排序键一律按前者写。

**`groupOrder` 不用 `segmentId` 里的 `seq`。** `seq` 是**解析器产出顺序**——
双栏页面上它可能先出完左栏再出右栏，表格页上可能按绘制顺序而非阅读顺序，
拿它当章节先后就是把阅读顺序的判断偷偷交给了解析器（§0-2）。

排序键改为三段，全部来自模型或批次元数据：

```
page          该组第一个条目所在页（页眉给的真实页码，§4.1.5）
batchIndex    同页跨批时用它兜底（批次按页切分，批号与页序同向）
sectionIndex  同批内模型给的章节顺序 ← 阅读顺序的真源
```

组内条目顺序同理用 `orderInSection`，不用 `seq`（§5.3、§6.3）。

**「有效 `sectionSegmentId`」按 `sectionRelation` 四态分别取，Java 不做任何推断：**

| `sectionRelation` | 有效 `sectionSegmentId` | `displayName` |
|---|---|---|
| `CURRENT` | 模型给的那个 | `sectionName` |
| `CONTINUATION` | **继承**同文件内前一批末章节的 `sectionSegmentId` | 被继承章节的 `sectionName` |
| `UNSECTIONED` | `"U-" + 最小 segmentId` | `sectionName`，**须过 §4.2 的来源校验**，不过则「未归入章节的内容」 |
| `UNKNOWN` | `"X-" + 最小 segmentId` | 固定文案「未标注章节」 |

后两态用组内最小 `segmentId` 做键，是为了**不让两处彼此无关的无章节内容并进同一组**
（一份报告的封面和末页声明都是 `UNSECTIONED`，合并展示是错的）。Java 只拼接稳定键。

> **这里用 `segmentId` 不违反总则的第 ③ 条。** 总则禁止的是拿 `seq` 当**顺序**，
> 而这里只是拿它当**唯一键**——键要的是确定性和稳定性，不要求它反映阅读顺序。
> 这两组的展示顺序仍由 `groupOrder` 决定。

`UNKNOWN` 不采信模型给的 `sectionName`，用固定文案；`UNSECTIONED` 可保留模型给出的描述名，
**但该名字必须能在这一组覆盖的原文里找到**（§4.2）——它是要上分组标题的，
不能是模型概括出来的一句话。

`CONTINUATION` 的继承在**同一文件内**按批次顺序进行；文件的第一批就报 `CONTINUATION`
时没有可继承的对象，按 `UNKNOWN` 处理——**不向前跨文件继承**。

`sectionIndex` 是**批内局部序号**（§4.1、§4.2），跨批不唯一，**不能进 `groupKey`**：
一份 3 批的报告里会出现三个 `sectionIndex = 0`。

**跨批的同一章节不会两批返回同一个 ID**——批次不重叠，后一批看不到前一批的标题块。
它靠上表 `CONTINUATION` 那一行的机械继承来合并，没有别的路径（§4.1）。

**去重只发生在同一个文件内**（同一文件跨批的重复条目，按 §4.1.3 的 `segmentId + itemIndex` 去重），
**跨文件一律不去重、不合并**——两份报告都有「血脂检查」时展示为两个独立分组。

`indicators` 合并后分配全局唯一 `indicatorId`，展示顺序不承担主键职责。
`summaryConclusions` 按 `fileIndex → groupOrder → page → sourceOrder` 排序
——`groupOrder` 和 `page` 都不能漏：`sourceOrder` 的作用域是"章节内、批内"，
跨章节和跨批都会撞号（见上文总则）。`itemNo` 可能为 null，不能当排序键。

## 5. 模块一：健康指标（需求 §5）

### 5.1 卡片字段

| 字段 | 来源 | 说明 |
|---|---|---|
| 指标名称 | `indicators[].name` | 报告原文，不改写 |
| 检测值 + 单位 | `value` + `unit` | |
| 参考正常范围 | `refRange` | 报告没写时展示「报告未提供」，**禁止填充通用参考值** |
| 展示结论 | `conclusionText` | `REPORT_TEXT` 路径直接引用报告原文；`REFERENCE_RANGE_IN_RANGE` 固定为「在参考范围内」；`REFERENCE_VALUE_MATCH` 固定为「符合报告参考值」 |
| 结论生成标志 | `conclusionGenerated` | 报告原文为 `false`；上述两种参考值准入文案为 `true`，前端必须做视觉区分 |
| 状态标签 | 见 §5.2 | |

> 「参考正常范围」「指标名称」以及 `REPORT_TEXT` 路径的 `conclusionText` 是**报告原文**，
> 一律由 Java 按 `segmentId` 定位，并做**包含性校验**——档位按该 segment 的
> `textSource` 区分，原生文本层严格子串、OCR 放宽（§3.2.3）。任一必需来源字段校验不过，
> 该指标**整条丢弃**。两条参考值准入文案是 Java 固定内容常量，不冒充报告原文。

### 5.2 状态标签：完全由模型判定

状态四态：`NORMAL` 正常（绿） / `HIGH` 偏高（红↑） / `LOW` 偏低（蓝↓） / `ABNORMAL` 异常（橙）。

> 需求 §5-3 只定义了前三种。第四种（橙）是本方案新增的，用于承载「阳性(+)」「可疑」「临界」
> 这类既不是偏高也不是偏低的结论，**需产品确认**（§12-1）。

**`status` 一律由 LLM-A 判定，Java 不覆盖**（§0-2）。判定的**依据**分两级，但两级都在模型侧：

| 情况 | 模型该怎么判 |
|---|---|
| 报告原文有**方向性**标记 | **照抄词表口径**：↑↓、偏高/偏低、增高/降低、升高/减低、正常、未见异常、异常、H/L |
| 报告只写了**非方向性**结论 | 结合该指标的**临床含义**判断：阳性(+)、**阴性(-)**、弱阳性、可疑、临界 |

> **模型不再回传「走了哪一级」的标记。** 原先的 `statusJudgedByModel` 布尔字段随
> §4.4-③ 的 13 个计数一并下线（2026-08-27），已从 Schema、提示词与 DTO 中删除。
> 两级的区分仍然成立，只是它只影响模型怎么判，不再是输出契约的一部分。

这两级写在提示词里（§4.3-6），不是写在 Java 里。

**Java 在线不做任何 `status` 校验**（§4.4-③，2026-08-25 下线）：

```
模型给的 status  →  直接采用
模型漏给 status  →  按 Schema 必填拦下，【剔除该条指标】（§4.4-①）
```

> **两步走到这里的。** 原设计是「词表命中即以词表为准」——那是 Java 改写医疗语义，违反 §0-2，
> 而且词表本身也拦不住它声称能拦的东西：子串匹配读不懂「较上次升高，仍在正常范围内」里的
> 「升高」不是结论方向；词表里同时有「异常」「未见异常」「正常」，一条「未见异常」同时命中三个词，
> 优先级在子串匹配这层定义不了。先改成了"只告警不覆写"，随后连告警一并下线
> ——**只用于观察的东西不该跑在每一次用户请求里**（§4.4-③）。
>
> 方向词的口径落在**提示词**（§4.3-6）和**评测集**（§11-18），不落在 Java。
> 代价是模型抽风时不再有运行时信号。`REPORT_TEXT` 路径的展示文字仍是原文，
> 用户看到的还是「↑偏高」三个字，只有颜色错；参考值准入路径按下述固定文案展示。

**为什么这一层要交给模型：** 「阳性」不等于异常，**「阴性」也不等于正常**。

```
过敏原-虾蟹类    阳性(+)  →  异常          过敏原-虾蟹类   阴性(-)  →  正常
乙肝表面抗体     阳性(+)  →  正常（有抗体） 乙肝表面抗体    阴性(-)  →  无抗体，报告通常提示接种
甲状腺球蛋白抗体 阳性(+)  →  异常          大便隐血        阴性(-)  →  正常
```

词表无论怎么写都分辨不了这六种，只有理解指标含义才能分。
**原设计把「阴性」无条件放进正常词表，与"阳性不一定异常"是同一个错误的两面**（§16 评审），
现已改为阴性阳性走同一条路径。这与 §8.4 把过敏打标交给模型是同一条理由。

**展示规则：颜色跟判定；文字按结论依据分流。**

```
标签颜色 = status 对应色
conclusionBasis = REPORT_TEXT               → conclusionText 报告原文（如「阳性(+)」），conclusionGenerated=false
conclusionBasis = REFERENCE_RANGE_IN_RANGE  → 「在参考范围内」，conclusionGenerated=true
conclusionBasis = REFERENCE_VALUE_MATCH     → 「符合报告参考值」，conclusionGenerated=true
```

前一条即使模型判反方向，用户看到的仍是报告原文；后两条只陈述与本报告参考值的关系，
不得扩大成「正常」或「未见异常」，且前端必须按生成标志做视觉区分。

**（2026-09-01 修正）本节原先要求「记录 `statusJudgedByModel = true` 的条数、留在线上」，
与 §4.4-③ 的下线清单直接矛盾**——`statusJudgedByModelCount` 正是那 13 个只写不读的计数之一。
以下线清单为准：**计数不存在，模型也不再回传这个字段**，Schema、提示词与 DTO 均已删除。

想知道有多大比例的判定走了临床含义判断路径，按 §4.4-③ 的要求
**先定清楚导出口径再实现**，不要先把字段加回契约。
错误集中在某几个指标名时的处理不变：把该指标的判定口径补进**提示词与评测集**，
走发版——**不是补进 Java 词表**（§0-2、§4.4-③）。

### 5.3 分组展示

按 §4.6 的 `groupKey`（`fileIndex + sectionSegmentId`）分组，**不用 `sectionName` 做键**
——多文件时两份报告都有「血脂检查」，用名字做键会把它们并进同一组；
也**不用 `sectionIndex`**——它是批内局部序号，跨批会撞。

分组标题用 `displayName`，分组顺序按 `fileIndex → groupOrder`（§4.6），
组内按 `page → orderInSection`——**都不使用 `segmentId` 里的 `seq`**，它是解析器产出顺序，
不是阅读顺序。
**全部平铺展示，不折叠。**

### 5.4 总览条（需求 §5-5）

```
总指标数 = 本模块展示的指标条数
正常项数 = status 为 NORMAL 的条数（绿）
异常项数 = status 为 HIGH + LOW + ABNORMAL 的条数
异常占比 = 异常项数 / 总指标数，四舍五入到整数百分比
```

> 这意味着「阳性」这类结论**是否计入异常项，由 LLM-A 的 `status` 决定**。
> 异常占比是用户看到的第一个数字，而它现在带有模型判断的成分——这是 §12-1 要一并确认的。
>
> **后端评审对「报告自带数字优先」提出反对（§12-9）：** 报告的「总项目数」通常包含大量
> 只有数值没有结论、因而不在本模块展示的指标，且 `reportOverview` 一般不提供正常项数。
> 结果是总览条显示 87 项而下方只有 52 张卡片，异常占比与展示内容无法核对。评审建议
> 总览条一律按本模块卡片计算，报告自带数字另设「报告原始汇总」一栏。
> **产品当前决策为不处理该不一致**，此处保留原设计，评审意见记录在案。

**报告自带汇总数字优先**：`reportOverview` 非空时，展示报告的数字，并在旁边标注「（报告原文）」。
两者不一致不做纠错、不告警，以报告为准。

`reportOverview` 为空时用上面的公式计算。

**不加任何差值说明文案。** 总指标数是本模块展示的条数，可能少于报告实际检查项目数
（只列数值不给结论的项按需求 §5-2 不展示，这类项在生化全套、血常规里常占 30~40%）。
产品已确认该不一致不作处理。

### 5.5 底部声明

> 以上指标数据均来自体检报告原文，仅供参考，如有疑问请咨询医生。

### 5.6 空态

无任何符合准入条件的指标时，展示「本次报告未提取到带明确结论的指标项」，
并**保留总览条位置展示 0**，不隐藏模块。

---

## 6. 模块二：健康问题（需求 §6）

### 6.1 三类来源与准入

**只有 `includeInHealthProblems = true` 的条目进入本模块**（§4.4-⑥）。
判定权完全在 LLM-A，**Java 既不覆写也不扫词表告警**（§4.4-③ 已下线）。
这条准入是必须的——`textualFindings` 和 `summaryConclusions` 里都有正常项：

```
肝胆B超：未见明显异常          ← textualFindings，status = NORMAL
心电图：窦性心律，正常心电图    ← textualFindings，status = NORMAL
各项检查未见明显异常           ← summaryConclusions，category = NORMAL_STATEMENT
建议继续保持适量运动           ← summaryConclusions，category = ROUTINE
建议每年参加健康体检           ← summaryConclusions，category = ROUTINE
```

无条件纳入的话，用户会在「健康问题」标题下看到「未见明显异常」，
直接违反需求 §6-1「仅汇总明确给出的异常结论和健康提示」。

| 来源类型 | 数据来源 | 准入条件 | 携带 `indicatorId` |
|---|---|---|---|
| `INDICATOR_NUMERIC` | `indicators` | `includeInHealthProblems = true` | **是** |
| `INDICATOR_TEXTUAL` | `textualFindings` | `includeInHealthProblems = true` | **否** |
| `SUMMARY` | `summaryConclusions` | `includeInHealthProblems = true` 且 `categories` 含 `HEALTH_PROBLEM` 或 `DIET_ADVICE` | **否** |

**`INDICATOR_TEXTUAL` 不带 `indicatorId`（§3.4 评审）：** 「脂肪肝」这类无数值结论
按 §5.1 的准入规则**根本不会生成健康指标卡片**，没有可跳转的目标。
原设计把它归入 `INDICATOR` 大类并统一要求关联跳转，是无法实现的。

前端据此渲染：只有 `INDICATOR_NUMERIC` 显示跳转按钮，另两类不显示
（符合需求 §6-3「若该问题来自总检结论且不直接对应某个指标，则不展示关联按钮」）。

**三类来源的准入条件统一为 `includeInHealthProblems`，由 LLM-A 判定**（§0-2、§4.3-11）。
原设计对 `INDICATOR_NUMERIC` 用 `status != NORMAL` 派生，两处不对：

- 准入是语义判断，不是 `status` 的机械函数。「白细胞 3.9（参考 4.0~10.0）↓」这种
  临界偏离、报告本身未作任何提示的，列进「健康问题」是系统自己加的诊断意味；
  而「血糖 6.0 正常范围，但报告写了『建议控制饮食』」反过来该进。
- 它把准入权转嫁给了 `status`，而 `status` 在原设计里又被 Java 词表覆盖（§5.2）
  ——两条叠起来，模块二收哪些条目实际上由一张子串词表决定。两处都已改回 LLM-A。

### 6.2 条目字段

| 字段 | 生成方式 |
|---|---|
| `displayName` | `INDICATOR_NUMERIC`：`problemName` 非 null 时直接用它（LLM-A 从原文取的自然语言表述，§4.3-12）；为 null 时由 Java 拼 `name + " " + conclusionText`——**两段都是报告原文，Java 只做字符串连接**<br>`INDICATOR_TEXTUAL`：直接用 `title`<br>`SUMMARY`：直接用回切后的原文 |
| `displayNameGenerated` | **布尔值**。`true` = 走了上面的拼接分支（`problemName == null`）（§12-10） |
| `sourceLabel` | 来源标注。单文件「血脂检查–甘油三酯」「总检结论第3条」；多文件加报告前缀「报告2-专家建议第2条」。**章节名取 §4.6 的 `displayName`，不写死「总检结论」** |
| `rawText` | 按 `segmentId` 取该 segment 的**整段原文**（§3.2.3）。segmentId 不存在则该条丢弃 |
| `indicatorId` | 仅 `INDICATOR_NUMERIC` 下发 |

**Java 不再拼「归一化结论词」**（§0-2）。原设计在 `problemName` 缺失时拼「指标名 + 归一化结论词」
（`HIGH` → 「偏高」），那是拿模型的语义分类去生成一句报告里没有的医疗表述：报告写「↑」，
系统写出「甘油三酯偏高」四个字并当成问题名展示。现在两个分支都只出报告原文——
有成句表述就用模型摘出来的那句（必须能在 `segmentIds` 内找到），没有就把指标名和结论原文
拼在一起（「甘油三酯 ↑」），**不翻译、不改写**。

`displayNameGenerated` 是给产品和测试用的：需求 §6-2 要求「直接引用报告原文表述，不做改写」，
而拼接严格说不是引用（虽然两段素材都是原文）。有这个字段，验收时能数出有多少条是拼的，
产品也能决定 UI 上要不要区别对待。

### 6.3 排序（需求 §6-4）

```
INDICATOR_NUMERIC + INDICATOR_TEXTUAL 在前，按 fileIndex → groupOrder → page → orderInSection
SUMMARY 在后，按 fileIndex → groupOrder → page → sourceOrder
不做严重程度分级、不做风险排序
```

`groupOrder` 见 §4.6 的排序总则：`(page, batchIndex, sectionIndex)`，
**不是** `page → 最小 segmentId`（`seq` 是解析器产出顺序，不是阅读顺序）。
`orderInSection` 与 `sourceOrder` 的作用域是"章节内、批内"，所以两条排序键都先用
`groupOrder` 把章节分开、再用 `page` 收敛批次，与 §4.1.2 的合并规则一致（§4.6 排序总则）。

`textualFindings` 的 `orderInSection` 是本轮补上的字段（§4.2）——原契约没有它，
排序规则却在用，会导致同章节内的文字结论顺序不稳定。
`SUMMARY` 用 `sourceOrder` 而非 `itemNo`，因为报告的总检结论**不一定编号**。

### 6.4 空态与声明

三类来源全空时：

> 本次报告未提取到明确的异常结论或健康提示。

**空数组只能说明“本链路没有提取到”，不能证明“报告全部正常”。** 条目可能因为模型漏抽、
原文回切失败或部分批次不可读而缺失；Java不得根据集合为空生成整体健康结论。

底部声明：

> 以上内容均为体检报告原文结论的汇总，不构成二次诊断，如有疑问请咨询医生。

## 7. 模块三：饮食建议（需求 §7）

### 7.1 三条硬约束

1. **不从指标异常推导建议。** 甘油三酯偏高 ≠ 低脂饮食，只有报告明文写了才生成。
2. **不合并同向建议。** 「低脂饮食」与「控制体重」各自成卡片，各自引用各自原文。
3. **不在饮食建议中引用指标数据。** 不出现「因您的甘油三酯 2.8」这类表述。

> 守法方式是结构性的：本模块的输入只有 `enumKey` + `rawText`，**结构上看不到指标数据**，不靠提示词嘱咐。

**这条约束的直接后果（已确认接受）：**

```
一份报告：甘油三酯 3.5↑↑、总胆固醇 6.8↑、脂肪肝
         总检结论只写「血脂偏高，建议复查」

→ 健康指标：正常展示，一片红标签
→ 健康问题：正常展示，三条
→ 饮食建议：三个分区全空态
→ 菜品推荐：「本食堂菜品暂无个性化推荐。」
```

医生写总检结论时并不总会写饮食建议，很多就是「建议复查」「建议专科就诊」。
这类报告的用户会看到后两个模块全空。**这是需求 §7-5 的明确选择，不是缺陷**，
产品已确认按此实现（报告里空的就是空的），不做通用建议兜底。

### 7.2 枚举清单（2026-08-27 证据审核快照）

**食入性过敏原**（参与菜品匹配）

**枚举与词表的唯一真源是 Java 常量类 `AllergenGroups`**
（`com.example.healthreport.constants`），**没有 CSV、没有生成器、没有运行时加载**。
`allergen_display_split.csv` 曾是参考基线，现已删除，任何文档不得再把它写成数据来源。
当前 13 个食入性组共有 126 个词条，其中 123 个 `REVIEWED` 生效、3 个 `REJECTED` 保留为负例；
任何增删都会改变 Layer 1 的拦截行为，
**属安全变更，须走医务评审后改常量类并发版**。

| enumKey | 展示名 | avoid 词数 | hidden 词数 |
|---|---|---|---|
| `SHRIMP_CRAB` | 虾蟹类 | 10 | 6（另 3 条 `REJECTED`） |
| `FISH` | 鱼类 | 3 | 5 |
| `MILK` | 牛奶及乳制品 | 10 | 5 |
| `EGG` | 蛋类及其制品 | 8 | 6 |
| `PEANUT` | 花生 | 2 | 4 |
| `SOY` | 大豆 | 5 | 6 |
| `WHEAT` | 小麦麸质 | 3 | 9 |
| `NUTS` | 坚果 | 9 | 0 |
| `MANGO` | 芒果 | 1 | 0 |
| `BEEF` | 牛肉 | 3 | 0 |
| `MUTTON` | 羊肉 | 3 | 0 |
| `MOLLUSK` | 软体动物及其制品 | 13 | 4 |
| `SESAME` | 芝麻及其制品 | 3 | 5 |

#### 7.2.1 过敏原组扩充裁决

原 11 组与实际食入性筛查及调味料风险不齐。2026-08-27 已完成证据裁决：
`MOLLUSK`、`SESAME` 纳入正式契约；`PINEAPPLE`、`PORK`、`CHICKEN` 本次不纳入。

| 优先级 | 组 | 缺失的后果 |
|---|---|---|
| **已纳入** | `MOLLUSK` 软体动物 | 直接物种名与蚝油/蚝汁进入词表；`XO酱` 只作模型线索 |
| **已纳入** | `SESAME` 芝麻 | 明确芝麻词硬匹配；麻酱、香油、麻油因复配/地区歧义只作模型线索 |
| 本次不纳入 | `PINEAPPLE` 菠萝 | 缺少真实报告命中与可控词表；「菠萝油」还有明确非菠萝语义 |
| 本次不纳入 | `PORK` 猪肉 / `CHICKEN` 鸡肉 | 泛词与调味料会造成不可控误杀，另立证据变更后才能加入 |

> **前两组已从「建议」升级为「必须」**，触发原因是 §8.1.1：食材表不含调味料，
> 蚝油和香油在数据里根本不出现，不收进枚举就等于对这两类过敏原完全不设防。
>
> ---
>
> ### 契约升级已完成
>
> 当前契约数字为：
>
> ```
> 食入性过敏原枚举        13 组
> 过敏原枚举合计          18（13 食入 + 5 非食物）
> LLM-B 打标维度          22（13 过敏 + 9 饮食注意）
> 正式枚举合计            36
> ```
>
> 新增维度现已能承载软体动物和芝麻结果：菜名明确出现蚝油/蚝汁时可落 `MOLLUSK REJECT`；
> 芝麻、芝麻酱、芝麻油可硬匹配。香油、麻油、麻酱及只靠通常做法的场景不得猜成实际配方，
> 统一保留为 `MODEL_ONLY` / `UNKNOWN`。
>
> **补齐的动作是一整套，不能只往常量类里加一行**（`constants/内容常量说明V3.md` §4.2）：
>
> ```
> ① 医务评审通过（审核记录 3、5c）      ← 前置，不可跳过
> ② AllergenGroups 常量类加两组词表
> ③ LLM-A Schema 的 allergenKey 枚举加两个值
> ④ LLM-B Schema 的 enumKey 枚举加两个值
> ⑤ 两个提示词的枚举表同步
> ⑥ 上面四个契约数字全部改：11→13、16→18、20→22、34→36
> ⑦ bump tagRuleVersion → 全量重打标（§8.3.1）
> ```
>
> ②~⑦ 已落地，逐条证据记录见 `constants/内容常量证据审核台账V1.md`。若组织发布制度要求
> 具名医师或注册营养师签字，① 中的具名签字仍须独立完成，AI 证据复核不能代签。
>
> **扩充过敏原组会提高拒绝率。** 为避免把所有凉菜一刀切，只有明确“芝麻、芝麻酱、芝麻油”
> 做 Java 硬匹配；“香油、麻油、麻酱”保持 `MODEL_ONLY`，实际配方不明时 LLM-B 返回 `UNKNOWN`。

**吸入性/接触性过敏原**（只展示，**不参与菜品匹配**）

`DUST_MITE` 尘螨 / `POLLEN` 花粉 / `ANIMAL_DANDER` 动物皮屑 / `MOLD` 霉菌 / `COCKROACH` 蟑螂

> 国内过敏原筛查普遍分吸入组和食入组，吸入组不是食物。

**`isFoodBorne` 是纯内部字段，不影响展示分组。** 全部过敏原**统一放在过敏提醒区，
按报告原文顺序混排**（排序键 `groupOrder → page → sourceOrder`，见 §4.6 排序总则；
`sourceOrder` 由 LLM-A 给，Java 不从 segment 顺序反推），
不按食入性/非食物分组、不额外排序、不加分区标题。该字段只决定两件事：

| | 列食材清单 | 参与菜品匹配 |
|---|---|---|
| `isFoodBorne = true` | ✅ 需避免食材 + 易忽略的加工食品 | ✅ |
| `isFoodBorne = false` | ❌ 卡片上只有过敏原名称和来源标注 | ❌ |

**`OTHER` 必须按 `isFoodBorne` 拆成两条路径，不能混为一谈：**

| | 例子 | 过敏提醒区 | 菜品推荐 |
|---|---|---|---|
| `OTHER` + `isFoodBorne=true` | 芹菜、芥末、亚硫酸盐 | 展示原文 + 来源 | 走 §8.5 名称匹配 |
| `OTHER` + `isFoodBorne=false` | 艾蒿、豚草、真菌 | 展示原文 + 来源 | **完全不进菜品链路** |

> 不拆的后果是误杀：§8.5 会拿过敏原名称去跟菜名和食材做字符串匹配，
> 而不少植物性吸入过敏原的名字本身就是食材（艾蒿↔青团的艾草、桑↔桑葚、豚草↔某些野菜）。
> 报告写「艾蒿花粉 阳性」时会把含艾草的菜判成过敏不推荐——用户对花粉过敏，吃艾草并无问题。

**营养补充**

`IRON` 铁 / `CALCIUM` 钙 / `PROTEIN` 蛋白质 / `VITAMIN_D` 维生素D / `VITAMIN_B12` 维生素B12 /
`FOLATE` 叶酸 / `DIETARY_FIBER` 膳食纤维 / `ZINC` 锌 / `POTASSIUM` 钾

**饮食注意**

`LOW_FAT` 低脂 / `LOW_SODIUM` 低盐 / `LOW_ADDED_SUGAR` 限制添加糖 / `LOW_PURINE` 低嘌呤 /
`LOW_CHOLESTEROL` 低胆固醇 / `LOW_CALORIE` 控制体重 / `HIGH_FIBER` 高纤维 /
`LIMIT_ALCOHOL` 限酒 / `LIGHT_DIET` 清淡饮食

合计 **36 个正式枚举 + `OTHER`**（13 食入性过敏原 + 5 非食物过敏原 + 9 营养补充 + 9 饮食注意）。

### 7.3 高危表述退出结构化链路（Java 安全闸）

模型归一化可能出现方向性错误——把「低蛋白饮食」映射到 `PROTEIN`（蛋白质补充）会直接导致肾病患者被推荐高蛋白菜品。

**准入政策（2026-08-28 重构）分两层：**

```
第一层  模型判定（safety.StructuredAdmission 读取，Java 不推断）
        applicability      CURRENT_PATIENT / OTHER_PERSON / GENERAL_INFORMATION / UNCERTAIN
        structuredSafety   NORMAL / DIRECTIONAL_RESTRICTION / SPECIAL_POPULATION / UNCERTAIN
        【只有 CURRENT_PATIENT + NORMAL 放行】，其余一律抑制

第二层  Java 词表兜底（safety.HighRiskAdviceGate），不可被模型推翻
        低蛋白 / 限蛋白 / 优质低蛋白 / 低钾 / 限钾 / 低磷 / 限磷 / 低碘 / 限碘 / 忌碘 / 高碘
        扫描对象是 adviceQuote —— 模型摘出的【建议本身那一句】，不是整段 rawText
```

命中任一层即给该条打上 `structuredOutputSuppressed = true`，**该条按 §7.4 的 `OTHER` 路径处理**：
只展示报告原文与来源，不生成食材清单、不参与菜品匹配、不进入打标维度。

> **这是 fail-closed，与改造前相反。** 原先是「命中黑名单才抑制」，现在是「只有明确放行才放行」。
> 模型字段没填好时会抑制得**更多**，不是更少——刻意选的安全方向，不是缺陷。

#### 为什么把人群词移出词表

原词表还有「妊娠 / 孕期 / 哺乳期 / 儿童」四个**人群裸词**，2026-08-28 移除。

它们出现在文本里**根本不表示这条建议受限**——可能在说受检者、家属、既往史，也可能是科普。
而 Java 只能做字面包含，分辨不了指向。已实测出事：某报告把

```
[101] …>28为肥胖(孕妇和
[102] 14岁以下儿童除外)。请您戒烟忌酒,低脂、低糖饮食,控制食量,多吃蔬菜、水果
```

BMI 公式免责说明的后半截与饮食建议切进了**同一个 PDF 绘制单元**。按整块扫描时「儿童」命中，
4 条饮食建议 + 1 条营养补充被一起抑制，**菜品推荐模块随之整个清空，全程没有任何异常日志**。

修法是两条同时做：
1. **人群归属交给 LLM-A**（`applicability`）——它有版面和语义上下文，Java 没有；
2. **扫描对象从整块收敛到 `adviceQuote`**——那一句由模型摘出、上限 100 字、必须逐字回切原文。

> **补一条（2026-08-28）：`adviceQuote` 之外，证据段原文也要扫。**
> 只扫摘出的那一句会被**摘句绕过**：回切只要求它是证据原文的子串，
> 原文「建议低蛋白、低脂饮食」摘成「低脂饮食」并标 `NORMAL`，兜底就一次也不触发，
> 「低蛋白」这条不可被模型推翻的红线实际上等于没有。
> 现在两处都扫、任一命中即抑制——词表里剩下的全是语义完整的方向性限制词，
> 扫原文不会再现「儿童」那种人群裸词误杀（该类词已于同日移出词表）。
> 代价是同段里另一条建议带方向性限制时会一起抑制，这是刻意选的方向：宁可少给结构化内容。

> 保留的 11 个词都是**语义完整的方向性限制**：无论建议给谁，「低蛋白」「限钾」都必须由医嘱个体化。
> 它们的字面含义就是限制本身，误报风险比人群名词低一个量级。
>
> **也不要改成「给 patient 加年龄、让 Java 判是不是儿童」**：患者契约里本来就没有年龄字段；
> 就算有，也解决不了「儿童」在描述受检者、家属、病史还是科普这个问题。

**注意它改的是什么。** Java **不覆写 `enumKey`**——`enumKey` 是 LLM-A 的归一化结论，
Java 改了它就是替模型下另一个结论（§0-2）。这里改的是「这条要不要走结构化输出」，
是一个纯粹的**闸门**：命中只会让系统少输出内容，永远不会让它输出一个不同的医疗语义。
模型原本给的 `enumKey` 原样保留在结构里，仅用于排障归因。

这道闸门的合法性来自 **§0-6 的二级红线**（方向性饮食禁忌，偏向"判不准就不输出"），
用法上属于 **§0-2 在生产链路里唯一允许的词表用法——往安全方向降级**。

**生产链路里跑词表的地方现在只剩三处，全部属于这一类：**

```
§4.4-②   高风险内容交叉扫描  → ALLERGEN_SUSPECT_MISS 降级
§7.3      高危表述安全闸      → 该条退出结构化链路
§8.5      过敏关键词兜底      → 与模型结果取并集，只增不减 REJECT
```

**没有第二类。**「只告警计数」那一类已于 2026-08-25 整体下线（§4.4-③），
任何"以词表为准改写模型语义字段"或"为观察而扫词表"的写法都不成立。

### 7.4 OTHER 的处理

`enumKey = OTHER`（或 §7.3 的 `structuredOutputSuppressed = true`）时：

- **照常展示**该条建议的报告原文与来源标注（需求 §7 要求展示报告里写的每一条）
- **不加任何说明文字**（产品决策，§12-3）
- **不生成食材清单、不参与菜品匹配、不进入打标维度**

> **这不满足需求 §7-3（§4.6 评审）。** 需求要求每个过敏原都列避免食材和易忽略食物、
> 每个营养素都列推荐食材/摄入量/搭配建议、每个饮食要求都列推荐食材/限制食材/烹饪建议。
> 「展示了原文」不等于「完成了该条饮食建议」。产品已确认接受该降级（§12-11），
> 需求文档需同步修改。

### 7.5 内容常量

每个正式枚举对应一份硬编码内容常量（`DietAdviceContent` 常量类），上线前由营养师/医务审核一遍，
改动随代码发版。字段按需求 §7-3 定义：

```java
// 过敏提醒（食入性）—— 真源是 Java 常量类 AllergenGroups，随代码发版
class AllergenGroup {
    String key;                   // SHRIMP_CRAB
    String displayName;           // 虾蟹类
    List<Word> avoidIngredients;  // 该词本身即该过敏原或其直接制品：虾、蟹、虾仁、虾米
    List<Word> hiddenFoods;       // 该过敏原是其成分之一但品类名看不出来：虾丸、蟹棒、XO酱
}
// Word { matchWord（用于 Layer 1 匹配，宽松）, displayName（用于展示，已审核）}
// 两条永久不变量，由单测强制：
//   avoidIngredients ∩ hiddenFoods = ∅
//   两者 matchWord 的并集 == 该 key 在 CSV 中的全部 matchWord
// 前者防重复展示，后者防「展示了但不匹配」和「匹配了但不展示」

// 营养补充
class NutritionContent {
    String[] recommendIngredients; // 推荐食材 3~8 种：猪肝、鸭血、菠菜、黑木耳、红枣、瘦牛肉
    String[] intakeNotes;          // 摄入量说明：猪肝每周 1~2 次，每次约 50g
    String[] pairingTips;          // 搭配贴士：铁与维生素C同食可促进吸收
}

// 饮食注意
class DietRequirementContent {
    String[] recommendIngredients; // 推荐食材：鸡胸肉、鱼、豆腐、蔬菜
    String[] avoidIngredients;     // 需避免/限制：油炸食品、肥肉、奶油、动物内脏
    String[] cookingTips;          // 烹饪方式建议：建议蒸、煮、炖，避免煎、炸
}
```

### 7.6 展示

三个分区独立成卡片区，每条建议旁标注来源，引用报告原文：

```
来源：过敏原检查–虾蟹类 阳性(+)
来源：总检结论–建议补充铁剂
来源：总检结论–建议低脂低盐饮食
```

**来源标注完全由字段拼出来，Java 不做任何推断：**

```
来源标注 = 章节展示名 + "–" + 原文
           章节展示名 = sectionIndex → §4.6 的 groupKey/displayName（多文件带「报告N-」前缀）
           「第N条」   = itemNo 非 null 时用它；为 null 时【不写条号】，不拿 sourceOrder 顶替
           原文       = blockRefs 展开后按 segmentId 取整段 rawText（§3.2.3）
排序     = groupOrder → page → sourceOrder    ← 不能用批内 sectionIndex，跨批会撞（§4.6 总则）
           page = min(该条目 segmentIds 的页码)
```

> **这三个字段是本轮补进契约的**（§4.2）。原契约里 `nutritionSupplements` /
> `dietRequirements` 只有 `enumKey` / `itemIndex` / `blockRefs`，**没有任何来源信息**，
> 而本节要求标注「总检结论–…」。那样实现时 Java 只剩三条路，每条都不成立：
>
> ```
> 按 blockRefs 找相邻章节标题   → 版面推断，是 LLM-A 的职责（§0-2）
> 与 summaryConclusions 做文本关联 → 语义关联，同样越界，而且两边不一定一一对应
> 直接写死「总检结论」           → 抽取范围含「专家建议」「健康指导」（§4.3-5），会标错
> ```
>
> 现在三个字段直接来自模型，Java 只查表和排序。
>
> **`itemNo` 为 null 时不许拿 `sourceOrder` 当条号显示**——`sourceOrder` 是我们自己数的批内序号，
> 报告上没印这个数字，显示出来就是编造（同 §6.3 `SUMMARY` 不拿 `itemNo` 当排序键的理由，反过来）。

一条原文拆出多个枚举时（「建议低脂低盐饮食」→ `LOW_FAT` + `LOW_SODIUM`），
两张卡片各自独立展示，**来源三字段完全相同**，引用**同一段原文**，
这不算合并（需求 §7-5 禁止的是合并建议本身）。

**三类来源之间不做交叉关联、不合并**（需求 §7-5）。

### 7.7 空态（需求 §7-4）

| 分区 | 无内容时的文案 |
|---|---|
| 过敏提醒 | 本次报告未提取到明确的过敏原相关内容 |
| 营养补充 | 本次报告未提取到明确的营养补充建议 |
| 饮食注意 | 本次报告未提取到明确的饮食注意要求 |

**全模块不出现任何提示、说明或警示文字**，只有报告原文、来源标注、已收录枚举的食材内容，
以及上表的空态句和 §7.8 的底部声明（产品决策，§12-3）。

> **已知接受的风险：** 报告压根没做过敏原筛查时，页面与"做了筛查但全阴性"完全一样，
> 都显示「本次报告未提取到明确的过敏原相关内容」。系统不会告知用户"推荐结果未考虑过敏因素"，
> 而本版又没有用户自填过敏原的入口——即用户没有任何途径让系统知道他的过敏情况，
> 也不会被提示这一点。此为产品明示决策，兜底仅靠 §7.8 与 §8.11 的模块声明。

### 7.8 底部声明

> 以上建议均基于体检报告原文，不构成医疗或营养处方，具体饮食方案请遵医嘱。

---

## 8. 模块四：食堂菜品推荐（需求 §8）

### 8.1 菜品数据

**菜品与食材数据只允许离线打标任务读取。** 在线推荐不查询菜品表和食材表，
只读取凌晨已经发布完成的 Redis 标签集合。

```
ct_dish             company_id、dishes_id、dish_name、on_shelf、biz_date
ct_dish_ingredient  company_id、dishes_id、ingredient_name、weight_g
```

> 这两张是食堂系统的表，本方案只读、不写、不改结构。
> 实际表名接入时核对；企业与菜品 ID 列分别固定按 `company_id`、`dishes_id` 接入（§12-13）。

用户与菜品都归属于企业。`company_id` 是菜品查询、Redis Key 和在线读取的强制隔离维度；
任务创建时把可信登录上下文中的 `companyId` 固化到任务记录；在线组装只使用该字段，
不得接受请求参数覆盖，也不得跨企业合并集合。

菜品量可能很大，凌晨任务禁止一次性查询全企业菜品：先按企业分页读取菜品主表，
再按当前页的菜品 ID 批量查询食材。不得用菜品与食材一对多 JOIN 后直接分页，
也不得逐菜查询食材形成 N+1。分页细节见 §8.3.2。

#### 8.1.1 `ct_dish_ingredient` 里没有调味料（已确认）

**食材表只记录主料与配料，油、盐、糖、酱油、醋、料酒、蚝油、香油、豆瓣酱、
沙拉酱、XO 酱、鸡精、淀粉这些一概不入表**（菜品数据方确认，2026-08-25）。

这不是"少几行数据"，它**直接拆掉了过敏拦截的一整条路径**：

```
酱油   → 大豆 + 小麦    仅菜名/配方明确出现酱油时成立；红烧、卤等做法词本身不成立
蚝油   → 贝类          蚝油生菜、蚝油牛肉、大量炒青菜
香油   → 芝麻线索       存在地区/复配歧义，只作 MODEL_ONLY
豆瓣酱 → 配方线索       不能只凭菜系或做法推断具体大豆/小麦配方
沙拉酱 → 配方线索       存在无蛋配方，未明确时 UNKNOWN
XO 酱  → 配方线索       配方差异大，虾/干贝维度未明确时 UNKNOWN

裁决依据见 `constants/内容常量说明V3.md` §4.3 与逐条证据台账。技术词、菜系常识和
“通常会放”不足以产生 `REJECT`；缺少明确菜名、食材或配方证据时必须 `UNKNOWN`。
```

**这些过敏原真实存在于菜里，但在我们能读到的数据里完全不存在。**
后果分三条，逐条落在下面各节：

| 影响面 | 后果 | 处理 |
|---|---|---|
| §8.5 Java 关键词兜底 | 食材名里永远匹配不到调味料，**只剩菜名一条通路** | 词表覆盖明确调味料菜名；`MOLLUSK` / `SESAME` 已纳入（§7.2.1） |
| §8.4 LLM-B 过敏打标 | 模型拿到的食材列表同样没有调味料，**不能靠列表判断有无** | 只有明确菜名/配方证据可 `REJECT`；只靠通常做法时输出 `UNKNOWN`（§8.4） |
| §8.8 主料推导 | 「排除调味料」这一步成了空操作 | 删掉 `SEASONING` 常量（§8.8） |

> **食材表为空 ≠ 这道菜没有这个过敏原。** 全案凡是"食材里没有 X 所以判 NEUTRAL"的推理
> 一律不成立——只能推出"主料配料里没有 X"。这条要写进 LLM-B 的提示词，
> 也要写进 Java 兜底的注释里，否则以后一定有人照着"数据里没有"下结论。

无论 `dishes_id` 是企业内唯一还是全系统唯一，Redis Key 和数据库查询都必须包含 `company_id`。
唯一 ID 不能代替租户隔离；同一请求中的用户企业、Redis 企业段和菜品所属企业必须完全一致。

### 8.2 三个维度的判定方式

| 维度 | 枚举数 | 判定方 | 需求依据 |
|---|---|---|---|
| 食入性过敏原 | 13 | **凌晨离线打标，只把 `REJECT` 写入过敏原不推荐集合** | §8-2 主料或配料 |
| ↑ 注 | | 食材表不含调味料（§8.1.1），Java 兜底实际只覆盖明确菜名；缺少配方证据时 LLM-B 必须给 `UNKNOWN`，不得用常识补配方 | |
| 营养补充 | 9 | **凌晨离线确定性打标，只写推荐集合** | §8-2 菜品主料 |
| 饮食注意 | 9 | **凌晨离线对 9 个维度写不推荐集合；仅 `LOW_PURINE`、`HIGH_FIBER` 按主料确证后写推荐集合** | §8-2 仅限能从菜品主料确证的维度 |
| 吸入性过敏原 | 5 | **不参与** | 非食物 |

LLM-B 处理 13 个食入性过敏原和 9 个饮食注意维度，只负责 `REJECT / UNKNOWN / NEUTRAL`
安全判定；2 个饮食正向维度和 9 个营养补充维度由 Java 在同一个凌晨任务中按主料计算。
发布给在线链路的结果统一为 **33 个集合**：

```
13 个 allergen:reject
 2 个 diet:recommend（仅 LOW_PURINE、HIGH_FIBER）
 9 个 diet:reject
 9 个 nutrition:recommend
= 33 个标签集合
```

> **数量口径：Redis 方向集合固定是 33 个。** 文档其他位置出现的“40 道菜”只是
> LLM-B 单次模型调用的菜品批量上限，不是 Redis Key 或集合数量。

### 8.3 离线打标（每日凌晨，xxl-job）

#### 8.3.1 缓存 Key 按维度组织，不按菜

Redis 直接保存用户查询所需的方向集合，不保存逐菜五态对象。正式 Key 全部带
`companyId + bizDate`，同一企业同一天的 Key 使用相同 Redis Cluster hash tag：

```text
dish:recommend:{<companyId>:<bizDate>}:allergen:reject:<enumKey>
dish:recommend:{<companyId>:<bizDate>}:diet:recommend:<enumKey>
dish:recommend:{<companyId>:<bizDate>}:diet:reject:<enumKey>
dish:recommend:{<companyId>:<bizDate>}:nutrition:recommend:<enumKey>
```

其中 `diet:recommend` 只允许 `LOW_PURINE`、`HIGH_FIBER` 两个 `enumKey`；其余 7 个饮食维度
只存在 `diet:reject`，不得创建空的正向占位 Key，也不得因 LLM-B 给出正向判断而扩展集合范围。

每个 Key 都是 Redis `SET`，成员统一编码为：

```text
<dishId>\t<dishName>
```

同一道菜在所有标签集合里必须使用完全相同的成员字符串，集合并、差运算才能以菜品为单位生效。
编码与解码由一个公共 codec 实现；`dishId` 和 `dishName` 写入前都必须拒绝制表符、换行符和空值。
不能只用菜名作为成员，因为同一企业当天可能存在同名菜，Redis 会错误合并。
在线解码遇到畸形成员时只跳过该成员并累计告警数量，不记录成员原文，也不得让模块四异常废掉
已经完成的模块一至三；合法成员仍继续组装，全部成员都无效时模块四返回空态。

**不创建以下 Key：**

```text
dish:recommend:active
dish:recommend:{...}:all
dishId -> dishName Hash
dishId + 标签维度 -> matchedIngredients Hash
```

原因分别是：在线只读取当前 `bizDate`，不需要 active 指针；推荐候选从正向标签并集开始，
不需要全部菜品集合；复合成员已经携带菜品名称；推荐理由直接使用报告原文，不使用命中食材。

`companyId` 必须来自可信登录上下文，并在拼 Key 前由唯一 codec 做 UTF-8 Base64URL 无填充编码，
避免企业标识中的冒号或花括号破坏 Key 分段与 Cluster hash tag。不同企业的集合绝不能参加
同一条 `SUNION` / `SDIFF`。日期 Key TTL 为 3 天只用于清理，在线永远只读取当天 Key，
当天构建失败不得回退读取前一天的数据。

#### 8.3.2 分企业游标分页与内部 `tagHash`

凌晨任务先分页枚举存在当日在架菜品的企业，再在每个企业内按 `dishes_id` 做 Keyset 分页：

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

`pageSize` 由 `dish.tag-query-page-size` 配置，默认 500，属于待压测校准值。第一页传
`lastDishesId = null`；查询实现对首批不拼接 `dishes_id > :lastDishesId` 条件。每个非空批次都必须
在返回页对象中提供 `lastDishesId`，其值严格等于本批最后一条记录的 `dishes_id`；下一批查询把该值
原样作为输入游标。空批次返回空菜品列表和 `lastDishesId = null`，表示分页结束；批次数少于
`pageSize` 时也可直接结束。禁止 OFFSET 分页，避免菜品量大时越翻越慢。`dishes_id` 若不是单调
可比较类型，则使用等价的稳定复合游标，不能退回 OFFSET。

每页分两次查询：第一条只查菜品主表，第二条按本页 `(company_id, dishes_id)` 集合一次批量查询食材。
不能在一对多 JOIN 结果上分页，否则一道菜的多条食材会挤占页容量并造成菜品断页；不能逐菜查询食材。
分页循环只保留当前页对象，标签结果增量写入该企业当天的 staging SET，禁止把全企业菜品一次性留在内存。

每个企业分页前后各执行一次使用同一过滤条件的 `COUNT(*)`，要求开始数量、结束数量和实际处理的
去重菜品数三者相等；不等说明分页期间菜单发生变化，该企业不发布。菜品系统仍应在凌晨构建窗口
冻结当日菜单，双计数只是检测明显漂移，不能把不断变化的数据伪装成同一时刻快照。

企业间完全独立：一个企业分页或打标失败只使该企业当天不发布，不得污染或阻断已经成功构建的其他企业。

`tagHash` 只服务于离线增量判定和 MySQL 历史记录，不进入在线 Redis 成员或查询路径，
并且必须规范化后再计算：

```
tagHash = sha256(
    tagRuleVersion + "|" + promptVersion + "|" + modelVersion + "|" +
    normalize(dishName) + "|" +
    join(",", 食材列表按 normalize(name) 字典序排序后的 "name:weightG")
)
其中：weightG 统一换算为克并四舍五入到 1 位小数，未知编码为 null 而不是 0
      name 走 §3.2.2 的规范化
完整规则见开发方案 §9.5.1
```

不排序、不统一单位、不规范名称的话，**外部查询返回顺序变一下就会触发全量无意义重打标**。

#### 8.3.3 打标任务

```
① 取得 bizDate，分页枚举有在架菜品的 companyId
② 每个企业按 §8.3.2 游标分页查询菜品；当前页菜品 ID 一次批量查询食材
③ 对当前页完成三类标签计算：
   - 13 个过敏原只产出 reject 成员
   - 9 个饮食注意由 LLM-B 产出 reject 安全结论；仅 LOW_PURINE、HIGH_FIBER
     由凌晨 DietPositiveMatcher 按主料产出 recommend 成员
   - 9 个营养补充只产出 recommend 成员
④ 当前页结果增量 SADD 到该企业、该 bizDate、该 buildId 的 33 个 staging SET
⑤ 全部分页完成后校验处理菜品数、每维度覆盖和两个饮食正向维度的 recommend/reject 互斥
⑥ 通过 Lua 在一个原子操作中把该企业 33 个 staging SET 替换成当天正式 SET
⑦ 设置 TTL 3 天并删除该企业构建临时数据；失败时当天正式 SET 保持不存在
```

staging Key 与正式 Key 使用相同的 `{companyId:bizDate}` hash tag，保证 Redis Cluster 下原子替换
不跨 slot。合法空集合在 Redis 中没有实体 Key，发布脚本对它执行 `DEL formalKey`；非空集合执行
`RENAME stagingKey formalKey`。33 个方向集合必须作为一个企业快照整体发布，绝不能逐个覆盖正式 Key。
脚本返回已处理方向数仅作为 Java 发布清单与 Lua 契约的握手，用于发现脚本漂移；它不代表成功写入
的成员数，不应描述成业务发布结果数量校验。

改提示词、词表或模型会让相关 `tagHash` 变化并触发离线重算；分页和模型小批次是两层独立边界，
数据库一页可拆成多个 LLM-B 批次，不得为了模型批量上限把数据库查询退回逐条读取。

#### 8.3.4 在线读取路径

```
① 从任务记录取得创建时由可信登录上下文固化的 companyId，从任务入口取得统一 bizDate
② 按用户正式枚举选择该企业当天的正向集合和排除集合 Key
③ 把全部相关维度的 `SMEMBERS` 命令一次入 pipeline，以一次网络往返取回，禁止按维度串行 RTT
④ Java 对正向集合做并集，对过敏 reject 与饮食 reject 集合做并集
⑤ 推荐菜 = 正向并集 - 排除并集；不推荐菜 = 排除并集，并利用已取回的维度集合恢复标签归属
⑥ 解析复合成员得到 dishId / dishName；畸形成员只跳过并计数，不记录原文、不使整份任务失败
⑦ 按菜名拼音排序后各取前 3 道；推荐菜只恢复正向标签，不推荐菜只恢复 reject 标签
⑧ 推荐理由直接取报告中该标签维度对应的原文；不推荐菜不恢复、不返回任何正向标签或推荐理由
```

在线链路禁止调用 `DishQueryService`、禁止查询 `ct_dish` / `ct_dish_ingredient`、禁止计算
`tagHash`、禁止读取 `ct_dish_tag`、禁止调用 LLM-B。相关 Key 不存在时按当天无可用标签处理，
返回空态并告警，不读取前一天 Key，也不实时重算。

#### 8.3.5 持久化

```sql
CREATE TABLE ct_dish_tag (
  company_id        VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci  NOT NULL COMMENT '菜品所属企业ID，离线打标、查询与Redis发布的租户隔离键',
  dishes_id             BIGINT       NOT NULL COMMENT '食堂菜品ID',
  tag_hash            CHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci     NOT NULL COMMENT '打标输入哈希：规则版本+提示词版本+模型版本+菜名+食材',
  enum_key            VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci  NOT NULL COMMENT '过敏原、饮食注意或营养补充维度枚举键',
  verdict             VARCHAR(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci  NOT NULL COMMENT '离线打标结论：RECOMMEND推荐/REJECT不推荐/UNKNOWN数据不足/NEUTRAL未命中；在线只发布RECOMMEND与REJECT方向集合',
  matched_ingredients VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '命中食材名称的JSON数组字符串',
  reason              VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '模型返回的判定理由',
  model_version       VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci  NOT NULL COMMENT 'LLM-B模型版本',
  prompt_version      VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci  NOT NULL COMMENT 'LLM-B提示词版本',
  tag_rule_version    VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci  NOT NULL COMMENT '内容常量版本，仅排障可读',
  last_seen_date      DATE         NOT NULL COMMENT '最后一次被预热确认为当前有效的业务日，清理只看这一列',
  create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，由数据库维护',
  create_by           VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci  NULL COMMENT '创建人标识',
  update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间，由数据库维护',
  update_by           VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci  NULL COMMENT '更新人标识',
  UNIQUE KEY uk (company_id, dishes_id, tag_hash, enum_key),
  KEY idx_build (company_id, enum_key, dishes_id, tag_hash),
  KEY idx_last_seen (last_seen_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='菜品维度打标结果';
```

（正式 DDL 以开发方案 §3.1 为准；那边每个字符列都逐列写了字符集与排序规则。）

三个 `*_version` 列冗余存储（版本段已经在 `tag_hash` 里），
**仅供离线排障，不参与在线推荐查询**。

**没有单独的 `tagged_at` 列**——打标时间就是行的 `create_time`，
而 `tag_hash` 进了唯一键，同一输入下的行不会被覆写重打。

### 8.4 LLM-B 打标契约

输入：一批菜品（菜名 + 食材列表 + 重量）+ 一个枚举的展示名和内容常量。
**食材列表里没有任何调味料**（§8.1.1），这一点必须在提示词里对模型讲明。
饮食维度只向 LLM-B 提供反向与安全判定所需内容，不下发 `recommendableFoodList`、
`PositiveMatchPolicy` 或其他正向触发规则，避免模型越权产出推荐。
正式契约为 `schema/dish_tag_output.schema.json`。

```jsonc
{
  "enumKey": "LOW_FAT",
  "neutralDishIds": [10001],            // 能确认不含/不违反的，只回 ID
  "unknownDishIds": [10003, 10004],     // 判不出的，只回 ID —— 过敏维度下这类是常态
  "hitList": [                           // 只有 REJECT 携带离线校验证据
    { "dishId": 10002, "verdict": "REJECT",
      "evidenceType": "COOKING", "matchedIngredients": [], "reason": "油炸菜品" }
  ]
}
```

**紧凑格式不缩小覆盖范围**：`neutralDishIds ∪ unknownDishIds ∪ hitList`
必须精确等于本批全部输入 `dishId`，**三者两两不相交**。
**遗漏的菜绝不补成 `NEUTRAL`**——那会把「没判定」伪装成「确认安全」。

**（2026-09-02）覆盖问题由整批作废改为归入 `UNKNOWN`**，与 §4.4-① 的 LLM-A 条目剔除同源：
实测真实菜单会出现重复标签与缺失标签，整批作废下任一批出问题就导致该企业当天 33 个集合
一个都不发布，失败率随批数指数放大（1000 道菜的企业是 25 批）。

```
缺失（模型压根没提）        → 归入 unknownDishIds     等于「没判定」，UNKNOWN 正是这个意思
跨列表相交（判定自相矛盾）   → 归入 unknownDishIds     两个判定打架，取安全侧
同列表内重复（抄重了）       → 【只去重，不改判】       意图明确，去重是忠实修复
多余 dishId（不属于本批）    → 直接丢弃                UNKNOWN 也只能装本批的菜
hitList 某条不合 Schema     → 剔除该条，菜品随之归入 unknownDishIds
违规定位不到某道菜           → 整批作废
```

`UNKNOWN` 是安全侧：这类菜不进任何推荐 staging SET。归入**必打日志并带上 `dishId`**
（LLM-B 是全案唯一允许记录完整请求响应的链路），**归入量超过 20% 即整批作废**
（且至少允许 1 道，避免小批次修一道就作废）。详见开发方案 §8.2。

| 维度 | 允许的 verdict |
|---|---|
| 食入性过敏原 | `REJECT` / `UNKNOWN` / `NEUTRAL` |
| 饮食注意 | `REJECT` / `UNKNOWN` / `NEUTRAL`；正向不由 LLM-B 输出 |

#### 调用方式：直连，且必须先剥离思考段（2026-08-27 定）

模型是 **qwen3-32b-k100**，走与 LLM-A / OCR 同一个网关的 OpenAI 兼容
`/chat/completions`，两条消息：`system` 放提示词正文，`user` 放本批菜品。

**这个模型带「深度思考」，而思考过程是内联在 `content` 里的**，不是单独字段：

```
"content": "<think>\n（思考过程，可能很长）\n</think>\n\n{ 真正的 JSON }"
```

因此 `content` **不能直接当 JSON 解析**。Java 侧必须先按 `</think>` 剥离，
再解析剩余部分。**剥离规则要严格**，理由见开发方案 §13.2.3：
思考段里经常出现示例 JSON，任何"找第一个 `{`"式的宽松提取都会把示例当成结果。

> **打标结果的正确性不依赖"思考能不能关掉"。** 即使后续确认网关支持关闭思考，
> 剥离逻辑也必须无条件保留——一个部署开关不该成为解析正确性的前提。

#### `NEUTRAL` 与 `UNKNOWN` 必须分开（2026-08-25 补）

§8.1.1 说"食材表没有调味料，不能证明这道菜不含某过敏原"，
而**上一版**的例子写着「白灼西兰花 → 无明显调料路径 → `NEUTRAL`」、
提示词里写着"判不出通常放什么就是 `NEUTRAL`"——**那两条与前一句自相矛盾**：
`NEUTRAL` 的语义是"确定不含"，而在没有真实调味料数据的前提下，
模型能给出的最强结论只有"没看出含的理由"，那不是"确定不含"。

> **两处均已修正**（2026-08-25）：§8.1.1 的例子改为 `UNKNOWN`，
> `prompt/dish_tag.md` 的过敏三态、饮食三态定义、证据降级口径与饮食注意示例（凉拌黄瓜）
> 全部改为 `UNKNOWN`。**当前提示词不存在这个冲突**，本段只作变更记录保留。

```
REJECT    菜名、食材或标准产品名称提供明确或高可信成分证据
UNKNOWN   数据不足，判不出 —— 食材表没调味料、菜名也看不出、做法不确定
NEUTRAL   有完整配方或调味料标签，能确认不含
```

LLM-B 输出中没有 `RECOMMEND`。饮食正向由凌晨 Java 逻辑另行计算，而且只允许
`LOW_PURINE`、`HIGH_FIBER` 两个维度；模型路径的变化不能扩大需求规定的正向范围。

**在当前数据条件下，过敏维度的 `NEUTRAL` 几乎不应该出现。** 食堂给的是主料配料表，
不是配方表，所以「白灼西兰花」的正确答案是 `UNKNOWN` 而不是 `NEUTRAL`——
白灼确实没有明显调料路径，但那只是"我没看出来"，不是"厨房没放"。
`NEUTRAL` 留给将来食材表补全调味料、或菜品数据带上配方标签之后。

**该状态只在凌晨构建阶段消费，在线不再逐菜处理五态：**

```
过敏维度 UNKNOWN     → 不写过敏 reject SET，也禁止该菜进入任何正向 SET
饮食注意 UNKNOWN     → 不写该维度 recommend/reject SET，也禁止该菜进入任何正向 SET
批次覆盖不完整        → 问题菜归入 UNKNOWN（§8.2）；修复量超 20% 或定位不到某道菜才整批作废，
                       企业快照完整性不通过时当天 33 个正式 SET 不发布
```

> **为什么不干脆把 UNKNOWN 从严映射成 REJECT。** 那样几乎所有菜都会被拒
> ——过敏维度下 `NEUTRAL` 本来就该罕见，全判 `REJECT` 等于推荐模块永远空着，
> 而且会把大量无辜菜品放进"不推荐列表"，构成错误指控（§8.9 已论证过这一点）。
> 不进入任何在线结果是唯一诚实的选择：**我们不知道，所以不说。**
>
> **代价是推荐列表会明显变短**，与 §8.1.1 的"过敏拒绝率上升"是同一笔账的另一半，
> 一并计入 §11-4a 的量化。

**过敏维度提示词要点：**

1. **判断可见菜名和食材是否为该过敏原提供直接或高可信证据**，包括菜名明确写出的调味料和
   加工食品线索。只有明确/高可信证据可 `REJECT`；仅“可能含有”时必须 `UNKNOWN`。
2. **食材列表里没有调味料，「列表里没有」不等于「这道菜里没有」**（§8.1.1）。
   判断调味料带来的过敏原时，依据是**菜名或实际配方证据**，不能把通常做法当成事实：

   ```
   蚝油生菜    食材：生菜                → 贝类？【是】菜名里就写着蚝油
   麻婆豆腐    食材：豆腐、牛肉末        → 大豆？【是】明确豆腐；不能另推定豆瓣酱配方
   沙拉时蔬    食材：生菜、圣女果        → 鸡蛋？【UNKNOWN】菜名未说明酱料配方
   XO酱炒饭    食材：米饭、鸡蛋、青豆    → 虾/软体动物？【UNKNOWN】XO酱配方不固定
   白灼西兰花  食材：西兰花              → 无明显调料路径 → 【UNKNOWN，不是 NEUTRAL】
   ```

   > **最后一行是 2026-08-25 修正的。** 原先写 `NEUTRAL`，与本节开头
   > 「食材表为空 ≠ 这道菜没有这个过敏原」直接矛盾——没有配方数据时，
   > 模型能给的最强结论是"没看出含的理由"，那是 `UNKNOWN`，不是"确认不含"（§8.4）。

   > **口径已裁决：**菜名明确出现酱油/豉油时可作为大豆/小麦高可信线索；香油只作芝麻
   > `MODEL_ONLY` 线索；红烧、酱爆、卤、凉拌等做法词不得单独推出调味料成分。

3. **模型不得把通常做法补成实际配方。** `UNKNOWN` 在凌晨构建时阻止菜品进入正向集合；
   因此没有必要用未经证实的常识制造 `REJECT`，也不能用信息缺失制造 `NEUTRAL`。
   代价是 REJECT 率会明显上升，见下。

> **这条改动会让过敏拒绝率大幅上升。** 「酱油含小麦」一条就能让小麦过敏用户的
> 中式咸口菜几乎全军覆没。这是**正确**行为（那些菜确实含酱油），但产品需要知道
> 推荐列表会变得很短，甚至空掉——与 §7.2.1 收录「香油」的代价是同一类，只是范围更大。
> 需用真实菜单量化（§11-4a）。

### 8.5 过敏的 Java 关键词兜底

模型漏标是随机的，而最直白的菜漏标后果最严重。过敏维度**额外**跑一层确定性匹配，
与模型结果**取并集**：

```java
// AllergenKeywords 直接由 AllergenGroup 的 avoidIngredients ∪ hiddenFoods 得出
// 与展示同源，不另建一张表
// 匹配范围：菜名 + 全部食材名（不只主料）；无重量阈值，微量即命中
任一来源判 REJECT → 该菜在该过敏维度 REJECT，模型不可推翻
```

**食材表没有调味料之后，这一层实际上只剩「菜名」一条通路**（§8.1.1）。
「蚝油生菜」能拦住是因为**菜名里写着蚝油**，不是因为食材里有；而「红烧肉」里的酱油、
「凉拌黄瓜」里的香油，这一层**一个都拦不到**——§8.4 也不得猜配方，只能给 `UNKNOWN`，
由凌晨完整性门槛阻止该菜进入正向集合。

后果是这一层从"双保险"退化成了"菜名保险"，两条必须跟上：

```
① 词表必须收录调味料在【菜名】里的写法
   蚝油 / 蚝汁 → MOLLUSK       香油 / 麻油 / 麻酱 → SESAME 的 MODEL_ONLY 线索
   酱油 / 豉油 → WHEAT          红烧 / 酱爆 / 卤 → 不进硬词表，配方不明为 UNKNOWN
② MOLLUSK 与 SESAME 已纳入正式契约（§7.2.1）
```

**已知代价：过杀。**「鱼香肉丝」在鱼过敏时会被误标。按 §0-6 的不对称主动接受。
误杀集中在少数几个词时，加一个不超过 20 条的例外词典（常量数组）。

### 8.6 枚举外过敏原（`OTHER`）

`OTHER` 没有稳定的离线标签维度，不进入 33 个 Redis 集合。在线没有食材数据，也不得为了
`OTHER` 临时查询菜品库或做字符串匹配；模块三继续展示报告原文，模块四不据此推荐或排除菜品。
将来若要支持，必须先扩展经审核的正式过敏原维度并随凌晨任务发布，不能恢复在线食材匹配。

### 8.7 营养补充：凌晨任务中的纯 Java 确定性打标

**这个维度不需要模型。** 需求 §8-2 的规则本身就是确定性的：菜品主料包含该营养素的推荐食材。

```java
Set<String> matched = intersect(
    mainIngredients(dish),                               // §8.8
    NUTRITION_CONTENT.get(enumKey).recommendIngredients   // §7.5 内容常量
);
verdict = matched.isEmpty() ? NEUTRAL : RECOMMEND;
```

**原设计的漏洞：** 只校验了模型返回的 `matchedIngredients ⊆ 主料集`，
**没校验它属不属于该营养素的推荐食材**。模型如果认为「大米补铁」，而大米确实是主料，
Java 校验会原样放行——一道白米饭就成了补铁推荐菜。

改成 Java 交集后：结果完全确定、零模型成本、可单测穷举，且符合 §0-3
「确定性规则不交给模型」。**LLM-B 少 9 个维度。**

食材名对不上时（「猪肝」vs「鲜猪肝」）用 `IngredientAliasWords` 常量别名表兜一层，
未命中即按不匹配处理（方向保守，只会少推荐）。

计算发生在凌晨分页任务内，命中结果写入对应 `nutrition:recommend:<enumKey>` staging SET；
在线只读集合，不再读取食材或重新计算。

#### 8.7.1 饮食注意：九个反向维度，两个主料正向维度

LLM-B 必须在凌晨对 9 个饮食注意维度完成 `REJECT`、`UNKNOWN`、`NEUTRAL` 三态安全判定。
发布时只把 `REJECT` 写入 `diet:reject:<enumKey>`；LLM-B 不输出 `RECOMMEND`，`UNKNOWN` 和
`NEUTRAL` 也不建在线集合。

需求 §8-2 明确规定，推荐仅限能从菜品主料确证的维度，第一期只有 `LOW_PURINE`、
`HIGH_FIBER`。原来的 `DietPositiveMatcher` 不再在线运行，而是移动到凌晨分页任务：

```java
Set<String> matchedMainIngredientSet = intersect(
    mainIngredients(dish),
    DietRequirementContents.ALL.get(enumKey).getRecommendableFoodList()
);
boolean recommend = safetyVerdict == TagState.NEUTRAL
    && !matchedMainIngredientSet.isEmpty();
```

这段 Java 逻辑只允许遍历 `LOW_PURINE`、`HIGH_FIBER`。只有 LLM-B 安全结论为 `NEUTRAL` 且主料
命中已审核推荐食材时，才写入 `diet:recommend:<enumKey>`；`REJECT` 或 `UNKNOWN` 即使主料命中
也不得推荐。`LOW_FAT`、`LOW_SODIUM`、`LOW_ADDED_SUGAR`、`LOW_CHOLESTEROL`、
`LOW_CALORIE`、`LIMIT_ALCOHOL`、`LIGHT_DIET` 依赖调味料、用油量、酒精或完整配方证据，
第一期只允许写不推荐集合，绝不创建对应的 `diet:recommend` 集合。

改变执行时点只能消除在线查食材，不能扩大正向业务范围。任何扩展都必须先修改需求、补充可确证
的数据字段与规则，并形成 §12 所要求的可追溯产品决策。报告同时命中饮食与营养的同文案标签时，
展示层按标签文案去重；推荐理由按报告 `rawText` 去重。

### 8.8 主料推导

需求 §8-2 规定营养补充只匹配**主料**，过敏匹配**主料或配料**。菜品数据无主料标记，按重量推导：

```java
Set<String> mainIngredients(Dish d) {
    // 1. 排除无重量数据的食材；全部无重量 → 返回空集（该菜营养维度全 NEUTRAL）
    // 2. total = 剩余食材重量之和
    // 规则一：重量占比 >= 25% 的，无论名次
    // 规则二：重量前 2 名，且占比 >= 15%
    // 两条取并集；都不满足则取最重的一个
}
```

> **没有「排除调味料」这一步，也没有 `SEASONING` 常量**——食材表里本来就没有调味料（§8.1.1）。
> 原设计的第一步是空操作，删掉。
>
> **阈值不用重新校准。** 旧公式的分母本来就是"排除调味料之后的重量和"，
> 而排除是空操作，所以分母没变，`0.25 / 0.15` 的推导前提不受影响（仍需按 §11-2 实测校准）。

| 菜品 | 数据 | 仅 ≥25% | 仅前2名 | 双规则 |
|---|---|---|---|---|
| 青椒肉丝（补蛋白） | 青椒200 肉丝80 → 28.6% | ✅ | ✅ | 主料 ✓ |
| 番茄炒蛋（补蛋白） | 番茄250 鸡蛋100 → 28.5% | ✅ | ✅ | 主料 ✓ |
| 青菜猪肝（补铁） | 青菜180 猪肝5 → 2.7% | ❌ 正确 | ⚠️ 误判 | 非主料 ✓ |

`MAIN_RATIO = 0.25` 与 `TOP_N_MIN_RATIO = 0.15` 是推导值，**上线前抽 50 道真实菜品校准**。
误判率偏高时应推动食堂系统补主料字段，而不是继续调阈值——业务数据永远比推导规则可靠。

### 8.9 标签完整性在发布前保证

在线 Redis 只保存可执行的 `RECOMMEND` / `REJECT` 方向集合，不保存 `NEUTRAL`、`UNKNOWN`
或 `TAG_MISSING` 集合。三者的差异只存在于凌晨构建与排障阶段，不能让在线再次逐菜裁决。

每个 LLM-B 批次仍必须对输入菜品给出完整、互斥的 `REJECT` / `UNKNOWN` / `NEUTRAL` 覆盖；
模型少返回、多返回或集合相交时按 §8.2 修复——问题菜归入 `UNKNOWN`，
修复量超 20% 或违规定位不到某道菜才整批作废。**修复后覆盖必须精确成立**，
这条断言本身没有放松，只是不合规的那几道菜从「整批丢掉」变成「归入 UNKNOWN」。对任一可产生 `REJECT` 的生效维度，菜品为 `UNKNOWN`
时不得写入任何正向推荐集合；只有安全事实为 `NEUTRAL` 时，才允许 Java 对
`LOW_PURINE`、`HIGH_FIBER` 继续执行主料正向匹配。

一个企业的全部菜品分页完成后，发布前必须校验：查询到的菜品数与处理完成数相等、所有 31 个
标签维度都完成、2 个饮食正向维度的 recommend/reject 集合互斥、其余 7 个饮食维度不存在
recommend 集合、复合成员企业归属一致。任一校验失败，该企业当天 33 个正式集合一个都不发布。
当天新增但未进入凌晨分页快照的菜品
不会出现在任何集合中，也不会在线实时补算。

> **（2026-09-01）单企业打标上限 `dish.tag-max-dishes-per-company`（默认 500）。**
> 在架菜品超过该值时，只对游标序前 N 道打标并照常发布，「菜品数与处理完成数相等」
> 这一条改为「处理完成数恰好等于上限」。**未触发上限时该校验一字不变**，
> 仍然能发现外部 count 与 page 两个接口自相矛盾。
>
> 被闸掉的菜品当天**既不进推荐集合、也不进过敏拒绝集合**——它们对模块四不存在，
> 与上面那句「未进入凌晨分页快照的菜品」是同一种缺席。取哪 500 道由 `dishes_id`
> 游标序决定，**不是业务优先级**：菜单超过 500 道的企业，哪些菜进快照是任意的。
>
> **标签保留期同日由 30 天缩短为 7 天。** 在架菜品每天刷新 `last_seen_date` 不受影响；
> 只有连续下架超过 7 天的菜品重新上架时需要重打标。
>
> **取 7 天而不是 3 天，是为了覆盖周菜单轮换。** 菜单轮换周期大于保留期时，
> 轮换菜每轮回来都要重调一次 LLM-B；食堂多为周循环（周一的菜下周一才回来，间隔 7 天），
> 3 天会让夜间调用量显著抬高。7 天既控住 `ct_dish_tag` 表体积，又不打断周轮换的复用。
> **注意与 Redis 方向集合的 TTL 3 天无关**，那是另一件事（§8.3）。

### 8.10 合并裁决

设当前用户报告生效维度对应的 Redis 集合为：

```text
P = SUNION(饮食 recommend 集合, 营养 recommend 集合)
R = SUNION(过敏原 reject 集合, 饮食 reject 集合)

notRecommended = R
recommended    = P - R
```

`P - R` 可由同 slot Lua 一次完成，或批量取得两个集合后在 Java 中做 `removeAll`；禁止为每道菜、
每个标签发独立 Redis 请求。冲突时不推荐天然优先：进入 `R` 的菜必须从 `P` 剔除，且返回时
不得携带任何正向标签或推荐理由。该规则对 `allergen:reject` 与 `diet:reject` 完全相同，
不是只在过敏冲突时生效。

正向 Key 列表为空时，Java 直接把 `P` 设为空集合，不调用零参数 `SUNION`；排除 Key 列表为空时
同理把 `R` 设为空集合。不能为了凑 Redis 命令引入 `all` 或占位 Key。

如果用户只有过敏原，没有饮食或营养正向要求，则 `P` 为空：推荐列表为空，`R` 中命中过敏原的
菜进入不推荐列表；没有命中过敏、也没有正向标签的普通菜不进入任何列表。正因为候选从 `P`
开始，系统不需要 `all` 集合。

### 8.11 标签与推荐理由

| tagType | 来源 | 示例 | 建议色 |
|---|---|---|---|
| `NUTRITION` | 营养补充 RECOMMEND | 补铁、高蛋白、补钙 | 绿 |
| `DIET_OK` | 饮食注意主料确证 `RECOMMEND`（§8.7.1） | 低嘌呤、高纤维 | 蓝 |
| `ALLERGY` | 过敏命中 | 虾蟹过敏、芹菜过敏 | 红 |
| `DIET_AVOID` | 饮食注意 REJECT | 高脂、高盐、高糖 | 橙 |

**推荐理由直接返回报告原文，不拼菜品食材：**

```
标签：补铁
推荐理由：建议补铁
```

标签文案由枚举常量转换，推荐理由由命中的标签维度关联回当前报告的 `rawText`；一道菜命中
多条正向标签时返回全部标签及对应原文，完全相同的标签和原文分别去重。凌晨缓存不保存
`matchedIngredients`，也不保存用户报告原文。这个边界保证公开菜品缓存不混入健康数据。

推荐菜只返回 `dishName + recommendTags + recommendReasons`；不推荐菜只返回
`dishName + notRecommendTags`。不得扩大为价格、图片、分类或完整菜品信息。

### 8.12 排序、空态与降级

两个列表都按**菜名拼音首字母排序**（TinyPinyin，请求时实时计算，不落库），
非汉字开头的菜名统一排在汉字之后，**排序后各取前 3 道**。

```
recommendList 与 rejectList 最大长度均为 3
截断必须在全部维度合并裁决之后 —— 先取 3 再判过敏，会把该拦的菜留下、该推的菜丢掉
不足 3 道按实际返回，不用占位菜补满
```

| 情况 | 处理 |
|---|---|
| 饮食建议中无任何正式枚举内容 | 「本食堂菜品暂无个性化推荐。」 |
| 有饮食建议但两个列表都为空 | 「本次未匹配到符合建议的食堂菜品，菜品以食堂实际上架为准。」 |
| `suppressDishRecommend = true`（§4.1.1） | **整个模块不输出** |

### 8.13 底部声明

> 推荐菜品基于体检报告内容及食堂菜品数据自动匹配，菜品信息以食堂实际上架为准。

---

## 9. 存储与接口

### 9.1 持久化清单

| 存储 | 内容 | 生命周期 |
|---|---|---|
| MySQL `ct_health_report_task` | 任务状态真源、userId、companyId、attempt、心跳、deadline、partial、deleted_at（DDL §2.3.2） | `expire_at` 到期物理删除 |
| MySQL `ct_health_report_file` | fileId、userId、companyId、S3 定位、taskId、fileIndex、文件元数据、status、expire_at | 按 §2.7 清理矩阵 |
| MySQL `ct_dish_tag` | 按企业保存的离线标签历史与版本元数据；不参与在线推荐读取（DDL §8.3.5） | 按 `last_seen_date < bizDate-7d` 清理陈旧行（2026-09-01 由 30d 缩短，按周菜单轮换定档，见 §8.9 下方说明） |
| MySQL `ct_dish` / `ct_dish_ingredient` | 带 `company_id` 的食堂菜品与食材；仅凌晨任务分页只读 | 外部维护 |
| S3 私有 Bucket | 原始文件 | 按 §2.7 清理矩阵 |
| Redis `result:{taskId}` | 四模块结果 JSON | TTL 2h |
| Redis `dish:recommend:{companyId:bizDate}:...` | 每企业每天 33 个方向标签 SET；Member=`dishId\tdishName`（§8.3.1） | TTL 3d，在线只读当天 |

**没有任务队列，没有 `task:{taskId}` 状态 Hash，没有 Redis 墓碑，也没有 outbox 表。**
任务状态与删除标志都在 MySQL——状态 CAS 要与文件绑定同事务，Redis 做不到；
`deleted_at` 也不能随 TTL 消失（§2.6）。执行由 §2.3.3 的**事务提交后**提交本机线程池完成。

**Redis 在本方案里只剩两个用途**：四模块结果（TTL 2h）和按企业隔离的当日菜品标签集合（TTL 3d）。
菜品标签集合丢失时当天模块返回空态并告警，不允许在线回源重算；**没有任何调度状态依赖 Redis**——
Redis 整个挂掉时，正在跑的任务仍能跑完，只是写结果那一步失败、任务判 `FAILED`。

**MySQL 不存任何健康数据**，`ct_health_report_task` 不含。

> **一个例外必须显式承认：`ct_health_report_file.origin_name`。**
> 它原样保存上传文件名，而真实文件名常常是「张三-2026年度体检报告.pdf」
> ——姓名与体检属性都在里面。本版把它定性为**敏感元数据**：
> 只用于前端回显、**禁止进入普通应用日志**、禁止传给任何外部系统、随 file 行一起删除；
> 排障期仅可进入默认关闭的独立敏感 DEBUG logger。
> 是否改成只存安全生成的展示名，列入 §12 待产品确认。
但"不进 MySQL"不等于"都进 Redis"——三类数据的去向不同：

| 数据 | 去向 | 存活期 |
|---|---|---|
| **姓名 / 性别** | **只在工作线程内存**，用于 §4.5 同一性比对，比完即弃 | 任务执行期内，**从不写 Redis** |
| **完整 OCR 文本 / 全部 segment 的 `rawText`** | **只在工作线程内存** | 同上，**从不写 Redis** |
| **四模块要展示的原文片段**（健康问题 `rawText`、饮食建议来源原文、指标五字段…） | 随四模块结果写 Redis `result:{taskId}` | TTL 2 小时 |

> **本表 2026-08-25 修正。** 原文写「姓名、报告原文、OCR 文本…只在 Redis 结果里存 2 小时」，
> 与 §2.7 的「姓名 / 性别仅在 Worker 内存，**不写 Redis**、不入日志、不返回前端」直接冲突。
> 以 §2.7 为准：**姓名和完整 OCR 文本一个字都不进 Redis**。
>
> 进 Redis 的只有**四模块实际要展示的那些片段**——它们本来就要下发给前端，
> 存进结果缓存不增加任何暴露面；而姓名和全文 OCR 是**前端根本不需要**的东西，
> 让它们在 Redis 里躺两小时是纯粹的多余风险（§11.1 的核查项「运维人员能否直接读取 Redis
> 中的报告原文」正是冲着这个来的）。

**`ct_health_report_file` 行必须整行删除，不是只删 S3 对象。**
原文件名、文件大小、`cloud_file_key`、内容 hash 都是可定位报告的信息，
需求 §3-10 的口径是「关闭后清除所有数据」，留着元数据不符合该口径。

#### 9.1.1 建表约定（项目统一规范）

本方案新建的三张表全部遵守，接入时不得例外。

**① 表名一律 `ct_` 前缀。**

```
ct_health_report_task    ct_health_report_file    ct_dish_tag
```

**② 四个审计列每张表都有，列名和类型固定。**

```sql
create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，由数据库维护',
create_by    VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '创建人标识',
update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间，由数据库维护',
update_by    VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '更新人标识'
```

> **文档里的 DDL 示例也不能例外**（`AGENTS.md` §4）：每个字符列逐列写
> `CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci`，UUID、hash、枚举名这些只存 ASCII 的列也一样。

**③ `create_time` 与 `update_time` 由数据库维护，代码永远不赋值。**

**实体上必须显式声明，光靠"不写"不够**（`AGENTS.md` §4）：

```java
@TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
private LocalDateTime createTime;

@TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
private LocalDateTime updateTime;
```

该字段永不进入 insert / update 语句，select 不受影响。
**禁止配置 `MetaObjectHandler` 自动填充这两列，禁止在任何 SQL 或 `UpdateWrapper` 里写
`update_time = now()`。**

插入时不写这两列，更新时也不写——`DEFAULT CURRENT_TIMESTAMP` 负责前者，
`ON UPDATE CURRENT_TIMESTAMP` 负责后者。

```java
// 错误
task.setCreateTime(new Date());
task.setUpdateTime(new Date());

// 正确：这两个字段在 insert/update 语句里根本不出现
```

> 这条对本方案有一个具体后果：§2.3.4 的 CAS、§2.5 的状态更新、§2.6 的删除标志，
> 这些 `UPDATE` 语句都**不要**带 `update_time = now()`。
> MyBatis-Plus 若配置了 `@TableField(fill = FieldFill.INSERT_UPDATE)` 自动填充，
> 需对这两列关闭，否则会覆盖数据库默认值、绕开约定。

**④ `create_by` / `update_by` 写固定系统标识，绝不写入用户标识**（`AGENTS.md` §4）。

| 表 | 取值 |
|---|---|
| `ct_health_report_task` | `HEALTH_REPORT_API`（在线创建）/ `HEALTH_REPORT_WORKER`（工作线程写回） |
| `ct_health_report_file` | `HEALTH_REPORT_API` |
| `ct_dish_tag` | `DISH_TAG_JOB`（离线预热任务） |

> **本节 2026-08-25 修正。** 原先写「取当前 `userId`」，直接违反工程规范
> 「`create_by` / `update_by` 写固定系统标识，绝不写入用户标识」。
> 这不只是规范问题——`user_id` 列已经承担归属校验（§2.2），
> 再把 userId 冗余进审计列，等于同一份身份信息多一处副本、多一处泄漏面，
> 而它对排障没有任何新增价值：**通过 `task_id` 就能查到 `user_id`。**

**⑤ 字符集与排序规则（MySQL 8.0）。**

| 列类型 | 字符集 / 排序规则 | 理由 |
|---|---|---|
| 建表默认 | **`utf8mb4` / `utf8mb4_general_ci`**，必须显式写在每条 `CREATE TABLE` 上 | 报告文本含部首区字符（§3.2.2）与生僻 CJK，`utf8mb3` 不够用；显式写出是为了不随部署环境或 MySQL 版本默认漂移（MySQL 8 自己的默认是 `utf8mb4_0900_ai_ci`，不是同一个） |
| 每一个字符列（含哈希、ID、枚举列） | 逐列 `CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci`，**无例外** | 统一到一套字符集与排序规则，不再有 `ascii_bin` 特例。代价是 `_ci` 大小写不敏感、唯一键会把大小写变体当同一行，因此**大小写唯一性改由代码层保证**：ID 统一由 `IdCanonicalizer` 生成小写规范形式，`user_id` 的归属校验在 Java 侧再做一次精确 `equals`（开发方案 §3.1.3） |

**⑤-2 表上不写任何 `CONSTRAINT`。** 没有外键、没有 `CHECK`、没有触发器；
`verdict` 与 `evidence_type` 的一致性等业务规则全部由代码层在写入前保证
（执行点见开发方案 §3.1.2）。表定义里只保留 `PRIMARY KEY` / `UNIQUE KEY` / `KEY`
三种索引声明——唯一键是 `insertIgnore` 幂等的基础设施，不算业务约束。

`ct_dish` / `ct_dish_ingredient` 是食堂系统的表，本方案只读，其结构以对方为准。

**⑥ 每个字段和每张表都必须有中文 `COMMENT`。**

- 字段使用 MySQL 字段级 `COMMENT '中文业务含义'`；
- 枚举/状态列在中文说明后列出允许值或指向定义；
- 表使用 `COMMENT='中文表用途'`；
- 行尾 `--` 备注、Java 注释、字段名自说明和另外的数据字典均不能代替字段 `COMMENT`；
- 本约束同时适用于新建表、`ALTER TABLE ADD COLUMN`、迁移脚本和文档中的 DDL 示例。

### 9.2 接口清单

| 接口 | 说明 |
|---|---|
| `POST /api/health-report/file` | 单文件上传，返回 fileId |
| `POST /api/health-report/analyze` | 提交有序 fileIds，创建并返回 taskId。**「重新解析」复用本接口**（§2.5） |
| `GET  /api/health-report/task/{taskId}` | 轮询状态与进度，失败时返回 `failCode` 与 `reanalyzable` |
| `GET  /api/health-report/result/{taskId}` | 取四模块结果，含 `partial` / `suppressDietAdvice` / `suppressDishRecommend` |
| `DELETE /api/health-report/task/{taskId}` | 用户关闭页面时清除（§2.6） |

**除上传接口外，全部接口必须校验 `taskId` 同时归属当前 `userId`、`companyId`，且
`deleted_at IS NULL`。**
taskId 难猜是混淆，不是鉴权。**上传接口虽无 taskId，但创建任务时必须校验文件归属**（§2.2）。

### 9.3 轮询

前端递增间隔轮询：`2s → 3s → 5s → 5s...`，上限 5 分钟。
Worker deadline 10 分钟，**客户端超时不写终态**，服务端独立判定（§2.3.4）。
该值以 §4.1.4 的并发执行为前提；批次若串行，30 页报告的耗时上界会超过它。

---

## 10. 实施优先级

### P0 — 安全

0. **三层职责边界（§0-2）**：生产链路里 Java 的词表**只允许「往安全方向降级或拦截」一种用法**，
   **不得改写 `status` / `isFoodBorne` / `includeInHealthProblems` / `enumKey`**，
   **也不得为"只告警"而扫词表**（§4.4-③ 已整体下线）。
   单测须断言：生产代码中不存在 `ConclusionLabelWords` / `NormalStatementWords` /
   `AllergenSectionWords` 的任何引用（ArchUnit 或包扫描即可）。
1. LLM-A 输出 Schema 强制必填 + **高风险内容交叉扫描与安全降级**（§4.4-①②）
2. **过敏原 `resultStatus` 准入过滤，阴性绝不进入**（§4.4-④）
3. **过敏原回切失败 → `ALLERGEN_SUSPECT_MISS` 降级，不得静默丢弃**（§4.4-⑤）
4. **批次 `UNREADABLE` → `BATCH_UNREADABLE` 降级，模块三四不输出**（§4.1.1）
5. **精确累计 31~60 页 → `PAGE_TRUNCATED` 降级，模块三四不输出；精确累计 >60 页或
   单个 Word 超独立上限 → `PAGE_LIMIT_EXCEEDED`，且不调用 LLM-A**（§3.3）
6. **标签完整性在按企业发布前校验**；构建不完整时该企业当天 33 个正式集合均不发布（§8.9）
7. 过敏原按 `isFoodBorne` 拆两条路径；**正式枚举的 `isFoodBorne` 由 `enumKey` 查表得出
   （模型返回值直接丢弃）；`OTHER` 采信 LLM-A，Java 不校验、不改写、不告警**（§4.4-③④、§7.2）
8. 高危表述**退出结构化链路**的 Java 安全闸，**不覆写 `enumKey`**（§7.3）
9. 过敏 Java 关键词兜底 + 与模型取并集（§8.5）
10. 枚举外过敏原名称匹配，仅对 `isFoodBorne = true` 生效（§8.6）
11. 过敏拒绝优先于一切推荐判定（§8.10）
12. 多文件同一性校验，不一致直接失败（§4.5）
13. 创建任务的文件所有权校验 + 原子绑定（§2.2）
14. **`deleted_at` 标志 + Worker 写回前带条件**（§2.6）
15. **`DietAdviceContent` 全量内容、过敏关键词族、高危黑名单、饮食注意规则的营养师/医务审核**
    ——**未审核通过，模块三与模块四不得上线**。审核需留档审核人与内容版本号。
15a. **`MOLLUSK` / `SESAME` 两组的工程契约补齐已完成**（§7.2.1）：
    常量类、两个 Schema、两个提示词、四个契约数字（13、18、22、36）及
    `tagRuleVersion=tag-1.0.0` 已同步。部署时仍须全量重打；如组织要求具名执业签字，签字未完成
    仍是模块三、模块四的上线阻断项
15b. **LLM-B 离线状态不进入在线逐菜裁决**：过敏原允许三态，饮食注意允许增加
    `RECOMMEND`；`UNKNOWN` 不得进入任何正向集合，Redis 只保存最终方向集合（§8.4、§8.9）

### P0 — 正确性

16. **Segment 机制 + `rawText`/`normalizedText` 分离 + 按 `textSource` 分档的包含性校验**（§3.2）
17. `batchStatus` 三态，`NO_REPORT_FEATURE` 与 `UNREADABLE` 分开（§4.1.1）
18. 健康问题准入：**三类来源统一用 LLM-A 的 `includeInHealthProblems`**，
    `INDICATOR_NUMERIC` 不再由 `status != NORMAL` 派生；**Java 既不覆写也不扫词表**（§4.4-③⑥、§6.1、§4.3-11）
18a. **章节归属由 LLM-A 给出**，`sectionIndex` 为批内局部序号，跨批对齐用 `sectionSegmentId`；
    **批次边界由 `sectionRelation` 四态显式表态，Java 只在 `CONTINUATION` 时继承**
    （§4.1、§4.2、§4.6、§5.3、§6.3）
18d. **解析器只输出原子文本块 + `bbox`，不识别表格、不聚类行列**；单元格与行的归组由
    LLM-A 用 `segmentIds` 表达（§3.2.1、§4.3-10a）
18e. **来源约束覆盖全部模型复述的原文串**，包括患者姓名/性别、总览数字、指标五字段，
    以及 `UNSECTIONED` 分组的 `sectionName`（它会上分组标题）；
    无法回切的字段按 §4.2 逐字段降级，不得参与同一性判断（§4.2、§3.2.3、§4.4-⑤）
18f. **PDF 绘制单元密度闸**：某文件 `segment/页 > 400` 判为逐字形绘制，
    **该文件整体改走 OCR**，不得把字形块喂给模型；解析用 `PDFStreamEngine.showTextString`,
    **不用 `PDFTextStripper`**（它内部按行聚类，等于把版面判断做回 Java）（§3.2.1）
18g. **`blockRefs` 上限分两档**：`indicators` 为 32（一行指标跨 5~12 块），
     `textualFindings` / `summaryConclusions` 为 128（引用整段文字）。
     **原先三者共用 32**，2026-09-01 用真实报告实测时两个模型在 `summaryConclusions`
     同一处独立撞限，确认 32 拦的是正常输出而不是乱引，据此拆开（`$defs/paragraphBlockRefs`）。
     `orderInSection` / `sourceOrder` 仍必须进正式契约
    ——§6.3、§4.6 的排序规则依赖这两个字段，Schema 缺它们等于排序无据（§4.2、§6.3）
18h. **批内块号编址**：模型侧只见 `blockRef`（整数）+ 每页页眉，Java 在 Schema 校验后
    立刻查表展开成 `segmentId`，下游只认展开后的值（§4.1.5、§4.4-①a）。
    页眉必须给**真实页码**，否则 `sectionRelation` 的 `CONTINUATION` 判断失去依据
18i. **OCR 路径受 400 块/页 约束，超限整任务 `FAILED / UNREADABLE`**，
    不做局部截断、不新增 `partial_reason` 枚举（§4.1.5）。
    原「每批输入预算 ≤ 60k token」已于 2026-09-02 删除——实测 8 页带图 89k~101k，
    该数字既不准也从未被执行
18k. **模块三来源三字段进契约**：`nutritionSupplements` / `dietRequirements` 必须带
    `sectionIndex` / `sourceOrder` / `itemNo`，否则来源标注只能靠推断或写死（§4.2、§7.6）
18l. **全案排序统一到 §4.6 的排序总则**：分组 `fileIndex → groupOrder`，
    组内 `page → orderInSection`，条目 `page → sourceOrder`；
    **任何排序键都不得用 `segmentId` 的 `seq`**，批内序号必须先用 `page` 收敛
    （§4.6、§5.3、§6.3、§7.6）
18p. **`allergens` 必须带 `sourceOrder`**——§7.2 要求过敏提醒区按报告原文顺序混排，
    没有它 Java 只能回去依赖 segment 顺序（§4.2、§7.2）
18q. **`sectionIndex` 的引用完整性由 Java 校验**：`sections[i].sectionIndex == i` 且唯一；
    条目的 `sectionIndex` 必须命中该集合，否则整条丢弃（§4.4-①b）
18m. **跨批同章节靠 `CONTINUATION` 继承合并**，不靠"两批返回同一个 ID"——
    批次不重叠，后一批看不到前一批的标题块（§4.1、§4.6）
18r. **②b 阳性行覆盖扫描**：以解析器 segment 为输入，「过敏原名 + 阳性标记」**同块**共现的段
    必须被 `allergens` 覆盖，否则 `ALLERGEN_SUSPECT_MISS`。**候选段的发现依据独立于模型**
    （②a 的三个集合同源，
    拦不住"一致地漏"），但**只是同块场景的有限兜底**——电子版 PDF 上名称与结果分属不同绘制单元，
    本层命中不了，**不得描述成"过敏漏抽已堵死"**（§4.4-②b）
18s. **不得为配对名称与结果而做相邻块 / `bbox` 同行 / 表格还原**——那是版面推断，
    属 LLM-A 职责（§0-2、§3.2.1）。宁可保留盲区，也不破边界
18n. **`allergenSectionBlockRefs` / `allergenDataBlockRefs` 的集合规则**：
    `D ⊆ S`、`A ⊆ S` 为结构断言；`D \\ A` 非空 = 漏抽 → `ALLERGEN_SUSPECT_MISS`（§4.4-②a）
18o. **`bbox` 逐块随文本下发**，不能只给页面图——解析器不聚类行列，
    模型判断"同一行"要靠坐标（§4.1.5、§3.2.1）
18j. **`页/批`、`8 批上限`、`W = floor(C/4)` 是一组参数，不得单独调**（§4.1.5）
18b. **`status` 由模型决定，Java 在线不做任何校验**；确定性方向词落在提示词与评测集，
    不落在 Java（§4.4-③、§5.2）
18c. **`displayName` 两个分支都只出报告原文**，Java 不拼「归一化结论词」（§4.3-12、§6.2）
19. `INDICATOR_TEXTUAL` 不带 `indicatorId`（§6.1）
20. 准入三分法（§4.3-2）
21. **阴性与阳性同走模型判断路径，不进正常词表**（§4.3-6）；
    该口径同样适用于离线评测集里的 `NormalStatementWords`——**不含「阴性」**（§11-18）
21a. **三张语义词表不进生产**（`ConclusionLabelWords` / `NormalStatementWords` /
    `AllergenSectionWords`）：不实现 `statusConflictCount` / `normalAdmitSuspectCount` /
    `foodBorneConflictCount`，全部移入离线评测（§4.4-③、§11-18）。
    **§4.4-② 的高风险交叉扫描不在此列**——它触发安全降级，不是计数器
22. **营养维度由凌晨任务做 Java 确定性交集打标并发布推荐集合**，在线不再读食材（§8.7）
23. 主料双规则推导（§8.8）；**没有 `SEASONING` 常量**——食材表本来就不含调味料（§8.1.1）
23a. **食材表不含调味料**：LLM-B 只按明确菜名/配方证据判 `REJECT`，通常做法不明时判 `UNKNOWN`；
    Java 兜底词表覆盖明确调味料在**菜名**里的写法；`MOLLUSK` / `SESAME` 已纳入正式契约
    （§8.1.1、§8.4、§8.5、§7.2.1）
23b. **禁止「食材表里没有 X 所以判 NEUTRAL」的推理**——它只能推出"主料配料里没有 X"（§8.1.1）
24. **Redis 按 `companyId + bizDate + 标签方向` 组织 33 个 SET**，成员统一为
    `dishId\tdishName`；不建 `active`、`all`、名称 Hash 或命中食材 Hash（§8.3.1）
24a. **菜品数据库查询按企业做 `dishes_id` Keyset 分页**；当前页批量查食材，禁止 OFFSET、
    一对多 JOIN 分页和逐菜 N+1 查询（§8.3.2）
25. **`tagHash` 排序 + 单位统一 + 名称规范化**（§8.3.2）
26. **去重只认 `segmentId + itemIndex`，非重叠页面不去重**（§4.1.3）
27. 多文件合并用 `groupKey`，健康指标按 `groupKey` 分组（§4.6、§5.3）
28. **一条原文可拆多个枚举条目，共享同一 segment**（§4.2、§4.3-4）
29. `summaryConclusions.categories` 数组 + `sourceOrder` 排序（§4.2、§6.3）
30. `textualFindings.orderInSection`（§4.2、§6.3）

### P0 — 工程

31. **状态机纯单向无回边，删除用 `deleted_at` 正交标志**（§2.3.1）
32. **`ct_health_report_task` DDL，状态 CAS 落 MySQL**（§2.3.2）
33. **`submit` 到线程池在创建事务提交【之后】；被拒则事务外把任务 CAS 为 `FAILED/SERVER_ERROR`**（§2.3.3）
33a. **没有消息队列**：删 `XADD`/`XACK`/`XDEL`、`q:analysis`、Consumer Group、
    创建前的队列深度校验、投递失败与孤儿消息处理。**Redis 只剩结果缓存与打标缓存**（§2.3.3、§9.1）
33b. **线程池必须有界 + `AbortPolicy`**：不得用无界队列（用户排到文件都过期了）、
    不得用 `CallerRunsPolicy`（分析会跑在 Tomcat 请求线程上，分钟级占死）、
    不得静默丢弃（任务永远停在 `QUEUED`）（§2.3.3）
33c. **`W = floor(C / (4 × 实例数))`**——本机线程池下每个实例独立跑满自己的 `W`，
    漏掉实例数会成倍超用模型配额（§4.1.4）
33d. **重启即失败、不自动恢复**：靠 §2.3.4 的心跳巡检在 15 分钟内收敛。
    单实例可在启动时把非终态任务一次判失败以加速；**多实例绝对不可以**（§2.3.3）
33e. **任务池与批次池必须分开**（`analysisExecutor` / `llmBatchExecutor`）——
    共用一个池必然线程饥饿死锁，而且心跳正常、巡检扫不出来（§2.3.3、§4.1.4）
33f. **巡检两条件并列**：心跳超时 → `SERVER_ERROR`；`now() > deadline_at` → `EXECUTION_TIMEOUT`。
    心跳只更新 `heartbeat_at`，**绝不顺延 `deadline_at`**，否则第二条永不命中（§2.3.4）
33g. **成功写入顺序固定**：写 Redis（不可见）→ MySQL CAS → CAS 失败则删 Redis；
    结果接口必须先查 MySQL 为 `SUCCEEDED` 再读 Redis（§2.6.1）
33h. **成功时把 `expire_at` 顺延为 2 小时**与结果 TTL 对齐，否则第 30 分钟后
    任务行被删、归属校验没有依据，结果的后 90 分钟读不到（§2.6.2）
33i. **成功 CAS 必须带 `AND deadline_at >= now()`**——否则超时后、巡检跑到前的窗口里
    任务照样能成功，`deadline_at` 从来拦不住任何东西（§2.6.1）
33j. **`idx_deadline (status, deadline_at)`**——巡检的第二个条件需要它，
    只有 `idx_sweep (status, heartbeat_at)` 时那条 UPDATE 会全表扫（§2.3.2）
33k. **审计时间列实体上写 `@TableField(insertStrategy/updateStrategy = NEVER)`**，
    禁止 `MetaObjectHandler` 自动填充，禁止 SQL 里写 `update_time = now()`（§9.1.1）
34. **全案零重试**：无执行重试、无投递重投（§2.3.3、§2.5、§4.1、§4.4）
34a. **`FILE_ALREADY_BOUND` 返回已绑定的 taskId**，兜住响应丢包（§2.2）
34b. **LLM-A 批次并发执行**（串行跑不完 deadline）+ **只持有编码字节不持有 `BufferedImage`** + **心跳独立线程**（§4.1.4）
35. **「重新解析」= 同批 fileIds 重调 analyze，可从可重解析的失败任务解绑**（§2.2、§2.5）
36. **清理矩阵按状态逐类判定，原文件在 `SUCCEEDED` 后才删**（§2.7）
37. **在线推荐只读当前用户企业当天的 Redis 标签集合**；未命中返回空态并告警，禁止查菜品库、
    回源标签表、读取前一天或实时打标（§8.3.4）
38. 逐格式判定 + 解压炸弹防御，**流式计数不信 `getSize()`**（§3.1、§3.1.1）
39. **Word 独立分块规则 + 内嵌图片 OCR + 上传下界预筛/Worker 精确容量裁决**（§3.3.1）
40. **正式 JSON Schema 文件（LLM-A / LLM-B）+ 契约测试**（§4.2、§8.4）

### P0 — 需求符合性

41. 四个模块底部声明 + 全部空态文案；空态只能表达「未提取到明确内容」，
    不得推导「各项正常」「未涉及某风险」等医学结论（§6.4、§7.7）
42. 总览条数字（§5.4）
43. 来源标注取报告原文章节名（§6.2）
44. 推荐理由直接返回对应标签维度的报告原文；不拼命中食材（§8.11）
45. 拼音首字母排序（§8.12）
46. 三阶段进度条（§2.4）

### P1

47. 打标计数与召回率告警
48. 部首映射表持续补齐
49. 食材别名表扩充

### P2

51. 过敏误杀例外词典（≤20 条）
52. 菜品拼音人工修正
53. OFD 兼容性扩展

### 明确不做

- 用户自填过敏原
- 由指标异常推导饮食建议（需求 §7-5 禁止）
- 「体检报告不完整」的失败判定（§2.5、§12-2）
- 严重程度分级 / 风险排序（需求 §6-4 禁止）
- 历史报告回看（结果 TTL 2 小时）
- 原图跳转与单页预览
- **OCR bounding box 落库**（原为 P1-50，2026-08-25 移到此处）
- 内容管理后台 / 建议内容的人工编辑
- 报告未写饮食建议时的通用建议兜底（§7.1）
- 菜品标签的 `active` 指针与 `all` 全集 Key；按企业当天 staging SET 原子替换正式 SET（§8.3）

> **为什么「bbox 落库」不是"暂缓"而是"不做"。** 它与 §2.7 直接冲突：
> 渲染图与 OCR 中间产物**仅存在于内存，不落盘、不入 S3**，bbox 就是 OCR 中间产物。
>
> 而且落了也没用——原图高亮需要三样东西：
>
> ```
> 原图  → §2.7 清理矩阵：SUCCEEDED 后即删
> 原文  → 只在 Redis 存 2 小时
> 坐标  → 就算落库了，指向的另外两样都已经不存在
> ```
>
> **要让它有用就得连坐**：建 segment 表把 `rawText` 一起落库、并延长原始文件留存期
> ——那是把报告原文永久落库，撞 §2.7 的日志红线和 §11.1 的上线阻断项。
> 所以这件事的前提不是"排期"，是**先做一轮隐私评估并重定数据留存策略**；
> 在那之前它不该以任何形式出现在需求列表里。

## 11. 上线前必须验证的假设

本文档所有阈值均为推导值，未经真实数据验证。

| # | 假设 | 验证方式 | 不成立的后果 |
|---|---|---|---|
| 1 | 文本层判据（50字符/页、30%非空白） | 20 份不同机构 PDF 抽样 | 电子版误走 OCR，或扫描件误当电子版 |
| 2 | 主料双规则阈值 0.25 / 0.15 | 50 道真实菜品人工标注对比 | 营养推荐名不副实，或该推的推不出来。**注意分母不含调味料，但那是因为食材表本来就没有（§8.1.1），不是因为代码排除了它们** |
| 3 | 36 个枚举的覆盖率 | 20 份真实报告统计 `OTHER` 占比 | 模块三大面积只展示原文 |
| 4 | 过敏原枚举覆盖真实筛查面板 | 收集 5 家机构的过敏原检查项目清单 | §8.6 的兜底分支频繁触发 |
| 4a | **调味料缺失对过敏召回的影响** | 抽 50 道真实菜品，人工标注"实际含哪些过敏原（含调味料带入的）"，与 LLM-B + Java 兜底的结果比对，分别统计**漏标率**和**过杀率** | 食材表不含调味料（§8.1.1），过敏拦截从"数据 + 模型"退化成"菜名 + 模型常识"。漏标率高 → 安全红线被削弱，必须回头推动食材表补调味料；过杀率高 → 推荐列表空掉，产品要重新权衡（§8.4、§8.5、§12-10a） |
| 5 | OFD 解析可行性 | 3~5 家真实样本跑通 | 该格式降级或砍掉 |
| 6 | LLM-B 打标稳定性 | 同一批菜连跑 5 次比对 verdict 一致率 | 打标结果每天跳变 |
| 7 | **Segment 切分的稳定性与粒度** | 20 份报告统计：一个 segment 平均含几个指标、**一个指标平均跨几个 segment**（原子块粒度下这是常态，§3.2.1） | 包含性校验失效（粒度太粗则形同虚设），或 `blockRefs` 超过 32 条上限 |
| 7b | **`MAX_SEGMENTS_PER_PAGE = 400` 这个密度闸阈值** | 20 份不同机构 PDF 统计每页 segment 数分布，看「按单元格发绘制操作」与「逐字形」两簇分不分得开 | 定高了，逐字形 PDF 漏进模型侧撑爆输入；定低了，正常 PDF 被误降级成 OCR，精度白丢（§3.2.1） |
| 7a | **原子块粒度下模型的 `segmentIds` 引用准确率** | 20 份报告人工标注表格行，统计漏引、跨栏错引的比例 | 解析器不再切单元格（§3.2.1），单元格归组全靠模型。漏引一个块 → 包含性校验不过 → 指标被丢弃 |
| 8 | **Word 等效页折算系数（40 segment/页）** | 5 份医院导出 Word 报告，比对实际渲染页数 | 容量限制与分批策略失准 |
| 9 | **Word 内嵌图片形态占比** | 同上样本统计"扫描件贴进 Word"的比例 | 若占多数，Word 路径实质上是 OCR 路径，成本与耗时需重估 |
| 10 | 抽取召回率评测集 | 20~30 份真实报告人工标注过敏原、饮食医嘱、异常结论，统计 LLM-A 漏抽率 | 无法判断疑似漏抽告警的阈值，也无法证明模型没在静默漏抽 |
| 11 | 30 页以上报告的实际占比 | 样本统计 | 降级路径触发频率未知，可能远超预期 |
| 12 | **`ALLERGEN_SUSPECT_MISS` 的误报率与漏报率** | 用评测集跑，**样本必须按 `textSource` 分层**（电子版 PDF 与扫描件各占一半）：① 误报——多少正常报告被误降级；② 漏报——人工标注每份报告的阳性过敏原条数，与 `allergens` 实际抽出的条数比对；③ **②b 在两类样本上各自的命中率** | 误报过高则模块四大面积不输出。漏报是新增观测项：②a 三个集合同源拦不住"一致地漏"，而 ②b **只在名称与结果同块时有效**（§4.4-②b 的已知盲区）——电子版 PDF 上它基本不工作，这个差异必须用分层样本量出来，否则会误以为兜底层普遍有效 |
| 13 | **LLM-A 调用的瞬时失败率**（超时 / 429 / 5xx） | 压测 + 灰度期观测 | 全案不做执行重试，瞬时抖动直接变成用户可见失败。失败率高于 2% 就要重新讨论是否放开批次级重试 |
| 14 | **线程池 `QUEUE_CAPACITY` 与拒绝率** | 灰度期观测 `analyze` 接口因 `AbortPolicy` 返回 `SERVER_ERROR` 的占比，以及任务从 `QUEUED` 到 `PARSING` 的等待时长分布 | 容量定小了，正常流量就被拒；定大了，用户排到文件 `expire_at`（30 分钟）都到了，排到也没文件可读（§2.2、§2.3.3）。这两个方向都直接反映成用户可见失败 |
| 14a | **重启导致的任务失败率与收敛时长** | 灰度期统计发版/重启期间被心跳巡检判 `FAILED` 的任务数，以及用户从提交到看见失败的实际等待 | 本次简单版接受"重启即失败"，但 15 分钟的干等是否可接受需要真实数据。发版频繁时可能要缩短巡检间隔，或改成发版前先停止接单再等在途任务跑完（§2.3.3、§2.3.4） |
| 15 | **模型服务的并发配额 `C`** | 向服务方确认，并压测验证 | §4.1.4 的 `W = floor(C/4)` 定不下来。全案零重试，一个 429 就是一次用户可见失败，`W` 设大了会直接反映成失败率 |
| 16 | **OCR 单页耗时与 LLM-A 单批耗时** | 用真实样本实测 | §4.1.4 的 deadline 测算基于 2~3s/页 与 60~180s/批，都是推演值。实际更慢的话 10 分钟 deadline 要重新定 |
| 16a | **`sectionRelation` 四态的判准率**，尤其 `CONTINUATION` 与 `UNSECTIONED` 的区分 | 20 份报告人工标注批次边界，统计封面/须知页被误判为 `CONTINUATION` 的比例 | 误判会把封面文字挂到上一个检查章节下。偏高只是不归组（安全），误判成 `CONTINUATION` 才是错误归属 |
| 20 | ~~每批输入 token 实测~~ **已完成（2026-09-02）** | `ExtractionQualityAndTokenBudgetIT` | 实测 8 页带图 **89k~101k**、每页图像 ≈2,730 token、每页文本 ≈14,577 token（bbox 前缀占大头，原估算没算它）。估算值 49k 偏低约 2 倍，**`≤60k` 硬约束已删除**（§4.1.5）。真正的天花板在输出侧：decode ≈106 token/s，180 秒读超时对应约 19k 输出 token |
| 21 | **模型回填块号的准确率**（是否会写成 `"17"` / `"[17]"` / 越界 / 漏填） | 契约测试 + 评测集统计 `blockRef` 展开失败率 | 编址从字符串 id 换成整数块号（§4.1.5），省 24% 输入，但引入一类新错误：块号错了就等于引用了不存在的块，条目被丢弃。失败率高于预期就要回退到全 id 编址 |
| 19 | **PDFBox 能否稳定拿到 `Tj` / `TJ` 绘制单元** | 覆写 `PDFStreamEngine.showTextString` / `showTextStrings` 在 20 份真实 PDF 上试跑，统计每页 segment 数与切分形态 | 拿不到就只能退回 `PDFTextStripper`，而它内部按行聚类——等于把版面判断偷偷做回 Java（§0-2），此路不通。届时只剩「全部 PDF 走 OCR」一条路，需重估成本与精度（§3.2.1） |
| 17 | **LLM-A 给 `sectionSegmentId` 的准确率**（含跨页续表、双栏、批次边界继承） | 20 份真实报告人工标注章节边界，比对模型输出 | 章节归属已收归模型（§0-2、§4.1），准确率不够则模块一分组和模块二排序错乱。**注意：这不是"改回 Java 推导"的理由**——Java 推导在同样的版面上错得更多且静默，正确的应对是改提示词或换模型 |
| 18 | **离线评测集必须覆盖三处已下线的在线检查** | 在 §11-10 的评测集里补三组用例并给出通过阈值：① `status` 与报告方向标记一致（「↑偏高」不得判 `NORMAL`）；② `OTHER` 过敏原的 `isFoodBorne` 判定；③ 正常语句不得进模块二（「甲状腺结节，余未见异常」的结节要留、「未见明显异常」要滤掉） | 三处 Java 覆写先改成只告警、再整体下线（§4.4-③），**生产环境已没有任何模型漂移的实时信号**。评测集是唯一的替代，因此**发版前跑评测集从"建议"变成"必须"**：换模型、改提示词、报告形态变化都要跑。不跑就上线 = 模型静默变差无人知晓 |

### 11.1 敏感数据链路的技术核查

体检报告、过敏原和医生建议是敏感健康信息。§2.7 声称"原文件在任务成功后删除"，
但本方案只管得了自己这一侧。上线前需逐项核查并留档：

```
□ OCR 服务是否第三方？请求图片留存多久？
□ 【LLM-B】模型服务端是否留存请求与响应？能否关闭？
   —— LLM-B 的输入只有菜名、食材、枚举展示名，【不含任何健康数据】，风险等级低
   —— 改直连后不再有 Dify 运行记录这一处留存，但模型网关本身仍在上面那条链路里
□ 【LLM-A】直连的模型服务端是否留存请求？留存多久？能否关闭？
   —— 这条取代了原来的 Dify 检查项。LLM-A 的请求里有报告页面图与 OCR 全文，
      是全案最敏感的一次出网，**必须逐项落实到服务方的书面口径**
□ 模型网关 / APM / 异常追踪（Sentry 等）是否记录请求体？
□ 传输与对象存储是否加密？
□ 临时文件、崩溃转储（heap dump）是否含报告内容？
   —— 分析与 Web 层共用一个堆（§2.3.3），OOM 时的自动 heap dump 会把内存里的
      报告原文、姓名、OCR 文本、segment 与 bbox 一起写进磁盘。
      这是本方案唯一会让报告原文落盘的路径，**必须显式关闭或加密隔离**
□ 第三方服务的数据留存周期与删除机制？
□ 是否存在跨境传输？
□ 运维人员能否直接读取 Redis 中的报告原文？
```

后端评审建议将此列为上线阻断项而非普通假设验证。

---

## 12. 待产品确认

| # | 事项 |
|---|---|
| 1 | **「异常」（橙）状态标签 + 状态由模型判定**——需求 §5-3 只定义三种。判定「阳性/阴性是否属于异常」需要理解指标的医学含义，超出需求 §5-1「不额外推断、不二次分析」的边界，且直接影响总览条异常占比（§5.2、§5.4） |
| 2 | **请从需求 §3-9 去掉「体检报告不完整（缺页等）」**——模型无法判断用户少传了什么。评审建议改为系统可判定的场景：PDF/OFD 声明页数与实际可读页数不一致、Word 结构损坏、图片无法解码。V1 均不实现（§2.5） |
| 3 | **全案不出任何提示、说明、警示文字**——四个模块只展示内容，兜底全靠需求规定的四条底部声明。已确认接受的连带后果：<br>① 报告未做过敏原筛查时用户不会被告知，且本版无自填入口（§7.7）<br>② `OTHER` 建议（含低蛋白、限碘等高危项）不会说明其未纳入菜品推荐（§7.4）<br>③ 枚举外过敏原的隐藏成分风险不作说明（§8.6）<br>④ 吸入性过敏原卡片只有名称和来源，不解释为何无食材清单（§7.2） |
| 4 | **累计 60MB 上限**——需求只规定单文件限制和最多 5 个文件，按需求理论上限是 100MB。60MB 指**上传体积**。需回写需求并给出固定错误提示（§2.2） |
| 5 | **三种降级的用户可见形态**——`PAGE_TRUNCATED` / `BATCH_UNREADABLE` 两类不出模块三四，`ALLERGEN_SUSPECT_MISS` 不出模块四。用户会看到"页面少了两块"，需确认是静默隐藏还是给文案；但 §12-3 已定"不出任何提示文字"，两者需一并裁决（§4.1.1） |
| 6 | **饮食要求来源 = 总检结论 + 医生建议章节**——比需求 §7-2「仅总检结论」略宽，排除各科小结、检查须知、科普段落、检查前准备。需回写需求（§4.3-5） |
| 7 | **已确认：弱阳性 / 可疑 / 临界过敏结果作为产品安全信号进入菜品过滤，但不得展示或表述为临床确诊阳性。** 这是 §0-6 的保守策略；后续如有病史、复测或食物激发结论，应由医疗流程作个体化判断（§4.4-④） |
| 8 | **一次只能分析一个人的报告，不支持代家人分析**——需求未规定，属新增限制，需回写需求。已知该校验是"发现冲突则拒绝"的弱校验，拦不住同名不同人和双方都识别不出姓名的情况（§4.5） |
| 9 | **总览条用报告自带数字还是本模块计算值**——后端评审建议一律用本模块计算值，理由是报告的总项目数含大量不展示的指标，会导致数字与卡片对不上。产品当前决策为不处理该不一致（§5.4） |
| 10 | **健康问题名称允许系统拼接两段报告原文**——报告没有成句表述（`problemName = null`）时，拼「指标名 + 结论原文」，如「甘油三酯 ↑」。**不再拼「甘油三酯偏高」这类归一化措辞**——那是系统造出报告里没有的表述（§0-2，本轮已改）。需求 §6-2 要求直接引用原文不做改写，拼接严格说仍不是引用（虽然两段素材都是原文），已加 `displayNameGenerated` 字段供验收统计（§6.2） |
| 10a | **已确认调味料证据边界**——明确菜名/配方出现酱油、豉油时可产生大豆/小麦拒绝；“红烧、酱爆、卤”等做法词不入硬词表，只靠通常做法时为 `UNKNOWN`。产品需监控 `UNKNOWN` 导致的推荐收缩，但不得以提高推荐量为由把未知降成 `NEUTRAL`（§8.4、§8.5） |
| 11 | **`OTHER` 建议只展示原文，不给食材内容**——不满足需求 §7-3 对每条建议的字段要求。需回写需求（§7.4） |
| 12 | **凡进入 `rejectSet` 的菜都不下发任何正向标签或推荐理由。** `rejectSet` 同时包含过敏原 `REJECT` 与饮食注意 `REJECT`；菜品只进入不推荐列表并展示全部命中的不推荐标签。该规则比需求 §8-3「展示所有命中的标签，冲突时不推荐优先」更窄：需求文字仍可能被理解为“归入不推荐列表但继续展示已命中的正向标签”，而本方案明确全部抑制。该完整偏离范围需作为正式产品决策回写需求（§8.10） |
| 13 | **食堂数据源接入项。** `company_id`、`dishes_id` 已确定；仍需在接入时确认菜品表与食材表的实际表名、`on_shelf` / `biz_date` / 食材字段映射、按企业游标分页与当前批食材查询能力，以及等价索引是否存在。逻辑表名 `ct_dish` / `ct_dish_ingredient` 不能未经核对直接当作物理表名（§8.1、§8.3.2） |
| 14 | **预热窗口后新上架的菜当天不出现在推荐列表**——§8.9 完整性门槛的必然结果。若食堂当天临时加菜频繁，需评估影响面 |
| 15 | **已按需求锁定：饮食注意正向推荐第一期仅 `LOW_PURINE`、`HIGH_FIBER`。** LLM-B 从在线改为凌晨离线，只改变执行时点，不产生新的调味料、用油量、酒精或完整配方证据；其余 7 个饮食维度只做不推荐。若以后扩展，必须先修改需求 §8-2、补充可确证的数据字段与规则并形成评审记录（§8.7.1） |

> **所有"产品已确认"但与原需求不同的决策，都必须同步修改《体检报告分析需求.md》或形成
> 可追溯的评审记录。** 否则需求文档和设计方案会同时成为有效依据，开发、测试和验收无法确定最终口径。
