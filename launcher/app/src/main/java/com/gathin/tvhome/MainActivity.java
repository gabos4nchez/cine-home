package com.gathin.tvhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Home, built from the Cine board: the selected channel fills the top, its tile sits in the row below.
 * Focus is one number kept here rather than Android's focus search, so every D-pad press has one result:
 * Left/Right move one tile, sliding to the next page of six past the edge of a page and stopping at the first and
 * last app; Up goes to the Wi-Fi button and Down from there comes back to the same tile.
 * A tap of OK opens the app when OK is let go; holding it opens a menu over the tile instead, whose Mover lifts the
 * tile so Left/Right carry it along the row, and the order is kept until the app data is cleared.
 * Each channel shows the person's own pictures (pushed to hero/ or linked from the theme), or else up to six from its
 * Play Store page; two or more take turns every few seconds.
 */
public final class MainActivity extends Activity {
    private static final int COLUMNS = 6;
    private static final String SETTINGS = "com.android.tv.settings";
    private static final String PLAY_STORE = "com.android.vending";
    // At the end of the row, in this order, unless the person has carried them elsewhere with Mover.
    private static final String[] LAST = {PLAY_STORE, SETTINGS};
    private static final String OPEN = "Abrir", MOVE = "Mover", UPDATE = "Actualizar", INFO = "Información",
            UNINSTALL = "Desinstalar";
    private static final String[] DEFAULT_DOCK = {"com.goodtoolapps.zeus", "com.alphainventor.filemanager", "com.esaba.downloader", "com.android.vending", SETTINGS};
    private static final Map<String, String> DESCRIPTIONS = new HashMap<>();
    static {
        DESCRIPTIONS.put("com.goodtoolapps.zeus", "Navega por internet desde el TV con tus sitios favoritos.");
        DESCRIPTIONS.put("com.alphainventor.filemanager", "Explora, copia y organiza los archivos guardados en el TV.");
        DESCRIPTIONS.put("com.esaba.downloader", "Descarga archivos desde una dirección web e instala apps.");
        DESCRIPTIONS.put("com.android.vending", "Busca e instala apps y juegos para tu TV.");
        DESCRIPTIONS.put(SETTINGS, "Red, pantalla, sonido, control remoto y el resto de opciones del TV.");
    }

    private static final class Channel {
        String pkg, name, description;
        Drawable icon, banner;
        List<File> pictures = new ArrayList<>();
        int shown;                                          // Which of the pictures is on screen; they take turns.
        int color, accent;
    }

