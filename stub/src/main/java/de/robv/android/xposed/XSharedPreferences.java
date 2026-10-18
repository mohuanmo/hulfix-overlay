package de.robv.android.xposed;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Map;
import java.util.Set;

/**
 * Stub of the Xposed framework's XSharedPreferences.
 *
 * <p>The real implementation is provided at runtime by the Xposed framework (LSPosed) inside
 * the hooked process. It allows the module code running inside another process (e.g. SystemUI)
 * to read this module's own SharedPreferences file. Only the read-side API used by this module
 * is declared here; {@link #edit()} is not usable across processes (writes go through the
 * module's own app process instead).</p>
 */
public class XSharedPreferences implements SharedPreferences {

    private final String mPackageName;
    private final String mPrefFileName;

    public XSharedPreferences(String packageName) {
        this(packageName, null);
    }

    public XSharedPreferences(String packageName, String prefFileName) {
        mPackageName = packageName;
        mPrefFileName = (prefFileName == null || prefFileName.isEmpty())
                ? packageName + "_preferences"
                : prefFileName;
    }

    @SuppressWarnings("unused")
    public XSharedPreferences(Context base, String prefFileName) {
        this(base.getPackageName(), prefFileName);
    }

    /** @deprecated kept for API compatibility; no-op on modern frameworks */
    @Deprecated
    public boolean makeWorldReadable() {
        return true;
    }

    /** Re-read the preference file from disk. */
    public void reload() {
    }

    public File getXFile() {
        return new File("/data/data/" + mPackageName + "/shared_prefs/" + mPrefFileName + ".xml");
    }

    @Override
    public Map<String, ?> getAll() {
        return java.util.Collections.emptyMap();
    }

    @Override
    public String getString(String key, String defValue) {
        return defValue;
    }

    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
        return defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        return defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        return defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        return defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        return defValue;
    }

    @Override
    public boolean contains(String key) {
        return false;
    }

    @Override
    public Editor edit() {
        throw new UnsupportedOperationException("XSharedPreferences is read-only inside the hooked process");
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
    }
}
