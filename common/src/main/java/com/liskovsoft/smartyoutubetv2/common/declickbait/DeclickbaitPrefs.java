package com.liskovsoft.smartyoutubetv2.common.declickbait;

import android.annotation.SuppressLint;
import android.content.Context;

import androidx.core.content.ContextCompat;

import com.liskovsoft.sharedutils.mylogger.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

/**
 * Device-wide de-clickbait settings, including the Claude API key.
 * <p>
 * Stored in the app's {@code no_backup} folder on purpose: SmartTube's Google Drive backup zips
 * {@code shared_prefs} and {@code files}, and Android's own Auto Backup skips {@code no_backup},
 * so the key never leaves the device.
 */
public class DeclickbaitPrefs {
    private static final String TAG = DeclickbaitPrefs.class.getSimpleName();
    private static final String FILE_NAME = "declickbait.json";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static final String MODEL_OPUS = "claude-opus-5-5";
    public static final String MODEL_HAIKU = "claude-haiku-5-5";

    @SuppressLint("StaticFieldLeak")
    private static DeclickbaitPrefs sInstance;
    private final File mFile;
    private String mApiKey;
    private String mModel = MODEL_OPUS;
    private boolean mButtonEnabled = true;

    private DeclickbaitPrefs(Context context) {
        mFile = new File(ContextCompat.getNoBackupFilesDir(context.getApplicationContext()), FILE_NAME);
        load();
    }

    public static synchronized DeclickbaitPrefs instance(Context context) {
        if (sInstance == null) {
            sInstance = new DeclickbaitPrefs(context);
        }
        return sInstance;
    }

    public synchronized String getApiKey() {
        return mApiKey;
    }

    public synchronized boolean hasApiKey() {
        return mApiKey != null && !mApiKey.isEmpty();
    }

    /**
     * Saves the key with any whitespace removed (pasting can add spaces or line breaks).
     *
     * @return false, without saving, if what's left doesn't look like an API key
     */
    public synchronized boolean setApiKey(String apiKey) {
        String cleaned = apiKey != null ? apiKey.replaceAll("\\s+", "") : "";
        if (!cleaned.isEmpty() && !isWellFormedKey(cleaned)) {
            return false;
        }
        mApiKey = cleaned.isEmpty() ? null : cleaned;
        save();
        return true;
    }

    /** Claude API keys are ASCII letters, digits, '-' and '_'. Anything else can't go in an HTTP header. */
    public static boolean isWellFormedKey(String key) {
        return key != null && key.matches("[A-Za-z0-9_-]{20,300}");
    }

    /** "…x7Qa", or null when no key is set. */
    public synchronized String getMaskedKey() {
        if (!hasApiKey()) {
            return null;
        }
        return "…" + mApiKey.substring(Math.max(0, mApiKey.length() - 4));
    }

    public synchronized String getModel() {
        return mModel;
    }

    public synchronized void setModel(String model) {
        mModel = model;
        save();
    }

    /** Effort tuned per model from the prompt test: Haiku at low effort missed most jump points. */
    public synchronized String getEffort() {
        return MODEL_HAIKU.equals(mModel) ? "medium" : "low";
    }

    public synchronized boolean isButtonEnabled() {
        return mButtonEnabled;
    }

    public synchronized void setButtonEnabled(boolean enabled) {
        mButtonEnabled = enabled;
        save();
    }

    private void load() {
        if (!mFile.exists()) {
            return;
        }

        try (InputStream in = new FileInputStream(mFile)) {
            byte[] data = new byte[(int) mFile.length()];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
            JSONObject json = new JSONObject(new String(data, 0, read, UTF8));
            mApiKey = json.optString("apiKey", null);
            mModel = json.optString("model", MODEL_OPUS);
            mButtonEnabled = json.optBoolean("buttonEnabled", true);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Can't read settings: %s", e.getMessage());
        }
    }

    private void save() {
        try {
            JSONObject json = new JSONObject();
            json.put("apiKey", mApiKey);
            json.put("model", mModel);
            json.put("buttonEnabled", mButtonEnabled);

            File parent = mFile.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }

            try (OutputStream out = new FileOutputStream(mFile)) {
                out.write(json.toString().getBytes(UTF8));
            }
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Can't save settings: %s", e.getMessage());
        }
    }
}
