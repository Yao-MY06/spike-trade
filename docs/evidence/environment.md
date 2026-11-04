# 运行环境登记（阶段五 · 第四章「固定环境与基线测量」）

> 采集时间：**2026-10-28 16:20（Asia/Shanghai）**
> 执行者：**AI 代跑**（所有命令由 AI 执行，未由用户亲手操作）
> 本文只登记事实，不含任何密码、密钥或连接串；凭据由 `config/application.yaml`（已 gitignore）以 `${TRADE_*}` 占位符注入。

## 一、代码版本

| 项 | 值 |
| --- | --- |
| 仓库路径 | `F:\study\spike-base`（远端 `Madgeaaaaa/spike` 的二次开发工作副本，本地另建 git） |
| 分支 | `master` |
| HEAD | `0d4c087acdf7b8ecb8c86cca571734f272d0197f`（`evidence: 基线接口耗时采样 run1 …`） |
| 基线起点 | `0bfd67148578229bc25b98fa592a2cc6b142e0f7`（上游最后一次提交，2025-08-01） |
| 工作区状态 | `git status --short` = 2 个未跟踪项：`_to_delete/`、`docs/architecture.md`；**无已修改的受版本控制文件** |
| 构建产物 | `target/spike-trade-0.0.1-SNAPSHOT.jar`，52,295,340 字节，mtime **2026-10-24 15:26** |

**限制**：jar 构建于 2026-10-24 15:26，早于当前 HEAD（`0d4c087`）。HEAD 相对构建点新增的提交只涉及 `docs/`（证据与脚本），**不改变编译产物**；但这一点是推断，不是验证——如需严格对齐，应重新 `mvn package` 后重跑基线。

## 二、环境

| 项 | 值 |
| --- | --- |
| 操作系统 | Microsoft Windows 11 Pro，10.0.26200，build 26200，64 位 |
| CPU | AMD Ryzen 5 7640HS w/ Radeon 760M Graphics，6 物理核 / 12 逻辑核 |
| 内存 | 13.7 GB（TotalVisibleMemorySize） |
| 工作目录 | `F:\study\spike-base`（**必须在项目根启动**，Spring Boot 才会加载 `./config/application.yaml`） |
| 服务端口 | 8081（启动参数显式 `--server.port=8081`） |
| 外部配置 | `config/application.yaml`（已 gitignore）提供 6 个 `${TRADE_*}` 变量的值：DB 密码、Redis 密码、MQ 用户名/密码、JWT admin/user 密钥。**值不入本文** |

## 三、依赖版本

| 组件 | 版本 | 获取方式 |
| --- | --- | --- |
| 构建/运行 JDK | **11.0.32.1**（Eclipse Temurin，`F:\path`） | `F:\path\bin\java.exe -version` |
| 系统默认 JDK | 25.0.1（Oracle，`C:\Program Files\Java\jdk-25`） | `java -version`；**只用于 IDE/其他工具，本项目必须用 11** |
| Maven | **3.9.16**（`E:\work\apache-maven-3.9.16`），本次以 `JAVA_HOME=F:\path` 运行，`mvn -v` 报告 Java 11.0.32.1 / Adoptium | `mvn -v` |
| 应用框架 | Spring Boot **2.3.12.RELEASE** / Spring 5.2.15.RELEASE | 源码 `pom.xml`；启动日志 |
| MySQL | **9.5.0 MySQL Community Server - GPL**（InnoDB 9.5.0），本机安装，3306 | `select @@version, @@version_comment, @@innodb_version` |
| Redis | 容器 `$REDIS_CONTAINER`，镜像 `redis:6.2`；**服务端 v=6.2.24**（jemalloc-5.1.0，64 位）；容器 Up，6379 监听 | `docker exec "$REDIS_CONTAINER" redis-server --version` |
| RabbitMQ | 容器 `$MQ_CONTAINER`，镜像 `rabbitmq:3.8-management`；**服务端 3.8.34**；容器 Up，5672 / 15672 监听 | `docker exec $MQ_CONTAINER rabbitmqctl version` |
| Docker | Docker Desktop，引擎 29.4.3（containerized） | `docker info` |
| 连接池 | HikariCP（Spring Boot 2.3 默认） | 启动日志 `HikariPool-1` |
| 前端 | nginx 1.18.0（`F:\study\spike-nginx`），80 端口；本次指标采集**不使用前端** | 历史记录 |

