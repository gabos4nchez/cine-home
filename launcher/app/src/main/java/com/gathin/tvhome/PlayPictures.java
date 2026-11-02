package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Featured pictures for an app that has none of its own, taken from its Play Store listing: the landscape
 * screenshots (the TV ones), dark enough to sit behind the title. They land in files/auto/<package>/ as 0.img..5.img
 * with a .stamp, and are fetched again after a week. An app that isn't on Play, or has no screenshot that fits, gets
 * an empty folder with a stamp, so it keeps its logo and isn't asked for again until then.
 *
 * The Play page is not an API: when its markup changes this finds nothing, and the apps keep their logos.
 *
 * The same folders also hold the pictures a theme links to by address (app_pictures), for apps that aren't on Play:
 * files/links/<package>/, fetched again after a week or as soon as the list of addresses changes.
 */
final class PlayPictures {
    static final long FRESH_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int MAX = 6, MAX_LINKS = 10;
    private static final int PAGE_LIMIT = 4 << 20, IMAGE_LIMIT = 2 << 20, LINK_LIMIT = 8 << 20;
    // data-screenshot-index marks the listing's own screenshots, in every language; the rest are other apps' icons.
    private static final Pattern SHOT = Pattern.compile("<img[^>]+data-screenshot-index[^>]*>");
    private static final Pattern SRC = Pattern.compile("src=\"(https://play-lh\\.googleusercontent\\.com/[A-Za-z0-9_\\-]+)");
    private static final String AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private PlayPictures() {}

    static boolean fresh(File dir) {
        File stamp = new File(dir, ".stamp");
        return stamp.isFile() && System.currentTimeMillis() - stamp.lastModified() < FRESH_MS;
    }

    /** The pictures already fetched for an app, in order. */
    static List<File> stored(File dir) {
        List<File> out = new ArrayList<>();
        for (int i = 0; i < MAX_LINKS; i++) {
            File file = new File(dir, i + ".img");
            if (file.isFile()) out.add(file);
        }
        return out;
    }

    /** Returns false when the network failed, so the caller tries again on the next visit instead of in a week. */
    static boolean fetch(String pkg, File dir) {
        String page;
        try {
            page = new String(get("https://play.google.com/store/apps/details?id=" + pkg + "&hl="
                    + Locale.getDefault().getLanguage(), PAGE_LIMIT), "UTF-8");
        } catch (FileNotFoundException notOnPlay) {
            page = "";
        } catch (IOException offline) {
            return false;
        }
        Set<String> urls = new LinkedHashSet<>();
        Matcher img = SHOT.matcher(page);
        while (img.find()) {
            Matcher src = SRC.matcher(img.group());
            if (src.find()) urls.add(src.group(1));
        }
        List<byte[]> keep = new ArrayList<>();
        for (String url : urls) {
            if (keep.size() == MAX) break;
            try {
                byte[] bytes = get(url + "=w1280-h720", IMAGE_LIMIT);
                if (fits(bytes)) keep.add(bytes);
            } catch (IOException skipped) {
                // One picture lost is not worth failing the app for.
            }
        }
        save(dir, keep, pkg);
        return true;
    }

    /** The theme's addresses for this app are the ones these pictures came from, less than a week ago. */
    static boolean linksFresh(File dir, List<String> urls) {
        if (!fresh(dir)) return false;
        try {
            byte[] stamp = java.nio.file.Files.readAllBytes(new File(dir, ".stamp").toPath());
            return new String(stamp, "UTF-8").equals(String.join("\n", urls));
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * Fetches the pictures a theme lists for an app, in its order; anything that doesn't decode as an image is skipped.
     * Returns false when nothing could be reached at all, so the caller tries again on the next visit.
     */
    static boolean fetchLinks(List<String> urls, File dir) {
        List<byte[]> keep = new ArrayList<>();
        boolean reached = false;
        for (String url : urls) {
            if (keep.size() == MAX_LINKS) break;
            try {
                byte[] bytes = get(url, LINK_LIMIT);
                reached = true;
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                if (bounds.outWidth > 0) keep.add(bytes);
            } catch (FileNotFoundException gone) {
                reached = true;
            } catch (IOException | RuntimeException unreachable) {
                // A bad address or a dropped connection: skip it.
            }
        }
        if (!reached) return false;
        save(dir, keep, String.join("\n", urls));
        return true;
    }

    private static void save(File dir, List<byte[]> pictures, String stampText) {
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        for (int i = 0; i < MAX_LINKS; i++) new File(dir, i + ".img").delete();
        try {
            for (int i = 0; i < pictures.size(); i++) {
                try (FileOutputStream out = new FileOutputStream(new File(dir, i + ".img"))) {
                    out.write(pictures.get(i));
                }
            }
            try (FileOutputStream stamp = new FileOutputStream(new File(dir, ".stamp"))) {
                stamp.write(stampText.getBytes("UTF-8"));
            }
        } catch (IOException full) {
            // Storage full: whatever was written shows, and the missing stamp means another try next visit.
        }
    }

    /** Landscape (phone screenshots are portrait) and not bright: a white settings screen would glare behind the title. */
    private static boolean fits(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outWidth < bounds.outHeight * 1.4f) return false;
        BitmapFactory.Options small = new BitmapFactory.Options();
        small.inSampleSize = Math.max(1, bounds.outWidth / 64);
        Bitmap tiny = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, small);
        if (tiny == null) return false;
        double sum = 0;
        int count = 0;
        for (int y = 0; y < tiny.getHeight(); y++) {
            for (int x = 0; x < tiny.getWidth(); x++) {
                int p = tiny.getPixel(x, y);
                sum += .299 * Color.red(p) + .587 * Color.green(p) + .114 * Color.blue(p);
                count++;
            }
        }
        tiny.recycle();
        // Measured on real listings: films and dark TV apps sit under .26, grey menu screens from .32 up.
        return count > 0 && sum / count / 255 < .30;
    }

    private static byte[] get(String address, int limit) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(address).openConnection();
        http.setConnectTimeout(15000);
        http.setReadTimeout(15000);
        http.setRequestProperty("User-Agent", AGENT);
        http.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag());
        try (InputStream in = http.getInputStream()) {     // A 404 throws FileNotFoundException here.
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
                if (out.size() > limit) throw new IOException("too large");
            }
            return out.toByteArray();
        } finally {
            http.disconnect();
        }
    }
}
