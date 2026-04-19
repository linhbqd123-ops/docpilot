#!/usr/bin/env python3
"""
run_all.py - Cross-platform launcher for DocPilot modules

Usage:
  python run_all.py

What it does:
- Builds packages/doc-mcp if needed and runs the produced JAR with java -jar.
- Ensures a virtualenv for packages/core-engine and installs requirements, then runs Uvicorn.
- Ensures packages/desktop dependencies are installed, then runs npm run dev.

Notes:
- Requires Java (+ Maven to build), Python 3.11+, Node and npm in PATH.
- Run from repository root.
"""
from __future__ import annotations

import asyncio
import shutil
import sys
import signal
from pathlib import Path
from glob import glob
import hashlib
from typing import Optional, Set

ROOT = Path(__file__).parent.resolve()
DOC_MCP = ROOT / "packages" / "doc-mcp"
CORE = ROOT / "packages" / "core-engine"
DESKTOP = ROOT / "packages" / "desktop"


async def run_cmd(cmd, cwd: Path, name: str):
    """Run a subprocess, stream stdout/stderr, return exit code.
    Supports cancellation: will terminate child on task cancel.
    """
    # resolve executable name to full path when possible (helps on Windows where
    # commands are .cmd/.exe in PATH)
    if cmd:
        exe = str(cmd[0])
        resolved = shutil.which(exe)
        if resolved:
            cmd = [resolved] + list(cmd[1:])
    cmd_str = " ".join(map(str, cmd))
    print(f"[{name}] starting: {cmd_str} (cwd={cwd})")
    try:
        proc = await asyncio.create_subprocess_exec(
            *cmd,
            cwd=str(cwd),
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.STDOUT,
        )
    except FileNotFoundError as e:
        raise FileNotFoundError(f"Executable not found for '{name}': {cmd[0]}\nCommand: {cmd_str}") from e
    except OSError as e:
        raise SystemExit(f"Failed to start process for {name}: {e}") from e

    async def _reader():
        assert proc.stdout is not None
        while True:
            line = await proc.stdout.readline()
            if not line:
                break
            try:
                text = line.decode("utf-8", errors="replace").rstrip()
            except Exception:
                text = str(line)
            print(f"[{name}] {text}")

    reader = asyncio.create_task(_reader())

    try:
        rc = await proc.wait()
        await reader
        return rc
    except asyncio.CancelledError:
        # try graceful
        try:
            proc.terminate()
        except Exception:
            pass
        try:
            await asyncio.wait_for(proc.wait(), timeout=5)
        except Exception:
            try:
                proc.kill()
            except Exception:
                pass
            await proc.wait()
        await reader
        raise


def _file_hash(path: Path) -> str:
    h = hashlib.sha1()
    with path.open("rb") as f:
        while True:
            chunk = f.read(8192)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def dir_fingerprint(root: Path, exclude_dirs: Optional[Set[str]] = None) -> str:
    if exclude_dirs is None:
        exclude_dirs = set()
    h = hashlib.sha1()
    for p in sorted(root.rglob("*")):
        if not p.is_file():
            continue
        rel = p.relative_to(root)
        if any(part in exclude_dirs for part in rel.parts):
            continue
        st = p.stat()
        h.update(str(rel).encode())
        h.update(str(st.st_size).encode())
        h.update(str(st.st_mtime_ns).encode())
    return h.hexdigest()


def _read_text(path: Path) -> Optional[str]:
    try:
        return path.read_text(encoding="utf-8").strip()
    except Exception:
        return None


def _write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def find_doc_mcp_jar() -> list[str]:
    pattern = str(DOC_MCP / "target" / "doc-mcp-*.jar")
    return glob(pattern)


async def build_doc_mcp_if_needed():
    # compute fingerprint of the doc-mcp sources (exclude build output)
    curr_fp = dir_fingerprint(DOC_MCP, exclude_dirs={"target", ".git"})
    target_dir = DOC_MCP / "target"
    fp_file = target_dir / ".run_all_fp"
    prev_fp = _read_text(fp_file)

    jars = find_doc_mcp_jar()
    if jars and prev_fp == curr_fp:
        print("[doc-mcp] JAR exists and sources unchanged, skipping build")
        return

    print("[doc-mcp] Sources changed or JAR missing — attempting build")
    if sys.platform.startswith("win") and (DOC_MCP / "build.bat").exists():
        cmd = ["cmd", "/c", str(DOC_MCP / "build.bat")]
    else:
        # prefer mvn if available, otherwise try mvnw
        if shutil.which("mvn"):
            cmd = ["mvn", "package", "-DskipTests"]
        else:
            mvnw = DOC_MCP / "mvnw"
            if mvnw.exists():
                cmd = [str(mvnw), "package", "-DskipTests"]
            else:
                print("[doc-mcp] Maven not found and no mvnw — skipping doc-mcp build")
                return

    rc = await run_cmd(cmd, cwd=DOC_MCP, name="doc-mcp-build")
    if rc != 0:
        raise SystemExit("doc-mcp build failed (see logs)")

    # on successful build, record fingerprint
    target_dir.mkdir(parents=True, exist_ok=True)
    _write_text(fp_file, curr_fp)


def venv_python_path(core_dir: Path) -> Path:
    v = core_dir / ".venv"
    if sys.platform.startswith("win"):
        return v / "Scripts" / "python.exe"
    return v / "bin" / "python"


