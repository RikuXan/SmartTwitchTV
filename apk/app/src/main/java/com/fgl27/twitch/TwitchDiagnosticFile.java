package com.fgl27.twitch;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

final class TwitchDiagnosticFile implements AutoCloseable {
    private final File directory;
    private final String session;
    private final long maxBytes;
    private final int maxFiles;
    private int part;
    private long bytes;
    private BufferedOutputStream output;

    TwitchDiagnosticFile(File directory, String session, long maxBytes, int maxFiles) {
        this.directory = directory;
        this.session = session;
        this.maxBytes = maxBytes;
        this.maxFiles = maxFiles;
    }

    void append(String line) throws IOException {
        byte[] encoded = (line + "\n").getBytes(StandardCharsets.UTF_8);
        if (encoded.length > maxBytes) throw new IOException("Diagnostic record exceeds file limit");
        if (output == null || bytes + encoded.length > maxBytes) rotate();
        output.write(encoded);
        bytes += encoded.length;
    }

    void flush() throws IOException {
        if (output != null) output.flush();
    }

    private void rotate() throws IOException {
        close();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create diagnostic directory");
        File[] files = directory.listFiles((dir, name) -> name.startsWith("twitchll-") && name.endsWith(".log"));
        if (files == null) throw new IOException("Cannot list diagnostic directory");
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
        for (int i = 0; i <= files.length - maxFiles; i++) {
            if (!files[i].delete()) throw new IOException("Cannot prune diagnostic file");
        }
        File file;
        do {
            file = new File(directory, "twitchll-" + session + "-" + (++part) + ".log");
        } while (!file.createNewFile());
        output = new BufferedOutputStream(new FileOutputStream(file), 32768);
        bytes = 0;
    }

    @Override
    public void close() throws IOException {
        if (output != null) {
            BufferedOutputStream previous = output;
            output = null;
            previous.close();
        }
    }
}
