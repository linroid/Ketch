#!/bin/sh
# Installs a Ketch update on Linux. Arguments: the app's process id, the Debian package, and the
# launcher to open afterwards. Waits up to a minute for the app to quit, installs the package as
# root through polkit, which asks for the user's password, and opens the app, updated or not.
pid="$1"; package="$2"; app="$3"
trap '' HUP
echo "$(date): installing $package"
i=0
while kill -0 "$pid" 2>/dev/null; do
  i=$((i + 1))
  if [ "$i" -gt 600 ]; then echo "Ketch did not quit"; exit 1; fi
  sleep 0.1
done
if pkexec dpkg -i "$package"; then
  echo "installed"
else
  echo "dpkg failed or was cancelled"
fi
rm -f "$package"
"$app" >/dev/null 2>&1 &
