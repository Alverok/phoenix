package com.phoenix.tui;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import com.phoenix.core.DeviceRegistry;
import com.phoenix.dispatch.DispatchResult;
import com.phoenix.dispatch.TaskAdmission;
import com.phoenix.dispatch.TaskDispatcher;
import com.phoenix.model.NodeRecord;
import com.phoenix.model.NodeStatus;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.net.RegistrationServer;

@Singleton
public class PhoenixTui {
    private static final int HEARTBEAT_WINDOW_MS = 20_000;
    private final DeviceRegistry registry;
    private final RegistrationServer registrationServer;
    private final TaskAdmission taskAdmission;
    private final TaskJournal taskJournal;
    private final PhoenixEventLog eventLog;
    private final AtomicInteger taskCounter = new AtomicInteger(1);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private int eventScrollOffset = 0;

    @Inject
    public PhoenixTui(DeviceRegistry registry, RegistrationServer registrationServer, TaskAdmission taskAdmission, TaskJournal taskJournal, PhoenixEventLog eventLog) {
        this.registry = registry;
        this.registrationServer = registrationServer;
        this.taskAdmission = taskAdmission;
        this.taskJournal = taskJournal;
        this.eventLog = eventLog;
    }

    public void run() throws Exception {
        DefaultTerminalFactory factory = new DefaultTerminalFactory().setForceTextTerminal(true);
        Terminal terminal;
        try {
            terminal = factory.createTerminal();
        } catch (IOException e) {
            terminal = factory.setPreferTerminalEmulator(true).createTerminal();
        }
        Screen screen = new TerminalScreen(terminal);
        screen.startScreen();
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        try {
            System.setOut(PhoenixOutputCapture.createPrintStream(eventLog));
            System.setErr(PhoenixOutputCapture.createPrintStream(eventLog));
            System.out.println("[TUI] Phoenix dashboard started");
            while (running.get()) {
                handleInput(screen);
                render(screen);
                Thread.sleep(150);
            }
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
            screen.stopScreen();
        }
    }

    private void handleInput(Screen screen) throws Exception {
        KeyStroke key;
        while ((key = screen.pollInput()) != null) {
            if (key.getKeyType() == KeyType.Escape) {
                running.set(false);
                return;
            }
            switch (key.getKeyType()) {
                case ArrowUp -> {
                    eventScrollOffset++;
                    continue;
                }
                case ArrowDown -> {
                    eventScrollOffset = Math.max(0, eventScrollOffset - 1);
                    continue;
                }
                case PageUp -> {
                    eventScrollOffset += 10;
                    continue;
                }
                case PageDown -> {
                    eventScrollOffset = Math.max(0, eventScrollOffset - 10);
                    continue;
                }
                default -> {
                }
            }
            if (key.getKeyType() != KeyType.Character) continue;
            char c = Character.toLowerCase(key.getCharacter());
            switch (c) {
                case 'q' -> running.set(false);
                case 'd' -> submitTask();
                case 'c' -> eventLog.clear();
                case 'g' -> eventScrollOffset = 0;
                case 'h' -> eventLog.append("Commands: D=dispatch C=clear G=latest " + "Up/Down=scroll PgUp/PgDn=page Q=quit");
                default -> {
                }
            }
        }
    }

    private void submitTask() {
        int number = taskCounter.getAndIncrement();
        TaskDescriptor task = new TaskDescriptor(String.format("task-%03d", number), "Monte-Carlo-Pi", 2, 512);
        taskAdmission.submitFresh(task).whenComplete((outcome, error) -> {
            if (error != null) {
                eventLog.append("[TUI] " + task.id() + " failed: " + error.getMessage());
                return;
            }
            if (outcome instanceof TaskDispatcher.Outcome.Completed completed) {
                DispatchResult result = completed.result();
                eventLog.append("[TUI] " + task.id() + " -> " + result.result().status() + " on " + result.nodeDisplayName() + " | output=" + result.result().outputData());
            } else {
                eventLog.append("[TUI] " + task.id() + " -> WAITING for an eligible node");
            }
        });
    }

