#!/usr/bin/env node

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import {
  listServers,
  readConsole,
  sendCommand,
  sendCommandAs,
  getServerInfo,
  listPlugins,
  getPlayerInfo,
  getOpenInventory,
  guiOpen,
  guiRead,
  guiSet,
  guiClose,
  searchSkriptSyntax,
  skriptEval,
} from "./api.js";

// ── Helpers ─────────────────────────────────────────────────────────

function hasUnquotedNewlines(code: string): boolean {
  let inQuotes = false;
  let escaped = false;
  for (const ch of code) {
    if (escaped) { escaped = false; continue; }
    if (ch === "\\") { escaped = true; continue; }
    if (ch === '"') { inQuotes = !inQuotes; }
    else if (ch === "\n" && !inQuotes) { return true; }
  }
  return false;
}

function textResult(data: unknown) {
  const text = typeof data === "string" ? data : JSON.stringify(data, null, 2);
  return { content: [{ type: "text" as const, text }] };
}

function errorResult(message: string) {
  return { content: [{ type: "text" as const, text: `Error: ${message}` }], isError: true as const };
}

async function handleApi(
  fn: () => Promise<{ ok: boolean; status: number; data: unknown }>
) {
  try {
    const res = await fn();
    if (!res.ok) {
      const msg =
        typeof res.data === "object" && res.data !== null && "error" in res.data
          ? String((res.data as Record<string, unknown>).error)
          : JSON.stringify(res.data);
      return errorResult(`HTTP ${res.status}: ${msg}`);
    }
    return textResult(res.data);
  } catch (err: unknown) {
    const message =
      err instanceof Error ? err.message : "Unknown error occurred";
    return errorResult(message);
  }
}

// ── Server setup ────────────────────────────────────────────────────

const server = new McpServer({
  name: "mcclaude",
  version: "1.0.0",
});

// ── Tool registrations ──────────────────────────────────────────────

server.tool(
  "list_servers",
  "List all Minecraft servers the user has access to, along with their online status and server IDs. Use this to get the server ID needed for other mcclaude tools.",
  {},
  async () => handleApi(() => listServers())
);

server.tool(
  "read_console",
  "Read recent console output from a live Minecraft server. This is the ONLY way to see server console logs — they are not available on the filesystem. Use the normal Read tool for files instead.",
  {
    server: z.string().describe("Server ID (get this from list_servers)"),
    lines: z.number().optional().describe("Number of lines to return (default 50)"),
    filter: z.string().optional().describe("Regex filter for console lines"),
  },
  async ({ server: serverId, lines, filter }) =>
    handleApi(() => readConsole(serverId, lines, filter))
);

server.tool(
  "send_command",
  "Execute a command on a live Minecraft server (e.g. tps, say, give, tp). This is the ONLY way to run server commands. Do NOT use this for file operations — use the normal Read/Write tools on the mounted drive instead.",
  {
    server: z.string().describe("Server ID (get this from list_servers)"),
    command: z.string().describe("Command to execute (without leading /)"),
  },
  async ({ server: serverId, command }) =>
    handleApi(() => sendCommand(serverId, command))
);

server.tool(
  "send_command_as",
  "Execute a command AS a specific online player (the player is the command sender, not the console). Use this when a command's behavior depends on the sender being a player — e.g. plugin commands that check sender.hasPermission(), `sender instanceof Player`, or that act on the sender's location/inventory. This replaces Skript's `make player(x) execute command` with proper output capture. " +
    "IMPORTANT: only console output is captured. Messages the command sends directly to the player's chat (player.sendMessage / message expressions in Skript) are NOT visible to McClaude — output may come back empty even when the command worked. Per CLAUDE.md, do not run active interactions on real players unless the user has explicitly authorized that player.",
  {
    server: z.string().describe("Server ID (get this from list_servers)"),
    player: z.string().describe("Exact name of an ONLINE player to run the command as. Errors if the player is offline."),
    command: z.string().describe("Command to execute (without leading /)"),
  },
  async ({ server: serverId, player, command }) =>
    handleApi(() => sendCommandAs(serverId, player, command))
);

server.tool(
  "get_server_info",
  "Get live status of a Minecraft server: version, TPS, player count, MOTD. This queries the running server in real-time.",
  {
    server: z.string().describe("Server ID (get this from list_servers)"),
  },
  async ({ server: serverId }) =>
    handleApi(() => getServerInfo(serverId))
);

