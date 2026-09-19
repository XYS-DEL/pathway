package com.acooldog.nfc.internal;

import android.content.Context;

import com.acooldog.nfc.NfcPayload;
import com.acooldog.nfc.NfcPayloadDispatchResult;

/** 把一条载荷发出去。 */
public final class SendNfcPayloadUseCase {
    private final NfcPayloadDispatcher dispatcher;

    public SendNfcPayloadUseCase(NfcPayloadDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public NfcPayloadDispatchResult send(Context context, NfcPayload payload) {
        return dispatcher.dispatch(context, payload);
    }
}
