# Jellyfin "Continue Watching" on the home screen

Date: 2026-10-26
Scope: Android app only (Google TV / Android TV devices, including the MagicCubic/Hotack H713
projector). No LG/Samsung web app parity for now.

## Goal

Show the viewer's in-progress movies and episodes from their own Jellyfin server as a 4th tab
in the home screen's existing picks carousel (hero card + row of cards), next to the current
Trending / Movies / TV tabs. Tapping a card opens the Jellyfin app so the viewer can resume
playback there.

## Why a separate `Jellyfin` class, not a generalized `Recs.Pick`

Two approaches were considered:

- **Generalize `Recs.Pick`** with a `source` field (TMDB vs Jellyfin), reusing the whole
  existing picks pipeline (image cache, hero, click handling) as-is.
- **New `Jellyfin.java` class**, same conventions as `Recs` (`get()`, `refreshIfStale()`,
  `now()`, a `Listener` interface), with its own lightweight item type. `HomeView` routes the
  new tab to this class instead of `recs`, reusing only the generic drawing primitives
  (`drawImage`, `roundImage`, `drawPlatter`), not the TMDB-specific fields (genre, franchise,
  streaming service).

The second was chosen: `Recs.java` and the hero/cards code in `HomeView.java` are already dense
and tuned for 60fps; touching them to thread through a second, unrelated data source risks
regressing the existing TMDB picks. A new class with a narrow interface keeps risk isolated and
matches the existing separation of concerns (`Recs` = TMDB specifically).

## Components

### `Jellyfin.java` (new, `app/src/io/freemytv/`)

Mirrors `Recs.java`'s shape:

- `static Jellyfin get(Context c)` — singleton accessor.
- `interface Listener { void onJellyfin(); }`, `addListener` / `removeListener`.
- `boolean configured()` — true once `Prefs` holds a token.
- `List<Item> now()` — current in-progress items (empty list if not configured, unreachable, or
  nothing in progress).
- `void refreshIfStale()` — called from `HomeView` on resume and when the Reprendre tab is
  selected (same trigger points `Recs.refreshIfStale()` already uses). Rules:
  - No-op if not `configured()`.
  - No-op if a fetch already in flight.
  - No-op if the last attempt failed less than 2 minutes ago (mirrors `Recs`'s 10-minute backoff,
    shortened because a local-network blip is more likely to be transient here).
  - Otherwise fetch the list in the background (existing `io` executor pattern from `Recs`).
- `String error()` — last failure, for the "Jellyfin indisponible" placeholder; null when fine.
- `void connect(Context c, String server, String username, String password, Callback cb)` —
  does the login call (below) off the main thread, invokes `cb` with success/failure; never
  stores the password.
- `void disconnect(Context c)` — calls `/Sessions/Logout` best-effort, then clears the four
  `Prefs` fields regardless of whether the network call succeeds.
- `static class Item { String id; String title; String subtitle; String imagePath; }` — title is
  the episode/movie name, subtitle is the series name (empty for movies), `imagePath` is the
  cached file once downloaded (same cache-dir-per-feature pattern as `Recs.imageFile`, under
  `getCacheDir()/jellyfin/`).
- Click handling: `Jellyfin.launch(Context c)` resolves and starts `org.jellyfin.androidtv`'s
  launch intent (`PackageManager.getLaunchIntentForPackage`); if the package isn't installed,
  `HomeView` shows the existing toast pattern used elsewhere for a missing app, same as a picks
  card whose app was uninstalled.

### `Prefs.java` additions

Four new fields, same pattern as the existing simple get/set pairs:

- `jellyfinServer` (String, e.g. `http://192.168.1.50:8096`)
- `jellyfinToken` (String, the Jellyfin `AccessToken`)
- `jellyfinUserId` (String, the Jellyfin `User.Id`)
- `jellyfinUsername` (String, display-only in Settings — never the password)

`configured()` in `Jellyfin.java` is simply `!jellyfinToken.isEmpty()`.

### `SettingsActivity.java` addition

A new row, "Jellyfin", in Home Settings (same section as Remote Buttons / Sleep Timer). Behavior:

- Not configured: row opens a small form — server address, username, password (masked, standard
  remote-keyboard entry) — with a "Se connecter" action.
  - On submit: call `Jellyfin.connect(...)`. Success → save fields, show the row as "Connecté
    (nom d'utilisateur)". Failure → inline error message (bad credentials vs server unreachable,
    using the HTTP status/exception to pick the wording), nothing is saved, form stays open to
    retry.
- Configured: row shows "Connecté (nom d'utilisateur)" with a "Déconnecter" action, which calls
  `Jellyfin.disconnect(...)` and reverts the row to its unconfigured state.

No `Capabilities.missing` entry is needed: this isn't a hardware/OS capability gate like the
Now Playing listener, it's just configured-or-not, handled entirely within the row and the tab's
visibility (see below).

### `HomeView.java` changes

- `TAB_KIND` becomes `{null, "movie", "tv", "jellyfin"}`, **but the 4th tab is only added to the
  tab strip when `Jellyfin.get(c).configured()` is true** — unconfigured means 3 tabs exactly as
  today, no dimmed/unavailable 4th tab.
