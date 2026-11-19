#!/bin/bash
set -euo pipefail
umask 077
export GIT_TERMINAL_PROMPT=0
export HOME=/var/lib/horizon-manager-runtime
export UV_CACHE_DIR="$HOME/uv-cache"
export UV_PROJECT_ENVIRONMENT="$HOME/venv"
export UV_PYTHON_DOWNLOADS=never
export UV_LINK_MODE=copy
export PYTHONDONTWRITEBYTECODE=1
commit=$(cat /opt/horizon-manager/horizon.commit)
[[ "$commit" =~ ^[a-f0-9]{40}$ ]]
checkout="$HOME/upstream-$commit"
mkdir -p "$checkout"
if [[ ! -d "$checkout/.git" ]]; then git -C "$checkout" init --quiet; fi
if ! git -C "$checkout" cat-file -e "$commit^{commit}" 2>/dev/null; then
  git -C "$checkout" fetch --quiet --depth 1 https://github.com/Thysrael/Horizon.git "$commit"
fi
git -C "$checkout" checkout --quiet --detach "$commit"
test "$(git -C "$checkout" rev-parse HEAD)" = "$commit"
cd "$checkout"
python3 -m venv "$HOME/build-tools"
"$HOME/build-tools/bin/pip" --disable-pip-version-check install --quiet --only-binary=:all: "uv==0.13.9"
uv="$HOME/build-tools/bin/uv"
"$uv" sync --frozen --no-dev --no-editable --python /usr/bin/python3
"$uv" pip install --python "$HOME/venv/bin/python" --no-deps --only-binary :all: -r /opt/horizon-manager/agent/requirements.txt
"$uv" pip check --python "$HOME/venv/bin/python"
cd /opt/horizon-manager/agent
"$HOME/venv/bin/python" -c 'from horizon_manager.api import REVISION; from src.processing.profiles import BUILTIN_PROFILES_DIR; assert BUILTIN_PROFILES_DIR.is_dir(); assert REVISION == open("/opt/horizon-manager/horizon.commit").read().strip()'
