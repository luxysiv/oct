#!/data/data/com.termux/files/usr/bin/bash
#
# OpenCode P2 proof (see OPENCODE.md, "P2").
#
# Proves, on-device and without rebuilding the APK, the two P2 mechanisms:
#
#   A. Daemon unification. `opencode serve --service` (what OpenCodeDaemon now
#      runs) stays in the foreground, registers itself as the shared background
#      service, does NOT print its password, and OpenCodeDaemon's state-file read
#      recovers that password so Basic auth still works. SIGTERM unregisters it.
#
#   B. Tool bridge. The bundled termux-api MCP stdio server speaks the protocol
#      (initialize / tools/list / tools/call), frames exactly one JSON per line,
#      decodes string arguments (quotes / newlines), dispatches to the termux-*
#      commands and fails helpfully when termux-api is absent.
#
# Re-runnable. Uses a throwaway HOME so the real service registration is never
# touched.

set -u

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
TMPBASE="${TMPDIR:-$PREFIX/tmp}"
BIN="$HOME_DIR/.opencode/bin/opencode"
[ -x "$BIN" ] || BIN="$PREFIX/bin/opencode"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MCP="$SCRIPT_DIR/../app/src/main/assets/opencode/termux-api-mcp.sh"

pass=0
fail=0
ok()  { printf '  PASS  %s\n' "$1"; pass=$((pass + 1)); }
bad() { printf '  FAIL  %s\n' "$1"; fail=$((fail + 1)); }

command -v curl >/dev/null 2>&1 || { echo "curl: not found"; exit 1; }
[ -x "$BIN" ] || { echo "opencode binary: not found ($BIN)"; exit 1; }

SB="$TMPBASE/oc-p2-home.$$"
log="$TMPBASE/oc-p2-serve.$$.log"
mkdir -p "$SB"
pid=""
stub=""
cleanup() {
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null
        sleep 1
        kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null
    fi
    [ -n "$stub" ] && rm -rf "$stub"
    rm -rf "$SB" "$log"
}
trap cleanup EXIT

echo "== OpenCode P2 proof =="
echo "binary: $BIN"
echo "version: $("$BIN" --version 2>/dev/null)"

run_sandbox() {
    env -i PATH="$PREFIX/bin:/system/bin" HOME="$SB" TMPDIR="$TMPBASE" \
        LANG=C.UTF-8 OPENCODE_DISABLE_AUTOUPDATE=1 "$BIN" "$@"
}

# ===========================================================================
# A. Daemon unification: serve --service
# ===========================================================================
echo
echo "A. Daemon unification (serve --service)"

env -i PATH="$PREFIX/bin:/system/bin" HOME="$SB" TMPDIR="$TMPBASE" \
    LANG=C.UTF-8 OPENCODE_DISABLE_AUTOUPDATE=1 \
    "$BIN" serve --service --hostname 127.0.0.1 --port 0 >"$log" 2>&1 &
pid=$!

url=""
for _ in $(seq 1 40); do
    url="$(grep -o 'server listening on https\?://[^ ]*' "$log" 2>/dev/null | head -1 | awk '{print $4}')"
    [ -n "$url" ] && break
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.5
done

if [ -n "$url" ] && kill -0 "$pid" 2>/dev/null; then
    ok "app-owned service started in the foreground ($url)"
else
    bad "service did not start"; echo "--- log ---"; cat "$log"; echo "PASS=$pass FAIL=$fail"; exit 1
fi

if grep -q 'server password' "$log"; then
    bad "service mode printed a password line (expected it only in the state file)"
else
    ok "service mode prints no password (recovered from the state file)"
fi

