#!/data/data/com.termux/files/usr/bin/bash
#
# OpenCode TUI caret proof (see TOOLBAR_TUI.md, section "3b. Dòng caret do TUI tự khai báo").
#
# Proves, on-device and without rebuilding the APK, the terminal-byte contract that
# TermuxActivity.isTerminalCursorLineTap() / TuiClassifier.isComposerRowAt() rely on:
# the running opencode TUI manages the hardware cursor itself and, at the end of every
# repaint, parks it on its input caret and only then shows it:
#
#     ESC[?25l  ... ESC[<row>;<col>H  ESC[?25h
#               ^ draw sweep        ^ caret   ^ show
#
# Checks:
#   1. the TUI toggles the cursor (ESC[?25l / ESC[?25h) and uses synchronized output
#      (ESC[?2026h / ESC[?2026l), so the caret is not where a mid-redraw sample expects;
#   2. the bytes immediately before ESC[?25h are ESC[<row>;<col>H in the large majority
#      of frames - that destination is what TerminalEmulator.getDeclaredCursorRow()
#      captures;
#   3. while idle the declared row is one fixed row in the lower half of the screen;
#   4. after typing, the declared column moves (the caret follows the draft).
#
# Re-runnable. Only writes captures under $TMPDIR and stops the TUI it starts.

set -u

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
TMPDIR="${TMPDIR:-$PREFIX/tmp}"
BIN="$PREFIX/bin/opencode"
[ -x "$BIN" ] || BIN="$HOME_DIR/.opencode/bin/opencode"

# A small, fixed geometry keeps the caret row predictable.
ROWS=24
COLS=80

pass=0
fail=0
ok()  { printf '  PASS  %s\n' "$1"; pass=$((pass + 1)); }
bad() { printf '  FAIL  %s\n' "$1"; fail=$((fail + 1)); }

command -v script >/dev/null 2>&1 || { echo "script: not found"; exit 1; }
command -v stty   >/dev/null 2>&1 || { echo "stty: not found";   exit 1; }
[ -x "$BIN" ] || { echo "opencode binary: not found ($BIN)"; exit 1; }

# Optional hard cap so a TUI that refuses to exit cannot hang the proof.
# `script` survives a plain SIGTERM (it forwards it and keeps waiting), so escalate to KILL:
# that closes the pty master, the TUI gets SIGHUP and the shared service is released.
TIMEOUT=()
command -v timeout >/dev/null 2>&1 && TIMEOUT=(timeout --kill-after=3 25)

work="$TMPDIR/oc-tui-cursor.$$"
mkdir -p "$work"

# Run the TUI in its own PTY, feed it a script, capture the raw bytes.
# The TUI occasionally spends its first seconds waiting for the shared
# `opencode serve` daemon, so a capture without a repaint is retried once.
capture() {
    local log="$1" feeder="$2" attempt
    for attempt in 1 2; do
        ( eval "$feeder" ) | "${TIMEOUT[@]}" script -q -c "stty rows $ROWS cols $COLS; \"$BIN\"" "$log" >/dev/null 2>&1
        grep -aq "$h25" "$log" 2>/dev/null && return 0
        echo "  info  no repaint captured (attempt $attempt), retrying..."
        sleep 1
    done
    return 0
}

esc=$'\033'
h25="$esc\\[?25h"
l25="$esc\\[?25l"
cup_then_show="$esc\\[[0-9;]*H$esc\\[?25h"

echo "== OpenCode TUI caret proof =="
echo "binary:  $BIN"
echo "version: $("$BIN" --version 2>/dev/null)"
echo "geometry: ${COLS}x${ROWS}"

# --- 1. Idle capture ---------------------------------------------------------
idle="$work/idle.log"
echo
echo "capturing idle TUI (~7s)..."
capture "$idle" "sleep 6; printf '\\003'; sleep 1; printf '\\003'; sleep 1"

[ -s "$idle" ] || { bad "idle capture is empty ($idle)"; echo "PASS=$pass FAIL=$fail"; exit 1; }

