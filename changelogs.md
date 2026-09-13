# ArchiveTune 15.0 — Stable Changelog

The biggest update since the first stable release: new music sources, new player
styles, a Liquid Glass redesign, AI-powered lyrics, and hundreds of fixes.

## Appearance

- Liquid Glass design across the app
- Liquid-glass popups: compact height cap (40% of the screen), visible row
  dividers on every glass menu (Spotify playlist rows and sleep-timer sections
  included), and the glass effect only draws when a live backdrop is available
- Floating popups with Liquid Glass OFF: redesigned "solid sheet" — one opaque
  elevated theme surface with a hairline edge, a drag handle, a flat header,
  outlined action tiles and flat sections with hairline dividers (replaces the
  old grey-on-grey translucent card stack; the glass mode is unchanged)
- Hide status bar
- Canvas playback in the albums page
- Show Lyrics toggle, Auto Enter AOD, Enter AOD when screen dims
- Tablet mode
- Minimal mode
- Hide scrollbar
- Mini player styles
- Navigation bar dimensions and label customisation
- New Apple Music player style with animations ported from Vivi Music
- New player styles: SpatialFlow, BitChord, SimpMusic, Editorial and Material
  Extended — Editorial/Material Extended refreshed from upstream's exact V9/V10
  code
- Music haptics (from SpatialFlow) — driven by a real PCM tap in the audio
  processor chain, no RECORD_AUDIO permission needed
- Redesigned Home, playlist, search UIs, profile popups, and new releases
- Redesigned History and stats screens
- Sleep timer with a draggable slider
- New icons for the whole app
- Lyrics text customisation and vinyl mode with preview for lyrics/song share
- New Apple Music-style popup in the Apple Music lyrics style
- Lyrics overflow popup matches the main branch exactly, scales in from the
  anchor icon, and uses a neutral scrim while open
- Menu row dividers span the full row at a consistent hairline weight (the old
  one-sided inset dimmed only the centre of the row)
- App icon packs — applying an icon now switches the real launcher icon on the
  home screen and app drawer; the downloadable pack is 96% smaller (WebP
  rasters, aliases kept) with a pinned integrity digest
- SF Pro font picker rows render a live specimen of the real font

## Features

- Video playback
- Picture-in-picture mode
- Spotify Canvas
- Canvas artwork is gated only by its own toggles — turning off video playback
  no longer disables canvas
- Artwork priority
- History duration down to a minimum of 1 second
- JioSaavn source
- Deezer Premium streaming with full-quality decryption and real downloads
- Qobuz direct playback, lossless matching, and backup source
- TikTok source: inline queue, artist avatars, video thumbnails (with a
  thumbnail fallback chain, palette from the raw URL and next-page prefetch)
- Echo-Music playback
- SponsorBlock for YouTube
- Download source priority
- Word-by-word synced (karaoke) lyrics
- Prioritise word-synced lyrics
- Enhanced lyrics now render in the new player styles too (SpatialFlow overlay
  and SimpMusic fullscreen sheet share the word-synced view; BitChord shows the
  seek-preview line while scrubbing)
- Musixmatch experimental lyrics
- Lyrics API check
- Automatic AI translation
- AI romanisation and automatic AI romanisation
- Exclude languages for auto translation and romanisation
- Direct API link for each AI provider
- More AI providers
- Separate AI provider for translation and romanisation — when enabled the
  dedicated provider does ALL romanisation and the main provider only
  translates; romanisation cache is persisted across restarts and keyed per
  provider config, Mistral gained working completions, and OpenRouter/Mistral
  get a model picker
- AI batches run in parallel with higher rate limits — translations and
  romanisations land visibly faster
- API token compressor for token savings
- Source check for every playback source
- WebAuth login for Last.fm and Libre.fm
- Import playlists from any provider
- YouTube Music region change
- Export downloads with a folder picker
- Save canvas and save cover
- Automatic cloud storage backup
- Google Drive backup provider and statistics backup (listening stats ride
  along in every backup and merge back idempotently)
