"""
McClaude TUI — Terminal User Interface for managing Minecraft servers.

Replaces the old CLI. Launch with: python -m mcclaude.tui
"""

from __future__ import annotations

import hashlib
import json
import os
import threading
import time
from pathlib import Path

from textual.app import App, ComposeResult
from textual.containers import Container, Horizontal
from textual.widgets import Header, Footer, Static, Button, Input, Label, DataTable
from textual.screen import Screen, ModalScreen
from textual import work

from api import McclaudeAPI, ApiError
from config import load_config, save_config, CONFIG_DIR, CONFIG_FILE
from drive import McclaudeDAVProvider, DAV_PORT, _win_mount, _win_unmount, _ensure_webclient_running
from wsgidav.wsgidav_app import WsgiDAVApp
from cheroot.wsgi import Server as CherootServer


# ── MCP Server Installation ────────────────────────────────────────

def _get_mcp_js_path() -> Path:
    """Path where the MCP server JS lives."""
    return CONFIG_DIR / "mcp-server.js"


def _find_mcp_source() -> Path | None:
    """Find the MCP server source during development."""
    # Monorepo layout
    dev_path = Path(__file__).resolve().parent.parent.parent / "mcp-server" / "dist" / "index.js"
    if dev_path.exists():
        return dev_path
    # Bundled (embedded in this file as _MCP_SERVER_JS)
    return None


def install_mcp_server(token: str, api_url: str) -> str:
    """Install MCP server into Claude Code config. Returns status message."""
    js_path = _get_mcp_js_path()

    # Copy JS from dev source if available
    source = _find_mcp_source()
    if source:
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        js_path.write_bytes(source.read_bytes())
    elif hasattr(install_mcp_server, "_embedded_js"):
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        # write_bytes (not write_text) so Windows doesn't translate \n -> \r\n,
        # which would make the hash check think we're always out of date.
        js_path.write_bytes(install_mcp_server._embedded_js.encode("utf-8"))
    elif not js_path.exists():
        return "MCP server JS not found"

    # Update ~/.claude.json
    claude_path = Path.home() / ".claude.json"
    try:
        if claude_path.exists():
            config = json.loads(claude_path.read_text(encoding="utf-8"))
        else:
            config = {}

        if "mcpServers" not in config:
            config["mcpServers"] = {}

        config["mcpServers"]["mcclaude"] = {
            "type": "stdio",
            "command": "node",
            "args": [str(js_path)],
            "env": {
                "MCCLAUDE_URL": api_url,
                "MCCLAUDE_TOKEN": token,
            },
        }

        claude_path.write_text(json.dumps(config, indent=2), encoding="utf-8")
        return f"Installed at {js_path}"
    except Exception as e:
        return f"Failed: {e}"


# ── Setup Screen ───────────────────────────────────────────────────

