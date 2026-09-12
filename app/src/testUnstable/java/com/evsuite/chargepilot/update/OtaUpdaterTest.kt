package com.evsuite.chargepilot.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision half of the unstable updater: which URL may be fetched, which build is newer,
 * and what the downloaded file is allowed to be called. Each of those is proposed by a remote
 * document, so each is tested against what a hostile release would look like.
 */
class OtaUpdaterTest {

    @Test fun `a github release asset is allowed`() {
        assertTrue(OtaUpdater.isAllowedUrl("https://github.com/malys/EVChargePilot/releases/x.apk"))
        assertTrue(OtaUpdater.isAllowedUrl("https://objects.githubusercontent.com/a/b.apk"))
        assertTrue(OtaUpdater.isAllowedUrl("https://release-assets.githubusercontent.com/a.apk"))
    }

    @Test fun `http is refused, including a downgrade of an allowed host`() {
        assertFalse(OtaUpdater.isAllowedUrl("http://github.com/malys/EVChargePilot/x.apk"))
    }

    @Test fun `a lookalike host is not github`() {
        assertFalse(OtaUpdater.isAllowedUrl("https://github.com.attacker.net/x.apk"))
        assertFalse(OtaUpdater.isAllowedUrl("https://evil-github.com/x.apk"))
        assertFalse(OtaUpdater.isAllowedUrl("https://githubusercontent.com.evil/x.apk"))
    }

    @Test fun `an unparsable url is refused rather than guessed at`() {
        assertFalse(OtaUpdater.isAllowedUrl("not a url"))
        assertFalse(OtaUpdater.isAllowedUrl(""))
    }

    @Test fun `a higher build of the same version is newer`() {
        assertTrue(OtaUpdater.isNewer("0.2.0.43", "0.2.0-unstable"))
        assertTrue(OtaUpdater.isNewer("0.2.0.43", "0.2.0.42"))
        assertTrue(OtaUpdater.isNewer("0.3.0.1", "0.2.0.99"))
    }

    @Test fun `the same or an older build is not an update`() {
        assertFalse(OtaUpdater.isNewer("0.2.0.42", "0.2.0.42"))
        assertFalse(OtaUpdater.isNewer("0.2.0.41", "0.2.0.42"))
        assertFalse(OtaUpdater.isNewer("0.1.9.99", "0.2.0"))
    }

    @Test fun `a segment without digits counts as zero and does not shift the rest left`() {
        assertEquals(listOf(0, 2, 0, 43), OtaUpdater.segments("v0.2.0.43-unstable"))
        assertEquals(listOf(0, 0, 7), OtaUpdater.segments("0.x.7"))
    }

    @Test fun `the version comes from the asset name, not the tag`() {
        assertEquals(
            "0.2.0.43",
            OtaUpdater.versionFromAssetName("EVChargePilot-unstable-0.2.0.43.apk")
        )
    }

    @Test fun `an asset that is not this channel's APK is ignored`() {
        assertNull(OtaUpdater.versionFromAssetName("EVProfile-unstable-2.7.0.9.apk"))
        assertNull(OtaUpdater.versionFromAssetName("EVChargePilot-stable-0.2.0.apk"))
        assertNull(OtaUpdater.versionFromAssetName("EVChargePilot-unstable.apk"))
        assertNull(OtaUpdater.versionFromAssetName("EVChargePilot-unstable-0.2.0.43.apk.txt"))
    }

    @Test fun `a version read off a remote name cannot choose where the file lands`() {
        assertEquals("EVChargePilot-unstable-.._.._etc.apk", OtaUpdater.fileName("../../etc"))
        assertEquals("EVChargePilot-unstable-unknown.apk", OtaUpdater.fileName(""))
        assertEquals("EVChargePilot-unstable-unknown.apk", OtaUpdater.fileName(null))
        assertFalse(OtaUpdater.fileName("a/b c").contains("/"))
    }
}
