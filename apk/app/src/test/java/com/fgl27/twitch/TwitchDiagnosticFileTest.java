package com.fgl27.twitch;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TwitchDiagnosticFileTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void rotatesBetweenRecordsAndBoundsRetainedBytes() throws Exception {
        File directory = temporary.newFolder();
        try (TwitchDiagnosticFile log = new TwitchDiagnosticFile(directory, "session", 12, 3)) {
            for (int i = 0; i < 30; i++) log.append("event" + i);
            log.flush();
            File[] files = directory.listFiles();
            assertEquals(3, files.length);
            for (File file : files) {
                assertTrue(file.length() <= 12);
                String text = read(file);
                assertTrue(text.endsWith("\n"));
                assertTrue(text.matches("(event\\d+\\n)+"));
            }
            assertTrue(Arrays.stream(files).anyMatch(file -> {
                try { return read(file).contains("event29\n"); } catch (IOException error) { throw new AssertionError(error); }
            }));
        }
    }

    @Test
    public void flushExposesRecordsAndRotationCountsUtf8Bytes() throws Exception {
        File directory = temporary.newFolder();
        try (TwitchDiagnosticFile log = new TwitchDiagnosticFile(directory, "utf8", 9, 3)) {
            log.append("éé");
            log.flush();
            assertEquals("éé\n", read(directory.listFiles()[0]));
            log.append("éé");
            log.flush();
            assertEquals(2, directory.listFiles().length);
            for (File file : directory.listFiles()) assertEquals(5, file.length());
        }
    }

    @Test
    public void reopeningDoesNotOverwriteAndPrunesOnlyOwnedFiles() throws Exception {
        File directory = temporary.newFolder();
        File unrelated = new File(directory, "keep.txt");
        Files.write(unrelated.toPath(), new byte[] {1});
        try (TwitchDiagnosticFile log = new TwitchDiagnosticFile(directory, "same", 20, 2)) {
            log.append("first");
        }
        try (TwitchDiagnosticFile log = new TwitchDiagnosticFile(directory, "same", 20, 2)) {
            log.append("second");
        }
        assertEquals("first\n", read(new File(directory, "twitchll-same-1.log")));
        try (TwitchDiagnosticFile log = new TwitchDiagnosticFile(directory, "next", 20, 2)) {
            log.append("third");
        }
        assertTrue(unrelated.exists());
        assertEquals(3, directory.listFiles().length);
        assertEquals("third\n", read(new File(directory, "twitchll-next-1.log")));
    }

    @Test
    public void unavailableDirectoryCanRecoverWithoutOverwriting() throws Exception {
        File directory = temporary.newFile();
        try (TwitchDiagnosticFile log = new TwitchDiagnosticFile(directory, "recover", 20, 2)) {
            try {
                log.append("unavailable");
                fail("Expected storage failure");
            } catch (IOException expected) {}
            assertTrue(directory.delete());
            log.append("recovered");
            log.flush();
            assertEquals("recovered\n", read(directory.listFiles()[0]));
        }
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
