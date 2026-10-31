package de.robv.android.xposed;

/** Compile-time stub only. Real implementation provided by LSPosed at runtime.
 *  This class MUST NOT be packed into the module dex. */
public class XSharedPreferences {
    public XSharedPreferences(String packageName, String prefFileName) {}
    public void reload() {}
    public boolean getBoolean(String key, boolean def) { return def; }
    public String getString(String key, String def) { return def; }
    public int getInt(String key, int def) { return def; }
}