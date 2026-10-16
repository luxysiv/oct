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

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

}
