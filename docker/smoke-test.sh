#!/bin/sh
# Starts a built Ketch image and checks that it serves: the health check passes, the API asks for
# the token it created, the files it writes belong to PUID:PGID, and `docker stop` ends it
# cleanly. The release workflow runs it for each architecture before pushing the image.
#
# Usage: docker/smoke-test.sh <image> [platform]
# Single-quoted commands expand their variables in the container.
# shellcheck disable=SC2016
set -eu

image=$1
platform=${2:-}
name=ketch-smoke-$$
cleanup() {
  docker rm -f "$name" > /dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
  echo "FAIL: $*"
  docker logs "$name" 2>&1 | tail -n 40 || true
  exit 1
}

docker run --detach --name "$name" ${platform:+--platform "$platform"} \
  --env KETCH_NAME=Smoke "$image" > /dev/null

# Emulated architectures start slowly, so the health check gets two minutes.
status=starting
for _ in $(seq 1 60); do
  status=$(docker inspect --format '{{.State.Health.Status}}' "$name")
  [ "$status" = healthy ] && break
  [ "$(docker inspect --format '{{.State.Running}}' "$name")" = true ] || fail "the container exited"
  sleep 2
done
[ "$status" = healthy ] || fail "the health check reports $status"
echo "ok: healthy"

in_container() {
  docker exec "$name" sh -c "$1"
}

# The image has no HTTP client but ketch, so bash's /dev/tcp asks; Debian's sh is dash.
api_status() {
  docker exec "$name" bash -c \
    "exec 3<>/dev/tcp/127.0.0.1/8642 && printf 'GET $1 HTTP/1.0\r\n$2\r\n' >&3 && head -n 1 <&3" \
    | cut -d ' ' -f 2
}

[ "$(api_status /api/health '')" = 200 ] || fail "/api/health does not answer 200"
[ "$(api_status /api/status '')" = 401 ] || fail "/api/status answers without the token"
token=$(in_container 'cat "$KETCH_CONFIG_DIR/api-token"')
[ "$(api_status /api/status "Authorization: Bearer $token\r\n")" = 200 ] \
  || fail "/api/status refuses the token the server created"
echo "ok: API and token"

owners=$(in_container 'stat -c %u:%g "$KETCH_CONFIG_DIR/ketch.db" "$KETCH_CONFIG_DIR/api-token"' \
  | tr '\n' ' ')
[ "$owners" = "1000:1000 1000:1000 " ] || fail "config files belong to $owners"
[ "$(in_container 'stat -c %u /proc/1')" = 1000 ] || fail "ketch does not run as PUID"
echo "ok: runs as PUID:PGID"

docker stop --timeout 20 "$name" > /dev/null
docker logs "$name" 2>&1 | grep -q "Shutting down Ketch server" \
  || fail "docker stop did not run the shutdown hooks"
echo "ok: stops cleanly"
echo "Smoke test of $image${platform:+ ($platform)} passed"
