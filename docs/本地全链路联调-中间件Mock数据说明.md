# 本地全链路联调 —— 中间件 Mock 数据说明

> 建立日期：2026-08-27
> 用途：在本机没有 MySQL / Redis / S3 / 上游认证 / 食堂菜品库的情况下，
> 用真实体检报告 PDF 跑通「上传 → 解析 → OCR → LLM-A 抽取 → 组装 → 结果查询」全链路。
> 本文件是 Mock 数据的**唯一真源**：代码里的 Mock 实现必须与本文件逐条一致，改一边就要改另一边。

---

## 1. 范围：什么被 Mock，什么必须真连

链路上一共有 9 个外部边界：6 个中间件与只读查询边界，3 条模型链路。

**中间件一律替换；模型链路做成真连与契约桩两套。** 只有真连能回答「这份报告抽得准不准」，
但在拿到网关参数之前，真连一行代码都跑不了——而 LLM-A 之后的 Schema 校验、来源回切、
合并去重、安全扫描与四模块组装是整个工程里最厚的一段。契约桩就是为了先把这一段跑起来。

> **桩验的是契约，不是质量。** 它证明「模型按约定格式回话时下游走得通」，
> 不证明「模型抽得准」。两者不能互相替代，所以是两个测试类，不是一个开关。

| # | 边界 | 生产实现 | 本次处理 |
|---|---|---|---|
| M1 | 当前用户认证 | `CurrentUserProvider` | **Mock**：固定 userId |
| M2 | 对象存储 | `S3FileStorage` | **Mock**：本机文件目录 |
| M3 | 食堂只读菜品库 | `DishQueryService` | **Mock**：固定 18 道在架菜品 |
| M4 | MySQL 8 | `spring.datasource` | **替身**：H2 内存库 `MODE=MySQL`，建表脚本仍用 `sql/schema.sql` |
| M5 | Redis | `StringRedisTemplate` | **替身**：嵌入式 redis-server 进程 |
| M6 | xxl-job 调度 | `@XxlJob("dishTagJob")` | **手工触发**，不接调度中心 |
| S1 | PaddleOCR-VL | `PaddleOcrClient` | **两套**：真连 / 契约桩 |
| S2 | LLM-A 抽取 | `ExtractionModelClient` | **两套**：真连 / 契约桩 |
| S3 | LLM-B 打标 | `DishTagModelClient` | **两套**：真连 / 契约桩 |

三条模型链路各有两套装配，由**用哪个测试类**决定，不是开关：

| 测试类 | 模型链路 | 用途 |
|---|---|---|
| `RealReportLocalChainRunTest` | **真连**（需 9 个环境变量） | 验抽取质量 |
| `StubbedFullChainRunTest` | **契约桩** | 验链路走得通 |

### 1.1 与 `AGENTS.md` §5 的关系

`AGENTS.md` §5 规定 `S3FileStorage` / `CurrentUserProvider` / `DishQueryService`
三个占位符「绝不写假数据返回」，理由是**假数据会让上层测试通过而掩盖未实现**。

**本方案让这条约束自动成立，靠的不是纪律而是物理隔离**：

1. **`src/main` 一行不改。** 全部 Mock 与联调入口只存在于 `src/test`，
   生产构建的产物里根本没有这些类。现有的 `UnsupportedS3FileStorage` /
   `UnsupportedCurrentUserProvider` / `UnsupportedDishQueryService` 原样保留、
   照样抛 `UnsupportedOperationException`。
2. **占位 Bean 用 `@Primary` 覆盖，不用 `@Profile`。** 三个占位 Bean 仍在容器里，
   只是按类型注入时不再被选中（见 `LocalChainMockConfiguration`）。
   因此不需要新增任何 profile，也就不存在「配错 profile 就跑假数据」这条路径。
3. **默认构建永远跑不到。** 联调用例打 `@Tag("local-chain")`，
   而 surefire 的 `excludedGroups` 已把它排除，与仓库既有的
   `pre-release-only` / `external-ocr` 是同一套机制，没有发明第三套规矩。

同理，§5 末条「不生成任何中间件或数据源配置类、不写 `application.yml` 的中间件段」
针对的是**生产配置**。本方案没有新增任何生产配置文件：数据源与 Redis 参数
写在联调用例自己的 `@SpringBootTest(properties = …)` 与 `@DynamicPropertySource` 里。

---

## 2. 怎么跑

