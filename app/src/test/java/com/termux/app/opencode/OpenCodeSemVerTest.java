package com.termux.app.opencode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OpenCodeSemVerTest {

    @Test
    public void ordersReleaseVersions() {
        assertTrue(OpenCodeSemVer.compare("2.0.26", "2.0.25") > 0);
        assertTrue(OpenCodeSemVer.compare("2.1.0", "2.0.99") > 0);
        assertTrue(OpenCodeSemVer.compare("3.0.0", "2.99.99") > 0);
        assertEquals(0, OpenCodeSemVer.compare("2.0.26", "2.0.26"));
        assertTrue(OpenCodeSemVer.compare("2.0.25", "2.0.26") < 0);
    }

    @Test
    public void stripsLeadingVAndBuildMetadata() {
        assertEquals(0, OpenCodeSemVer.compare("v2.0.26", "2.0.26"));
        assertEquals(0, OpenCodeSemVer.compare("2.0.26+build.5", "2.0.26"));
    }

    @Test
    public void releaseIsNewerThanItsPrerelease() {
        assertTrue(OpenCodeSemVer.compare("2.0.0", "2.0.0-beta.1") > 0);
        assertTrue(OpenCodeSemVer.compare("2.0.0-beta.2", "2.0.0-beta.1") > 0);
        assertTrue(OpenCodeSemVer.compare("2.0.0-rc.1", "2.0.0-beta.9") > 0);
    }

    @Test
    public void isNewerHandlesEmptyCurrent() {
        assertTrue(OpenCodeSemVer.isNewer("2.0.26", null));
        assertTrue(OpenCodeSemVer.isNewer("2.0.26", ""));
        assertFalse(OpenCodeSemVer.isNewer(null, "2.0.26"));
        assertFalse(OpenCodeSemVer.isNewer("2.0.26", "2.0.26"));
        assertTrue(OpenCodeSemVer.isNewer("2.0.27", "2.0.26"));
    }

    @Test
    public void parsesFirstSemverFromText() {
        assertEquals("2.0.26", OpenCodeSemVer.parse("opencode 2.0.26\n"));
        assertEquals("2.0.26", OpenCodeSemVer.parse("2.0.26"));
        assertEquals("1.2.3", OpenCodeSemVer.parse("build 1.2.3-rc.1 now"));
        assertEquals(null, OpenCodeSemVer.parse("no version here"));
        assertEquals(null, OpenCodeSemVer.parse("2.0"));
    }
}
