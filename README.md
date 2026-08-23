# marketing-platform

营销活动平台，将「用户完成指定行为后获得奖励」抽象为可配置玩法，统一承载活动配置、资格决策、奖励发放、订单履约与对账补偿能力。首期支持裂变与权益售卖两类玩法。

## 玩法

### 裂变

社交邀请玩法，用户邀请好友完成指定行为后双向获得奖励，资金方向为纯补贴。

- 轮次管理：按用户与场景维度自动开轮，支持手动开轮与轮次查询
- 好友获取与过滤：拉取可分享好友列表，按已建立关系、活动规则、风控规则逐层过滤
- 关系状态机：`INVITED → CONNECTED → JOINED → DONE`，非终态支持过期与取消
- 双向发奖：徒弟完成确权后，同源派生两个幂等键分别发放徒弟奖励与师傅返奖

### 权益售卖

支付履约玩法，用户购买权益包并由平台对接供应方完成发放，含完整逆向链路。

- 商品与权益配置：SKU、权益包、权益项三级模型，支持版本快照
- 交易：预咨询试算、凭证签发与校验、服务端重算比价、库存与限购原子预占
- 支付：支付单创建、回调金额校验、超时关单
- 履约：按供应方分组编排发放，多权益项独立记录发放结果与下游单号
- 逆向：退款准入判定、权益回收、退款执行，回收与退款先后可审计

### 公共能力

两类玩法共用，玩法层不重复实现。

- **活动配置**：活动创建、发布前六项校验、状态机流转、配置版本快照（历史单据按下单时配置履约）
- **资格决策**：人群、城市、渠道、风控多维判定，返回标准原因码，只读无副作用
- **统一发奖**：唯一对接奖励供应方的出口，按幂等号去重，支持发放、查单、回收
- **操作记录**：任一有副作用的动作留痕，支持按父单查子单、按外部单号反查业务
- **可靠任务**：与业务状态同事务落库，多实例抢占式调度，支持租约续期与故障接管
- **对账**：15 项定时比对，资损哨兵指标由对账产出

## 架构

```
接入层      gateway                              鉴权、限流、路由、统一响应
              ↓
玩法层      fission        benefit-order         各玩法私有逻辑
              ↓                  ↓
公共能力层  activity  ·  reward                  两玩法共用
              ↓                  ↓
下游        mock 支付  ·  mock 供应方             外部系统（本期 mock）
```

依赖严格自上而下，由 `pom.xml` 编译期强制：上层可依赖下层（允许跨层），下层不得依赖上层，同层不互调。

**两种部署形态，同一份业务代码。** V0–V3 是模块化单体：全部模块装进 `mp-gateway` 一个进程，Dubbo 走 `injvm` 本地调用。V4 起可切分布式：五服务独立打包，`tri` 协议 + Nacos 服务发现，按三机拓扑部署（基础设施 / 接入+玩法层 / 公共能力层）。切换只改配置与装配，`src/main/java` 下无业务逻辑改动。

| 模块 | 职责 |
| --- | --- |
| `mp-gateway` | 接入层，V0–V3 单进程启动入口，兼托管前端静态产物 |
| `mp-fission` | 裂变关系、好友过滤、双向发奖编排 |
| `mp-benefit-order` | 订单、支付对接、履约编排、退款回收 |
| `mp-activity` | 活动配置、配置版本、资格决策 |
| `mp-reward` | 统一发奖、供应方路由、查单、回收 |
| `mp-mock-downstream` | mock 支付、mock 供应方 |
| `mp-common` | 结果码、四分类枚举、单号与幂等键生成、异常 |
| `mp-api` | 对外接口定义，按服务拆五个子模块 |
| `web` | Vue3 前端工程，独立构建，不进 Maven 生命周期 |

数据按服务分库：`db_activity` / `db_fission` / `db_benefit` / `db_reward`，禁止跨库 JOIN 与跨库事务。V1 以单数据源承载全部表，多数据源在 V2 配置（[分阶段方案](docs/营销活动平台-分阶段方案.md) §7.3 第 8 条）。

## 一致性设计

系统的核心约束是**下游调用结果不确定**——RPC 超时后无法判断下游是否已执行。围绕这一约束建立四层防线：

| 层 | 手段 | 作用 |
| --- | --- | --- |
| L1 | 凭证签名 | 防篡改、防伪造 |
| L2 | 分布式锁（Redisson） | 降低并发碰撞概率，属性能优化 |
| L3 | 业务唯一键 + 唯一索引 | 正确性最终兜底，锁失效时仍成立 |
| L4 | 操作记录 + 可靠任务 | 结果未定时以原幂等号查单收敛 |

