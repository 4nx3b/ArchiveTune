# ArchiveTune 15.1 — Changelog

The follow-up to 15.0: a new Looper player style, the lyrics page rebuilt as a
true whole-page overlay that materialises in place, the lyrics menu back to the
familiar floating card, video playback in two more styles, and the biggest
player-animation performance pass yet.

## New

- New player style: **Looper** (ported from
  [SthrNilshaaa/looper](https://github.com/SthrNilshaaa/looper)) — Jost
  typography, the squiggly ExpressiveSlider, asymmetric 80dp transport pills,
  40dp utility pills, the blurred-sleeve backdrop under a fixed scrim, and the
  Apple-Music-exact canvas twin behind the controls; lyrics use ArchiveTune's
  online lyrics with the enhanced animation
- The lyrics page is now a whole-page overlay over the player controls —
  always full screen, no sheet corners or short box area — for the Cinematic,
  Editorial, Immersive, Material Extended and SimpMusic styles
- Opening the lyrics page no longer slides anything up from the bottom edge:
  the page crossfades and scales in over the player in place (650ms, the Apple
  Music cover-to-lyrics morph), so it can never read as a bottom sheet
- YouTube video playback in the SpatialFlow and Looper styles (the video
  replaces the artwork with its quality pill, exactly like the other styles)
- "Disable blur effects" now also removes the backdrop gradient wash from the
  Home and Search pages (the Library already obeyed it)

## Fixes

- The lyrics overflow menu is back to the 15.0 presentation: the floating menu
  card (song header + action grid) that Liquid Glass frosts when the toggle is
  on, instead of the small anchored popup the page had briefly grown
- The Immersive player's controls are back at the bottom of the screen — a
  layout regression had pinned the whole control block to the top with a
  giant empty gap underneath
- The Liquid Glass lyrics popup keeps its familiar dark fill (glass under a
  deep scrim, opaque near-black without the glass toggle) for the styles that
  still anchor it to their header buttons (Apple Music, SpatialFlow, TikTok,
  SimpMusic)
- Player open/minimise animation no longer fights the app: progress ticks
  pause while the sheet is mid-flight, and the collapsed mini player's
  keep-alive player subtree drops from 10 to 2 updates per second — returning
  to the app and idle scrolling are visibly smoother, and the mini player's
  idle battery drain drops with it
- The canvas artwork video now pauses at the top of the minimise fade instead
  of decoding all the way to the fully-collapsed mini player — minimising the
  player while a canvas plays no longer stutters (most visible in the
  SpatialFlow and Apple Music styles)
- The glass shader prewarm moved out of the cold-open window (it used to
  jank the first seconds of the home feed)
- The tint-frosted navigation bar is finally what its description promises:
  an opaque, accent-tinted bar (25% toward the theme primary) instead of a
  see-through black wash — correct in light mode, moderate brightness in both,
  with icon colours that follow the app theme rather than the system one
- Searching settings no longer offers the "yt-dlp runtime" result that crashed
  on tap; every remaining search route was cross-checked against the real
  navigation destinations, and Android Auto search hits now deep-link with
  auto-scroll to the exact row
- Lyrics provider tests get a second chance: a single slow DNS lookup or
  dropped connection no longer marks a healthy provider as unavailable
- Pull-to-refresh on the home page can no longer silently do nothing: a stuck
  in-flight load no longer blocks later refreshes (120s watchdog), and
  auto-reloads arriving mid-refresh wait for the manual one instead of
  cancelling it
- Tapping a song in Quick Picks plays that song again: the explicit-content
  filter no longer dropped the song you actually tapped from its own queue
  (which made the next song play with the wrong title and audio everywhere)
- Apple Music popup search works without a pasted developer token (it now
  uses the auto-scraped web-player JWT and anonymous catalog search), and
  Deezer login works from regions without Deezer access via a manual ARL
  cookie entry with a verify button
- Scrolling any tab's list hides the bottom navigation bar completely and the
  mini player smoothly takes over the freed space; scrolling back up brings
  the bar back just as smoothly (the SpatialFlow behaviour)

---

## Features (15.1 addendum)

- The equalizer in the song overflow menu gained SpatialFlow's audio effects:
  **Reverb** (None / Small Room / Medium Room / Large Room / Medium Hall /
  Large Hall / Plate — SpatialFlow's exact parameter map), **stereo balance**,
  and **8D audio** implemented as a real-time processor inside the playback
  pipeline — no FFmpeg, no intermediate files, works for streamed songs too,
  and reacts to the speed slider instantly. Spatial settings are saved with
  the equalizer profiles
- The SpatialFlow player style's lyrics opening is now complete: alongside the
  circular reveal, the album art morphs into a compact thumbnail in the top
  bar while the lyrics expand and parks there until they close (canvas songs
  keep their canvas fade)
- Liquid Glass is more liquid everywhere: stronger edge refraction (taller
  band, ~25% stronger bend, depth effect on), more vivid colour bleed from
  the scrolling content behind it, and the big frosted popups actually got
  CHEAPER on the GPU (32dp → 20dp blur pays for their new refraction)

---

# ArchiveTune 15.0 — Stable Changelog

The biggest update since the first stable release: new music sources, new player
styles, a Liquid Glass redesign, AI-powered lyrics, and hundreds of fixes.

## Appearance

- Liquid Glass design across the app
- Liquid-glass popups: compact height cap (40% of the screen), visible row
  dividers on every glass menu, and the glass effect only draws when a live
  backdrop is available; floating popups with Liquid Glass OFF get a
  redesigned "solid sheet" (one opaque elevated surface, hairline edge,
  outlined action tiles) with no square corners peeking past the rounded
  border, shadow and clip
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
- Menu row dividers span the full row at a consistent hairline weight
- App icon packs — applying an icon switches the real launcher icon on the
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
- Enhanced lyrics render in the new player styles too (SpatialFlow overlay,
  SimpMusic fullscreen sheet; BitChord shows the seek-preview line while
  scrubbing)
- Musixmatch experimental lyrics
- Lyrics API check
- Automatic AI translation
- AI romanisation and automatic AI romanisation
- Exclude languages for auto translation and romanisation
- Direct API link for each AI provider
- More AI providers
- Separate AI provider for translation and romanisation — a dedicated provider
  does all romanisation while the main one only translates; the romanisation
  cache persists across restarts keyed per provider config, Mistral gained
  working completions, and OpenRouter/Mistral get a model picker
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
- Lyrics active line no longer freezes after a song change, and the
  enhanced-lyrics restart race is fixed
- SpatialFlow: canvas/lyrics overhaul — AM-exact canvas layering (frosted
  twin, scrim, sharp-stage fade), constant-white lyrics over the dark
  blurred-artwork backdrop in both themes, canvas freeze-on-lyrics-open,
  working queue reordering, pinned quality pill, bottom-pinned no-canvas
  layout, overflow icon and light-mode text fixes
- Cinematic player light mode: the lyrics text now follows the player's
  own text colour (dark ink on the light theme surface, white over artwork
  backgrounds) instead of constant white that vanished against the light
  background
- Cinematic and Immersive players: the overflow (three-dot) icon next to the
  now-playing title is removed — the row keeps share and like; the full song
  menu stays reachable from the queue
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