```bash
# JDK 必须是 8（仓库红线），本机用 Corretto 8
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)

# 跑全部联调用例
mvn -P local-chain test -Dtest=RealReportLocalChainRunTest

# 只跑某一个
mvn -P local-chain test -Dtest='RealReportLocalChainRunTest#parseStageOnRealReportShouldProduceSegments'
```

`h2`、`embedded-redis`、`wiremock` 本来就已经是 `test` 作用域，
**因此 pom 的依赖一个字都不用改**；`local-chain` profile 只是把 surefire 的
`excludedGroups` 换成 `groups`，让被默认排除的这个标签能跑起来。

### 2.1 涉及的文件

| 文件 | 作用 |
|---|---|
| `src/test/java/com/example/healthreport/localchain/LocalChainMockConfiguration.java` | 三个 Mock Bean（用户 / 对象存储 / 食堂查询） |
| `src/test/java/com/example/healthreport/localchain/LocalChainMockDishes.java` | §5.1 的 18 道菜品数据 |
| `src/test/java/com/example/healthreport/localchain/LocalChainSchema.java` | 把生产 `sql/schema.sql` 转成 H2 可执行语句 |
| `src/test/java/com/example/healthreport/localchain/LocalChainModelStubConfiguration.java` | OCR / LLM-A / LLM-B 三条链路的契约桩 |
| `src/test/java/com/example/healthreport/localchain/LocalChainEmbeddedRedis.java` | 进程级单例的嵌入式 Redis |
| `src/test/java/com/example/healthreport/localchain/LocalChainTestProperties.java` | 共用的中间件与模型连接参数 |
| `src/test/java/com/example/healthreport/localchain/LocalChainHttp.java` | 共用的真实 HTTP 调用助手 |
| `src/test/java/com/example/healthreport/localchain/RealReportLocalChainRunTest.java` | 真连联调用例 + 离线诊断 |
| `src/test/java/com/example/healthreport/localchain/StubbedFullChainRunTest.java` | 契约桩全链路用例（走真实 HTTP） |
| `pom.xml` | `excludedGroups` 增加 `local-chain`；新增同名 profile |

### 2.2 接口一律走真实端口

两个全链路用例都用 `webEnvironment = RANDOM_PORT` + `TestRestTemplate` 发真实 HTTP 请求，
**不直接调 Controller 方法**。直接调方法时下面这些全都不会执行：

- multipart 解析与 `spring.servlet.multipart.max-file-size` 限额；
- `AnalyzeRequest` 上的 `@Valid`（由参数解析器触发，直接调用完全失效）；
- `@PathVariable` 绑定；
- `HealthReportExceptionHandler` 的异常与 HTTP 状态码映射；
- 响应体的 Jackson 序列化。

那样的「全链路」会整个漏掉 Web 层。只跑解析阶段的诊断用例仍然直接调服务层
——它们要看的是接口不暴露的中间产物。

---

## 3. M1 `CurrentUserProvider` —— 固定用户

```
currentUserId() → "mock-user-0001"
```

| 约束 | 满足情况 |
|---|---|
| `ct_health_report_task.user_id` `VARCHAR(64)` | 15 字符，通过 |
| 归属校验走 Java 精确 `equals`（`AGENTS.md` §4） | 全小写，无大小写变体歧义 |
| 不得是真实用户标识 | `mock-` 前缀，一眼可辨 |

**整个联调期间只有这一个用户**，因此「任务归属校验」这条路径在本次联调中
只能验证正例。越权（A 用户查 B 用户任务）返回 404 的负例不在本次范围内，
已由 `OwnershipGuardTest` 覆盖。

---

## 4. M2 `S3FileStorage` —— 本机文件目录

| 方法 | Mock 行为 |
|---|---|
| `write(objectKey, bytes)` | 写入 `${根目录}/${objectKey}`，父目录自动创建，已存在则覆盖 |
| `read(objectKey)` | 读回全部字节；文件不存在抛 `IllegalStateException`（**不返回空数组**） |
| `delete(objectKey)` | 删除文件；文件不存在也算成功（幂等，与 S3 语义一致） |

- 根目录：`./target/local-mock-s3/`（`target` 下，`mvn clean` 即清空，不会误提交）
- 实际 objectKey 形如 `health-report/{fileId}`（见 `FileUploadService`），
  因此磁盘上是 `target/local-mock-s3/health-report/{fileId}`。
- **必须做路径逃逸校验**：规范化后的绝对路径不在根目录下就直接抛异常。
  objectKey 由 `IdCanonicalizer` 生成、不含 `..`，但 Mock 不能依赖调用方的自觉。