server.tool(
  "list_plugins",
  "List all plugins installed on a Minecraft server, including name, version, enabled status, and dependencies.",
  {
    server: z.string().describe("Server ID (get this from list_servers)"),
  },
  async ({ server: serverId }) =>
    handleApi(() => listPlugins(serverId))
);

server.tool(
  "get_player_info",
  "Get detailed info about online players. Without a player name, returns all online players. With a name, returns that player's details including health, location, gamemode, armor, held item, cursor item, and active effects.",
  {
    server: z.string().describe("Server ID (get this from list_servers)"),
    player: z.string().optional().describe("Player name (omit for all online players)"),
    inventory: z.boolean().optional().describe("Include full inventory contents (default false, can be large)"),
    styled: z.boolean().optional().describe("If true, also include `name_styled` and `lore_styled` fields on items containing &-codes (e.g. `&aDiamond`). Default false (plain text only)."),
  },
  async ({ server: serverId, player, inventory, styled }) =>
    handleApi(() => getPlayerInfo(serverId, player, inventory, styled))
);

server.tool(
  "get_open_inventory",
  "Read the inventory/GUI a player currently has open (chest, plugin GUI, anvil, etc.). Returns the type, title, size, and the contents of every non-empty slot with material, amount, display name, lore, and enchantments. Use this to verify GUI layouts you've built (e.g. /buy menus, custom shop UIs) without screenshots, or to see what slot a player has selected. " +
    "If the player has no GUI open (just their own inventory via E), `open` is `false` and `type` is `crafting`. For the player's own inventory contents, use `get_player_info` with `inventory: true` instead. Read-only, safe under Player safety rules.",
  {
    server: z.string().describe("Server ID (get this from list_servers)"),
    player: z.string().describe("Exact name of an ONLINE player. Errors if the player is offline."),
    styled: z.boolean().optional().describe("If true, also include `name_styled`, `lore_styled`, and `title_styled` fields with &-codes preserved (e.g. `&aDiamond`). Useful for verifying color-meaningful GUIs (red=locked, green=available). Default false."),
  },
  async ({ server: serverId, player, styled }) =>
    handleApi(() => getOpenInventory(serverId, player, styled))
);

// ── GUI Designer tools ──────────────────────────────────────────────

server.tool(
  "gui_open",
  "Open a GUI design canvas for a player. Items are freely movable — this is a collaborative workspace, not a functional GUI. " +
    "The player can rearrange items in-game, and you can read/modify the layout with gui_read and gui_set. " +
    "The session persists even if the player closes the inventory (they can reopen with /mcclaude gui). " +
    "Use gui_close to end the session and get the final layout. Subject to Player safety rules.",
  {
    server: z.string().describe("Server ID"),
    player: z.string().describe("Online player to open the design GUI for"),
    type: z.string().optional().describe("Inventory type (default 'chest'). Options: chest (rows×9 slots), hopper (5), dispenser/dropper (9), furnace/blast_furnace/smoker (3), anvil (3), brewing (5), enchanting (2), workbench (10), beacon (1), grindstone (3), smithing (3), stonecutter (2), cartography (3), loom (4)"),
    rows: z.number().min(1).max(6).optional().describe("Number of rows for chest type (default 3). Ignored for hopper/dispenser."),
    title: z.string().describe("GUI title with &-codes (e.g. '&6Shop Menu')"),
    slots: z.string().optional().describe("JSON array of initial slot objects: [{slot: number, type: string, amount?: number, name?: string, name_styled?: string, lore?: string[], lore_styled?: string[], enchantments?: {name: level}}]. Same format as gui_read output."),
  },
  async ({ server: serverId, player, type, rows, title, slots }) => {
    const parsedSlots = slots ? JSON.parse(slots) : undefined;
    return handleApi(() => guiOpen(serverId, player, type, rows, title, parsedSlots));
  }
);

server.tool(
  "gui_read",
  "Read the current state of a player's design GUI. Returns all non-empty slots with type, name, name_styled (&-codes), lore, lore_styled, and enchantments. " +
    "Also records a snapshot — if the player modifies the GUI after this read, gui_set will detect the conflict and require you to re-read. " +
    "Think of this as the Read tool for GUI design.",
  {
    server: z.string().describe("Server ID"),
    player: z.string().describe("Player with an active design session"),
  },
  async ({ server: serverId, player }) =>
    handleApi(() => guiRead(serverId, player))
);

