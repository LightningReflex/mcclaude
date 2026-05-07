package com.mcclaude.plugin;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

public class McClaudePlugin extends JavaPlugin {

    private WebSocketClient webSocketClient;
    private String serverUrl;
    private String serverToken;
    private String displayName;
    private List<String> allowedPaths;
    private List<String> blockedFiles;
    private List<String> blockedCommands;
    private int reconnectDelaySeconds;
    private int maxFileSizeKb;

    private static final String[] ADJECTIVES = {
        "brave", "swift", "calm", "dark", "bold", "keen", "wild", "pure",
        "vast", "warm", "cool", "deep", "fair", "grim", "hazy", "icy",
        "jade", "lazy", "mild", "neat", "pale", "rich", "sage", "tall",
        "arid", "blue", "cozy", "dull", "epic", "flat", "glad", "high",
        "iron", "just", "lush", "mega", "neon", "opal", "pink", "rare",
    };

    private static final String[] NOUNS = {
        "fox", "oak", "sun", "bay", "elm", "gem", "hub", "ink", "jar",
        "key", "log", "map", "net", "orb", "pin", "ram", "sky", "tea",
        "urn", "vine", "wolf", "yew", "arch", "bell", "cave", "dawn",
        "fern", "gate", "hill", "isle", "lake", "moon", "nest", "peak",
        "reef", "star", "tide", "vale", "wind", "void", "core", "edge",
    };

    @Override
    public void onEnable() {
        generateConfigIfMissing();
        loadConfiguration();

        webSocketClient = new WebSocketClient(this);
        webSocketClient.connect();

        getCommand("mcclaude").setExecutor(new McClaudeCommand(this));

        getLogger().info("McClaude plugin enabled. Connecting to " + serverUrl);
    }

    @Override
    public void onDisable() {
        if (webSocketClient != null) {
            webSocketClient.disconnect();
            webSocketClient = null;
        }
        getLogger().info("McClaude plugin disabled.");
    }

    private void generateConfigIfMissing() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (configFile.exists()) {
            return;
        }

        // Generate random token and name
        String token = UUID.randomUUID().toString();
        String name = randomName();

        var cfg = getConfig();

        cfg.setComments("server-url", List.of(
                " McClaude Configuration",
                "",
                " Connection",
                ""));
        cfg.set("server-url", "ws://localhost:3000/ws/plugin");

        cfg.setComments("server-token", List.of(
                "Network token — all servers and clients with the same token are linked together",
                "Acts like a password: anyone with this token can see and access your server",
                "Keep it random or hard to guess — simple tokens like \"test\" could collide with others"));
        cfg.set("server-token", token);

        cfg.setComments("server-name", List.of("Display name shown in the drive and Claude Code"));
        cfg.set("server-name", name);

        cfg.setComments("allowed-paths", List.of(
                "",
                " Permissions",
                "",
                "File access — empty string means everything, or list specific paths"));
        cfg.set("allowed-paths", List.of(""));

        cfg.setComments("blocked-files", List.of("Files hidden from listings (e.g. ops.json, whitelist.json)"));
        cfg.set("blocked-files", List.of());

        cfg.setComments("blocked-commands", List.of("Commands that cannot be executed remotely (e.g. stop, op)"));
        cfg.set("blocked-commands", List.of());

        cfg.setComments("reconnect-delay-seconds", List.of(
                "",
                " Advanced",
                "",
                "Seconds to wait before reconnecting after disconnect"));
        cfg.set("reconnect-delay-seconds", 10);

        cfg.setComments("max-file-size-kb", List.of("Maximum file size for reads in KB (100MB default)"));
        cfg.set("max-file-size-kb", 102400);

        saveConfig();

        getLogger().info("Generated new config with token: " + token);
        getLogger().info("Server name: " + name);
    }

    private String randomName() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String a1 = ADJECTIVES[r.nextInt(ADJECTIVES.length)];
        String a2 = ADJECTIVES[r.nextInt(ADJECTIVES.length)];
        String n = NOUNS[r.nextInt(NOUNS.length)];
        return a1 + "-" + a2 + "-" + n;
    }

    public void loadConfiguration() {
        reloadConfig();
        serverUrl = getConfig().getString("server-url", "ws://localhost:3000/ws/plugin");
        serverToken = getConfig().getString("server-token");
        displayName = getConfig().getString("server-name");
        allowedPaths = getConfig().getStringList("allowed-paths");
        blockedFiles = getConfig().getStringList("blocked-files");
        blockedCommands = getConfig().getStringList("blocked-commands");
        reconnectDelaySeconds = getConfig().getInt("reconnect-delay-seconds", 10);
        maxFileSizeKb = getConfig().getInt("max-file-size-kb", 102400);

        // These must be set
        if (serverToken == null || serverToken.isEmpty() || serverToken.equals("your-server-token-here")) {
            serverToken = UUID.randomUUID().toString();
            getConfig().set("server-token", serverToken);
            saveConfig();
            getLogger().info("Generated new token: " + serverToken);
        }
        if (displayName == null || displayName.isEmpty()) {
            displayName = randomName();
            getConfig().set("server-name", displayName);
            saveConfig();
            getLogger().info("Generated server name: " + displayName);
        }
    }

    public void reconnect() {
        if (webSocketClient != null) {
            webSocketClient.disconnect();
        }
        webSocketClient = new WebSocketClient(this);
        webSocketClient.connect();
    }

    public WebSocketClient getWebSocketClient() {
        return webSocketClient;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public String getServerToken() {
        return serverToken;
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<String> getAllowedPaths() {
        return allowedPaths;
    }

    public List<String> getBlockedFiles() {
        return blockedFiles;
    }

    public List<String> getBlockedCommands() {
        return blockedCommands;
    }

    public int getReconnectDelaySeconds() {
        return reconnectDelaySeconds;
    }

    public int getMaxFileSizeKb() {
        return maxFileSizeKb;
    }
}