> ⚠️ 目录里存的是**体检报告原文**。联调结束后手工删除，不要留在磁盘上。
> `TaskResourceCleanupService` 在任务成功后会自动删（`deleteFiles`），
> 失败的任务要靠 30 分钟后的孤儿清理，本次联调不等它。

---

## 5. M3 `DishQueryService` —— 当日在架菜品

```
queryOnShelfDishes(bizDate) → 固定 18 道菜，与 bizDate 无关
```

**与 `bizDate` 无关是刻意的**：这样 `tagHash` 在联调期间保持稳定，
第二次跑能命中 `ct_dish_tag` 的复用分支，正好验证「打标复用」这条路径。

### 5.1 菜品数据全表

重量单位为克；`null` 表示重量未知（`DishIngredient.weightG` 允许 `null`）。

| dishId | 菜名 | 食材（名称:克重） |
|---|---|---|
| 1001 | 清炒菠菜 | 菠菜:200、植物油:8、蒜:5 |
| 1002 | 香煎三文鱼 | 三文鱼:150、柠檬:10、橄榄油:8、海盐:2 |
| 1003 | 蒜蓉粉丝蒸虾 | 虾仁:120、粉丝:60、蒜蓉:15、生抽:5 |
| 1004 | 番茄炒蛋 | 番茄:150、鸡蛋:120、植物油:10、白砂糖:3 |
| 1005 | 牛奶燕麦粥 | 牛奶:250、燕麦:50 |
| 1006 | 尖椒炒猪肝 | 猪肝:130、尖椒:80、植物油:10、料酒:5 |
| 1007 | 黑木耳炒西兰花 | 西兰花:150、黑木耳:60、胡萝卜:30、植物油:8 |
| 1008 | 红烧五花肉 | 五花肉:200、冰糖:20、生抽:15、八角:2 |
| 1009 | 孜然羊肉 | 羊肉:160、洋葱:60、植物油:10、孜然:3 |
| 1010 | 凉拌腐竹木耳 | 腐竹:80、黑木耳:70、生抽:8、香油:5、熟芝麻:5 |
| 1011 | 芒果西米露 | 芒果:120、椰浆:60、西米:50、白砂糖:15 |
| 1012 | 白灼西兰花 | 西兰花:null、蒜:null、生抽:null |
| 1013 | 清水煮时蔬 | （空食材表） |
| 1014 | 鱼香肉丝 | 猪瘦肉:120、木耳:50、胡萝卜:40、泡椒:10、白砂糖:8 |
| 1015 | 铁板鱿鱼 | 鱿鱼:150、洋葱:60、青椒:40、孜然:3 |
| 1016 | 宫保鸡丁 | 鸡胸肉:150、黄瓜:50、花生米:40、干辣椒:5 |
| 1017 | 番茄牛肉面 | 面条:150、牛腩:100、番茄:80、生抽:8 |
| 1018 | 腰果西芹 | 西芹:150、腰果:40、胡萝卜:30、植物油:8 |

### 5.2 为什么是这 18 道 —— 每道菜负责验证什么

这批数据不是随手编的菜单，每一道都对应链路上一个确定性分支：

| 覆盖目标 | 菜品 |
|---|---|
| 13 个食入性过敏组**全覆盖** | 虾蟹 1003 / 鱼类 1002 / 牛奶 1005 / 蛋类 1004 / 花生 1016 / 大豆 1010 / 小麦 1017 / 坚果 1018 / 芒果 1011 / 牛肉 1017 / 羊肉 1009 / 软体 1015 / 芝麻 1010 |
| 主料规则一（占比 ≥25%） | 1001、1008 等多数菜 |
| 主料规则二（前两名且 ≥15%） | 1005 燕麦 16.67%、1007 黑木耳 24.19% |
| 排名优先于占比（第 3 名占比更高也不算主料） | 1011：椰浆 24.49% 入选，西米 20.41% 落选（第 3 名） |
| 一道菜命中多个营养维度 | 1006 猪肝命中 IRON / VITAMIN_B12 / FOLATE / ZINC 四维 |
| 全部重量未知 → 主料空集 → 营养全 NEUTRAL | 1012 |
| 空食材表不炸 | 1013 |
| 食材别名归一（`IngredientAliasWords`） | 1014 猪瘦肉 → 瘦猪肉 |
| **标准名精确匹配的负例**（不做语义泛化） | 1002 三文鱼 ≠「鱼肉」→ PROTEIN 不推荐；1018 西芹 ≠「芹菜」→ 高纤维不推荐 |
| **`displayOnlyFoodList` 绝不触发推荐**的负例 | 1004 鸡蛋在 ZINC 的仅展示清单里 → ZINC 保持 NEUTRAL |
| `POTASSIUM` 永不自动推荐（可推荐清单为空） | 全部 18 道菜 |
| 过敏兜底**不看重量**（非主料也拦） | 1010 熟芝麻仅 5 克（2.98%）仍触发 SESAME |
| 过敏例外规则命中（`AllergenExceptions`） | 1014「鱼香肉丝」含「鱼」但被「鱼香」豁免 |
| **例外只作用于菜名、不作用于食材**这条不对称 | 1015：菜名「鱿鱼」对 FISH 豁免，但食材「鱿鱼」含「鱼」走食材路径，FISH 照样 REJECT |
| 纯负面菜（营养全 NEUTRAL，等 LLM-B 给饮食注意 REJECT） | 1008 红烧五花肉、1011 芒果西米露 |

