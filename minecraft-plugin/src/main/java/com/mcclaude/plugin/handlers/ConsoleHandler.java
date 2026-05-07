package com.mcclaude.plugin.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcclaude.plugin.McClaudePlugin;
import com.mcclaude.plugin.WebSocketClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class ConsoleHandler {

    private static final int BUFFER_SIZE = 500;
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final McClaudePlugin plugin;
    private final String[] circularBuffer;
    private int bufferIndex;
    private int bufferCount;
    private McClaudeAppender appender;
    private boolean running;

    public ConsoleHandler(McClaudePlugin plugin, WebSocketClient client) {
        this.plugin = plugin;
        this.circularBuffer = new String[BUFFER_SIZE];
        this.bufferIndex = 0;
        this.bufferCount = 0;
        this.running = false;
    }

    public void start() {
        if (running) return;
        running = true;

        appender = new McClaudeAppender();
        appender.start();

        Logger rootLogger = (Logger) LogManager.getRootLogger();
        rootLogger.addAppender(appender);

        plugin.getLogger().info("Console capture started.");
    }

    public void shutdown() {
        if (!running) return;
        running = false;

        if (appender != null) {
            Logger rootLogger = (Logger) LogManager.getRootLogger();
            rootLogger.removeAppender(appender);
            appender.stop();
            appender = null;
        }
    }

    public void handleConsoleRead(String id, JsonObject data, WebSocketClient client) {
        int lines = data.has("lines") ? data.get("lines").getAsInt() : 50;
        String filter = data.has("filter") ? data.get("filter").getAsString() : null;

        lines = Math.min(lines, BUFFER_SIZE);
        lines = Math.min(lines, bufferCount);

        List<String> result = new ArrayList<>();

        synchronized (circularBuffer) {
            int startIndex = (bufferIndex - lines + BUFFER_SIZE) % BUFFER_SIZE;
            for (int i = 0; i < lines; i++) {
                String line = circularBuffer[(startIndex + i) % BUFFER_SIZE];
                if (line != null) {
                    if (filter == null || filter.isEmpty() || line.toLowerCase().contains(filter.toLowerCase())) {
                        result.add(line);
                    }
                }
            }
        }

        JsonObject response = new JsonObject();
        JsonArray linesArray = new JsonArray();
        for (String line : result) {
            linesArray.add(line);
        }
        response.add("lines", linesArray);
        response.addProperty("total", bufferCount);
        client.sendResult(id, response);
    }

    public int getLogSize() {
        synchronized (circularBuffer) {
            return bufferCount;
        }
    }

    public List<String> getLogsSince(int previousSize) {
        synchronized (circularBuffer) {
            int newLines = bufferCount - previousSize;
            if (newLines <= 0) return new ArrayList<>();
            newLines = Math.min(newLines, BUFFER_SIZE);

            List<String> result = new ArrayList<>();
            int startIndex = (bufferIndex - newLines + BUFFER_SIZE) % BUFFER_SIZE;
            for (int i = 0; i < newLines; i++) {
                String line = circularBuffer[(startIndex + i) % BUFFER_SIZE];
                if (line != null) {
                    result.add(line);
                }
            }
            return result;
        }
    }

    private void addLine(String line) {
        synchronized (circularBuffer) {
            circularBuffer[bufferIndex] = line;
            bufferIndex = (bufferIndex + 1) % BUFFER_SIZE;
            if (bufferCount < BUFFER_SIZE) {
                bufferCount++;
            }
        }
    }

    private class McClaudeAppender extends AbstractAppender {

        protected McClaudeAppender() {
            super("McClaudeConsoleAppender", null,
                    PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            if (!running) return;

            try {
                String timestamp = TIME_FORMATTER.format(
                        Instant.ofEpochMilli(event.getTimeMillis()));
                String level = event.getLevel().name();
                String loggerName = event.getLoggerName();
                String message = event.getMessage().getFormattedMessage();

                if (loggerName != null && loggerName.contains(".")) {
                    loggerName = loggerName.substring(loggerName.lastIndexOf('.') + 1);
                }

                String formattedLine = String.format("[%s %s] [%s]: %s",
                        timestamp, level, loggerName != null ? loggerName : "Server", message);

                addLine(formattedLine);

                if (event.getThrown() != null) {
                    addLine(event.getThrown().toString());
                }
            } catch (Exception e) {
                // Don't let logging errors crash anything
            }
        }
    }
}
