# Builds the dashboard, then serves it with nginx (running as a non-root user),
# which also forwards /api to OpsPilot.
FROM node:24.22.2-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.30.6-alpine
ENV OPSPILOT_API_URL=http://opspilot:8080
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist/opspilot-ui/browser /usr/share/nginx/html
EXPOSE 8080
