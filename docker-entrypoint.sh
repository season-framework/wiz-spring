#!/usr/bin/env bash
set -euo pipefail

APP_ROOT="${APP_ROOT:-/opt/${APP_DIR:-app}}"

start_sshd() {
    local enabled="${WIZ_ENABLE_SSH:-true}"

    case "${enabled,,}" in
        true|1|yes)
            mkdir -p /run/sshd
            ssh-keygen -A

            if [[ -n "${SSH_PUBLIC_KEY:-}" ]]; then
                install -d -m 0700 /root/.ssh
                printf '%s\n' "$SSH_PUBLIC_KEY" > /root/.ssh/authorized_keys
                chmod 0600 /root/.ssh/authorized_keys
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

start_sshd

if [[ ! -d "$APP_ROOT" ]]; then
    printf 'WIZ Spring workspace not found: %s\n' "$APP_ROOT" >&2
    exit 1
fi

cd "$APP_ROOT"

if [[ $# -eq 0 || "$1" == "serve" ]]; then
    if [[ $# -gt 0 ]]; then
        shift
    fi

    run_args=(
        run
        --root "$APP_ROOT"
        --host 0.0.0.0
        --port "${WIZ_PORT:-3000}"
    )
    if [[ -n "${WIZ_PROFILE:-}" ]]; then
        run_args+=(--profile "$WIZ_PROFILE")
    fi

    exec wiz-spring "${run_args[@]}" "$@"
fi

exec "$@"
