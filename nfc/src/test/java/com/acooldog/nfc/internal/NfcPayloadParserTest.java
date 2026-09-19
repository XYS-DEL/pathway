package com.acooldog.nfc.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class NfcPayloadParserTest {
    private final NfcPayloadParser parser = new NfcPayloadParser();

    @Test
    public void parse_returnsEmptyPayloadWhenRecordsAreMissing() {
        assertTrue(parser.parse(null, "android.nfc.action.NDEF_DISCOVERED").isEmpty());
        assertTrue(parser.parse(Collections.emptyList(), "android.nfc.action.NDEF_DISCOVERED").isEmpty());
    }

    @Test
    public void parse_takesFirstNonEmptyUrlAndPackageAcrossRecords() {
        List<NfcRecordData> records = Arrays.asList(
                new NfcRecordData("", ""),
                new NfcRecordData("https://example.com/run", ""),
                new NfcRecordData("https://ignored.example.com", "com.example.target")
        );

        com.acooldog.nfc.NfcPayload payload = parser.parse(records, "android.nfc.action.NDEF_DISCOVERED");

        assertEquals("https://example.com/run", payload.getUrl());
        assertEquals("com.example.target", payload.getPackageName());
        assertEquals("android.nfc.action.NDEF_DISCOVERED", payload.getSource());
    }

    @Test
    public void parse_keepsUrlWhenNoPackageRecordExists() {
        List<NfcRecordData> records =
                Collections.singletonList(new NfcRecordData("https://example.com/run", ""));

        com.acooldog.nfc.NfcPayload payload = parser.parse(records, "tag");

        assertTrue(payload.hasUrl());
        assertFalse(payload.hasPackageName());
        assertFalse(payload.isEmpty());
    }

    @Test
    public void parse_skipsNullRecords() {
        List<NfcRecordData> records = new ArrayList<>();
        records.add(null);
        records.add(new NfcRecordData("https://example.com/run", "com.example.target"));

        com.acooldog.nfc.NfcPayload payload = parser.parse(records, "tag");

        assertEquals("https://example.com/run", payload.getUrl());
        assertEquals("com.example.target", payload.getPackageName());
    }

    @Test
    public void parse_returnsEmptyWhenEveryRecordIsBlank() {
        List<NfcRecordData> records = Arrays.asList(
                new NfcRecordData("", ""),
                new NfcRecordData("   ", "  ")
        );

        assertTrue(parser.parse(records, "tag").isEmpty());
    }
}
