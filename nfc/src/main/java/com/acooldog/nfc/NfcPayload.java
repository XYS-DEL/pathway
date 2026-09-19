package com.acooldog.nfc;

/**
 * 一次 NFC 交互携带的内容：一个 URL、一个目标包名，以及来源标记。
 *
 * <p>来源（source）用于区分这条内容是手输的、从标签读到的、还是从本地配置或共享列表取来的。
 */
public final class NfcPayload {
    public static final NfcPayload EMPTY = new NfcPayload("", "", "");

    private final String url;
    private final String packageName;
    private final String source;

    public NfcPayload(String url, String packageName, String source) {
        this.url = trimToEmpty(url);
        this.packageName = trimToEmpty(packageName);
        this.source = trimToEmpty(source);
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

    public boolean hasUrl() {
        return !isBlank(url);
    }

    public boolean hasPackageName() {
        return !isBlank(packageName);
    }

    /** URL 和包名都为空时视为空载荷。来源标记不参与判定。 */
    public boolean isEmpty() {
        return !hasUrl() && !hasPackageName();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }
}
