FROM nginxinc/nginx-unprivileged:stable-alpine@sha256:ed04ec1ff34502c339ee5c3ae3f855442398edc1d05591e2b98981dcbbd20b1e
COPY deploy/web/nginx.conf /etc/nginx/nginx.conf
COPY frontend/dist/ /usr/share/nginx/html/
EXPOSE 8443
ENTRYPOINT ["nginx", "-g", "daemon off;"]
