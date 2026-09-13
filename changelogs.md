# ArchiveTune 15.0 — Stable Changelog

The biggest update since the first stable release: new music sources, new player
styles, a Liquid Glass redesign, AI-powered lyrics, and hundreds of fixes.

## Appearance

- Liquid Glass design across the app
- Hide status bar
- Canvas playback in the albums page
- Show Lyrics toggle, Auto Enter AOD, Enter AOD when screen dims
- Tablet mode
- Minimal mode
- Hide scrollbar
- Mini player styles
- Navigation bar dimensions and label customisation
- New Apple Music player style with animations ported from Vivi Music
- New player styles: SpatialFlow, BitChord, SimpMusic, Editorial
- Music haptics (from SpatialFlow)
- Redesigned Home, playlist, search UIs, profile popups, and new releases
- Redesigned History and stats screens
- Sleep timer with a draggable slider
- New icons for the whole app
- Lyrics text customisation and vinyl mode with preview for lyrics/song share
- New Apple Music-style popup in the Apple Music lyrics style
- App icon packs — applying an icon now switches the real launcher icon on the
  home screen and app drawer

## Features

- Video playback
- Picture-in-picture mode
- Spotify Canvas
- Artwork priority
- History duration down to a minimum of 1 second
- JioSaavn source
- Deezer Premium streaming with full-quality decryption and real downloads
- Qobuz direct playback, lossless matching, and backup source
- TikTok source: inline queue, artist avatars, video thumbnails
- Echo-Music playback
- SponsorBlock for YouTube
- Download source priority
- Word-by-word synced (karaoke) lyrics
- Prioritise word-synced lyrics
- Musixmatch experimental lyrics
- Lyrics API check
- Automatic AI translation
- AI romanisation and automatic AI romanisation
- Exclude languages for auto translation and romanisation
- Direct API link for each AI provider
- More AI providers
- Separate AI provider for translation and romanisation
- API token compressor for token savings
- Source check for every playback source
- WebAuth login for Last.fm and Libre.fm
- Import playlists from any provider
- YouTube Music region change
- Export downloads with a folder picker
- Save canvas and save cover
- Automatic cloud storage backup
- Google Drive backup provider and statistics backup
- Search with inline switches and auto-scroll behaviour
- Last.fm dashboard
- Spotify feature improvements and additions
- Lyrics "from" and "written by" credits
- Undo translation
- Major enhanced lyrics upgrades
- Songs preload
- Listen Together: host or join a synced listening session
- Telegram rebuilt on TDLight — slimmer APK, reliable OTP login, streaming fixes
- Inline video playback in the artwork slot, fullscreen with true landscape,
  captions, and up to 4K quality

## Fixes

- Decluttered UI
- Fixed 90% of the bugs and visual glitches present in the original app
- Dead code removal
- Lots of optimisations to make the app smoother
- Low-end device optimisations, GPU-friendly artwork, preloaded tabs
- Fixed download corruption and HTTP 403 failures
- Bounded YouTube download retries
- Fixed Apple Music player crash, YouTube playback stalls, lyrics lag and
  misalignment, queue controls, and Last.fm decoding
- SpatialFlow lyrics: no more opaque colour flash when opening lyrics — the
  blurred artwork backdrop is pre-warmed and composes on the first frame
- Floating popup menus with Liquid Glass OFF: fully opaque surface with proper
  theme colours (no ghosting of controls through the card, no translucent
  action tiles)
- Default player: the three-dot song overflow menu returns next to the
  "Now Playing" header, top right
- Qobuz backup: the community mirror went dark — resolvers now walk a
  user-configurable endpoint chain (Settings → Sources → Qobuz backup), with a
  10-minute circuit breaker so a dead mirror never slows playback
- Lossless tracks no longer randomly mute (silence-skip processor removed —
  bit-perfect output)
- SpatialFlow queue reordering works (optimistic drag with commit on release)
- Update notifications: tap the action to download with live progress in the
  notification bar and an install prompt on completion

---

**Full change history:**
[Compare v14.0.5362...v15.0](https://github.com/4nx3b/ArchiveTune/compare/v14.0.5362...v15.0)
