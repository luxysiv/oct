package com.termux.app.opencode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TuiClassifierTest {

    private static TuiSnapshot snapshot(boolean alternateBuffer, String... lines) {
        return new TuiSnapshot(Arrays.asList(lines), 80, lines.length,
            0, 0, alternateBuffer, true, "session title");
    }

    @Test
    public void classifyNullIsUnknown() {
        assertEquals(TuiState.UNKNOWN, TuiClassifier.classify(null));
    }

    @Test
    public void notOnAlternateBufferIsShell() {
        assertEquals(TuiState.SHELL,
            TuiClassifier.classify(snapshot(false, "$ echo hi", "hi")));
    }

    @Test
    public void composerPlaceholderIsComposer() {
        assertEquals(TuiState.COMPOSER,
            TuiClassifier.classify(snapshot(true, "Ask anything… \"how do I …\"")));
    }

    @Test
    public void composerHintIsComposer() {
        assertEquals(TuiState.COMPOSER,
            TuiClassifier.classify(snapshot(true, "Ask anything, / for commands, @ for context")));
    }

    @Test
    public void shellModePlaceholderIsComposer() {
        assertEquals(TuiState.COMPOSER,
            TuiClassifier.classify(snapshot(true, "Run a command… \"ls\"")));
    }

    @Test
    public void footerShortcutPairIsComposer() {
        assertEquals(TuiState.COMPOSER,
            TuiClassifier.classify(snapshot(true, "tab agents            ctrl+p commands")));
    }

    @Test
    public void agentsOnlyIsNotComposer() {
        // "agents" alone (ordinary transcript text) must not be mistaken for the composer footer.
        assertFalse(TuiClassifier.hasComposer(snapshot(true, "these agents are helpful")));
    }

    @Test
    public void busyInterruptHintIsBusy() {
        assertEquals(TuiState.BUSY,
            TuiClassifier.classify(snapshot(true, "esc  again to interrupt")));
    }

    @Test
    public void busyEscAndInterruptOnSameLineIsBusy() {
        assertEquals(TuiState.BUSY,
            TuiClassifier.classify(snapshot(true, "esc interrupt")));
    }

    @Test
    public void composerWhileBusyIsBusy() {
        assertEquals(TuiState.BUSY,
            TuiClassifier.classify(snapshot(true, "tab agents  ctrl+p commands", "esc again to interrupt")));
    }

    @Test
    public void dialogExactTitleIsDialog() {
        assertEquals(TuiState.DIALOG,
            TuiClassifier.classify(snapshot(true, "  Commands  ")));
    }

    @Test
    public void dialogSubstringTitleIsDialog() {
        assertEquals(TuiState.DIALOG,
            TuiClassifier.classify(snapshot(true, "╭─ Select model ─────────╮")));
    }

    @Test
    public void dialogWinsOverComposer() {
        assertEquals(TuiState.DIALOG,
            TuiClassifier.classify(snapshot(true, "Ask anything… \"x\"", "Select agent")));
    }

    @Test
    public void footerCommandsOnlyIsNotDialog() {
        // "commands" as part of the footer shortcut must not trip the exact "Commands" title.
        assertFalse(TuiClassifier.hasDialog(snapshot(true, "ctrl+p commands")));
    }

    @Test
    public void altBufferWithNothingRecognisedIsUnknown() {
        assertEquals(TuiState.UNKNOWN,
            TuiClassifier.classify(snapshot(true, "random output", "more output")));
    }

    @Test
    public void matchingIsCaseInsensitive() {
        assertEquals(TuiState.COMPOSER,
            TuiClassifier.classify(snapshot(true, "ASK ANYTHING")));
        assertEquals(TuiState.DIALOG,
            TuiClassifier.classify(snapshot(true, "SELECT MODEL")));
    }

    @Test
    public void snapshotLineIsOutOfRangeSafe() {
        TuiSnapshot s = snapshot(true, "one");
        assertEquals("one", s.line(0));
        assertEquals("", s.line(-1));
        assertEquals("", s.line(5));
        assertEquals(1, s.rowCount());
    }

    @Test
    public void snapshotCopiesAndProtectsItsLines() {
        List<String> mutable = new ArrayList<>();
        mutable.add("one");
        TuiSnapshot s = new TuiSnapshot(mutable, 80, 1, 0, 0, true, false, null);
        mutable.add("two");
        assertEquals(1, s.rowCount());
    }

    @Test
    public void composerRowIsAnchoredOnThePlaceholder() {
        String[] lines = filled(20);
        lines[19] = "Ask anything… \"x\"";
        TuiSnapshot s = snapshot(true, lines);
        // bottom = 19, band = max(8, min(20/3, 12)) = 8, so rows 12..19 are the composer.
        assertTrue(TuiClassifier.isComposerRow(s, 19));
        assertTrue(TuiClassifier.isComposerRow(s, 12));
        assertFalse(TuiClassifier.isComposerRow(s, 11));
    }

    @Test
    public void composerRowIsAnchoredOnTheShortcutFooter() {
        String[] lines = filled(20);
        lines[19] = "tab agents            ctrl+p commands";
        TuiSnapshot s = snapshot(true, lines);
        assertTrue(TuiClassifier.isComposerRow(s, 19));
        assertTrue(TuiClassifier.isComposerRow(s, 12));
        assertFalse(TuiClassifier.isComposerRow(s, 11));
    }

    @Test
    public void composerRowIsAnchoredOnTheBusyFooter() {
        String[] lines = filled(20);
        lines[19] = "esc  again to interrupt";
        TuiSnapshot s = snapshot(true, lines);
        assertTrue(TuiClassifier.isComposerRow(s, 19));
        assertTrue(TuiClassifier.isComposerRow(s, 12));
        assertFalse(TuiClassifier.isComposerRow(s, 11));
    }

    @Test
    public void composerRowKeepsADistanceFromTheTranscript() {
        // The block above the composer must stay reachable, e.g. for inline permission buttons.
        String[] lines = filled(40);
        lines[39] = "Ask anything… \"x\"";
        TuiSnapshot s = snapshot(true, lines);
        // bottom = 39, band = max(8, min(40/3, 12)) = 12, so rows 28..39 only.
        assertTrue(TuiClassifier.isComposerRow(s, 39));
        assertTrue(TuiClassifier.isComposerRow(s, 28));
        assertFalse(TuiClassifier.isComposerRow(s, 27));
    }

    @Test
    public void composerRowIsFalseWhenComposerNotOnScreen() {
        String[] lines = filled(20);
        Arrays.fill(lines, "ordinary transcript line");
        assertFalse(TuiClassifier.isComposerRow(snapshot(true, lines), 19));
    }

    @Test
    public void composerRowIsFalseForDialog() {
        String[] lines = filled(20);
        lines[0] = "Select model";
        assertFalse(TuiClassifier.isComposerRow(snapshot(true, lines), 19));
    }

    @Test
    public void composerRowOutOfRangeIsFalse() {
        String[] lines = filled(10);
        lines[9] = "Ask anything";
        TuiSnapshot s = snapshot(true, lines);
        assertFalse(TuiClassifier.isComposerRow(s, -1));
        assertFalse(TuiClassifier.isComposerRow(s, 10));
        assertFalse(TuiClassifier.isComposerRow(null, 0));
    }

    @Test
    public void composerBandRowIgnoresMissingMarkers() {
        // A non-empty draft in a narrow window hides the placeholder and the shortcut footer.
        String[] lines = filled(30);
        Arrays.fill(lines, "assistant response text");
        TuiSnapshot s = snapshot(true, lines);
        // band = max(8, min(30/3, 12)) = 10, so rows 20..29 are the fallback band.
        assertTrue(TuiClassifier.isComposerBandRow(s, 29));
        assertTrue(TuiClassifier.isComposerBandRow(s, 20));
        assertFalse(TuiClassifier.isComposerBandRow(s, 19));
        assertFalse(TuiClassifier.isComposerBandRow(s, -1));
        assertFalse(TuiClassifier.isComposerBandRow(s, 30));
        assertFalse(TuiClassifier.isComposerBandRow(null, 0));
    }

    @Test
    public void composerRowAtAnchorsOnTheDeclaredCaret() {
        // Caret declared on row 15 of 20: band = 8, so the block is rows 12..min(19, 15 + 4 = 19).
        String[] lines = filled(20);
        Arrays.fill(lines, "assistant response text");
        TuiSnapshot s = snapshot(true, lines);
        assertTrue(TuiClassifier.isComposerRowAt(s, 15, 15));
        assertTrue(TuiClassifier.isComposerRowAt(s, 19, 15));
        assertTrue(TuiClassifier.isComposerRowAt(s, 12, 15));
        assertFalse(TuiClassifier.isComposerRowAt(s, 11, 15));
        assertFalse(TuiClassifier.isComposerRowAt(s, 20, 15));
    }

    @Test
    public void composerRowAtWorksWithoutAnyComposerMarker() {
        // A long draft in a narrow window hides the placeholder and the footer.
        String[] lines = filled(24);
        Arrays.fill(lines, "assistant response text");
        TuiSnapshot s = snapshot(true, lines);
        // band(24) = 8, so the caret only anchors the composer from row 16 down.
        assertTrue(TuiClassifier.isComposerRowAt(s, 20, 20));
        assertTrue(TuiClassifier.isComposerRowAt(s, 23, 20));
        assertFalse(TuiClassifier.isComposerRowAt(s, 15, 20));
    }

    @Test
    public void composerRowAtRejectsACaretOutsideTheBottomBand() {
        // A dialog / full-screen list keeps its caret in the middle of the screen.
        String[] lines = filled(20);
        Arrays.fill(lines, "Select something");
        TuiSnapshot s = snapshot(true, lines);
        // caretRow 10 < 20 - band(8) = 12.
        assertFalse(TuiClassifier.isComposerRowAt(s, 10, 10));
    }

    @Test
    public void composerRowAtIsSafeForBadInput() {
        String[] lines = filled(20);
        TuiSnapshot s = snapshot(true, lines);
        assertFalse(TuiClassifier.isComposerRowAt(null, 0, 0));
        assertFalse(TuiClassifier.isComposerRowAt(s, -1, 15));
        assertFalse(TuiClassifier.isComposerRowAt(s, 20, 15));
        assertFalse(TuiClassifier.isComposerRowAt(s, 15, -1));
        assertFalse(TuiClassifier.isComposerRowAt(s, 15, 20));
    }

    private static String[] filled(int count) {
        String[] lines = new String[count];
        Arrays.fill(lines, "");
        return lines;
    }

}