---

## 6. 由 Mock 数据推导的确定性预期（步骤三的验收基线）

以下三列**完全由 Java 确定性规则算出，与模型无关**，因此可以在跑之前就写死。
步骤三跑完对不上，就是代码问题，不是模型问题。

| dishId | 菜名 | 主料集合 | 营养维度结果 | 过敏硬兜底 |
|---|---|---|---|---|
| 1001 | 清炒菠菜 | 菠菜 | FOLATE=RECOMMEND[菠菜] | 无 |
| 1002 | 香煎三文鱼 | 三文鱼 | VITAMIN_D=RECOMMEND[三文鱼] | FISH（菜名含「鱼」） |
| 1003 | 蒜蓉粉丝蒸虾 | 虾仁、粉丝 | PROTEIN=RECOMMEND[虾仁] | SHRIMP_CRAB（菜名含「虾」） |
| 1004 | 番茄炒蛋 | 番茄、鸡蛋 | PROTEIN=RECOMMEND[鸡蛋]；VITAMIN_B12=RECOMMEND[鸡蛋] | EGG（食材「鸡蛋」） |
| 1005 | 牛奶燕麦粥 | 牛奶、燕麦 | CALCIUM / PROTEIN / VITAMIN_B12=RECOMMEND[牛奶]；DIETARY_FIBER=RECOMMEND[燕麦] | MILK（菜名含「牛奶」） |
| 1006 | 尖椒炒猪肝 | 猪肝、尖椒 | IRON / VITAMIN_B12 / FOLATE / ZINC=RECOMMEND[猪肝] | 无 |
| 1007 | 黑木耳炒西兰花 | 西兰花、黑木耳 | FOLATE=RECOMMEND[西兰花]；DIETARY_FIBER=RECOMMEND[西兰花,黑木耳] | 无 |
| 1008 | 红烧五花肉 | 五花肉 | 全部 NEUTRAL | 无 |
| 1009 | 孜然羊肉 | 羊肉、洋葱 | IRON=RECOMMEND[羊肉] | MUTTON（菜名含「羊肉」） |
| 1010 | 凉拌腐竹木耳 | 腐竹、黑木耳 | DIETARY_FIBER=RECOMMEND[黑木耳] | SOY（菜名含「腐竹」）；SESAME（食材「熟芝麻」） |
| 1011 | 芒果西米露 | 芒果、椰浆 | 全部 NEUTRAL | MANGO（菜名含「芒果」） |
| 1012 | 白灼西兰花 | **空集** | 全部 NEUTRAL | 无 |
| 1013 | 清水煮时蔬 | **空集** | 全部 NEUTRAL | 无 |
| 1014 | 鱼香肉丝 | 瘦猪肉、木耳 | PROTEIN=RECOMMEND[瘦猪肉] | **无**（「鱼香」豁免生效） |
| 1015 | 铁板鱿鱼 | 鱿鱼、洋葱 | 全部 NEUTRAL | FISH（食材「鱿鱼」含「鱼」）；MOLLUSK（菜名含「鱿鱼」） |
| 1016 | 宫保鸡丁 | 鸡胸肉、黄瓜 | PROTEIN=RECOMMEND[鸡胸肉] | PEANUT（食材「花生米」） |
| 1017 | 番茄牛肉面 | 面条、牛腩 | 全部 NEUTRAL | WHEAT（食材「面条」）；BEEF（菜名含「牛肉」） |
| 1018 | 腰果西芹 | 西芹、腰果 | 全部 NEUTRAL | NUTS（菜名含「腰果」） |

