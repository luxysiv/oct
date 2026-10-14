package com.termux.app.terminal;

/**
 * Pure helper for deciding whether a tap in the terminal view landed on the row that holds the
 * terminal cursor.
 *
 * <p>TUI apps such as OpenCode keep the terminal cursor inside their prompt field, so the cursor
 * row is effectively the app's input line. Keeping this mapping free of Android types makes it
 * unit-testable, since the off-by-one between the tap row and the rendered cursor row is easy to
 * get wrong.
 */
public final class TerminalCursorLine {

    private TerminalCursorLine() {}

    /**
     * Whether a tap at visual row {@code tappedRow} is on the line where the terminal cursor is
     * drawn.
     *
     * <p>{@code TerminalRenderer.render()} iterates transcript rows starting at {@code topRow} and
     * draws the cursor when the iterated row equals {@code cursorRow}, so the cursor's visual row
     * (0-based from the top of the view) is {@code cursorRow - topRow}. {@code topRow} is 0 when
     * the view shows the live screen and negative when scrolled back into the transcript.
     *
     * @param tappedRow 0-based visual row of the tap, as returned by
     *                  {@code TerminalView.getColumnAndRow(event, false)[1]}.
     * @param cursorRow 0-based screen row of the terminal cursor.
     * @param topRow    first transcript row currently displayed (0 when not scrolled).
     * @param rows      number of visible rows.
     * @return true if the tap is on the cursor line and within the visible rows.
     */
    public static boolean isCursorLine(int tappedRow, int cursorRow, int topRow, int rows) {
        if (tappedRow < 0 || tappedRow >= rows) return false;
        return tappedRow == cursorRow - topRow;
    }

}
