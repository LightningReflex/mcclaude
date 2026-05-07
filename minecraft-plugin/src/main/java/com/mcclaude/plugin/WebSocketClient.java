package com.mcclaude.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcclaude.plugin.handlers.CommandHandler;
import com.mcclaude.plugin.handlers.ConsoleHandler;
import com.mcclaude.plugin.handlers.FileHandler;
import com.mcclaude.plugin.handlers.ServerInfoHandler;
import com.mcclaude.plugin.handlers.SkriptBridge;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.bukkit.Bukkit;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class WebSocketClient extends WebSocketListener {

    private final McClaudePlugin plugin;
    private final Gson gson;
    private final CryptoUtil crypto;
    private final ConsoleHandler consoleHandler;
    private final CommandHandler commandHandler;
    private final FileHandler fileHandler;
    private final ServerInfoHandler serverInfoHandler;
    private final SkriptBridge skriptBridge;

    private OkHttpClient httpClient;
    private WebSocket webSocket;
    private boolean connected;
    private boolean intentionalDisconnect;
    private int reconnectTaskId = -1;

    /** Tracks the request ID when we're expecting a binary frame with encrypted RPC payload */
    private String pendingRpcRequestId;
    private final Set<String> activeRpcIds = ConcurrentHashMap.newKeySet();
    /** Tracks the request ID for an in-flight RPC so sendResult/sendError encrypt the response */

    public WebSocketClient(McClaudePlugin plugin) {
        this.plugin = plugin;
        this.gson = new Gson();
        this.crypto = new CryptoUtil(plugin.getServerToken());
        this.consoleHandler = new ConsoleHandler(plugin, this);
        this.commandHandler = new CommandHandler(plugin, consoleHandler);
        this.fileHandler = new FileHandler(plugin);
        this.serverInfoHandler = new ServerInfoHandler(plugin);
        this.skriptBridge = new SkriptBridge(plugin, consoleHandler);
        this.skriptBridge.init();
        this.connected = false;
        this.intentionalDisconnect = false;
    }

    public void connect() {
        intentionalDisconnect = false;

        httpClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build();

        String serverName = plugin.getDisplayName();
        String tokenHash = sha256(plugin.getServerToken());
        String url = plugin.getServerUrl() + "?token_hash=" + tokenHash + "&name=" + serverName;

        Request request = new Request.Builder()
                .url(url)
                .build();

        plugin.getLogger().info("Connecting to WebSocket: " + plugin.getServerUrl());
        webSocket = httpClient.newWebSocket(request, this);
    }

    public void disconnect() {
        intentionalDisconnect = true;
        cancelReconnect();
        consoleHandler.shutdown();

        if (webSocket != null) {
            webSocket.close(1000, "Plugin disabling");
            webSocket = null;
        }
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient = null;
        }
        connected = false;
    }

    public boolean isConnected() {
        return connected;
    }

    public void sendMessage(JsonObject message) {
        if (webSocket != null && connected) {
            String json = gson.toJson(message);
            webSocket.send(json);
        }
    }

    public void sendBinaryFrame(byte[] data) {
        if (webSocket != null && connected) {
            webSocket.send(ByteString.of(data));
        }
    }

    public CryptoUtil getCrypto() {
        return crypto;
    }

    @Override
    public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
        connected = true;
        plugin.getLogger().info("Connected to McClaude intermediate server.");
        consoleHandler.start();
    }

    @Override
    public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
        try {
            JsonObject message = JsonParser.parseString(text).getAsJsonObject();
            handleMessage(message);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to parse incoming message: " + text, e);
        }
    }

    @Override
    public void onMessage(@NotNull WebSocket webSocket, @NotNull ByteString bytes) {
        // Binary frame received — check what we're expecting

        // Case 1: Binary write (legacy protocol)
        if (pendingBinaryWriteId != null) {
            String writeId = pendingBinaryWriteId;
            String writePath = pendingBinaryWritePath;
            pendingBinaryWriteId = null;
            pendingBinaryWritePath = null;

            byte[] encrypted = bytes.toByteArray();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    byte[] decrypted = crypto.decryptRaw(encrypted);
                    File file = fileHandler.resolveFilePublic(writePath);
                    File parentDir = file.getParentFile();
                    if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();
                    Files.write(file.toPath(), decrypted);

                    JsonObject result = new JsonObject();
                    result.addProperty("path", writePath);
                    result.addProperty("size", decrypted.length);
                    result.addProperty("success", true);
                    sendResult(writeId, result);
                } catch (Exception e) {
                    sendError(writeId, "Binary write failed: " + e.getMessage());
                }
            });
            return;
        }

        // Case 2: RPC encrypted request
        if (pendingRpcRequestId != null) {
            String rpcId = pendingRpcRequestId;
            pendingRpcRequestId = null;

            byte[] encrypted = bytes.toByteArray();
            try {
                byte[] decrypted = crypto.decryptRaw(encrypted);
                String jsonStr = new String(decrypted, StandardCharsets.UTF_8);
                JsonObject request = JsonParser.parseString(jsonStr).getAsJsonObject();

                String type = request.has("type") ? request.get("type").getAsString() : null;
                JsonObject data = request.has("data") ? request.getAsJsonObject("data") : new JsonObject();

                if (type == null) {
                    sendError(rpcId, "RPC request missing 'type' field");
                    return;
                }

                // Track this RPC so sendResult/sendError encrypt the response
                activeRpcIds.add(rpcId);

                plugin.getLogger().fine("Handling RPC message type: " + type + " (id: " + rpcId + ")");
                dispatchRequest(rpcId, type, data);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to process RPC request: " + e.getMessage(), e);
                sendError(rpcId, "RPC processing failed: " + e.getMessage());
            }
            return;
        }

        plugin.getLogger().warning("Received unexpected binary frame (no pending operation)");
    }

    /** Set up to receive the next binary frame as a file write */
    private String pendingBinaryWriteId;
    private String pendingBinaryWritePath;

    public void expectBinaryWrite(String id, String path) {
        this.pendingBinaryWriteId = id;
        this.pendingBinaryWritePath = path;
    }

    @Override
    public void onClosing(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
        connected = false;
        plugin.getLogger().info("WebSocket closing: " + code + " - " + reason);
        webSocket.close(code, reason);
    }

    @Override
    public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
        connected = false;
        plugin.getLogger().info("WebSocket closed: " + code + " - " + reason);
        consoleHandler.shutdown();
        scheduleReconnect();
    }

    @Override
    public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable Response response) {
        connected = false;
        plugin.getLogger().log(Level.WARNING, "WebSocket connection failed: " + t.getMessage());
        consoleHandler.shutdown();
        scheduleReconnect();
    }

    private void handleMessage(JsonObject message) {
        String id = message.has("id") ? message.get("id").getAsString() : null;
        String type = message.has("type") ? message.get("type").getAsString() : null;
        JsonObject data = message.has("data") ? message.getAsJsonObject("data") : new JsonObject();

        // Binary stream incoming — server is about to send binary data for a file write
        if (message.has("stream") && message.get("stream").getAsBoolean() && id != null) {
            String path = data.has("path") ? data.get("path").getAsString() : "";
            expectBinaryWrite(id, path);
            return;
        }

        if (id == null || type == null) {
            plugin.getLogger().warning("Received message without id or type: " + message);
            return;
        }

        // RPC: expect the next binary frame to contain the encrypted request payload
        if ("rpc".equals(type)) {
            plugin.getLogger().fine("RPC request received, awaiting binary frame (id: " + id + ")");
            pendingRpcRequestId = id;
            return;
        }

        plugin.getLogger().fine("Handling message type: " + type + " (id: " + id + ")");
        dispatchRequest(id, type, data);
    }

    /**
     * Dispatches a request to the appropriate handler based on type.
     * Shared between the legacy text protocol and the new RPC protocol.
     */
    private void dispatchRequest(String id, String type, JsonObject data) {
        switch (type) {
            case "console_read":
                consoleHandler.handleConsoleRead(id, data, this);
                break;
            case "command":
                commandHandler.handleCommand(id, data, this);
                break;
            case "file_list":
                fileHandler.handleFileList(id, data, this);
                break;
            case "file_read":
                fileHandler.handleFileRead(id, data, this);
                break;
            case "file_write":
                fileHandler.handleFileWrite(id, data, this);
                break;
            case "file_delete":
                fileHandler.handleFileDelete(id, data, this);
                break;
            case "file_mkdir":
                fileHandler.handleMkdir(id, data, this);
                break;
            case "file_rename":
                fileHandler.handleRename(id, data, this);
                break;
            case "server_info":
                serverInfoHandler.handleServerInfo(id, this);
                break;
            case "plugins_list":
                serverInfoHandler.handlePluginsList(id, this);
                break;
            case "player_info":
                serverInfoHandler.handlePlayerInfo(id, data, this);
                break;
            case "skript_eval":
                skriptBridge.handleSkriptEval(id, data, this);
                break;
            default:
                sendError(id, "Unknown message type: " + type);
                break;
        }
    }

    public void sendResult(String id, JsonObject result) {
        if (activeRpcIds.remove(id)) {
            // RPC mode: encrypt entire response and send as stream
            JsonObject response = new JsonObject();
            response.add("result", result);
            response.add("error", null);
            byte[] encrypted = crypto.encryptRaw(response.toString().getBytes(StandardCharsets.UTF_8));

            JsonObject streamStart = new JsonObject();
            streamStart.addProperty("id", id);
            streamStart.addProperty("stream", "start");
            sendMessage(streamStart);

            sendBinaryFrame(encrypted);

            JsonObject streamEnd = new JsonObject();
            streamEnd.addProperty("id", id);
            streamEnd.addProperty("stream", "end");
            sendMessage(streamEnd);
            return;
        }

        // Normal mode (backward compat)
        JsonObject response = new JsonObject();
        response.addProperty("id", id);
        response.add("result", result);
        response.add("error", null);
        sendMessage(response);
    }

    public void sendError(String id, String error) {
        if (activeRpcIds.remove(id)) {
            // RPC mode: encrypt error response and send as stream
            JsonObject response = new JsonObject();
            response.add("result", null);
            response.addProperty("error", error);
            byte[] encrypted = crypto.encryptRaw(response.toString().getBytes(StandardCharsets.UTF_8));

            JsonObject streamStart = new JsonObject();
            streamStart.addProperty("id", id);
            streamStart.addProperty("stream", "start");
            sendMessage(streamStart);

            sendBinaryFrame(encrypted);

            JsonObject streamEnd = new JsonObject();
            streamEnd.addProperty("id", id);
            streamEnd.addProperty("stream", "end");
            sendMessage(streamEnd);
            return;
        }

        // Normal mode (backward compat)
        JsonObject response = new JsonObject();
        response.addProperty("id", id);
        response.add("result", null);
        response.addProperty("error", error);
        sendMessage(response);
    }

    /**
     * Check if the given request ID is currently in RPC mode.
     * Used by handlers (e.g., FileHandler) to adjust their behavior.
     */
    public boolean isRpcMode(String id) {
        return activeRpcIds.contains(id);
    }

    private void scheduleReconnect() {
        if (intentionalDisconnect) {
            return;
        }

        int delaySeconds = plugin.getReconnectDelaySeconds();
        plugin.getLogger().info("Scheduling reconnect in " + delaySeconds + " seconds...");

        // Schedule reconnect on the main server thread to safely interact with Bukkit scheduler
        reconnectTaskId = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!intentionalDisconnect && !connected) {
                plugin.getLogger().info("Attempting to reconnect...");
                connect();
            }
        }, delaySeconds * 20L).getTaskId(); // 20 ticks per second
    }

    private void cancelReconnect() {
        if (reconnectTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(reconnectTaskId);
            reconnectTaskId = -1;
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
