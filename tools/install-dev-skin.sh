#!/bin/sh
# Install a skin PNG into the dev-client resource pack.
#
# The dev client runs OFFLINE (--username Dev1), so it has no session to fetch a skin from and falls
# back to one of the nine vanilla default skins, chosen from the offline UUID. Rather than work out
# which one Dev1 lands on, this overwrites all nine in both arm widths — whichever the game reaches
# for, it gets your skin.
#
# Usage: sh tools/install-dev-skin.sh /path/to/skin.png
set -e
SRC="$1"
[ -f "$SRC" ] || { echo "usage: sh tools/install-dev-skin.sh <skin.png>"; exit 1; }
PACK="run/resourcepacks/dev-skin/assets/minecraft/textures/entity/player"
for arm in wide slim; do
  for name in steve alex ari efe kai makena noor sunny zuri; do
    cp "$SRC" "$PACK/$arm/$name.png"
  done
done
echo "installed $SRC into 18 default-skin slots"
