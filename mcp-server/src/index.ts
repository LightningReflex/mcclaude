#!/usr/bin/env node

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import {
  listServers,
  readConsole,
  sendCommand,
  getServerInfo,
  listPlugins,
  getPlayerInfo,
  searchSkriptSyntax,
  skriptEval,
} from "./api.js";

// ── Helpers ─────────────────────────────────────────────────────────

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
  },
  async ({ server: serverId, player, inventory }) =>
    handleApi(() => getPlayerInfo(serverId, player, inventory))
);

server.tool(
  "skript_eval",
  "Execute a Skript effect/expression on the server in real-time. Only works if Skript is installed. Use for testing Skript code, debugging, or running one-off effects.",
  {
    server: z.string().describe("Server ID (get this from list_servers)"),
    code: z.string().describe("Skript code to execute (e.g. 'send \"hello\" to all players', 'set {test} to 5')"),
  },
  async ({ server: serverId, code }) =>
    handleApi(() => skriptEval(serverId, code))
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
