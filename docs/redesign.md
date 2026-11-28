# UI redesign spec

One consistent layout system for every platform. Same features as before; this changes structure, sizing and styling only. Kotlin (Android + Linux) first, then Mac/iOS (SwiftUI) to match. iOS and Mac keep native behaviours where noted. Wireframes live in Figma (not linked here, to keep the repo shareable); this file is the contract.

## 1. Window classes (one rule, replaces 600 / 640x520 / isWideLayout mixes)

| Class | Width | Navigation | Page margin | Gutter | Detail pages | Now Playing |
|---|---|---|---|---|---|---|
| Compact | < 600 | Bottom tab bar (56) | 16 | 12 | Stacked | Full-screen sheet, queue below |
| Medium | 600-1023 | Rail (80) | 24 | 16 | Stacked, bigger art | Full-screen, two columns |
| Expanded | >= 1024 | Sidebar (240) | 32 | 20 | Two panes (info 320 / list) | Two columns: player left, queue right |

Desktop windows go down to 360 x 480, remember size and position, and pick the class from the window width, not the platform. (Done already: min size, persistence, width-based `isWide`; Mac compact bottom bar.)

Content max width 1600, then centred. No 720 dp "islands".

## 2. Navigation shell

- Five destinations everywhere, same order: Home, Library, Search, Downloads, Settings.
- Compact: bottom tab bar (icon over 11 sp label, selected = blue, hairline above), mini player docked directly above it.
- Medium: rail 80 wide (icon over label, selected item has blue tint pill), mini player docked at the bottom of the content area.
- Expanded: sidebar 240: brand row, the five destinations (selected = blue tint, blue text), a "Playlists" list (first few playlists, tap opens), and a connection status row at the bottom (green dot + server name). Mini player docked at the bottom of the content pane (72 high).
- Mini player: docked full width of the content area (not a floating narrow pill): 44 art, title + artist/album, prev/play/next; on Medium/Expanded also a seek bar, Queue and Cast buttons; a 3 dp progress line on top edge. The progress line, seek bar and Cast icon use the same art-derived accent as Now Playing (blue without art). Tapping it opens Now Playing.
- Per-destination back stacks, tap active tab to pop to root, Esc/back unchanged.
- Hide the tab bar while the keyboard is up (Android).

## 3. The one grid (albums, artists, playlists, favourites, random, search results, downloaded, recently played, "more by artist", artist albums, Home shelves)

columns = max(2, floor((contentWidth + gutter) / (minCard + gutter))); card width = (contentWidth - (columns-1) * gutter) / columns; cards fill their column. contentWidth = window/pane width minus 2 x page margin.

Density (global, remembered, one setting for every grid): **S** minCard 104, **M** 148 (default), **L** 200.
Resulting columns (content width = window or pane width minus the page margins; window 390 / 754 / 1280 with rail and sidebar gives content 358 / 658 / 976): M = 2 / 4 / 5, S = 3 / 5 / 8, L = 2 / 3 / 4.

- **A-Z strip** (Library sorted by name/artist): 22 wide, over the right edge. The grid's end margin is max(page margin, 22 + **8** gap) = 30 on Compact and Medium, 32 on Expanded, so cards never touch the strip: contentWidth = pane - margin - max(margin, 30). Compact 390 -> 344 content (still 2 columns). List view keeps the same 30 at the end.
- Replaces: fixed 120 dp phone cards, 140 dp Home rows, 152 adaptive AlbumList, 392 dp Random column, Playlists ignoring zoom, Recently played's fixed 3/4/5 columns, `GridZoom` 3-10 columns.
- Home shelves are horizontal rows of the same cards (same width as the grid column). Compact shows a peek of the next card; wider windows show whole cards.
- Card: square art radius 10 (artists: circle), title 14 semibold, subtitle 12 secondary (artist, or year on the Artist page), row gap = gutter + 8. Long-press / right-click menus as today.
- Density control = segmented S / M / L in the page header on Medium/Expanded; on Compact it lives in the "Sort & view" sheet with the same options. Present on every grid page (Library tabs, Favourites, Random, Search results, Downloads, Recently played, Artist).

## 4. Page structure

