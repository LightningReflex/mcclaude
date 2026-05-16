package com.mcclaude.plugin;

import com.mcclaude.plugin.handlers.GuiDesigner;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class McClaudeCommand implements CommandExecutor {

    private final McClaudePlugin plugin;

    public McClaudeCommand(McClaudePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "status":
                handleStatus(sender);
                break;
            case "reload":
                handleReload(sender);
                break;
            case "gui":
                handleGui(sender);
                break;
            default:
                sendUsage(sender);
                break;
        }

        return true;
    }

    private void handleStatus(CommandSender sender) {
        WebSocketClient client = plugin.getWebSocketClient();
        boolean connected = client != null && client.isConnected();

        sender.sendMessage(Component.text("=== McClaude Status ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Server URL: ", NamedTextColor.GRAY)
                .append(Component.text(plugin.getServerUrl(), NamedTextColor.WHITE)));

        if (connected) {
            sender.sendMessage(Component.text("Connection: ", NamedTextColor.GRAY)
                    .append(Component.text("Connected", NamedTextColor.GREEN)));
        } else {
            sender.sendMessage(Component.text("Connection: ", NamedTextColor.GRAY)
                    .append(Component.text("Disconnected", NamedTextColor.RED)));
        }

        sender.sendMessage(Component.text("Allowed paths: ", NamedTextColor.GRAY)
                .append(Component.text(String.join(", ", plugin.getAllowedPaths()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Blocked commands: ", NamedTextColor.GRAY)
                .append(Component.text(String.join(", ", plugin.getBlockedCommands()), NamedTextColor.WHITE)));
    }

    private void handleReload(CommandSender sender) {
        sender.sendMessage(Component.text("Reloading McClaude configuration...", NamedTextColor.YELLOW));

        plugin.loadConfiguration();
        plugin.reconnect();

        sender.sendMessage(Component.text("Configuration reloaded. Reconnecting to server.", NamedTextColor.GREEN));
    }

    private void handleGui(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by a player.", NamedTextColor.RED));
            return;
        }

        WebSocketClient wsClient = plugin.getWebSocketClient();
        if (wsClient == null) {
            sender.sendMessage(Component.text("McClaude is not connected.", NamedTextColor.RED));
            return;
        }

        GuiDesigner designer = wsClient.getGuiDesigner();
        if (designer == null || !designer.hasSession(player)) {
            sender.sendMessage(Component.text("No active design session. Claude will open one when designing a GUI.", NamedTextColor.YELLOW));
            return;
        }

        designer.reopenGui(player);
        sender.sendMessage(Component.text("Reopened design GUI.", NamedTextColor.GREEN));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("Usage: /mcclaude <status|reload|gui>", NamedTextColor.YELLOW));
    }
}
