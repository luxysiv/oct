package com.termux.app.opencode;

/**
 * Dominant UI mode of the OpenCode TUI, derived from a {@link TuiSnapshot} by
 * {@link TuiClassifier}.
 *
 * <p>The toolbar uses this to decide how to talk to the TUI directly (through the PTY) without
 * an API: for example it must never fire a bare {@code \r} while a modal {@link #DIALOG} is open,
 * because OpenCode would read it as "confirm the highlighted item" instead of "submit prompt".</p>
 */
public enum TuiState {

    /** The current session is not the OpenCode TUI (or no session is available). */
    NOT_OPENCODE,

    /** The alternate screen is not active: a normal shell / CLI prompt. */
    SHELL,

    /** The OpenCode composer is focused and idle: safe to paste text and submit. */
    COMPOSER,

    /** The composer is visible but a run is in progress ({@code Enter} steers the active run). */
    BUSY,

    /** A modal dialog / palette is open: do NOT send {@code Enter} blindly. */
    DIALOG,

    /** The alternate screen is active but nothing recognisable is on screen. */
    UNKNOWN

}