> 主料集合按重量降序排列，营养维度的 `matchedIngredients` 沿用该顺序。
> 上表由复刻 `MainIngredientResolver` / `NutritionMatcher` / `AllergenKeywordFallback`
> 三个类的规则、并从 `AllergenGroups` / `NutritionContents` 实际常量表取词计算得出。

### 6.1 LLM-B 打标规模

维度数 = 13 个食入性过敏原 + 9 个饮食注意 = **22**（`DishTagService.dimensions()`）。
批大小 `BATCH_SIZE = 40`，18 道菜一批装得下，因此：

- 首次打标：**22 次 LLM-B 调用**，产出 18 × 22 = 396 个三元组；
- 第二次起（`tagHash` 未变）：**0 次调用**，全部走复用分支。

### 6.2 与本次 PDF 的交集提醒

`82312235302.pdf` 是 18 页的样本 A，据 `真实体检报告样本-提取逻辑验证报告.md` §8.1，
**该报告没有过敏原筛查表，`allergens` 预期为空数组**。

因此本次联调里：

- 13 个过敏维度**大概率一个都不会生效**，上表的「过敏硬兜底」列这一轮验证不到；
- 真正会被点亮的是**营养补充**与**饮食注意**两类维度（若报告的总检结论/医生建议里有）；
- 如果模块四最终为空，先看日志里的 `模块四输入就绪，业务日=…，已抑制=…，当日在架菜品数=18`
  —— 「当日 0 道在架菜」和「被降级抑制」是两个完全不同的根因，这条日志能直接分开。

---

## 7. M4 MySQL → H2 内存库

| 项 | 值 |
|---|---|
| JDBC URL | `jdbc:h2:mem:health_report_local;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1` |
| 用户名 / 密码 | `sa` / （空） |
| 建表脚本 | 生产 `sql/schema.sql`，经 `LocalChainSchema` 做最小语法改写后逐条执行 |
| 初始数据 | **无。三张表全部空表起步**，每个用例前重建 |

**不预置任何行**：`ct_health_report_task` 和 `ct_health_report_file` 的行由上传与建任务
接口自己写；`ct_dish_tag` 的行由 M6 的打标 Job 写。预置行会让「行到底是谁写的」变成糊涂账，
而这次联调要看的恰恰就是链路自己写没写对。

复用 `sql/schema.sql` 而不是另抄一份 DDL：抄出来的副本会和生产 DDL 分叉，
而联调恰恰要验证「生产建表脚本描述的那张表」能不能跑通链路。

`LocalChainSchema` 的改写只涉及 H2 与 MySQL 的**语法**差异，不动列、类型、
可空性与唯一性这些**语义**，逐条如下：

| 改写 | 原因 |
|---|---|
| 去掉表尾 `ENGINE=InnoDB …` | H2 不认这一整串 |
| 去掉列级 `CHARACTER SET / COLLATE` | H2 不支持逐列声明 |
| 去掉列注释与表注释 | H2 的 `COMMENT` 语法位置不同 |
| `TINYINT(1)` → `TINYINT` | H2 的 TINYINT 不带显示宽度 |
| 索引名加表名前缀 | **MySQL 索引名按表作用域，H2 按 schema 作用域**；两张表各有一个 `idx_user` 在 MySQL 完全合法，照抄到 H2 会报 `Index "idx_user" already exists` |

> **刻意保留 `UNIQUE KEY`。** 仓库既有的 `SqlSchemaMigrationTest` 把全部
> `KEY` / `UNIQUE KEY` 一并剥掉了（它只比对列结构，不需要索引），
> 但联调不能这么干：`ct_dish_tag` 的幂等写入靠 `uk_dish_hash_enum`
> （`insertIgnore` 依赖唯一键），剥掉之后重复打标会静默产生多行
> ——而那正是联调要验的东西之一。已实测 H2 2.1.214 支持这两种内联索引声明。

### 7.1 已知失真点（H2 ≠ MySQL）

这几条在 H2 上验过**不等于**在 MySQL 上成立，上线前必须在真 MySQL 复验：

1. `insertIgnore` 依赖唯一键的冲突语义；
2. `ON UPDATE CURRENT_TIMESTAMP` 的触发时机与秒级精度；
3. `utf8mb4_general_ci` 的大小写不敏感行为（H2 的排序规则不同）；
4. 乐观锁 CAS 在并发下的实际表现（本次单线程联调本来也验不到）。

