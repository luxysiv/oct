#!/data/data/com.termux/files/usr/bin/bash
#
# termux-api-mcp.sh - a tiny Model Context Protocol (MCP) stdio server that
# exposes a curated set of Termux:API commands to OpenCode as tools.
#
# It speaks newline-delimited JSON-RPC 2.0 (the MCP "stdio" transport): one
# JSON message per line on stdin, one reply per line on stdout. It depends on
# nothing beyond bash and awk, both of which ship with Termux.
#
# At *call* time each tool needs the `termux-api` package (which provides the
# `termux-*` commands) plus the Termux:API companion app. When they are missing
# the tool returns a readable error instead of breaking the session.

set -u

SERVER_NAME="termux-api"
SERVER_VERSION="1.0.0"
PROTOCOL_VERSION="2024-11-05"

# Make sure the Termux toolchain is reachable even under the daemon's minimal
# environment (the app runs the server with PATH=$PREFIX/bin:/system/bin).
: "${PREFIX:=/data/data/com.termux/files/usr}"
case ":$PATH:" in
    *":$PREFIX/bin:"*) ;;
    *) PATH="$PREFIX/bin:$PATH"; export PATH ;;
esac

# Prefer gawk (GNU extensions such as strtonum) but fall back to awk.
AWK=$(command -v gawk 2>/dev/null || command -v awk 2>/dev/null || echo awk)

# ---------------------------------------------------------------------------
# JSON helpers (awk; no jq/ python needed).
# ---------------------------------------------------------------------------

# Raw value of a key, verbatim (strings keep their quotes, numbers stay bare).
json_raw() {
    local json=$1 key=$2
    printf '%s' "$json" | "$AWK" -v k="$key" '
        function rawval(s, key,   i, p, c, out) {
            i = index(s, "\"" key "\"")
            if (i == 0) return ""
            p = i + length(key) + 2
            while (p <= length(s) && substr(s, p, 1) != ":") p++
            p++
            while (p <= length(s) && substr(s, p, 1) == " ") p++
            if (substr(s, p, 1) == "\"") {
                out = "\""; p++
                while (p <= length(s)) {
                    c = substr(s, p, 1)
                    if (c == "\\") { out = out c substr(s, p + 1, 1); p += 2; continue }
                    out = out c; p++
                    if (c == "\"") break
                }
                return out
            }
            out = ""
            while (p <= length(s)) {
                c = substr(s, p, 1)
                if (c == "," || c == "}") break
                out = out c; p++
            }
            sub(/[ \t]+$/, "", out)
            return out
        }
        { print rawval($0, k) }
    '
}

# Decoded string value of a key (escapes resolved, quotes stripped).
json_str() {
    local json=$1 key=$2
    printf '%s' "$json" | "$AWK" -v k="$key" '
        function strval(s, key,   i, p, c, out, esc) {
            i = index(s, "\"" key "\"")
            if (i == 0) return ""
            p = i + length(key) + 2
            while (p <= length(s) && substr(s, p, 1) != ":") p++
            p++
            while (p <= length(s) && substr(s, p, 1) == " ") p++
            if (substr(s, p, 1) != "\"") return ""
            p++; out = ""
            while (p <= length(s)) {
                c = substr(s, p, 1)
                if (c == "\\") {
                    p++; esc = substr(s, p, 1)
                    if (esc == "n") out = out "\n"
                    else if (esc == "t") out = out "\t"
                    else if (esc == "r") out = out "\r"
                    else if (esc == "b") out = out "\b"
                    else if (esc == "f") out = out "\f"
                    else if (esc == "u") { out = out sprintf("%c", strtonum("0x" substr(s, p + 1, 4))); p += 4 }
                    else out = out esc
                    p++
                } else if (c == "\"") {
                    break
                } else {
                    out = out c; p++
                }
            }
            return out
        }
        { print strval($0, k) }
    '
}

# Numeric (or bare) value of a key, quotes stripped.
json_num() {
    local v
    v=$(json_raw "$1" "$2")
    printf '%s' "${v//\"/}"
}

