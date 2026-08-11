package com.itsonlybinary.miniboard;

import com.itsonlybinary.miniboard.protocol.Frame;
import com.itsonlybinary.miniboard.protocol.FrameError;

/**
 * Frame-level tap on the wire, for building a raw protocol log.
 *
 * <p>Every frame sent and received is reported, along with any bytes the decoder
 * rejected — a log that silently dropped CRC failures could not diagnose the
 * problems it exists for.
 *
 * <p>Use {@link Frame#getRawBytes()} for a hex dump of the exact on-wire bytes.
 *
 * <p>Both directions are delivered on the board's single callback thread, so the
 * log observes true wire order across transmit and receive.
 */
public interface RawFrameListener {

    /**
     * A frame was transmitted to the device.
     *
     * @param timestampNanos {@code System.nanoTime()} captured at the write call
     */
    void onTxFrame(Frame frame, long timestampNanos);

    /**
     * A frame was decoded from the device.
     *
     * @param timestampNanos {@code System.nanoTime()} captured when the containing
     *                       read returned; frames decoded from one read share it
     */
    void onRxFrame(Frame frame, long timestampNanos);

    /**
     * The decoder rejected bytes.
     *
     * @param discarded the exact bytes dropped
     */
    void onFrameError(byte[] discarded, FrameError reason, long timestampNanos);
}
