package com.acooldog.nfc;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * NFC 配置的持久化：上次用过的载荷，以及用户存下来的配置列表。
 *
 * <p>默认落在 {@code nfc_config_store} 这个 SharedPreferences 文件里。首次构造时会尝试从
 * 老版本的 {@code simulation_prefs_store} 里搬一次历史数据（那里曾把 NFC 配置和跑步模拟配置
 * 混在同一个文件），搬完打标记，只搬一次，且只补新文件里还缺的键。
 *
 * <p>需要和其他模块隔离时，可用 {@link #NfcConfigStore(Context, String)} 指定文件名，
 * 此时不会执行老数据迁移。
 */
public final class NfcConfigStore {
    /** 默认的 SharedPreferences 文件名。 */
    public static final String DEFAULT_PREFERENCES_NAME = "nfc_config_store";

    /** 老版本把 NFC 配置混在这个文件里。 */
    private static final String LEGACY_PREFERENCES_NAME = "simulation_prefs_store";

    private static final String KEY_LEGACY_MIGRATED = "legacy_migrated";
    private static final String KEY_URL = "nfc_url";
    private static final String KEY_PACKAGE = "nfc_package";
    private static final String KEY_SOURCE = "nfc_source";
    private static final String KEY_SAVED_CONFIGS = "nfc_saved_configs";

    /** 没有显式来源时使用的默认值。 */
    public static final String DEFAULT_SOURCE = "manual";

    private final SharedPreferences preferences;

    public NfcConfigStore(Context context) {
        this(context, DEFAULT_PREFERENCES_NAME);
    }

    public NfcConfigStore(Context context, String preferencesName) {
        Context appContext = context.getApplicationContext();
        this.preferences = appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
        if (DEFAULT_PREFERENCES_NAME.equals(preferencesName)) {
            migrateLegacyIfNeeded(appContext);
        }
    }

    public String getUrl() {
        return preferences.getString(KEY_URL, "");
    }

    public String getPackageName() {
        return preferences.getString(KEY_PACKAGE, "");
    }

    public String getSource() {
        return normalize(preferences.getString(KEY_SOURCE, DEFAULT_SOURCE), DEFAULT_SOURCE);
    }

    /** 记住上次用过的 URL、包名和来源。 */
    public void savePayload(String url, String packageName, String source) {
        preferences.edit()
                .putString(KEY_URL, normalize(url, ""))
                .putString(KEY_PACKAGE, normalize(packageName, ""))
                .putString(KEY_SOURCE, normalize(source, DEFAULT_SOURCE))
                .apply();
    }

    public NfcPayload getLastPayload() {
        return new NfcPayload(getUrl(), getPackageName(), getSource());
    }

    /** 读取本地保存的配置列表。同名配置只保留最后写入的那条。 */
    public List<SavedNfcConfig> getSavedConfigs() {
        List<SavedNfcConfig> configs = new ArrayList<>();
        String raw = preferences.getString(KEY_SAVED_CONFIGS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                SavedNfcConfig config = new SavedNfcConfig(
                        item.optString("name", ""),
                        item.optString("url", ""),
                        item.optString("packageName", ""),
                        item.optString("source", DEFAULT_SOURCE)
                );
                if (config.isComplete()) {
                    configs.add(config);
                }
            }
        } catch (Exception ignored) {
            // 缓存数据损坏时按空列表处理，不打断调用方。
        }
        return configs;
    }

    /** 保存一条配置。同名则覆盖，新配置排在最前。不完整的配置会被忽略。 */
    public void saveSavedConfig(SavedNfcConfig config) {
        if (config == null || !config.isComplete()) {
            return;
        }
        List<SavedNfcConfig> configs = getSavedConfigs();
        boolean replaced = false;
        for (int index = 0; index < configs.size(); index++) {
            if (configs.get(index).getName().equalsIgnoreCase(config.getName())) {
                configs.set(index, config);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            configs.add(0, config);
        }
        persistSavedConfigs(configs);
    }

    /** 按名称删除一条配置，返回是否真的删掉了。 */
    public boolean deleteSavedConfig(String name) {
        if (name == null) {
            return false;
        }
        List<SavedNfcConfig> configs = getSavedConfigs();
        boolean removed = false;
        for (int index = configs.size() - 1; index >= 0; index--) {
            if (configs.get(index).getName().equalsIgnoreCase(name.trim())) {
                configs.remove(index);
                removed = true;
            }
        }
        if (removed) {
            persistSavedConfigs(configs);
        }
        return removed;
    }

    private void persistSavedConfigs(List<SavedNfcConfig> configs) {
        JSONArray array = new JSONArray();
        if (configs != null) {
            for (SavedNfcConfig config : configs) {
                if (config == null || !config.isComplete()) {
                    continue;
                }
                JSONObject item = new JSONObject();
                try {
                    item.put("name", config.getName());
                    item.put("url", config.getUrl());
                    item.put("packageName", config.getPackageName());
                    item.put("source", normalize(config.getSource(), DEFAULT_SOURCE));
                    array.put(item);
                } catch (Exception ignored) {
                    // 单条写失败就跳过，不影响其余配置。
                }
            }
        }
        preferences.edit().putString(KEY_SAVED_CONFIGS, array.toString()).apply();
    }

    private void migrateLegacyIfNeeded(Context appContext) {
        if (preferences.getBoolean(KEY_LEGACY_MIGRATED, false)) {
            return;
        }

        SharedPreferences legacy =
                appContext.getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit().putBoolean(KEY_LEGACY_MIGRATED, true);

        // 只补新文件里还缺的键，避免覆盖调用方已经写进去的值。
        copyIfMissing(editor, legacy, KEY_URL, "");
        copyIfMissing(editor, legacy, KEY_PACKAGE, "");
        copyIfMissing(editor, legacy, KEY_SOURCE, "");
        copyIfMissing(editor, legacy, KEY_SAVED_CONFIGS, "[]");

        editor.apply();
    }

    private void copyIfMissing(
            SharedPreferences.Editor editor,
            SharedPreferences legacy,
            String key,
            String fallback
    ) {
        if (preferences.contains(key) || !legacy.contains(key)) {
            return;
        }
        editor.putString(key, legacy.getString(key, fallback));
    }

    private String normalize(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}
