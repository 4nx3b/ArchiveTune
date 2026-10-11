/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import moe.rukamori.archivetune.R

@Immutable
sealed class Screens(
    @StringRes val titleId: Int,
    @DrawableRes val iconIdInactive: Int,
    @DrawableRes val iconIdActive: Int,
    val route: String,
) {
    object Home : Screens(
        titleId = R.string.home,
        iconIdInactive = R.drawable.home_outlined,
        iconIdActive = R.drawable.home_filled,
        route = "home",
    )

    object Search : Screens(
        titleId = R.string.search,
        iconIdInactive = R.drawable.search,
        iconIdActive = R.drawable.solar_magnifer_bold,
        route = "search",
    )

    object Library : Screens(
        titleId = R.string.filter_library,
        iconIdInactive = R.drawable.library_outlined,
        iconIdActive = R.drawable.library_filled,
        route = "library",
    )

    object MoodAndGenres : Screens(
        titleId = R.string.mood_and_genres,
        iconIdInactive = R.drawable.style,
        iconIdActive = R.drawable.style,
        route = "mood_and_genres",
    )

    companion object {
        // CRITICAL: these lists MUST stay lazy.
        //
        // An eager `listOf(Home, Search, Library)` in the companion initializer
        // creates a class-initialization cycle with the object subclasses: if
        // the first-ever touch of the Screens family is a SUBCLASS static
        // (e.g. `Screens.Home.route` — which the MainActivity atmosphere-route
        // check reads before anything else), the JVM initializes Home first,
        // which initializes the Screens superclass, whose <clinit> then reads
        // Home.INSTANCE REENTRANTLY (still null) and bakes a null element into
        // both lists forever. The first `MainScreens.map { it.route }` then
        // throws NullPointerException — the startup crash of build 3b138b559
        // (MainActivity line ~1264, `navigationItems.map(Screens::route)`).
        //
        // `by lazy` defers the list construction until normal (post-init)
        // access, so every class is fully initialized when the elements are
        // read, regardless of which Screens member is touched first.
        val MainScreens: List<Screens> by lazy { listOf(Home, Search, Library) }
        val TvMainScreens: List<Screens> by lazy { listOf(Home, Search, Library) }
    }
}
