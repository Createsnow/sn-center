# 构建上下文仅允许既有编译产物和部署脚本，先执行工程验证；不在镜像中下载源码或秘密。
FROM eclipse-temurin:21.0.12.1_1-jre-noble@sha256:0c324fbe2e1455c3159184717440f3b52906ef82a39c4a6efc9edb401d31971a
RUN groupadd --gid 10001 adtek && useradd --uid 10001 --gid 10001 --no-create-home adtek \
    && mkdir -p /app/var/files && chown -R 10001:10001 /app/var
WORKDIR /app
COPY backend/adtek-server/target/adtek-server-0.1.0-SNAPSHOT.jar /app/app.jar
COPY deploy/api/entrypoint.sh /app/entrypoint.sh
RUN chmod 0555 /app/entrypoint.sh && chmod 0444 /app/app.jar
USER 10001:10001
EXPOSE 18080
ENTRYPOINT ["/app/entrypoint.sh"]
