import "dotenv/config";
import express from "express";
import helmet from "helmet";
import { createServer } from "http";
import { WebSocketServer, WebSocket } from "ws";
import { URL } from "url";
import { v4 as uuidv4, v5 as uuidv5 } from "uuid";
import { Logger } from "tslog";

// ── Logger ─────────────────────────────────────────────────────────

const log = new Logger({
  name: "McClaude",
  prettyLogTemplate: "{{dateIsoStr}} {{logLevelName}}\t{{name}} ",
  prettyLogTimeZone: "local",
  minLevel: parseInt(process.env.LOG_LEVEL || "3"), // 0=silly,1=trace,2=debug,3=info,4=warn,5=error
});

// ── Types ──────────────────────────────────────────────────────────

interface PluginConnection {
  ws: WebSocket;
  serverName: string;
  tokenHash: string;
  connectedAt: Date;
  pendingRequests: Map<string, PendingRequest>;
  activeStream: { id: string; chunks: Buffer[] } | null;
}

interface PendingRequest {
  resolve: (value: any) => void;
  reject: (reason: any) => void;
  timer: ReturnType<typeof setTimeout>;
}

// ── State ──────────────────────────────────────────────────────────

const plugins = new Map<string, PluginConnection>();
const REQUEST_TIMEOUT = 30000;

// ── Express app ────────────────────────────────────────────────────

const app = express();
const PORT = parseInt(process.env.PORT || "3000", 10);

app.use(helmet());
app.use(express.raw({ type: "application/octet-stream", limit: "200mb" }));
app.use(express.json({ limit: "200mb" }));

app.get("/health", (_req, res) => {
  res.json({ status: "ok", timestamp: new Date().toISOString(), plugins: plugins.size });
});

function requireToken(req: express.Request, res: express.Response, next: express.NextFunction) {
  const tokenHash = req.headers["x-token-hash"] as string;
  if (!tokenHash) {
    log.warn("Request without X-Token-Hash from", req.ip);
    res.status(401).json({ error: "Missing X-Token-Hash" });
    return;
  }
  (req as any).tokenHash = tokenHash;
  next();
}

function getPluginOrFail(req: express.Request, res: express.Response): PluginConnection | null {
  const id = String(req.params.id ?? "");
  const plugin = plugins.get(id);
  if (!plugin || plugin.tokenHash !== (req as any).tokenHash) {
    log.warn("Server not found:", id.slice(0, 8));
    res.status(404).json({ error: "Server not found" });
    return null;
  }
  if (plugin.ws.readyState !== WebSocket.OPEN) {
    log.warn("Server offline:", plugin.serverName);
    res.status(503).json({ error: "Server offline" });
    return null;
  }
  return plugin;
}

// ── Routes ─────────────────────────────────────────────────────────

app.get("/api/servers", requireToken, (req, res) => {
  const tokenHash = (req as any).tokenHash;
  const result: any[] = [];
  for (const [id, p] of plugins.entries()) {
    if (p.tokenHash === tokenHash) {
      result.push({ id, name: p.serverName, online: p.ws.readyState === WebSocket.OPEN });
    }
  }
  log.debug("List servers:", result.length, "found for token", tokenHash.slice(0, 8));
  res.json({ servers: result });
});

// Console read now goes through RPC — no server-side storage needed

app.post("/api/servers/:id/rpc", requireToken, async (req, res) => {
  const plugin = getPluginOrFail(req, res);
  if (!plugin) return;

  const requestId = uuidv4();
  const body = req.body as Buffer;

  log.debug("RPC request:", requestId.slice(0, 8), "→", plugin.serverName, `(${body.length} bytes)`);

  try {
    const response = await new Promise<{ binary?: Buffer; json?: any }>((resolve, reject) => {
      const timer = setTimeout(() => {
        plugin.pendingRequests.delete(requestId);
        log.warn("RPC timeout:", requestId.slice(0, 8), "for", plugin.serverName);
        reject(new Error("Request timed out"));
      }, REQUEST_TIMEOUT);

      plugin.pendingRequests.set(requestId, { resolve, reject, timer });

      plugin.ws.send(JSON.stringify({ id: requestId, type: "rpc" }));
      plugin.ws.send(body);
    });

    if (response.binary) {
      log.debug("RPC response:", requestId.slice(0, 8), `binary (${response.binary.length} bytes)`);
      res.setHeader("Content-Type", "application/octet-stream");
      res.send(response.binary);
    } else {
      log.debug("RPC response:", requestId.slice(0, 8), "json");
      res.json(response.json || response);
    }
  } catch (err: any) {
    log.error("RPC error:", requestId.slice(0, 8), err.message);
    res.status(502).json({ error: err.message });
  }
});

