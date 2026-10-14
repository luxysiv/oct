#!/data/data/com.termux/files/usr/bin/bash
#
# OpenCode P0 proof (see OPENCODE.md, "P0").
#
# Proves the client half of the inverted architecture on-device, without
# rebuilding the APK:
#   1. the loopback OpenCode daemon answers and serves its own web UI;
#   2. a browser Accept on /auth/connect/<code> yields a session cookie that
#      authenticates the API (the same flow OpenCodeClientActivity uses);
#   3. OpenCode executes a shell command in the Termux environment via
#      POST /api/shell and returns the output;
#   4. OpenCode owns a persistent PTY (create / list / remove).
#
# Re-runnable. Read-only apart from the shell/pty it creates and removes.

set -u

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
pass=0
fail=0

ok()  { printf '  PASS  %s\n' "$1"; pass=$((pass + 1)); }
bad() { printf '  FAIL  %s\n' "$1"; fail=$((fail + 1)); }

command -v opencode >/dev/null 2>&1 || { echo "opencode: not found"; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "curl: not found"; exit 1; }

echo "== OpenCode P0 proof =="
echo "opencode: $(opencode --version 2>/dev/null)"

# 1. Daemon URL ---------------------------------------------------------------
url="$(opencode service status 2>/dev/null | tr -d '\r' | grep -o 'http://[^ ]*' | head -1)"
if [ -z "$url" ]; then
    echo "service not running; starting it…"
    opencode service start >/dev/null 2>&1
    sleep 2
    url="$(opencode service status 2>/dev/null | tr -d '\r' | grep -o 'http://[^ ]*' | head -1)"
fi
if [ -n "$url" ]; then ok "daemon url $url"; else bad "daemon url (service status empty)"; fi

# 2. Web UI -------------------------------------------------------------------
if [ -n "$url" ]; then
    title="$(curl -s -m 5 "$url/" 2>/dev/null | grep -o '<title>[^<]*</title>' | head -1)"
    case "$title" in
        *OpenCode*) ok "web ui served ($title)" ;;
        *)          bad "web ui title ($title)" ;;
    esac
fi

# 3. Browser pairing cookie ---------------------------------------------------
# The CLI holds the daemon auth context; a bare `POST /api/pair` is rejected
# (HTTP 401), so obtain the one-time connect URL the way the app does.
if [ -n "$url" ]; then
    connect="$(opencode pair 2>/dev/null | tr -d '\r' \
        | grep -o 'http://[^ ]*/auth/connect/[^ ]*' | head -1)"
    if [ -n "$connect" ]; then
        jar="$PREFIX/tmp/oc-p0-cookies.$$"
        status="$(curl -s -m 5 -c "$jar" -H 'Accept: text/html' -o /dev/null \
            -w '%{http_code}' "$connect" 2>/dev/null)"
        auth="$(curl -s -m 5 -b "$jar" -o /dev/null -w '%{http_code}' "$url/api/info" 2>/dev/null)"
        rm -f "$jar"
        if [ "$auth" = "200" ]; then
            ok "browser pairing: cookie authenticates API (connect HTTP $status)"
        else
            bad "cookie auth failed (connect HTTP $status, api/info HTTP $auth)"
        fi
    else
        bad "could not obtain pairing link from 'opencode pair'"
    fi
fi

# 4. Shell exec via API -------------------------------------------------------
shell_json="$(opencode api shell.create \
    --data '{"command":"echo OC_P0_SHELL_OK; id -u; pwd"}' 2>/dev/null)"
sid="$(printf '%s' "$shell_json" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')"
if [ -n "$sid" ]; then
    sleep 1
    out="$(opencode api shell.output --param id="$sid" 2>/dev/null)"
    case "$out" in
        *OC_P0_SHELL_OK*)
            line="$(printf '%s' "$out" | sed -n 's/.*"output":"\([^"]*\)".*/\1/p' | head -1)"
            ok "shell exec via API (${line%%\\n*})" ;;
        *) bad "shell output unexpected ($out)" ;;
    esac
    opencode api shell.remove --param id="$sid" >/dev/null 2>&1
else
    bad "shell.create failed ($shell_json)"
fi

# 5. Persistent PTY -----------------------------------------------------------
pty_json="$(opencode api pty.create \
    --data "{\"command\":\"$PREFIX/bin/bash\",\"args\":[\"-l\"],\"title\":\"p0-proof\"}" 2>/dev/null)"
ptid="$(printf '%s' "$pty_json" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')"
if [ -n "$ptid" ]; then
    listed="$(opencode api pty.list 2>/dev/null)"
    case "$listed" in
        *"$ptid"*) ok "persistent pty created + listed ($ptid)" ;;
        *)         bad "pty not listed ($listed)" ;;
    esac
    opencode api pty.remove --param ptyID="$ptid" >/dev/null 2>&1
else
    bad "pty.create failed ($pty_json)"
fi

echo
echo "PASS=$pass FAIL=$fail"
[ "$fail" -eq 0 ]
