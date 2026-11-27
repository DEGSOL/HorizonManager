#!/bin/bash
set -Eeuo pipefail
umask 077
export LC_ALL=C
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
export DEBIAN_FRONTEND=noninteractive
installer=/run/horizon-manager-install
progress=/var/lib/horizon-manager-install
app=/opt/horizon-manager
etc=/etc/horizon-manager
step=preflight
started=$(date -u +%FT%TZ)
operation=$(cat /proc/sys/kernel/random/uuid)

report() {
    local tmp
    tmp=$(mktemp "$progress/status.XXXXXXXX")
    printf 'install=%s\nstep=%s\ncode=%s\noperation=%s\nstarted=%s\nupdated=%s\n' \
      "$1" "$step" "${2:-0}" "$operation" "$started" "$(date -u +%FT%TZ)" > "$tmp"
    chmod 644 "$tmp"
    mv -f "$tmp" "$progress/status"
    cat "$progress/status" >> "$progress/events"
    chmod 644 "$progress/events"
    if [ "$(wc -l < "$progress/events")" -gt 600 ]; then
      tail -n 600 "$progress/events" > "$progress/events.tmp"
      chmod 644 "$progress/events.tmp"
      mv -f "$progress/events.tmp" "$progress/events"
    fi
}
rollback() {
    if [[ -z "${rollback_dir:-}" ]]; then return; fi
    systemctl stop horizon-manager.service
    rm -rf -- "$app/agent"
    cp -a "$rollback_dir/agent" "$app/agent"
    cp -a "$rollback_dir/horizon.commit" "$app/horizon.commit"
    cp -a "$rollback_dir/build-runtime.sh" "$app/build-runtime.sh"
    if [[ -f "$rollback_dir/bundle.sha256" ]]; then
        cp -a "$rollback_dir/bundle.sha256" "$app/bundle.sha256"
    else
        rm -f "$app/bundle.sha256"
    fi
    cp -a "$rollback_dir/horizon-manager.service" /etc/systemd/system/horizon-manager.service
    if [[ -n "${database_backup:-}" && -f "$database_backup" ]]; then
        copy_database "$database_backup" "$database"
    fi
    if [[ "${runtime_changed:-no}" == yes ]]; then build_runtime; fi
    systemctl daemon-reload
    systemctl start horizon-manager.service
}
copy_database() {
    python3 - "$1" "$2" <<'PY'
import sqlite3
import sys
from contextlib import closing
from pathlib import Path

source, target = map(Path, sys.argv[1:])
with closing(sqlite3.connect(source.resolve().as_uri() + "?mode=ro", uri=True)) as original:
    with closing(sqlite3.connect(target)) as copy:
        original.backup(copy)
PY
}

build_runtime() {
    systemd-run --quiet --wait --collect --unit="horizon-manager-build-$(cat /proc/sys/kernel/random/uuid)" \
      --property=TimeoutStartSec=20min --property=RuntimeMaxSec=20min \
      --property=DynamicUser=yes --property=User=horizon-manager-runtime \
      --property=StateDirectory=horizon-manager-runtime --property=StateDirectoryMode=0700 \
      --property=ProtectSystem=strict --property=ProtectHome=yes --property=NoNewPrivileges=yes \
      --property=PrivateTmp=yes --property=StandardOutput=null --property=StandardError=null \
      "$app/build-runtime.sh"
}

cleanup() {
    rm -f "$installer/token" "$installer/bundle.zip"
    if [[ -n "${staging:-}" ]]; then rm -rf -- "$staging"; fi
}
trap 'rc=$?; set +e; rollback; report failed "$rc"; cleanup; exit "$rc"' ERR
trap 'set +e; rollback; report failed 124; cleanup; exit 124' TERM INT
block() {
    rollback || true
    report failed "$1"
    cleanup
    exit "$1"
}
stage() { step="$1"; report running; }
stage preflight
. /etc/os-release
case "${ID:-}:${VERSION_ID:-}:$(uname -m)" in
  ubuntu:24.04:x86_64|ubuntu:24.04:aarch64|debian:12:x86_64|debian:12:aarch64) ;;
  *) block 21 ;;
esac
[[ "$(cat /proc/1/comm)" = systemd ]] || block 22
[[ "$(df -Pk /var/lib | awk 'END {print $4}')" -ge 1572864 ]] || block 23
[[ "$(awk '/^MemTotal:/ {print $2}' /proc/meminfo)" -ge 442368 ]] || block 24
grep -Eq '^[a-f0-9]{64}$' "$installer/token" || block 25

