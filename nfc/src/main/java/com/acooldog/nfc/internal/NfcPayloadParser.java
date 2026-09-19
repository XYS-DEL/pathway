package com.acooldog.nfc.internal;

import com.acooldog.nfc.NfcPayload;

import java.util.List;

/**
 * 把一组 NDEF 记录归并成一个 {@link NfcPayload}：各取第一个非空的 URL 和包名。
 *
 * <p>不依赖任何 Android 框架类，可以直接跑 JVM 单元测试。
 */
public final class NfcPayloadParser {
    public NfcPayload parse(List<NfcRecordData> records, String source) {
        if (records == null || records.isEmpty()) {
            return NfcPayload.EMPTY;
        }

        String url = "";
        String packageName = "";
        for (NfcRecordData record : records) {
            if (record == null) {
                continue;
            }
            if (url.isEmpty() && !record.getUri().isEmpty()) {
                url = record.getUri();
            }
            if (packageName.isEmpty() && !record.getPackageName().isEmpty()) {
                packageName = record.getPackageName();
            }
            if (!url.isEmpty() && !packageName.isEmpty()) {
                break;
            }
        }

        return new NfcPayload(url, packageName, source);
    }
}
