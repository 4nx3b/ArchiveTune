/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackResumptionPlannerTest {
    private val savedQueue = PlaybackResumptionPlanner.PersistedItems(
        items = listOf("first", "current", "next"),
        mediaItemIndex = 1,
        positionMs = 42_000L,
    )

    @Test
    fun `playback restores the saved queue and position when the player is empty`() {
        val result = PlaybackResumptionPlanner.resolve(
            currentItems = emptyList(),
            currentIndex = -1,
            currentPositionMs = 0L,
            persistedItems = savedQueue,
            isForPlayback = true,
        )

        assertEquals(PlaybackResumptionPlanner.Result(savedQueue.items, 1, 42_000L), result)
    }

    @Test
    fun `preview returns only the saved current item without restoring the full queue`() {
        val result = PlaybackResumptionPlanner.resolve(
            currentItems = emptyList(),
            currentIndex = -1,
            currentPositionMs = 0L,
            persistedItems = savedQueue,
            isForPlayback = false,
        )

        assertEquals(PlaybackResumptionPlanner.Result(listOf("current"), 0, 42_000L), result)
    }

    @Test
    fun `playback expands a matching controller preview back into the saved queue`() {
        val result = PlaybackResumptionPlanner.resolve(
            currentItems = listOf("current"),
            currentIndex = 0,
            currentPositionMs = 0L,
            persistedItems = savedQueue,
            isForPlayback = true,
        )

        assertEquals(PlaybackResumptionPlanner.Result(savedQueue.items, 1, 42_000L), result)
    }

    @Test
    fun `preview keeps the live position even when the item matches the saved queue`() {
        val result = PlaybackResumptionPlanner.resolve(
            currentItems = listOf("current"),
            currentIndex = 0,
            currentPositionMs = 7_000L,
            persistedItems = savedQueue,
            isForPlayback = false,
        )

        assertEquals(PlaybackResumptionPlanner.Result(listOf("current"), 0, 7_000L), result)
    }

    @Test
    fun `a newly selected single item is not replaced by a stale saved queue`() {
        val result = PlaybackResumptionPlanner.resolve(
            currentItems = listOf("new-selection"),
            currentIndex = 0,
            currentPositionMs = 3_000L,
            persistedItems = savedQueue,
            isForPlayback = true,
        )

        assertEquals(PlaybackResumptionPlanner.Result(listOf("new-selection"), 0, 3_000L), result)
    }

    @Test
    fun `an existing live queue takes precedence over the saved queue`() {
        val liveItems = listOf("current", "new-next")

        for (isForPlayback in listOf(true, false)) {
            val result = PlaybackResumptionPlanner.resolve(
                currentItems = liveItems,
                currentIndex = 1,
                currentPositionMs = 9_000L,
                persistedItems = savedQueue,
                isForPlayback = isForPlayback,
            )

            val expected = if (isForPlayback) {
                PlaybackResumptionPlanner.Result(liveItems, 1, 9_000L)
            } else {
                PlaybackResumptionPlanner.Result(listOf("new-next"), 0, 9_000L)
            }
            assertEquals("isForPlayback=$isForPlayback", expected, result)
        }
    }

    @Test
    fun `missing or empty queues return a safe empty result`() {
        for (persisted in listOf(null, savedQueue.copy(items = emptyList()))) {
            for (isForPlayback in listOf(true, false)) {
                val result = PlaybackResumptionPlanner.resolve(
                    currentItems = emptyList(),
                    currentIndex = Int.MAX_VALUE,
                    currentPositionMs = -1L,
                    persistedItems = persisted,
                    isForPlayback = isForPlayback,
                )

                assertEquals(PlaybackResumptionPlanner.Result(emptyList<String>(), 0, 0L), result)
            }
        }
    }

    @Test
    fun `live queue survives an empty saved queue and clamps invalid indices and positions`() {
        val liveItems = listOf("first", "last")

        for ((index, safeIndex) in listOf(Int.MIN_VALUE to 0, Int.MAX_VALUE to 1)) {
            for (isForPlayback in listOf(true, false)) {
                val result = PlaybackResumptionPlanner.resolve(
                    currentItems = liveItems,
                    currentIndex = index,
                    currentPositionMs = -1L,
                    persistedItems = savedQueue.copy(items = emptyList()),
                    isForPlayback = isForPlayback,
                )

                val expected = if (isForPlayback) {
                    PlaybackResumptionPlanner.Result(liveItems, safeIndex, 0L)
                } else {
                    PlaybackResumptionPlanner.Result(listOf(liveItems[safeIndex]), 0, 0L)
                }
                assertEquals("index=$index, isForPlayback=$isForPlayback", expected, result)
            }
        }
    }

    @Test
    fun `saved queue clamps invalid indices before matching a controller preview`() {
        for ((index, safeIndex) in listOf(Int.MIN_VALUE to 0, Int.MAX_VALUE to 2)) {
            val persisted = savedQueue.copy(mediaItemIndex = index, positionMs = -1L)
            val result = PlaybackResumptionPlanner.resolve(
                currentItems = listOf(savedQueue.items[safeIndex]),
                currentIndex = 0,
                currentPositionMs = 5_000L,
                persistedItems = persisted,
                isForPlayback = true,
            )

            assertEquals(PlaybackResumptionPlanner.Result(savedQueue.items, safeIndex, 0L), result)
        }
    }
}
