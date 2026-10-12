# AGENTS.md — ArchiveTune (vossgraves fork of 4nx3b/ArchiveTune)

Active development on `dev`, tracking `4nx3b/ArchiveTune@dev`. Any agent working
here must preserve the invariants below.

## Mandatory reading order (every task, before any coding)

1. **`rules.md`** (repo root) — the repository owner's standing rules, in full.
   The non-negotiables:
   - **Never compile locally.** Push to `dev`, monitor the CI build for **7 minutes**
     (`scripts/poll_ci_sha.sh <head-sha> 7`), and fix it if it fails.
   - **Senior Engineer Mode + Strict Mode**: understand before changing, smallest
     complete solution, zero regressions, evidence-driven verification, adversarial
     self-review, closed-loop process (Inspect → Understand → Plan → Implement →
     Integrate → Verify → Review).
   - **Always maintain `AGENTS.md` and `worklog.md`. Always use English.**
2. **`AGENTS.md`** (this file) — fork invariants, module layout, build/test contracts,
   dependency gotchas.
3. **`worklog.md`** — what previous tasks did; append an entry for the current task
   before finishing (format: `Task ID` / `Agent` / `Task` / `Work Log` /
   `Stage Summary`).

## Fork invariants — never break

- **Tidal source**: `app/src/main/kotlin/moe/rukamori/archivetune/tidal/` (`TidalAudioProvider`, `TidalAccountManager`, `TidalInstanceHealthManager`, `TidalDns`, `TidalArtworkProvider`), Tidal settings/login UI, `utils/tidal/`; instance racing documented in `INSTANCE_RACING.md`. Live progressive-DASH streams use the `tidal-dash://` scheme routed in `MusicService`.
- **Multi-source audio**: providers live in top-level packages `tidal/`, `deezer/` (DeezerCrypto + Media3 decrypting DataSource), `qobuz/` (+ `QobuzBackupProvider` via the kouzu.in mirror), `spotify/`; shared contract (`DirectStream`, `TitleMatch`, source priority) in `audiosource/`. Playback resolution goes through `resolveMultiSourceDataSpec` in `playback/MusicService.kt`; YouTube is the final fallback. Do not rewire playback around this.
- **Spotify is catalog + OPTIONAL experimental direct streaming**: `spotifycore/` + app `spotify/` provide metadata, artwork, search, playlist import and track-to-YouTube identification. `AudioSourceType.SPOTIFY` (default OFF, `SpotifyDirectEnabledKey`) adds direct streaming from Spotify's CDN with two selectable resolve methods (`SpotifyResolveMethodKey`, bottom-sheet picker in the Sources page): **Stopify** (`spotify/SpotifyWebStreamer.kt` — Dealer WS → device registration → connect-state play command → storage-resolve; the device registers BOTH `file_ids_mp4` and `file_ids_mp4_dual` manifest formats and the Dealer handler always prefers the standard `file_ids_mp4` entry — never register only the dual format, those files decode to silence; Widevine through `MusicService.buildSpotifyDrmManagerOrNull` (per-player manager cached inside `createMediaSourceFactory`, never shared between the primary and crossfade players)/`SpotifyLicenseCallback` with default security level and standard provisioning — do not force L3 or throw on provisioning; anti-mute playback reporting on start/30s/finish via `reportPlayback` driven from `updateSpotifyPlaybackReporting` in `onMediaItemTransition`) and **Echo Music** (`spotify/echo/` — desktop device-pairing token refreshed against the live app version from loadspot.pages.dev with a web-persona client-token fallback, hand-rolled extended-metadata protobuf for the audio file list, storage-resolve, PlayPlay key; `echo-spotify://` scheme routed to `EchoDecryptingDataSource`, an AES-128-CTR decrypting DataSource modelled on the Deezer one; Ogg payloads skip the 0xA7 custom header, FLAC starts at 0 — this mirrors librespot's `SPOTIFY_OGG_HEADER_END` behavior; Echo failures fall back to the Stopify pipeline). Keep the experimental gate + ban warning UI in sync with this.
- **SoundCloud source**: `soundcloud/SoundCloudAudioProvider` (MetroFusePlus port, `AudioSourceType.SOUNDCLOUD`, default OFF) resolves anonymously via scraped client_id; the playback OkHttp client's interceptor strips the `_archivetune_soundcloud*` marker params and injects browser headers. Progressive transcodings are played directly; HLS-only tracks ride the `soundcloud-hls://` scheme (base64-encoded playlist URL) routed to `playback/SoundCloudHlsDataSource`, which synthesizes a progressive TS stream from the playlist segments — the same pattern as `tidal-dash://`. Never return a raw `https://…m3u8` URL from the resolver: the lazy DataSpec resolution feeds HLS playlists to progressive extractors and fails. Search-category playback pins `MediaMetadata.preferredSource` + `soundCloudUrl` (direct permalink → trusted resolve).
- **Playback client policy**: `AutoChoosePlaybackClientKey` + `YTPlayerUtils` own bounded YouTube client selection/fallback (incl. video-scoped PO tokens and the yt-dlp layer). Manual mode must remain available.
- **Telegram streaming**: `telegram/` (TDLib wrapper + `TelegramDataSource`), browse/settings/login UI. Playback routed by the `telegram://` branch in `MusicService`'s `SchemeRoutingDataSource`, independent of the multi-source chain. Channels materialise as local playlists (`LPtg<chatId>`); artwork via the Coil `tgart://` fetcher. api_id/hash via `BuildConfig` with public Telegram Desktop fallback.
- **CI signing**: workflows sign with the committed `app/persistent-debug.keystore` when the `KEYSTORE` secret is absent. Do not let upstream workflows overwrite this.
- **Listen Together**: LAN + vivimusic public servers only. No koiverse REST/WS path and no `*.koiverse.cloud` / `raw.githubusercontent.com/koiverse/*` call anywhere in the tree.
- **Flamingo player canvas morph**: `ui/player/flamingo/FlamingoPlayer.kt` renders the canvas through the SAME shared-element system upstream's `AppleMusicPlayer` ships — `FlamingoCanvasStage` lives inside the Album branch of `SharedTransitionLayout`/`AnimatedContent` carrying `sharedBounds(ShareAlbumKey)` (non-bouncy spring, StiffnessMediumLow, 600ms symmetric cross-fades, AdaptiveCornerShape overlay clip), mirroring `AppleMusicMiniHeader`'s artwork in `FlamingoPlayingBar`. The canvas ExoPlayer is disposed with the Album branch on page changes and recreated on return (upstream's documented tradeoff; the blurred backdrop instance in the background layer is the retained one). The sharp stage's bottom dissolve (`FlamingoSharpStageFadeBrush`, DstIn from 0.62) and the frost fraction derive from the same constant, as in upstream's `fadeBottom`. Do NOT reintroduce a custom offset/layout flight between the stage and the bar slot — three such rewrites shipped jank before the upstream port. The moving-blur background (`FlamingoBackground.kt`) is a Compose port of Flamingo's KenBurnsView (`RandomTransitionGenerator` rect math, MIN_RECT_FACTOR 0.75, AccelerateDecelerateInterpolator) at the fork's tuned 3.5–11s transition pace — keep the pace, keep the math.
- **Protected files**: `applicationId` (`moe.rukamori.archivetune`) is fork identity — never adopt upstream changes to it. `Koiverse.jks*`, `ArchiveTuneKoiverseServer.txt`, `DataServer.txt` were removed and must never be restored.
- **SukiSU liquid glass navigation bar**: `ui/component/sukisu/SukisuLiquidNavBar.kt` + `SukisuGlassSupport.kt` are a SEPARATE nav-bar style (`SukisuLiquidGlassNavBarKey`, mutually exclusive with frosted/tint/liquid-glass/bitchord in `NavigationBarSettings`). They port SukiSU-Ultra's FloatingBottomBar (ghost accent row recorded into a `LayerBackdrop` + `rememberCombinedBackdrop`, kyant `lens` refraction, gravity-quantized specular highlights, `SukisuInteractiveHighlight` AGSL spotlight, rubber-band drag on `LiquidGlassDragAnimation`). Never merge this path into the normal `FloatingNavigationToolbar` liquid-glass bar or share tuning state with `LocalLiquidGlassTuning`.
- **Back animation style**: `BackAnimationStyleKey` (DEFAULT/PREDICTIVE, enum in `constants/PreferenceKeys.kt`) is provided app-wide via `LocalBackAnimationStyle` from MainActivity. `PredictiveStyleBackHandler` + `rememberPredictiveSheetBackState` (`ui/component/PredictiveBack.kt`) are the ONLY sanctioned back-handler wrappers for sheets (`BottomSheet`, `BottomSheetPage`, `BottomSheetMenu` use them); the progress `Animatable` must stay hoisted OUTSIDE any conditional composition so the cancel/commit reset survives handler disposal.
- **SukiSU About page**: `AboutScreen.kt` renders over `SukisuAboutBackground` (`ui/screens/settings/about/`) — the OS3 blob-field AGSL shader, 60fps node loop, spring color cycling, scroll-faded. The links card refracts that background through the `aboutGlassBackdrop` `LayerBackdrop`; the parallax logo stack tracks element Y positions captured once (`onGloballyPositioned` guards `!= 0f`). Keep the sukisu physics when touching this page.

## Modules & submodules

- Gradle modules: `:app :core :moriextractor :audio:decent-usb-audio-driver :spotifycore :canvas :jiosaavn :lastfm :musixmatch :shazamkit :morideobfuscator :lyrics:kugou :lyrics:lrclib :lyrics:betterlyrics :lyrics:unison :lyrics:youlyplus` (see `settings.gradle.kts`).
- Canvas settings live on their own sub-page: `settings/player/canvas` (`CanvasSettingsScreen`) — canvas sources, resolvers, the procedural static-artwork motion (`canvas/procedural/`, Sekai Tune port: Kawarp + Square Tunnel AGSL, audio-reactive) and artwork priority. Ambient mode was fully removed (route, screens, strings); AOD true-ambient and the video-player ambient glow are separate legacy features and remain.
- Dolby/FFmpeg decoder (audiophile page): `media3-decoder-ffmpeg` resolves ONLY from the dmt-media3 maven repo via `exclusiveContent` in `settings.gradle.kts`; its transitive media3 artifacts are excluded in `app/build.gradle.kts` so the app's pinned media3 stays authoritative. `DolbyFfmpegDecoderKey` flips `EXTENSION_RENDERER_MODE_PREFER` in `MusicService.createRenderersFactory` (read once — restart playback to apply) and is mutually exclusive with bit-perfect, USB-exclusive and audio offload (both directions, enforced in `AudiophileSettings`).
- Submodules: `core` → **4nx3b/core** (NewPipeExtractor-based InnerTube client), `lyrics` → **4nx3b/lyrics**, `IconPack` → rukamori, `morideobfuscator` → rukamori. Commit + push inside a submodule first, then pin the gitlink; never leave a dirty or unpushed pointer.
- Downloads: Media3 DownloadManager over `downloadCache` spans (files only materialize at export, `ExportDownloadedSongsScreen` + `AudioTagger`). Every download also runs `DownloadExtrasCoordinator` on queue: lyrics fetched through the user's `LyricsProviderOrderKey` priority into the lyrics DB and canvas resolved through the artwork-provider priority into `CanvasArtworkPlaybackCache` (video files cached to disk), so downloaded songs play offline with lyrics and canvas. Exports embed the stored lyrics as `FieldKey.LYRICS` and the canvas url as `FieldKey.CUSTOM1` by default.

## Build & test

- JDK 21, Android SDK (compileSdk 37), Gradle wrapper 9.6.1.
- Flavor matrix: gms/foss × mobile/tv × universal/arm64/x86_64. Debug check: `./gradlew assembleGmsMobileUniversalDebug`.
- Fork contract tests (palette/crossfade/artwork/multi-source/presence/telegram — sources in `app/src/test/`): `./gradlew :app:testGmsMobileUniversalDebugUnitTest`. Run after any merge or feature change.
- Keep the GPL header banner on new `.kt` files. No formatter task — match surrounding style.

## Dependency gotchas

- `settings.gradle.kts` declares a GCS mirror of Maven Central **before** `mavenCentral()` — do not reorder (Maven Central 429-rate-limits CI).
- JitPack is scoped via `exclusiveContent` to an allow-list of `com.github.*` groups (TeamNewPipe, tdlibx, PRDownloader, jaudiotagger, MetrolistGroup…). A new `com.github.*` dependency fails until its group is added.
- The embedded Python/yt-dlp layer (Chaquopy) was removed on 2026-08-26: YouTube stream resolution is native-only via the compiled InnerTube core (`NativeStreamRepository` + BotGuard/QuickJS PO tokens). Upstream still carries Chaquopy — expect merge conflicts in `app/build.gradle.kts`, `gradle/libs.versions.toml`, and `playback/stream/*` on sync; resolve them by keeping the fork's native-only shape.

## Automated upstream sync

`.github/workflows/upstream-sync.yml` merges hourly via `scripts/upstream_sync.sh` + `scripts/ai_resolve.py`, following these invariants. If you change a fork feature or protected file, update the invariants in **both** this file and the script's verification section.

## Agent hygiene

- Never commit credentials, tokens, keystores or agent session state (`.swarm/`, `.opencode/`, `.jcode/` stay untracked).
- Local working notes (`ARCHIVETUNE_SESSION_NOTES.md`, `ARCHIVETUNE_CODE_MAP.md`) are scratch memory — never ship them.
