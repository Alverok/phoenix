package com.phoenix.tui;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public final class PhoenixOutputCapture
        extends OutputStream {

    private final PhoenixEventLog eventLog;

    private final StringBuilder currentLine =
            new StringBuilder();

    public PhoenixOutputCapture(
            PhoenixEventLog eventLog) {

        this.eventLog = eventLog;
    }

    @Override
    public synchronized void write(int b)
            throws IOException {

        write(
                new byte[]{(byte) b},
                0,
                1
        );
    }

    @Override
    public synchronized void write(
            byte[] bytes,
            int offset,
            int length) {

        String text =
                new String(
                        bytes,
                        offset,
                        length,
                        StandardCharsets.UTF_8
                );

        for (int i = 0; i < text.length(); i++) {

            char c = text.charAt(i);

            if (c == '\n') {
                flushLine();
                continue;
            }

            if (c != '\r') {
                currentLine.append(c);
            }
        }
    }

    @Override
    public synchronized void flush() {

        if (!currentLine.isEmpty()) {
            flushLine();
        }
    }

    private void flushLine() {

        String line =
                currentLine.toString().strip();

        if (!line.isEmpty()) {
            eventLog.append(line);
        }

        currentLine.setLength(0);
    }

    public static PrintStream createPrintStream(
            PhoenixEventLog eventLog) {

        return new PrintStream(
                new PhoenixOutputCapture(eventLog),
                true,
                StandardCharsets.UTF_8
        );
    }
}
