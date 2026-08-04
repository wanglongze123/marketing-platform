# marketing-platform

营销活动平台：**裂变**（社交邀请，纯补贴）+ **权益售卖**（支付履约，含退款回收）两个玩法，共用活动配置、资格决策、统一发奖三项公共能力。

---

## 克隆后第一件事

```bash
mvn validate
```

**必须先跑这一条**，否则 git 钩子不生效，commit message 与格式检查都不会拦你。

> `git-build-hook-maven-plugin` 在 `validate` 阶段把 `.githooks/` 装为仓库钩子（`core.hooksPath`）。
> 本地钩子可被 `--no-verify` 绕过，CI 端会重复校验一次。

## 跑起来

```bash
mvn validate                    # ① 装 git 钩子（仅首次）
docker compose up -d mysql      # ② 起 MySQL
mvn verify                      # ③ 编译 + 单测 + 集成测试
mvn -pl mp-gateway spring-boot:run   # ④ 启动（单进程，其余模块 injvm 调用）
```

冒烟验证：

```bash
curl http://localhost:8080/smoke/BZ001
# {"code":0,"data":{"bizNo":"BZ001","chain":["gateway","benefit-order","reward","mock"]},"traceId":"..."}
```

## 环境要求

| 项 | 版本 |
| --- | --- |
| JDK | 21 |
| Maven | 3.9+ |
| Docker Desktop | 最新（Apple Silicon 需先装 Rosetta） |

**装环境前先读 [`docs/营销活动平台-环境与依赖.md`](docs/营销活动平台-环境与依赖.md) §1.1**，里面记了三个实测踩过的坑：Homebrew 换清华源、Docker 要 Rosetta、镜像加速必配。照着装能少走弯路。

## 常用命令

| 命令 | 作用 |
| --- | --- |
| `mvn spotless:apply` | 格式化代码，**提交前必跑** |
| `mvn spotless:check` | 检查格式（CI 会跑） |
| `mvn test` | 单测（`*Test.java`） |
| `mvn verify` | 单测 + 集成测试 + 格式检查，**合入前必跑** |
| `mvn dependency:tree -Dverbose` | 排查版本冲突 |
| `docker compose down -v` | 删库重建，验证 Flyway 从零可跑 |

## 工程结构

```
mp-common/            结果码、四分类枚举、单号生成、幂等键工具、异常
mp-api/               对外接口定义（按服务拆 5 个子 module）
mp-gateway/           接入层，V0/V1 的单进程启动入口
mp-activity/          公共能力：活动配置、资格决策
mp-reward/            公共能力：统一发奖
mp-fission/           玩法：裂变（V3 填充）
mp-benefit-order/     玩法：权益售卖
mp-mock-downstream/   mock 支付、mock 供应方
```

**依赖方向由 `pom.xml` 编译期强制**：上层可依赖下层（可跨层），下层不得依赖上层，同层不互调。

## 文档

| 文档 | 回答什么 |
| --- | --- |
| [技术方案](docs/营销活动平台-技术方案.md) | 怎么设计的、为什么这么设计。**终态**，不随阶段修改 |
| [分阶段方案](docs/营销活动平台-分阶段方案.md) | 每阶段从终态里裁哪一块、做到什么程度算完 |
| [开发规范](docs/营销活动平台-开发规范.md) | 提交、分支、建表、幂等键、review 的项目专属约定 |
| [环境与依赖](docs/营销活动平台-环境与依赖.md) | 用什么版本、怎么跑起来 |
| [PRD](docs/营销活动平台-PRD.md) | 业务需求 |

改完文档跑 `python3 docs/check-docs.py`，17 项机器可核对的一致性会自动回归。

## 当前阶段

**V0 · 工程骨架** —— 不写业务代码，只验证构建、CI、测试基础设施能跑通。

`/smoke` 链路与 `smoke_record` 表是脚手架，V1 结束时删除。

阶段划分与退出标准见[分阶段方案](docs/营销活动平台-分阶段方案.md)。

## 提交规范

[Conventional Commits 1.0.0](https://www.conventionalcommits.org/zh-hans/v1.0.0/)：

```
feat(benefit): 实现支付回调与主单条件更新
fix(reward): 修正重试时重新生成 opNo 导致的重复发放
```

编码规范以《阿里巴巴 Java 开发手册》黄山版为准，格式由 Spotless（AOSP 风格）统一。
