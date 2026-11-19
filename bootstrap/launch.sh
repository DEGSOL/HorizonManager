#!/bin/bash
# Run only after the owner's explicit installation approval.
set -euo pipefail
umask 077
stage=${1:?}
[[ "$stage" =~ ^/tmp/horizon-manager\.[A-Za-z0-9]{10}$ ]]
[[ "$(id -u)" == 0 ]]
if systemctl is-active --quiet horizon-manager-install.service; then
  echo ALREADY_RUNNING
  exit 0
fi
install -d -m 700 /run/horizon-manager-install
exec 9>/run/horizon-manager-install/launch.lock
flock -x 9
if systemctl is-active --quiet horizon-manager-install.service; then
  echo ALREADY_RUNNING
  exit 0
fi
install -d -m 755 /var/lib/horizon-manager-install
install -m 700 "$stage/install.sh" /run/horizon-manager-install/install.sh
install -m 600 "$stage/bundle.zip" /run/horizon-manager-install/bundle.zip
install -m 600 "$stage/token" /run/horizon-manager-install/token
systemd-run --quiet --collect --unit=horizon-manager-install \
  --property=TimeoutStartSec=35min --property=RuntimeMaxSec=35min \
  --property=StandardOutput=null --property=StandardError=null \
  /bin/bash /run/horizon-manager-install/install.sh
echo INSTALL_STARTED
