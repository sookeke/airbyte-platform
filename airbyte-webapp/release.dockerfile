ARG BUILD_IMAGE=airbyte-webapp:0.50.38
FROM ${BUILD_IMAGE} AS builder


FROM nginx:alpine AS release 

EXPOSE 8080

ARG SRC_DIR=/workspace/airbyte-webapp/build/app/build/app

COPY --from=builder ${SRC_DIR} /usr/share/nginx/html
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