**四分类结果**：下游返回统一归为 `SUCCESS` / `FAIL` / `PROCESSING` / `UNKNOWN`，仅 `FAIL` 允许走失败分支。`PROCESSING` 采用长退避（30s → 2m → 10m），`UNKNOWN` 采用短退避（1s → 5s → 30s），两者不合并。

**幂等三道闸**：幂等键唯一索引挡重传，单据级唯一索引 `uk_biz_op(bizNo, opType, opSeq)` 挡同一业务语义的不同键，主单条件更新挡乱序回调。

**幂等键规约**：一律确定性字符串拼接，来源限定为外部输入或已落库的稳定值，禁止内部自增序列、时间戳、UUID 参与；超时重试必须复用原键。

**一致性机制单一**：仅使用可靠任务表，任务写入与业务状态变更处于同一本地事务。RocketMQ 仅用于跨层事件广播，不承担一致性职责。

## 技术栈

| 维度 | 选型 |
| --- | --- |
| 运行时 | JDK 21（虚拟线程 GA） |
| 框架 | Spring Boot 3.5、Spring MVC + 虚拟线程 |
| 服务化 | Apache Dubbo 3.3（injvm / tri 双协议） |
| 持久层 | MyBatis-Plus、MySQL 8、Flyway |
| 注册中心 | Nacos 2.4（V4 起，injvm 形态不注册） |
| 缓存与锁 | Redis + Redisson；活动配置本地缓存用 Caffeine |
| 消息 | RocketMQ 5.3（普通消息，跨层事件广播） |
| 流控 | Sentinel（V4 起，仅 gateway 侧） |
| 可观测 | Micrometer + Prometheus + Grafana + Alertmanager |
| 测试 | Testcontainers（真实 MySQL）、k6 |

选型依据与备选对比见[技术方案](docs/营销活动平台-技术方案.md) §2。

## 开发进度

采用模块化单体形态开发，交付期切换为分布式部署，业务代码不变。

| 阶段 | 范围 | 退出标准 | 状态 |
| --- | --- | --- | --- |
| V0 | 工程骨架、CI、冒烟链路 | CI 全绿，依赖方向编译期可拦截 | ✅ |
| V1 | 权益售卖正向链路 | 下单 → 支付回调 → 发放 → 查询 e2e 通过 | ✅ |
| V2 | 四分类收敛、可靠任务、幂等三道闸、库存 | 注入超时自动收敛无重复发放；500VU 抢 100 无超卖 | ✅ |
| V3 | 裂变全链路、逆向退款、对账、事件广播 | 新玩法接入公共能力层零改动；对账检出人为注入的差异 | ✅ |
| V4 | 分布式化、多实例、监控看板、全链路压测 | kill 实例任务被接管；三机部署压测通过 | ✅ |

V1 实测记录（实施偏差、缺陷、形状冻结落地情况）见[分阶段方案](docs/营销活动平台-分阶段方案.md) §4.8，V2 见 §5.8，V3 见 §6.6，V4 见 §6A.4。

各阶段详细范围与退出标准见[分阶段方案](docs/营销活动平台-分阶段方案.md)。

## 快速开始

环境要求：JDK 21、Maven 3.9+、Docker。前端另需 Node 18.19+。

```bash
mvn validate                          # 安装 git 钩子，克隆后需执行一次
docker compose up -d mysql
mvn verify                            # 编译、单测、集成测试
mvn -pl mp-gateway spring-boot:run
```

静态产物已随仓库提交，`localhost:8080` 直接可用，改前端才需要重新构建：

```bash
cd web && npm install
npm run verify                        # 枚举一致性 + 类型检查
npm run build                         # 产物直接输出到 mp-gateway 静态目录
npm run dev                           # 或起 :5173 dev server，/api 代理到 :8080
```

### 页面演示

浏览器打开 `localhost:8080`，默认进商品页。演示数据由 seed 脚本初始化：活动 `ACT_DEMO_001`、SKU `SKU_DEMO_001`、售价 99 元、含两个分属不同供应方的权益项。

| 路径 | 用途 |
| --- | --- |
| `/shop` | 商品页，下单 → 模拟支付 → 观察发放 |
| `/my-orders`、`/benefit/orders/:bizNo` | 订单列表与详情（含操作记录时间线） |
| `/fission/rounds` | 裂变轮次与关系 |
| `/ops/tasks`、`/ops/reconcile` | 可靠任务看板、对账 |
| `/devtools` | 故障注入与场景断言，迁自旧 `console.html` |

### 命令行演示

链路与页面一致。**下单必须带 `consultToken`、支付通知必须带 `sign`** —— 二者分别自 V2 的 L1 防线起强制，缺则返回 `4003` / `4731`。

