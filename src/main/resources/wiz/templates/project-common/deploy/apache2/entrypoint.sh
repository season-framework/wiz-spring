#!/bin/sh
set -eu

: "${API_PREFIX:=/api}"
: "${BACKEND_HOST:=backend}"
: "${BACKEND_PORT:=8080}"
export API_PREFIX BACKEND_HOST BACKEND_PORT
envsubst '${API_PREFIX} ${BACKEND_HOST} ${BACKEND_PORT}' \
  < /usr/local/apache2/conf/extra/wiz.conf.template \
  > /usr/local/apache2/conf/extra/wiz.conf
exec "$@"
