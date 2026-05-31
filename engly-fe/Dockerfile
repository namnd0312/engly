# syntax=docker/dockerfile:1
# ---- Build stage: produce the optimized Angular browser bundle ----
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build -- --configuration=production

# ---- Runtime stage: nginx serving static SPA + reverse-proxying /api ----
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
# Angular "application" builder emits to dist/frontend/browser
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
EXPOSE 80
