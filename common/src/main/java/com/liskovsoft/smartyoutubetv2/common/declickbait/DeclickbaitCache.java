package com.liskovsoft.smartyoutubetv2.common.declickbait;

import android.content.Context;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Comparator;

/** Saved results, one small JSON file per video in the app's cache folder (never backed up). */
public class DeclickbaitCache {
    private static final int MAX_ENTRIES = 300;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static File dir(Context context) {
        return new File(context.getApplicationContext().getCacheDir(), "declickbait");
    }

    private static File file(Context context, String videoId) {
        return new File(dir(context), videoId.replaceAll("[^A-Za-z0-9_-]", "_") + ".json");
    }

    @Nullable
    public static DeclickbaitResult get(Context context, String videoId) {
        if (videoId == null) {
            return null;
        }
        File f = file(context, videoId);
        if (!f.exists()) {
            return null;
        }
        try (InputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
            return DeclickbaitResult.fromJson(new JSONObject(new String(data, 0, read, UTF8)));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    public static void put(Context context, DeclickbaitResult result) {
        if (result == null || result.videoId == null) {
            return;
        }
        File d = dir(context);
        if (!d.exists() && !d.mkdirs()) {
            return;
        }
        try (OutputStream out = new FileOutputStream(file(context, result.videoId))) {
            out.write(result.toJson().toString().getBytes(UTF8));
        } catch (IOException | JSONException ignored) {
            // A missing cache entry only costs a repeat request.
        }
        trim(d);
    }

    public static void clear(Context context) {
        File[] files = dir(context).listFiles();
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private static void trim(File d) {
        File[] files = d.listFiles();
        if (files == null || files.length <= MAX_ENTRIES) {
            return;
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                long x = a.lastModified(), y = b.lastModified();
                return x < y ? -1 : (x == y ? 0 : 1);
            }
        });
        for (int i = 0; i < files.length - MAX_ENTRIES; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }
}
