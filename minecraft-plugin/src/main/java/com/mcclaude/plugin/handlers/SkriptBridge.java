package com.mcclaude.plugin.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcclaude.plugin.McClaudePlugin;
import com.mcclaude.plugin.WebSocketClient;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Bridge to Skript's internal API via reflection.
 * Tries to access Effect.parse / TriggerItem.walk at startup.
 * Falls back to Bukkit.dispatchCommand("sk effect ...") if reflection fails.
 */
public class SkriptBridge {

    private final McClaudePlugin plugin;
    private final ConsoleHandler consoleHandler;

    private boolean skriptAvailable = false;
    private boolean reflectionAvailable = false;

    // Reflected classes and methods (null if reflection failed)
    private Class<?> effectClass;
    private Class<?> triggerItemClass;
    private Class<?> effectCommandEventClass;
    private Class<?> parserInstanceClass;
    private Class<?> skriptLoggerClass;
    private Class<?> retainingLogHandlerClass;
    private Class<?> variablesClass;

    private Method effectParse;
    private Method triggerItemWalk;
    private Method parserGetInstance;
    private Method parserSetCurrentEvent;
    private Method parserDeleteCurrentEvent;
    private Method loggerStartRetaining;
    private Method logHandlerClear;
    private Method logHandlerPrintLog;
    private Method logHandlerStop;
    private Method variablesRemoveLocals;
    private Constructor<?> effectCommandEventConstructor;

    public SkriptBridge(McClaudePlugin plugin, ConsoleHandler consoleHandler) {
        this.plugin = plugin;
        this.consoleHandler = consoleHandler;
    }

    /**
     * Try to detect Skript and access its internals via reflection.
     * Call this on plugin enable, AFTER Skript has loaded.
     */
    public void init() {
        Plugin skript = Bukkit.getPluginManager().getPlugin("Skript");
        if (skript == null || !skript.isEnabled()) {
            plugin.getLogger().info("Skript not found — skript_eval disabled.");
            return;
        }

        skriptAvailable = true;
        plugin.getLogger().info("Skript detected: " + skript.getDescription().getVersion());

        // Try reflection
        try {
            ClassLoader cl = skript.getClass().getClassLoader();

            effectClass = cl.loadClass("ch.njol.skript.lang.Effect");
            triggerItemClass = cl.loadClass("ch.njol.skript.lang.TriggerItem");
            effectCommandEventClass = cl.loadClass("ch.njol.skript.command.EffectCommandEvent");
            parserInstanceClass = cl.loadClass("ch.njol.skript.lang.parser.ParserInstance");
            skriptLoggerClass = cl.loadClass("ch.njol.skript.log.SkriptLogger");
            retainingLogHandlerClass = cl.loadClass("ch.njol.skript.log.RetainingLogHandler");
            variablesClass = cl.loadClass("ch.njol.skript.variables.Variables");

            effectParse = effectClass.getMethod("parse", String.class, String.class);
            triggerItemWalk = triggerItemClass.getMethod("walk", triggerItemClass, org.bukkit.event.Event.class);
            parserGetInstance = parserInstanceClass.getMethod("get");
            parserSetCurrentEvent = parserInstanceClass.getMethod("setCurrentEvent", String.class, Class[].class);
            parserDeleteCurrentEvent = parserInstanceClass.getMethod("deleteCurrentEvent");
            loggerStartRetaining = skriptLoggerClass.getMethod("startRetainingLog");
            logHandlerClear = retainingLogHandlerClass.getMethod("clear");
            logHandlerPrintLog = retainingLogHandlerClass.getMethod("printLog");
            logHandlerStop = retainingLogHandlerClass.getMethod("stop");
            variablesRemoveLocals = variablesClass.getMethod("removeLocals", org.bukkit.event.Event.class);

            effectCommandEventConstructor = effectCommandEventClass.getConstructor(
                    org.bukkit.command.CommandSender.class, String.class
            );

            reflectionAvailable = true;
            plugin.getLogger().info("Skript reflection bridge initialized — direct eval available.");

        } catch (Exception e) {
            reflectionAvailable = false;
            plugin.getLogger().info("Skript reflection failed (" + e.getMessage() + ") — using dispatchCommand fallback.");
        }
    }

    public boolean isAvailable() {
        return skriptAvailable;
    }

