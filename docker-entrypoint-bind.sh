#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-app}"
DATA_DIR="${DATA_DIR:-/mnt/data}"
APP_ROOT="${APP_ROOT:-/opt/$APP_DIR}"
DATA_APP_ROOT="${WIZ_DATA_APP_DIR:-$DATA_DIR/$APP_DIR}"
APP_SEED_ROOT="${WIZ_SEED_DIR:-/opt/$APP_DIR.seed}"

has_contents() {
    local path="$1"
    [[ -d "$path" ]] || return 1
    find "$path" -mindepth 1 -maxdepth 1 ! -name lost+found -print -quit | grep -q .
}

copy_seed() {
    local seed_root="$1"
    local target_root="$2"
    local target_parent
    local temporary_root

    if [[ ! -d "$seed_root" ]]; then
        printf 'Seed directory not found: %s\n' "$seed_root" >&2
        exit 1
    fi

    target_parent="$(dirname "$target_root")"
    temporary_root="${target_root}.tmp.$$"

    rm -rf "$temporary_root"
    mkdir -p "$target_parent" "$temporary_root"
    cp -a "$seed_root"/. "$temporary_root"/
    rm -rf "$target_root"
    mv "$temporary_root" "$target_root"
}

ensure_link() {
    local link_path="$1"
    local target_path="$2"

    mkdir -p "$(dirname "$link_path")"

    if [[ -L "$link_path" ]]; then
        ln -sfn "$target_path" "$link_path"
        return
    fi

    if [[ -e "$link_path" ]]; then
        printf 'Cannot create symlink because the path already exists: %s\n' "$link_path" >&2
        exit 1
    fi

    ln -s "$target_path" "$link_path"
}

mkdir -p "$DATA_DIR"

if ! has_contents "$DATA_APP_ROOT"; then
    copy_seed "$APP_SEED_ROOT" "$DATA_APP_ROOT"
elif [[ ! -f "$DATA_APP_ROOT/config/wiz.yml" ]]; then
    printf 'Persisted directory is not a WIZ Spring workspace: %s\n' "$DATA_APP_ROOT" >&2
    exit 1
fi

ensure_link "$APP_ROOT" "$DATA_APP_ROOT"

exec /docker-entrypoint.sh "$@"
