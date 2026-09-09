# 4K Plus TV Player — Session Handoff

## Current state

- Native Android project using Kotlin and Jetpack Compose.
- Repository: `tareq58798-a11y/4k-plus-tv-player` (private GitHub repository).
- GitHub Actions successfully builds a debug APK using Java 17.
- Latest local commit includes the supplied 4K Plus TV logo, safer screen-edge spacing, and touch feedback.
- The next functional milestone adds real M3U/provider connection testing, playlist parsing, encrypted source storage, loading/error handling, and real content counts on Home.

## Product direction

- Android phones first, with portrait and landscape support.
- Built for existing 4K Plus TV customers; comfort and simplicity are the priority.
- Users can connect through generated Device ID/Device Key or manually add an M3U URL/provider login.
- No local M3U file option, activation code entry, payment, subscription, or trial interface.
- Main areas: Live TV, Movies, Series, Continue Watching, Favorites, History, Playlists, Settings, parental controls, updates, and external-player fallback.

## Latest implemented feedback

- Added safe top, bottom, and side spacing, including wider landscape margins.
- Added the supplied 4K Plus TV logo to the UI and application icon.
- Removed Contact Support.
- Removed Check Activation.
- Refresh now represents the activation/playlist check.
- Added animated refresh, card press scaling, ripple feedback, haptics, clipboard actions, and snackbar confirmations.
- Added a premium visual pass to the activation and home screens: atmospheric brand glows, layered gradient surfaces, stronger typography, accent-icon containers, richer status treatment, rounded elevated cards, a branded Continue Watching banner, entry animation, and overflow-safe quick actions.

## Design direction

The current interface is clean but feels too plain. Keep it minimal and easy to navigate, while bringing it to life creatively through:

- stronger visual hierarchy and branded blue/cyan/orange accents;
- subtle gradients, layered cards, depth, and tasteful background treatments;
- polished focus, pressed, loading, success, and error states;
- purposeful micro-animations and transitions;
- richer media artwork where content is available;
- consistent spacing and large touch targets without crowding screen edges;
- an interface that feels premium and lively rather than sterile or overdecorated.

## Next step

Build and test version 0.2.0 through GitHub Actions. Verify a real M3U URL and provider login, confirm loading/errors and Home counts, then add content browsing screens and Media3 playback after the ingestion path is stable. Visual refinements remain intentionally deferred.
