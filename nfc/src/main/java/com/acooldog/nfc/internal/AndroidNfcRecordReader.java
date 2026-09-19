package com.acooldog.nfc.internal;

import android.content.Intent;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.os.Parcelable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 基于 {@link NdefMessage} 的记录读取实现。 */
public final class AndroidNfcRecordReader implements NfcRecordReader {
    /** Android 应用记录（AAR）的外部类型名。 */
    private static final byte[] ANDROID_APP_RECORD_TYPE =
            "android.com:pkg".getBytes(StandardCharsets.US_ASCII);

    @Override
    public List<NfcRecordData> readRecords(Intent intent) {
        List<NfcRecordData> records = new ArrayList<>();
        if (intent == null) {
            return records;
        }

        Parcelable[] rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
        if (rawMessages == null) {
            return records;
        }

        for (Parcelable rawMessage : rawMessages) {
            if (!(rawMessage instanceof NdefMessage)) {
                continue;
            }
            for (NdefRecord record : ((NdefMessage) rawMessage).getRecords()) {
                records.add(new NfcRecordData(extractUri(record), extractPackageName(record)));
            }
        }
        return records;
    }

    private String extractUri(NdefRecord record) {
        try {
            Uri uri = record.toUri();
            return uri == null ? "" : uri.toString();
        } catch (Exception ignored) {
            // 记录不是 URI 类型时 toUri() 会抛异常，按空处理。
            return "";
        }
    }

    private String extractPackageName(NdefRecord record) {
        if (record.getTnf() != NdefRecord.TNF_EXTERNAL_TYPE) {
            return "";
        }
        if (!Arrays.equals(record.getType(), ANDROID_APP_RECORD_TYPE)) {
            return "";
        }
        return new String(record.getPayload(), StandardCharsets.UTF_8);
    }
}
