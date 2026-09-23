package dev.holo795.crashsleuth.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Comparing two versions: the part that decides whether anyone is told about a new one. */
class ReleaseTest {
    @Test
    fun `a later version is later`() {
        assertTrue(Release.isNewer("1.0.1", "1.0.0"))
        assertTrue(Release.isNewer("1.1.0", "1.0.9"))
        assertTrue(Release.isNewer("2.0.0", "1.9.9"))
        // Ten comes after nine, which a plain text comparison gets wrong.
        assertTrue(Release.isNewer("1.10.0", "1.9.0"))
        assertTrue(Release.isNewer("v1.0.1", "1.0.0"))
    }

    @Test
    fun `the same version, or an older one, is not offered`() {
        assertFalse(Release.isNewer("1.0.0", "1.0.0"))
        assertFalse(Release.isNewer("1.0.0", "1.0.1"))
        assertFalse(Release.isNewer("1.9.0", "1.10.0"))
        assertFalse(Release.isNewer("0.1.0", "1.0.0"))
    }

    @Test
    fun `a finished version comes after its own beta, and a snapshot is never newer`() {
        assertTrue(Release.isNewer("1.0.0", "1.0.0-beta"))
        assertFalse(Release.isNewer("1.0.0-beta", "1.0.0"))
        assertFalse(Release.isNewer("1.0.0", "1.0.0"))
        // A finished version is later than the snapshot of the same number, which is why a working copy
        // reports no version at all: nobody building from sources is told a release is newer than their work.
        assertTrue(Release.isNewer("0.1.0", "0.1.0-SNAPSHOT"))
        assertNull(Release.current)
    }
}
