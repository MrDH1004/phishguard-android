package kr.phishguard.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    private val hex = "a".repeat(32) + "B".repeat(32)

    @Test fun newerPatch() = assertTrue(UpdateLogic.isNewer("v1.0.1", "1.0.0"))
    @Test fun sameVersion() = assertFalse(UpdateLogic.isNewer("v1.0.0", "1.0.0"))
    @Test fun olderVersion() = assertFalse(UpdateLogic.isNewer("1.0.0", "1.0.1"))
    @Test fun numericNotLexical() = assertTrue(UpdateLogic.isNewer("1.10.0", "1.9.9"))
    @Test fun differentLength() {
        assertEquals(0, UpdateLogic.compareVersions("1.2", "1.2.0"))
        assertTrue(UpdateLogic.isNewer("2", "1.9.9"))
    }
    @Test fun garbageTagIsNotNewer() = assertFalse(UpdateLogic.isNewer("latest", "1.0.0"))
    @Test fun suffixIgnored() = assertEquals(0, UpdateLogic.compareVersions("v1.2.3-beta", "1.2.3"))

    @Test fun shaWithFilename() = assertEquals(hex.lowercase(), UpdateLogic.parseSha256("$hex  phishguard-v1.0.1.apk\n"))
    @Test fun shaOnly() = assertEquals(hex.lowercase(), UpdateLogic.parseSha256("  $hex \n"))
    @Test fun shaBinaryMarker() = assertEquals(hex.lowercase(), UpdateLogic.parseSha256("$hex *a.apk"))
    @Test fun shaInvalid() {
        assertNull(UpdateLogic.parseSha256("not a hash"))
        assertNull(UpdateLogic.parseSha256(""))
        assertNull(UpdateLogic.parseSha256("abc123  file"))
    }
    @Test fun hashCaseInsensitive() {
        assertTrue(UpdateLogic.hashMatches(hex, hex.lowercase()))
        assertFalse(UpdateLogic.hashMatches(hex, "0".repeat(64)))
    }
}