agent_get() {
    { printf 'header = "Authorization: Bearer '; tr -d '\n' < "$etc/api-token"; printf '"\n'; } | \
      curl --noproxy '*' -fsS --max-time 3 --config - "http://127.0.0.1:18777/v1/$1"
}
check_health() {
    agent_get health | python3 -c 'import json,sys; data=json.load(sys.stdin); assert data["api"] == 1 and data["horizon"] == sys.argv[1] and data["agent"] == sys.argv[2]' \
      "$(cat "$app/horizon.commit")" "$target_agent"
}
bundle_hash=$(sha256sum "$installer/bundle.zip" | cut -d' ' -f1)
target_agent=$(python3 - "$installer/bundle.zip" <<'PYCODE'
import re, sys
from zipfile import ZipFile
with ZipFile(sys.argv[1]) as archive:
    info = archive.getinfo("agent/horizon_manager/__init__.py")
    assert info.file_size <= 1000
    text = archive.read(info).decode()
    print(re.search(r'^VERSION = "([0-9]+\.[0-9]+\.[0-9]+)"$', text, re.M)[1])
PYCODE
)
upgrading=no
if [[ -f "$etc/api-token" ]]; then
    cmp -s "$etc/api-token" "$installer/token" || block 26
    if systemctl is-active --quiet horizon-manager.service && [[ -f "$app/horizon.commit" ]]; then
        if [[ -f "$app/bundle.sha256" && "$(cat "$app/bundle.sha256")" == "$bundle_hash" ]]; then
            stage health
            check_health || block 28
            report success
            cleanup
            exit 0
        fi
        upgrading=yes
    fi
fi

stage packages
missing=()
for package in ca-certificates curl git python3 python3-venv unzip iproute2; do
    if ! dpkg-query -W -f='${db:Status-Status}' "$package" 2>/dev/null | grep -qx installed; then missing+=("$package"); fi
done
if (( ${#missing[@]} )); then
    timeout 600 apt-get -o Acquire::Retries=2 -o Acquire::http::Timeout=25 update
    timeout 600 apt-get -o DPkg::Lock::Timeout=90 -o Acquire::Retries=2 -o Acquire::http::Timeout=25 install -y --no-install-recommends "${missing[@]}"
fi
if [[ "$upgrading" == no ]] && ss -H -ltn 'sport = :18777' | grep -q .; then block 27; fi

stage files
install -d -m 755 "$app"
install -d -m 700 "$etc"
staging=$(mktemp -d "$installer/payload.XXXXXXXX")
# Extract only the application files shipped in the APK.
python3 - "$installer/bundle.zip" "$staging" <<'PY'
import re
import sys
from pathlib import Path
from zipfile import ZipFile
with ZipFile(sys.argv[1]) as archive:
    for entry in archive.infolist():
        name = entry.filename
        if not re.fullmatch(r"(?:horizon\.commit|horizon-manager\.service|build-runtime\.sh|agent/requirements\.txt|agent/horizon_manager/[a-z_]+\.py)", name):
            continue
        if entry.file_size > 1_000_000:
            raise SystemExit("Oversized application file")
        dest = Path(sys.argv[2]) / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        with dest.open("xb") as output:
            output.write(archive.read(entry))
PY
if [[ "$upgrading" == yes ]]; then
    candidate_rollback="$staging/rollback"
    mkdir -m 700 "$candidate_rollback"
    cp -a "$app/agent" "$app/horizon.commit" "$app/build-runtime.sh" /etc/systemd/system/horizon-manager.service "$candidate_rollback/"
    if [[ -f "$app/bundle.sha256" ]]; then cp -a "$app/bundle.sha256" "$candidate_rollback/"; fi
    if cmp -s "$staging/horizon.commit" "$app/horizon.commit" &&
       cmp -s "$staging/agent/requirements.txt" "$app/agent/requirements.txt"; then
        reuse_runtime=yes
    fi
    agent_get 'jobs?limit=100&active=true' | python3 -c 'import json,sys; assert not any(x["state"] in {"queued","running"} for x in json.load(sys.stdin))' || block 30
    installed_agent=$(agent_get health | python3 -c 'import json,sys; print(json.load(sys.stdin)["agent"])')
    downgrade=$(python3 -c 'import sys; print(tuple(map(int, sys.argv[1].split("."))) > tuple(map(int, sys.argv[2].split("."))))' "$installed_agent" "$target_agent")
    rollback_dir="$candidate_rollback"
    systemctl stop horizon-manager.service
    if [[ "$downgrade" == True ]]; then
        database=/var/lib/horizon-manager-runtime/manager.sqlite3
        candidate_backup=$(mktemp "$progress/rollback-${installed_agent}-XXXXXXXX.sqlite3")
        if ! copy_database "$database" "$candidate_backup"; then
            rm -f -- "$candidate_backup"
            block 28
        fi
        database_backup="$candidate_backup"
    fi
fi
rm -rf -- "$app/agent"
install -d -m 755 "$app/agent/horizon_manager"
install -m 644 "$staging/horizon.commit" "$app/horizon.commit"
install -m 644 "$staging/agent/requirements.txt" "$app/agent/requirements.txt"
install -m 644 "$staging"/agent/horizon_manager/*.py "$app/agent/horizon_manager/"
install -m 755 "$staging/build-runtime.sh" "$app/build-runtime.sh"
if [[ ! -f "$etc/api-token" ]]; then install -m 600 "$installer/token" "$etc/api-token"; fi

if [[ "${reuse_runtime:-no}" != yes ]]; then
    stage runtime
    runtime_changed=yes
    build_runtime
fi

stage service
install -m 644 "$staging/horizon-manager.service" /etc/systemd/system/horizon-manager.service
systemctl daemon-reload
systemctl enable --now horizon-manager.service
stage health
for ((attempt=1; attempt<=25; attempt++)); do
    if check_health; then
        printf '%s\n' "$bundle_hash" > "$app/bundle.sha256"
        rollback_dir=
        report success
        cleanup
        exit 0
    fi
    sleep 1
done
block 28