server.tool(
  "gui_set",
  "Set specific slots in a player's design GUI (incremental — unspecified slots are untouched). " +
    "CONFLICT DETECTION: if the player modified the GUI since your last gui_read, this returns {conflict: true} with the current state instead of applying changes. You must gui_read first to acknowledge the player's changes. " +
    "Think of this as the Edit tool for GUI design. Set type to 'air' to clear a slot. " +
    "Item format is the same as gui_read output — you can take items from a read and pass them back with modifications.",
  {
    server: z.string().describe("Server ID"),
    player: z.string().describe("Player with an active design session"),
    slots: z.string().describe("JSON array of slot objects to set: [{slot: number, type: string, amount?: number, name?: string, name_styled?: string, lore?: string[], lore_styled?: string[], enchantments?: {name: level}}]. Only specified slots change."),
  },
  async ({ server: serverId, player, slots }) => {
    const parsedSlots = JSON.parse(slots);
    return handleApi(() => guiSet(serverId, player, parsedSlots));
  }
);

server.tool(
  "gui_close",
  "End a design session and return the final layout. Closes the GUI for the player. " +
    "The returned layout contains all slot data — use it to write the final implementation (Skript script, Java plugin code, YAML config, etc.).",
  {
    server: z.string().describe("Server ID"),
    player: z.string().describe("Player with an active design session"),
  },
  async ({ server: serverId, player }) =>
    handleApi(() => guiClose(serverId, player))
);

// ── Skript tools ───────────────────────────────────────────────────

server.tool(
  "skript_eval",
  "Execute Skript effect(s) on the server in real-time. Only works if Skript is installed. Use for testing Skript code, debugging, or running one-off effects. " +
    "Pass `code` for a single effect, or `codes` for a batch of effects executed sequentially (local variables and imports persist within the batch). Each code string must be a single effect — no newlines outside of quoted strings.",
  {
    server: z.string().describe("Server ID (get this from list_servers)"),
    code: z.string().optional().describe("Single Skript effect to execute (e.g. 'send \"hello\" to all players')"),
    codes: z.array(z.string()).optional().describe("Batch of Skript effects to execute sequentially. Local variables persist across the batch (e.g. ['set {_x} to 5', 'send \"%{_x}%\"'])"),
  },
  async ({ server: serverId, code, codes }) => {
    if (!code && (!codes || codes.length === 0)) {
      return errorResult("Provide either `code` (single) or `codes` (batch)");
    }
    const toValidate = codes ?? [code!];
    for (let i = 0; i < toValidate.length; i++) {
      if (hasUnquotedNewlines(toValidate[i])) {
        const label = codes ? `codes[${i}]` : "code";
        return errorResult(
          `${label} contains a newline outside of quotes. Each effect must be a single line. ` +
          `Split into separate strings: ${JSON.stringify(toValidate[i].split("\n"))}`
        );
      }
    }
    if (codes) {
      return handleApi(() => skriptEval(serverId, undefined, codes));
    }
    return handleApi(() => skriptEval(serverId, code!));
  }
);

server.tool(
  "search_skript_syntax",
  "Search the SkriptHub syntax database for Skript effects, expressions, conditions, events, etc. Use this to find the correct syntax for Skript scripts. Does NOT require a server connection — queries SkriptHub API directly.",
  {
    query: z.string().describe("Search query (e.g. 'teleport', 'send message', 'on join')"),
    addon: z.string().optional().describe("Filter by addon name (e.g. 'Skript', 'skript-reflect')"),
    type: z.string().optional().describe("Filter by syntax type: 'effect', 'expression', 'condition', 'event'"),
    limit: z.number().optional().describe("Max results (default 20)"),
  },
  async ({ query, addon, type, limit }) =>
    handleApi(() => searchSkriptSyntax(query, addon, type, limit))
);

// ── Start ───────────────────────────────────────────────────────────

async function main() {
  const transport = new StdioServerTransport();
  console.error("[mcclaude-mcp] Starting MCP server...");
  await server.connect(transport);
  console.error("[mcclaude-mcp] Server connected and ready.");
}

main().catch((err) => {
  console.error("[mcclaude-mcp] Fatal error:", err);
  process.exit(1);
});