state="$SB/.local/state/opencode/service.json"
state_url="$(sed -n 's/.*"url":"\([^"]*\)".*/\1/p' "$state" 2>/dev/null)"
password="$(sed -n 's/.*"password":"\([^"]*\)".*/\1/p' "$state" 2>/dev/null)"
if [ "$state_url" = "$url" ] && [ -n "$password" ]; then
    ok "state file registers this instance with url + password"
else
    bad "state file missing/mismatched (url='$state_url' pw='${password:0:4}…')"
fi

status="$(run_sandbox service status 2>&1 | head -1)"
if [ "$status" = "$url" ]; then
    ok "opencode service status routes clients to this instance"
else
    bad "service status did not report the instance ('$status')"
fi

auth_header="Authorization: Basic $(printf 'opencode:%s' "$password" | base64 | tr -d '\n')"
info="$(curl -s -m 5 -H "$auth_header" "$url/api/info" 2>/dev/null)"
case "$info" in
    *'"capabilities"'*) ok "state-file password authenticates the API (Basic auth)" ;;
    *)                  bad "Basic auth with state password failed ($info)" ;;
esac

pair="$(curl -s -m 5 -X POST -H "$auth_header" -H 'Accept: application/json' "$url/api/pair" 2>/dev/null)"
case "$pair" in
    *'"code"'*) ok "POST /api/pair still works through the service" ;;
    *)          bad "pair failed ($pair)" ;;
esac

kill "$pid" 2>/dev/null
for _ in $(seq 1 10); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.5
done
ok "service stopped cleanly (SIGTERM)"
pid=""

sleep 1
if [ -f "$state" ]; then
    bad "state registration left behind after stop"
else
    ok "state registration removed on stop"
fi

# ===========================================================================
# B. Tool bridge: termux-api MCP stdio server
# ===========================================================================
echo
echo "B. Termux:API MCP bridge ($MCP)"

if [ ! -f "$MCP" ]; then
    bad "bridge script missing ($MCP)"
else
    # initialize + notifications/initialized + ping
    resp="$(printf '%s\n' \
        '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"opencode","version":"2"}}}' \
        '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
        '{"jsonrpc":"2.0","id":2,"method":"ping"}' \
        | bash "$MCP")"
    case "$resp" in
        *'"serverInfo"'*'"name":"termux-api"'*) ok "initialize handshake" ;;
        *) bad "initialize handshake ($resp)" ;;
    esac
    case "$resp" in
        *notifications*) bad "replied to a notification" ;;
        *) ok "notifications are not answered" ;;
    esac
    case "$resp" in
        *'"id":2,"result":{}'*) ok "ping answered" ;;
        *) bad "ping not answered" ;;
    esac

    # tools/list: one line, advertises the tools
    tl="$(printf '%s\n' '{"jsonrpc":"2.0","id":3,"method":"tools/list"}' | bash "$MCP")"
    lines="$(printf '%s\n' "$tl" | wc -l | tr -d ' ')"
    case "$tl" in
        *'"termux_notification"'*'"termux_clipboard_set"'*'"termux_battery_status"'*)
            ok "tools/list advertises the bridge tools" ;;
        *) bad "tools/list content ($(printf '%s' "$tl" | cut -c1-80))" ;;
    esac
    if [ "${lines:-0}" -eq 1 ]; then
        ok "tools/list is framed as one JSON line"
    else
        bad "tools/list spans $lines lines"
    fi

    # tools/call dispatch + argument decoding
    stub="$(mktemp -d)"
    out="$stub/out"
    printf '#!%s\nprintf "%%s" "$1" > "%s"\n' "$PREFIX/bin/bash" "$out" \
        > "$stub/termux-clipboard-set"
    chmod +x "$stub/termux-clipboard-set"

    cresp="$(printf '%s\n' \
        '{"jsonrpc":"2.0","id":"c1","method":"tools/call","params":{"name":"termux_clipboard_set","arguments":{"text":"a \"b\"\nline2\ttab"}}}' \
        | PATH="$stub:$PATH" bash "$MCP")"
    case "$cresp" in
        *'"isError":false'*) ok "tools/call dispatches to the termux-* command" ;;
        *) bad "tools/call dispatch ($cresp)" ;;
    esac
    expected="$(printf 'a "b"\nline2\ttab')"
    actual="$(cat "$out" 2>/dev/null)"
    if [ "$actual" = "$expected" ]; then
        ok "string argument decoded (quotes, newline, tab)"
    else
        bad "argument decoding (got: $(printf '%s' "$actual" | cat -A))"
    fi

    # unknown tool -> JSON-RPC error
    uresp="$(printf '%s\n' '{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"nope","arguments":{}}}' | bash "$MCP")"
    case "$uresp" in
        *'"error"'*'-32602'*) ok "unknown tool rejected with -32602" ;;
        *) bad "unknown tool handling ($uresp)" ;;
    esac

    # termux-api missing -> helpful error (only when it really is missing)
    if ! command -v termux-battery-status >/dev/null 2>&1; then
        mresp="$(printf '%s\n' '{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"termux_battery_status","arguments":{}}}' | bash "$MCP")"
        if printf '%s' "$mresp" | grep -q '"isError":true' && printf '%s' "$mresp" | grep -q 'termux-api'; then
            ok "missing termux-api -> actionable error"
        else
            bad "missing termux-api handling ($mresp)"
        fi
    else
        ok "termux-api present: skipping the missing-command check"
    fi