class SetupScreen(Screen):
    """First-run setup — enter token and server URL."""

    CSS = """
    SetupScreen { align: center middle; }
    #setup-box {
        width: 64;
        height: auto;
        border: solid $accent;
        padding: 2 4;
        background: $surface;
    }
    .setup-label { margin: 1 0 0 0; color: $text-muted; }
    .setup-input { margin: 0 0 1 0; }
    #setup-title { text-align: center; margin-bottom: 1; }
    #setup-status { margin-top: 1; min-height: 1; }
    #setup-buttons { margin-top: 1; }
    #setup-buttons Button { margin-right: 1; }
    """

    def compose(self) -> ComposeResult:
        with Container(id="setup-box"):
            yield Static("[b]McClaude[/b]", id="setup-title")
            yield Label("Server URL", classes="setup-label")
            yield Input(value="http://localhost:3000", id="url-input", classes="setup-input")
            yield Label("Token (from plugins/McClaude/config.yml)", classes="setup-label")
            yield Input(placeholder="paste token here", id="token-input", classes="setup-input")
            with Horizontal(id="setup-buttons"):
                yield Button("Connect", classes="btn-primary",id="connect-btn")
                yield Button("Quit", classes="btn-quit",id="quit-btn")
            yield Static("", id="setup-status")

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "quit-btn":
            self.app.exit()
        elif event.button.id == "connect-btn":
            self.run_setup()

    @work(thread=True)
    def run_setup(self) -> None:
        url = self.query_one("#url-input", Input).value.strip()
        token = self.query_one("#token-input", Input).value.strip()
        status = self.query_one("#setup-status", Static)

        if not url or not token:
            self.app.call_from_thread(status.update, "[red]Both fields are required[/red]")
            return

        self.app.call_from_thread(status.update, "Connecting...")

        try:
            api = McclaudeAPI(url, token)
            servers = api.list_servers()
        except ApiError as e:
            self.app.call_from_thread(status.update, f"[red]{e}[/red]")
            return
        except Exception as e:
            self.app.call_from_thread(status.update, f"[red]Connection failed: {e}[/red]")
            return

        self.app.call_from_thread(status.update, f"Found {len(servers)} server(s). Installing MCP...")

        msg = install_mcp_server(token, url)
        save_config({"api_url": url, "token": token})

        self.app.call_from_thread(status.update, f"[green]Ready! {len(servers)} server(s). MCP: {msg}[/green]")
        time.sleep(1.5)
        def go_main():
            try:
                self.app.uninstall_screen("main")
            except KeyError:
                pass
            self.app.install_screen(MainScreen(), "main")
            self.app.switch_screen("main")
        self.app.call_from_thread(go_main)


# ── Main Screen ────────────────────────────────────────────────────