- Search with inline switches and auto-scroll behaviour
- Last.fm dashboard
- Spotify feature improvements and additions
- Lyrics "from" and "written by" credits
- Undo translation
- Major enhanced lyrics upgrades
- Songs preload
- Listen Together: host or join a synced listening session
- Telegram rebuilt on TDLight — slimmer APK, reliable OTP login, streaming
  fixes, and a runtime engine download pill on the integration page
- Inline video playback in the artwork slot, fullscreen with true landscape,
  captions, and up to 4K quality
- Translations refreshed from Weblate (upstream catalogue merged, fork-only
  entries preserved)

## Fixes

- Decluttered UI
- Fixed 90% of the bugs and visual glitches present in the original app
- Dead code removal (unused API services, solver, helpers — ~4300 lines swept)
- Lots of optimisations to make the app smoother
- Low-end device optimisations, GPU-friendly artwork, preloaded tabs
- Canvas frosted backdrop renders a downscaled frost twin instead of a
  full-resolution layer — major GPU savings
- Shared HTTP client across the stack (fixes a connection leak)
- Playback starts the moment the source resolves — the artificial first-byte
  timeout is gone
- Fixed download corruption and HTTP 403 failures
- Bounded YouTube download retries, bounded cache waits with a single
  auto-retry, and live per-item download progress
- Fixed Apple Music player crash, YouTube playback stalls, lyrics lag and
  misalignment, queue controls, and Last.fm decoding
- Lyrics active line no longer freezes after a song change (stale position
  provider), and the enhanced-lyrics restart race is fixed
- SpatialFlow lyrics: no more opaque colour flash when opening lyrics — the
  blurred artwork backdrop is pre-warmed and composes on the first frame
- SpatialFlow lyrics performance: pre-blurred bitmap backdrop on all APIs,
  the covered main player column drops out of composition once lyrics are
  revealed, and karaoke character paths are cached per layout
- SpatialFlow canvas freezes the instant lyrics open (no background decoding
  during the fade) and resumes from the exact paused position on close
- SpatialFlow canvas layering rebuilt to Apple Music's exact recipe —
  full-height frosted twin, AM scrim, sharp stage with the 0.62→1.0 fade
- SpatialFlow queue reordering works (optimistic drag with commit on release)
- SpatialFlow time row: the quality pill is pinned to the centre between the
  timestamps instead of drifting with label widths
- SpatialFlow light mode: text reads over every surface — on-canvas text is
  white (the canvas always renders behind a dark scrim, so light mode no
  longer paints near-black text over it), the no-canvas blurred backdrop
  uses a white scrim in light theme instead of the black one that sank dark
  artworks into an unreadable wash, and the player follows the app's
  dark-mode setting (ON/OFF/AUTO) instead of the raw system state
- SpatialFlow artwork layout: with no canvas playing, the thumbnail and the
  control stack pin to the bottom of the player exactly where the bottom
  controls sit while the canvas plays — no more floating mid-screen or
  jumping when the canvas resolves (the fixed top-offset calculation is
  removed)
- Default and SpatialFlow players: the three-dot song overflow menu sits next
  to the "Now Playing" header, top right, opening the full song menu
- BitChord canvas actually plays now (the canvas resolver used to clear the
  artwork for that style unconditionally)
- Lossless tracks no longer randomly mute (silence-skip processor removed —
  bit-perfect output)
- Update notifications: tap the action to download with live progress in the
  notification bar and an install prompt on completion
- Qobuz backup: the community mirror went dark — resolvers now walk a
  user-configurable endpoint chain (Settings → Sources → Qobuz backup), with a
  10-minute circuit breaker so a dead mirror never slows playback
- About/onboarding links point at the project's real destinations (website,
  donate, privacy)

---

**Full change history:**
[Compare v14.0.5362...main](https://github.com/4nx3b/ArchiveTune/compare/v14.0.5362...main)
