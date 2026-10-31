package com.gathin.tvhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home, built from the Cine board: the selected channel fills the top, its tile sits in the row below.
 * Focus is one number kept here rather than Android's focus search, so every D-pad press has one result:
 * Left/Right move one tile, sliding to the next page of six past the edge of a page and stopping at the first and
 * last app; Up goes to the Wi-Fi button and Down from there comes back to the same tile.
 */
public final class MainActivity extends Activity {
    private static final int COLUMNS = 6;
    private static final String SETTINGS = "com.android.tv.settings";
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
        File picture;
        int color, accent;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Channel> channels = new ArrayList<>();
    // Two full-screen pictures at most: the one on screen and the one just left.
    private final LruCache<String, Bitmap> pictures = new LruCache<>(2);
    private final ExecutorService pictureWorker = Executors.newSingleThreadExecutor();
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
        row = new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false);
        tiles.setLayoutManager(row);
        tiles.setItemAnimator(null);
        tiles.setAdapter(new Tiles());
        tiles.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(tick, new IntentFilter(Intent.ACTION_TIME_TICK), null, null);
        updateClock();
        loadChannels();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(tick);
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
            List<String> dock = strings(theme, "dock_apps");
            if (dock.isEmpty()) dock = List.of(DEFAULT_DOCK);
            for (String pkg : dock) if (installed.containsKey(pkg) && !hidden.contains(pkg)) order.add(pkg);
            // An app installed later lands on the rows under the dock, so nothing becomes unreachable.
            for (String pkg : installed.keySet()) if (!order.contains(pkg) && !hidden.contains(pkg)) order.add(pkg);
            StringBuilder stamps = new StringBuilder();
            for (String pkg : order) {
                File picture = pictureFile(pkg);
                if (picture != null) stamps.append(picture.getName()).append(picture.lastModified());
            }
            String signature = order + "|" + labels + "|" + notes + "|" + setColors + "|" + stamps;
            if (signature.equals(loadedSignature)) return;
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
                c.picture = pictureFile(pkg);
                c.color = colorOf(setColors.get(pkg), pm, info);
                c.accent = accentOf(c.color);
                fresh.add(c);
            }
            main.post(() -> {
                loadedSignature = signature;
                channels.clear();
                channels.addAll(fresh);
                pictures.evictAll();
                selected = Math.max(0, indexOf(prefs.getString("selected", "")));
                tiles.getAdapter().notifyDataSetChanged();
                painted = selected;
                render(false);
            });
        });
    }

    /** The featured picture for an app, if one was pushed: files/hero/<package>.jpg, .png or .webp. */
    private File pictureFile(String pkg) {
        File dir = new File(getExternalFilesDir(null), "hero");
        for (String extension : new String[] {".jpg", ".png", ".webp"}) {
            File file = new File(dir, pkg + extension);
            if (file.isFile()) return file;
        }
        return null;
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
        if (dominant == 0) return getColor(R.color.accent);
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

    /** The channel's picture if it has one, decoded off the main thread; its logo on its colour otherwise. */
    private void showArt(Channel c) {
        if (c.picture == null) {
            art.show(c.color, null, c.banner);
            return;
        }
        Bitmap cached = pictures.get(c.pkg);
        if (cached != null) {
            art.show(c.color, cached, null);
            return;
        }
        art.show(c.color, null, null);
        pictureWorker.execute(() -> {
            Bitmap decoded = BitmapFactory.decodeFile(c.picture.getPath());
            main.post(() -> {
                if (decoded != null) pictures.put(c.pkg, decoded);
                if (!channels.isEmpty() && channels.get(selected) == c) {
                    art.show(c.color, decoded, decoded == null ? c.banner : null);
                }
            });
        });
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
        showArt(c);
        art.setAlpha(.6f);
        art.animate().alpha(1f).setDuration(220).start();
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
        if (key == KeyEvent.KEYCODE_BACK) return true;     // Home is the bottom of the stack: Back has nowhere to go.
        if (!dpad && !ok) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (ok) {
                if (event.getRepeatCount() == 0) open();
            } else {
                move(key);
            }
        }
        return true;
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
            if (position == selected && !onWifi) {
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