---

## 8. M5 Redis → 嵌入式 redis-server

| 项 | 值 |
|---|---|
| 实现 | `com.github.codemonstur:embedded-redis:1.4.3`（已在本地 `~/.m2`） |
| 平台二进制 | jar 内含 `redis-server-6.2.6-v5-darwin-arm64`，本机可直接跑 |
| 监听 | `127.0.0.1`，端口取空闲端口后写入 `spring.redis.host/port` |
| 生命周期 | 联调用例 `@BeforeAll` 拉起，`@AfterAll` 销毁；端口经 `@DynamicPropertySource` 注入 |
| 初始数据 | **无。空库起步** |

链路自己会写两类键，均无需预置：

| 键 | 写入方 | TTL |
|---|---|---|
| `result:{taskId}` | `TaskResultCache.write` | 2 小时 |
| `dish:tag:{业务日}:{维度枚举名}`（Hash） | `DishTagCache.putAll` | 3 天 |

选嵌入式而不是 Testcontainers：本机**没有 Docker**（已确认）。
选嵌入式而不是自己写内存版 `StringRedisTemplate`：TTL 真没真写进去、
Hash 结构在服务端长什么样，内存假实现一件都验不到。

---

## 9. M6 xxl-job → 手工触发

不接调度中心。`DishTagJob.execute()` 需要在**分析任务之前**跑一次，
否则 `ct_dish_tag` 是空的，模块四全部菜品会落到 `TAG_MISSING`。

触发方式：在联调用例里直接注入 `DishTagJob` 并调用 `execute()`，
不需要任何生产侧的触发入口。**当前尚未接入**——它依赖 LLM-B 的真实连接参数，
见 §10。

---

## 10. 真连所需的配置（跑 `RealReportLocalChainRunTest` 时必须提供）

以下 12 项没有默认值，缺任何一项 Spring 启动阶段直接失败
（`OcrProperties.afterPropertiesSet` / `OcrStartupValidator` / 各 `StartupValidator`）。
**凭证只走环境变量，绝不写进仓库任何文件。**

### 10.1 OCR 接入契约六项（`OcrProperties`，无默认值）

| 键 | 约束 |
|---|---|
| `ocr.max-encoded-image-bytes` | 正整数 |
| `ocr.max-request-body-bytes` | 正整数，且 ≤ `Integer.MAX_VALUE` |
| `ocr.request-encoding` | **必须是 `JSON_BASE64`**，配 `MULTIPART` 启动即失败 |
| `ocr.accepts-encoded-bytes` | **必须是 `true`** |
| `ocr.applies-exif-orientation` | 与下一项不能同时为 `false` |
| `ocr.returns-image-dimensions` | 同上 |

### 10.2 三条模型链路的连接参数（环境变量）

| 链路 | 环境变量 |
|---|---|
| OCR | `OCR_BASE_URL`、`OCR_MODEL`、`OCR_API_KEY` |
| LLM-A 抽取 | `EXTRACTION_BASE_URL`、`EXTRACTION_MODEL`、`EXTRACTION_API_KEY` |
| LLM-B 打标 | `DISHTAG_BASE_URL`、`DISHTAG_MODEL`、`DISHTAG_API_KEY` |

> 本机当前**这 9 个环境变量一个都没设**。步骤三开跑前需要你提供网关地址、
> 模型名与 key，否则应用起不来。

---

## 11. 隐私边界（联调期同样生效）

- `logging.level.HEALTH_REPORT_SENSITIVE` **保持 `OFF`**。
  联调不是打开报告原文日志的理由；真要看抽取结果，看接口返回，不看日志。
- `target/local-mock-s3/` 下是体检报告原文，联调结束手工清空。
- H2 是内存库，进程退出即消失，报告结构化内容不落盘 —— 这一点与设计方案
  「MySQL 不存报告内容」的约束方向一致。
- Mock 的 `mock-user-0001` 不是真实用户标识，可以安全出现在日志里。

---

## 12. 变更纪律

- 改动 §5.1 的菜品数据 → §6 的预期表必须同步重算（`tagHash` 会变，会触发全量重打标）；
- 本文件与代码里的 Mock 实现不一致时，**以本文件为准并立即改代码**；
- 本文件描述的一切**只存在于 `src/test`**，且只在显式选择 `local-chain` 标签时执行；
  不得把 Mock 搬进 `src/main`，也不得让任何常规用例依赖它们。