json_escape() {
    local s=$1
    s=${s//\\/\\\\}
    s=${s//\"/\\\"}
    s=${s//$'\n'/\\n}
    s=${s//$'\r'/\\r}
    s=${s//$'\t'/\\t}
    printf '%s' "$s"
}

# ---------------------------------------------------------------------------
# JSON-RPC replies.
# ---------------------------------------------------------------------------

send() { printf '%s\n' "$1"; }

respond() { # $1 = id token, $2 = raw result JSON
    send "{\"jsonrpc\":\"2.0\",\"id\":$1,\"result\":$2}"
}

respond_text() { # $1 = id, $2 = text, $3 = isError (true/false)
    local esc iserr
    esc=$(json_escape "$2")
    iserr=${3:-false}
    respond "$1" "{\"content\":[{\"type\":\"text\",\"text\":\"$esc\"}],\"isError\":$iserr}"
}

respond_error() { # $1 = id, $2 = code, $3 = message
    local msg
    msg=$(json_escape "$3")
    send "{\"jsonrpc\":\"2.0\",\"id\":$1,\"error\":{\"code\":$2,\"message\":\"$msg\"}}"
}

require_cmd() { command -v "$1" >/dev/null 2>&1; }

missing_api() {
    respond_text "$1" "termux-api is not available. Run 'pkg install termux-api' and install the Termux:API app from F-Droid/Play, then retry." true
}

run_quiet() { "$@" >/dev/null 2>&1; }

# ---------------------------------------------------------------------------
# Tools.
# ---------------------------------------------------------------------------

TOOLS_JSON='[{"name":"termux_notification","description":"Post an Android notification via Termux:API. Useful to alert the user when a long task finishes.","inputSchema":{"type":"object","properties":{"title":{"type":"string","description":"Notification title."},"content":{"type":"string","description":"Notification body text."}},"required":["content"]}},{"name":"termux_toast","description":"Show a short Android toast popup on screen.","inputSchema":{"type":"object","properties":{"text":{"type":"string","description":"Text to show."}},"required":["text"]}},{"name":"termux_clipboard_get","description":"Read the current Android clipboard text.","inputSchema":{"type":"object","properties":{}}},{"name":"termux_clipboard_set","description":"Replace the Android clipboard text.","inputSchema":{"type":"object","properties":{"text":{"type":"string","description":"Text to place on the clipboard."}},"required":["text"]}},{"name":"termux_battery_status","description":"Get the device battery status as JSON (percentage, plugged, health, temperature).","inputSchema":{"type":"object","properties":{}}},{"name":"termux_vibrate","description":"Vibrate the device for a number of milliseconds.","inputSchema":{"type":"object","properties":{"duration_ms":{"type":"number","description":"Duration in milliseconds (default 1000)."}}}},{"name":"termux_tts_speak","description":"Speak text out loud with the device text-to-speech engine.","inputSchema":{"type":"object","properties":{"text":{"type":"string","description":"Text to speak."}},"required":["text"]}},{"name":"termux_location","description":"Get the device last known location (needs location permission granted to Termux:API).","inputSchema":{"type":"object","properties":{"provider":{"type":"string","description":"gps, network or passive (default: network)."},"request":{"type":"string","description":"once or last (default: last)."}}}}]'

handle_call() {
    local line=$1 id=$2 name out
    name=$(json_str "$line" "name")

    case "$name" in
        termux_notification)
            if ! require_cmd termux-notification; then missing_api "$id"; return; fi
            local title content
            title=$(json_str "$line" "title")
            content=$(json_str "$line" "content")
            if out=$(termux-notification --title "$title" --content "$content" 2>&1); then
                respond_text "$id" "Notification posted." false
            else
                respond_text "$id" "termux-notification failed: $out" true
            fi
            ;;
        termux_toast)
            if ! require_cmd termux-toast; then missing_api "$id"; return; fi
            local text
            text=$(json_str "$line" "text")
            if out=$(termux-toast "$text" 2>&1); then
                respond_text "$id" "Toast shown." false
            else
                respond_text "$id" "termux-toast failed: $out" true
            fi
            ;;
        termux_clipboard_get)
            if ! require_cmd termux-clipboard-get; then missing_api "$id"; return; fi
            if out=$(termux-clipboard-get 2>&1); then
                respond_text "$id" "${out:-<clipboard is empty>}" false
            else
                respond_text "$id" "termux-clipboard-get failed: $out" true
            fi
            ;;
        termux_clipboard_set)
            if ! require_cmd termux-clipboard-set; then missing_api "$id"; return; fi
            text=$(json_str "$line" "text")
            if out=$(termux-clipboard-set "$text" 2>&1); then
                respond_text "$id" "Clipboard updated." false
            else
                respond_text "$id" "termux-clipboard-set failed: $out" true
            fi
            ;;
        termux_battery_status)
            if ! require_cmd termux-battery-status; then missing_api "$id"; return; fi
            if out=$(termux-battery-status 2>&1); then
                respond_text "$id" "$out" false
            else
                respond_text "$id" "termux-battery-status failed: $out" true
            fi
            ;;
        termux_vibrate)
            if ! require_cmd termux-vibrate; then missing_api "$id"; return; fi
            local duration
            duration=$(json_num "$line" "duration_ms")
            [ -z "$duration" ] && duration=1000
            if out=$(termux-vibrate -d "$duration" 2>&1); then
                respond_text "$id" "Vibrated for ${duration}ms." false
            else
                respond_text "$id" "termux-vibrate failed: $out" true
            fi
            ;;
        termux_tts_speak)
            if ! require_cmd termux-tts-speak; then missing_api "$id"; return; fi
            text=$(json_str "$line" "text")
            if out=$(termux-tts-speak "$text" 2>&1); then
                respond_text "$id" "Speaking." false
            else
                respond_text "$id" "termux-tts-speak failed: $out" true
            fi
            ;;
        termux_location)
            if ! require_cmd termux-location; then missing_api "$id"; return; fi
            local provider request
            provider=$(json_str "$line" "provider")
            request=$(json_str "$line" "request")
            [ -z "$provider" ] && provider=network
            [ -z "$request" ] && request=last
            if out=$(termux-location -p "$provider" -r "$request" 2>&1); then
                respond_text "$id" "$out" false
            else
                respond_text "$id" "termux-location failed: $out" true
            fi
            ;;
        "" )
            respond_error "$id" -32602 "Missing tool name"
            ;;
        * )
            respond_error "$id" -32602 "Unknown tool: $name"
            ;;
    esac
}

