package kr.phishguard.app.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialAppsTest {
    @Test fun listsLoaded() {
        assertTrue(OfficialApps.PACKAGES.size >= 300)
        assertTrue("net.koino.anysupportmobile" in OfficialApps.REMOTE)
        assertTrue(OfficialApps.PACKAGES.all { it == it.lowercase() })
    }

    @Test fun fakeBankNameWithOtherPackage() {
        val label = "KB국민은행"
        assertTrue(ThreatIntel.isOfficialNameOtherPackage("com.fake.kbstar", label))
    }

    @Test fun realPackageNotFlagged() {
        OfficialApps.LABELS.forEach { (label, pkgs) ->
            pkgs.forEach { assertFalse("$label/$it", ThreatIntel.isOfficialNameOtherPackage(it, label)) }
        }
    }

    @Test fun caseInsensitivePackage() {
        assertTrue(ThreatIntel.isOfficialPackage("Com.sktelecom.minit") || ThreatIntel.isOfficialPackage("com.sktelecom.minit"))
    }

    @Test fun remoteListUsed() {
        assertTrue(ThreatIntel.isRemoteControl("kr.co.helpu.host", "HelpU"))
    }
}
