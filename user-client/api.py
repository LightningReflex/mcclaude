"""
API client for the McClaude intermediate server.

Uses a shared token for authentication (X-Token-Hash header).
All content (file data, console output, commands) is E2E encrypted
with AES-256-GCM so the intermediate server never sees plaintext.

All operations go through the encrypted RPC endpoint except for
list_servers (plain GET) and read_console (stored encrypted blobs).
"""

from __future__ import annotations

import base64
import hashlib
import json
from typing import Any

import requests

from crypto import encrypt_raw, decrypt_raw


class ApiError(Exception):
    """Raised when the API returns an error response."""

    def __init__(self, status: int, message: str):
        self.status = status
        self.message = message
        super().__init__(f"HTTP {status}: {message}")


class McclaudeAPI:
    """Thin wrapper around the McClaude intermediate-server REST API."""

    def __init__(self, base_url: str, token: str):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
        self.session = requests.Session()
        self.session.headers.update({
            "X-Token-Hash": self.token_hash,
            "Content-Type": "application/json",
        })

    # ── helpers ──────────────────────────────────────────────────────

    def _url(self, path: str) -> str:
        return f"{self.base_url}{path}"

    def _request(self, method: str, path: str, **kwargs: Any) -> Any:
        try:
            resp = self.session.request(method, self._url(path), **kwargs)
        except requests.ConnectionError:
            raise ApiError(0, f"Could not connect to {self.base_url}")
        except requests.Timeout:
            raise ApiError(0, "Request timed out")
        except requests.RequestException as exc:
            raise ApiError(0, str(exc))

        if not resp.ok:
            try:
                body = resp.json()
                msg = body.get("error", resp.text)
            except ValueError:
                msg = resp.text
            raise ApiError(resp.status_code, msg)

        if resp.status_code == 204 or not resp.text:
            return {}
        return resp.json()

    def _rpc(self, server_id: str, request: dict) -> dict:
        """Send an encrypted RPC request and return the decrypted response."""
        payload = json.dumps(request).encode("utf-8")
        encrypted = encrypt_raw(self.token, payload)

        try:
            resp = self.session.post(
                self._url(f"/api/servers/{server_id}/rpc"),
                data=encrypted,
                headers={
                    "Content-Type": "application/octet-stream",
                    "X-Token-Hash": self.token_hash,
                },
            )
        except requests.RequestException as exc:
            raise ApiError(0, str(exc))

        if not resp.ok:
            # Server-level error (not encrypted)
            try:
                msg = resp.json().get("error", resp.text)
            except ValueError:
                msg = resp.text
            raise ApiError(resp.status_code, msg)

        # Decrypt response
        decrypted = decrypt_raw(self.token, resp.content)
        result = json.loads(decrypted.decode("utf-8"))

        if "error" in result and result["error"]:
            raise ApiError(502, result["error"])

        return result.get("result", result)

    # ── servers ──────────────────────────────────────────────────────

    def list_servers(self) -> list[dict]:
        """GET /api/servers — list all servers with this token."""
        data = self._request("GET", "/api/servers")
        return data.get("servers", [])

    # ── file operations (via encrypted RPC) ──────────────────────────

    def list_files(self, server_id: str, path: str = ".") -> list[dict]:
        """List files in a directory via RPC."""
        result = self._rpc(server_id, {"type": "file_list", "data": {"path": path}})
        entries = result.get("entries", [])
        return entries

    def read_file_bytes(self, server_id: str, path: str) -> bytes:
        """Read a file and return raw decrypted bytes via RPC."""
        result = self._rpc(server_id, {"type": "file_read", "data": {"path": path}})
        content_b64 = result.get("content_base64", "")
        if content_b64:
            return base64.b64decode(content_b64)
        # Fallback: plain text content
        content = result.get("content", "")
        return content.encode("utf-8") if isinstance(content, str) else content

    def write_file_encrypted(self, server_id: str, path: str, data: bytes) -> dict:
        """Write file content via RPC (content is base64-encoded in the request)."""
        content_b64 = base64.b64encode(data).decode("ascii")
        return self._rpc(server_id, {"type": "file_write", "data": {"path": path, "content_base64": content_b64}})

    def delete_file(self, server_id: str, path: str) -> dict:
        """Delete a file via RPC."""
        return self._rpc(server_id, {"type": "file_delete", "data": {"path": path}})

    def mkdir(self, server_id: str, path: str) -> dict:
        """Create a directory via RPC."""
        return self._rpc(server_id, {"type": "file_mkdir", "data": {"path": path}})

    def rename_file(self, server_id: str, from_path: str, to_path: str) -> dict:
        """Rename/move a file or directory via RPC."""
        return self._rpc(server_id, {"type": "file_rename", "data": {"from": from_path, "to": to_path}})
