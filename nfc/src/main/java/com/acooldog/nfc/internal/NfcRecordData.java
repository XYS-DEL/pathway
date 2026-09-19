package com.acooldog.nfc.internal;

/** 从一条 NDEF 记录里解出来的原始数据。属于模块内部实现，不作为公开 API。 */
public final class NfcRecordData {
    private final String uri;
    private final String packageName;

    public NfcRecordData(String uri, String packageName) {
        this.uri = uri == null ? "" : uri.trim();
        this.packageName = packageName == null ? "" : packageName.trim();
    }

    public String getUri() {
        return uri;
    }

    public String getPackageName() {
        return packageName;
    }
}
