"""
Build script for McClaude.

Bundles the MCP server JS and all Python modules into a single mcclaude.py.

Usage: python build.py
Output: dist/mcclaude.py + dist/requirements.txt
"""

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).parent
MCP_DIR = ROOT / "mcp-server"
CLIENT_DIR = ROOT / "user-client"
DIST_DIR = ROOT / "dist"

MODULES = ["crypto", "config", "api", "drive", "tui"]

HEADER = '''#!/usr/bin/env python3
"""
McClaude — Let Claude Code develop Minecraft plugins on a live server.

Single-file portable script. Run with: py mcclaude.py
Requirements: pip install -r requirements.txt
"""
from __future__ import annotations
'''

FOOTER = '''

# ════════════════════════════════════════════════════════════════════
# Entry Point
# ════════════════════════════════════════════════════════════════════

if __name__ == "__main__":
    from mcclaude_tui import main  # noqa: reference to merged module
    main()
'''


def build_mcp_bundle() -> str:
    """Bundle MCP server TypeScript into a single JS file."""
    print("[1/4] Bundling MCP server JS...")
    npx = "npx.cmd" if sys.platform == "win32" else "npx"
    result = subprocess.run(
        [npx, "esbuild", "src/index.ts", "--bundle", "--platform=node",
         "--target=node18", "--format=esm", "--external:dotenv",
         "--outfile=dist/bundle-full.js"],
        cwd=str(MCP_DIR), capture_output=True, text=True,
    )
    if result.returncode != 0:
        print(f"  esbuild failed: {result.stderr}")
        sys.exit(1)

    js = (MCP_DIR / "dist" / "bundle-full.js").read_text(encoding="utf-8")
    print(f"  Bundled: {len(js) // 1024}KB")
    return js


def read_module(name: str) -> str:
    """Read a Python module and strip cross-module imports."""
    path = CLIENT_DIR / f"{name}.py"
    if not path.exists():
        print(f"  Warning: {path} not found")
        return ""

    lines = []
    skip_main_block = False
    for line in path.read_text(encoding="utf-8").splitlines():
        # Skip imports that reference the mcclaude package (will be in same file)
        stripped = line.lstrip()
        # Skip cross-module imports (everything is in one file when built)
        local_modules = ("api", "config", "crypto", "drive", "tui")
        is_local = (
            stripped.startswith("from mcclaude.") or stripped.startswith("import mcclaude.")
            or any(stripped.startswith(f"from {m} import") for m in local_modules)
            or any(stripped == f"import {m}" for m in local_modules)
        )
        if is_local:
            indent = line[:len(line) - len(stripped)]
            lines.append(f"{indent}pass  # [merged] {stripped}")
            continue
        if line.startswith("from __future__"):
            continue
        # Strip if __name__ == "__main__" blocks (build script adds its own)
        if line.strip().startswith("if __name__"):
            skip_main_block = True
            continue
        if skip_main_block:
            if line and not line[0].isspace():
                skip_main_block = False
            else:
                continue
        lines.append(line)

    return "\n".join(lines)


def build():
    DIST_DIR.mkdir(exist_ok=True)

    # 1. Bundle MCP JS
    js_code = build_mcp_bundle()

    # 2. Read modules
    print("[2/4] Reading Python modules...")
    modules = {}
    for name in MODULES:
        modules[name] = read_module(name)
        lines = modules[name].count("\n")
        print(f"  {name}.py: {lines} lines")

    # 3. Assemble
    print("[3/4] Assembling mcclaude.py...")

    # Collect all unique stdlib/3rd-party imports from all modules
    parts = [HEADER]

    # Embed the MCP JS
    # Use triple-quoted raw string to avoid escaping issues
    parts.append("# " + "═" * 68)
    parts.append(f"# Embedded MCP Server JS ({len(js_code) // 1024}KB)")
    parts.append("# " + "═" * 68)
    parts.append("")
    # Write JS as a base64 blob to avoid string escaping nightmares
    import base64
    js_b64 = base64.b64encode(js_code.encode("utf-8")).decode("ascii")
    parts.append("import base64 as _b64")
    parts.append(f'_MCP_SERVER_JS_B64 = "{js_b64}"')
    parts.append("_MCP_SERVER_JS = _b64.b64decode(_MCP_SERVER_JS_B64).decode('utf-8')")
    parts.append("")

    # Add each module with a section header
    for name in MODULES:
        parts.append("# " + "═" * 68)
        parts.append(f"# Module: {name}")
        parts.append("# " + "═" * 68)
        parts.append("")
        parts.append(modules[name])
        parts.append("")

    # Wire the embedded JS into the install function
    parts.append("# Wire embedded JS into installer")
    parts.append("install_mcp_server._embedded_js = _MCP_SERVER_JS")
    parts.append("")

    # Entry point
    parts.append("# " + "═" * 68)
    parts.append("# Entry Point")
    parts.append("# " + "═" * 68)
    parts.append("")
    parts.append("if __name__ == '__main__':")
    parts.append("    main()")
    parts.append("")

    combined = "\n".join(parts)

    out_path = DIST_DIR / "mcclaude.py"
    out_path.write_text(combined, encoding="utf-8")
    print(f"  Output: {out_path} ({len(combined) // 1024}KB, {combined.count(chr(10))} lines)")

    # 4. Requirements
    print("[4/4] Writing requirements.txt...")
    req_path = DIST_DIR / "requirements.txt"
    req_path.write_text(
        "textual>=0.50.0\n"
        "requests>=2.28.0\n"
        "cryptography>=41.0.0\n"
        "wsgidav>=4.3.0\n"
        "cheroot>=10.0.0\n"
        "pywin32>=306\n",
        encoding="utf-8",
    )

    print()
    print(f"Done! Ship:")
    print(f"  dist/mcclaude.py       ({len(combined) // 1024}KB)")
    print(f"  dist/requirements.txt")


if __name__ == "__main__":
    build()
