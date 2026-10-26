# Jellyfin Continue Watching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the viewer's in-progress Jellyfin movies/episodes as a 4th "Reprendre" tab on the
Free My TV home screen (Android app), with login configured on-TV in Home Settings.

**Architecture:** A new `Jellyfin.java` class (same shape as `Recs.java`: singleton, background
executor, `Listener` interface) owns login, the resume-list fetch and its own image cache, and
produces plain `Recs.Pick` structs so `HomeView`'s existing hero/card/click code needs no new
branch beyond "which manager does this tab's data come from". The home screen's tab bar, whose
count is a hardcoded literal `3` in about twenty places, becomes a fixed `TAB_COUNT = 4` (not
dynamic) to keep that refactor mechanical and low-risk.

**Tech Stack:** Java 8, Android SDK only (no Gradle, no libraries), `HttpURLConnection` +
`org.json` (same as `Recs.java`'s TMDB/Watchmode calls).

**Spec:** `docs/superpowers/specs/2026-10-26-jellyfin-continue-watching-design.md`

## Global Constraints

- Java 8 language level, minSdk 26 (Android 8), targetSdk 33. No Gradle, no third-party libraries.
- The project has no automated test suite. Every task's "test" step is: `./build.sh` ends with
  `Built FreeMyTV.apk`, then install and check behavior on a real device via
  `adb logcat -s FreeMyTV` — this is the project's actual verification method (see CLAUDE.md).
- Every on-screen string goes through `Ui.tr("English")` / `Ui.trf(...)` / `Ui.plural(...)`,
  never a hardcoded literal in the drawing/UI code.
- The Jellyfin password is never written to `Prefs`, never logged, never kept past the
  `connect()` call that uses it.
- Android-only scope: no changes to `web/`.
- `AppLog.w(String)` for warnings/errors (never includes the password).
- Comments in English, short, only where the why isn't obvious (matches existing code style).

---

### Task 1: `Prefs.java` — Jellyfin settings storage

**Files:**
- Modify: `app/src/io/freemytv/Prefs.java`

**Interfaces:**
- Produces: `Prefs.jellyfinServer()`, `Prefs.jellyfinToken()`, `Prefs.jellyfinUserId()`,
  `Prefs.jellyfinUsername()` (all `String`, `""` when unset), `Prefs.setJellyfin(String server,
  String token, String userId, String username)`, `Prefs.clearJellyfin()`.

- [ ] **Step 1: Add the four getters, the combined setter and the clear method**

Find the "// MARK: - City" section (around line 140) and add a new section right after
`setCity`'s closing brace (after line 149 in the current file):

```java
    // MARK: - Jellyfin

    static String jellyfinServer() { return sp().getString("jellyfinServer", ""); }
    static String jellyfinToken() { return sp().getString("jellyfinToken", ""); }
    static String jellyfinUserId() { return sp().getString("jellyfinUserId", ""); }
    static String jellyfinUsername() { return sp().getString("jellyfinUsername", ""); }

    static void setJellyfin(String server, String token, String userId, String username) {
        sp().edit().putString("jellyfinServer", server).putString("jellyfinToken", token)
                .putString("jellyfinUserId", userId).putString("jellyfinUsername", username).apply();
        version++;
    }

    static void clearJellyfin() {
        sp().edit().remove("jellyfinServer").remove("jellyfinToken").remove("jellyfinUserId")
                .remove("jellyfinUsername").apply();
        version++;
    }
```

- [ ] **Step 2: Build**

Run: `cd /Users/vthuillier/free-my-tv && ./build.sh`
Expected: ends with `Built FreeMyTV.apk (...)`. (Nothing calls these methods yet — this only
needs to compile.)

- [ ] **Step 3: Commit**

```bash
git add app/src/io/freemytv/Prefs.java
git commit -m "Jellyfin: add Prefs storage for server, token, user id, username"
```

---

### Task 2: `AndroidManifest.xml` — package visibility for the Jellyfin app

**Files:**
- Modify: `app/AndroidManifest.xml:59-62`

**Interfaces:**
- Produces: `org.jellyfin.androidtv`'s launch intent becomes resolvable via
  `PackageManager.getLaunchIntentForPackage` (Android 11+ package visibility).

- [ ] **Step 1: Add the package to the `<queries>` block**

The block already lists several brand-specific packages (e.g. for HY300 projectors). Add the
Jellyfin Android TV app's package id right after the HY300 group:

```xml
        <package android:name="com.softwinner.awlivetv" />
        <package android:name="org.jellyfin.androidtv" />
```

(i.e. insert the new `<package>` line directly after the existing
`<package android:name="com.softwinner.awlivetv" />` line at `app/AndroidManifest.xml:61`.)

- [ ] **Step 2: Build**

Run: `cd /Users/vthuillier/free-my-tv && ./build.sh`
Expected: ends with `Built FreeMyTV.apk (...)`.

- [ ] **Step 3: Commit**

```bash
git add app/AndroidManifest.xml
git commit -m "Jellyfin: declare package visibility for org.jellyfin.androidtv"
```

---

### Task 3: `Jellyfin.java` — login, resume list, image cache

**Files:**
- Create: `app/src/io/freemytv/Jellyfin.java`

**Interfaces:**
- Consumes: `Prefs.jellyfinServer()/jellyfinToken()/jellyfinUserId()/jellyfinUsername()/
  setJellyfin(...)/clearJellyfin()` (Task 1); `Recs.Pick` (existing struct: `int id; String kind,
  title, backdrop, meta, app, pkg, link; boolean detailed;`); `Backdrop.make(Bitmap, boolean)`
  (existing); `AppLog.w(String)` (existing).
- Produces: `Jellyfin.get(Context)`, `Jellyfin.Listener { void onJellyfin(); }`,
  `addListener`/`removeListener`, `configured()`, `now()`, `wanted()`, `refreshIfStale()`,
  `error()`, `backdrop(Recs.Pick)`, `connect(Context, String server, String username, String
  password, Connect cb)` with `interface Connect { void onResult(boolean ok, String error); }`,
  `disconnect(Context)`.

- [ ] **Step 1: Write the class**

```java
package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen "Reprendre" tab: the viewer's own Jellyfin server, in-progress movies and episodes
 * (Jellyfin's "Resume" list), shown as plain Recs.Pick cards so HomeView's existing hero/card/click
 * code needs no Jellyfin-specific branch — a pick's pkg is the Jellyfin app, so "Watch" just opens it.
 *
 * Login (server, username, password) happens once in Home Settings; only the server, access token,
 * user id and username are kept (Prefs) — the password is used for one HTTP call and never stored.
 */
final class Jellyfin {
    interface Listener { void onJellyfin(); }
    interface Connect { void onResult(boolean ok, String error); }

    private static final long RETRY_AFTER = 2 * 60_000L;
    private static final String CLIENT = "Free My TV", DEVICE = "Android TV", VERSION = "1.0";

    private static Jellyfin instance;

    static synchronized Jellyfin get(Context c) {
        if (instance == null) instance = new Jellyfin(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Backdrop> images = new LinkedHashMap<>();
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private volatile List<Recs.Pick> picks = new ArrayList<>();
    private volatile boolean fetching;
    private volatile long failedAt;
    private volatile String error = "";

    private Jellyfin(Context c) {
        context = c;
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    boolean configured() {
        return !Prefs.jellyfinToken().isEmpty();
    }

    String error() { return error; }

    List<Recs.Pick> now() { return picks; }

    List<Recs.Pick> wanted() { return picks; }

    void refreshIfStale() {
        if (!configured() || fetching) return;
        long now = System.currentTimeMillis();
        long sinceFail = now - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < RETRY_AFTER) return;
        fetching = true;
        io.execute(() -> {
            try {
                List<Recs.Pick> fetched = fetchResume();
                picks = fetched;
                error = "";
                failedAt = 0;
                notifyListeners();
            } catch (ExpiredSession e) {
                AppLog.w("jellyfin: session expired, forgetting it");
                Prefs.clearJellyfin();
                picks = new ArrayList<>();
                error = "";
                failedAt = 0;
                notifyListeners();
            } catch (IOException | JSONException e) {
                error = e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("jellyfin resume list: " + e.getMessage());
            } finally {
                fetching = false;
            }
        });
    }

    /** The access token was rejected (401): the session is gone, not just one failed request. */
    private static final class ExpiredSession extends IOException {}

    private List<Recs.Pick> fetchResume() throws IOException, JSONException {
        String server = Prefs.jellyfinServer();
        String userId = Prefs.jellyfinUserId();
        String token = Prefs.jellyfinToken();
        String url = server + "/Users/" + userId + "/Items/Resume?Limit=3&Fields=Overview"
                + "&EnableImageTypes=Backdrop,Primary&MediaTypes=Video";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Authorization", authHeader(token));
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new ExpiredSession();
            if (code != 200) throw new IOException("Jellyfin returned HTTP " + code);
            JSONObject root = new JSONObject(read(c.getInputStream()));
            JSONArray items = root.optJSONArray("Items");
            List<Recs.Pick> out = new ArrayList<>();
            if (items == null) return out;
            for (int i = 0; i < items.length() && out.size() < 3; i++) {
                Recs.Pick p = parseItem(items.getJSONObject(i));
                if (p != null) out.add(p);
            }
            return out;
        } finally {
            c.disconnect();
        }
    }

    private Recs.Pick parseItem(JSONObject item) {
        String id = item.optString("Id", "");
        if (id.isEmpty()) return null;
        Recs.Pick p = new Recs.Pick();
        p.kind = "jellyfin";
        p.id = id.hashCode();
        p.title = item.optString("Name", "");
        p.meta = "Episode".equals(item.optString("Type")) ? item.optString("SeriesName", "") : "";
        p.app = null;
        p.pkg = "org.jellyfin.androidtv";
        p.link = null;
        p.detailed = true;
        String tag = item.optJSONArray("BackdropImageTags") != null && item.optJSONArray("BackdropImageTags").length() > 0
                ? item.optJSONArray("BackdropImageTags").optString(0, "")
                : item.optJSONObject("ImageTags") != null ? item.optJSONObject("ImageTags").optString("Primary", "") : "";
        boolean hasBackdrop = item.optJSONArray("BackdropImageTags") != null && item.optJSONArray("BackdropImageTags").length() > 0;
        p.backdrop = tag.isEmpty() ? null : id + ":" + (hasBackdrop ? "Backdrop" : "Primary") + ":" + tag;
        return p;
    }

    private static String authHeader(String token) {
        String base = "MediaBrowser Client=\"" + CLIENT + "\", Device=\"" + DEVICE + "\", DeviceId=\""
                + DEVICE + "\", Version=\"" + VERSION + "\"";
        return token.isEmpty() ? base : base + ", Token=\"" + token + "\"";
    }

    // MARK: - Login / logout

    void connect(Context c, String server, String username, String password, Connect cb) {
        String cleanServer = server.trim().replaceAll("/+$", "");
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("Username", username).put("Pw", password);
                HttpURLConnection conn = (HttpURLConnection) new URL(cleanServer + "/Users/AuthenticateByName").openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", authHeader(""));
                conn.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));
                int code = conn.getResponseCode();
                if (code == 401) {
                    conn.disconnect();
                    main.post(() -> cb.onResult(false, Ui.tr("Invalid username or password.")));
                    return;
                }
                if (code != 200) {
                    conn.disconnect();
                    throw new IOException("HTTP " + code);
                }
                JSONObject result = new JSONObject(read(conn.getInputStream()));
                conn.disconnect();
                String token = result.getString("AccessToken");
                String userId = result.getJSONObject("User").getString("Id");
                Prefs.setJellyfin(cleanServer, token, userId, username);
                main.post(() -> { cb.onResult(true, null); refreshIfStale(); });
            } catch (Exception e) {
                AppLog.w("jellyfin login: " + e.getMessage());
                main.post(() -> cb.onResult(false, Ui.tr("Couldn't reach the server.")));
            }
        });
    }

    void disconnect(Context c) {
        String server = Prefs.jellyfinServer(), token = Prefs.jellyfinToken();
        Prefs.clearJellyfin();
        picks = new ArrayList<>();
        error = "";
        failedAt = 0;
        synchronized (images) { images.clear(); }
        notifyListeners();
        if (token.isEmpty()) return;
        io.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(server + "/Sessions/Logout").openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", authHeader(token));
                conn.getResponseCode();
                conn.disconnect();
            } catch (IOException e) {
                AppLog.w("jellyfin logout: " + e.getMessage());   // local state is already cleared either way
            }
        });
    }

    // MARK: - Images (own cache dir, own in-memory Backdrop cache; same Backdrop.make as Recs)

    private File imageFile(Recs.Pick p) {
        return new File(new File(context.getCacheDir(), "jellyfin"), p.backdrop.replace(":", "_"));
    }

    Backdrop backdrop(Recs.Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        io.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    private void loadImage(Recs.Pick p) {
        File f = imageFile(p);
        try {
            if (!f.exists()) download(p, f);
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b == null) throw new IOException("unreadable image");
            Backdrop backdrop = Backdrop.make(b, false);
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                while (images.size() > 4) images.remove(images.keySet().iterator().next());
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("jellyfin image: " + e.getMessage());
        }
    }

    private void download(Recs.Pick p, File f) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        String[] parts = p.backdrop.split(":", 3);   // id:ImageType:tag
        String url = Prefs.jellyfinServer() + "/Items/" + parts[0] + "/Images/" + parts[1]
                + "?tag=" + parts[2] + "&api_key=" + Prefs.jellyfinToken();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
    }

    private static String read(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString("UTF-8");
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onJellyfin(); });
    }
}
```

Notes for the implementer:
- `DeviceId` is set to the literal string `"Android TV"` rather than `Settings.Secure.ANDROID_ID`
  to keep this task self-contained (Jellyfin only uses it to tell sessions apart in its admin
  dashboard; it doesn't need to be globally unique). The unused `android.provider.Settings`
  import can be dropped if this simplification is kept — remove it if the compiler warns.
- `now()`/`wanted()` return the same field (`picks`) — there's no "all three tabs" union to build
  (unlike `Recs.wanted()`), since Jellyfin only ever feeds one tab.
- `Backdrop.make(b, false)` always builds the non-"light" (full-design) variant. `Recs.setLight`
  exists because TMDB pictures are built in both variants depending on device; doing the same for
  Jellyfin is a straightforward follow-up if later needed on a low-RAM TV, but is out of scope
  for this plan (the user's Google TV box and MagicCubic projector are both full-design devices).

- [ ] **Step 2: Build**

Run: `cd /Users/vthuillier/free-my-tv && ./build.sh`
Expected: ends with `Built FreeMyTV.apk (...)`. Fix any compile error (e.g. an unused import
warning is fine; an actual error means a typo against `Recs.Pick`'s real field names — re-check
against `app/src/io/freemytv/Recs.java:45-52`).

- [ ] **Step 3: Commit**

```bash
git add app/src/io/freemytv/Jellyfin.java
git commit -m "Jellyfin: add login, resume-list fetch and image cache"
```

---

### Task 4: `HomeView.java` — fixed 4th tab, wired to Jellyfin

**Files:**
- Modify: `app/src/io/freemytv/HomeView.java`

**Interfaces:**
- Consumes: `Jellyfin.get(Context)`, `.addListener/.removeListener`, `.now()`, `.wanted()`,
  `.backdrop(Recs.Pick)`, `.refreshIfStale()`, `.configured()` (Task 3).
- Produces: a working 4th "Reprendre" tab; `picksOn()` now also true when only Jellyfin is
  configured.

This task replaces every tab-count literal `3` with a new `TAB_COUNT` constant, adds the 4th tab
label and `TAB_KIND` entry, and routes that tab's data through `Jellyfin` instead of `Recs`. Each
edit below is anchored to its current exact text so it can be applied as an exact-match
replacement; none of them change behavior for the first three tabs.

- [ ] **Step 1: Add the `TAB_COUNT` constant, extend `TAB_KIND`, add the `jellyfin` field**

Replace:
```java
    private static final String[] TAB_KIND = {null, "movie", "tv"};
```
with:
```java
    private static final String[] TAB_KIND = {null, "movie", "tv", "jellyfin"};
    /** Tab count (Explore/Movies/Shows/Reprendre): fixed, not dynamic — kept in one place because
     *  so much tab-bar layout and navigation code below is indexed off it. */
    private static final int TAB_COUNT = 4;
```

Replace:
```java
    private final Recs recs;
```
with:
```java
    private final Recs recs;
    private final Jellyfin jellyfin;
```

- [ ] **Step 2: Fix the two fixed-size arrays that assumed 3 tabs**

Replace:
```java
    private final RectF[] topRects = new RectF[3 + HomeApps.MAX_TOP];
```
with:
```java
    private final RectF[] topRects = new RectF[TAB_COUNT + HomeApps.MAX_TOP];
```

(`cardFocus`/`cardRects` stay `new Spring[3]`/`new RectF[3]` — those are "cards visible on one
tab" slots, not a tab count, and every tab (including Jellyfin's) shows at most 3 cards.)

- [ ] **Step 3: Wire the `jellyfin` field alongside `recs` in the constructor and lifecycle**

Replace:
```java
        recs = Recs.get(a);
```
with:
```java
        recs = Recs.get(a);
        jellyfin = Jellyfin.get(a);
```

Replace:
```java
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
```
with:
```java
        data.addListener(this);
        recs.addListener(this);
        jellyfin.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        jellyfin.refreshIfStale();
```

Replace:
```java
        data.removeListener(this);
        recs.removeListener(this);
```
with:
```java
        data.removeListener(this);
        recs.removeListener(this);
        jellyfin.removeListener(this);
```

Replace:
```java
            data.refreshIfStale();
            recs.refreshIfStale();
            checkNotices();
```
with:
```java
            data.refreshIfStale();
            recs.refreshIfStale();
            jellyfin.refreshIfStale();
            checkNotices();
```

- [ ] **Step 4: Implement `Jellyfin.Listener` and `onJellyfin()`**

Replace the class declaration:
```java
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
```
with:
```java
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener, Jellyfin.Listener {
```

Add, right after `onRecs()`:
```java
    @Override public void onRecs() {
        wake();
        updatePicks();
    }

    @Override public void onJellyfin() {
        wake();
        updatePicks();
    }
```
(i.e. insert the new `onJellyfin()` method directly after the existing `onRecs()` method.)

- [ ] **Step 5: Add the tab label**

Replace:
```java
            tabs = new String[] {Ui.tr("Explore"), Ui.tr("Movies"), Ui.tr("Shows")};
```
with:
```java
            tabs = new String[] {Ui.tr("Explore"), Ui.tr("Movies"), Ui.tr("Shows"), Ui.tr("Resume")};
```

- [ ] **Step 6: Source the Jellyfin tab's cards from `Jellyfin` instead of `Recs`**

Replace:
```java
    private void updateCards() {
        List<Recs.Pick> list = recs.now(TAB_KIND[tab]);
        if (!samePicks(list, cards)) {
            cards = list;
            cardIndex = Math.min(cardIndex, Math.max(0, list.size() - 1));
            if (zone == Z_CARDS && list.isEmpty()) {
                zone = Z_SHELF;
                retarget(false);
            }
        }
        for (Recs.Pick p : recs.wanted()) recs.backdrop(p);   // all three tabs preloaded: switching tabs is instant
        // Picks bring their own background; without them (no TMDB key), your photo if there is one.
        boolean own = !picksOn() && Photos.on(getContext());
        Backdrop b = own ? Photos.backdrop(getContext(), ownReady) : cards.isEmpty() ? null : recs.backdrop(cards.get(0));
        if (b != null || (cards.isEmpty() && !own)) showBackdrop(b);
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
    }
```
with:
```java
    private void updateCards() {
        boolean onJellyfin = tab == TAB_COUNT - 1;
        List<Recs.Pick> list = onJellyfin ? jellyfin.now() : recs.now(TAB_KIND[tab]);
        if (!samePicks(list, cards)) {
            cards = list;
            cardIndex = Math.min(cardIndex, Math.max(0, list.size() - 1));
            if (zone == Z_CARDS && list.isEmpty()) {
                zone = Z_SHELF;
                retarget(false);
            }
        }
        for (Recs.Pick p : recs.wanted()) recs.backdrop(p);   // all three TMDB tabs preloaded: switching tabs is instant
        for (Recs.Pick p : jellyfin.wanted()) jellyfin.backdrop(p);
        // Picks bring their own background; without them (no TMDB key and no Jellyfin), your photo if there is one.
        boolean own = !picksOn() && Photos.on(getContext());
        Backdrop b = own ? Photos.backdrop(getContext(), ownReady)
                : cards.isEmpty() ? null : onJellyfin ? jellyfin.backdrop(cards.get(0)) : recs.backdrop(cards.get(0));
        if (b != null || (cards.isEmpty() && !own)) showBackdrop(b);
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
    }
```

- [ ] **Step 7: `picksOn()` also considers a Jellyfin-only setup**

Replace:
```java
    private boolean picksOn() {
        return Recs.hasKey(getContext());
    }
```
with:
```java
    private boolean picksOn() {
        return Recs.hasKey(getContext()) || Jellyfin.get(getContext()).configured();
    }
```

- [ ] **Step 8: Replace every remaining tab-count literal `3` with `TAB_COUNT`**

Each of the following is an exact-match replacement (old → new); apply all of them. These sites
are tab-bar sizing, navigation and accessibility code — none of them are the unrelated "3 cards"
or "three per hour" literals elsewhere in the file, which must NOT be touched.

1. Replace `if (zone == Z_TOP && topIndex >= 3 + lightButtons.size())` with
   `if (zone == Z_TOP && topIndex >= TAB_COUNT + lightButtons.size())`
2. Replace `topIndex = 2 + lightButtons.size();` with `topIndex = TAB_COUNT - 1 + lightButtons.size();`
3. In `setLightButtons`, replace:
   ```java
                if (topIndex < 3 && !picksOn()) zone = Z_SHELF;   // without the picks the tabs can't hold focus
                else if (topIndex < 3) selectTab(topIndex);
   ```
   with:
   ```java
                if (topIndex < TAB_COUNT && !picksOn()) zone = Z_SHELF;   // without the picks the tabs can't hold focus
                else if (topIndex < TAB_COUNT) selectTab(topIndex);
   ```
4. Replace `for (int i = 3; i < 3 + lightButtons.size() && !clock; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));`
   with `for (int i = TAB_COUNT; i < TAB_COUNT + lightButtons.size() && !clock; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));`
5. In the click handler, replace:
   ```java
        } else if (zone == Z_TOP) {
            if (topIndex < 3) {
                selectTab(topIndex);
                return;
            }
            it = lightButtons.get(topIndex - 3);
            r = topRects[topIndex];
   ```
   with:
   ```java
        } else if (zone == Z_TOP) {
            if (topIndex < TAB_COUNT) {
                selectTab(topIndex);
                return;
            }
            it = lightButtons.get(topIndex - TAB_COUNT);
            r = topRects[topIndex];
   ```
6. Replace `for (int i = picksOn() ? 0 : 3; i < 3 + lightButtons.size(); i++) out.add(a11yId(Z_TOP, i));`
   with `for (int i = picksOn() ? 0 : TAB_COUNT; i < TAB_COUNT + lightButtons.size(); i++) out.add(a11yId(Z_TOP, i));`
7. Replace `if (i >= 3) return label(lightButtons, i - 3);` with
   `if (i >= TAB_COUNT) return label(lightButtons, i - TAB_COUNT);`
8. Replace `return Ui.trf("{name}, tab {i} of {n}", "name", tabs()[i], "i", i + 1, "n", 3);` with
   `return Ui.trf("{name}, tab {i} of {n}", "name", tabs()[i], "i", i + 1, "n", TAB_COUNT);`
9. Replace `if (i >= 3 + lightButtons.size()) return false;` with
    `if (i >= TAB_COUNT + lightButtons.size()) return false;`
10. Replace:
    ```java
                if (i >= 3 + lightButtons.size() || (i < 3 && !picksOn())) return;
    ```
    with:
    ```java
                if (i >= TAB_COUNT + lightButtons.size() || (i < TAB_COUNT && !picksOn())) return;
    ```
11. Immediately following (same block), replace `topIndex = i; if (i < 3) selectTab(i);` with
    `topIndex = i; if (i < TAB_COUNT) selectTab(i);`
12. In `layoutLight()`, replace `for (int i = 0; i < 3; i++) {` (the one building `topRects[i]`
    for the tab strip, immediately before `float tw = Math.max(lTabOn.measureText(tabs()[i]), ...)`)
    with `for (int i = 0; i < TAB_COUNT; i++) {`
13. In the same method, replace `topRects[3 + i].set(right - b, cy - b / 2, right, cy + b / 2);`
    with `topRects[TAB_COUNT + i].set(right - b, cy - b / 2, right, cy + b / 2);`
14. In `drawLightTop()`, replace the first `for (int i = 0; i < 3; i++) {` (the one indexing
    `topRects[i]` and drawing `tabs()[i]`) with `for (int i = 0; i < TAB_COUNT; i++) {`
15. Still in `drawLightTop()`, the card-focus loops `for (int i = 0; i < 3; i++) if
    (cardFocus[i].value > 0.02f ...` and `for (int i = 0; i < 3; i++) if (i != top)
    drawLightCard(c, i);` stay as `3` — those iterate the fixed 3 *card slots*, not tabs. Do not
    change them.
16. Replace `for (int i = 0; i < lightButtons.size(); i++) { ... topRects[3 + i] ... }` — find
    ```java
        for (int i = 0; i < lightButtons.size(); i++) {
            HomeApps.Item it = lightButtons.get(i);
            RectF r = topRects[3 + i];
    ```
    and replace with:
    ```java
        for (int i = 0; i < lightButtons.size(); i++) {
            HomeApps.Item it = lightButtons.get(i);
            RectF r = topRects[TAB_COUNT + i];
    ```
17. Replace `float right = (lightButtons.isEmpty() ? CONTENT_R * k : topRects[3].left) - 28 * k, cy = L_BUTTON_CY * k;`
    with `float right = (lightButtons.isEmpty() ? CONTENT_R * k : topRects[TAB_COUNT].left) - 28 * k, cy = L_BUTTON_CY * k;`
18. Replace `topIndex = picksOn() ? tab : 3;` with `topIndex = picksOn() ? tab : TAB_COUNT;`
19. Replace `int next = Math.max(picksOn() ? 0 : 3, Math.min(2 + lightButtons.size(), topIndex + dx));`
    with `int next = Math.max(picksOn() ? 0 : TAB_COUNT, Math.min(TAB_COUNT - 1 + lightButtons.size(), topIndex + dx));`
20. Replace `if (topIndex < 3) selectTab(topIndex);   // like Apple TV tabs: focusing a tab selects it`
    with `if (topIndex < TAB_COUNT) selectTab(topIndex);   // like Apple TV tabs: focusing a tab selects it`
21. Replace:
    ```java
                } else if (topIndex < 3 && !lightButtons.isEmpty()) {
                    topIndex = 3;   // up from the tabs reaches the corner buttons without walking the row
    ```
    with:
    ```java
                } else if (topIndex < TAB_COUNT && !lightButtons.isEmpty()) {
                    topIndex = TAB_COUNT;   // up from the tabs reaches the corner buttons without walking the row
    ```

After applying all 22 edits, re-run this check — it must print nothing (every remaining bare `3`
in these specific contexts has been replaced; any hit is a missed site to fix before moving on):

```bash
grep -n "topIndex.*< 3\|topIndex.*>= 3\|topIndex - 3\|topRects\[3\]\|i < 3 +\|i >= 3 +\|, \"n\", 3)\|topIndex = 3\b" app/src/io/freemytv/HomeView.java
```

- [ ] **Step 9: Build**

Run: `cd /Users/vthuillier/free-my-tv && ./build.sh`
Expected: ends with `Built FreeMyTV.apk (...)`. Fix any compile error before continuing — a
mismatched brace from one of the 22 replacements is the most likely mistake.

- [ ] **Step 10: Manual check — tabs still work with no Jellyfin configured yet**

Install on the Google TV box or the MagicCubic projector:
```bash
adb connect <TV_IP>:5555
adb install -r FreeMyTV.apk
```
On the TV: open the home screen, confirm 4 tabs show (Explore/Movies/Shows/Reprendre), the first
three behave exactly as before, and selecting "Reprendre" shows the plain clock (nothing configured
yet — expected, Task 5 adds the login screen). Confirm arrow-key navigation between tabs and up
to the theme/light buttons (top-right corner icons) still works in both directions.

- [ ] **Step 11: Commit**

```bash
git add app/src/io/freemytv/HomeView.java
git commit -m "Jellyfin: add fixed 4th 'Reprendre' tab wired to Jellyfin.java"
```

---

### Task 5: `SettingsActivity.java` — Jellyfin row, login and logout

**Files:**
- Modify: `app/src/io/freemytv/SettingsActivity.java`

**Interfaces:**
- Consumes: `Jellyfin.get(Context)`, `.configured()`, `.connect(...)`, `.disconnect(...)`
  (Task 3); `Prefs.jellyfinUsername()` (Task 1).

- [ ] **Step 1: Add imports**

Replace:
```java
import android.widget.EditText;
import android.widget.Toast;
```
with:
```java
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
```

- [ ] **Step 2: Add the row label**

Replace:
```java
            if (dollar()) l.add(Ui.tr("Dollar"));
            return l.toArray(new String[0]);
```
with:
```java
            if (dollar()) l.add(Ui.tr("Dollar"));
            l.add(Ui.tr("Jellyfin"));
            return l.toArray(new String[0]);
```

- [ ] **Step 3: Add a `jellyfinRow()` helper (the row's index depends on how many coin rows show)**

Add, right after the `private String[] settingLabels() { ... }` method:
```java
        /** The Jellyfin row is always last, after the (variable-length) coin rows. */
        private int jellyfinRow() {
            return settingLabels().length - 1;
        }
```

- [ ] **Step 4: Add its status text**

Replace:
```java
            for (int i = COIN_ROWS; i < v.length; i++) v[i] = onOff(Prefs.quotes() && Prefs.coin(i - COIN_ROWS));
            return v;
```
with:
```java
            for (int i = COIN_ROWS; i < jellyfinRow(); i++) v[i] = onOff(Prefs.quotes() && Prefs.coin(i - COIN_ROWS));
            v[jellyfinRow()] = Jellyfin.get(getContext()).configured()
                    ? Ui.trf("Connected ({username})", "username", Prefs.jellyfinUsername()) : Ui.tr("Off");
            return v;
```

- [ ] **Step 5: Fix the "locked while Quotes is off" check so it stops before the Jellyfin row**

Replace:
```java
                    whyRows[r] = screen == HOME_SETTINGS && pickScroll + r >= COIN_ROWS && !Prefs.quotes()
                            ? Ui.tr("Turn on the Quotes widget to pick its coins.")
                            : need == null ? null : Capabilities.missing(getContext(), need);
```
with:
```java
                    whyRows[r] = screen == HOME_SETTINGS && pickScroll + r >= COIN_ROWS && pickScroll + r < jellyfinRow() && !Prefs.quotes()
                            ? Ui.tr("Turn on the Quotes widget to pick its coins.")
                            : need == null ? null : Capabilities.missing(getContext(), need);
```

- [ ] **Step 6: Guard the Jellyfin row out of the coin-toggle `adjust()` switch**

Replace:
```java
            int row = pickScroll + focus;
            switch (row) {
                case 0: break;   // OK opens the language picker.
```
with:
```java
            int row = pickScroll + focus;
            if (screen == HOME_SETTINGS && row == jellyfinRow()) return;   // OK opens the login/logout dialog
            switch (row) {
                case 0: break;   // OK opens the language picker.
```

- [ ] **Step 7: Handle OK on the Jellyfin row (open login, or confirm logout)**

Replace:
```java
            } else if (screen == HOME_SETTINGS && pickScroll + focus == PHOTOS_ROW) {
                askRemovePhotos();
                return;
```
with:
```java
            } else if (screen == HOME_SETTINGS && pickScroll + focus == PHOTOS_ROW) {
                askRemovePhotos();
                return;
            } else if (screen == HOME_SETTINGS && pickScroll + focus == jellyfinRow()) {
                askJellyfin();
                return;
```

- [ ] **Step 8: Write `askJellyfin()` and `askJellyfinLogout()`**

Add, right after `askCity()`'s closing brace:
```java
        private void askJellyfin() {
            if (Jellyfin.get(getContext()).configured()) {
                askJellyfinLogout();
                return;
            }
            LinearLayout layout = new LinearLayout(activity);
            layout.setOrientation(LinearLayout.VERTICAL);
            EditText server = new EditText(activity);
            server.setHint(Ui.tr("Server address"));
            server.setSingleLine(true);
            EditText username = new EditText(activity);
            username.setHint(Ui.tr("Username"));
            username.setSingleLine(true);
            EditText password = new EditText(activity);
            password.setHint(Ui.tr("Password"));
            password.setSingleLine(true);
            password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            for (EditText field : new EditText[] {server, username, password}) layout.addView(field);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Jellyfin")
                    .setView(layout)
                    .setPositiveButton(Ui.tr("Log in"), (d, which) -> doJellyfinLogin(
                            server.getText().toString(), username.getText().toString(), password.getText().toString()))
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .create();
            dialog.show();
        }

        private void doJellyfinLogin(String server, String username, String password) {
            if (server.trim().isEmpty() || username.trim().isEmpty()) return;
            Jellyfin.get(getContext()).connect(getContext(), server, username, password, (ok, error) -> {
                if (!ok) {
                    Toast.makeText(getContext(), error, Toast.LENGTH_LONG).show();
                    return;
                }
                invalidate();
            });
        }

        private void askJellyfinLogout() {
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Jellyfin")
                    .setMessage(Ui.trf("Disconnect from {server}?", "server", Prefs.jellyfinServer()))
                    .setPositiveButton(Ui.tr("Disconnect"), (d, which) -> {
                        Jellyfin.get(getContext()).disconnect(getContext());
                        invalidate();
                    })
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .show();
        }
```

- [ ] **Step 9: Build**

Run: `cd /Users/vthuillier/free-my-tv && ./build.sh`
Expected: ends with `Built FreeMyTV.apk (...)`.

- [ ] **Step 10: Manual check — login, tab fills in, logout**

Install, open Settings → Home Settings, scroll to the new "Jellyfin" row at the bottom (shows
"Off"). Press OK: the login dialog opens with three fields. Enter the real Jellyfin server URL,
username and password (typed with the remote), confirm. Expected: dialog closes, the row now
shows "Connected (username)". Go back to the home screen: the "Reprendre" tab now shows real
in-progress items with artwork within a few seconds (watch `adb logcat -s FreeMyTV` for any
`jellyfin:` warning lines if it doesn't). Press OK on a card: the Jellyfin app opens. Back in
Settings, press OK on the Jellyfin row again: a "Disconnect from <server>?" confirmation appears;
confirm it, the row reverts to "Off" and the home screen's Reprendre tab goes back to the plain
clock.

Also test a wrong password: the dialog should show "Invalid username or password." without
saving anything (row still shows "Off" afterwards).

- [ ] **Step 11: Commit**

```bash
git add app/src/io/freemytv/SettingsActivity.java
git commit -m "Jellyfin: add Home Settings row with login/logout dialogs"
```

---

### Task 6: Translations

**Files:**
- Modify: `app/assets/i18n/en.json` (regenerated), `app/assets/i18n/fr.json` (hand-translated,
  per project convention — see CLAUDE.md "The code" section on `tools/translate.py`).

**Interfaces:** none (text only).

- [ ] **Step 1: Regenerate `en.json` and see what's missing everywhere else**

Run: `cd /Users/vthuillier/free-my-tv && python3 tools/translate.py`

This scans every `Ui.tr(...)`/`Ui.trf(...)`/`Ui.plural(...)` call added in Tasks 4 and 5 ("Resume",
"Jellyfin", "Server address", "Username", "Password", "Log in", "Cancel" (already exists),
"Connected ({username})", "Off" (already exists), "Disconnect from {server}?", "Disconnect",
"Invalid username or password.", "Couldn't reach the server.") and adds them to `en.json`, then
reports which language catalogues are missing them.

- [ ] **Step 2: Hand-translate the new keys into French**

Open `app/assets/i18n/fr.json` and add the new English keys with their French values, in
alphabetical key order (matching the file's existing convention), for example:

```json
 "Connected ({username})": "Connecté ({username})",
 "Couldn't reach the server.": "Impossible de joindre le serveur.",
 "Disconnect": "Déconnecter",
 "Disconnect from {server}?": "Se déconnecter de {server} ?",
 "Invalid username or password.": "Identifiant ou mot de passe invalide.",
 "Jellyfin": "Jellyfin",
 "Log in": "Se connecter",
 "Password": "Mot de passe",
 "Resume": "Reprendre",
 "Server address": "Adresse du serveur",
 "Username": "Identifiant",
```

(Exact key list depends on what Step 1's run of `translate.py` actually reports as new — add
every key it lists for `fr.json`, using the English text as the source of truth for meaning.)

- [ ] **Step 3: Verify**

Run: `cd /Users/vthuillier/free-my-tv && python3 tools/translate.py --check`
Expected: no error for `en.json` or `fr.json`. Other catalogues (es, de, pt, etc.) will still
report the new keys as missing — that's expected and acceptable for this personal build (they
fall back to English, same as any other untranslated string); it does not block `tools/check.sh`.

- [ ] **Step 4: Commit**

```bash
git add app/assets/i18n/en.json app/assets/i18n/fr.json
git commit -m "Jellyfin: add English and French strings"
```

---

### Task 7: Full verification and final build

**Files:** none (verification only).

- [ ] **Step 1: Run the project's full pre-push check**

Run: `cd /Users/vthuillier/free-my-tv && ./tools/check.sh`
Expected: passes (Google TV build, `translate.py --check`, LG/Samsung packages, ES5/CSS checks —
none of this plan's changes touch `web/`, so those checks should be unaffected).

- [ ] **Step 2: Full manual run-through on both devices**

Per the spec's test plan — on the Google TV box and the MagicCubic projector (`192.168.1.177` and
`192.168.1.8`):
1. Install the freshly built `FreeMyTV.apk`.
2. Settings → Home Settings → Jellyfin → log in with real credentials → row shows "Connected
   (username)".
3. Home screen shows "Reprendre" tab with real in-progress items and artwork.
4. Tap a card → Jellyfin app opens.
5. Turn off Wi-Fi on the TV (or stop the Jellyfin server) → tab shows the plain clock without
   crashing; the rest of the home screen keeps working.
6. Restore connectivity → tab recovers within about 2 minutes (or immediately on the next
   Settings → Jellyfin visit, which calls `refreshIfStale()` again on resume).
7. Settings → Jellyfin → Disconnect → tab reverts to the plain clock, row reverts to "Off".

- [ ] **Step 3: Final commit (only if Step 2 required fixes)**

If manual testing on real devices surfaces a bug, fix it, re-run the relevant build/check step,
and commit the fix with a message describing what was observed and corrected.
