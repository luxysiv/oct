package com.termux.app.opencode;

import java.util.Locale;

/**
 * Pure heuristic that maps a {@link TuiSnapshot} to a {@link TuiState}.
 *
 * <p>OpenCode draws its whole UI to the terminal, so the app can classify what is on screen by
 * matching a small set of strings the TUI always renders. The strings below were taken from the
 * OpenCode TUI source and from the strings embedded in the installed binary
 * ({@code 2.0.30}); see {@code TOOLBAR_TUI.md} for the evidence. They are deliberately kept in one
 * place and matched case-insensitively so they are easy to review and update when a new OpenCode
 * release changes its copy.</p>
 *
 * <p>Pure Java (no Android imports) so it can be unit-tested on the JVM.</p>
 */
public final class TuiClassifier {

    private TuiClassifier() {}

    // ---------------------------------------------------------------------------------------------
    // Tokens drawn while the composer is visible.
    // ---------------------------------------------------------------------------------------------

    /** Composer placeholder / hint prefixes. */
    public static final String[] COMPOSER_TOKENS = {
        "Ask anything",                     // placeholder "Ask anything… \"…\"" + hint line
        "/ for commands, @ for context",    // composer hint
        "Run a command",                    // placeholder in shell mode
    };

    /** Right-hand footer shortcut for cycling agents ({@code tab agents}). */
    public static final String COMPOSER_FOOTER_AGENTS = "agents";

    /** Right-hand footer shortcut for the command palette ({@code ctrl+p commands}). */
    public static final String COMPOSER_FOOTER_COMMANDS = "commands";

    // ---------------------------------------------------------------------------------------------
    // Tokens drawn while a run is active.
    // ---------------------------------------------------------------------------------------------

    /** Interrupt hint shown while OpenCode is generating ({@code esc again to interrupt}). */
    public static final String BUSY_TOKEN = "again to interrupt";

    /** The interrupt hint is rendered as {@code esc … interrupt}; both words must be on one row. */
    public static final String BUSY_INTERRUPT = "interrupt";
    public static final String BUSY_ESC = "esc";

    // ---------------------------------------------------------------------------------------------
    // Dialog / palette titles.
    // ---------------------------------------------------------------------------------------------

    /**
     * Dialog titles matched against a whole (trimmed) row. Kept to titles short enough that a full
     * row match is required, because a plain {@code contains} would also match ordinary transcript
     * text.
     */
    public static final String[] DIALOG_TITLES_EXACT = {
        "Commands",     // command palette
        "Sessions",     // session list
        "Stash",        // prompt stash
        "Themes",       // theme list
    };

    /**
     * Dialog titles matched by substring. These are multi-word / distinctive enough that a
     * {@code contains} is safe and tolerates the box border being drawn on the same row.
     */
    public static final String[] DIALOG_TITLES_CONTAINS = {
        "Select model",
        "Select agent",
        "MCP servers",
    };

    // ---------------------------------------------------------------------------------------------

    /**
     * Classify a snapshot. Never returns {@link TuiState#NOT_OPENCODE} (that decision belongs to
     * {@link TuiBridge}, which knows which session is the OpenCode TUI); a {@code null} snapshot
     * yields {@link TuiState#UNKNOWN}.
     */
    public static TuiState classify(TuiSnapshot snapshot) {
        if (snapshot == null) return TuiState.UNKNOWN;
        if (!snapshot.alternateBufferActive()) return TuiState.SHELL;
        if (hasDialog(snapshot)) return TuiState.DIALOG;
        if (hasComposer(snapshot)) return hasBusy(snapshot) ? TuiState.BUSY : TuiState.COMPOSER;
        if (hasBusy(snapshot)) return TuiState.BUSY;
        return TuiState.UNKNOWN;
    }

    /** Whether a modal dialog / palette is open. */
    public static boolean hasDialog(TuiSnapshot snapshot) {
        if (snapshot == null) return false;
        for (String raw : snapshot.lines()) {
            String line = lower(raw);
            String trimmed = line.trim();
            for (String title : DIALOG_TITLES_EXACT) {
                if (trimmed.equals(lower(title))) return true;
            }
            for (String title : DIALOG_TITLES_CONTAINS) {
                if (line.contains(lower(title))) return true;
            }
        }
        return false;
    }

    /** Whether the OpenCode composer is visible. */
    public static boolean hasComposer(TuiSnapshot snapshot) {
        if (snapshot == null) return false;
        boolean hasFooter = false;
        for (String raw : snapshot.lines()) {
            String line = lower(raw);
            for (String token : COMPOSER_TOKENS) {
                if (line.contains(lower(token))) return true;
            }
            // The footer renders both shortcut hints on one row: "tab agents … ctrl+p commands".
            if (line.contains(COMPOSER_FOOTER_AGENTS) && line.contains(COMPOSER_FOOTER_COMMANDS)) {
                hasFooter = true;
            }
        }
        return hasFooter;
    }