class MainScreen(Screen):
    """Dashboard — server list, mount controls, status."""

    CSS = """
    #server-table { height: 1fr; margin: 1 2; }
    .btn-row { height: auto; margin: 0 1; padding: 0 0 1 0; }
    .btn-row Button { width: 1fr; margin: 0 1; }
    #status { dock: bottom; height: 1; background: $surface; padding: 0 2; }
    """

    _mounted: bool = False
    _mounting: bool = False
    _cancelled: bool = False
    _cheroot: object = None

    def compose(self) -> ComposeResult:
        yield Header(show_clock=True)
        yield DataTable(id="server-table")
        with Horizontal(classes="btn-row"):
            yield Button("Mount (M:)", id="mount-btn", classes="btn-blue")
            yield Button("Refresh", id="refresh-btn")
            yield Button("Settings", id="settings-btn")
        with Horizontal(classes="btn-row"):
            yield Button("Repair MCP", id="repair-btn")
            yield Button("Reset", id="reset-btn", classes="btn-pink")
            yield Button("Quit", id="quit-btn", classes="btn-quit")
        yield Static("Loading...", id="status")
        yield Footer()

    def on_mount(self) -> None:
        table = self.query_one(DataTable)
        table.add_columns("Name", "Status", "ID")
        self.refresh_servers()
        self.set_interval(5, self.refresh_servers)
        self.check_mcp_update()

    @work(thread=True)
    def refresh_servers(self) -> None:
        cfg = load_config()
        if not cfg or not cfg.get("token"):
            return

        try:
            api = McclaudeAPI(cfg["api_url"], cfg["token"])
            servers = api.list_servers()
        except Exception:
            self.app.call_from_thread(
                self.query_one("#status", Static).update,
                "[red]Connection failed — check server[/red]"
            )
            return

        def update():
            table = self.query_one(DataTable)
            table.clear()
            for s in servers:
                st = "[green]online[/green]" if s.get("online") else "[red]offline[/red]"
                table.add_row(s.get("name", "?"), st, s.get("id", "?")[:12])

            mount = "[green]M:\\ mounted[/green]" if self._mounted else "[dim]not mounted[/dim]"
            token_preview = cfg["token"][:8] + "..."
            self.query_one("#status", Static).update(
                f" {len(servers)} server(s) | {mount} | token: {token_preview}"
            )

        self.app.call_from_thread(update)

    def on_button_pressed(self, event: Button.Pressed) -> None:
        bid = event.button.id
        if bid == "quit-btn":
            self.app.action_quit()
        elif bid == "mount-btn":
            if self._mounted:
                self._do_unmount()
                btn = self.query_one("#mount-btn", Button)
                btn.label = "Mount (M:)"
                btn.remove_class("btn-yellow")
                btn.add_class("btn-blue")
                self.query_one("#status", Static).update("Unmounted.")
            else:
                self.do_mount()
        elif bid == "refresh-btn":
            self.refresh_servers()
        elif bid == "settings-btn":
            self.app.push_screen(SettingsScreen())
        elif bid == "repair-btn":
            self.do_repair()
        elif bid == "reset-btn":
            self._cancelled = True
            if self._mounted:
                self._do_unmount()
            CONFIG_FILE.unlink(missing_ok=True)
            try:
                self.app.uninstall_screen("setup")
            except KeyError:
                pass
            self.app.install_screen(SetupScreen(), "setup")
            self.app.switch_screen("setup")

    @work(thread=True)
    def do_mount(self) -> None:
        # Disable button immediately to prevent spam
        def disable_mount():
            self.query_one("#mount-btn", Button).disabled = True
        self.app.call_from_thread(disable_mount)

        self._mounting = True

        def _re_enable_mount():
            self._mounting = False
            def reset_btn():
                btn = self.query_one("#mount-btn", Button)
                btn.label = "Mount (M:)"
                btn.disabled = False
                btn.remove_class("btn-yellow")
                btn.add_class("btn-blue")
            self.app.call_from_thread(reset_btn)

        cfg = load_config()
        if not cfg:
            self._set_status("[red]No config found[/red]")
            _re_enable_mount()
            return

        self._set_status("Starting WebDAV server...")

        try:
            api = McclaudeAPI(cfg["api_url"], cfg["token"])
            servers = api.list_servers()
            self._set_status(f"Connected ({len(servers)} servers). Starting WebDAV...")
        except Exception as e:
            self._set_status(f"[red]Cannot connect to server: {e}[/red]")
            _re_enable_mount()
            return

        try:
            provider = McclaudeDAVProvider(api)
            dav_config = {
                "provider_mapping": {"/": provider},
                "host": "127.0.0.1", "port": DAV_PORT,
                "verbose": 0, "logging": {"enable": False},
                "dir_browser": {"enable": False},
                "http_authenticator": {
                    "domain_controller": None,
                    "accept_basic": False,
                    "accept_digest": False,
                },
                "simple_dc": {"user_mapping": {"*": True}},
            }

            app = WsgiDAVApp(dav_config)
            self._cheroot = CherootServer(("127.0.0.1", DAV_PORT), app)
            threading.Thread(target=self._cheroot.start, daemon=True).start()
            time.sleep(1)
            self._set_status("WebDAV running. Mounting drive...")

            if self._cancelled:
                self._cheroot.stop()
                return

            _ensure_webclient_running()
            _win_unmount("M")
            time.sleep(0.3)

            if self._cancelled:
                self._cheroot.stop()
                return

            remote = "\\\\127.0.0.1@" + str(DAV_PORT) + "\\DavWWWRoot"
            err = _win_mount("M", remote)

            if err:
                self._set_status(f"[red]Mount failed: {err}[/red]")
                _re_enable_mount()
                return

            try:
                import win32com.client
                shell = win32com.client.Dispatch("Shell.Application")
                f = shell.NameSpace("M:")
                if f:
                    f.Self.Name = "McClaude"
            except Exception:
                pass

            self._mounted = True
            self._mounting = False

            def update_btns():
                btn = self.query_one("#mount-btn", Button)
                btn.label = "Unmount (M:)"
                btn.disabled = False
                btn.remove_class("btn-blue")
                btn.add_class("btn-yellow")

            self.app.call_from_thread(update_btns)
            self._set_status("[green]Drive mounted at M:\\[/green]")

        except Exception as e:
            self._set_status(f"[red]Mount error: {e}[/red]")
            _re_enable_mount()

    def _set_status(self, text: str) -> None:
        """Thread-safe status bar update."""
        try:
            self.app.call_from_thread(self.query_one("#status", Static).update, text)
        except Exception:
            pass  # screen might be gone

    def _do_unmount(self) -> None:
        _win_unmount("M")
        if self._cheroot:
            try:
                self._cheroot.stop()
            except Exception:
                pass
            self._cheroot = None
        self._mounted = False

    @work(thread=True)
    def do_repair(self) -> None:
        cfg = load_config()
        if not cfg:
            return
        status = self.query_one("#status", Static)
        self.app.call_from_thread(status.update, "Reinstalling MCP server...")
        msg = install_mcp_server(cfg["token"], cfg["api_url"])
        self.app.call_from_thread(status.update, f"[green]MCP: {msg}[/green]")

    def check_mcp_update(self) -> None:
        """Check if installed MCP server JS is outdated vs what we have."""
        js_path = _get_mcp_js_path()
        if not js_path.exists():
            return

        # Get the source JS (dev or embedded)
        source = _find_mcp_source()
        if source:
            source_hash = hashlib.md5(source.read_bytes()).hexdigest()
        elif hasattr(install_mcp_server, "_embedded_js"):
            source_hash = hashlib.md5(install_mcp_server._embedded_js.encode()).hexdigest()
        else:
            return

        installed_hash = hashlib.md5(js_path.read_bytes()).hexdigest()

        if source_hash != installed_hash:
            def handle_update(confirmed: bool) -> None:
                if confirmed:
                    cfg = load_config()
                    if cfg:
                        msg = install_mcp_server(cfg["token"], cfg["api_url"])
                        self._set_status(f"[green]MCP updated. Restart Claude Code to use new tools.[/green]")
                    else:
                        self._set_status("[red]No config — run setup first[/red]")
            self.app.push_screen(UpdateModal(), handle_update)


