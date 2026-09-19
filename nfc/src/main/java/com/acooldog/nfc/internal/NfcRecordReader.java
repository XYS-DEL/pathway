package com.acooldog.nfc.internal;

import android.content.Intent;

import java.util.List;

/** 从一个 NFC Intent 里读出原始 NDEF 记录。 */
public interface NfcRecordReader {
    List<NfcRecordData> readRecords(Intent intent);
}