```bash
# ① 预咨询，返回 consultToken 与 dealPrice
curl -s -X POST localhost:8080/api/benefit/consult \
  -H 'Content-Type: application/json' \
  -d '{"userId":"U001","activityId":"ACT_DEMO_001","skuId":"SKU_DEMO_001"}'

# ② 下单，consultToken 填上一步的返回值，返回 bizNo 与 tradeNo
curl -s -X POST localhost:8080/api/benefit/trade \
  -H 'Content-Type: application/json' \
  -d '{"userId":"U001","activityId":"ACT_DEMO_001","skuId":"SKU_DEMO_001",
       "clientReqNo":"REQ001","quantity":1,"consultToken":"<consultToken>"}'

# ③ 取签名。真实链路由支付方算出，mock 支付方不主动回调，故留了这个演示端点。
#    V4 起 /api/fault/** 需带运维令牌，缺了返回 403
curl -s -X POST localhost:8080/api/fault/pay-notify/sign \
  -H 'X-Ops-Token: local-dev-ops-token-do-not-use-in-prod' \
  -H 'Content-Type: application/json' \
  -d '{"outTradeNo":"<bizNo>","tradeNo":"<tradeNo>","notifySeq":"NS001",
       "payStatus":"SUCCESS","payAmount":9900,"currency":"CNY",
       "merchantId":"MCH_LOCAL_DEMO"}'

# ④ 支付结果通知，sign 填上一步的返回值
curl -s -X POST localhost:8080/api/benefit/pay-callback \
  -H 'Content-Type: application/json' \
  -d '{"outTradeNo":"<bizNo>","tradeNo":"<tradeNo>","notifySeq":"NS001",
       "payStatus":"SUCCESS","payAmount":9900,"currency":"CNY",
       "merchantId":"MCH_LOCAL_DEMO","sign":"<sign>"}'

# ⑤ 查单，pay_status=PAY_SUCCESS、grant_status=GRANT_SUCCESS、两条履约明细
curl -s localhost:8080/api/benefit/order/<bizNo>
```

`/api/fault/**` 是演示设施，能签发通知等于能伪造收款。V4 起已加令牌校验，调用需带 `X-Ops-Token`（本地默认值见 `application-local.yml`）。

### 分布式形态

```bash
docker compose --profile v4 up -d      # MySQL/Redis/Nacos/RocketMQ + 监控栈
mvn -B package -DskipTests             # 产出六个 *-boot.jar
docker compose -f docker-compose-dist.yml up -d --build

docker/smoke-dist.sh                   # 全链路冒烟
docker/verify-exit-criteria.sh         # 退出标准逐条验证
```

多实例与故障验证：

```bash
MP_TASK_LEASE_SECONDS=5 MP_TASK_INTERVAL_MILLIS=500 \
  docker compose -f docker-compose-dist.yml up -d --scale benefit-order=3
docker/verify-takeover.sh              # kill -9 后任务被接管、陈旧写回被 fencing 拒绝
```

| 端口 | 服务 |
| --- | --- |
| 8080 | gateway（前端与业务入口） |
| 3000 | Grafana（免登录，两块看板） |
| 9090 | Prometheus |
| 8848 | Nacos 控制台 |

macOS 环境搭建注意事项见[环境与依赖](docs/营销活动平台-环境与依赖.md) §1.1。

## 文档

| 文档 | 内容 |
| --- | --- |
| [技术方案](docs/营销活动平台-技术方案.md) | 架构、DDL、接口、时序、一致性实现、监控压测 |
| [分阶段方案](docs/营销活动平台-分阶段方案.md) | 阶段划分、形状冻结清单、各阶段退出标准 |
| [开发规范](docs/营销活动平台-开发规范.md) | 工程结构、建表、幂等键、事务边界、合入前检查 |
| [环境与依赖](docs/营销活动平台-环境与依赖.md) | 版本锁定、环境搭建、中间件配置 |
| [前端技术方案](docs/营销活动平台-前端技术方案.md) | 前端选型、契约层、页面结构、部署 |
| [PRD](docs/营销活动平台-PRD.md) | 业务需求与验收标准 |

## 协作约定

`main` + 短分支 + PR + squash merge。提交遵循 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/v1.0.0/)，编码规范采用《阿里巴巴 Java 开发手册》黄山版，格式由 Spotless（AOSP 风格）统一。

合入前提为 CI 七项全绿（commit 格式、Spotless、编译、单测、集成测试、Flyway 从零执行、文档一致性），review 非必需。涉及资金路径的变更由提交者按[开发规范](docs/营销活动平台-开发规范.md) §11 自查并在 PR 中给出结论。