## 三之二、应用进程与实际 JVM 参数（2026-10-28 16:26 采集）

| 项 | 值 |
| --- | --- |
| 进程 | 唯一在跑的 `java.exe`，PID **25872** |
| 启动命令行 | `F:\path\bin\java.exe -jar target/spike-trade-0.0.1-SNAPSHOT.jar --server.port=8081` |
| 工作目录 | `F:\study\spike-base`（由此加载 `./config/application.yaml`） |
| **显式 JVM 参数** | **无**（未设 `-Xmx` / `-Xms` / GC 参数，全部走 JDK 11 默认人机工程值） |
| 实际生效 GC | `-XX:+UseG1GC`，`CICompilerCount=4` |
| 实际堆 | `InitialHeapSize` 230,686,720 B ≈ **220 MB**；`MaxHeapSize` 3,676,307,456 B ≈ **3.42 GB**（按容器/物理内存推导） |
| 采集命令 | `Get-CimInstance Win32_Process -Filter "Name='java.exe'"` 取 `CommandLine`；`jcmd <pid> VM.command_line`；`jcmd <pid> VM.flags` |

> 结论：**基线数据来自一套未调优的默认 JVM 配置**。后续若做压测或调优对比，必须同时记录本节的堆大小与 GC，否则数字不可比。

## 四、输入（数据规模与采样参数）

**数据库 `spike_trade`（精确 `COUNT(*)`，2026-10-28 16:21）**

| 表 | 行数 |
| --- | --- |
| `tb_user` | 1005 |
| `tb_shop` | 14 |
| `tb_shop_type` | 10 |
| `tb_blog` | 4 |
| `tb_voucher` | 2 |
| `tb_seckill_voucher` | 1 |
| `tb_voucher_order` | 0 |

> 注：`tb_user` 的 1005 行是历次登录/发码联调累积出来的测试用户，**不是业务真实规模**；`tb_blog` 仅 4 行，`/blog/hot` 的耗时结构受此影响（分页与 N+1 次数直接由博客条数决定）。

**采样参数（`docs/evidence/baseline-metrics/measure-rt.sh`）**

| 项 | 值 |
| --- | --- |
| 接口 | `GET /shop-type/list`、`GET /voucher/list/1`、`GET /blog/hot?current=1`（均免登录） |
| 预热 | 20 次/接口，结果丢弃 |
| 采样 | 100 次/接口，**串行**，无并发 |
| 计时 | `curl -w '%{time_total}'`，转毫秒保留 3 位小数 |
| 有效样本判定 | 仅 HTTP 200；非 200 / 超时 / 连不上计入 `fail`，不写入样本文件 |
| 百分位 | 最近秩法：第 k 个 = 排序后第 `ceil(n×k/100)` 个（n=100 时 p95 = 第 95 条） |
| 输出 | `run1/raw-<接口>.txt`（原始，每行一次成功请求的毫秒数）+ `summary.tsv` / `summary.md` / `meta.txt` |

**已完成的采样（两轮，均 AI 代跑；原始数据分别在 `run1/`、`run2/`）**

| 接口 | run1 p50 | run1 p95 | run1 max | run2 p50 | run2 p95 | run2 max |
| --- | --- | --- | --- | --- | --- | --- |
| `shop-type-list` | 6.818 | 10.284 | 35.330 | 6.906 | 9.332 | 11.279 |
| `voucher-list` | 7.871 | 11.521 | 13.705 | 8.270 | 12.570 | 28.235 |
| `blog-hot` | 17.071 | 22.371 | 34.761 | 13.519 | 20.343 | 32.592 |

- 两轮均 n=100、fail=0；run1 采集于 2026-10-24 15:47，run2 于 2026-10-28 16:26（**不同进程实例**：run1 的 jar 启动于 10-24 15:26，run2 重新启动，PID 25872）。
- p50 两轮相差 0–20%（`blog-hot` 从 17.1 降到 13.5，`voucher-list` 从 7.9 升到 8.3），**说明单轮 100 样本的跨运行稳定性有限**；做改造前后对比时，同一结论必须在同一进程、同一数据规模下各跑两轮再比。

**SQL 统计（run2，原始输出见 `baseline-metrics/run2/db-digest.txt`）**