    private void render(Screen screen) throws Exception {
        screen.clear();
        TextGraphics g = screen.newTextGraphics();
        TerminalSize size = screen.getTerminalSize();
        int width = size.getColumns();
        int height = size.getRows();
        drawHeader(g, width);
        int nodesHeight = Math.max(8, height / 3);
        drawNodes(g, width, nodesHeight);
        drawTasks(g, 1, nodesHeight + 1, width / 2 - 2, height - nodesHeight - 6);
        drawEvents(g, width / 2, nodesHeight + 1, width / 2 - 2, height - nodesHeight - 6);
        drawFooter(g, width, height);
        screen.refresh();
    }

    private void drawHeader(TextGraphics g, int width) {
        g.setBackgroundColor(TextColor.ANSI.BLACK);
        g.setForegroundColor(TextColor.ANSI.CYAN);
        g.putString(2, 0, "PHOENIX CORE");
        g.setForegroundColor(TextColor.ANSI.WHITE);
        g.putString(17, 0, "| Heterogeneous Edge Orchestrator");
        String coreAddress = System.getProperty("phoenix.advertiseAddress", "127.0.0.1");
        String addressText = "| Core: " + coreAddress;
        int addressX = 52;
        if (addressX + addressText.length() < width - 15) {
            g.putString(addressX, 0, addressText);
        }
        g.setForegroundColor(TextColor.ANSI.GREEN);
        g.putString(Math.max(0, width - 14), 0, "● RUNNING");
    }

    private void drawNodes(TextGraphics g, int width, int height) {
        box(g, 0, 1, width - 1, height);
        g.setForegroundColor(TextColor.ANSI.CYAN);
        g.putString(2, 2, "NODES");
        int y = 4;
        List < NodeRecord > nodes = registry.list();
        for (NodeRecord node: nodes) {
            if (y >= height - 1) {
                break;
            }
            drawNode(g, node, y, width);
            y += 2;
        }
    }

    private void drawNode(TextGraphics g, NodeRecord node, int y, int width) {
        long age = System.currentTimeMillis() - node.lastHeartbeat().toEpochMilli();
        double freshness = Math.max(0.0, Math.min(1.0, 1.0 -((double) age / HEARTBEAT_WINDOW_MS)));
        TextColor color = switch (node.state()) {
            case ONLINE -> TextColor.ANSI.GREEN;
            case DEGRADED -> TextColor.ANSI.YELLOW;
            case OFFLINE -> TextColor.ANSI.RED;
            default -> TextColor.ANSI.WHITE;
        };
        g.setForegroundColor(color);
        g.putString(2, y, truncate(node.info().displayName(), 20));
        g.setForegroundColor(TextColor.ANSI.WHITE);
        g.putString(23, y, String.format("%-8s", node.info().hardwareClass()));
        String cpuText = node.status().cpuUsagePercent() < 0 ? "CPU   N/A": String.format("CPU %5.1f%%", node.status().cpuUsagePercent());
        g.putString(32, y, cpuText);
        g.putString(46, y, String.format("RAM %6dMB", node.status().availableRamMb()));
        g.putString(60, y, String.format("RTT %5.1fms", node.status().networkLatencyMs()));
        int barStart = 73;
        int barWidth = Math.max(10, width - barStart - 14);
        drawBar(g, barStart, y, barWidth, freshness, color);
        g.setForegroundColor(color);
        String heartbeatText;
        if (node.state() == NodeStatus.DEGRADED) {
            heartbeatText = "DEGRADED";
        } else if (node.state() == NodeStatus.OFFLINE) {
            heartbeatText = "OFFLINE";
        } else {
            heartbeatText = String.format("%4.1fs", age / 1000.0);
        }
        g.putString(barStart + barWidth + 2, y, heartbeatText);
    }

