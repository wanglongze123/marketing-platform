# 五个服务共用一份镜像定义，靠 build arg 指定模块。
#
# 为什么不给每个服务写一份：构建步骤逐字相同，差别只有 jar 路径。五份重复的
# Dockerfile 意味着改基础镜像要改五处，而漏改一处的表现是「那个服务的 JDK
# 版本不一样」—— 跑得起来，只在某些行为上不同。
#
# ------------------------------------------------------------------
# 只拷 jar，不在容器里编译。
#
# 初版写的是多阶段构建（maven 镜像里跑 mvn package），实测单个服务超过 9 分钟
# 还没完成：容器内的 ~/.m2 是空的，dependency:go-offline 要把全部依赖重新下一遍，
# 而宿主机上它们早就在了。六个服务就是六次重复下载。
#
# 代价是构建前必须先在宿主机 mvn package —— 这一步本来也要做（CI 要跑测试），
# 且 compose 会在 jar 缺失时直接报错，不会悄悄打出一个空镜像。
# ------------------------------------------------------------------
FROM eclipse-temurin:21-jre

WORKDIR /app

ARG MODULE
# 取 -boot 后缀那个：repackage 配了 classifier，同目录下还有一个同名的普通库
# jar（几十 K，供模块间依赖）。通配 *.jar 会两个都匹配到，COPY 随即失败；
# 就算只匹配到一个，拿错了的表现是「no main manifest attribute」
COPY ${MODULE}/target/*-boot.jar app.jar

# 容器内时区与 MySQL 的 +8:00 对齐 —— 不一致会让 next_time 与库时钟差 8 小时，
# 任务要么立刻全部到期、要么永远不到期，而两者都不报错
ENV TZ=Asia/Shanghai

# 让 JVM 按容器 cgroup 限额算堆，而不是按宿主机物理内存 —— 后者在多容器部署下
# 会让每个 JVM 都以为自己独占整机
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
