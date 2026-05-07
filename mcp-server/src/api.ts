// Try loading .env for dev mode, skip if not available (bundled mode)
try {
  const { config } = await import("dotenv");
  const { fileURLToPath } = await import("url");
  const { dirname, resolve } = await import("path");
  const __filename = fileURLToPath(import.meta.url);
  const __dirname = dirname(__filename);
  config({ path: resolve(__dirname, "..", ".env"), quiet: true });
} catch {
  // No dotenv — env vars come from Claude Code's config
}

import { createHash } from "crypto";
import { encryptRaw, decryptRaw } from "./crypto.js";

const API_URL = (process.env.MCCLAUDE_URL || process.env.MCCLAUDE_API_URL || "http://localhost:3000").replace(
  /\/$/,
  ""
);
export const TOKEN = process.env.MCCLAUDE_TOKEN || process.env.MCCLAUDE_API_KEY || "";
const TOKEN_HASH = TOKEN
  ? createHash("sha256").update(TOKEN).digest("hex")
  : "";

if (!TOKEN) {
  console.error(
    "[mcclaude-mcp] Warning: MCCLAUDE_TOKEN is not set. Requests will fail."
  );
}

interface ApiResponse {
  ok: boolean;
  status: number;
  data: unknown;
}

async function request(
  method: string,
  path: string,
  body?: Record<string, unknown>
): Promise<ApiResponse> {
  const url = `${API_URL}${path}`;

  const headers: Record<string, string> = {
    "X-Token-Hash": TOKEN_HASH,
    "Content-Type": "application/json",
  };

  const init: RequestInit = { method, headers };
  if (body !== undefined) {
    init.body = JSON.stringify(body);
  }

  const res = await fetch(url, init);
  const text = await res.text();

  let data: unknown;
  try {
    data = JSON.parse(text);
  } catch {
    data = text;
  }

  return { ok: res.ok, status: res.status, data };
}

async function rpc(server: string, rpcRequest: Record<string, unknown>): Promise<ApiResponse> {
  const payload = Buffer.from(JSON.stringify(rpcRequest));
  const encrypted = encryptRaw(TOKEN, payload);

  const res = await fetch(`${API_URL}/api/servers/${server}/rpc`, {
    method: "POST",
    headers: {
      "Content-Type": "application/octet-stream",
      "X-Token-Hash": TOKEN_HASH,
    },
    body: new Uint8Array(encrypted),
  });

  if (!res.ok) {
    return { ok: false, status: res.status, data: await res.json().catch(() => null) };
  }

  const responseBytes = Buffer.from(await res.arrayBuffer());
  const decrypted = decryptRaw(TOKEN, responseBytes);
  const result = JSON.parse(decrypted.toString());

  if (result.error) {
    return { ok: false, status: 502, data: { error: result.error } };
  }

  return { ok: true, status: 200, data: result.result ?? result };
}

// ── Public API methods ──────────────────────────────────────────────

export async function listServers(): Promise<ApiResponse> {
  return request("GET", "/api/servers");
}

export async function readConsole(
  server: string,
  lines?: number,
  filter?: string
): Promise<ApiResponse> {
  const data: Record<string, unknown> = {};
  if (lines !== undefined) data.lines = lines;
  if (filter !== undefined) data.filter = filter;
  return rpc(server, { type: "console_read", data });
}

export async function sendCommand(
  server: string,
  command: string
): Promise<ApiResponse> {
  return rpc(server, { type: "command", data: { command } });
}

export async function getServerInfo(server: string): Promise<ApiResponse> {
  return rpc(server, { type: "server_info", data: {} });
}

export async function listPlugins(server: string): Promise<ApiResponse> {
  return rpc(server, { type: "plugins_list", data: {} });
}

export async function getPlayerInfo(server: string, player?: string, inventory?: boolean): Promise<ApiResponse> {
  const data: Record<string, unknown> = {};
  if (player) data.player = player;
  if (inventory) data.inventory = true;
  return rpc(server, { type: "player_info", data });
}

export async function skriptEval(server: string, code: string): Promise<ApiResponse> {
  return rpc(server, { type: "skript_eval", data: { code } });
}

// ── SkriptHub syntax API (local, no MC server needed) ──────────────

interface SkriptSyntax {
  id: number;
  title: string;
  description: string;
  syntax_pattern: string;
  syntax_type: string;
  return_type: string | null;
  addon: { name: string };
}

let skriptSyntaxCache: SkriptSyntax[] | null = null;
let skriptSyntaxFetchedAt = 0;
const SKRIPT_CACHE_TTL = 3600000; // 1 hour

async function fetchSkriptSyntax(): Promise<SkriptSyntax[]> {
  if (skriptSyntaxCache && Date.now() - skriptSyntaxFetchedAt < SKRIPT_CACHE_TTL) {
    return skriptSyntaxCache;
  }
  const res = await fetch("https://skripthub.net/api/v1/addonsyntaxlist/", {
    headers: { Accept: "application/json" },
  });
  if (!res.ok) throw new Error(`SkriptHub API error: ${res.status}`);
  skriptSyntaxCache = await res.json() as SkriptSyntax[];
  skriptSyntaxFetchedAt = Date.now();
  return skriptSyntaxCache;
}

export async function searchSkriptSyntax(
  query: string,
  addonFilter?: string,
  typeFilter?: string,
  limit = 20,
): Promise<ApiResponse> {
  try {
    const all = await fetchSkriptSyntax();
    const lower = query.toLowerCase();
    let results = all.filter((s) => {
      const matches =
        s.title?.toLowerCase().includes(lower) ||
        s.syntax_pattern?.toLowerCase().includes(lower) ||
        s.description?.toLowerCase().includes(lower);
      if (!matches) return false;
      if (addonFilter && s.addon?.name?.toLowerCase() !== addonFilter.toLowerCase()) return false;
      if (typeFilter && s.syntax_type?.toLowerCase() !== typeFilter.toLowerCase()) return false;
      return true;
    });
    results = results.slice(0, limit);
    const data = results.map((s) => ({
      title: s.title,
      pattern: s.syntax_pattern?.replace(/\\r\\n/g, "\n"),
      type: s.syntax_type,
      addon: s.addon?.name,
      description: s.description?.slice(0, 200),
      return_type: s.return_type,
    }));
    return { ok: true, status: 200, data: { results: data, total: results.length } };
  } catch (err: any) {
    return { ok: false, status: 502, data: { error: err.message } };
  }
}
