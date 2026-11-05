# Web 运行镜像：nginx 提供前端静态资源并把 /api 反向代理到 API 服务；TLS 在入口层（K8s Ingress / 负载均衡 / 主机反向代理）终止。
FROM nginxinc/nginx-unprivileged:stable-alpine@sha256:ed04ec1ff34502c339ee5c3ae3f855442398edc1d05591e2b98981dcbbd20b1e
COPY deploy/web/nginx.conf /etc/nginx/nginx.conf
COPY frontend/dist/ /usr/share/nginx/html/
EXPOSE 8080
ENTRYPOINT ["nginx", "-g", "daemon off;"]
