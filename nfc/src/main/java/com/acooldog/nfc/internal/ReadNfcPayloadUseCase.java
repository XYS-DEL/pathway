package com.acooldog.nfc.internal;

import android.content.Intent;

import com.acooldog.nfc.NfcPayload;

/** 从一个 NFC Intent 解出载荷。来源取自 Intent 的 action。 */
public final class ReadNfcPayloadUseCase {
    private final NfcRecordReader recordReader;
    private final NfcPayloadParser payloadParser;

    public ReadNfcPayloadUseCase(NfcRecordReader recordReader, NfcPayloadParser payloadParser) {
        this.recordReader = recordReader;
        this.payloadParser = payloadParser;
    }

    public NfcPayload read(Intent intent) {
        String source = intent == null || intent.getAction() == null ? "" : intent.getAction();
        return payloadParser.parse(recordReader.readRecords(intent), source);
    }
}