    private void drawTasks(TextGraphics g, int x, int y, int width, int height) {
        box(g, x, y, width, height);
        g.setForegroundColor(TextColor.ANSI.CYAN);
        g.putString(x + 2, y + 1, "TASKS");
        int row = y + 3;
        for (TaskJournal.TaskState task: taskJournal.recent(8)) {
            if (row >= y + height - 1) break;
            TextColor statusColor = switch (task.status()) {
                case "SUCCESS" -> TextColor.ANSI.GREEN;
                case "FAILED" -> TextColor.ANSI.RED;
                case "WAITING", "RETRYING" -> TextColor.ANSI.YELLOW;
                case "RUNNING" -> TextColor.ANSI.CYAN;
                default -> TextColor.ANSI.WHITE;
            };
            String displayStatus = "RETRYING".equals(task.status()) ? "RETRY " + task.attempt(): task.status();
            g.setForegroundColor(statusColor);
            g.putString(x + 2, row, String.format("%-9s %-10s", task.taskId(), displayStatus));
            g.setForegroundColor(TextColor.ANSI.WHITE);
            g.putString(x + 24, row, truncate(task.nodeName() == null ? "-": task.nodeName(), 18));
            g.putString(x + 44, row, String.format("%5dms", task.elapsedMs()));
            row++;
        }
    }

    private void drawEvents(TextGraphics g, int x, int y, int width, int height) {
        box(g, x, y, width, height);
        g.setForegroundColor(TextColor.ANSI.CYAN);
        g.putString(x + 2, y + 1, "EVENTS");
        int visibleRows = Math.max(1, height - 4);
        int innerWidth = Math.max(12, width - 4);
        List < String > wrapped = new ArrayList < >();
        for (String event: eventLog.snapshot()) {
            wrapped.addAll(PhoenixEventLog.wrap(event, innerWidth));
        }
        int maxOffset = Math.max(0, wrapped.size() - visibleRows);
        eventScrollOffset = Math.min(eventScrollOffset, maxOffset);
        int offset = eventScrollOffset;
        int end = wrapped.size() - offset;
        int start = Math.max(0, end - visibleRows);
        int row = y + 3;
        for (String line: wrapped.subList(start, end)) {
            if (row >= y + height - 1) break;
            TextColor color = line.contains("FAILED") || line.contains("WAITING") ? TextColor.ANSI.RED: line.contains("DEGRADED") || line.contains("OFFLINE") || line.contains("Recovery") || line.contains("RETRY") ? TextColor.ANSI.YELLOW: line.contains("SUCCESS") ? TextColor.ANSI.GREEN: TextColor.ANSI.WHITE;
            g.setForegroundColor(color);
            g.putString(x + 2, row, line);
            row++;
        }
        g.setForegroundColor(TextColor.ANSI.CYAN);
        String indicator = offset > 0 ? "↑" + offset + " [G]=live": "[G]=live";
        g.putString(Math.max(x + 2, x + width - indicator.length() - 2), y + 1, indicator);
    }

    private void drawFooter(TextGraphics g, int width, int height) {
        g.setForegroundColor(TextColor.ANSI.CYAN);
        g.putString(2, height - 2, "[D] Dispatch");
        g.setForegroundColor(TextColor.ANSI.WHITE);
        g.putString(18, height - 2, "[C] Clear");
        g.putString(29, height - 2, "[H] Help");
        g.putString(40, height - 2, "[↑↓/PgUp/PgDn] Scroll");
        g.putString(64, height - 2, "[G] Live");
        g.putString(74, height - 2, "[Q] Quit");
    }

    private static void drawBar(TextGraphics g, int x, int y, int width, double fraction, TextColor color) {
        int filled =(int) Math.round(width * fraction);
        g.setForegroundColor(color);
        for (int i = 0;i < width;i++) {
            g.putString(x + i, y, i < filled ? "█": "░");
        }
    }

    private static void box(TextGraphics g, int x, int y, int width, int height) {
        g.setForegroundColor(TextColor.ANSI.WHITE);
        g.putString(x, y, "┌");
        g.putString(x + width, y, "┐");
        g.putString(x, y + height, "└");
        g.putString(x + width, y + height, "┘");
        for (int i = 1;i < width;i++) {
            g.putString(x + i, y, "─");
            g.putString(x + i, y + height, "─");
        }
        for (int i = 1;i < height;i++) {
            g.putString(x, y + i, "│");
            g.putString(x + width, y + i, "│");
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 1)) + "…";
    }

}
