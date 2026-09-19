package com.acooldog.nfc;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.os.Build;

import com.acooldog.nfc.internal.AndroidNfcRecordReader;
import com.acooldog.nfc.internal.NfcPayloadParser;
import com.acooldog.nfc.internal.ReadNfcPayloadUseCase;

/**
 * 一次"读卡会话"，把前台调度那套样板封起来。
 *
 * <p>接入方在 Activity 里转发四个生命周期回调即可：
 * <pre>{@code
 * @Override protected void onCreate(Bundle state) {
 *     super.onCreate(state);
 *     session = new NfcReaderSession(this, payload -> {
 *         if (payload.isEmpty()) { return; }   // 读到空标签
 *         urlInput.setText(payload.getUrl());
 *     });
 * }
 * @Override protected void onResume()  { super.onResume();  session.onResume(); }
 * @Override protected void onPause()   { session.onPause(); super.onPause(); }
 * @Override protected void onNewIntent(Intent intent) {
 *     super.onNewIntent(intent);
 *     setIntent(intent);
 *     session.onNewIntent(intent);
 * }
 * }</pre>
 *
 * <p>冷启动时"贴着标签启动应用"也能读到——{@link #onResume()} 会在首次调用时补读一次当前 Intent。
 */
public final class NfcReaderSession {
    /** 本机 NFC 硬件状态。 */
    public enum Status {
        /** 设备没有 NFC 硬件。 */
        NO_HARDWARE,
        /** 有硬件但系统里没开启。 */
        DISABLED,
        /** 可用。 */
        READY
    }

    /** 读到载荷时的回调。载荷可能为空，用 {@link NfcPayload#isEmpty()} 判断。 */
    public interface OnPayloadListener {
        void onPayload(NfcPayload payload);
    }

    /** 载荷自身没带来源标记时补上的默认值。 */
    public static final String DEFAULT_SOURCE = "tag";

    private final Activity activity;
    private final OnPayloadListener listener;
    private final ReadNfcPayloadUseCase readNfcPayloadUseCase;
    private final NfcAdapter nfcAdapter;
    private final PendingIntent foregroundDispatchIntent;
    private boolean startIntentHandled;

    public NfcReaderSession(Activity activity, OnPayloadListener listener) {
        this.activity = activity;
        this.listener = listener;
        this.readNfcPayloadUseCase = new ReadNfcPayloadUseCase(
                new AndroidNfcRecordReader(),
                new NfcPayloadParser()
        );
        this.nfcAdapter = NfcAdapter.getDefaultAdapter(activity);
        this.foregroundDispatchIntent = createForegroundDispatchIntent();
    }

    public Status getStatus() {
        if (nfcAdapter == null) {
            return Status.NO_HARDWARE;
        }
        if (!nfcAdapter.isEnabled()) {
            return Status.DISABLED;
        }
        return Status.READY;
    }

    /** 在 Activity 的 {@code onResume} 里调用。 */
    public void onResume() {
        if (nfcAdapter != null && nfcAdapter.isEnabled()) {
            nfcAdapter.enableForegroundDispatch(activity, foregroundDispatchIntent, null, null);
        }
        // 只在首次补读当前 Intent：否则每次回到前台都会把输入框重置成上次读到的标签值。
        if (!startIntentHandled) {
            startIntentHandled = true;
            handleNfcIntent(activity.getIntent());
        }
    }

    /** 在 Activity 的 {@code onPause} 里调用。 */
    public void onPause() {
        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(activity);
        }
    }

    /** 在 Activity 的 {@code onNewIntent} 里调用。是 NFC Intent 并已回调监听器时返回 true。 */
    public boolean onNewIntent(Intent intent) {
        return handleNfcIntent(intent);
    }

    /** 判断这个 Intent 是不是三种 NFC 发现事件之一。 */
    public boolean isNfcIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return false;
        }
        String action = intent.getAction();
        return NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)
                || NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)
                || NfcAdapter.ACTION_TAG_DISCOVERED.equals(action);
    }

    private boolean handleNfcIntent(Intent intent) {
        if (!isNfcIntent(intent)) {
            return false;
        }
        NfcPayload payload = readNfcPayloadUseCase.read(intent);
        if (listener != null) {
            String source = payload.getSource().isEmpty() ? DEFAULT_SOURCE : payload.getSource();
            listener.onPayload(new NfcPayload(payload.getUrl(), payload.getPackageName(), source));
        }
        return true;
    }

    private PendingIntent createForegroundDispatchIntent() {
        Intent intent = new Intent(activity, activity.getClass())
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12 起必须显式声明可变性，漏掉就读不到标签。
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        return PendingIntent.getActivity(activity, 0, intent, flags);
    }
}