- Tab selection and `updateCards()`/`updatePicks()` branch: when the selected tab is `"jellyfin"`,
  source cards from `Jellyfin.get(c).now()` instead of `recs.now(kind)`. The hero card and the
  row of cards reuse their existing drawing code; the per-item fields consumed are narrowed to
  title/subtitle/image (no genre badge, no streaming-service logo, no franchise grouping — those
  concepts don't apply to Jellyfin items).
- Click on a Jellyfin card/hero calls `Jellyfin.launch(context)` instead of
  `HomeApps.app(p.pkg, ...)`.
- Empty/placeholder state: if the tab is selected and `Jellyfin.get(c).now()` is empty because of
  `error() != null`, the hero area shows a plain "Jellyfin indisponible" message (reusing
  whatever existing empty/error-card drawing exists for picks with no results) instead of an
  empty carousel.
- `HomeView` adds itself as a `Jellyfin.Listener` the same way it's a `Recs.Listener`, to redraw
  when a background fetch completes.

## Data flow

1. **Login** (`SettingsActivity` → `Jellyfin.connect`):
   `POST {server}/Users/AuthenticateByName` body `{"Username": "...", "Pw": "..."}`, header
   `Authorization: MediaBrowser Client="Free My TV", Device="Android TV", DeviceId="<ANDROID_ID>", Version="1.0"`.
   Response gives `AccessToken` and `User.Id`. Store both + server + username in `Prefs`;
   the password is never referenced again after this call returns.

2. **Fetch resume list** (`Jellyfin.refreshIfStale` → background):
   `GET {server}/Users/{userId}/Items/Resume?Limit=12&Fields=Overview&EnableImageTypes=Backdrop,Primary&MediaTypes=Video`,
   header `Authorization: MediaBrowser Token="{token}", Client="Free My TV", Device="Android TV", DeviceId="<ANDROID_ID>", Version="1.0"`.
   A 401 here means the token was revoked/expired: treat as "disconnected" — clear the four
   `Prefs` fields (same effect as pressing Déconnecter) and let the Settings row + tab visibility
   follow from that on next read, no retry loop with the same dead token.

3. **Fetch images**:
   `{server}/Items/{id}/Images/Backdrop?tag={tag}&api_key={token}` (or `Primary` when no backdrop
   tag), downloaded and cached to `getCacheDir()/jellyfin/<id>-<tag>`, same pattern as
   `Recs.downloadImage`/`imageFile`.

4. **Refresh triggers**: `HomeView`'s existing resume-from-background hook and tab-selection
   hook both call `Jellyfin.get(c).refreshIfStale()`, exactly where they already call
   `recs.refreshIfStale()`.

5. **Logout** (`SettingsActivity` → `Jellyfin.disconnect`):
   `POST {server}/Sessions/Logout` with the current token, best-effort (ignore failure — the
   point is clearing local state), then `Prefs` fields are cleared unconditionally.

## Error handling

| Situation | Behavior |
|---|---|
| Not configured | 4th tab doesn't exist. Settings row shows the login form. |
| Configured, server unreachable (timeout/DNS/connection refused) | Tab stays visible; hero area shows "Jellyfin indisponible"; `error()` set; next attempt no sooner than 2 min later. |
| Configured, server reachable, resume list empty | Tab stays visible; hero area shows a plain "Rien en cours" message (same placeholder mechanism as the "Jellyfin indisponible" case, different text). |
| Token rejected (401) | Treated as session expired: `Prefs` jellyfin fields cleared, tab disappears, Settings row reverts to login form. No retry with the same token. |
| Login submitted with wrong credentials | Inline error in the Settings form; nothing saved. |
| Login submitted, server URL unreachable | Inline error distinguishing "serveur injoignable" from "identifiants invalides" (network exception vs HTTP 401 on the auth call). |
| Jellyfin app not installed, user taps a card | Same toast/behavior already used elsewhere in the project for a picks card whose app isn't on the TV. |

## Security

- The password lives only as a local variable for the duration of the `AuthenticateByName` call;
  it is never written to `Prefs`, never logged (`AppLog` call sites touching this flow must not
  include it).
- The access token is stored in `Prefs` (SharedPreferences) in the clear, the same trust level
  already accepted for the TMDB key in `config.properties` — adding encryption here would be
  disproportionate: anyone with physical/ADB access to the TV already has access to everything
  the app can do.
- No certificate validation changes: plain HTTP to a local-network Jellyfin server (the common
  case) is accepted as-is, matching how the user's own server is actually deployed.

## Testing (manual — the project has no automated test suite)

1. `./build.sh` and `tools/check.sh` both succeed.
2. Install on the Google TV box and the MagicCubic projector.
3. Settings → Jellyfin → log in with real credentials → row shows "Connecté (username)".
4. Home screen shows a 4th "Reprendre" tab with real in-progress items and their artwork.
5. Tap a card → Jellyfin app opens.
6. Stop the Jellyfin server (or disconnect the TV from the network) → tab shows "Jellyfin
   indisponible" without crashing; home screen otherwise keeps working.
7. Bring the server back → tab recovers within the 2-minute retry window.
8. Settings → Déconnecter → tab disappears, row reverts to the login form.

## Out of scope (explicitly, to avoid over-building)

- LG/Samsung (`web/`) parity — can be a follow-up if the user gets a Jellyfin-watching LG/Samsung
  TV later.
- Deep-linking directly into the specific episode inside the Jellyfin app (unreliable across
  Jellyfin Android TV app versions); tapping just opens the app.
- Jellyfin Quick Connect / API-key-based auth — username+password covers the single-household
  use case without exposing a server-wide admin key.
- Encrypted credential storage beyond what the project already does for the TMDB key.