    private static final long ROTATE_MS = 8000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Channel> channels = new ArrayList<>();
    // Two full-screen pictures at most: the one on screen and the one just left.
    private final LruCache<String, Bitmap> pictures = new LruCache<>(2);
    private final ExecutorService pictureWorker = Executors.newSingleThreadExecutor();
    // Play Store pictures, one app at a time and only while Home is on screen; see PlayPictures.
    private final ExecutorService fetcher = Executors.newSingleThreadExecutor();
    private final AtomicBoolean fetching = new AtomicBoolean();
    private volatile boolean visible;
    private boolean autoPictures = true;
    private Map<String, List<String>> themeLinks = new HashMap<>();
    // Brightness correction per picture file, measured once when it is first decoded.
    private final Map<String, Float> gains = new HashMap<>();
    private final Runnable rotate = new Runnable() {
        @Override public void run() {
            if (!channels.isEmpty() && menuIndex < 0 && !moving) {
                Channel c = channels.get(selected);
                if (c.pictures.size() > 1) {
                    c.shown = (c.shown + 1) % c.pictures.size();
                    showArt(c, true);
                }
            }
            main.postDelayed(this, ROTATE_MS);
        }
    };
    private ArtView art;
    private TextView greeting, clock, selectedLabel, title, description;
    private View wifi;
    private boolean onWifi;
    private RecyclerView tiles;
    private LinearLayoutManager row;
    private View arrowLeft, arrowRight;
    private int painted = -1;
    private SharedPreferences prefs;
    private int selected;
    private String loadedSignature = "";
    private LinearLayout menu;
    private final List<String> menuActions = new ArrayList<>();
    private int menuIndex = -1;                             // -1 while the menu is closed.
    private int moveStart;
    private boolean moving, okPending, longPressed;
    private final Runnable longPress = () -> {
        longPressed = true;
        showMenu();
    };
    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setFormat(PixelFormat.RGBA_8888);     // The gradients band badly in the default 16-bit surface.
        setContentView(R.layout.activity_main);
        art = findViewById(R.id.art);
        selectedLabel = findViewById(R.id.selected_label);
        greeting = findViewById(R.id.greeting);
        clock = findViewById(R.id.clock);
        title = findViewById(R.id.title);
        description = findViewById(R.id.description);
        tiles = findViewById(R.id.tiles);
        wifi = findViewById(R.id.wifi);
        prefs = getPreferences(MODE_PRIVATE);
        arrowLeft = findViewById(R.id.arrow_left);
        arrowRight = findViewById(R.id.arrow_right);
        menu = findViewById(R.id.menu);
        row = new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false);
        tiles.setLayoutManager(row);
        tiles.setItemAnimator(null);
        tiles.setAdapter(new Tiles());
        tiles.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        registerReceiver(tick, new IntentFilter(Intent.ACTION_TIME_TICK), null, null);
        updateClock();
        loadChannels();
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        unregisterReceiver(tick);
        main.removeCallbacks(rotate);
        main.removeCallbacks(longPress);
        okPending = false;
        closeMenu();
        if (moving) finishMove();
    }

    private void updateClock() {
        Date now = new Date();
        Locale es = Locale.getDefault();
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String time = DateFormat.getTimeFormat(this).format(now);
        String day = capitalize(DateFormat.format(DateFormat.getBestDateTimePattern(es, "EEE d MMM"), now).toString());
        clock.setText(time + "  ·  " + day.replace(",", "").replace(".", ""));
        greeting.setText(hour < 5 || hour >= 19 ? "BUENAS NOCHES" : hour < 12 ? "BUENOS DÍAS" : "BUENAS TARDES");
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.getDefault()) + s.substring(1);
    }

    /** Reads the list on a worker and swaps it in only when something changed, so coming back from an app stays instant. */
    private void loadChannels() {
        Executors.newSingleThreadExecutor().execute(() -> {
            PackageManager pm = getPackageManager();
            JSONObject theme = readTheme();
            Map<String, String> labels = pairs(theme, "app_labels");
            Map<String, String> notes = pairs(theme, "app_descriptions");
            Map<String, String> setColors = pairs(theme, "app_colors");
            List<String> hidden = strings(theme, "hidden_apps");
            LinkedHashMap<String, ResolveInfo> installed = new LinkedHashMap<>();
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
                installed.put(info.activityInfo.packageName, info);
            }
            installed.remove(getPackageName());
            List<String> order = new ArrayList<>();
            // The order set with Mover comes first, then the theme's dock for what it hasn't placed.
            List<String> placed = new ArrayList<>(savedOrder());
            List<String> dock = strings(theme, "dock_apps");
            placed.addAll(dock.isEmpty() ? List.of(DEFAULT_DOCK) : dock);
            for (String pkg : placed) {
                if (installed.containsKey(pkg) && !hidden.contains(pkg) && !order.contains(pkg)) order.add(pkg);
            }
            // An app installed later lands on the rows under the dock, so nothing becomes unreachable.
            for (String pkg : installed.keySet()) if (!order.contains(pkg) && !hidden.contains(pkg)) order.add(pkg);
            keepLast(order, movedByUser());
            boolean auto = theme.optBoolean("auto_pictures", true);
            Map<String, List<String>> links = new HashMap<>();
            for (Map.Entry<String, String> entry : pairs(theme, "app_pictures").entrySet()) {
                List<String> urls = new ArrayList<>();
                for (String url : entry.getValue().trim().split("[\\s|]+")) if (!url.isEmpty()) urls.add(url);
                if (!urls.isEmpty()) links.put(entry.getKey(), urls);
            }
            forgetUnused(installed.keySet(), links.keySet());
            StringBuilder stamps = new StringBuilder();
            for (String pkg : order) {
                for (File picture : picturesOf(pkg, auto, links)) {
                    stamps.append(picture.getPath()).append(picture.lastModified());
                }
            }
            String signature = order + "|" + labels + "|" + notes + "|" + setColors + "|" + auto + "|" + links + "|" + stamps;
            if (signature.equals(loadedSignature)) {
                if (auto) main.post(() -> fetchMissing(order, links));
                return;
            }
            List<Channel> fresh = new ArrayList<>();
            for (String pkg : order) {
                ResolveInfo info = installed.get(pkg);
                Channel c = new Channel();
                c.pkg = pkg;
                String label = labels.get(pkg);
                c.name = label != null ? label : info.loadLabel(pm).toString();
                String note = notes.get(pkg);
                c.description = note != null ? note : DESCRIPTIONS.getOrDefault(pkg, "");
                c.icon = info.loadIcon(pm);
                c.banner = bannerOf(pm, info);
                c.pictures = picturesOf(pkg, auto, links);
                c.color = colorOf(setColors.get(pkg), pm, info);
                c.accent = accentOf(c.color);
                fresh.add(c);
            }
            main.post(() -> {
                if (moving) return;                         // Mover owns the row until it ends; the next resume reloads.
                closeMenu();
                loadedSignature = signature;
                autoPictures = auto;
                themeLinks = links;
                channels.clear();
                channels.addAll(fresh);
                pictures.evictAll();
                selected = Math.max(0, indexOf(prefs.getString("selected", "")));
                tiles.getAdapter().notifyDataSetChanged();
                painted = selected;
                render(false);
                if (auto) fetchMissing(order, links);
            });
        });
    }

    /**
     * The person's own pictures win: pushed to hero/, and the ones the theme links to. Only an app with none of those,
     * and none still to fetch, gets the Play Store ones; with auto_pictures off, nothing fetched is shown.
     */
    private List<File> picturesOf(String pkg, boolean auto, Map<String, List<String>> links) {
        List<File> own = pushedPictures(pkg);
        if (auto && links.containsKey(pkg)) own.addAll(PlayPictures.stored(fetchedDir("links", pkg)));
        if (!own.isEmpty() || links.containsKey(pkg)) return own;
        return auto ? PlayPictures.stored(fetchedDir("auto", pkg)) : new ArrayList<>();
    }

    /** Pushed pictures: hero/<package>.jpg (or .png, .webp), then every picture in hero/<package>/, by name. */
    private List<File> pushedPictures(String pkg) {
        List<File> out = new ArrayList<>();
        File hero = new File(getExternalFilesDir(null), "hero");
        for (String extension : new String[] {".jpg", ".png", ".webp"}) {
            File file = new File(hero, pkg + extension);
            if (file.isFile()) {
                out.add(file);
                break;
            }
        }
        File[] folder = new File(hero, pkg).listFiles((dir, name) -> name.matches("(?i).+\\.(jpe?g|png|webp)"));
        if (folder != null) {
            Arrays.sort(folder);
            out.addAll(Arrays.asList(folder));
        }
        return out;
    }

    private File fetchedDir(String kind, String pkg) {
        return new File(new File(getExternalFilesDir(null), kind), pkg);
    }

    /** Pictures of apps that are gone, or of links the theme no longer lists, take no room. */
    private void forgetUnused(Set<String> installed, Set<String> linked) {
        for (String kind : new String[] {"auto", "links"}) {
            File[] dirs = new File(getExternalFilesDir(null), kind).listFiles();
            if (dirs == null) continue;
            for (File dir : dirs) {
                boolean used = kind.equals("auto") ? installed.contains(dir.getName()) : linked.contains(dir.getName());
                if (used) continue;
                File[] files = dir.listFiles();
                if (files != null) for (File file : files) file.delete();
                dir.delete();
            }
        }
    }

    /**
     * Fetches the pictures a theme links to, and Play Store pictures for the apps that have none of their own, when
     * they are missing or a week old, one app at a time. It stops when Home leaves the screen or the network fails,
     * and carries on at the next visit.
     */
    private void fetchMissing(List<String> order, Map<String, List<String>> links) {
        if (!fetching.compareAndSet(false, true)) return;
        List<String> pkgs = new ArrayList<>(order);
        fetcher.execute(() -> {
            try {
                for (String pkg : pkgs) {
                    if (!visible) break;
                    List<String> urls = links.get(pkg);
                    if (urls != null) {
                        File dir = fetchedDir("links", pkg);
                        if (PlayPictures.linksFresh(dir, urls)) continue;
                        if (!PlayPictures.fetchLinks(urls, dir)) break;
                    } else {
                        File dir = fetchedDir("auto", pkg);
                        if (!pushedPictures(pkg).isEmpty() || PlayPictures.fresh(dir)) continue;
                        if (!PlayPictures.fetch(pkg, dir)) break;
                    }
                    main.post(() -> picturesArrived(pkg));
                }
            } finally {
                fetching.set(false);
            }
        });
    }

    private void picturesArrived(String pkg) {
        int i = indexOf(pkg);
        if (i < 0 || !autoPictures) return;
        Channel c = channels.get(i);
        c.pictures = picturesOf(pkg, true, themeLinks);
        c.shown = 0;
        if (i == selected) showArt(c, true);
    }

    private List<String> savedOrder() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString("order", "[]"));
            for (int i = 0; i < array.length(); i++) out.add(array.optString(i));
        } catch (Exception unreadable) {
            // Start over from the theme's dock.
        }
        return out;
    }

    /** The apps the person has carried to another place with Mover. */
    private Set<String> movedByUser() {
        return new HashSet<>(prefs.getStringSet("moved", Collections.emptySet()));
    }

    private static void keepLast(List<String> order, Set<String> moved) {
        for (String pkg : LAST) if (!moved.contains(pkg) && order.remove(pkg)) order.add(pkg);
    }

    private int indexOf(String pkg) {
        for (int i = 0; i < channels.size(); i++) if (channels.get(i).pkg.equals(pkg)) return i;
        return -1;
    }

    private JSONObject readTheme() {
        try {
            File file = new File(getExternalFilesDir(null), "config.json");
            return new JSONObject(new String(Files.readAllBytes(file.toPath()), "UTF-8"));
        } catch (Exception noTheme) {
            return new JSONObject();
        }
    }

    private static List<String> strings(JSONObject theme, String key) {
        List<String> out = new ArrayList<>();
        JSONArray array = theme.optJSONArray(key);
        for (int i = 0; array != null && i < array.length(); i++) out.add(array.optString(i));
        return out;
    }

    private static Map<String, String> pairs(JSONObject theme, String key) {
        Map<String, String> out = new HashMap<>();
        for (String entry : strings(theme, key)) {
            int eq = entry.indexOf('=');
            if (eq > 0) out.put(entry.substring(0, eq), entry.substring(eq + 1));
        }
        return out;
    }

    /** The theme's app_colors wins: a banner can carry another brand's colours than the app's own. */
    private static int colorOf(String set, PackageManager pm, ResolveInfo info) {
        if (set != null) {
            try {
                return Color.parseColor(set.trim());
            } catch (IllegalArgumentException notAColor) {
                // Fall through to the banner.
            }
        }
        return dominantColor(bannerOf(pm, info));
    }

    private static Drawable bannerOf(PackageManager pm, ResolveInfo info) {
        Drawable banner = info.activityInfo.loadBanner(pm);
        if (banner == null) banner = info.activityInfo.applicationInfo.loadBanner(pm);
        return banner != null ? banner : info.loadIcon(pm);
    }

    /** The most colourful pixels of a tiny copy of the banner, averaged; white and black don't count. */
    private static int dominantColor(Drawable banner) {
        try {
            Bitmap small = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
            banner.setBounds(0, 0, 32, 18);
            banner.draw(new Canvas(small));
            float[] hsv = new float[3], weight = new float[12];
            for (int y = 0; y < 18; y++) {
                for (int x = 0; x < 32; x++) {
                    int p = small.getPixel(x, y);
                    if (Color.alpha(p) < 128) continue;
                    Color.colorToHSV(p, hsv);
                    if (hsv[1] < .35f || hsv[2] < .25f) continue;
                    weight[(int) (hsv[0] / 30) % 12] += hsv[1] * hsv[2];
                }
            }
            small.recycle();
            int best = -1;
            float bestWeight = 8f;
            for (int i = 0; i < 12; i++) {
                if (weight[i] > bestWeight) {
                    best = i;
                    bestWeight = weight[i];
                }
            }
            return best < 0 ? 0 : Color.HSVToColor(new float[] {best * 30 + 15, .8f, .9f});
        } catch (RuntimeException badDrawable) {
            return 0;
        }
    }

    /** Bright enough to read as text on the dark ground and to carry dark text on the button, at 4.5:1 or better. */
    private int accentOf(int dominant) {
        if (dominant == 0) return getColor(R.color.neutral);     // Black, white or grey banners: no colour to borrow.
        float[] hsv = new float[3];
        Color.colorToHSV(dominant, hsv);
        boolean darkHue = hsv[0] >= 210 && hsv[0] <= 290;
        return Color.HSVToColor(new float[] {hsv[0], darkHue ? .50f : .68f, 1f});
    }

    private GradientDrawable card(int fill, int line, float lineDp, float radiusDp) {
        float dp = getResources().getDisplayMetrics().density;
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(radiusDp * dp);
        shape.setStroke(Math.round(lineDp * dp), line);
        return shape;
    }

    /** White into the channel's accent across the name, as far as the name reaches. */
    private void paintTitle(int accent) {
        title.post(() -> {
            float width = title.getLayout() != null ? title.getLayout().getLineWidth(0) : title.getWidth();
            title.getPaint().setShader(new LinearGradient(0, 0, Math.max(1f, width), 0,
                    Color.WHITE, accent, Shader.TileMode.CLAMP));
            title.invalidate();
        });
    }

    private void paintWifi(int accent) {
        wifi.setBackground(onWifi
                ? card(accent & 0x00FFFFFF | 0x66000000, accent, 2.25f, 19.5f)
                : card(0x47000000, 0x33FFFFFF, 1f, 19.5f));
    }

    /**
     * The channel's current picture if it has any, decoded off the main thread; its logo on its colour otherwise.
     * A turn of the rotation keeps the old picture up until the new one is decoded, then fades it in.
     */
    private void showArt(Channel c, boolean turn) {
        if (c.pictures.isEmpty()) {
            art.show(c.color, null, c.banner);
            return;
        }
        File file = c.pictures.get(c.shown % c.pictures.size());
        String key = file.getPath();
        Bitmap cached = pictures.get(key);
        if (cached != null && gains.containsKey(key)) {
            showPicture(c, cached, gains.get(key), turn);
            return;
        }
        boolean store = file.getParentFile().getParentFile().getName().equals("auto");
        if (!turn) art.show(c.color, null, null);
        pictureWorker.execute(() -> {
            Bitmap decoded = decodeForScreen(file);
            float gain = decoded == null ? 1f : ArtView.gainFor(decoded, store);
            main.post(() -> {
                if (decoded != null) {
                    pictures.put(key, decoded);
                    gains.put(key, gain);
                }
                if (channels.isEmpty() || channels.get(selected) != c) return;
                if (decoded != null) showPicture(c, decoded, gain, turn);
                else if (!turn) art.show(c.color, null, c.banner);
            });
        });
    }

    /**
     * Pictures can come at any size, from a camera or a theme's link. The picture fills the 70% of the screen right of
     * the text, so it is halved for as long as it stays at least that wide: a 3440 px photo decodes at 1720 px, about
     * 8 MB instead of 32, which a 1 GB TV can afford twice over.
     */
    private Bitmap decodeForScreen(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        int area = Math.round(getResources().getDisplayMetrics().widthPixels * .7f);
        while (bounds.outWidth / (options.inSampleSize * 2) >= area) options.inSampleSize *= 2;
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    private void showPicture(Channel c, Bitmap picture, float gain, boolean turn) {
        art.show(c.color, picture, null, gain);
        if (!turn) return;
        art.setAlpha(.45f);
        art.animate().alpha(1f).setDuration(700).start();
    }

    /** Brings the selected tile's page of six into place and shows an arrow on each side that has another page. */
    private void showPage(boolean slide) {
        int start = selected - selected % COLUMNS;
        int tile = tiles.getWidth() / COLUMNS;
        if (slide && tile > 0) {
            int dx = start * tile - tiles.computeHorizontalScrollOffset();
            if (dx != 0) tiles.smoothScrollBy(dx, 0, new DecelerateInterpolator(), 260);
        } else {
            row.scrollToPositionWithOffset(start, 0);
        }
        arrowLeft.setVisibility(start > 0 ? View.VISIBLE : View.INVISIBLE);
        arrowRight.setVisibility(start + COLUMNS < channels.size() ? View.VISIBLE : View.INVISIBLE);
    }

    private void render(boolean slide) {
        if (channels.isEmpty()) return;
        Channel c = channels.get(selected);
        paintWifi(c.accent);
        title.getPaint().setShader(null);
        title.setText(c.name);
        paintTitle(c.accent);
        selectedLabel.setTextColor(c.accent);
        description.setText(c.description);
        showArt(c, false);
        art.setAlpha(.6f);
        art.animate().alpha(1f).setDuration(220).start();
        main.removeCallbacks(rotate);
        main.postDelayed(rotate, ROTATE_MS);
        showPage(slide);
        if (painted >= 0 && painted != selected) tiles.getAdapter().notifyItemChanged(painted);
        tiles.getAdapter().notifyItemChanged(selected);
        painted = selected;
        prefs.edit().putString("selected", c.pkg).apply();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean dpad = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean ok = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_NUMPAD_ENTER || key == KeyEvent.KEYCODE_BUTTON_A;
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (key == KeyEvent.KEYCODE_BACK) {
            // Home is the bottom of the stack: Back only closes the menu or ends Mover.
            if (down && event.getRepeatCount() == 0) {
                if (menuIndex >= 0) closeMenu();
                else if (moving) finishMove();
            }
            return true;
        }
        if (!dpad && !ok) return super.dispatchKeyEvent(event);
        if (ok) {
            press(event);
        } else if (down) {
            if (menuIndex >= 0) moveInMenu(key);
            else if (moving) carry(key);
            else move(key);
        }
        return true;
    }

    /**
     * OK acts when it is let go, so holding it can mean something else: past the long-press timeout the menu opens
     * and the release does nothing. In the menu and in Mover, OK acts at once and its repeats are ignored.
     */
    private void press(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() != 0) {
                // A remote that repeats a held key, or flags it as a long press, says so before the timer does.
                if (okPending && !longPressed) {
                    main.removeCallbacks(longPress);
                    longPress.run();
                }
                return;
            }
            if (menuIndex >= 0) {
                choose();
            } else if (moving) {
                finishMove();
            } else if (onWifi || channels.isEmpty()) {
                open();
            } else {
                okPending = true;
                longPressed = false;
                main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP && okPending) {
            okPending = false;
            main.removeCallbacks(longPress);
            if (!longPressed && !event.isCanceled()) open();
        }
    }

    private void showMenu() {
        if (channels.isEmpty() || onWifi) return;
        String pkg = channels.get(selected).pkg;
        menuActions.clear();
        menuActions.add(OPEN);
        menuActions.add(MOVE);
        if (PLAY_STORE.equals(installerOf(pkg))) menuActions.add(UPDATE);
        menuActions.add(INFO);
        if (!isSystemApp(pkg)) menuActions.add(UNINSTALL);
        menu.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (String action : menuActions) {
            TextView item = (TextView) inflater.inflate(R.layout.item_menu, menu, false);
            item.setText(action);
            if (UNINSTALL.equals(action)) item.setTextColor(getColor(R.color.danger));
            menu.addView(item);
        }
        menuIndex = 0;
        paintMenu();
        menu.setAlpha(0f);
        menu.setVisibility(View.VISIBLE);
        menu.post(this::placeMenu);
    }

    /** Just above the selected tile, lined up with its left edge and kept inside the row's margins. */
    private void placeMenu() {
        if (menuIndex < 0) return;
        float dp = getResources().getDisplayMetrics().density;
        View root = (View) menu.getParent();
        View tile = row.findViewByPosition(selected);
        int[] at = new int[2], origin = new int[2];
        root.getLocationInWindow(origin);
        float x = 48 * dp, y = root.getHeight() - menu.getHeight() - 150 * dp;
        if (tile != null) {
            tile.getLocationInWindow(at);
            x = at[0] - origin[0] + 6 * dp;
            y = at[1] - origin[1] - menu.getHeight() - 10 * dp;
        }
        menu.setX(Math.min(x, root.getWidth() - menu.getWidth() - 48 * dp));
        menu.setY(Math.max(24 * dp, y));
        menu.animate().alpha(1f).setDuration(140).start();
    }

    private void paintMenu() {
        int accent = channels.get(selected).accent;
        menu.setBackground(card(0xFF0B1013, 0x29FFFFFF, 1f, 10f));
        for (int i = 0; i < menu.getChildCount(); i++) {
            menu.getChildAt(i).setBackground(i == menuIndex
                    ? card(accent & 0x00FFFFFF | 0x47000000, accent, 2.25f, 7.5f) : null);
        }
    }

    private void moveInMenu(int key) {
        int next = menuIndex;
        if (key == KeyEvent.KEYCODE_DPAD_UP && menuIndex > 0) next--;
        if (key == KeyEvent.KEYCODE_DPAD_DOWN && menuIndex + 1 < menuActions.size()) next++;
        if (next == menuIndex) return;
        menuIndex = next;
        paintMenu();
    }

    private void closeMenu() {
        if (menuIndex < 0) return;
        menuIndex = -1;
        menu.animate().cancel();
        menu.setVisibility(View.GONE);
    }

    private void choose() {
        String action = menuActions.get(menuIndex);
        String pkg = channels.get(selected).pkg;
        Uri app = Uri.fromParts("package", pkg, null);
        closeMenu();
        switch (action) {
            case OPEN:
                open();
                break;
            case MOVE:
                startMove();
                break;
            case UPDATE:
                launch(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)).setPackage(PLAY_STORE));
                break;
            case INFO:
                launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app));
                break;
            case UNINSTALL:
                launch(new Intent(Intent.ACTION_DELETE, app));
                break;
            default:
                break;
        }
    }

    private void launch(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException noScreenForIt) {
            // This TV has no screen for that action; the menu is already closed.
        }
    }

    @SuppressWarnings("deprecation")
    private String installerOf(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? pm.getInstallSourceInfo(pkg).getInstallingPackageName() : pm.getInstallerPackageName(pkg);
        } catch (Exception unknown) {
            return null;
        }
    }

    /** Preinstalled apps can't be uninstalled from here, only disabled from a computer. */
    private boolean isSystemApp(String pkg) {
        try {
            return (getPackageManager().getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException gone) {
            return true;
        }
    }

    private void startMove() {
        if (channels.isEmpty()) return;
        moving = true;
        moveStart = selected;
        selectedLabel.setText("MOVIENDO");
        description.setText("Usa ◀ ▶ para cambiar su lugar. OK o Atrás para terminar.");
        tiles.getAdapter().notifyItemChanged(selected);
    }

    /** Swaps the lifted tile with its neighbour; the page follows it past the edge like a normal move. */
    private void carry(int key) {
        int to = selected;
        if (key == KeyEvent.KEYCODE_DPAD_LEFT && selected > 0) to--;
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT && selected + 1 < channels.size()) to++;
        if (to == selected) return;
        Collections.swap(channels, selected, to);
        int from = selected;
        selected = to;
        painted = to;
        tiles.getAdapter().notifyItemChanged(from);
        tiles.getAdapter().notifyItemChanged(to);
        showPage(true);
    }

    /** Saves the row; Play Store and Ajustes go back to the end unless they were the ones carried somewhere. */
    private void finishMove() {
        moving = false;
        String lifted = channels.get(selected).pkg;
        Set<String> moved = movedByUser();
        if (selected != moveStart) moved.add(lifted);
        List<String> order = new ArrayList<>();
        for (Channel c : channels) order.add(c.pkg);
        keepLast(order, moved);
        List<Channel> sorted = new ArrayList<>();
        for (String pkg : order) sorted.add(channels.get(indexOf(pkg)));
        channels.clear();
        channels.addAll(sorted);
        selected = indexOf(lifted);
        painted = selected;
        prefs.edit().putString("order", new JSONArray(order).toString()).putStringSet("moved", moved).apply();
        selectedLabel.setText(R.string.selected);
        description.setText(channels.get(selected).description);
        tiles.getAdapter().notifyDataSetChanged();
        showPage(true);
    }

    private void move(int key) {
        if (channels.isEmpty()) return;
        if (onWifi || key == KeyEvent.KEYCODE_DPAD_UP) {
            boolean nextOnWifi = onWifi ? key != KeyEvent.KEYCODE_DPAD_DOWN : true;
            if (nextOnWifi == onWifi) return;
            onWifi = nextOnWifi;
            paintWifi(channels.get(selected).accent);
            tiles.getAdapter().notifyItemChanged(selected);
            return;
        }
        int next = selected;
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (selected > 0) next--;
        } else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (selected + 1 < channels.size()) next++;
        }
        if (next == selected) return;
        selected = next;
        render(true);
    }

    private void openWifi() {
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException noWifiScreen) {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private void open() {
        if (onWifi) {
            openWifi();
            return;
        }
        if (channels.isEmpty()) return;
        String pkg = channels.get(selected).pkg;
        PackageManager pm = getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null && SETTINGS.equals(pkg)) launch = new Intent(Settings.ACTION_SETTINGS);
        if (launch == null) return;
        try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException gone) {
            loadedSignature = "";
            loadChannels();
        }
    }

    private final class Tiles extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tile, parent, false);
            view.getLayoutParams().width = parent.getWidth() / COLUMNS;
            return new RecyclerView.ViewHolder(view) {};
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            // Past the last app: an empty slot that fills out the last page, so it can slide fully into place.
            holder.itemView.setVisibility(position < channels.size() ? View.VISIBLE : View.INVISIBLE);
            if (position >= channels.size()) return;
            Channel c = channels.get(position);
            boolean isSettings = SETTINGS.equals(c.pkg);
            View card = holder.itemView.findViewById(R.id.card);
            TextView name = holder.itemView.findViewById(R.id.name);
            name.setText(c.name);
            ImageView icon = holder.itemView.findViewById(R.id.icon);
            if (isSettings) icon.setImageResource(R.drawable.ic_gear);
            else icon.setImageDrawable(c.icon);
            // Translucent on the shared background, so the row reads as part of it and not as a bar over it.
            boolean lifted = moving && position == selected;
            holder.itemView.setTranslationY(lifted ? -8 * getResources().getDisplayMetrics().density : 0f);
            if (lifted) {
                card.setBackground(card(c.accent & 0x00FFFFFF | 0x8C000000, c.accent, 3f, 7.5f));
            } else if (position == selected && !onWifi) {
                card.setBackground(card(c.accent & 0x00FFFFFF | 0x47000000, c.accent, 2.25f, 7.5f));
            } else if (position == selected) {
                card.setBackground(card(0x59000000, c.accent & 0x00FFFFFF | 0x80000000, 1f, 7.5f));
            } else {
                card.setBackground(card(0x47000000, 0x1FFFFFFF, 1f, 7.5f));
            }
            holder.itemView.setContentDescription(c.name);
        }

        @Override
        public int getItemCount() {
            return (channels.size() + COLUMNS - 1) / COLUMNS * COLUMNS;
        }
    }
}