# ---------------------------------------------------------------------------
# Main loop.
# ---------------------------------------------------------------------------

while IFS= read -r line; do
    [ -z "$line" ] && continue
    method=$(json_str "$line" "method")
    id=$(json_raw "$line" "id")

    case "$method" in
        initialize)
            ver=$(json_str "$line" "protocolVersion")
            [ -z "$ver" ] && ver="$PROTOCOL_VERSION"
            respond "$id" "{\"protocolVersion\":\"$(json_escape "$ver")\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"$SERVER_NAME\",\"version\":\"$SERVER_VERSION\"}}"
            ;;
        ping)
            respond "$id" "{}"
            ;;
        tools/list)
            respond "$id" "{\"tools\":$TOOLS_JSON}"
            ;;
        tools/call)
            handle_call "$line" "$id"
            ;;
        prompts/list)
            respond "$id" "{\"prompts\":[]}"
            ;;
        resources/list)
            respond "$id" "{\"resources\":[]}"
            ;;
        resources/templates/list)
            respond "$id" "{\"resourceTemplates\":[]}"
            ;;
        notifications/*)
            : # notifications never get a reply
            ;;
        "")
            : # ignore non-request noise
            ;;
        *)
            if [ -n "$id" ]; then respond_error "$id" -32601 "Method not found: $method"; fi
            ;;
    esac
done
