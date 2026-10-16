package com.termux.app.opencode;

import androidx.annotation.Nullable;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The app side of the direct (non-API) channel between the toolbar and the OpenCode TUI running
 * inside the Termux terminal.
 *
 * <p>There is no private protocol to invent: the PTY <em>is</em> the channel. This class only
 * closes the loop on the reading side, turning the emulator's screen buffer into a
 * {@link TuiSnapshot} that {@link TuiClassifier} can interpret, and identifying which session is
 * the OpenCode TUI. The writing side (sending text, keys and submit bytes) is intentionally not
 * here yet — see {@code TOOLBAR_TUI.md} for the roadmap.</p>
 *
 * <p>Everything that talks to the terminal lives here; the decision logic stays in the pure
 * {@link TuiClassifier} so it can be unit-tested without a device.</p>
 */
public final class TuiBridge {

    /** Session name used when the launcher auto-starts the OpenCode TUI (see {@code TermuxActivity}). */
    public static final String OPENCODE_SESSION_NAME = "opencode";

    private TuiBridge() {}

    /** Whether {@code session} is the terminal session that runs the OpenCode TUI. */
    public static boolean isOpenCodeTuiSession(@Nullable TerminalSession session) {
        if (session == null) return false;
        String name = session.mSessionName;
        return name != null && name.toLowerCase(Locale.ROOT).contains(OPENCODE_SESSION_NAME);
    }

    /**
     * Take a snapshot of the session's visible screen, or {@code null} when the session or its
     * emulator is not ready yet.
     */
    @Nullable
    public static TuiSnapshot snapshot(@Nullable TerminalSession session) {
        if (session == null) return null;

        TerminalEmulator emulator = session.getEmulator();
        if (emulator == null) return null;

        int rows = emulator.getRows();
        int columns = emulator.getColumns();
        TerminalBuffer screen = emulator.getScreen();

        List<String> lines = new ArrayList<>(Math.max(rows, 0));
        for (int row = 0; row < rows; row++) {
            // TerminalBuffer#getSelectedText uses an inclusive end row, so a single row is
            // (0, row, columns, row). Trailing blanks are already trimmed by the emulator.
            String line = screen.getSelectedText(0, row, columns, row);
            lines.add(line == null ? "" : line);
        }

        return new TuiSnapshot(lines, columns, rows,
            emulator.getCursorRow(), emulator.getCursorCol(),
            emulator.isAlternateBufferActive(), emulator.isMouseTrackingActive(),
            emulator.getTitle());
    }

    /**
     * Classify what the OpenCode TUI is showing. Returns {@link TuiState#NOT_OPENCODE} when the
     * session is not the OpenCode TUI at all.
     */
    public static TuiState classify(@Nullable TerminalSession session) {
        if (!isOpenCodeTuiSession(session)) return TuiState.NOT_OPENCODE;
        return TuiClassifier.classify(snapshot(session));
    }

}
