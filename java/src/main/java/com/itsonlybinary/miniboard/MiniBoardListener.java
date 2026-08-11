package com.itsonlybinary.miniboard;

/**
 * Unsolicited device events. All methods are invoked on the board's single
 * callback thread, so they are never concurrent with one another, but a slow
 * implementation delays every later callback. Do heavy work elsewhere.
 *
 * <p>Exceptions thrown by these methods are routed to {@link #onError} rather
 * than killing the callback thread.
 *
 * <p>See {@link MiniBoardAdapter} to override only what you need.
 */
public interface MiniBoardListener {

    /**
     * A key was pressed.
     *
     * @param keyIndex 0 to 53
     * @param keyId    the pressed key's opaque per-key identifier, 0-255, as
     *                 currently assigned to that slot. Never sent over HID.
     */
    void onKey(int keyIndex, int keyId);

    /** The typo-rejection logic blocked a keystroke. */
    void onTypoRejected();

    /**
     * An LED animation completed. Never fires for BLINK or PULSE configured with
     * {@code iterations = 0}, which loop forever.
     *
     * @param ledIndex 0 to 3, or <strong>-1</strong> when the event carried no
     *                 payload. {@code mb_cdc_interface.md} contradicts itself on
     *                 LED_FINISH's length — the summary table says LEN 0, §8
     *                 documents a 1-byte index — so a zero-length event reports
     *                 -1 rather than a fabricated index. Guard for it: indexing
     *                 an array by this value would throw from inside your
     *                 listener and reach {@link #onError} as a mystery.
     */
    void onLedFinish(int ledIndex);

    /** A text message from the firmware, typically debug output. */
    void onMessage(String text);

    /**
     * The active USB connector changed. Not fired for the SIDE event received
     * during the handshake — that one populates {@link DeviceInfo#getSide()}.
     */
    void onSideChanged(Side side);

    /**
     * The connection ended.
     *
     * @param cause null for a clean {@link MiniBoard#close()} or an expected
     *              post-REBOOT drop; otherwise the failure that ended it
     */
    void onDisconnected(Throwable cause);

    /** A non-fatal error: a listener threw, or an unmatched frame arrived. */
    void onError(Throwable error);
}