idle_show=$(grep -ao "$h25" "$idle" | wc -l)
idle_hide=$(grep -ao "$l25" "$idle" | wc -l)
if [ "$idle_show" -gt 0 ] && [ "$idle_hide" -gt 0 ]; then
    ok "TUI manages the cursor ($idle_show shows / $idle_hide hides)"
else
    bad "TUI does not toggle the cursor ($idle_show shows / $idle_hide hides)"
fi

sync=$(grep -ao "$esc\[?2026[hl]" "$idle" | wc -l)
if [ "$sync" -gt 0 ]; then
    ok "TUI uses synchronized output (ESC[?2026h/l x$sync)"
else
    bad "no synchronized output seen"
fi

# --- 2. The show is preceded by a CUP to the caret ---------------------------
idle_adj=$(grep -ao "$cup_then_show" "$idle" | wc -l)
if [ "$idle_show" -gt 0 ]; then
    # At least 80% of shows must be an explicit CUP to the caret; the rest are transitional
    # frames that repaint before parking again.
    if [ $((idle_adj * 100)) -ge $((idle_show * 80)) ]; then
        ok "CUP<caret> immediately before ESC[?25h ($idle_adj/$idle_show)"
    else
        bad "CUP<caret> before ESC[?25h only $idle_adj/$idle_show"
    fi
fi

# The declared row is the row of the dominant "CUP <row>;<col>H ESC[?25h" pair.
idle_rows=$(grep -ao "$cup_then_show" "$idle" \
    | sed -n 's/^.*\[\([0-9]*\);[0-9]*H.*$/\1/p')
declared_row=$(printf '%s\n' "$idle_rows" | sort | uniq -c | sort -rn | head -1 | awk '{print $2}')
declared_hits=$(printf '%s\n' "$idle_rows" | sort | uniq -c | sort -rn | head -1 | awk '{print $1}')

if [ -n "${declared_row:-}" ]; then
    echo "  info  declared caret row while idle: $declared_row ($declared_hits frames)"
    if [ "$declared_row" -ge $((ROWS / 2)) ]; then
        ok "declared caret row $declared_row is in the lower half of $ROWS rows"
    else
        bad "declared caret row $declared_row is in the upper half of $ROWS rows"
    fi
    # While idle the caret must not wander: one row dominates.
    distinct=$(printf '%s\n' "$idle_rows" | sort -u | wc -l)
    if [ "$declared_hits" -ge "$((idle_adj * 8 / 10))" ]; then
        ok "declared row is stable while idle ($distinct distinct rows)"
    else
        bad "declared row wanders while idle ($distinct distinct rows)"
    fi
else
    bad "no 'CUP<caret> ESC[?25h' pair found"
fi

# --- 3. Typing moves the declared caret column -------------------------------
typed="$work/typed.log"
echo
echo "capturing typed TUI (~8s)..."
capture "$typed" "sleep 5; printf 'draft'; sleep 2; printf '\\003'; sleep 1; printf '\\003'; sleep 1"

[ -s "$typed" ] || { bad "typed capture is empty ($typed)"; echo "PASS=$pass FAIL=$fail"; exit 1; }

typed_cols=$(grep -ao "$cup_then_show" "$typed" \
    | sed -n 's/^.*\[[0-9]*;\([0-9]*\)H.*$/\1/p' | sort -n | uniq)
col_min=$(printf '%s\n' "$typed_cols" | head -1)
col_max=$(printf '%s\n' "$typed_cols" | tail -1)

if [ -n "${col_min:-}" ] && [ -n "${col_max:-}" ] && [ "$col_max" -gt "$col_min" ]; then
    ok "typing moves the declared caret column ($col_min..$col_max)"
else
    bad "declared caret column did not move ($col_min..$col_max)"
fi

# The row must stay put while the draft grows.
typed_rows=$(grep -ao "$cup_then_show" "$typed" \
    | sed -n 's/^.*\[\([0-9]*\);[0-9]*H.*$/\1/p' | sort -u | wc -l)
if [ "$typed_rows" -le 2 ]; then
    ok "declared caret row stays put while typing ($typed_rows distinct rows)"
else
    bad "declared caret row moves while typing ($typed_rows distinct rows)"
fi

echo
echo "captures kept in: $work"
echo "PASS=$pass FAIL=$fail"
[ "$fail" -eq 0 ]
