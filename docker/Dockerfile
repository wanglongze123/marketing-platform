# 五个服务共用一份 Dockerfile，靠 build arg 指定模块。
#
# 为什么不给每个服务写一份：它们的构建步骤逐字相同，差别只有 jar 路径。五份重复的
# Dockerfile 意味着改基础镜像要改五处，而漏改一处的表现是「那个服务的 JDK 版本不一样」
# —— 跑得起来，只在某些行为上不同。
#
# 用法（在仓库根目录执行，构建上下文是根目录）：
#   docker build -f docker/Dockerfile --build-arg MODULE=mp-benefit-order -t mp/benefit-order .

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# 先只拷 pom 做依赖预热：源码改动不会让依赖层失效，重建从分钟级降到秒级
COPY pom.xml .
COPY mp-common/pom.xml            mp-common/
COPY mp-api/pom.xml               mp-api/
COPY mp-api/mp-api-activity/pom.xml mp-api/mp-api-activity/
COPY mp-api/mp-api-benefit/pom.xml  mp-api/mp-api-benefit/
COPY mp-api/mp-api-fission/pom.xml  mp-api/mp-api-fission/
COPY mp-api/mp-api-mock/pom.xml     mp-api/mp-api-mock/
COPY mp-api/mp-api-reward/pom.xml   mp-api/mp-api-reward/
COPY mp-activity/pom.xml          mp-activity/
COPY mp-reward/pom.xml            mp-reward/
COPY mp-fission/pom.xml           mp-fission/
COPY mp-benefit-order/pom.xml     mp-benefit-order/
COPY mp-mock-downstream/pom.xml   mp-mock-downstream/
COPY mp-gateway/pom.xml           mp-gateway/
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY . .

ARG MODULE
# -am 连带构建依赖的模块；跳过测试与 git 钩子安装（容器里没有 .git 的写权限需求）
RUN mvn -B -pl ${MODULE} -am package -DskipTests -Dspotless.check.skip=true

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

ARG MODULE
# 取 -boot 后缀那个：repackage 配了 classifier，同目录下还有一个同名的普通库
# jar（只有几十 K，供模块间依赖用）。通配 *.jar 会两个都匹配到，COPY 随即失败；
# 就算只匹配到一个，拿错了那个的表现是「no main manifest attribute」
COPY --from=build /src/${MODULE}/target/*-boot.jar app.jar

# 容器内时区与 MySQL 的 +8:00 对齐 —— 不一致会让 next_time 与库时钟差 8 小时，
# 任务要么立刻全部到期、要么永远不到期，而两者都不报错
ENV TZ=Asia/Shanghai

# 让 JVM 按容器 cgroup 限额算堆，而不是按宿主机物理内存 —— 后者在多容器部署下
# 会让每个 JVM 都以为自己独占整机
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