    /** Whether a run is in progress (the interrupt hint is on screen). */
    public static boolean hasBusy(TuiSnapshot snapshot) {
        if (snapshot == null) return false;
        for (String raw : snapshot.lines()) {
            String line = lower(raw);
            if (line.contains(BUSY_TOKEN)) return true;
            if (line.contains(BUSY_INTERRUPT) && line.contains(BUSY_ESC)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // Composer region.
    // ---------------------------------------------------------------------------------------------

    /** Placeholder prefixes that mark the composer's textarea row itself. */
    public static final String[] COMPOSER_PLACEHOLDER_TOKENS = {
        "Ask anything",
        "Run a command",
    };

    /** Least number of rows counted as the composer block, so the empty composer is covered. */
    public static final int COMPOSER_BAND_MIN_ROWS = 8;

    /** Most rows counted as the composer block, so a tap never swallows the whole transcript. */
    public static final int COMPOSER_BAND_MAX_ROWS = 12;

    /**
     * Number of rows the composer draws below its textarea: one padding row, the metadata row
     * (agent / model), the bottom-border row and the footer row.
     */
    public static final int COMPOSER_ROWS_BELOW_TEXTAREA = 4;

    /**
     * Whether screen {@code row} is inside the composer block (the region that holds the input box).
     *
     * <p>The composer is always the bottom-most block of the TUI. It is anchored by its footer
     * (the {@code agents}/{@code commands} shortcut hints, or the {@code esc interrupt} hint while
     * running) or, failing that, by the textarea placeholder. From that anchor the block is assumed
     * to be at most {@code max(8, min(rows/3, 12))} rows tall, which covers the empty composer and
     * a short draft without reaching into the transcript.</p>
     *
     * <p>Anchoring on the screen contents (instead of the hardware cursor) is the point: the cursor
     * is hidden or parked at (0,0) by OpenTUI in some redraws, so it cannot be trusted to sit on the
     * tapped row.</p>
     *
     * @return false when {@code snapshot} is not showing the composer at all, so a tap on a shell
     *         prompt, a dialog or a transcript is never treated as the input box.
     */
    public static boolean isComposerRow(TuiSnapshot snapshot, int row) {
        if (snapshot == null) return false;
        int rows = snapshot.rowCount();
        if (row < 0 || row >= rows) return false;
        if (!hasComposer(snapshot) && !hasBusy(snapshot)) return false;

        int footer = -1;
        int placeholder = -1;
        for (int r = 0; r < rows; r++) {
            String line = lower(snapshot.line(r));
            if ((line.contains(COMPOSER_FOOTER_AGENTS) && line.contains(COMPOSER_FOOTER_COMMANDS))
                || (line.contains(BUSY_INTERRUPT) && line.contains(BUSY_ESC))) {
                footer = r;
            }
            for (String token : COMPOSER_PLACEHOLDER_TOKENS) {
                if (line.contains(lower(token))) placeholder = r;
            }
        }

        int bottom;
        if (footer >= 0) bottom = footer;
        else if (placeholder >= 0) bottom = Math.min(rows - 1, placeholder + COMPOSER_ROWS_BELOW_TEXTAREA);
        else bottom = rows - 1;

        return inComposerBand(rows, row, bottom);
    }

    /**
     * Whether screen {@code row} is inside the composer block when the row the running app declared
     * as its caret is known ({@code TerminalEmulator#getDeclaredCursorRow()}, i.e. the destination
     * of the {@code CUP <caret> ESC[?25h} an OpenTUI-style app emits at the end of every repaint).
     *
     * <p>This is the precise counterpart of {@link #isComposerRow}: instead of anchoring the block on
     * the footer/placeholder text - which a long draft or a narrow window hides - it anchors on the
     * app's own caret. The caret is only accepted as an anchor when it sits in the bottom band, so a
     * caret inside a full-screen list or dialog is never mistaken for the input line.</p>
     *
     * @return false when {@code snapshot} is null, when either row is out of range, or when the caret
     *         is not in the bottom band of the screen.
     */
    public static boolean isComposerRowAt(TuiSnapshot snapshot, int row, int caretRow) {
        if (snapshot == null) return false;
        int rows = snapshot.rowCount();
        if (row < 0 || row >= rows) return false;
        if (caretRow < 0 || caretRow >= rows) return false;
        // The composer is the bottom-most block; a caret higher up belongs to something else.
        if (caretRow < rows - composerBand(rows)) return false;
        int bottom = Math.min(rows - 1, caretRow + COMPOSER_ROWS_BELOW_TEXTAREA);
        return inComposerBand(rows, row, bottom);
    }

    /**
     * Whether {@code row} is in the bottom band that the composer occupies, without requiring any
     * composer marker to be visible.
     *
     * <p>Used as a last resort for the one state this classifier cannot recognise from text: the
     * TUI is running but a non-empty draft in a narrow window ({@code width < 44}) hides both the
     * placeholder and the shortcut footer, so the screen looks like an ordinary transcript.</p>
     */
    public static boolean isComposerBandRow(TuiSnapshot snapshot, int row) {
        if (snapshot == null) return false;
        int rows = snapshot.rowCount();
        if (row < 0 || row >= rows) return false;
        return inComposerBand(rows, row, rows - 1);
    }

    /** Height in rows of the composer block on a screen {@code rows} tall. */
    public static int composerBand(int rows) {
        return Math.max(COMPOSER_BAND_MIN_ROWS, Math.min(rows / 3, COMPOSER_BAND_MAX_ROWS));
    }

    private static boolean inComposerBand(int rows, int row, int bottom) {
        int band = composerBand(rows);
        int top = Math.max(0, bottom - band + 1);
        return row >= top && row <= bottom;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

}
