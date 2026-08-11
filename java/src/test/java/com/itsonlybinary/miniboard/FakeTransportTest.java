package com.itsonlybinary.miniboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@link FakeTransport} hooks the reconnect tests depend on. A test
 * double that silently misbehaves turns every failure above it into a false
 * negative, so its own contract is tested rather than assumed.
 */
class FakeTransportTest {

    @Test
    @Timeout(10)
    void closeThenOpenReopensAndCountsBothOpens() throws Exception {
        FakeTransport transport = new FakeTransport();

        transport.open("TEST-A");
        assertTrue(transport.isOpen(), "first open must leave the port open");
        assertEquals(1, transport.getOpenCount());

        transport.close();
        assertFalse(transport.isOpen(), "close must leave the port shut");

        transport.open("TEST-B");
        assertTrue(transport.isOpen(), "the double must be reopenable, as SerialTransport is");
        assertEquals(2, transport.getOpenCount());
        assertEquals("TEST-B", transport.getPortName(), "reopen must adopt the new port name");
        assertTrue(transport.awaitOpenCount(2, 1, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(10)
    void failNextOpenIsConsumedByExactlyOneOpen() throws Exception {
        final FakeTransport transport = new FakeTransport();
        transport.failNextOpen(new IOException("simulated"));

        assertThrows(IOException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() throws Throwable {
                transport.open("TEST-A");
            }
        }, "the armed failure must be thrown");
        assertEquals(0, transport.getOpenCount(), "a failed open must not count as an open");

        // Directly, not wrapped in assertThrows: a catch-anything wrapper would
        // accept the very IOException a still-sticky failNextOpen would throw, so
        // the assertion that looked like it was doing the work could never fail.
        transport.open("TEST-A");
        assertTrue(transport.isOpen(),
                "the retry after a consumed failure must succeed");
        assertEquals(1, transport.getOpenCount(), "failNextOpen is one-shot");
    }

    @Test
    @Timeout(10)
    void blockedReadsParkUntilReleased() throws Exception {
        final FakeTransport transport = new FakeTransport();
        CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        transport.blockReads(entered, release);
        transport.open("TEST-A");

        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    transport.read(new byte[16]);
                } catch (Throwable t) {
                    failure.set(t);
                }
            }
        }, "fixture-reader");
        reader.setDaemon(true);
        reader.start();

        assertTrue(entered.await(2, TimeUnit.SECONDS), "read() was never entered");
        reader.join(300);
        assertTrue(reader.isAlive(), "a blocked read must not return on its own");

        release.countDown();
        reader.join(2000);
        assertFalse(reader.isAlive(), "releasing the latch must let read() return");
        assertEquals(null, failure.get(), "the released read must not throw");
    }

    @Test
    @Timeout(10)
    void queueHelloAcceptsACallerChosenSerial() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.open("TEST-A");
        transport.queueHello(new byte[] {(byte) 0xAB, (byte) 0xCD});

        byte[] buffer = new byte[512];
        int count = transport.read(buffer);
        assertTrue(count > 0, "the hello chunk must be readable");

        // The SERIAL event is 0x03; find it and check the payload that follows.
        boolean found = false;
        for (int i = 0; i + 4 < count; i++) {
            if ((buffer[i] & 0xFF) == 0xAA && (buffer[i + 1] & 0xFF) == 0x03
                    && (buffer[i + 2] & 0xFF) == 2) {
                assertEquals((byte) 0xAB, buffer[i + 3]);
                assertEquals((byte) 0xCD, buffer[i + 4]);
                found = true;
                break;
            }
        }
        assertTrue(found, "no SERIAL event carrying the requested bytes was emitted");
    }
}
