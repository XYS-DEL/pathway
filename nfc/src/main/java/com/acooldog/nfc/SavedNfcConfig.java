package com.acooldog.nfc;

/** 用户存在本地、可随时调出复用的一条 NFC 配置。 */
public final class SavedNfcConfig {
    private final String name;
    private final String url;
    private final String packageName;
    private final String source;

    public SavedNfcConfig(String name, String url, String packageName, String source) {
        this.name = name == null ? "" : name.trim();
        this.url = url == null ? "" : url.trim();
        this.packageName = packageName == null ? "" : packageName.trim();
        this.source = source == null ? "" : source.trim();
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getSource() {
        return source;
    }

    /** 名称、URL、包名三者齐备才算一条可用配置。 */
    public boolean isComplete() {
        return !name.isEmpty() && !url.isEmpty() && !packageName.isEmpty();
    }

    public NfcPayload toPayload() {
        return new NfcPayload(url, packageName, source);
    }
}
