# ArchiveTune 16.0 — Changelog

The sources update: podcasts join the app, Apple Music and Amazon Music become
full members of the source chain, Tidal and the community account pools arrive,
the equalizer grows into a complete Audio Effects console, Home learns to be
Spotify, and the SpatialFlow player finally collapses as smoothly as the
original app.

## Podcasts

- **Podcasts, ported from upstream**: search a show and it appears as its own
  card in the results; shows open a dedicated podcast page with episode lists,
  playback and pagination; the Home page's Podcasts chip is back (it was
  hidden) and home sections render shows and episodes; episodes play as
  normal queue entries with an "N episodes" queue header
- Podcast episodes are first-class citizens everywhere: square thumbnails,
  media-type metadata, no lyrics/scrobble/Discord-presence noise, excluded
  from mixes, quick picks, stats and the auto-radio; blocked-artist and AI
  content filters cover shows and episodes

## Sources & Accounts

- **Apple Music as a full music source**: web sign-in with auto-fetched
  tokens (the login no longer needs a pasted developer token), catalog search
  with suggestions, full-track playback with quality pickers
  (AAC / Lossless / Hi-Res), the Apple Music canvas, and word-synced lyrics
  from the signed-in account
- **Amazon Music source**: account login, settings (quality HD/Ultra HD,
  premium toggle, instances), a slot in the playback source priority, the
  download source priority and the search-from popup, plus a working
  anonymous catalog search client
- **Tidal lossless source**: account or token login with instant
  paste-verification, the Monochrome public instance list with dead-instance
  skipping and racing, Hi-Res Lossless quality
- **Community account pools v2**: lossless sources work without a personal
  login — encrypted-at-rest pool cache, per-user pool API key, community
  paste-list source, dead-account reporting, and a silent background refresh
  on every app open (no toast, one server fetch per 10 minutes)
- YouTube sign-in via OAuth device code; downloadable Japanese romanisation
  language packs

## Player & Audio

- **The Audio Effects console**: the equalizer popup is rebuilt around two
  pills — Equalizer (the original control set restored: basic/advanced mode,
  tone sliders, the device's real band sliders with reset, full-range output
  gain, automatic headroom, profiles with import/export) and Audio effects
  (8D, reverb, bass, loudness, balance, virtualizer and — new here —
  playback speed and pitch, moved out of the song's overflow menu, with an
  independent pitch slider and an "Enable audio effects" master switch that
  gates every effect)
- **SpatialFlow-exact player transition**: one floating artwork layer morphs
  continuously between the mini player's circle and the full player's slot
  (scale, position, corner radius and shadow all track the sheet progress),
  the sheet corners morph instead of popping, the crossfade hands over at the
  halfway point, and the settle spring carries the finger's fling velocity —
  ported from MythicalSHUB/SpatialFlow's own bottom-sheet architecture
- **Instant YouTube stream starts**: upcoming songs are now pre-resolved
  while the current one plays (on by default, two songs ahead), and the
  SimpMusic resolution runs its InnerTube request and NewPipe extraction
  concurrently — first-sound latency is the max of the two round trips, not
  their sum
