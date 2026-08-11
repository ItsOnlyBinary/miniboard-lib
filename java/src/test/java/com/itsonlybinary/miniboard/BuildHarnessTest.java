package com.itsonlybinary.miniboard;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the build itself: proves the JUnit 5 platform is wired up and that the
 * library is compiled to Java 8 bytecode. Replaced by real coverage in Task 2
 * onward, but kept — a silently unwired test task would make every later green
 * run meaningless.
 */
class BuildHarnessTest {

    @Test
    void junitPlatformIsWired() {
        assertEquals(4, 2 + 2);
    }

    @Test
    void libraryIsCompiledToJava8Bytecode() throws Exception {
        // Read the classfile header of a real library class rather than any runtime
        // property: java.specification.version reports the JDK executing the test
        // (25 here), so asserting on it would pass even if options.release were
        // deleted. Only the compiled bytes prove the release setting took effect.
        try (java.io.InputStream in = getClass().getClassLoader()
                .getResourceAsStream("com/itsonlybinary/miniboard/MiniBoard.class")) {
            // No silent skip. MiniBoard.class provably exists now, so a null here
            // means a rename or a broken classpath - and returning early would
            // make every assertion below vacuous exactly when it matters.
            assertNotNull(in, "MiniBoard.class is not on the test classpath");
            java.io.DataInputStream data = new java.io.DataInputStream(in);
            assertEquals(0xCAFEBABE, data.readInt(), "not a Java classfile");
            data.readUnsignedShort();                       // minor version
            int major = data.readUnsignedShort();
            assertEquals(52, major,
                    "expected Java 8 bytecode (major 52) but got major " + major
                            + " - options.release = 8 is not taking effect");
        }
    }

    /**
     * The engine's "no future is ever left unresolved" guarantee rests on one
     * unenforced invariant: every completion goes through
     * {@code completeOnCallbackThread}, which alone decides what thread runs the
     * dependent stages and alone survives a shut-down executor. Nothing in the
     * language enforces it, and the defect it prevents is invisible until a
     * consumer hangs, so it is pinned here structurally instead - by reading the
     * source, because bytecode inlines nothing that would distinguish the helper
     * from its callers.
     *
     * <p>An ArchUnit rule would say this more directly but costs a new pinned
     * dependency for one assertion, which this project does not take.
     */
    @Test
    void everyFutureCompletionRoutesThroughTheCallbackHelper() throws IOException {
        File source = new File(
                "src/main/java/com/itsonlybinary/miniboard/MiniBoardEngine.java");
        assertTrue(source.isFile(), "cannot find " + source.getAbsolutePath()
                + " - this test reads the engine source and must be run with the"
                + " miniboard-lib project directory as its working directory");

        String text = new String(Files.readAllBytes(source.toPath()),
                Charset.forName("UTF-8"));
        Matcher m = Pattern.compile("\\.complete(?:Exceptionally)?\\s*\\(").matcher(text);
        int raw = 0;
        while (m.find()) {
            raw++;
        }

        assertEquals(2, raw,
                "MiniBoardEngine must contain exactly two raw completions, both"
                        + " inside completeOnCallbackThread(), but found " + raw + "."
                        + " If you added one: route it through"
                        + " completeOnCallbackThread(future, value, error) instead."
                        + " Completing a future inline runs the consumer's dependent"
                        + " stages on whichever thread got there - the shared timeout"
                        + " scheduler, or the reader thread - and skips the"
                        + " already-shut-down-executor fallback that is the only"
                        + " reason a completion during teardown is never lost."
                        + " If you legitimately restructured the helper, update this"
                        + " count and say why in the commit message.");
    }
}
