package com.acooldog.nfc;

import android.content.Context;

import com.acooldog.nfc.internal.AndroidNfcPayloadDispatcher;
import com.acooldog.nfc.internal.SendNfcPayloadUseCase;

/**
 * 模拟发卡入口：把一条载荷当作"刷到的标签"发给目标应用。
 *
 * <p>无状态，可任意调用。
 */
public final class NfcSender {
    private static final SendNfcPayloadUseCase USE_CASE =
            new SendNfcPayloadUseCase(new AndroidNfcPayloadDispatcher());

    private NfcSender() {
    }

    public static NfcPayloadDispatchResult send(Context context, NfcPayload payload) {
        if (context == null || payload == null || payload.isEmpty()) {
            return NfcPayloadDispatchResult.failed("payload is empty");
        }
        return USE_CASE.send(context, payload);
    }

    public static NfcPayloadDispatchResult send(
            Context context,
            String url,
            String packageName,
            String source
    ) {
        return send(context, new NfcPayload(url, packageName, source));
    }
}
