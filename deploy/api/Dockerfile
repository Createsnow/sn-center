# API 运行镜像：只复制已验证的可执行 JAR，不在镜像中编译、下载源码或写入秘密。
# 无状态、非 root、只读根文件系统可运行；配置来自环境变量，秘密来自 /run/secrets（K8s Secret 卷同样适用）。
FROM eclipse-temurin:21.0.12.1_1-jre-noble@sha256:0c324fbe2e1455c3159184717440f3b52906ef82a39c4a6efc9edb401d31971a
RUN groupadd --gid 10001 adtek && useradd --uid 10001 --gid 10001 --no-create-home adtek
WORKDIR /app
COPY --chmod=0444 backend/adtek-server/target/adtek-server-0.1.0.jar /app/app.jar
# configtree：/run/secrets/<变量名> 文件内容作为同名配置值，例如 ADTEK_DB_PASSWORD。
ENV SPRING_CONFIG_IMPORT=optional:configtree:/run/secrets/ \
    ADTEK_SERVER_ADDRESS=0.0.0.0 \
    ADTEK_SERVER_PORT=18100 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"
USER 10001:10001
EXPOSE 18100
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
