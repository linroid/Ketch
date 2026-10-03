#!/bin/sh
# Installs a Ketch update on macOS. Arguments: the app's process id, the new bundle, and the
# installed bundle. Waits up to a minute for the app to quit, copies the new bundle beside the
# installed one, swaps the two by renaming and opens the result: the new app or, after any
# failure, the old one.
pid="$1"; staged="$2"; app="$3"
trap '' HUP
echo "$(date): installing $staged over $app"
i=0
while kill -0 "$pid" 2>/dev/null; do
  i=$((i + 1))
  if [ "$i" -gt 600 ]; then echo "Ketch did not quit"; exit 1; fi
  sleep 0.1
done
dir=$(dirname "$app")
name=$(basename "$app" .app)
new="$dir/.$name-update.app"
old="$dir/.$name-previous.app"
rm -rf "$new" "$old"
if ditto "$staged" "$new" && mv "$app" "$old"; then
  if mv "$new" "$app"; then
    echo "installed"
    rm -rf "$old"
    # Launch Services notices the new bundle by its date.
    touch "$app"
  else
    mv "$old" "$app"
  fi
fi
rm -rf "$new" "$staged"
open "$app"
