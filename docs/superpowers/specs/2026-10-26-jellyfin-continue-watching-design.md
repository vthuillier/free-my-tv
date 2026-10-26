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

### Reusing `Recs.Pick` as a plain data carrier (refinement found while planning)

`HomeView`'s hero and card rendering (`currentPick()`, `makeHero()`, the click handlers) are
written entirely against the `Recs.Pick` *struct* (plain fields: `kind`, `title`, `backdrop`,
`meta`, `app`, `pkg`, `link`) — none of that code calls into TMDB-specific logic. `HomeApps.app
(pkg, label, uri)` with `uri == null` simply resolves `pkg`'s own launch intent, which is exactly
"open the Jellyfin app" when `pkg = "org.jellyfin.androidtv"`.

So `Jellyfin.java` can build and return plain `Recs.Pick` instances (`kind = "jellyfin"`, `title`
= episode/movie name, `meta` = series name for an episode or empty for a movie, `pkg =
"org.jellyfin.androidtv"`, `app = null`, `link = null`, `backdrop` = a Jellyfin-specific cache
key, *not* a TMDB path) without `Recs.java` or `HomeView`'s hero/click code changing at all
beyond sourcing which manager a tab reads from. This keeps the original approach B's isolation
(all Jellyfin auth/fetch/image logic lives only in `Jellyfin.java`) while touching far less of
`HomeView.java` than first estimated — no new per-item type, no click-handling branches.

The existing "no picks this tab" fallback (an empty `cards` list makes `currentPick()` return
null, and `makeHero()` already falls back to a plain clock face) is reused as-is for "not
configured", "server unreachable" and "nothing in progress" — no new placeholder UI needed.

### `Jellyfin.java` (new, `app/src/io/freemytv/`)

Mirrors `Recs.java`'s shape:

- `static Jellyfin get(Context c)` — singleton accessor.
- `interface Listener { void onJellyfin(); }`, `addListener` / `removeListener`.
- `boolean configured()` — true once `Prefs` holds a token.
- `List<Recs.Pick> now()` — up to 3 current in-progress items, most recently watched first (empty
  list if not configured, unreachable, or nothing in progress — the existing clock fallback
  covers all three).
- `List<Recs.Pick> wanted()` — same as `now()` (kept for symmetry with `Recs.wanted()`, used by
  `updateCards()`'s prefetch loop).
- `void refreshIfStale()` — called from `HomeView` on resume and when the Reprendre tab is
  selected (same trigger points `Recs.refreshIfStale()` already uses). Rules:
  - No-op if not `configured()`.
  - No-op if a fetch already in flight.
  - No-op if the last attempt failed less than 2 minutes ago (mirrors `Recs`'s 10-minute backoff,
    shortened because a local-network blip is more likely to be transient here).
  - Otherwise fetch the list in the background (same single-thread executor pattern as `Recs`).
  - A 401 on the resume-list call clears the stored token (see Data flow) instead of retrying.
- `Backdrop backdrop(Recs.Pick p)` — same contract as `Recs.backdrop`: returns the ready image or
  null while it loads in the background (own in-memory cache, own disk cache directory
  `getCacheDir()/jellyfin/`, own `Backdrop.make(bitmap, forLight)` call — the same primitive
  `Recs` uses, not shared state).
- `void connect(Context c, String server, String username, String password, Connect cb)` —
  does the login call (below) off the main thread, invokes `cb` on the main thread with
  success/failure; never stores the password.
- `void disconnect(Context c)` — calls `/Sessions/Logout` best-effort, then clears the four
  `Prefs` fields regardless of whether the network call succeeds.

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

- `TAB_KIND` becomes `{null, "movie", "tv", "jellyfin"}` — **always 4 tabs**, not conditional on
  being configured (see "Always visible" revision below): revised from the original "4th tab
  only when configured" after finding the tab count is a hardcoded literal `3` in roughly twenty
  places in this file (tab-bar layout, focus/navigation, accessibility). A fixed `TAB_COUNT = 4`
  constant replacing every one of those literals is a small, mechanical, low-risk change; making
  the count dynamic (3 or 4) is not. Not configured → the tab shows, selecting it yields an empty
  `cards` list, which already falls back to the plain clock face (see below) — equivalent to
  "unavailable" without any new conditional-visibility code path.
- `picksOn()` (currently `Recs.hasKey(getContext())`, gates whether the tab strip/hero area show
  at all instead of a bare clock) becomes `Recs.hasKey(c) || Jellyfin.get(c).configured()`, so a
  Jellyfin-only setup (no TMDB key) still gets the tab strip.
- `updateCards()` branches: when `tab == JELLYFIN_TAB` (new constant, `3`), source `cards` from
  `Jellyfin.get(c).now()`/`.wanted()`/`.backdrop(p)` instead of `recs.now(kind)`/`.wanted()`/
  `.backdrop(p)`. Because both return `List<Recs.Pick>`, `currentPick()`, `makeHero()` and the
  hero/card click handlers (`HomeApps.app(p.pkg, Recs.serviceName(p), p.link)`) need no changes:
  a Jellyfin pick's `pkg = "org.jellyfin.androidtv"`, `app = null` (so `serviceName` returns `""`)
  and `link = null` already make that call open the Jellyfin app, nothing more.
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
| Not configured | Tab exists, selecting it shows the plain clock face (empty `cards`, same fallback as a TMDB tab with zero picks). Settings row shows the login form. |
| Configured, server unreachable (timeout/DNS/connection refused) | Tab stays visible; `now()` returns empty (clock face shown); `error()` set; next attempt no sooner than 2 min later. |
| Configured, server reachable, resume list empty | Tab stays visible; clock face shown (nothing to tell apart from "unreachable" visually — both are "nothing to show right now"; `error()` still distinguishes them for a future diagnostic, not surfaced in this version). |
| Token rejected (401) | Treated as session expired: `Prefs` jellyfin fields cleared, tab reverts to its not-configured (clock face) state, Settings row reverts to login form. No retry with the same token. |
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