# ── Settings Screen ────────────────────────────────────────────────

class SettingsScreen(Screen):
    """Edit configuration."""

    CSS = """
    SettingsScreen { align: center middle; }
    #settings-box {
        width: 64;
        height: auto;
        border: solid $accent;
        padding: 2 4;
        background: $surface;
    }
    .settings-label { margin: 1 0 0 0; color: $text-muted; }
    .settings-input { margin: 0 0 1 0; }
    #settings-buttons { margin-top: 1; }
    #settings-buttons Button { margin-right: 1; }
    #settings-status { margin-top: 1; min-height: 1; }
    """

    def compose(self) -> ComposeResult:
        cfg = load_config() or {}
        with Container(id="settings-box"):
            yield Static("[b]Settings[/b]")
            yield Label("Server URL", classes="settings-label")
            yield Input(value=cfg.get("api_url", "http://localhost:3000"), id="url-input", classes="settings-input")
            yield Label("Token", classes="settings-label")
            yield Input(value=cfg.get("token", ""), id="token-input", classes="settings-input")
            with Horizontal(id="settings-buttons"):
                yield Button("Save", classes="btn-primary",id="save-btn")
                yield Button("Back", id="back-btn")
            yield Static("", id="settings-status")

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "back-btn":
            self.app.pop_screen()
        elif event.button.id == "save-btn":
            url = self.query_one("#url-input", Input).value.strip()
            token = self.query_one("#token-input", Input).value.strip()
            save_config({"api_url": url, "token": token})
            install_mcp_server(token, url)
            self.query_one("#settings-status", Static).update("[green]Saved![/green]")


# ── App ────────────────────────────────────────────────────────────

class UpdateModal(ModalScreen[bool]):
    """Prompt to update the MCP server JS."""

    CSS = """
    UpdateModal { align: center middle; }
    #update-dialog {
        width: 55;
        height: auto;
        border: solid #d77757;
        padding: 2 4;
        background: #111111;
    }
    #update-dialog Static { margin-bottom: 1; }
    #update-buttons { margin-top: 1; }
    #update-buttons Button { margin-right: 1; }
    """

    def compose(self) -> ComposeResult:
        with Container(id="update-dialog"):
            yield Static("[b]MCP Server Update Available[/b]")
            yield Static("A newer version of the MCP server is available. Update now to get the latest tools and fixes.")
            yield Static("[dim]You'll need to restart Claude Code after updating.[/dim]")
            with Horizontal(id="update-buttons"):
                yield Button("Update", classes="btn-primary", id="confirm-update")
                yield Button("Skip", id="cancel-update")

    def on_button_pressed(self, event: Button.Pressed) -> None:
        self.dismiss(event.button.id == "confirm-update")


