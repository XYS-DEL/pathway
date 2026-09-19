package com.acooldog.nfc.internal;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.os.Parcelable;

import com.acooldog.nfc.NfcPayload;
import com.acooldog.nfc.NfcPayloadDispatchResult;

/**
 * 构造一条 {@code ACTION_NDEF_DISCOVERED} 广播，带上 URI 记录和应用记录（AAR），
 * 效果等同于把这个应用自己的 NDEF 消息"刷"给了目标应用。
 *
 * <p>目标应用不接受 NDEF 时退化为普通 {@code ACTION_VIEW} 打开 URL。
 */
public final class AndroidNfcPayloadDispatcher implements NfcPayloadDispatcher {
    @Override
    public NfcPayloadDispatchResult dispatch(Context context, NfcPayload payload) {
        try {
            Intent ndefIntent = createNdefIntent(payload);
            context.startActivity(ndefIntent);
            return NfcPayloadDispatchResult.ndefSent();
        } catch (ActivityNotFoundException | SecurityException exception) {
            return dispatchViewFallback(context, payload, exception);
        } catch (Exception exception) {
            return NfcPayloadDispatchResult.failed(exception.getMessage());
        }
    }

    private Intent createNdefIntent(NfcPayload payload) {
        Uri uri = Uri.parse(payload.getUrl());
        NdefRecord uriRecord = NdefRecord.createUri(uri);
        NdefRecord appRecord = NdefRecord.createApplicationRecord(payload.getPackageName());
        NdefMessage message = new NdefMessage(new NdefRecord[]{uriRecord, appRecord});

        Intent intent = new Intent(NfcAdapter.ACTION_NDEF_DISCOVERED);
        intent.setData(uri);
        intent.setPackage(payload.getPackageName());
        intent.putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, new Parcelable[]{message});
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return intent;
    }

    private NfcPayloadDispatchResult dispatchViewFallback(
            Context context,
            NfcPayload payload,
            Exception firstFailure
    ) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(payload.getUrl()));
        intent.setPackage(payload.getPackageName());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            context.startActivity(intent);
            return NfcPayloadDispatchResult.fallbackViewSent();
        } catch (ActivityNotFoundException | SecurityException secondFailure) {
            // 指定包名打不开，去掉包名再试一次，让系统自己挑应用。
            Intent genericIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(payload.getUrl()));
            genericIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(genericIntent);
                return NfcPayloadDispatchResult.fallbackViewSent();
            } catch (Exception finalFailure) {
                String detail = firstFailure.getMessage();
                if (detail == null || detail.trim().isEmpty()) {
                    detail = finalFailure.getMessage();
                }
                return NfcPayloadDispatchResult.failed(detail);
            }
        }
    }
}