app.use((_req, res) => { res.status(404).json({ error: "Not found" }); });

// ── WebSocket server ───────────────────────────────────────────────

const httpServer = createServer(app);
const wss = new WebSocketServer({ noServer: true, maxPayload: 200 * 1024 * 1024 });

httpServer.on("upgrade", (request, socket, head) => {
  const url = new URL(request.url || "", `http://${request.headers.host}`);
  if (url.pathname === "/ws/plugin") {
    const tokenHash = url.searchParams.get("token_hash");
    const name = url.searchParams.get("name") || "unnamed";
    if (!tokenHash) {
      log.warn("Plugin connection rejected: no token_hash");
      socket.write("HTTP/1.1 401\r\n\r\n");
      socket.destroy();
      return;
    }
    wss.handleUpgrade(request, socket, head, (ws) => {
      wss.emit("connection", ws, tokenHash, name);
    });
  } else {
    socket.write("HTTP/1.1 404\r\n\r\n");
    socket.destroy();
  }
});

wss.on("connection", (ws: WebSocket, tokenHash: string, serverName: string) => {
  // Deterministic ID: same token + name = same ID across reconnects
  const MCCLAUDE_NS = "6ba7b810-9dad-11d1-80b4-00c04fd430c8";
  const id = uuidv5(tokenHash + ":" + serverName, MCCLAUDE_NS);
  const plugin: PluginConnection = {
    ws, serverName, tokenHash,
    connectedAt: new Date(),
    pendingRequests: new Map(),
    activeStream: null,
  };

  plugins.set(id, plugin);
  log.info("Plugin connected:", serverName, `(${id.slice(0, 8)}, token: ${tokenHash.slice(0, 8)})`);

  ws.on("message", (raw, isBinary) => {
    if (isBinary) {
      if (plugin.activeStream) {
        plugin.activeStream.chunks.push(raw as Buffer);
        log.trace("Binary chunk:", plugin.activeStream.id.slice(0, 8), `(${(raw as Buffer).length} bytes)`);
      }
      return;
    }

    try {
      const msg = JSON.parse(raw.toString());

      if (msg.stream === "start" && msg.id) {
        log.debug("Stream start:", msg.id.slice(0, 8), "from", serverName);
        plugin.activeStream = { id: msg.id, chunks: [] };
        return;
      }

      if (msg.stream === "end" && msg.id) {
        const pending = plugin.pendingRequests.get(msg.id);
        const stream = plugin.activeStream;
        if (pending && stream && stream.id === msg.id) {
          const totalSize = stream.chunks.reduce((s, c) => s + c.length, 0);
          log.debug("Stream end:", msg.id.slice(0, 8), `(${totalSize} bytes total)`);
          plugin.pendingRequests.delete(msg.id);
          clearTimeout(pending.timer);
          pending.resolve({ binary: Buffer.concat(stream.chunks) });
        }
        plugin.activeStream = null;
        return;
      }

      if (msg.id && plugin.pendingRequests.has(msg.id)) {
        const pending = plugin.pendingRequests.get(msg.id)!;
        plugin.pendingRequests.delete(msg.id);
        clearTimeout(pending.timer);
        if (msg.error) {
          log.warn("Plugin error:", msg.id.slice(0, 8), msg.error);
          pending.reject(new Error(msg.error));
        } else {
          log.debug("Plugin response:", msg.id.slice(0, 8));
          pending.resolve({ json: msg.result ?? msg });
        }
      }
    } catch { /* ignore */ }
  });

  ws.on("close", () => {
    for (const [, p] of plugin.pendingRequests) {
      clearTimeout(p.timer);
      p.reject(new Error("Connection closed"));
    }
    plugins.delete(id);
    log.info("Plugin disconnected:", serverName);
  });

  ws.on("error", (err) => {
    log.error("WebSocket error:", serverName, err.message);
  });
});

// ── Start ──────────────────────────────────────────────────────────

httpServer.listen(PORT, () => {
  log.info(`Listening on port ${PORT}`);
  log.info("Blind encrypted relay — server sees nothing");
  log.info(`Log level: ${process.env.LOG_LEVEL || "3 (info)"} — set LOG_LEVEL=2 for debug`);
});
