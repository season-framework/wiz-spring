#!/usr/bin/env bash
set -euo pipefail

APP_ROOT="${APP_ROOT:-/opt/app}"

start_sshd() {
    local enabled="${WIZ_ENABLE_SSH:-true}"

    case "${enabled,,}" in
        true|1|yes)
            install -d -m 0755 /run/sshd
            ssh-keygen -A

            if [[ -n "${SSH_PASSWORD:-}" ]]; then
                if [[ "$SSH_PASSWORD" == *:* || "$SSH_PASSWORD" == *$'\n'* ]]; then
                    printf 'SSH_PASSWORD must not contain a colon or newline\n' >&2
                    exit 1
                fi
                printf 'root:%s\n' "$SSH_PASSWORD" | chpasswd
            fi

            /usr/sbin/sshd
            ;;
        false|0|no)
            ;;
        *)
            printf 'WIZ_ENABLE_SSH must be true or false\n' >&2
            exit 1
            ;;
    esac
}

if [[ ! -d "$APP_ROOT" || ! -s "$APP_ROOT/package.json" ]]; then
    printf 'WIZ Spring development workspace not found: %s\n' "$APP_ROOT" >&2
    exit 1
fi

start_sshd
unset SSH_PASSWORD
cd "$APP_ROOT"

if [[ $# -eq 0 || "$1" == "dev" || "$1" == "serve" ]]; then
    if [[ $# -gt 0 ]]; then
        shift
    fi
    exec npm run dev -- "$@"
fi

exec "$@"