class QuitConfirmModal(ModalScreen[bool]):
    """Confirmation dialog before quitting with drive mounted."""

    CSS = """
    QuitConfirmModal {
        align: center middle;
    }
    #quit-dialog {
        width: 50;
        height: auto;
        border: solid #ff6b80;
        padding: 2 4;
        background: #111111;
    }
    #quit-dialog Static {
        margin-bottom: 1;
    }
    #quit-buttons {
        margin-top: 1;
    }
    #quit-buttons Button {
        margin-right: 1;
    }
    """

    def compose(self) -> ComposeResult:
        with Container(id="quit-dialog"):
            yield Static("[b]Drive is active[/b]")
            yield Static("Quitting will unmount the drive and cancel any pending operations.")
            with Horizontal(id="quit-buttons"):
                yield Button("Quit & Unmount", classes="btn-quit",id="confirm-quit")
                yield Button("Cancel", id="cancel-quit")

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "confirm-quit":
            self.dismiss(True)
        else:
            self.dismiss(False)


class McclaudeApp(App):
    """McClaude TUI Application."""

    TITLE = "McClaude"
    BINDINGS = [("q", "quit", "Quit")]
    CSS = """
    Screen {
        background: #000000;
    }
    Header {
        background: #1a1a1a;
        color: #d77757;
    }
    Footer {
        background: #1a1a1a;
        color: #888888;
    }
    Button {
        background: #2a2a2a;
        color: #cccccc;
    }
    Button:hover {
        background: #3a3a3a;
    }
    .btn-primary {
        background: #d77757;
        color: #000000;
    }
    .btn-primary:hover {
        background: #e08868;
    }
    DataTable {
        background: #111111;
    }
    DataTable > .datatable--header {
        background: #1a1a1a;
        color: #d77757;
    }
    DataTable > .datatable--cursor {
        background: #2a2a2a;
    }
    Input {
        background: #111111;
        border: solid #333333;
        color: #cccccc;
    }
    Input:focus {
        border: solid #d77757;
    }
    #status {
        background: #111111;
        color: #888888;
    }
    #setup-box, #settings-box {
        background: #111111;
        border: solid #333333;
    }
    .setup-label, .settings-label {
        color: #888888;
    }
    #setup-title {
        color: #d77757;
    }
    .btn-blue {
        background: #4a7fd4;
        color: #000000;
    }
    .btn-blue:hover {
        background: #5a8fe4;
    }
    .btn-yellow {
        background: #d4a94a;
        color: #000000;
    }
    .btn-yellow:hover {
        background: #e4b95a;
    }
    .btn-pink {
        background: #d77757;
        color: #000000;
    }
    .btn-pink:hover {
        background: #e08868;
    }
    .btn-quit {
        background: #ff6b80;
        color: #000000;
    }
    .btn-quit:hover {
        background: #ff7b90;
    }
    Button:focus {
        text-style: none;
    }
    """

    def on_mount(self) -> None:
        cfg = load_config()
        if cfg and cfg.get("token"):
            self.install_screen(MainScreen(), "main")
            self.push_screen("main")
        else:
            self.install_screen(SetupScreen(), "setup")
            self.push_screen("setup")

    def action_quit(self) -> None:
        busy = any(
            isinstance(s, MainScreen) and (s._mounted or s._mounting)
            for s in self.screen_stack
        )
        if busy:
            def handle_result(confirmed: bool) -> None:
                if confirmed:
                    for s in self.screen_stack:
                        if isinstance(s, MainScreen):
                            s._cancelled = True
                            if s._mounted:
                                s._do_unmount()
                    self.exit()
            self.push_screen(QuitConfirmModal(), handle_result)
        else:
            self.exit()


def main():
    McclaudeApp().run()


if __name__ == "__main__":
    main()
