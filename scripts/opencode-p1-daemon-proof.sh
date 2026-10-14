#!/data/data/com.termux/files/usr/bin/bash
#
# OpenCode P1 proof (see OPENCODE.md, "P1").
#
# Proves, on-device and without rebuilding the APK, the exact startup/auth path
# OpenCodeDaemon + OpenCodeDaemonService + OpenCodeClientActivity implement:
#   1. an app-owned `opencode serve` instance starts on loopback with a clean
#      glibc environment and prints its URL and password;
#   2. the printed URL/password are parsed with the same patterns as
#      OpenCodeDaemon, and the instance serves its own web UI;
#   3. `POST /api/pair` with HTTP Basic `opencode:<password>` returns a code;
#   4. a browser Accept on /auth/connect/<code> yields a session cookie that
#      authenticates the API (the flow the client re-runs after a restart);
#   5. the process stops cleanly on SIGTERM (what OpenCodeDaemon.stop() sends).
#
# Re-runnable. Read-only apart from the server it starts and stops.

set -u

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
TMPDIR="${TMPDIR:-$PREFIX/tmp}"
BIN="$HOME_DIR/.opencode/bin/opencode"
[ -x "$BIN" ] || BIN="$PREFIX/bin/opencode"

pass=0
fail=0
ok()  { printf '  PASS  %s\n' "$1"; pass=$((pass + 1)); }
bad() { printf '  FAIL  %s\n' "$1"; fail=$((fail + 1)); }

command -v curl >/dev/null 2>&1 || { echo "curl: not found"; exit 1; }
[ -x "$BIN" ] || { echo "opencode binary: not found ($BIN)"; exit 1; }

log="$TMPDIR/oc-p1-daemon.$$.log"
jar="$TMPDIR/oc-p1-cookies.$$"
pid=""
cleanup() {
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null
        sleep 1
        kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null
    fi
    rm -f "$jar"
}
trap cleanup EXIT

echo "== OpenCode P1 daemon proof =="
echo "binary: $BIN"
echo "version: $("$BIN" --version 2>/dev/null)"

# 1. Start an app-owned server (exactly like OpenCodeDaemon) -------------------
# Clean env: no LD_PRELOAD/LD_LIBRARY_PATH, so the glibc-patched binary runs;
# --port 0 lets the OS pick a free port, which the server then reports.
env -i \
    PATH="$PREFIX/bin:/system/bin" \
    HOME="$HOME_DIR" \
    TMPDIR="$TMPDIR" \
    LANG=C.UTF-8 \
    OPENCODE_DISABLE_AUTOUPDATE=1 \
    "$BIN" serve --hostname 127.0.0.1 --port 0 >"$log" 2>&1 &
pid=$!

url=""
password=""
for _ in $(seq 1 40); do
    url="$(grep -o 'server listening on https\?://[^ ]*' "$log" 2>/dev/null | head -1 | awk '{print $4}')"
    password="$(grep -o 'server password [^ ]*' "$log" 2>/dev/null | head -1 | awk '{print $3}')"
    [ -n "$url" ] && [ -n "$password" ] && break
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.5
done

if [ -n "$url" ] && [ -n "$password" ]; then
    ok "app-owned daemon started ($url)"
else
    bad "daemon did not report url+password"; echo "--- log ---"; cat "$log"; echo "PASS=$pass FAIL=$fail"; exit 1
fi

# 2. Web UI -------------------------------------------------------------------
title="$(curl -s -m 5 "$url/" 2>/dev/null | grep -o '<title>[^<]*</title>' | head -1)"
case "$title" in
    *OpenCode*) ok "web ui served ($title)" ;;
    *)          bad "web ui title ($title)" ;;
esac

# 3. Pair with Basic auth (requestPairCode in OpenCodeClientActivity) ---------
auth_header="Authorization: Basic $(printf 'opencode:%s' "$password" | base64 | tr -d '\n')"
pair_json="$(curl -s -m 5 -X POST -H "$auth_header" -H 'Accept: application/json' "$url/api/pair" 2>/dev/null)"
code="$(printf '%s' "$pair_json" | sed -n 's/.*"code":"\([^"]*\)".*/\1/p')"
if [ -n "$code" ]; then
    ok "POST /api/pair with Basic auth -> code"
else
    bad "pair failed ($pair_json)"
fi

# 4. Redeem the code for a cookie, then use the cookie ------------------------
if [ -n "$code" ]; then
    rm -f "$jar"
    status="$(curl -s -m 5 -c "$jar" -H 'Accept: text/html' -o /dev/null \
        -w '%{http_code}' "$url/auth/connect/$code" 2>/dev/null)"
    info="$(curl -s -m 5 -b "$jar" "$url/api/info" 2>/dev/null)"
    case "$info" in
        *'"capabilities"'*) ok "connect HTTP $status; cookie authenticates API" ;;
        *)                  bad "cookie auth failed (connect HTTP $status)" ;;
    esac
fi

# 5. Clean SIGTERM shutdown (OpenCodeDaemon.stop) -----------------------------
kill "$pid" 2>/dev/null
for _ in $(seq 1 10); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.5
done
if kill -0 "$pid" 2>/dev/null; then
    bad "daemon still alive after SIGTERM"
else
    ok "daemon stopped cleanly (SIGTERM)"
fi
pid=""

echo
echo "PASS=$pass FAIL=$fail"
[ "$fail" -eq 0 ]
