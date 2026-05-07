package com.mcclaude.plugin.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcclaude.plugin.McClaudePlugin;
import com.mcclaude.plugin.WebSocketClient;
import org.bukkit.Bukkit;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class CommandHandler {

    private final McClaudePlugin plugin;
    private final ConsoleHandler consoleHandler;

    public CommandHandler(McClaudePlugin plugin, ConsoleHandler consoleHandler) {
        this.plugin = plugin;
        this.consoleHandler = consoleHandler;
    }

    public void handleCommand(String id, JsonObject data, WebSocketClient client) {
        String rawCommand = data.has("command") ? data.get("command").getAsString() : null;

        if (rawCommand == null || rawCommand.isEmpty()) {
            client.sendError(id, "No command provided");
            return;
        }

        String command = rawCommand;

        // Strip leading slash if present
        if (command.startsWith("/")) {
            command = command.substring(1);
        }

        // Check if command is blocked
        String baseCommand = command.split("\\s+")[0].toLowerCase();
        for (String blocked : plugin.getBlockedCommands()) {
            if (baseCommand.equals(blocked.toLowerCase())) {
                client.sendError(id, "Command '" + baseCommand + "' is blocked by McClaude configuration");
                return;
            }
        }

        final String finalCommand = command;

        // Capture console output before and after command execution
        // by using the console sender (which works with all command types)
        CompletableFuture<String> future = new CompletableFuture<>();

        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            // Snapshot the console log position before executing
            int logSizeBefore = consoleHandler.getLogSize();

            try {
                boolean result = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
                if (!result) {
                    future.complete("Unknown command or command failed: " + finalCommand);
                    return null;
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error executing command via McClaude: " + finalCommand, e);
                future.complete("Error: " + e.getMessage());
                return null;
            }

            // Give a tick for output to appear, then capture new log lines
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                List<String> newLines = consoleHandler.getLogsSince(logSizeBefore);
                StringBuilder sb = new StringBuilder();
                for (String line : newLines) {
                    sb.append(line).append("\n");
                }
                future.complete(sb.length() > 0 ? sb.toString().trim() : "Command executed successfully.");
            }, 1L);

            return null;
        });

        future.thenAccept(output -> {
            JsonObject result = new JsonObject();
            result.addProperty("command", finalCommand);
            JsonArray outputArray = new JsonArray();
            for (String line : output.split("\n")) {
                outputArray.add(line);
            }
            result.add("output", outputArray);
            result.addProperty("success", true);
            client.sendResult(id, result);
        }).exceptionally(throwable -> {
            client.sendError(id, "Failed to execute command: " + throwable.getMessage());
            return null;
        });
    }
}