async def ensure_core_venv():
    py = venv_python_path(CORE)
    req = CORE / "requirements.txt"
    req_hash = _file_hash(req) if req.exists() else None
    req_fp_file = CORE / ".requirements_hash"
    prev_req_hash = _read_text(req_fp_file)

    if not py.exists():
        print("[core-engine] venv missing — creating venv and installing requirements")
        rc = await run_cmd([sys.executable, "-m", "venv", ".venv"], cwd=CORE, name="core-venv-create")
        if rc != 0:
            raise SystemExit("Failed to create core-engine venv")

        py2 = venv_python_path(CORE)
        if not py2.exists():
            raise SystemExit("venv python not found after creation")

        rc = await run_cmd([str(py2), "-m", "pip", "install", "--upgrade", "pip"], cwd=CORE, name="core-pip-upgrade")
        if rc != 0:
            raise SystemExit("Failed to upgrade pip in core venv")

        if req.exists():
            rc = await run_cmd([str(py2), "-m", "pip", "install", "-r", "requirements.txt"], cwd=CORE, name="core-install-reqs")
            if rc != 0:
                raise SystemExit("Failed to install core-engine requirements")
            if req_hash:
                _write_text(req_fp_file, req_hash)
        else:
            print("[core-engine] No requirements.txt found, skipping pip install")
        return

    # venv exists; install requirements if they changed
    if req.exists() and prev_req_hash != req_hash:
        print("[core-engine] requirements changed — installing in existing venv")
        py2 = venv_python_path(CORE)
        rc = await run_cmd([str(py2), "-m", "pip", "install", "-r", "requirements.txt"], cwd=CORE, name="core-install-reqs")
        if rc != 0:
            raise SystemExit("Failed to install core-engine requirements")
        if req_hash:
            _write_text(req_fp_file, req_hash)
    else:
        print("[core-engine] venv found and requirements unchanged")


async def ensure_desktop_deps():
    if shutil.which("npm") is None:
        print("[desktop] npm not found in PATH — skipping desktop setup")
        return

    # compute hash for package files to detect changes
    files = [DESKTOP / "package.json", DESKTOP / "package-lock.json", DESKTOP / "yarn.lock"]
    h = hashlib.sha1()
    any_file = False
    for f in files:
        if f.exists():
            any_file = True
            try:
                h.update(f.read_bytes())
            except Exception:
                # fallback to mtime/size
                st = f.stat()
                h.update(str(f.name).encode())
                h.update(str(st.st_mtime_ns).encode())
                h.update(str(st.st_size).encode())

    deps_hash = h.hexdigest() if any_file else None
    fp_file = DESKTOP / ".deps_hash"
    prev = _read_text(fp_file)
    node_modules = DESKTOP / "node_modules"

    if node_modules.exists() and prev == deps_hash:
        print("[desktop] node_modules present and package files unchanged, skipping npm install")
        return

    print("[desktop] Installing npm dependencies")
    npm_exe = shutil.which("npm") or "npm"
    rc = await run_cmd([npm_exe, "install"], cwd=DESKTOP, name="desktop-npm-install")
    if rc != 0:
        raise SystemExit("npm install failed in desktop")
    if deps_hash:
        _write_text(fp_file, deps_hash)


def ensure_java():
    """Fail early if `java` is not available on PATH."""
    if shutil.which("java") is None:
        raise SystemExit("'java' not found in PATH. Install a JRE/JDK and ensure 'java' is on PATH.")


async def spawn_doc():
    jars = find_doc_mcp_jar()
    if not jars:
        raise SystemExit("doc-mcp JAR missing; cannot start doc-mcp")
    jar = jars[0]
    java_exe = shutil.which("java") or "java"
    return await run_cmd([java_exe, "-jar", jar], cwd=DOC_MCP, name="doc-mcp")


async def spawn_core():
    py = venv_python_path(CORE)
    if not py.exists():
        raise SystemExit("core-engine venv missing; run setup step failed")
    return await run_cmd([str(py), "-m", "uvicorn", "main:app", "--reload", "--port", "8000"], cwd=CORE, name="core-engine")


async def spawn_desktop():
    if shutil.which("npm") is None:
        raise SystemExit("npm not found; cannot start desktop")
    npm_exe = shutil.which("npm") or "npm"
    return await run_cmd([npm_exe, "run", "dev"], cwd=DESKTOP, name="desktop")


async def main():
    # 1) setup/build as needed
    try:
        await build_doc_mcp_if_needed()
        await ensure_core_venv()
        await ensure_desktop_deps()
        ensure_java()
    except SystemExit as e:
        print(e)
        return

    # 2) start all servers concurrently
    loop = asyncio.get_running_loop()
    tasks = [
        asyncio.create_task(spawn_doc()),
        asyncio.create_task(spawn_core()),
        asyncio.create_task(spawn_desktop()),
    ]

    # handle signals (best-effort on Windows)
    def _cancel_all():
        print("Stopping all servers...")
        for t in tasks:
            t.cancel()

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, _cancel_all)
        except NotImplementedError:
            # Windows fallback: signal handlers set below
            pass

    try:
        done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for d in done:
            try:
                rc = d.result()
                print(f"Task exited with code {rc}")
            except asyncio.CancelledError:
                pass
            except Exception as e:
                print("Server task error:", e)
    except asyncio.CancelledError:
        pass
    finally:
        for p in tasks:
            if not p.done():
                p.cancel()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("Interrupted — exiting")
