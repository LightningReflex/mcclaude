# McClaude

Let Claude Code develop on a live Minecraft server.

McClaude bridges Claude Code and a running Minecraft server so Claude can read the console, run commands, and edit plugin files like Skript scripts in real time, without ever leaving the IDE.

## What Claude can do

| Tool | Purpose |
|------|---------|
| `list_servers` | Get all servers reachable with the current token |
| `read_console` | Read recent console output (50 lines by default) |
| `send_command` | Execute a server command as console (no leading slash) |
| `send_command_as` | Execute a command as a specific online player (proper sender, captures console output — chat-only output is not captured) |
| `get_server_info` | Live TPS, MOTD, player count, version |
| `list_plugins` | Installed plugins with versions |
| `get_player_info` | Player details: health, location, gamemode, armor, effects (optional full inventory; optional `styled` for `&`-coded names/lore) |
| `get_open_inventory` | Read the GUI/inventory a player currently has open (slots, items, title) — useful for verifying custom GUIs without screenshots |
| `skript_eval` | Run Skript effect(s) — single (`code`) or batch (`codes` array, local variables persist across the batch). Validates no unquoted newlines before sending. Captures `send`/`broadcast` output directly (Skript only) |
| `search_skript_syntax` | Query SkriptHub for Skript syntax (no server needed) |

In addition to these tools, the user client mounts the server's filesystem as a Windows drive, so Claude reads and edits plugin files (`.sk`, `.yml`, etc.) using its normal Read/Write tools. Each server's mounted root has a virtual `CLAUDE.md` that describes the installed plugins (Skript, skript-reflect, skript-gui, Citizens, PlaceholderAPI, TAB, FancyHolograms, LuckPerms, Vault, etc.) and how to work with them.

## Prerequisites

- **Windows** for the user client. The drive-mount layer uses Windows-only APIs (`WNetAddConnection2W`). The MCP server, intermediate server, and Minecraft plugin all run anywhere; only the local mount on the developer's machine is Windows-restricted.
- **Java 21+** to build and run the Minecraft plugin.
- **Paper 1.21+** as the server platform.
- **Node.js 20+** to build and run the intermediate server and MCP server.
- **Python 3.10+** to run the user client (or rebuild the bundle).

## Setup

### 1. Build and host the intermediate server

```bash
cd intermediate-server
npm install
npm run build
npm start          # listens on :3000 by default
```

Or deploy to a Node.js host (Pterodactyl, Render, Heroku, etc.). The repo root has a thin `package.json` whose `postinstall` builds `intermediate-server/` and whose `start` runs it. So `git clone` + `npm install` + `npm start` is enough. On Pterodactyl with a generic Node.js egg, enable auto-update on the egg and it'll re-pull + rebuild on every restart.

### 2. Build and install the Minecraft plugin

```bash
cd minecraft-plugin
./gradlew shadowJar
```

Drop `build/libs/McClaude.jar` into your server's `plugins/` folder. On first start it generates `plugins/McClaude/config.yml`. Set the `server-url`, `server-name`, and a fresh `server-token` (any UUID).

### 3. Run the user client

End users only need `dist/mcclaude.py` and `dist/requirements.txt`:

```bash
pip install -r requirements.txt
python mcclaude.py
```

The TUI walks through setup (server URL + token), installs the MCP server into Claude Code's config, and offers a Mount button to map the live server filesystem to a Windows drive (default `M:\`).

To rebuild the bundle yourself: `python build.py` from the repo root.

## Architecture

```
                              +--------------------------+
Claude Code (IDE) --stdio---> |     MCP Server (TS)      |
                              |  read_console,           |
                              |  send_command,           |
                              |  skript_eval, etc.       |
                              +------------+-------------+
                                           | HTTPS (encrypted RPC)
                                           v
                              +--------------------------+
User Client (Python)          |   Intermediate Server    |
mounts files as a   --HTTPS-> |        (Node.js)         |
Windows drive (M:\)           |  Blind encrypted relay   |
                              +------------+-------------+
                                           | WebSocket
                                           v
                              +--------------------------+
                              |    Minecraft Plugin      |
                              |  (Paper 1.21+, Java 21)  |
                              +--------------------------+
```

Everything between the user client / MCP server and the Minecraft plugin is end-to-end encrypted with AES-256-GCM. The intermediate server only sees opaque encrypted blobs and a SHA-256 token hash, so it cannot decrypt any traffic.

## Components

| Folder | Language | Role |
|--------|----------|------|
| `minecraft-plugin/` | Java 21 (Paper 1.21+) | Plugin that runs on the MC server. Captures console, executes commands, serves files, evaluates Skript |
| `intermediate-server/` | TypeScript / Node.js | Stateless blind relay. Routes encrypted RPC requests between clients and plugins |
| `mcp-server/` | TypeScript / Node.js | MCP server that exposes tools to Claude Code over stdio |
| `user-client/` | Python (Textual TUI) | Setup TUI + WebDAV provider that mounts the server filesystem as a Windows drive |
| `build.py` | Python | Bundles everything into a single portable `dist/mcclaude.py` |

## Security model

- The user types a token once; the client stores it in `%APPDATA%/mcclaude/config.json`
- Clients send `SHA-256(token)` as `X-Token-Hash` to authenticate with the relay, so the raw token never leaves the user's machine
- The encryption key is derived from `SHA-256(token)`. The Minecraft plugin holds the same token in its config, so both sides can encrypt/decrypt
- The intermediate server stores nothing. It routes opaque encrypted blobs by token hash

## Development

```bash
# Plugin
cd minecraft-plugin && ./gradlew shadowJar

# MCP server (dev)
cd mcp-server && npm install && npm run dev

# Intermediate server (dev)
cd intermediate-server && npm install && npm run dev

# Bundle the user client
python build.py
```

## A note on AI assistance

McClaude was built with substantial help from Claude (the AI). Most of the code was AI-generated and then iterated on. If you spot something weird, awkwardly named, or over-engineered, that's likely why.

## Contributing

PRs are welcome. Whether it's bug fixes, new MCP tools, plugin compatibility notes, deployment guides, or refactoring questionable AI choices, open an issue or send a pull request.

## License

MIT, see `LICENSE`.
