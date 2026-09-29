FROM node:22-alpine AS builder
ARG NPM_REGISTRY=https://registry.npmjs.org
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --registry="${NPM_REGISTRY}"
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /src/dist /usr/share/nginx/html
EXPOSE 80