fi

# ===========================================================================
# C. End-to-end: opencode loads and connects the registered bridge
# ===========================================================================
echo
echo "C. End-to-end registration (opencode -> MCP server)"

: > "$log"
run_sandbox mcp add --global termux-api -- "$PREFIX/bin/bash" "$MCP" >/dev/null 2>&1
cfg="$(cat "$SB/.config/opencode/opencode.json" 2>/dev/null)"
if printf '%s' "$cfg" | grep -q "$PREFIX/bin/bash"; then
    ok "mcp add registered an explicit interpreter ($PREFIX/bin/bash)"
else
    bad "mcp add did not register the interpreter ($cfg)"
fi

env -i PATH="$PREFIX/bin:/system/bin" HOME="$SB" TMPDIR="$TMPBASE" \
    LANG=C.UTF-8 OPENCODE_DISABLE_AUTOUPDATE=1 "$BIN" serve --service --hostname 127.0.0.1 --port 0 \
    >"$log" 2>&1 &
pid=$!
url=""
for _ in $(seq 1 40); do
    url="$(grep -o 'server listening on https\?://[^ ]*' "$log" 2>/dev/null | head -1 | awk '{print $4}')"
    [ -n "$url" ] && break
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.5
done
state="$SB/.local/state/opencode/service.json"
password=""
for _ in $(seq 1 20); do
    state_url="$(sed -n 's/.*"url":"\([^"]*\)".*/\1/p' "$state" 2>/dev/null)"
    [ "$state_url" = "$url" ] && password="$(sed -n 's/.*"password":"\([^"]*\)".*/\1/p' "$state" 2>/dev/null)"
    [ -n "$password" ] && break
    sleep 0.5
done

if [ -n "$url" ] && [ -n "$password" ]; then
    auth_header="Authorization: Basic $(printf 'opencode:%s' "$password" | base64 | tr -d '\n')"
    mcp_resp=""
    for _ in $(seq 1 15); do
        mcp_resp="$(curl -s -m 5 -H "$auth_header" "$url/api/mcp?directory=$SB" 2>/dev/null)"
        case "$mcp_resp" in *'"status":"connected"'*) break ;; esac
        sleep 1
    done
    case "$mcp_resp" in
        *'"name":"termux-api"'*'"status":"connected"'*) ok "opencode connected the termux-api MCP server" ;;
        *) bad "opencode did not connect the MCP server ($mcp_resp)" ;;
    esac
else
    bad "sandbox service did not become reachable"
fi

kill "$pid" 2>/dev/null
for _ in $(seq 1 10); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.5
done
kill -9 "$pid" 2>/dev/null
pid=""

echo
echo "PASS=$pass FAIL=$fail"
[ "$fail" -eq 0 ]
