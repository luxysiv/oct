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

}
