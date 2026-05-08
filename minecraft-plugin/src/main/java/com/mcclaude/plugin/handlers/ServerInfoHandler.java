package com.mcclaude.plugin.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcclaude.plugin.McClaudePlugin;
import com.mcclaude.plugin.WebSocketClient;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.Plugin;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public class ServerInfoHandler {

    private final McClaudePlugin plugin;

    public ServerInfoHandler(McClaudePlugin plugin) {
        this.plugin = plugin;
    }

    public void handleServerInfo(String id, WebSocketClient client) {
        // Must gather some info on the main thread (player list, TPS)
        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            try {
                JsonObject result = new JsonObject();

                result.addProperty("version", Bukkit.getVersion());
                result.addProperty("bukkitVersion", Bukkit.getBukkitVersion());
                result.addProperty("players", Bukkit.getOnlinePlayers().size());
                result.addProperty("maxPlayers", Bukkit.getMaxPlayers());
                result.addProperty("motd", Bukkit.getMotd());

                // TPS - Paper API provides this
                double[] tps = Bukkit.getTPS();
                JsonArray tpsArray = new JsonArray();
                for (double t : tps) {
                    tpsArray.add(Math.round(t * 100.0) / 100.0);
                }
                result.add("tps", tpsArray);

                // Online player names
                JsonArray onlinePlayers = new JsonArray();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    onlinePlayers.add(player.getName());
                }
                result.add("onlinePlayers", onlinePlayers);

                client.sendResult(id, result);
            } catch (Exception e) {
                plugin.getLogger().warning("Error gathering server info: " + e.getMessage());
                client.sendError(id, "Error gathering server info: " + e.getMessage());
            }
            return null;
        });
    }

    public void handlePluginsList(String id, WebSocketClient client) {
        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            try {
                JsonObject result = new JsonObject();
                JsonArray pluginsArray = new JsonArray();

                for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("name", p.getName());
                    entry.addProperty("version", p.getDescription().getVersion());
                    entry.addProperty("enabled", p.isEnabled());
                    entry.addProperty("description", p.getDescription().getDescription());

                    JsonArray authors = new JsonArray();
                    for (String author : p.getDescription().getAuthors()) {
                        authors.add(author);
                    }
                    entry.add("authors", authors);

                    JsonArray dependencies = new JsonArray();
                    for (String dep : p.getDescription().getDepend()) {
                        dependencies.add(dep);
                    }
                    entry.add("dependencies", dependencies);

                    entry.addProperty("main", p.getDescription().getMain());
                    pluginsArray.add(entry);
                }

                result.add("plugins", pluginsArray);
                result.addProperty("count", pluginsArray.size());
                client.sendResult(id, result);
            } catch (Exception e) {
                client.sendError(id, "Error listing plugins: " + e.getMessage());
            }
            return null;
        });
    }

    public void handlePlayerInfo(String id, JsonObject data, WebSocketClient client) {
        String playerName = data.has("player") ? data.get("player").getAsString() : null;
        boolean includeInventory = data.has("inventory") && data.get("inventory").getAsBoolean();
        boolean styled = data.has("styled") && data.get("styled").getAsBoolean();

        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            try {
                if (playerName == null || playerName.isEmpty()) {
                    JsonObject result = new JsonObject();
                    JsonArray players = new JsonArray();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        players.add(serializePlayer(p, includeInventory, styled));
                    }
                    result.add("players", players);
                    result.addProperty("count", players.size());
                    client.sendResult(id, result);
                } else {
                    Player p = Bukkit.getPlayerExact(playerName);
                    if (p == null) {
                        client.sendError(id, "Player not found: " + playerName);
                    } else {
                        client.sendResult(id, serializePlayer(p, includeInventory, styled));
                    }
                }
            } catch (Exception e) {
                client.sendError(id, "Error getting player info: " + e.getMessage());
            }
            return null;
        });
    }

    public void handleOpenInventory(String id, JsonObject data, WebSocketClient client) {
        String playerName = data.has("player") ? data.get("player").getAsString() : null;
        boolean styled = data.has("styled") && data.get("styled").getAsBoolean();

        if (playerName == null || playerName.isEmpty()) {
            client.sendError(id, "No player provided");
            return;
        }

        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            try {
                Player p = Bukkit.getPlayerExact(playerName);
                if (p == null) {
                    client.sendError(id, "Player not online: " + playerName);
                    return null;
                }

                InventoryView view = p.getOpenInventory();
                Inventory top = view.getTopInventory();
                InventoryType type = top.getType();
                // CRAFTING = the player's default 2x2 crafting grid (no real GUI open)
                boolean hasGuiOpen = type != InventoryType.CRAFTING;

                JsonObject result = new JsonObject();
                result.addProperty("player", p.getName());
                result.addProperty("open", hasGuiOpen);
                result.addProperty("type", type.name().toLowerCase());
                result.addProperty("size", top.getSize());

                try {
                    var titleComp = view.title();
                    result.addProperty("title",
                        PlainTextComponentSerializer.plainText().serialize(titleComp));
                    if (styled) {
                        result.addProperty("title_styled",
                            LegacyComponentSerializer.legacyAmpersand().serialize(titleComp));
                    }
                } catch (Exception e) {
                    result.addProperty("title", "");
                }

                JsonObject cursor = serializeItem(p.getItemOnCursor(), styled);
                if (cursor != null) result.add("cursor", cursor);

                JsonArray slots = new JsonArray();
                for (int i = 0; i < top.getSize(); i++) {
                    JsonObject slot = serializeItem(top.getItem(i), styled);
                    if (slot != null) {
                        slot.addProperty("slot", i);
                        slots.add(slot);
                    }
                }
                result.add("slots", slots);

                client.sendResult(id, result);
            } catch (Exception e) {
                client.sendError(id, "Error reading open inventory: " + e.getMessage());
            }
            return null;
        });
    }

    private JsonObject serializeItem(ItemStack item) {
        return serializeItem(item, false);
    }

    private JsonObject serializeItem(ItemStack item, boolean styled) {
        if (item == null || item.getType().isAir()) return null;
        JsonObject obj = new JsonObject();
        obj.addProperty("type", item.getType().name().toLowerCase());
        obj.addProperty("amount", item.getAmount());
        if (item.hasItemMeta()) {
            var meta = item.getItemMeta();
            if (meta.hasDisplayName() && meta.displayName() != null) {
                var nameComp = meta.displayName();
                obj.addProperty("name", PlainTextComponentSerializer.plainText().serialize(nameComp));
                if (styled) {
                    obj.addProperty("name_styled",
                        LegacyComponentSerializer.legacyAmpersand().serialize(nameComp));
                }
            }
            if (meta.hasEnchants()) {
                JsonObject enchants = new JsonObject();
                for (var entry : meta.getEnchants().entrySet()) {
                    enchants.addProperty(entry.getKey().getKey().getKey(), entry.getValue());
                }
                obj.add("enchantments", enchants);
            }
            if (meta.hasLore()) {
                JsonArray lore = new JsonArray();
                JsonArray loreStyled = styled ? new JsonArray() : null;
                for (var line : meta.lore()) {
                    lore.add(PlainTextComponentSerializer.plainText().serialize(line));
                    if (loreStyled != null) {
                        loreStyled.add(LegacyComponentSerializer.legacyAmpersand().serialize(line));
                    }
                }
                obj.add("lore", lore);
                if (loreStyled != null) obj.add("lore_styled", loreStyled);
            }
            obj.addProperty("durability", ((Damageable) meta).getDamage());
        }
        return obj;
    }

    private JsonObject serializePlayer(Player p, boolean includeInventory, boolean styled) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", p.getName());
        obj.addProperty("uuid", p.getUniqueId().toString());
        obj.addProperty("health", p.getHealth());
        try {
            obj.addProperty("maxHealth", p.getAttribute(Attribute.MAX_HEALTH).getValue());
        } catch (NoSuchFieldError | Exception e) {
            obj.addProperty("maxHealth", 20.0);
        }
        obj.addProperty("food", p.getFoodLevel());
        obj.addProperty("level", p.getLevel());
        obj.addProperty("xp", Math.round(p.getExp() * 100));
        obj.addProperty("gamemode", p.getGameMode().name().toLowerCase());
        obj.addProperty("op", p.isOp());
        obj.addProperty("flying", p.isFlying());
        obj.addProperty("sneaking", p.isSneaking());

        // Location
        Location loc = p.getLocation();
        JsonObject location = new JsonObject();
        location.addProperty("world", loc.getWorld().getName());
        location.addProperty("x", Math.round(loc.getX() * 10.0) / 10.0);
        location.addProperty("y", Math.round(loc.getY() * 10.0) / 10.0);
        location.addProperty("z", Math.round(loc.getZ() * 10.0) / 10.0);
        location.addProperty("yaw", Math.round(loc.getYaw()));
        location.addProperty("pitch", Math.round(loc.getPitch()));
        obj.add("location", location);

        // Held items
        JsonObject mainHand = serializeItem(p.getInventory().getItemInMainHand(), styled);
        JsonObject offHand = serializeItem(p.getInventory().getItemInOffHand(), styled);
        if (mainHand != null) obj.add("mainHand", mainHand);
        if (offHand != null) obj.add("offHand", offHand);

        // Cursor item (item on cursor in open inventory)
        JsonObject cursor = serializeItem(p.getItemOnCursor(), styled);
        if (cursor != null) obj.add("cursor", cursor);

        // Armor
        JsonObject armor = new JsonObject();
        JsonObject helmet = serializeItem(p.getInventory().getHelmet(), styled);
        JsonObject chest = serializeItem(p.getInventory().getChestplate(), styled);
        JsonObject legs = serializeItem(p.getInventory().getLeggings(), styled);
        JsonObject boots = serializeItem(p.getInventory().getBoots(), styled);
        if (helmet != null) armor.add("helmet", helmet);
        if (chest != null) armor.add("chestplate", chest);
        if (legs != null) armor.add("leggings", legs);
        if (boots != null) armor.add("boots", boots);
        obj.add("armor", armor);

        // Effects
        JsonArray effects = new JsonArray();
        for (var effect : p.getActivePotionEffects()) {
            JsonObject e = new JsonObject();
            e.addProperty("type", effect.getType().getName());
            e.addProperty("amplifier", effect.getAmplifier());
            e.addProperty("duration", effect.getDuration() / 20);
            effects.add(e);
        }
        obj.add("effects", effects);

        // Full inventory (only when requested)
        if (includeInventory) {
            JsonArray inventory = new JsonArray();
            for (int i = 0; i < p.getInventory().getSize(); i++) {
                JsonObject slot = serializeItem(p.getInventory().getItem(i), styled);
                if (slot != null) {
                    slot.addProperty("slot", i);
                    inventory.add(slot);
                }
            }
            obj.add("inventory", inventory);
        }

        return obj;
    }
}
