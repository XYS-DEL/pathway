package com.acooldog.nfc;

/** 模拟发送 NFC 的结果。 */
public final class NfcPayloadDispatchResult {
    public enum Status {
        /** 成功发出 {@code ACTION_NDEF_DISCOVERED}，目标应用按 NDEF 处理了它。 */
        NDEF_SENT,
        /** 目标应用不接受 NDEF，已退化为普通 {@code ACTION_VIEW} 打开。 */
        FALLBACK_VIEW_SENT,
        /** 前两种都没成。 */
        FAILED
    }

    private final Status status;
    private final String detail;

    private NfcPayloadDispatchResult(Status status, String detail) {
        this.status = status;
        this.detail = detail == null ? "" : detail;
    }

    public static NfcPayloadDispatchResult ndefSent() {
        return new NfcPayloadDispatchResult(Status.NDEF_SENT, "");
    }

    public static NfcPayloadDispatchResult fallbackViewSent() {
        return new NfcPayloadDispatchResult(Status.FALLBACK_VIEW_SENT, "");
    }

    public static NfcPayloadDispatchResult failed(String detail) {
        return new NfcPayloadDispatchResult(Status.FAILED, detail);
    }

    public Status getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }

    public boolean isSuccessful() {
        return status != Status.FAILED;
    }
}