- Fixed: the 8D/balance processor killed every playback ("The source buffer
  is this buffer"); volume restored after a seek re-buffer; the playing
  notification regression after the lifecycle port (the session is armed
  explicitly again)

## Home, Library & Search

- **Spotify home feed** as a second Home page with the app-bar switcher and
  its own settings
- Library customization (hide/show the Liked/Offline/Cached/Local/Top-50
  cards plus a Recently Liked subsection), New Releases multi-select with a
  live count bar, voice search, UI scale, hide-music-videos, blocked artists
  filtered from playback
- Source check, redone: honest statuses (READY / DEGRADED / NOT_CONFIGURED /
  UNSUPPORTED / UNREACHABLE), cached inline state, real YouTube/JioSaavn/
  Apple/Amazon probes

## Design

- The tinted navigation bar now follows the scheme: a light accent pastel in
  light mode, a deep accent-tinted dark bar in dark mode, flat (no blur), on
  every Android version
- Lyrics pages across every player style share the Apple Music player's 56dp
  header thumbnail (was oversized in the other styles)
- Android Auto settings with the home-screen glass recipe (title inside the
  liquid-glass pill, content scrolling behind the haze band); the equalizer
  dialog draws edge-to-edge behind the status bar with the same treatment
- Year-in-music card exports are full screen and full HD: native pixels ship
  untouched whenever the capture meets the 1080p floor (no more cover-fit
  upscale smear), progressive enlargement below it

## Performance & Size

- YouTube stream resolution rebuilt on the SimpMusic/Echo extractors — the
  embedded yt-dlp/Python layer is gone and the APK dropped from ~50 MB to
  ~33 MB
- BitChord position-tick no longer invalidates the whole player; ported
  memory/lifecycle/GPU fixes (idle-stop restored, resolution-cache clears,
  dead Haze layer removed)
- Dead-code sweep: 141 unused string entries (all locales), 16 legacy
  drawables, orphaned functions and imports removed

## Android Auto

- Android Auto support: the settings page, car browse roots with per-policy
  filtering, voice search, media buttons — plus an Android Automotive
  (AAOSP) build flavor

---

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

- The equalizer popup opens again: the 16.0 rework had switched the dialog
  onto an edge-to-edge window path (FloatingDialogWindowTheme +
  layout-in-decor flags) that crashed on open on real devices — the dialog is
  back on the long-working window configuration, with the status bar still
  hidden while it shows. The dialog also drops the liquid-glass header it had
  grown: it was the only real dialog window in the app drawing the backdrop
  glass (layer recording + AGSL effect passes + a haze source inside a
  separate window), the one ingredient the crashing version still had that
  the long-working one never did — the header is back to plain material3
  icon buttons on the opaque dialog surface
- Non-canvas songs no longer show a misplaced artwork in the SpatialFlow
  player: the shared floating-artwork layer was laid out at the sheet root's
  top-left instead of at the full player's artwork slot, so the expanded
  artwork drew over the top bar with an empty gap where the slot actually
  is — the layer now bases itself on the slot's measured rect and the
  mini-to-full morph math lands it exactly on the slot (plus the slot keeps
  the same title spacing as the video and in-column branches)
- Library playlists keep their header: the Apple-Music-style hero collapsed
  to zero height the moment the playlist's canvas artwork finished loading
  (~1s after opening the page) — every child of the canvas backdrop box was
  matchParentSize, so inside the lazy list the box measured to nothing and
  the playlist information vanished (and the still-running video decode made
  scrolling laggy). The content column now sizes the box, with the canvas
  rendering behind the text as designed; the online playlist screen also
  stops rebuilding its song list instance on every recomposition (another
  scroll-jank source while playback state ticks)
- The Android Auto settings page reserves space for the mini player: the
  page used plain safe-drawing insets for its bottom padding, so the last
  preference rows sat underneath the mini player whenever something was
  playing — it now uses the player-aware window insets, the same recipe as
  the settings main page
- The SpatialFlow floating artwork no longer floats over the lyrics overlay
  and the queue drawer: the lyrics/queue state is now reported up from the
  player (the lyrics flag was previously wired to a signal the SpatialFlow
  style never sets, and the queue had no check at all), and the shared
  artwork layer fades out under both — exactly like the original app. The
  layer also stops drawing a stale-positioned artwork over canvas/video
  playback once the artwork slot reports it is occupied
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

## Fixes (16.0 addendum)

- The SpatialFlow lyrics page no longer closes itself moments after opening:
  the lyrics flag lived in a rememberSaveable keyed on the song id, and any
  transient metadata re-emission (a queue/source resolver swapping the current
  item mid-playback, with the id reverting a moment later) re-initialised that
  state to false — on-device the lyrics page reliably shut itself ~0.9 seconds
  after every tap on the Lyrics pill, with the reveal circle animating shut
  exactly like a user dismissal. The flag is now unkeyed and resets only when a
  genuinely different song id stays put for 250 ms, so resolver flickers can
  never kick you out of the lyrics. Two companion glitches died with it: the
  one-frame full-size artwork flash in the top-left corner right as the reveal
  finished (the flying shared element drew at its raw (0,0) layout slot for a
  frame when the artwork-slot rect was momentarily nulled — the rect is now
  retained while the lyrics own the screen and the layer turns itself invisible
  instead of drawing unpositioned), and the missing 56 dp thumbnail that was
  supposed to park in the lyrics header (the same premature rect null killed
  the flying artwork right after it finished its morph — the Apple-Music-style
  header thumbnail is back)
- The song thumbnail always renders in the mini player in the SpatialFlow
  style: the mini player's artwork slot had become a placeholder ring
  whenever the floating morph layer was expected to draw over it, so canvas
  and video songs (where that layer never draws) — or any moment the layer
  could not — left an empty circle with no artwork and no fallback. The mini
  player now always renders its own thumbnail underneath the morph layer
  (identical image, higher z-index — invisible when both draw), and that
  thumbnail gained the same hardening as every other artwork surface: a
  disk-cache-backed request plus the maxres → hq720 → mq fallback chain, so a
  single failed image request can never park the slot empty
- Opening the lyrics page in the SpatialFlow style no longer flickers: the
  circular-reveal progress was read as a raw float inside the player's main
  composition scope, invalidating the entire player (artwork pager, canvas
  surfaces, controls, queue drawer) on every one of the reveal's ~20 frames —
  the reads are now derived booleans that flip only at the thresholds, and
  the lyrics content composes at 45% of the reveal (still clipped) instead of
  popping in at 80%. The floating artwork's lyrics hand-off also animates
  with the flying artwork's spring: closing the lyrics page used to snap the
  slot artwork back to full opacity while the flying thumbnail was still
  morphing home, putting two artworks on screen at once
- The canvas source picker applies the chosen canvas immediately: picking a
  source in the player's overflow menu only re-wrote the playback cache —
  the visible canvas kept playing the old source until the next track
  change. The picker now pins the choice (replacing any existing entry — the
  old insert kept the previous artwork and silently ignored the tap) and
  publishes it into the live canvas render states, so the playing canvas
  swaps on the next frame; the "Save" download path does the same once the
  videos are on disk
- The canvas source picker dialog grew its requested polish: the
  "Choose Canvas source" title is centred and bold, and each source row
  shows its provider's mark — the Spotify logo for the Spotify canvas, the
  Apple Music logo for the ArchiveTune (Apple Music / BetterLyrics) canvas
- Minimal mode now also applies to the search tab: with the setting on, the
  search page shows only the search field and the recent searches — the
  trending searches chips, trending songs, new albums, moods & genres and
  the recommendation tabs are hidden (the same philosophy as minimal home:
  personal history stays, discovery goes)
- Local playlist pages no longer play canvas in their header: the
  Apple-Music-style hero had grown a looping canvas backdrop resolved from
  the playlist's first song (and the "Enable canvas in albums and playlists
  page" toggle gated it) — local playlists are back to the plain text hero,
  while online, top and Spotify playlist pages keep theirs

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

## Fixes (15.1 addendum, round 2)

- The playing notification is back: the canary lifecycle port had dropped the
  service's self-registered session, which was the only thing arming Media3's
  notification pipeline (the app UI binds the plain local binder, never a
  MediaController) — playback ran with no notification and no foreground
  promotion. The session is now registered explicitly via addSession(), with
  no binding side effects, so idle-stop keeps working
- The tinted navigation bar no longer blurs: it is now a flat, accent-tinted
  bar in both light and dark mode (renamed from "Tint frosted navigation bar"
  to "Tint navigation bar"), and it works on pre-Android-12 devices too
- The Android Auto settings page copies the settings main page header: the
  header row sits flush below the status bar (it was inset twice) and the
  glass back button samples real scrolling content behind it (it was reading
  an opaque empty surface)
- The new-releases selection popup's count line clears the corner radius and
  gets equal vertical padding above and below
- The SpatialFlow lyrics dismiss (X) button lost its outer circle outline

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