| SQL（摘要，完整文本见原始文件） | 次数 | 平均 ms | 最大 ms | 合计 ms |
| --- | --- | --- | --- | --- |
| `SELECT COUNT(*) FROM tb_blog` | 120 | 1.56 | 18.86 | 187.2 |
| `SELECT … FROM tb_user WHERE id = ?` | 480 | 0.25 | 3.306 | 119.8 |
| `SELECT … FROM tb_voucher v LEFT JOIN tb_seckill_voucher sv … WHERE v.shop_id = ? AND v.status = ?` | 120 | 0.65 | 2.988 | 77.9 |
| `SELECT … FROM tb_blog ORDER BY liked DESC LIMIT ?` | 120 | 0.37 | 0.689 | 44.4 |

> `TIMER_WAIT` 单位为皮秒，除以 1e9 得毫秒。次数 120 = 20 预热 + 100 采样；用户查询 480 = 120 × 4（`tb_blog` 共 4 行）。**N+1 成立**：与 `BlogServiceImpl.queryBlogUser` → `userService.getById` 的源码推导一致。
> 订正（2026-10-28）：本节此前版本把用户查询转述为 `WHERE phone = ?`，原始输出是 `WHERE id = ?`，已按原始输出改正；同时删除了 run1 的统计列——run1 当时没有取原始统计，来源无法追溯，不作证据。
> 限制：原始文件的采集时间在 run2 结束后约 4 分钟，无法证明期间应用没有收到其他请求；计数恰好为 120/480，与推导相符。

## 五、方法

1. 串行 HTTP 采样，每请求独立 curl 进程（**不复用连接**），因此不含 TCP 握手复用效应；反过来，Windows 进程启动开销使墙钟约 1.1 s/请求，但不进入 `time_total`。
2. DB 侧不看慢日志（`long_query_time = 10s`，对本项目接口等于永不触发），改用 `performance_schema` 的 digest 汇总表；采样前只清空这一张表。
3. 输出目录按 `run<N>` 分开（脚本在目录中已有 `raw-*.txt` 时拒绝执行），保证多轮原始数据都在。

## 六、限制（如实，勿外推）

1. **本文档对应两轮采样，但运行环境只登记了一次**：run2 的容器、进程、JVM 参数见第三节之二；run1 的应用进程（启动于 2026-10-24 15:26）已不在，其 JVM 参数**未采集**，按默认配置推定同值。
2. **单机、单进程、串行**，未并发：以上数字**不是吞吐量或压测结论**，不能推出 QPS 上限，也不能用于超卖/重复下单的并发正确性论证。
3. 数据规模极小（`tb_shop` 14 行、`tb_blog` 4 行），**指标随数据增长会变**；跨时间比较必须同时对比第四节的规模。
4. `tb_user` 1005 行是联调垃圾数据，不代表真实用户规模。
5. 指标对应的 jar 构建于 2026-10-24 15:26，早于 HEAD；HEAD 之后新增的提交只涉及 `docs/`，**不改变编译产物**——但这是推断（比对提交范围得出），不是重新构建验证过。
6. Windows 上每个请求要新起一个 curl 进程，墙钟约 1.1 s/请求；该开销**不计入** `time_total`，但会让单轮采样耗时 6–7 分钟。
7. 两轮跨运行 p50 相差最大 20%，**单轮结果不足以支撑"改造前后提升 X%"的结论**。

## 七、待补与已闭环

| 项 | 状态 |
| --- | --- |
| Docker 引擎 / 容器状态、镜像与 tag | ✅ 已补：引擎 29.4.3，`redis:6.2`（服务端 6.2.24）、`rabbitmq:3.8-management`（3.8.34），均 Up |
| 应用实际 JVM 启动参数 | ✅ 已补：见第三节之二（无显式参数，G1GC，堆 220 MB → 3.42 GB） |
| 第二轮采样 `run2` | ✅ 已完成：`docs/evidence/baseline-metrics/run2/`（6m15s，300 样本，fail=0） |
| 慢查询日志证据 | ⬜ **不做**：`long_query_time = 10s`，对本项目接口等于永不触发；改用 digest 表。若需抓慢 SQL，临时调低并**用完还原**，且把改动写进证据 |
| 跨实例/跨数据规模对照 | ⬜ 未做：需要扩大数据量（如把 `tb_shop`/`tb_blog` 造到千级）后再采样一轮，才能观察索引与 N+1 的真实放大效应 |
