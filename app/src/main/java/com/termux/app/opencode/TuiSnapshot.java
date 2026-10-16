package com.termux.app.opencode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable snapshot of the visible terminal screen, taken so that pure logic
 * ({@link TuiClassifier}) can reason about what the OpenCode TUI is showing without touching the
 * terminal emulator or any Android type.
 *
 * <p>Coordinates follow the terminal emulator's conventions: rows are 0-based from the top of the
 * <em>visible</em> screen and columns are 0-based from the left. Only the visible screen is
 * captured, never the scrollback, because the TUI runs on the alternate screen buffer and never
 * scrolls back.</p>
 *
 * <p>Pure Java (no Android imports) so it can be unit-tested on the JVM.</p>
 */
public final class TuiSnapshot {

    private final List<String> mVisibleLines;
    private final int mColumns;
    private final int mRows;
    private final int mCursorRow;
    private final int mCursorCol;
    private final boolean mAlternateBufferActive;
    private final boolean mMouseTrackingActive;
    private final String mTitle;

    /**
     * @param visibleLines          one entry per visible row, top to bottom (trailing blanks
     *                              already trimmed by the emulator).
     * @param columns               terminal width in cells.
     * @param rows                  terminal height in cells.
     * @param cursorRow             0-based screen row of the terminal cursor.
     * @param cursorCol             0-based screen column of the terminal cursor.
     * @param alternateBufferActive whether the TUI is on the alternate screen buffer.
     * @param mouseTrackingActive   whether the TUI currently captures mouse events.
     * @param title                 terminal title, as last set by the TUI via OSC (may be null).
     */
    public TuiSnapshot(List<String> visibleLines, int columns, int rows,
                       int cursorRow, int cursorCol,
                       boolean alternateBufferActive, boolean mouseTrackingActive,
                       String title) {
        mVisibleLines = Collections.unmodifiableList(
            new ArrayList<>(visibleLines == null ? Collections.<String>emptyList() : visibleLines));
        mColumns = columns;
        mRows = rows;
        mCursorRow = cursorRow;
        mCursorCol = cursorCol;
        mAlternateBufferActive = alternateBufferActive;
        mMouseTrackingActive = mouseTrackingActive;
        mTitle = title;
    }

    /** Visible rows, top to bottom. Never null; entries are never null. */
    public List<String> lines() {
        return mVisibleLines;
    }

    /** Number of captured rows. */
    public int rowCount() {
        return mVisibleLines.size();
    }

    /** Text of a visible row, or an empty string when {@code row} is out of range. */
    public String line(int row) {
        if (row < 0 || row >= mVisibleLines.size()) return "";
        String line = mVisibleLines.get(row);
        return line == null ? "" : line;
    }

    public int columns() {
        return mColumns;
    }

    public int rows() {
        return mRows;
    }

    public int cursorRow() {
        return mCursorRow;
    }

    public int cursorCol() {
        return mCursorCol;
    }

    public boolean alternateBufferActive() {
        return mAlternateBufferActive;
    }

    public boolean mouseTrackingActive() {
        return mMouseTrackingActive;
    }

    public String title() {
        return mTitle;
    }

}
