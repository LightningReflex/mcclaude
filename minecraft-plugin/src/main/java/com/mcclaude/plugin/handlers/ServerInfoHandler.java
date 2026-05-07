package com.mcclaude.plugin.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcclaude.plugin.McClaudePlugin;
import com.mcclaude.plugin.WebSocketClient;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.Plugin;
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

        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            try {
                if (playerName == null || playerName.isEmpty()) {
                    JsonObject result = new JsonObject();
                    JsonArray players = new JsonArray();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        players.add(serializePlayer(p, includeInventory));
                    }
                    result.add("players", players);
                    result.addProperty("count", players.size());
                    client.sendResult(id, result);
                } else {
                    Player p = Bukkit.getPlayerExact(playerName);
                    if (p == null) {
                        client.sendError(id, "Player not found: " + playerName);
                    } else {
                        client.sendResult(id, serializePlayer(p, includeInventory));
                    }
                }
            } catch (Exception e) {
                client.sendError(id, "Error getting player info: " + e.getMessage());
            }
            return null;
        });
    }

    private JsonObject serializeItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        JsonObject obj = new JsonObject();
        obj.addProperty("type", item.getType().name().toLowerCase());
        obj.addProperty("amount", item.getAmount());
        if (item.hasItemMeta()) {
            var meta = item.getItemMeta();
            if (meta.hasDisplayName()) {
                obj.addProperty("name", meta.displayName() != null ?
                    PlainTextComponentSerializer.plainText()
                        .serialize(meta.displayName()) : null);
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
                for (var line : meta.lore()) {
                    lore.add(PlainTextComponentSerializer.plainText()
                        .serialize(line));
                }
                obj.add("lore", lore);
            }
            obj.addProperty("durability", ((Damageable) meta).getDamage());
        }
        return obj;
    }

    private JsonObject serializePlayer(Player p, boolean includeInventory) {
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
        JsonObject mainHand = serializeItem(p.getInventory().getItemInMainHand());
        JsonObject offHand = serializeItem(p.getInventory().getItemInOffHand());
        if (mainHand != null) obj.add("mainHand", mainHand);
        if (offHand != null) obj.add("offHand", offHand);

        // Cursor item (item on cursor in open inventory)
        JsonObject cursor = serializeItem(p.getItemOnCursor());
        if (cursor != null) obj.add("cursor", cursor);

        // Armor
        JsonObject armor = new JsonObject();
        JsonObject helmet = serializeItem(p.getInventory().getHelmet());
        JsonObject chest = serializeItem(p.getInventory().getChestplate());
        JsonObject legs = serializeItem(p.getInventory().getLeggings());
        JsonObject boots = serializeItem(p.getInventory().getBoots());
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
                JsonObject slot = serializeItem(p.getInventory().getItem(i));
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