- **Page header** (every top-level page): title 28 bold on the left, actions on the right (Sort, Grid/List segmented, density S/M/L, Filter, Edit, Refresh as the page needs). Compact collapses sort/view/density/filter into one "Sort & view" button opening a sheet. Detail pages (Album, Playlist, Artist, AlbumList, Recently played) have a back control on the left (chevron + "Back" on desktop, icon on touch) and the same action area; the big title sits in the content.
- **Section header** (everywhere): 20 semibold, sentence case, optional "See all >" in blue on the right. Replaces 13 uppercase / 13 sentence / 17 semibold variants.
- **Tabs** (Library: Albums, Artists, Favourites, Playlists): 15 sp, selected = white bold with a 3 dp **pink** underline, hairline under the row, left aligned. Replaces SpaceAround labels and the reserved accessory width.
- **List row** (one spec): 56 high, 40 art (radius 6), title 15 medium, subtitle 12 secondary, optional trailing meta (13 secondary) and ⋯, hairline divider inset to the text. Used by Library list view, Search playlists, Downloads, Queue, tracks (leading number instead of art), playlist entries. List view on Medium/Expanded flows into 2 columns instead of stretching.
- **Settings-style group**: section header above a rounded (16) container of 52-high rows (label left, value/toggle/chevron right) with hairlines; optional **caption** under a row label (12 secondary, e.g. Maniac Mode's) and a **footer** paragraph under the group (12 secondary). Keep all existing captions/footers.
- **Banner** (update, offline, error): one style: rounded 16 surface, text, optional outlined action chip. Real icons, not text glyphs.
- **Empty state**: one style: 48 icon, title, message, optional action. **Spinner**: one size (24), centred; skeleton text replaces "Loading..." strings where it is cheap.
- **Chips / buttons**: pill 32 high chip (13 medium) for secondary actions; primary button 40 high pill, blue fill, white label. Buttons that start playback always show the play icon (16, 8 gap) before the label; Shuffle and Download show theirs; the favourite star is icon only. The info pane's action row wraps (flow row, 8 gaps) so a button drops to a second line instead of being clipped.
- **Favourite** is a **star** (outline when off, filled pink when on), not a heart, in buttons and menus.

## 5. Screens

- **Home**: page header (Refresh), banner area, shelves: On this day (with "30 years ago" badge), Favourites >, Random albums >, Playlists >, Recently played >, Recently added. Offline: Downloaded + Playlists only. Pull to refresh. "See all" opens the Library grid page (Favourites / Random) with a different title.
- **Library**: header (Sort menu, Grid/List, density, Filter) + tabs + grid or list; A-Z strip only when sorted by name/artist; tapping the active Albums label no longer cycles sort (sort is in the header). Random keeps "Shuffle again"; loads 24 at a time.
- **Album / Playlist**: Compact stacked (art, title, artist link, meta, Play / star / Download (Playlist: Play / Shuffle / Download), tracks, more by artist, About). Expanded two panes: left 320 sticky (art, title, artist, meta, actions, About), right tracks + "More by this artist" shelf. Header = back + ⋯ (Playlist also Edit). Edit mode in place (drag handles, rename, delete). Disc headings, per-disc and per-track download controls, track ⋯ (Play next, Add to queue, Add to playlist) unchanged.
- **Maniac Mode hides Play Next everywhere** (album, playlist and song menus, cards' long-press and right-click menus, the Play prompt); Add to Queue and Play stay, and the player refuses Play Next too.
- **Artist**: same two-pane pattern: circular art, name, **Play all** (blue primary) / **Shuffle** (outlined pill; hidden in Maniac Mode), bio + genres + attribution, album grid with year subtitle. Play all / Shuffle queue the albums shown, oldest release first (full release date, else Jan 1 of the production year, unknown last, same date by title), each album in disc then track order, at most **500 tracks** (the player then says "Queued the first 500 of this artist's tracks."); one queue group titled with the artist's name; Shuffle turns shuffle on (Maniac Mode never shuffles and plays in order); Play all leaves shuffle as it was, as for an album. Offline only downloaded albums play (disabled when none). Same Replace queue / Play Next / Add to Queue prompt as albums.
- **Search**: header "Search", search field (pill), filter chips All / Albums / Artists / Playlists, results as Albums grid + Playlists rows. The chips appear only once the query is not blank (with an empty box the page shows just the field and the recent searches). Recent searches + Clear, offline "downloads only" results, empty/hint states keep working.
- **Downloads**: header (Edit), Downloading rows with progress bars, Playlists shelf, Downloaded grid (same grid/density), storage card (side card on Expanded, below on Compact). Edit = multi-select + "Remove N" bar; long-press enters edit. The bar is full width of the content area above the bottom bar: navy 85% with a hairline top, a red pill Remove button 40 high at the page margin (12 above and below).
- **Settings**: Compact grouped list; Expanded split view (categories 240 left, detail right). "Download over mobile data" and "Streaming over mobile data" (and the matching footer sentences) exist only where there is mobile data (Android, iPhone/iPad; not Linux or Mac). Order: Maniac Mode, Server (Sign out), Downloads, Playback (Equaliser lives inside Playback: section on wide, pushed page on compact), Storage, Updates, About.
- **Recently played**: shared grid (+density); export panel is one card (below the grid on Compact, sticky right column on Expanded): size 3x3 / 4x4 / 5x5, Show album and artist toggle, Copy / Save. Below 9 albums no export.
- **Now Playing**: player + queue. Compact: close and ⋯ on top, art (up to 300), title 22 bold, artist link, album + format line, seek, prev/play/next (56/72/56), then a row of **icon buttons** (44 round) for Shuffle, Repeat, Visualiser, Cast, Output (on state = accent tint at 20% + accent icon, others neutral). The **accent** is the colour picked from the current artwork (`ArtPalette`, lifted to a legible brightness, fading over 0.3 s as the track changes), falling back to brand blue until it is worked out or when the art has none. It colours the seek bar fill, the on-state toggles, the artist link under the title (links on other screens stay blue), the visualiser bars, the quality label on hover and "Playing on" while casting, queue below (peeks, scrolls). Expanded: left column player (art ~300-420), right column queue panel with header (Queue, time left, Clear queue), album group headers with 36 thumbnails, current track highlighted pink tint. Drag-reorder, swipe/× remove, Maniac Mode note unchanged. No density scaling hack.
- **Sign in**: one centred card (max 440): logo disc, "Sign in to Jellyfin", server address, "On your network" discovery list + Search again, then the Quick Connect panel (title, explanation, a **Use Quick Connect** button, link to username & password) or the credentials form (Sign in, link "Use Quick Connect instead"). Discovery (including a single server found) and tapping a server only fill in the address; **Quick Connect starts only from the Use Quick Connect tap** (or the "Use Quick Connect instead" link), never on appear, discovery, picking or Return in the address field. It is cancelled and its code dropped when the address is edited or picked again, when switching to the password form and when the screen goes away. The button is disabled with no address.

## 6. Tokens

Colours (dark only):
- navy `#000B25` page background, over the app background image (`background.jpg`, cropped to fill, as `AppBackground` today).
- blue `#039FD6` actions, links, selected nav, primary buttons, and the fallback for the art accent. Progress and toggle-on on Now Playing and the mini player use the art-derived accent instead (blue only when there is none); the artist link on Now Playing too.
- pink `#AA5CC3` "current": current queue track, selected tab underline, now playing.
- surface = white 8% (cards, bars, groups, mini player, sidebar), art placeholder = white 16%, blue tint = blue 20% (selected row/segment/nav item), pink tint = pink 25% (current track), hairline = white 12%.
- text primary white, secondary white 60%, tertiary white 40%. Status green `#30D158`, red `#FF453A`.
- Replace the inline colours (mini player fill `0xE6101A33`, error pink, locally redeclared secondary/tertiary/divider) with these tokens.

Type: DEKIRU Sans. Page title 28 bold; section title 20 semibold; row title 15 medium; body 14 regular; caption 12 regular; nav label 11 medium. Now Playing title 22 bold, artist 17 medium.

Spacing: 4, 8, 12, 16, 24, 32 only. Radii: 6 (thumbs, track highlight), 10 (card art), 16 (panels, groups, banners), full (pills, round buttons).

## 7. Principles / non-goals

- No feature is removed. Maniac Mode, Quick Connect, equaliser, export grid, offline behaviour, Cast, Android widgets, CarPlay/Auto stay.
- Parity: Kotlin first, then Swift; any gap is stated. iOS phone keeps the native `TabView` at the bottom; iPad and Mac use the Medium/Expanded rules. Mac compact keeps the custom bottom bar.
- Keep tests green; pull layout rules into pure functions (window class, columns, density) with unit tests.
