package com.termux.app.terminal;

import org.junit.Assert;
import org.junit.Test;

public class TerminalCursorLineTest {

    @Test
    public void testTapOnCursorLineMatches() {
        // Not scrolled: visual row == screen row.
        Assert.assertTrue(TerminalCursorLine.isCursorLine(5, 5, 0, 24));
    }

    @Test
    public void testTapOnOtherLineDoesNotMatch() {
        Assert.assertFalse(TerminalCursorLine.isCursorLine(4, 5, 0, 24));
        Assert.assertFalse(TerminalCursorLine.isCursorLine(6, 5, 0, 24));
    }

    @Test
    public void testScrolledBackShiftsCursorDown() {
        // Scrolled back 3 rows: the cursor is drawn 3 rows lower, so the tap row must match that.
        Assert.assertTrue(TerminalCursorLine.isCursorLine(8, 5, -3, 24));
        Assert.assertFalse(TerminalCursorLine.isCursorLine(5, 5, -3, 24));
    }

    @Test
    public void testOutOfBoundsRowsNeverMatch() {
        Assert.assertFalse(TerminalCursorLine.isCursorLine(-1, 0, 0, 24));
        Assert.assertFalse(TerminalCursorLine.isCursorLine(24, 0, 0, 24));
    }

    @Test
    public void testTopRowZeroFirstRow() {
        Assert.assertTrue(TerminalCursorLine.isCursorLine(0, 0, 0, 24));
    }

}
