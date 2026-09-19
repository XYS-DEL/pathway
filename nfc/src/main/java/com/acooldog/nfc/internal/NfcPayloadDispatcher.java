package com.acooldog.nfc.internal;

import android.content.Context;

import com.acooldog.nfc.NfcPayload;
import com.acooldog.nfc.NfcPayloadDispatchResult;

/** 把一条载荷当作"模拟刷到"的卡发出去。 */
public interface NfcPayloadDispatcher {
    NfcPayloadDispatchResult dispatch(Context context, NfcPayload payload);
}
