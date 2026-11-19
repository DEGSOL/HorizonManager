#!/bin/sh
set -eu
export LC_ALL=C
. /etc/os-release
printf 'os=%s\nversion=%s\narch=%s\n' "${ID:-unknown}" "${VERSION_ID:-unknown}" "$(uname -m)"
printf 'memory_kb=%s\ndisk_kb=%s\ncpu=%s\n' "$(awk '/^MemTotal:/ {print $2}' /proc/meminfo)" "$(df -Pk /var/lib | awk 'END {print $4}')" "$(getconf _NPROCESSORS_ONLN)"
if [ "$(id -u)" = 0 ]; then echo privileges=root
elif command -v sudo >/dev/null && sudo -n -l /bin/bash >/dev/null 2>&1; then echo privileges=sudo
elif command -v sudo >/dev/null && id -nG | tr ' ' '\n' | grep -qx sudo; then echo privileges=password
else echo privileges=unknown; fi
if command -v docker >/dev/null; then
  echo docker=yes
  if docker compose version >/dev/null 2>&1; then echo compose=yes; fi
  if command -v timeout >/dev/null && timeout 4 docker ps --format '{{.Image}} {{.Names}}' 2>/dev/null | grep -qi horizon; then echo docker_horizon=yes; fi
fi
if [ "$(cat /proc/1/comm)" = systemd ]; then echo systemd=yes; fi
if command -v getent >/dev/null && timeout 5 getent ahosts github.com >/dev/null 2>&1; then echo dns=yes; fi
if [ -r /var/lib/horizon-manager-install/status ]; then
  cat /var/lib/horizon-manager-install/status
  if grep -qx 'install=running' /var/lib/horizon-manager-install/status &&
     ! systemctl is-active --quiet horizon-manager-install.service 2>/dev/null; then
    printf 'install=failed\ncode=29\n'
  fi
fi
if systemctl is-active --quiet horizon-manager.service 2>/dev/null; then echo agent=active; fi
for repo in "$HOME/Horizon" "$HOME/horizon" /opt/Horizon /opt/horizon /srv/Horizon /srv/horizon; do
  if [ -f "$repo/pyproject.toml" ] && [ -f "$repo/src/main.py" ] && grep -q '^name = "horizon"' "$repo/pyproject.toml"; then
    printf 'existing=%s\n' "$repo"
    break
  fi
done
