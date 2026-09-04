/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.canvas

/**
 * Result of one canvas-source health check, as surfaced by the Settings →
 * Playback → Artwork → "Canvas Check" diagnostic (2026-09-04, user request:
 * "Add an option under artwork header in playback settings named Canvas
 * Check which tells me all the mirrors, my own accounts, APIs or endpoints
 * for canvas are working or not").
 *
 * Every check performs the REAL request the playback path performs — no
 * mock reachability pings — so the reported state is what canvas playback
 * would actually experience right now.
 */
sealed interface CanvasSourceDiagnosis {
    /** Human-readable explanation shown under the source's name. */
    val detail: String

    /**
     * The source answered a valid response — it is working.
     * [canvasFound] additionally reports whether the probe track actually
     * resolved a canvas URL (an endpoint can be perfectly healthy while the
     * specific probe song simply has no canvas).
     */
    data class Ok(
        val canvasFound: Boolean,
        override val detail: String,
    ) : CanvasSourceDiagnosis

    /**
     * The source was contacted but refused or is unusable — an HTTP auth
     * rejection, or a "resolver" answering non-JSON (dead/repurposed domain,
     * rate-limit interstitial, auth wall).
     */
    data class Rejected(
        val httpStatus: Int?,
        override val detail: String,
    ) : CanvasSourceDiagnosis

    /** Network-level failure: DNS, timeout, no route, TLS. */
    data class Unreachable(
        override val detail: String,
    ) : CanvasSourceDiagnosis

    /**
     * The check could not run: no Spotify session, no configured mirrors,
     * probe song not matchable. Nothing is broken — the source just is not
     * in play for this user.
     */
    data class Skipped(
        override val detail: String,
    ) : CanvasSourceDiagnosis
}
