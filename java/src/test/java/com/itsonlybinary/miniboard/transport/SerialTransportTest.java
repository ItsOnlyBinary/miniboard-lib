package com.itsonlybinary.miniboard.transport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * This is an environment smoke check, not a behavioural test: its result
 * depends on what serial hardware happens to be attached to the machine
 * running it, so it is explicitly exempt from the "tests must assert real
 * behaviour" rule. It asserts only what is machine-independent and always
 * true, regardless of hardware.
 *
 * <p>The real thing this test guards against is jna 3.5.1 + purejavacomm
 * 0.0.29 failing to load their native layer on the running JDK (an
 * {@code UnsatisfiedLinkError} or a JNA version error at class-load time).
 * {@link SerialTransport#listPortNames()} never opens a port, so running it
 * here cannot assert DTR or disturb any attached device.
 */
class SerialTransportTest {

    @Test
    void listPortNamesNeverReturnsNullAndNeverThrows() {
        List<String> ports = assertDoesNotThrow(new org.junit.jupiter.api.function.ThrowingSupplier<List<String>>() {
            @Override
            public List<String> get() {
                return SerialTransport.listPortNames();
            }
        });

        assertNotNull(ports, "listPortNames() must never return null, even with no hardware attached");
    }

    @Test
    void listPortNamesIsSortedAndConsistentAcrossConsecutiveCalls() {
        List<String> first = SerialTransport.listPortNames();
        List<String> second = SerialTransport.listPortNames();

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first, second, "consecutive calls should observe the same stable port list");

        List<String> sorted = new ArrayList<String>(first);
        Collections.sort(sorted);
        assertEquals(sorted, first, "returned list must be sorted");
    }

    /**
     * Unlike the two tests above, this is a real behavioural assertion, not a
     * smoke check: a never-opened transport must fast-fail on read/write rather
     * than attempting real I/O, regardless of what hardware is attached.
     */
    @Test
    void neverOpenedTransportFastFailsInsteadOfTouchingIo() {
        SerialTransport transport = new SerialTransport();

        assertFalse(transport.isOpen());
        assertNull(transport.getPortName());

        IOException readEx = assertThrows(IOException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() throws IOException {
                transport.read(new byte[16]);
            }
        });
        assertEquals("Port is not open", readEx.getMessage());

        IOException writeEx = assertThrows(IOException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() throws IOException {
                transport.write(new byte[] {0});
            }
        });
        assertEquals("Port is not open", writeEx.getMessage());
    }
}