    public void handleSkriptEval(String id, JsonObject data, WebSocketClient client) {
        if (!skriptAvailable) {
            client.sendError(id, "Skript is not installed on this server");
            return;
        }

        // Batch mode: codes array
        if (data.has("codes") && data.get("codes").isJsonArray()) {
            JsonArray codesArr = data.getAsJsonArray("codes");
            List<String> codes = new ArrayList<>();
            for (int i = 0; i < codesArr.size(); i++) {
                codes.add(codesArr.get(i).getAsString());
            }
            if (codes.isEmpty()) {
                client.sendError(id, "Empty codes array");
                return;
            }
            if (reflectionAvailable) {
                evalBatchViaReflection(id, codes, client);
            } else {
                evalBatchViaDispatch(id, codes, client);
            }
            return;
        }

        // Single mode: code string
        String code = data.has("code") ? data.get("code").getAsString() : null;
        if (code == null || code.isEmpty()) {
            client.sendError(id, "No code provided");
            return;
        }

        if (reflectionAvailable) {
            evalViaReflection(id, code, client);
        } else {
            evalViaDispatch(id, code, client);
        }
    }

    private void evalViaReflection(String id, String code, WebSocketClient client) {
        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            try {
                // Custom sender that captures all output
                CaptureSender sender = new CaptureSender();

                // Start retaining log
                Object logHandler = loggerStartRetaining.invoke(null);

                try {
                    // Create event with our capturing sender
                    Object event = effectCommandEventConstructor.newInstance(sender, code);

                    // Set parser context
                    Object parser = parserGetInstance.invoke(null);
                    parserSetCurrentEvent.invoke(parser, "effect command",
                            new Class[]{effectCommandEventClass});

                    // Parse the effect
                    Object effect = effectParse.invoke(null, code, (String) null);

                    parserDeleteCurrentEvent.invoke(parser);

                    JsonObject result = new JsonObject();
                    result.addProperty("code", code);
                    result.addProperty("method", "reflection");

                    if (effect != null) {
                        logHandlerClear.invoke(logHandler);
                        logHandlerPrintLog.invoke(logHandler);

                        // Execute
                        triggerItemWalk.invoke(null, effect, event);
                        variablesRemoveLocals.invoke(null, event);

                        result.addProperty("success", true);
                    } else {
                        result.addProperty("success", false);
                        result.addProperty("output", "Failed to parse: " + code);
                    }

                    // Captured messages from the sender
                    JsonArray outputLines = new JsonArray();
                    for (String line : sender.getMessages()) {
                        outputLines.add(line);
                    }
                    result.add("output", outputLines);
                    client.sendResult(id, result);

                } finally {
                    logHandlerStop.invoke(logHandler);
                }

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Skript reflection eval failed, falling back", e);
                evalViaDispatch(id, code, client);
            }
            return null;
        });
    }

    private void evalBatchViaReflection(String id, List<String> codes, WebSocketClient client) {
        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            try {
                CaptureSender sender = new CaptureSender();
                Object logHandler = loggerStartRetaining.invoke(null);

                try {
                    // Shared event so local variables persist across the batch
                    Object event = effectCommandEventConstructor.newInstance(sender, codes.get(0));
                    Object parser = parserGetInstance.invoke(null);
                    parserSetCurrentEvent.invoke(parser, "effect command",
                            new Class[]{effectCommandEventClass});

                    JsonArray results = new JsonArray();

                    for (String code : codes) {
                        sender.clear();
                        logHandlerClear.invoke(logHandler);

                        Object effect = effectParse.invoke(null, code, (String) null);

                        JsonObject entry = new JsonObject();
                        entry.addProperty("code", code);

                        if (effect != null) {
                            logHandlerPrintLog.invoke(logHandler);
                            triggerItemWalk.invoke(null, effect, event);
                            entry.addProperty("success", true);
                        } else {
                            entry.addProperty("success", false);
                            entry.addProperty("error", "Failed to parse: " + code);
                        }

                        JsonArray outputLines = new JsonArray();
                        for (String line : sender.getMessages()) {
                            outputLines.add(line);
                        }
                        entry.add("output", outputLines);
                        results.add(entry);
                    }

                    parserDeleteCurrentEvent.invoke(parser);
                    variablesRemoveLocals.invoke(null, event);

                    JsonObject result = new JsonObject();
                    result.addProperty("method", "reflection");
                    result.addProperty("batch", true);
                    result.add("results", results);
                    client.sendResult(id, result);

                } finally {
                    logHandlerStop.invoke(logHandler);
                }

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Skript batch reflection eval failed, falling back", e);
                evalBatchViaDispatch(id, codes, client);
            }
            return null;
        });
    }

    private void evalBatchViaDispatch(String id, List<String> codes, WebSocketClient client) {
        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            int logSizeBefore = consoleHandler.getLogSize();

            for (String code : codes) {
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "sk effect " + code);
                } catch (Exception e) {
                    // Continue with remaining codes
                }
            }

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                List<String> newLines = consoleHandler.getLogsSince(logSizeBefore);
                JsonObject result = new JsonObject();
                result.addProperty("method", "dispatch");
                result.addProperty("batch", true);
                result.addProperty("success", true);

                JsonArray outputLines = new JsonArray();
                for (String line : newLines) {
                    outputLines.add(line);
                }
                result.add("console_output", outputLines);
                client.sendResult(id, result);
            }, 5L);

            return null;
        });
    }

    private void evalViaDispatch(String id, String code, WebSocketClient client) {
        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            int logSizeBefore = consoleHandler.getLogSize();

            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "sk effect " + code);
            } catch (Exception e) {
                client.sendError(id, "Failed to execute: " + e.getMessage());
                return null;
            }

            // Wait a tick for output
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                List<String> newLines = consoleHandler.getLogsSince(logSizeBefore);
                JsonObject result = new JsonObject();
                result.addProperty("code", code);
                result.addProperty("method", "dispatch");
                result.addProperty("success", true);

                JsonArray outputLines = new JsonArray();
                for (String line : newLines) {
                    outputLines.add(line);
                }
                result.add("console_output", outputLines);
                client.sendResult(id, result);
            }, 1L);

            return null;
        });
    }

    /**
     * CommandSender that captures all messages sent to it.
     * Used as the sender for Skript effect execution so we can
     * capture output like "send" messages directly.
     */
    private static class CaptureSender implements CommandSender {

        private final List<String> messages = new ArrayList<>();
        private final LegacyComponentSerializer serializer = LegacyComponentSerializer.legacySection();

        public List<String> getMessages() {
            return new ArrayList<>(messages);
        }

        public void clear() {
            messages.clear();
        }

        @Override public void sendMessage(@NotNull String message) { messages.add(message); }
        @Override public void sendMessage(@NotNull String... msgs) { for (String m : msgs) messages.add(m); }
        @Override public void sendMessage(@NotNull UUID sender, @NotNull String message) { messages.add(message); }
        @Override public void sendMessage(@NotNull UUID sender, @NotNull String... msgs) { for (String m : msgs) messages.add(m); }
        @Override public void sendMessage(@NotNull Component message) { messages.add(serializer.serialize(message)); }
        @Override public @NotNull String getName() { return "McClaude"; }
        @Override public @NotNull Server getServer() { return Bukkit.getServer(); }
        @Override public boolean isPermissionSet(@NotNull String name) { return true; }
        @Override public boolean isPermissionSet(@NotNull Permission perm) { return true; }
        @Override public boolean hasPermission(@NotNull String name) { return true; }
        @Override public boolean hasPermission(@NotNull Permission perm) { return true; }
        @Override public @NotNull PermissionAttachment addAttachment(@NotNull Plugin p, @NotNull String n, boolean v) { throw new UnsupportedOperationException(); }
        @Override public @NotNull PermissionAttachment addAttachment(@NotNull Plugin p) { throw new UnsupportedOperationException(); }
        @Override public PermissionAttachment addAttachment(@NotNull Plugin p, @NotNull String n, boolean v, int t) { throw new UnsupportedOperationException(); }
        @Override public PermissionAttachment addAttachment(@NotNull Plugin p, int t) { throw new UnsupportedOperationException(); }
        @Override public void removeAttachment(@NotNull PermissionAttachment a) {}
        @Override public void recalculatePermissions() {}
        @Override public @NotNull Set<PermissionAttachmentInfo> getEffectivePermissions() { return Collections.emptySet(); }
        @Override public boolean isOp() { return true; }
        @Override public void setOp(boolean value) {}
        @Override public @NotNull Component name() { return Component.text("McClaude"); }
        @Override public @NotNull Spigot spigot() { return new Spigot(); }
    }
}
