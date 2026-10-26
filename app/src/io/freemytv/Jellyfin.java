package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

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
