#!/bin/sh
# Entry point of the Ketch Docker image.
#
# Started as root, the default, it gives the config folder to PUID:PGID and runs ketch as that
# user, so downloads belong to a user of the NAS rather than to root. Started as another user
# (compose `user:`, or the apps user TrueNAS runs custom apps as), it runs ketch as that user.
# UMASK sets the permissions of the files ketch creates.
#
# The arguments are ketch's: none, or options alone, start `ketch server`; any other program,
# such as `sh`, runs as it is, for debugging.
set -eu

case "${1:-}" in
  "") set -- ketch server ;;
  -*) set -- ketch server "$@" ;;
  server | health | mcp | ai-discover | update | *://* | magnet:*) set -- ketch "$@" ;;
esac

log() {
  echo "ketch-entrypoint: $*" >&2
}

if [ -n "${UMASK:-}" ]; then
  umask "$UMASK"
fi

uid=${PUID:-1000}
gid=${PGID:-1000}
if [ "$(id -u)" != 0 ] || [ "$uid" = 0 ]; then
  exec "$@"
fi
case "$uid:$gid" in
  *[!0-9:]* | :* | *:)
    log "PUID and PGID must be numeric user and group IDs, not '$uid' and '$gid'"
    exit 2
    ;;
esac

config_dir=${KETCH_CONFIG_DIR:-/config}
download_dir=${KETCH_DOWNLOAD_DIR:-/downloads}
mkdir -p "$config_dir" "$download_dir"

# Names for the IDs, with the config folder as home, for the tools and libraries that look
# them up.
if ! getent group "$gid" > /dev/null; then
  echo "ketch:x:$gid:" >> /etc/group
fi
if ! getent passwd "$uid" > /dev/null; then
  echo "ketch:x:$uid:$gid:Ketch:$config_dir:/usr/sbin/nologin" >> /etc/passwd
fi

as_user() {
  setpriv --reuid="$uid" --regid="$gid" --clear-groups -- "$@"
}

# The config folder only holds Ketch's own files: settings, the task database, the access
# token and torrent state.
if ! find "$config_dir" \( ! -user "$uid" -o ! -group "$gid" \) -exec chown -h "$uid:$gid" {} +
then
  log "could not give $config_dir to $uid:$gid; Ketch may fail to save its settings and downloads"
fi

# The download folder is the user's: it is only taken over when Docker just created it, empty
# and owned by root, because nothing was mounted there.
if ! as_user test -w "$download_dir"; then
  if [ "$(stat -c %u "$download_dir")" = 0 ] && [ -z "$(ls -A "$download_dir")" ]; then
    chown "$uid:$gid" "$download_dir"
  else
    log "$download_dir is not writable by $uid:$gid; set PUID and PGID to the owner of the" \
      "folder mounted there, or give them write access, or downloads will fail"
  fi
fi

export HOME="$config_dir"
exec setpriv --reuid="$uid" --regid="$gid" --clear-groups -- "$@"
