#!/bin/sh
# Tests the image's entrypoint: builds docker/Dockerfile around a stub `ketch` that reports the
# user, group, umask and arguments it runs with, and checks them for each way the image starts.
# Needs only Docker. Usage: docker/test-entrypoint.sh
# Single-quoted commands expand their variables in the container.
# shellcheck disable=SC2016
set -eu

here=$(cd "$(dirname "$0")" && pwd)
image=ketch-entrypoint-test
work=$(mktemp -d)
cleanup() {
  rm -rf "$work"
  docker volume rm "$image-config" "$image-empty" "$image-media" > /dev/null 2>&1 || true
  docker image rm -f "$image" > /dev/null 2>&1 || true
}
trap cleanup EXIT

arch=$(docker version --format '{{.Server.Arch}}')
mkdir -p "$work/context/build/linux-$arch/licenses"
cp "$here/Dockerfile" "$here/entrypoint.sh" "$work/context/"
cat > "$work/context/build/linux-$arch/ketch" <<'STUB'
#!/bin/sh
echo "uid=$(id -u) gid=$(id -g) umask=$(umask) home=$HOME args=$*"
STUB
docker build --quiet --tag "$image" "$work/context" > /dev/null

failures=0
check() {
  description=$1
  expected=$2
  shift 2
  actual=$(docker run --rm "$@" 2>&1 | tail -n 1)
  if [ "$actual" = "$expected" ]; then
    echo "ok: $description"
  else
    echo "FAIL: $description"
    echo "  expected: $expected"
    echo "  actual:   $actual"
    failures=$((failures + 1))
  fi
}

check "starts the server as PUID:PGID" \
  "uid=1000 gid=1000 umask=0022 home=/config args=server" "$image"
check "takes PUID, PGID and UMASK" \
  "uid=568 gid=568 umask=0002 home=/config args=server" \
  -e PUID=568 -e PGID=568 -e UMASK=002 "$image"
check "runs as the user Docker starts it as" \
  "uid=1234 gid=1234 umask=0022 home=/ args=server" --user 1234:1234 "$image"
check "PUID=0 keeps root" "uid=0 gid=0 umask=0022 home=/root args=server" -e PUID=0 "$image"
check "passes options to ketch server" \
  "uid=1000 gid=1000 umask=0022 home=/config args=server --port 9000" "$image" --port 9000
check "runs other ketch commands" \
  "uid=1000 gid=1000 umask=0022 home=/config args=health" "$image" health
check "runs other programs as they are" "plain" "$image" echo plain
check "refuses non-numeric IDs" \
  "ketch-entrypoint: PUID and PGID must be numeric user and group IDs, not 'me' and '1000'" \
  -e PUID=me "$image"

# The config folder becomes the user's, an empty download folder Docker created too, but a
# folder with files keeps its owner. Volumes, as bind mounts of macOS folders fake ownership.
for volume in config empty media; do
  docker volume create "$image-$volume" > /dev/null
done
docker run --rm -v "$image-config:/config" -v "$image-media:/media" debian:trixie-slim sh -c \
  'mkdir /config/torrent-state && touch /config/ketch.db /media/movie.mkv'
docker run --rm -v "$image-config:/config" -v "$image-empty:/downloads" "$image" > /dev/null
check "gives the config folder and an empty download folder to the user" \
  "1000:1000 1000:1000 1000:1000" \
  -v "$image-config:/config" -v "$image-empty:/downloads" debian:trixie-slim sh -c \
  'echo $(stat -c %u:%g /config/ketch.db /config/torrent-state /downloads)'
check "warns about a download folder with files it cannot write to" \
  "ketch-entrypoint: /downloads is not writable by 1000:1000; set PUID and PGID to the owner of\
 the folder mounted there, or give them write access, or downloads will fail" \
  -v "$image-media:/downloads" --entrypoint sh "$image" -c \
  'ketch-entrypoint server 2>&1 >/dev/null'
check "leaves a download folder with files to its owner" "0:0" \
  -v "$image-media:/media" debian:trixie-slim stat -c %u:%g /media

if [ "$failures" -gt 0 ]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "All entrypoint checks passed"
