package com.mcclaude.plugin.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mcclaude.plugin.McClaudePlugin;
import com.mcclaude.plugin.WebSocketClient;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class GuiDesigner implements Listener {

    private final McClaudePlugin plugin;
    private final Map<UUID, DesignSession> sessions = new HashMap<>();

    public GuiDesigner(McClaudePlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // ── Design Session ─────────────────────────────────────────────────

    private static class DesignSession {
        final Inventory inventory;
        final String title;
        final String type;
        final int rows;
        int version = 0;
        int lastReadVersion = 0;

        DesignSession(Inventory inventory, String title, String type, int rows) {
            this.inventory = inventory;
            this.title = title;
            this.type = type;
            this.rows = rows;
        }
    }

    // ── Public: reopen for /mcclaude gui ───────────────────────────────

    public boolean hasSession(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    public void reopenGui(Player player) {
        DesignSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            player.openInventory(session.inventory);
        }
    }

    // ── Event Listeners ────────────────────────────────────────────────

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        DesignSession session = sessions.get(player.getUniqueId());
        if (session == null) return;
        if (!event.getInventory().equals(session.inventory)) return;
        // Allow the click (free movement) but mark as dirty
        session.version++;
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        DesignSession session = sessions.get(player.getUniqueId());
        if (session == null) return;
        if (!event.getInventory().equals(session.inventory)) return;
        session.version++;
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        // Session persists — player can reopen with /mcclaude gui
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    // ── RPC Handlers ───────────────────────────────────────────────────

    public void handleGuiOpen(String id, JsonObject data, WebSocketClient client) {
        String playerName = data.has("player") ? data.get("player").getAsString() : null;
        int rows = data.has("rows") ? data.get("rows").getAsInt() : 3;
        String title = data.has("title") ? data.get("title").getAsString() : "Design";
        String type = data.has("type") ? data.get("type").getAsString().toLowerCase() : "chest";

        if (playerName == null || playerName.isEmpty()) {
            client.sendError(id, "No player provided");
            return;
        }

        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            try {
                Player player = Bukkit.getPlayerExact(playerName);
                if (player == null) {
                    client.sendError(id, "Player not online: " + playerName);
                    return null;
                }

                // Close existing session if any
                sessions.remove(player.getUniqueId());

                Component titleComp = LegacyComponentSerializer.legacyAmpersand().deserialize(title);
                Inventory inv;
                int actualRows;
                if (type.equals("chest")) {
                    if (rows < 1 || rows > 6) {
                        client.sendError(id, "Rows must be 1-6 for chest type");
                        return null;
                    }
                    inv = Bukkit.createInventory(null, rows * 9, titleComp);
                    actualRows = rows;
                } else {
                    try {
                        org.bukkit.event.inventory.InventoryType invType =
                            org.bukkit.event.inventory.InventoryType.valueOf(type.toUpperCase());
                        inv = Bukkit.createInventory(null, invType, titleComp);
                        actualRows = 1;
                    } catch (IllegalArgumentException e) {
                        client.sendError(id, "Unknown inventory type: " + type +
                            ". Valid types: chest, hopper, dispenser, dropper, furnace, blast_furnace, smoker, anvil, brewing, enchanting, workbench, beacon, grindstone, smithing, stonecutter, cartography, loom");
                        return null;
                    }
                }

                // Pre-populate slots if provided
                if (data.has("slots") && data.get("slots").isJsonArray()) {
                    for (JsonElement el : data.getAsJsonArray("slots")) {
                        JsonObject slotData = el.getAsJsonObject();
                        int slot = slotData.get("slot").getAsInt();
                        if (slot >= 0 && slot < inv.getSize()) {
                            ItemStack item = deserializeItem(slotData);
                            if (item != null) inv.setItem(slot, item);
                        }
                    }
                }

                DesignSession session = new DesignSession(inv, title, type, actualRows);
                sessions.put(player.getUniqueId(), session);
                player.openInventory(inv);

                JsonObject result = new JsonObject();
                result.addProperty("player", player.getName());
                result.addProperty("type", type);
                result.addProperty("title", title);
                result.addProperty("rows", actualRows);
                result.addProperty("size", inv.getSize());
                result.add("slots", serializeInventory(inv));
                client.sendResult(id, result);

            } catch (Exception e) {
                client.sendError(id, "Error opening design GUI: " + e.getMessage());
            }
            return null;
        });
    }

    public void handleGuiRead(String id, JsonObject data, WebSocketClient client) {
        String playerName = data.has("player") ? data.get("player").getAsString() : null;

        if (playerName == null || playerName.isEmpty()) {
            client.sendError(id, "No player provided");
            return;
        }

        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            try {
                Player player = Bukkit.getPlayerExact(playerName);
                if (player == null) {
                    client.sendError(id, "Player not online: " + playerName);
                    return null;
                }

                DesignSession session = sessions.get(player.getUniqueId());
                if (session == null) {
                    client.sendError(id, "No active design session for " + playerName + ". Use gui_open first.");
                    return null;
                }

                // Update lastReadVersion — Claude has now seen the current state
                session.lastReadVersion = session.version;

                JsonObject result = new JsonObject();
                result.addProperty("player", player.getName());
                result.addProperty("type", session.type);
                result.addProperty("title", session.title);
                result.addProperty("rows", session.rows);
                result.addProperty("size", session.inventory.getSize());
                result.addProperty("version", session.version);
                result.addProperty("modified_since_last_read", false);
                result.add("slots", serializeInventory(session.inventory));
                client.sendResult(id, result);

            } catch (Exception e) {
                client.sendError(id, "Error reading design GUI: " + e.getMessage());
            }
            return null;
        });
    }

    public void handleGuiSet(String id, JsonObject data, WebSocketClient client) {
        String playerName = data.has("player") ? data.get("player").getAsString() : null;

        if (playerName == null || playerName.isEmpty()) {
            client.sendError(id, "No player provided");
            return;
        }
        if (!data.has("slots") || !data.get("slots").isJsonArray()) {
            client.sendError(id, "No slots provided");
            return;
        }

        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            try {
                Player player = Bukkit.getPlayerExact(playerName);
                if (player == null) {
                    client.sendError(id, "Player not online: " + playerName);
                    return null;
                }

                DesignSession session = sessions.get(player.getUniqueId());
                if (session == null) {
                    client.sendError(id, "No active design session for " + playerName + ". Use gui_open first.");
                    return null;
                }

                // Conflict detection: has the inventory been modified since Claude last read?
                if (session.version != session.lastReadVersion) {
                    JsonObject result = new JsonObject();
                    result.addProperty("conflict", true);
                    result.addProperty("message",
                        "GUI was modified by the player since your last read. " +
                        "Call gui_read to see current state before making changes.");
                    result.addProperty("version", session.version);
                    result.addProperty("last_read_version", session.lastReadVersion);
                    result.add("current_slots", serializeInventory(session.inventory));
                    client.sendResult(id, result);
                    return null;
                }

                // Apply slot changes (incremental)
                JsonArray slotsArr = data.getAsJsonArray("slots");
                for (JsonElement el : slotsArr) {
                    JsonObject slotData = el.getAsJsonObject();
                    int slot = slotData.get("slot").getAsInt();
                    if (slot < 0 || slot >= session.inventory.getSize()) continue;

                    String type = slotData.has("type") ? slotData.get("type").getAsString() : null;
                    if (type == null || type.equalsIgnoreCase("air") || type.isEmpty()) {
                        session.inventory.setItem(slot, null);
                    } else {
                        ItemStack item = deserializeItem(slotData);
                        if (item != null) session.inventory.setItem(slot, item);
                    }
                }

                // Claude now implicitly knows the state after its own changes
                session.lastReadVersion = session.version;

                JsonObject result = new JsonObject();
                result.addProperty("conflict", false);
                result.addProperty("success", true);
                result.addProperty("player", player.getName());
                result.add("slots", serializeInventory(session.inventory));
                client.sendResult(id, result);

            } catch (Exception e) {
                client.sendError(id, "Error setting GUI slots: " + e.getMessage());
            }
            return null;
        });
    }

    public void handleGuiClose(String id, JsonObject data, WebSocketClient client) {
        String playerName = data.has("player") ? data.get("player").getAsString() : null;

        if (playerName == null || playerName.isEmpty()) {
            client.sendError(id, "No player provided");
            return;
        }

        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            try {
                Player player = Bukkit.getPlayerExact(playerName);
                if (player == null) {
                    client.sendError(id, "Player not online: " + playerName);
                    return null;
                }

                DesignSession session = sessions.remove(player.getUniqueId());
                if (session == null) {
                    client.sendError(id, "No active design session for " + playerName);
                    return null;
                }

                // Return final state before destroying
                JsonObject result = new JsonObject();
                result.addProperty("player", player.getName());
                result.addProperty("type", session.type);
                result.addProperty("title", session.title);
                result.addProperty("rows", session.rows);
                result.addProperty("size", session.inventory.getSize());
                result.add("final_layout", serializeInventory(session.inventory));

                // Close the inventory for the player
                player.closeInventory();

                client.sendResult(id, result);

            } catch (Exception e) {
                client.sendError(id, "Error closing design GUI: " + e.getMessage());
            }
            return null;
        });
    }

    // ── Serialization ──────────────────────────────────────────────────

    private JsonArray serializeInventory(Inventory inv) {
        JsonArray slots = new JsonArray();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            JsonObject obj = serializeItem(item);
            obj.addProperty("slot", i);
            slots.add(obj);
        }
        return slots;
    }

    private JsonObject serializeItem(ItemStack item) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", item.getType().name().toLowerCase());
        obj.addProperty("amount", item.getAmount());
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta.hasDisplayName() && meta.displayName() != null) {
                obj.addProperty("name",
                    PlainTextComponentSerializer.plainText().serialize(meta.displayName()));
                obj.addProperty("name_styled",
                    LegacyComponentSerializer.legacyAmpersand().serialize(meta.displayName()));
            }
            if (meta.hasLore() && meta.lore() != null) {
                JsonArray lore = new JsonArray();
                JsonArray loreStyled = new JsonArray();
                for (Component line : meta.lore()) {
                    lore.add(PlainTextComponentSerializer.plainText().serialize(line));
                    loreStyled.add(LegacyComponentSerializer.legacyAmpersand().serialize(line));
                }
                obj.add("lore", lore);
                obj.add("lore_styled", loreStyled);
            }
            if (meta.hasEnchants()) {
                JsonObject enchants = new JsonObject();
                for (var entry : meta.getEnchants().entrySet()) {
                    enchants.addProperty(entry.getKey().getKey().getKey(), entry.getValue());
                }
                obj.add("enchantments", enchants);
            }
        }
        return obj;
    }

    private ItemStack deserializeItem(JsonObject data) {
        String typeName = data.has("type") ? data.get("type").getAsString() : null;
        if (typeName == null || typeName.equalsIgnoreCase("air")) return null;

        Material mat = Material.matchMaterial(typeName.toUpperCase());
        if (mat == null) mat = Material.matchMaterial(typeName);
        if (mat == null) return null;

        int amount = data.has("amount") ? data.get("amount").getAsInt() : 1;
        ItemStack item = new ItemStack(mat, Math.max(1, amount));

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        // Name: prefer name_styled, fall back to name (both treated as &-coded)
        String name = null;
        if (data.has("name_styled")) name = data.get("name_styled").getAsString();
        else if (data.has("name")) name = data.get("name").getAsString();
        if (name != null && !name.isEmpty()) {
            meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(name));
        }

        // Lore: prefer lore_styled, fall back to lore
        JsonArray loreArr = null;
        if (data.has("lore_styled") && data.get("lore_styled").isJsonArray()) {
            loreArr = data.getAsJsonArray("lore_styled");
        } else if (data.has("lore") && data.get("lore").isJsonArray()) {
            loreArr = data.getAsJsonArray("lore");
        }
        if (loreArr != null && loreArr.size() > 0) {
            List<Component> lore = new ArrayList<>();
            for (JsonElement el : loreArr) {
                lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize(el.getAsString()));
            }
            meta.lore(lore);
        }

        // Enchantments
        if (data.has("enchantments") && data.get("enchantments").isJsonObject()) {
            JsonObject enchObj = data.getAsJsonObject("enchantments");
            for (Map.Entry<String, JsonElement> entry : enchObj.entrySet()) {
                Enchantment ench = Enchantment.getByName(entry.getKey().toUpperCase());
                if (ench == null) {
                    // Try namespaced key
                    ench = Enchantment.getByKey(org.bukkit.NamespacedKey.minecraft(entry.getKey()));
                }
                if (ench != null) {
                    meta.addEnchant(ench, entry.getValue().getAsInt(), true);
                }
            }
        }

        item.setItemMeta(meta);
        return item;
    }
}
