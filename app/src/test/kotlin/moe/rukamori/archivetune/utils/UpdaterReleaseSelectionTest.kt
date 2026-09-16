/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.utils

import moe.rukamori.archivetune.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterReleaseSelectionTest {
    private fun release(
        tag: String,
        name: String,
        publishedAt: String = "2026-09-13T17:57:12Z",
    ): ReleaseInfo =
        ReleaseInfo(
            tagName = tag,
            name = name,
            body = null,
            publishedAt = publishedAt,
            htmlUrl = "https://github.com/4nx3b/ArchiveTune/releases/tag/$tag",
        )

    @Test
    fun extensionReleasesAreNeverSelectedAsAppUpdates() {
        val releases =
            listOf(
                release("v15.0", "15.0"),
                release("icon-pack-v1", "Runtime icon pack (icon-pack-v1)"),
                release("N202609131719", "Canary 15.0.6365-eed1fe56a (202609131719)"),
                release("tdlight-2b51b33", "TDLight natives (tdlight-2b51b33)"),
                release("tdlib-1.8.56", "TDLib 1.8.56 natives"),
            )

        assertEquals("v15.0", Updater.findLatestRelease(releases)?.tagName)
    }

    @Test
    fun newestStableAppReleaseWinsOverOlderStable() {
        val releases =
            listOf(
                release("v14.0.5362", "14.0.5362"),
                release("v15.0", "15.0"),
            )

        assertEquals("v15.0", Updater.findLatestRelease(releases)?.tagName)
    }

    @Test
    fun canaryTagsAreNotStableCandidates() {
        val releases =
            listOf(
                release("N202609131719", "Canary 15.0.6365-eed1fe56a (202609131719)"),
            )

        assertNull(Updater.findLatestRelease(releases))
    }

    @Test
    fun twoComponentVersionIsUpToDateAgainstItself() {
        assertTrue(Updater.isSameVersion("15.0", "15.0"))
        assertFalse(Updater.isUpdateAvailable("15.0", "15.0"))
    }

    @Test
    fun twoComponentVersionParsesForComparison() {
        assertTrue(Updater.isUpdateAvailable("15.0.1", "15.0"))
        assertFalse(Updater.isUpdateAvailable("15.0", "15.0.6365-eed1fe56a"))
        assertTrue(Updater.isUpdateAvailable("15.0", "14.0.0"))
    }

    @Test
    fun canaryReleaseVersionNameCarriesBuildNumber() {
        val canary = release("N202609131719", "Canary 15.0.6365-eed1fe56a (202609131719)")

        assertEquals(6365, Updater.canaryBuildNumber(canary))
    }

    @Test
    fun stableReleaseVersionNameNormalizesTwoComponentTag() {
        val stable = release("v15.0", "15.0")

        assertEquals("15.0.0", Updater.getReleaseVersionName(stable))
    }

    @Test
    fun buildNumberComparisonStillTakesPrecedence() {
        val currentVersionName = BuildConfig.VERSION_NAME
        val buildNumber = Updater.buildNumberOrNull(currentVersionName)

        if (buildNumber == null) {
            return
        }

        assertTrue(Updater.isUpdateAvailable("15.0 (${buildNumber + 1})", currentVersionName))
        assertFalse(Updater.isUpdateAvailable("15.0 ($buildNumber)", currentVersionName))
    }
}
