/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import moe.rukamori.archivetune.spotify.SpotifyLibraryRepository

/**
 * Hilt entry Point exposing [SpotifyLibraryRepository] to non-Hilt call sites
 * (e.g. the cross-service playlist import dialog, which is a plain Composable
 * and cannot use `@Inject constructor` directly).
 *
 * Usage:
 * ```
 * val repo = EntryPointAccessors
 *     .fromApplication(context, SpotifyRepositoryEntryPoint::class.java)
 *     .spotifyLibraryRepository()
 * ```
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SpotifyRepositoryEntryPoint {
    fun spotifyLibraryRepository(): SpotifyLibraryRepository
}
