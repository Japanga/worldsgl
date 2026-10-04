#!/usr/bin/env python3
"""WorldsGL Multiplayer Server GUI.

Double-click this file (or run `python server.py`) to open the Tkinter server
manager.  It provides a visual server console, configurable bind IP/port,
a saved list of server endpoints, and a live connected-player list.

The server is a lightweight TCP relay for the WorldsGL multiplayer JAR.  It
keeps no game assets; the exported JAR contains the game world and clients
connect to this process to exchange player state and chat.
"""

import json
import os
import socket
import threading
import tkinter as tk
from tkinter import ttk, messagebox, filedialog
import subprocess
import shutil
import re
import time
import zipfile
import tempfile
import urllib.request
import urllib.parse
import sys

DEFAULT_HOST = "0.0.0.0"
DEFAULT_PORT = 8765
DEFAULT_WEB_PORT = 8756
DEFAULT_TUNNEL_SUBDOMAIN = "worldsgl-retail"
RETAIL_RESERVED_TUNNEL_SUBDOMAIN = "worldsgl-server"
CONFIG_DIR = os.path.join(os.path.expanduser("~"), ".worldsgl")
SERVER_LIST_FILE = os.path.join(CONFIG_DIR, "servers.json")
TUNNEL_SESSION_FILE = os.path.join(CONFIG_DIR, "localtunnel_session.json")

# Public WebSocket tunnel state.  The game protocol itself remains TCP on
# DEFAULT_PORT; the web gateway exists solely so HTTP/WebSocket tunnel
# providers can carry it without exposing the game port.
web_server_socket = None
web_server_running = False
web_port_global = DEFAULT_WEB_PORT
tunnel_process = None
tunnel_url = ""
tunnel_provider = ""
server_jar_process = None
server_jar_path = ""
client_jar_path = ""
client_download_copy = ""
client_download_zip = ""
tunnel_lock = threading.RLock()

clients = {}          # socket -> username
player_state = {}     # socket -> (x, y, z, rotation)
player_color = {}     # socket -> (red, green, blue), independent of username
admin_users = set()   # usernames marked Admin/Host in the server GUI
muted_users = set()   # usernames whose chat is disabled
lock = threading.RLock()
server_socket = None
server_running = False
stop_event = threading.Event()


def safe_text(value, limit=500):
    return (value or "").replace("\r", " ").replace("\n", " ").replace("|", "_")[:limit]



def websocket_public_endpoint():
    """Return the current public game endpoint using the WebSocket scheme."""
    if not tunnel_url:
        return ""
    endpoint = tunnel_url.strip().rstrip("/")
    if endpoint.startswith("https://"):
        endpoint = "wss://" + endpoint[len("https://"):]
    elif endpoint.startswith("http://"):
        endpoint = "ws://" + endpoint[len("http://"):]
    elif not endpoint.startswith(("ws://", "wss://")):
        endpoint = "wss://" + endpoint.lstrip("/")
    if not endpoint.endswith("/game"):
        endpoint += "/game"
    return endpoint

def send_line(sock, line):
    try:
        sock.sendall((line + "\n").encode("utf-8"))
        return True
    except OSError:
        return False


def unique_name(requested):
    base = safe_text(requested, 24).strip() or "Player"
    with lock:
        used = set(clients.values())
    if base not in used:
        return base
    n = 2
    while f"{base}_{n}" in used:
        n += 1
    return f"{base}_{n}"


def broadcast(line, exclude=None):
    with lock:
        targets = list(clients.keys())
    dead = []
    for sock in targets:
        if sock is exclude:
            continue
        if not send_line(sock, line):
            dead.append(sock)
    for sock in dead:
        disconnect(sock)


def disconnect(sock):
    with lock:
        username = clients.pop(sock, None)
        player_state.pop(sock, None)
        player_color.pop(sock, None)
        if username:
            admin_users.discard(username)
            muted_users.discard(username)
    try:
        sock.close()
    except OSError:
        pass
    if username:
        broadcast(f"LEFT|{username}")
        gui_log(f"[-] {username} disconnected")
        refresh_players()


def find_socket_by_username(username):
    with lock:
        for sock, name in clients.items():
            if name == username:
                return sock
    return None


def admin_action(username, action):
    username = safe_text(username, 24).strip()
    if not username:
        return
    sock = find_socket_by_username(username)
    if sock is None:
        return
    if action == "admin":
        with lock:
            if username in admin_users:
                admin_users.remove(username)
                enabled = False
            else:
                admin_users.add(username)
                enabled = True
        gui_log(f"[ADMIN] {username} {'is now' if enabled else 'is no longer'} Admin/Host")
        refresh_players()
    elif action == "mute":
        with lock:
            if username in muted_users:
                muted_users.remove(username)
                enabled = False
            else:
                muted_users.add(username)
                enabled = True
        send_line(sock, "MUTE_STATE|" + ("1" if enabled else "0"))
        if enabled:
            send_line(sock, "MUTED|You have been muted by the admin/host")
        else:
            send_line(sock, "MUTE_STATE|0")
        gui_log(f"[ADMIN] {username} {'muted' if enabled else 'unmuted'}")
        refresh_players()
    elif action == "warn":
        send_line(sock, "WARN|You have been warned by the admin/host")
        gui_log(f"[ADMIN] Warned {username}")
    elif action == "kick":
        # Deliver the kick packet before severing the player's connection.
        # This is especially important for clients connected through the
        # WebSocket bridge: the runtime must receive KICK so it can display
        # the full-screen kicked state instead of merely seeing a disconnect.
        send_line(sock, "KICK|You have been kicked by the admin/host")
        gui_log(f"[ADMIN] Kicked {username}")
        def finish_kick():
            time.sleep(0.12)
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            disconnect(sock)
        threading.Thread(target=finish_kick, daemon=True, name="WorldsGL-Kick").start()


def _clamp_color_byte(value, fallback=255):
    try:
        return max(0, min(255, int(value)))
    except (TypeError, ValueError):
        return fallback


def _parse_join_color(parts):
    if len(parts) >= 5:
        return (
            _clamp_color_byte(parts[2], 70),
            _clamp_color_byte(parts[3], 200),
            _clamp_color_byte(parts[4], 255),
        )
    return (70, 200, 255)


def handle_client(sock, address):
    username = None
    try:
        reader = sock.makefile("r", encoding="utf-8", newline="\n")
        first = reader.readline()
        if not first:
            return

        # JOIN is parsed field-by-field. The username is ONLY parts[1]; RGB
        # values, including legacy JOIN colors, are never appended to it.
        parts = first.rstrip("\n").split("|")
        if len(parts) < 2 or parts[0] != "JOIN":
            send_line(sock, "ERROR|Expected JOIN|username")
            return

        username = unique_name(parts[1])
        initial_color = _parse_join_color(parts)

        with lock:
            clients[sock] = username
            player_state[sock] = (0.0, 0.0, 0.0, 0.0)
            player_color[sock] = initial_color

        # WELCOME contains ONLY the server-assigned username.
        send_line(sock, f"WELCOME|{username}")

        # Give the new client the existing players and their independent colors.
        with lock:
            existing = [
                (other_name,
                 player_state.get(other, (0, 0, 0, 0)),
                 player_color.get(other, (70, 200, 255)))
                for other, other_name in clients.items() if other is not sock
            ]
        for other_name, state, color in existing:
            x, y, z, rot = state
            cr, cg, cb = color
            send_line(sock, f"PLAYER|{other_name}|{x}|{y}|{z}|{rot}|{cr}|{cg}|{cb}")

        # Announce the new player's color separately from the username.
        cr, cg, cb = initial_color
        broadcast(f"COLOR|{username}|{cr}|{cg}|{cb}", exclude=sock)
        broadcast(f"JOIN|{username}", exclude=sock)

        gui_log(f"[+] {username} connected from {address[0]}:{address[1]}")
        refresh_players()

        for raw in reader:
            if not server_running:
                break
            line = raw.rstrip("\n")
            if not line:
                continue

            parts = line.split("|")
            if parts[0] == "STATE":
                if len(parts) >= 6:
                    try:
                        state = tuple(float(v) for v in parts[2:6])
                        with lock:
                            player_state[sock] = state
                        broadcast("STATE|" + username + "|" + "|".join(parts[2:6]), exclude=sock)
                    except ValueError:
                        pass

            elif parts[0] == "COLOR":
                # Associate color with this connection, not with any client-
                # supplied username field. This is the authoritative player color.
                if len(parts) >= 5:
                    # COLOR|username|red|green|blue
                    color = (
                        _clamp_color_byte(parts[2], 70),
                        _clamp_color_byte(parts[3], 200),
                        _clamp_color_byte(parts[4], 255),
                    )
                    with lock:
                        player_color[sock] = color
                        actual_name = clients.get(sock, username)
                    cr, cg, cb = color
                    broadcast(f"COLOR|{actual_name}|{cr}|{cg}|{cb}", exclude=sock)

            elif parts[0] == "CHAT" and len(parts) >= 3:
                message = safe_text(parts[2], 500)
                with lock:
                    is_muted = username in muted_users
                    is_admin = username in admin_users
                if is_muted:
                    send_line(sock, "MUTED|You have been muted by the admin/host")
                else:
                    broadcast(f"CHAT|{username}|{message}|{'1' if is_admin else '0'}")

    except (OSError, UnicodeError) as exc:
        gui_log(f"[!] Client error {address}: {exc}")
    finally:
        disconnect(sock)


def server_accept_loop():
    global server_socket
    while server_running and not stop_event.is_set():
        try:
            server_socket.settimeout(1.0)
            sock, address = server_socket.accept()
        except socket.timeout:
            continue
        except OSError:
            break
        threading.Thread(
            target=handle_client,
            args=(sock, address),
            daemon=True,
            name="WorldsGL-Client",
        ).start()


def start_server(bind_host, port, web_port=None):
    global server_socket, server_running, web_port_global
    if server_running:
        return False, "The server is already running."

    try:
        port = int(port)
        if not 1 <= port <= 65535:
            raise ValueError
    except ValueError:
        return False, "Port must be between 1 and 65535."

    bind_host = bind_host.strip()
    if not bind_host:
        return False, "Enter a bind IP/address."

    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind((bind_host, port))
        sock.listen(32)
    except OSError as exc:
        try:
            sock.close()
        except Exception:
            pass
        return False, str(exc)

    server_socket = sock
    server_running = True
    stop_event.clear()
    threading.Thread(target=server_accept_loop, daemon=True, name="WorldsGL-Server").start()
    gui_log(f"[SERVER] Listening on {bind_host}:{port}")

    try:
        web_port = int(web_port if web_port is not None else DEFAULT_WEB_PORT)
        if not 1 <= web_port <= 65535:
            raise ValueError
    except ValueError:
        web_port = DEFAULT_WEB_PORT
    web_port_global = web_port

    ok, error = start_web_server(web_port)
    if not ok:
        server_running = False
        try:
            sock.close()
        except OSError:
            pass
        server_socket = None
        return False, error

    refresh_status()
    refresh_tunnel_status()
    return True, None


def stop_server():
    global server_socket, server_running
    server_running = False
    stop_event.set()
    try:
        if server_socket:
            server_socket.close()
    except OSError:
        pass
    server_socket = None

    with lock:
        sockets = list(clients.keys())
    for sock in sockets:
        disconnect(sock)

    stop_server_jar()
    stop_tunnel()
    stop_web_server()

    gui_log("[SERVER] Stopped")
    refresh_status()
    refresh_tunnel_status()


def load_saved_servers():
    try:
        with open(SERVER_LIST_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, list) else []
    except (OSError, ValueError):
        return []


def save_saved_servers(entries):
    try:
        os.makedirs(CONFIG_DIR, exist_ok=True)
        with open(SERVER_LIST_FILE, "w", encoding="utf-8") as f:
            json.dump(entries, f, indent=2)
        return True
    except OSError:
        return False



# ---------------------------------------------------------------------------
# Public HTTP/WebSocket gateway + tunnel management
# ---------------------------------------------------------------------------

def _ws_frame(payload):
    data = payload.encode("utf-8") if isinstance(payload, str) else payload
    n = len(data)
    if n < 126:
        return bytes([0x81, n]) + data
    if n < 65536:
        return bytes([0x81, 126]) + n.to_bytes(2, "big") + data
    return bytes([0x81, 127]) + n.to_bytes(8, "big") + data


def _ws_read_frame(sock):
    head = sock.recv(2)
    if len(head) != 2:
        return None
    fin_opcode, second = head
    opcode = fin_opcode & 0x0F
    masked = bool(second & 0x80)
    length = second & 0x7F
    if length == 126:
        raw = sock.recv(2)
        if len(raw) != 2:
            return None
        length = int.from_bytes(raw, "big")
    elif length == 127:
        raw = sock.recv(8)
        if len(raw) != 8:
            return None
        length = int.from_bytes(raw, "big")
    mask = sock.recv(4) if masked else b""
    if masked and len(mask) != 4:
        return None
    data = bytearray()
    while len(data) < length:
        chunk = sock.recv(min(65536, length - len(data)))
        if not chunk:
            return None
        data.extend(chunk)
    if masked:
        for i in range(len(data)):
            data[i] ^= mask[i % 4]
    return opcode, bytes(data)


def _http_headers(raw):
    try:
        first = raw.split(b"\r\n", 1)[0].decode("latin1")
        parts = first.split()
        return parts[0], parts[1] if len(parts) > 1 else ""
    except Exception:
        return "", ""


def _http_response(status, content_type, body):
    data = body.encode("utf-8") if isinstance(body, str) else body
    reason = {
        200: "OK", 400: "Bad Request", 404: "Not Found",
        426: "Upgrade Required", 500: "Internal Server Error", 503: "Service Unavailable"
    }.get(status, "OK")
    return (
        f"HTTP/1.1 {status} {reason}\r\n"
        f"Content-Type: {content_type}\r\n"
        f"Content-Length: {len(data)}\r\n"
        "Connection: close\r\n\r\n"
    ).encode("latin1") + data


def _websocket_accept_key(key):
    import hashlib, base64
    digest = hashlib.sha1(
        (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode("ascii")
    ).digest()
    return base64.b64encode(digest).decode("ascii")


def _websocket_bridge(client_sock):
    """Bridge WebSocket /game traffic to the normal WorldsGL TCP server."""
    game_sock = None
    try:
        game_sock = socket.create_connection(
            ("127.0.0.1", int(port_var.get())), timeout=8
        )
        client_sock.settimeout(None)
        game_sock.settimeout(None)

        def client_to_game():
            try:
                while True:
                    frame = _ws_read_frame(client_sock)
                    if frame is None:
                        break
                    opcode, payload = frame
                    if opcode == 0x8:
                        break
                    if opcode == 0x9:
                        try:
                            client_sock.sendall(bytes([0x8A, len(payload)]) + payload)
                        except OSError:
                            pass
                        continue
                    if opcode != 0x1:
                        continue
                    game_sock.sendall(payload)
                    if not payload.endswith(b"\n"):
                        game_sock.sendall(b"\n")
            except OSError:
                pass
            finally:
                try:
                    game_sock.shutdown(socket.SHUT_WR)
                except OSError:
                    pass

        thread = threading.Thread(target=client_to_game, daemon=True)
        thread.start()

        buffer = b""
        while True:
            chunk = game_sock.recv(65536)
            if not chunk:
                break
            buffer += chunk
            while b"\n" in buffer:
                line, buffer = buffer.split(b"\n", 1)
                try:
                    client_sock.sendall(_ws_frame(line.decode("utf-8", "replace") + "\n"))
                except OSError:
                    return
    except socket.timeout:
        # A client/tunnel timing out is a normal disconnect condition.
        pass
    except OSError:
        # Client disconnects and tunnel resets are expected during normal use.
        try:
            client_sock.sendall(_ws_frame("ERROR|Game server unavailable"))
        except OSError:
            pass
    finally:
        try:
            if game_sock:
                game_sock.close()
        except OSError:
            pass
        try:
            client_sock.close()
        except OSError:
            pass


def _web_gateway_client(client_sock):
    try:
        client_sock.settimeout(8)
        raw = b""
        while b"\r\n\r\n" not in raw and len(raw) < 65536:
            chunk = client_sock.recv(4096)
            if not chunk:
                return
            raw += chunk

        method, path = _http_headers(raw)
        if method != "GET":
            client_sock.sendall(_http_response(400, "text/plain", "GET required"))
            return

        request_text = raw.decode("latin1", "replace")
        headers = {}
        for line in request_text.split("\r\n")[1:]:
            if ":" in line:
                k, v = line.split(":", 1)
                headers[k.strip().lower()] = v.strip()

        if path.split("?", 1)[0] == "/health":
            body = json.dumps({
                "service": "WorldsGL Multiplayer Gateway",
                "game_port": int(port_var.get()),
                "web_port": web_port_global,
                "tunnel": tunnel_url or None,
                "status": "running" if server_running else "stopped"
            })
            client_sock.sendall(_http_response(200, "application/json", body))
            return

        route = path.split("?", 1)[0]

        if route in ("/", "/index.html"):
            jar_path = client_download_copy or (client_jar_var.get().strip() if client_jar_var is not None else "")
            jar_exists = bool(client_download_zip and os.path.isfile(client_download_zip))
            jar_name = os.path.basename(jar_path) if jar_exists else "WorldsGL_Client.jar"
            public_endpoint = websocket_public_endpoint() or "/game"
            if jar_exists:
                download_block = f"<a class='download' href='{client_download_url(jar_path)}'>Download Complete Client ZIP</a>"
                jar_status = "Complete client ZIP (JAR + lib + natives) is ready."
            else:
                download_block = "<span class='disabled'>No Client JAR has been selected on the host.</span>"
                jar_status = "The host has not selected an exported Client JAR yet."
            body = f"""<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>WorldsGL Multiplayer</title>
<style>
body{{margin:0;background:#0d0d0d;color:#e8e8e8;font-family:Segoe UI,Arial,sans-serif}}
.wrap{{max-width:760px;margin:70px auto;padding:32px}}.card{{background:#171717;border:1px solid #303030;border-radius:14px;padding:30px;box-shadow:0 12px 40px #0008}}
h1{{margin-top:0}}p{{color:#bdbdbd;line-height:1.55}}.status{{display:inline-block;padding:7px 12px;border-radius:999px;background:#163b24;color:#8ff0ad;font-size:13px}}
.endpoint{{background:#0a0a0a;border:1px solid #333;padding:12px;border-radius:8px;word-break:break-all;font-family:Consolas,monospace}}
.download{{display:inline-block;margin-top:18px;padding:12px 18px;border-radius:8px;background:#f0f0f0;color:#111;text-decoration:none;font-weight:600}}
.disabled{{color:#888}}.small{{font-size:12px;color:#777;margin-top:24px}}
</style></head><body><div class="wrap"><div class="card">
<span class="status">● ONLINE</span><h1>WorldsGL Multiplayer</h1>
<p>This server is online and ready for WorldsGL multiplayer connections.</p>
<p><strong>Complete Client Package</strong><br>{jar_status}</p>{download_block}
<p><strong>WebSocket endpoint</strong></p><div class="endpoint">{public_endpoint}</div>
<p class="small">This temporary public tunnel remains available while the host server and tunnel are running.</p>
</div></div></body></html>"""
            client_sock.sendall(_http_response(200, "text/html; charset=utf-8", body))
            return

        if route in ("/client.jar", "/download-client") or route.startswith("/download/"):
            zip_path = client_download_zip
            if not zip_path or not os.path.isfile(zip_path):
                client_sock.sendall(_http_response(
                    503,
                    "text/plain; charset=utf-8",
                    "The complete WorldsGL client package is not ready on the host."
                ))
                return

            try:
                filename = os.path.basename(zip_path).replace('"', "")
                file_size = os.path.getsize(zip_path)
                headers = (
                    "HTTP/1.1 200 OK\r\n"
                    "Content-Type: application/zip\r\n"
                    f"Content-Length: {file_size}\r\n"
                    f'Content-Disposition: attachment; filename="{filename}"\r\n'
                    "Cache-Control: no-store, no-cache, must-revalidate\r\n"
                    "Pragma: no-cache\r\n"
                    "Connection: close\r\n\r\n"
                ).encode("latin1")
                # Do not load/send the entire ZIP as one enormous socket write.
                # Large LocalTunnel transfers can stall when the server tries to
                # hand the whole archive to the socket in a single sendall().
                # The request parser uses a short 8-second socket timeout, but
                # that timeout must NOT remain active while streaming a large ZIP
                # through LocalTunnel. A slow tunnel can legitimately take longer
                # than 8 seconds between successful socket writes.
                client_sock.sendall(headers)
                client_sock.settimeout(None)
                with open(zip_path, "rb") as package_file:
                    while True:
                        chunk = package_file.read(64 * 1024)
                        if not chunk:
                            break
                        client_sock.sendall(chunk)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, TimeoutError, OSError) as exc:
                # A downloader/tunnel can close the connection while the package
                # is being streamed. Do not try to write a second HTTP response
                # onto that already-broken socket; just log the interruption.
                try:
                    gui_log(f"[CLIENT] ZIP transfer interrupted: {exc}")
                except Exception:
                    pass
            return

        if route == "/game" and headers.get("upgrade", "").lower() != "websocket":
            # A normal browser visit to /game must show the WorldsGL page, not
            # a provider/interstitial-style response. WebSocket clients still
            # upgrade below and are bridged to the game server.
            jar_path = client_download_copy or (client_jar_var.get().strip() if client_jar_var is not None else "")
            jar_exists = bool(client_download_zip and os.path.isfile(client_download_zip))
            jar_name = os.path.basename(jar_path) if jar_exists else "WorldsGL_Client.jar"
            public_endpoint = websocket_public_endpoint() or "/game"
            if jar_exists:
                download_block = f"<a class='download' href='{client_download_url(jar_path)}'>Download Complete Client ZIP</a>"
                jar_status = "Complete client ZIP (JAR + lib + natives) is ready."
            else:
                download_block = "<span class='disabled'>No Client JAR has been selected on the host.</span>"
                jar_status = "The host has not selected an exported Client JAR yet."
            body = f"""<!doctype html>
<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">
<title>WorldsGL Multiplayer</title>
<style>
body{{margin:0;background:#0d0d0d;color:#e8e8e8;font-family:Segoe UI,Arial,sans-serif}}
.wrap{{max-width:760px;margin:70px auto;padding:32px}}.card{{background:#171717;border:1px solid #303030;border-radius:14px;padding:30px;box-shadow:0 12px 40px #0008}}
h1{{margin-top:0}}p{{color:#bdbdbd;line-height:1.55}}.status{{display:inline-block;padding:7px 12px;border-radius:999px;background:#163b24;color:#8ff0ad;font-size:13px}}
.download{{display:inline-block;margin-top:18px;padding:12px 18px;border-radius:8px;background:#f0f0f0;color:#111;text-decoration:none;font-weight:600}}
.disabled{{color:#888}}.small{{font-size:12px;color:#777;margin-top:24px}}
</style></head><body><div class=\"wrap\"><div class=\"card\">
<span class=\"status\">● ONLINE</span><h1>WorldsGL Multiplayer</h1>
<p>This is the playable WorldsGL server page.</p>
<p><strong>Complete Client Package</strong><br>{jar_status}</p>{download_block}
<p class=\"small\">Open this page to obtain the complete client ZIP (JAR + lib + natives). The /game WebSocket endpoint is used automatically by the client.</p>
</div></div></body></html>"""
            client_sock.sendall(_http_response(200, "text/html; charset=utf-8", body))
            return

        if route != "/game":
            client_sock.sendall(_http_response(404, "text/plain", "Not found"))
            return

        key = headers.get("sec-websocket-key")
        if not key:
            client_sock.sendall(_http_response(400, "text/plain", "Missing WebSocket key"))
            return

        response = (
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Accept: {_websocket_accept_key(key)}\r\n\r\n"
        )
        client_sock.sendall(response.encode("latin1"))
        _websocket_bridge(client_sock)
    except socket.timeout:
        # Normal HTTP clients, tunnel health checks, and browsers may close or
        # stop responding while the header is being read.  Do not report these
        # expected timeouts as server errors.
        try:
            client_sock.close()
        except OSError:
            pass
    except OSError as exc:
        # Connection resets/early disconnects are also normal for HTTP/WebSocket
        # traffic. Keep the console quiet instead of treating them as errors.
        try:
            client_sock.close()
        except OSError:
            pass


def web_accept_loop():
    global web_server_socket
    while web_server_running and web_server_socket:
        try:
            web_server_socket.settimeout(1.0)
            sock, _ = web_server_socket.accept()
            threading.Thread(
                target=_web_gateway_client,
                args=(sock,),
                daemon=True,
                name="WorldsGL-WebGateway",
            ).start()
        except socket.timeout:
            continue
        except OSError:
            break


def start_web_server(web_port):
    global web_server_socket, web_server_running, web_port_global
    if web_server_running:
        return True, None
    try:
        web_port = int(web_port)
        if not 1 <= web_port <= 65535:
            raise ValueError
    except ValueError:
        return False, "Web/tunnel port must be between 1 and 65535."

    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("127.0.0.1", web_port))
        sock.listen(32)
    except OSError as exc:
        try:
            sock.close()
        except Exception:
            pass
        return False, f"Could not bind local web gateway on 127.0.0.1:{web_port}: {exc}"

    web_server_socket = sock
    web_server_running = True
    web_port_global = web_port
    threading.Thread(target=web_accept_loop, daemon=True, name="WorldsGL-WebGateway").start()
    gui_log(f"[WEB] Gateway listening on 127.0.0.1:{web_port}/game")
    return True, None


def stop_web_server():
    global web_server_socket, web_server_running
    web_server_running = False
    try:
        if web_server_socket:
            web_server_socket.close()
    except OSError:
        pass
    web_server_socket = None


def _find_executable(names):
    for name in names:
        path = shutil.which(name)
        if path:
            return path
    return None


def _parse_tunnel_url(line):
    """Extract a public HTTPS tunnel URL from LocalTunnel/cloudflared output."""
    text = (line or "").strip().replace("\x1b", "")
    patterns = [
        r"https?://[A-Za-z0-9.-]+\.loca\.lt(?:/[^\s\]\[<>]*)?",
        r"https?://[A-Za-z0-9.-]+\.localtunnel\.me(?:/[^\s\]\[<>]*)?",
        r"https?://[A-Za-z0-9.-]+\.trycloudflare\.com(?:/[^\s\]\[<>]*)?",
        r"[A-Za-z0-9.-]+\.trycloudflare\.com",
    ]
    for pattern in patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            value = match.group(0).rstrip("\\/).,;\"'")
            if not value.startswith("http"):
                value = "https://" + value
            return value
    return ""


def _requested_localtunnel_name():
    try:
        value = tunnel_subdomain_var.get().strip()
    except Exception:
        value = DEFAULT_TUNNEL_SUBDOMAIN
    value = re.sub(r"[^A-Za-z0-9-]", "-", value).strip("-")

    # Retail builds may never request the official WorldsGL tunnel name.
    # Compare case-insensitively so WORLDsgl-server / Worldsgl-Server cannot
    # be used to bypass the reservation.
    if value.lower() == RETAIL_RESERVED_TUNNEL_SUBDOMAIN.lower():
        gui_log(
            f"[TUNNEL] Reserved name '{RETAIL_RESERVED_TUNNEL_SUBDOMAIN}' "
            "is unavailable in the retail build. Using the retail default instead."
        )
        value = DEFAULT_TUNNEL_SUBDOMAIN

    return value or DEFAULT_TUNNEL_SUBDOMAIN


def _write_tunnel_session(process, provider, requested_subdomain=""):
    try:
        os.makedirs(CONFIG_DIR, exist_ok=True)
        payload = {
            "pid": int(process.pid),
            "provider": provider,
            "web_port": int(web_port_global),
            "subdomain": requested_subdomain,
        }
        with open(TUNNEL_SESSION_FILE, "w", encoding="utf-8") as f:
            json.dump(payload, f)
    except Exception:
        pass


def _clear_tunnel_session_file():
    try:
        os.remove(TUNNEL_SESSION_FILE)
    except OSError:
        pass


def _terminate_process_tree(pid):
    """Terminate a tracked tunnel process and its child node/npx process tree."""
    if not pid:
        return
    try:
        if os.name == "nt":
            subprocess.run(
                ["taskkill", "/PID", str(int(pid)), "/T", "/F"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
        else:
            os.kill(int(pid), 15)
    except Exception:
        pass


def _cleanup_stale_localtunnel_session():
    """Clear a previous WorldsGL LocalTunnel session before creating a new one."""
    session = None
    try:
        with open(TUNNEL_SESSION_FILE, "r", encoding="utf-8") as f:
            session = json.load(f)
    except Exception:
        session = None

    if session and str(session.get("provider", "")).lower() == "localtunnel":
        pid = session.get("pid")
        if pid:
            gui_log(f"[TUNNEL] Clearing previous LocalTunnel session (PID {pid})...")
            _terminate_process_tree(pid)
            time.sleep(0.8)
        _clear_tunnel_session_file()


def _cleanup_localtunnel_processes_for_port(port):
    """Remove orphaned LocalTunnel/npx processes belonging to this web port.

    This deliberately matches both LocalTunnel and the configured port so an
    unrelated Node process is not terminated just because Node is installed.
    """
    port_text = str(int(port))
    try:
        if os.name == "nt":
            script = (
                "$ErrorActionPreference='SilentlyContinue'; "
                "$ps=Get-CimInstance Win32_Process; "
                "foreach($p in $ps){ "
                "$c=[string]$p.CommandLine; "
                "if($c -match '(?i)localtunnel' -and $c -match '(?i)(--port|port)\\s*"
                + port_text + "'){ "
                "Stop-Process -Id $p.ProcessId -Force "
                "} }"
            )
            subprocess.run(
                ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
        else:
            subprocess.run(
                ["pkill", "-f", rf"localtunnel.*(--port|port)\s*{port_text}"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
    except Exception:
        pass


def _tunnel_output_reader(process, provider, requested_subdomain=""):
    global tunnel_url
    try:
        for raw in iter(process.stdout.readline, ""):
            if not raw:
                break
            line = raw.strip()
            if line:
                gui_log(f"[{provider.upper()}] {line}")
                url = _parse_tunnel_url(line)
                if url:
                    # Never silently accept LocalTunnel's random fallback when
                    # a named WorldsGL session was requested. That random URL
                    # would break the generated website/client distribution URL.
                    if provider == "localtunnel" and requested_subdomain:
                        host = re.sub(r"^https?://", "", url, flags=re.IGNORECASE).split("/", 1)[0].split(":", 1)[0]
                        expected = requested_subdomain.lower() + "."
                        if not host.lower().startswith(expected):
                            gui_log(
                                f"[TUNNEL] LocalTunnel returned unexpected hostname {host}; "
                                f"expected {requested_subdomain}. Clearing this session instead of using a random name."
                            )
                            _terminate_process_tree(process.pid)
                            break
                    with tunnel_lock:
                        if not tunnel_url:
                            tunnel_url = url
                    gui_log(f"[TUNNEL] Public WebSocket endpoint: {websocket_public_endpoint()}")
                    patch_selected_client()
                    refresh_tunnel_status()
    except Exception as exc:
        gui_log(f"[TUNNEL] Output monitor stopped: {exc}")
    finally:
        if process.poll() is not None:
            _clear_tunnel_session_file()


def _wait_for_tunnel(timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        with tunnel_lock:
            if tunnel_url:
                return tunnel_url
        if tunnel_process and tunnel_process.poll() is not None:
            break
        time.sleep(0.25)
    return ""


def stop_tunnel():
    global tunnel_process, tunnel_url, tunnel_provider
    with tunnel_lock:
        process = tunnel_process
        tunnel_process = None
        tunnel_url = ""
        tunnel_provider = ""
    if process:
        _terminate_process_tree(getattr(process, "pid", None))
        try:
            process.wait(timeout=3)
        except Exception:
            pass
    _clear_tunnel_session_file()
    refresh_tunnel_status()


def _launch_process(command, provider, requested_subdomain=""):
    global tunnel_process, tunnel_provider, tunnel_url
    try:
        flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        if os.name == "nt":
            flags |= getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
        process = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            stdin=subprocess.DEVNULL,
            text=True,
            bufsize=1,
            creationflags=flags,
        )
    except (OSError, ValueError) as exc:
        gui_log(f"[TUNNEL] Could not start {provider}: {exc}")
        return False

    with tunnel_lock:
        tunnel_process = process
        tunnel_provider = provider
        tunnel_url = ""
    _write_tunnel_session(process, provider, requested_subdomain)
    threading.Thread(
        target=_tunnel_output_reader,
        args=(process, provider, requested_subdomain),
        daemon=True,
        name="WorldsGL-TunnelOutput",
    ).start()
    return True


def start_localtunnel():
    # LocalTunnel can retain a released named subdomain briefly after a client
    # dies. Clean our previous/orphaned session first, then request the same
    # name again instead of allowing a random fallback URL.
    _cleanup_stale_localtunnel_session()
    _cleanup_localtunnel_processes_for_port(web_port_global)
    time.sleep(0.8)

    subdomain = _requested_localtunnel_name()
    if subdomain.lower() == RETAIL_RESERVED_TUNNEL_SUBDOMAIN.lower():
        gui_log(
            f"[TUNNEL] BLOCKED: '{RETAIL_RESERVED_TUNNEL_SUBDOMAIN}' is reserved "
            "for the official WorldsGL server and cannot be used by retail builds."
        )
        return False

    npx = _find_executable(("npx.cmd", "npx"))
    lt = _find_executable(("lt.cmd", "lt"))
    if npx:
        command = [npx, "--yes", "localtunnel", "--port", str(web_port_global), "--local-host", "127.0.0.1",
                   "--subdomain", subdomain]
        gui_log(f"[TUNNEL] Starting LocalTunnel as {subdomain}...")
        if _launch_process(command, "localtunnel", subdomain):
            return True
    if lt:
        command = [lt, "--port", str(web_port_global), "--local-host", "127.0.0.1",
                   "--subdomain", subdomain]
        gui_log(f"[TUNNEL] Starting installed LocalTunnel client as {subdomain}...")
        if _launch_process(command, "localtunnel", subdomain):
            return True
    return False

def _bundled_cloudflared_path():
    if os.name == "nt":
        return os.path.join(CONFIG_DIR, "cloudflared.exe")
    return os.path.join(CONFIG_DIR, "cloudflared")


def ensure_cloudflared():
    """Find cloudflared or download the official standalone binary if needed."""
    existing = _find_executable(("cloudflared.exe", "cloudflared"))
    if existing:
        return existing

    local = _bundled_cloudflared_path()
    if os.path.isfile(local):
        if os.name != "nt":
            try:
                os.chmod(local, 0o755)
            except OSError:
                pass
        return local

    system = os.sys.platform
    machine = os.environ.get("PROCESSOR_ARCHITECTURE", "").lower()
    if os.name == "nt":
        if "arm64" in machine or "aarch64" in machine:
            asset = "cloudflared-windows-amd64.exe"
            # Cloudflare's current Windows standalone distribution is x64.
            gui_log("[TUNNEL] Windows ARM detected; using the x64 Cloudflare binary.")
        elif "86" in machine and "64" not in machine:
            asset = "cloudflared-windows-386.exe"
        else:
            asset = "cloudflared-windows-amd64.exe"
    elif system == "linux":
        asset = "cloudflared-linux-arm64" if "aarch64" in machine or "arm64" in machine else "cloudflared-linux-amd64"
    elif system == "darwin":
        asset = "cloudflared-darwin-arm64.tgz" if "arm64" in machine else "cloudflared-darwin-amd64.tgz"
    else:
        return None

    # GitHub's official "latest" asset route follows Cloudflare releases.
    url = "https://github.com/cloudflare/cloudflared/releases/latest/download/" + asset
    try:
        os.makedirs(CONFIG_DIR, exist_ok=True)
        gui_log("[TUNNEL] cloudflared was not installed; downloading the official binary...")
        if asset.endswith(".tgz"):
            # Keep the automatic path conservative on macOS: extracting a
            # release archive requires additional handling and is less useful
            # than asking the user to install via Homebrew.
            return None
        urllib.request.urlretrieve(url, local)
        if os.path.isfile(local) and os.path.getsize(local) > 1000000:
            if os.name != "nt":
                os.chmod(local, 0o755)
            gui_log("[TUNNEL] cloudflared downloaded to " + local)
            return local
    except Exception as exc:
        gui_log(f"[TUNNEL] Automatic cloudflared download failed: {exc}")
        try:
            os.remove(local)
        except OSError:
            pass
    return None


def start_cloudflared():
    cloudflared = ensure_cloudflared()
    if not cloudflared:
        return False
    command = [cloudflared, "tunnel", "--no-autoupdate", "--url",
               f"http://127.0.0.1:{web_port_global}"]
    gui_log("[TUNNEL] Starting Cloudflare Quick Tunnel...")
    return _launch_process(command, "cloudflare")


def _tunnel_process_alive():
    with tunnel_lock:
        process = tunnel_process
    return bool(process and process.poll() is None)


def _restart_tunnel_provider(provider):
    stop_tunnel()
    if provider == "cloudflare":
        return start_cloudflared()
    return start_localtunnel()


def start_public_tunnel(provider_override=None):
    """Start the public tunnel synchronously. Call start_public_tunnel_async() from the GUI."""
    stop_tunnel()
    if not web_server_running:
        ok, error = start_web_server(web_port_global)
        if not ok:
            return False, error

    provider = (provider_override if provider_override is not None else tunnel_provider_var.get()).strip().lower()
    if provider == "automatic":
        providers = [("cloudflare", start_cloudflared), ("localtunnel", start_localtunnel)]
    elif provider == "localtunnel":
        providers = [("localtunnel", start_localtunnel)]
    else:
        providers = [("cloudflare", start_cloudflared)]

    for provider_name, starter in providers:
        stop_tunnel()
        gui_log(f"[TUNNEL] Starting {provider_name.title()}... (this may take a moment)")
        if not starter():
            gui_log(f"[TUNNEL] {provider_name} could not be started; trying the next provider.")
            continue

        url = _wait_for_tunnel(90)
        if url:
            gui_log(f"[TUNNEL] READY: {url}/game")
            patch_selected_client()
            refresh_tunnel_status()
            return True, None

        with tunnel_lock:
            process_alive = tunnel_process is not None and tunnel_process.poll() is None
        if process_alive:
            gui_log(f"[TUNNEL] {provider_name} is still running; continuing to monitor for its public URL.")
            refresh_tunnel_status()
            return True, None
        gui_log(f"[TUNNEL] {provider_name} exited without publishing a public URL; trying the next provider.")

    stop_tunnel()
    return False, "No public tunnel could be established. See the Server log for provider output."


def start_public_tunnel_async(provider_override=None):
    """Run tunnel deployment away from Tk's event loop so the GUI stays responsive."""
    provider = provider_override
    if provider is None:
        provider = tunnel_provider_var.get().strip()
    deployment_set_busy("Deploying public tunnel...", True)

    def worker():
        try:
            ok, error = start_public_tunnel(provider)
        except Exception as exc:
            ok, error = False, str(exc)
            gui_log(f"[TUNNEL] Deployment error: {exc}")

        def finished():
            deployment_set_busy("Public tunnel ready." if ok else "Public tunnel deployment failed.", False)
            refresh_tunnel_status()
            if not ok and root is not None:
                messagebox.showerror("Public Tunnel", error or "Unable to create the public tunnel.")

        if root is not None:
            root.after(0, finished)

    threading.Thread(target=worker, daemon=True, name="WorldsGL-TunnelDeploy").start()
    return True

def _build_complete_client_zip(jar_copy, source_root):
    """Build a complete downloadable client package without replacing a live ZIP."""
    global client_download_zip

    if not jar_copy or not os.path.isfile(jar_copy):
        return False, "Client JAR download copy does not exist."

    source_root = os.path.abspath(source_root or "")
    lib_dir = os.path.join(source_root, "lib")
    natives_dir = os.path.join(source_root, "natives")

    if not os.path.isdir(lib_dir):
        return False, f"Missing required lib directory: {lib_dir}"
    if not os.path.isdir(natives_dir):
        return False, f"Missing required natives directory: {natives_dir}"

    download_dir = os.path.join(CONFIG_DIR, "web_downloads")
    os.makedirs(download_dir, exist_ok=True)

    base = os.path.splitext(os.path.basename(jar_copy))[0]
    # Never overwrite the ZIP currently being served by a browser/tunnel.
    zip_name = f"{base}_Complete.zip"
    destination = os.path.join(download_dir, zip_name)
    if os.path.exists(destination):
        stamp = time.strftime("%Y%m%d-%H%M%S")
        destination = os.path.join(download_dir, f"{base}_Complete_{stamp}.zip")

    temp_name = destination + f".{os.getpid()}.{threading.get_ident()}.tmp"

    try:
        with zipfile.ZipFile(temp_name, "w", zipfile.ZIP_DEFLATED) as zout:
            zout.write(jar_copy, arcname=os.path.basename(jar_copy))

            for folder_name, folder_path in (("lib", lib_dir), ("natives", natives_dir)):
                for root_dir, _, filenames in os.walk(folder_path):
                    for filename in filenames:
                        full_path = os.path.join(root_dir, filename)
                        relative = os.path.relpath(full_path, folder_path)
                        zout.write(full_path, arcname=os.path.join(folder_name, relative))

        # Prefer a new filename, so an existing ZIP held open by the browser is
        # never replaced. The only replace here is the brand-new temporary file.
        os.replace(temp_name, destination)
        client_download_zip = destination
        return True, destination
    except Exception as exc:
        try:
            os.remove(temp_name)
        except OSError:
            pass
        return False, str(exc)


def prepare_client_download_copy(jar_path, source_root=None):
    """Prepare the JAR copy and complete JAR+lib+natives ZIP for web download."""
    global client_download_copy, client_download_zip, client_jar_path

    jar_path = (jar_path or "").strip()
    if not jar_path or not os.path.isfile(jar_path):
        client_download_copy = ""
        client_download_zip = ""
        client_jar_path = jar_path
        return False

    try:
        download_dir = os.path.join(CONFIG_DIR, "web_downloads")
        os.makedirs(download_dir, exist_ok=True)

        filename = os.path.basename(jar_path)
        if not filename.lower().endswith(".jar"):
            filename += ".jar"

        destination = os.path.join(download_dir, filename)

        # Do not copy a file onto itself.
        if os.path.abspath(jar_path) != os.path.abspath(destination):
            shutil.copy2(jar_path, destination)

        client_jar_path = jar_path
        client_download_copy = destination

        if source_root is None:
            source_root = os.path.dirname(os.path.abspath(jar_path))

        ok, result = _build_complete_client_zip(destination, source_root)
        if not ok:
            client_download_zip = ""
            gui_log(f"[CLIENT] Could not prepare complete download package: {result}")
            return False

        gui_log(f"[CLIENT] Complete web download ready: {os.path.basename(result)}")
        return True
    except Exception as exc:
        client_download_copy = ""
        client_download_zip = ""
        try:
            gui_log(f"[CLIENT] Could not prepare complete download package: {exc}")
        except Exception:
            pass
        return False


def client_download_url(jar_path):
    """Return the browser URL for the complete client ZIP package."""
    package_name = os.path.basename(client_download_zip or "")
    if not package_name:
        base = os.path.splitext(os.path.basename(jar_path or "WorldsGL_Client.jar"))[0]
        package_name = base + "_Complete.zip"
    return "/download/" + urllib.parse.quote(package_name)


def patch_client_jar_endpoint(jar_path, endpoint):
    if not jar_path or not os.path.isfile(jar_path):
        return False, "Client JAR does not exist."
    endpoint = endpoint.rstrip("/")
    if not endpoint.startswith(("http://", "https://", "ws://", "wss://")):
        return False, "Tunnel endpoint is not a valid URL."

    if endpoint.startswith("https://"):
        endpoint = "wss://" + endpoint[len("https://"):]
    elif endpoint.startswith("http://"):
        endpoint = "ws://" + endpoint[len("http://"):]

    if not endpoint.endswith("/game"):
        endpoint += "/game"

    temp_name = jar_path + f".{os.getpid()}.{threading.get_ident()}.tmp"
    try:
        with zipfile.ZipFile(jar_path, "r") as zin, zipfile.ZipFile(
            temp_name, "w", zipfile.ZIP_DEFLATED
        ) as zout:
            replaced = False
            for item in zin.infolist():
                if item.filename == "worldsgl_client_endpoint.txt":
                    data = (endpoint + "\n").encode("utf-8")
                    zout.writestr(item, data)
                    replaced = True
                else:
                    zout.writestr(item, zin.read(item.filename))
            if not replaced:
                zout.writestr("worldsgl_client_endpoint.txt", endpoint + "\n")
        os.replace(temp_name, jar_path)
        return True, endpoint
    except Exception as exc:
        try:
            os.remove(temp_name)
        except OSError:
            pass
        return False, str(exc)


def patch_selected_client():
    """Patch only the web-served JAR copy and rebuild its complete ZIP."""
    selected = client_jar_var.get().strip() if client_jar_var is not None else ""
    if not selected or not os.path.isfile(selected) or not tunnel_url:
        return

    source_root = os.path.dirname(os.path.abspath(selected))
    if not prepare_client_download_copy(selected, source_root):
        gui_log(f"[CLIENT] Could not prepare complete download package: {selected}")
        return

    endpoint = websocket_public_endpoint() or tunnel_url
    ok, result = patch_client_jar_endpoint(client_download_copy, endpoint)
    if not ok:
        gui_log(f"[CLIENT] Could not patch download copy: {result}")
        return

    # Rebuild from the now-patched web copy, using the original JAR's sibling
    # lib/natives directories. This avoids ever touching the original JAR.
    ok, package = _build_complete_client_zip(client_download_copy, source_root)
    if ok:
        gui_log(f"[CLIENT] Complete web download ready: {os.path.basename(package)} -> {result}")
    else:
        gui_log(f"[CLIENT] Could not prepare complete download package: {package}")


def choose_client_jar():
    path = filedialog.askopenfilename(
        title="Select exported WorldsGL client JAR",
        filetypes=[("Java JAR", "*.jar"), ("All files", "*.*")]
    )
    if path:
        client_jar_var.set(path)
        prepare_client_download_copy(path)
        if tunnel_url:
            patch_selected_client()


def choose_server_jar():
    path = filedialog.askopenfilename(
        title="Select WorldsGL server/host JAR",
        filetypes=[("Java JAR", "*.jar"), ("All files", "*.*")]
    )
    if path:
        server_jar_var.set(path)


def launch_server_jar():
    global server_jar_process
    path = server_jar_var.get().strip()
    if not path:
        return
    if not os.path.isfile(path):
        messagebox.showerror("Server JAR", "Selected server JAR does not exist.")
        return
    if server_jar_process and server_jar_process.poll() is None:
        gui_log("[SERVER JAR] Host JAR is already running.")
        return

    java = _find_executable(("java.exe", "java"))
    if not java:
        messagebox.showerror("Server JAR", "Java was not found on PATH.")
        return

    username = host_username_var.get().strip() or "Host"
    local_ws = f"ws://127.0.0.1:{web_port_global}/game"
    command = [
        java,
        f"-Dworldsgl.serverHost=true",
        f"-Dworldsgl.username={username}",
        f"-Dworldsgl.server={local_ws}",
        "-jar",
        path,
        "--worldsgl-exported",
    ]
    try:
        server_jar_process = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            stdin=subprocess.DEVNULL,
            text=True,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        threading.Thread(
            target=_server_jar_output_reader,
            daemon=True,
            name="WorldsGL-ServerJar",
        ).start()
        gui_log(f"[SERVER JAR] Launched {os.path.basename(path)}")
    except OSError as exc:
        messagebox.showerror("Server JAR", str(exc))


def _server_jar_output_reader():
    process = server_jar_process
    if not process or not process.stdout:
        return
    try:
        for line in process.stdout:
            line = line.rstrip()
            if line:
                gui_log("[SERVER JAR] " + line)
    except Exception:
        pass


def stop_server_jar():
    global server_jar_process
    process = server_jar_process
    server_jar_process = None
    if process:
        try:
            process.terminate()
            process.wait(timeout=3)
        except Exception:
            try:
                process.kill()
            except Exception:
                pass


def refresh_tunnel_status():
    if root is None or tunnel_status_var is None:
        return
    def update():
        with tunnel_lock:
            url = tunnel_url
            provider = tunnel_provider
        if url:
            tunnel_status_var.set(f"● PUBLIC  |  {provider}  |  {websocket_public_endpoint()}")
        elif tunnel_process and tunnel_process.poll() is None:
            tunnel_status_var.set("● STARTING TUNNEL...")
        else:
            tunnel_status_var.set("● NO PUBLIC TUNNEL")
    root.after(0, update)


def deployment_set_busy(message, busy):
    """Update the deployment indicator without blocking the Tk event loop."""
    if root is None:
        return
    def update():
        if deployment_status_var is not None:
            deployment_status_var.set(message)
        if deployment_progress is not None:
            if busy:
                deployment_progress.start(12)
            else:
                deployment_progress.stop()
        if start_button is not None:
            start_button.configure(state="disabled" if busy else "normal")
    root.after(0, update)


# ---------------------------------------------------------------------------
# Tkinter GUI
# ---------------------------------------------------------------------------
root = None
bind_var = None
port_var = None
name_var = None
server_entry_var = None
status_var = None
players_tree = None
player_menu = None
log_text = None
saved_list = None
web_port_var = None
tunnel_subdomain_var = None
tunnel_provider_var = None
tunnel_status_var = None
deployment_status_var = None
deployment_progress = None
start_button = None
client_jar_var = None
server_jar_var = None
host_username_var = None
auto_tunnel_var = None


def gui_log(message):
    if root is None or log_text is None:
        return
    def append():
        try:
            log_text.configure(state="normal")
            log_text.insert("end", message + "\n")
            log_text.see("end")
            log_text.configure(state="disabled")
        except tk.TclError:
            pass
    root.after(0, append)


def refresh_status():
    if root is None:
        return
    def update():
        if status_var is None:
            return
        if server_running:
            with lock:
                count = len(clients)
            status_var.set(f"● RUNNING  |  {bind_var.get()}:{port_var.get()}  |  {count} player(s)")
        else:
            status_var.set("● STOPPED")
    root.after(0, update)


def refresh_players():
    if root is None or players_tree is None:
        return
    def update():
        try:
            for item in players_tree.get_children():
                players_tree.delete(item)
            with lock:
                rows = list(clients.values())
                admin_snapshot = set(admin_users)
                muted_snapshot = set(muted_users)
            for username in rows:
                flags = []
                if username in admin_snapshot:
                    flags.append("Admin/Host")
                if username in muted_snapshot:
                    flags.append("Muted")
                players_tree.insert("", "end", values=(username, ", ".join(flags) or "Connected"))
            refresh_status()
        except tk.TclError:
            pass
    root.after(0, update)


def player_context_action(action):
    selection = players_tree.selection()
    if not selection:
        return
    values = players_tree.item(selection[0], "values")
    if not values:
        return
    admin_action(values[0], action)


def show_player_context_menu(event):
    item = players_tree.identify_row(event.y)
    if not item:
        return
    players_tree.selection_set(item)
    player_menu.tk_popup(event.x_root, event.y_root)


def saved_entries():
    entries = []
    for item in saved_list.get_children():
        values = saved_list.item(item, "values")
        if len(values) >= 3:
            entries.append({"name": values[0], "host": values[1], "port": int(values[2])})
    return entries


def populate_saved(entries):
    for item in saved_list.get_children():
        saved_list.delete(item)
    for entry in entries:
        saved_list.insert("", "end", values=(
            entry.get("name", "Server"),
            entry.get("host", "127.0.0.1"),
            entry.get("port", DEFAULT_PORT),
        ))


def add_saved_server():
    name = name_var.get().strip() or "Server"
    host = server_entry_var.get().strip()
    try:
        port = int(port_var.get())
        if not 1 <= port <= 65535:
            raise ValueError
    except ValueError:
        messagebox.showerror("Invalid port", "Port must be between 1 and 65535.")
        return
    if not host:
        messagebox.showerror("Invalid server", "Enter a server IP/address.")
        return
    saved_list.insert("", "end", values=(name, host, port))
    save_saved_servers(saved_entries())


def remove_saved_server():
    selection = saved_list.selection()
    if not selection:
        return
    for item in selection:
        saved_list.delete(item)
    save_saved_servers(saved_entries())


def use_saved_server():
    selection = saved_list.selection()
    if not selection:
        return
    values = saved_list.item(selection[0], "values")
    if len(values) < 3:
        return
    name_var.set(values[0])
    server_entry_var.set(values[1])
    port_var.set(values[2])


def toggle_tunnel():
    if tunnel_process and tunnel_process.poll() is None:
        stop_tunnel()
        gui_log("[TUNNEL] Stopped.")
        deployment_set_busy("Ready", False)
        return
    start_public_tunnel_async(tunnel_provider_var.get().strip())

def copy_tunnel_url():
    if not tunnel_url:
        return
    try:
        root.clipboard_clear()
        root.clipboard_append(websocket_public_endpoint())
        root.update()
        gui_log("[TUNNEL] WebSocket endpoint copied to clipboard.")
    except tk.TclError:
        pass


def toggle_server():
    if server_running:
        stop_server()
        start_button.configure(text="Start Server")
        tunnel_button.configure(text="Create Public Tunnel")
        deployment_set_busy("Ready", False)
        return

    bind_host = bind_var.get()
    port = port_var.get()
    try:
        web_port = int(web_port_var.get())
    except ValueError:
        web_port = DEFAULT_WEB_PORT

    deployment_set_busy("Starting server and web gateway...", True)
    gui_log("[DEPLOY] Starting server deployment in background; the GUI will remain responsive.")

    def worker():
        try:
            ok, error = start_server(bind_host, port, web_port)
        except Exception as exc:
            ok, error = False, str(exc)
            gui_log(f"[SERVER] Startup error: {exc}")

        def finished():
            if not ok:
                deployment_set_busy("Server startup failed.", False)
                messagebox.showerror("WorldsGL Server", error or "Unable to start the server.")
                return
            start_button.configure(text="Stop Server", state="normal")
            deployment_set_busy("Server online." if not auto_tunnel_var.get() else "Server online — deploying public tunnel...", False)
            if auto_tunnel_var.get():
                start_public_tunnel_async(tunnel_provider_var.get().strip())

        if root is not None:
            root.after(0, finished)

    threading.Thread(target=worker, daemon=True, name="WorldsGL-ServerDeploy").start()

def on_close():
    stop_tunnel()
    if server_running:
        stop_server()
    else:
        stop_server_jar()
        stop_tunnel()
        stop_web_server()
    root.destroy()


def build_gui():
    # Each server.py launch starts a fresh tunnel session.
    _cleanup_stale_localtunnel_session()
    _cleanup_localtunnel_processes_for_port(DEFAULT_WEB_PORT)
    global root, bind_var, port_var, name_var, server_entry_var
    global status_var, players_tree, player_menu, log_text, saved_list, start_button
    global web_port_var, tunnel_subdomain_var, tunnel_provider_var
    global tunnel_status_var, deployment_status_var, deployment_progress, client_jar_var, server_jar_var, host_username_var, auto_tunnel_var

    root = tk.Tk()
    root.title("WorldsGL Multiplayer Server — Retail")
    root.geometry("1100x700")
    root.minsize(900, 600)
    root.protocol("WM_DELETE_WINDOW", on_close)

    style = ttk.Style(root)
    try:
        style.theme_use("clam")
    except tk.TclError:
        pass

    bind_var = tk.StringVar(value=DEFAULT_HOST)
    port_var = tk.StringVar(value=str(DEFAULT_PORT))
    name_var = tk.StringVar(value="Local Server")
    server_entry_var = tk.StringVar(value="127.0.0.1")
    status_var = tk.StringVar(value="● STOPPED")
    web_port_var = tk.StringVar(value=str(DEFAULT_WEB_PORT))
    tunnel_subdomain_var = tk.StringVar(value=DEFAULT_TUNNEL_SUBDOMAIN)
    tunnel_provider_var = tk.StringVar(value="Automatic")
    tunnel_status_var = tk.StringVar(value="● NO PUBLIC TUNNEL")
    deployment_status_var = tk.StringVar(value="Ready")
    client_jar_var = tk.StringVar(value="")
    server_jar_var = tk.StringVar(value="")
    host_username_var = tk.StringVar(value="Host")
    auto_tunnel_var = tk.BooleanVar(value=True)

    outer = ttk.Frame(root, padding=12)
    outer.pack(fill="both", expand=True)

    title = ttk.Label(outer, text="WorldsGL Multiplayer Server — Retail", font=("Segoe UI", 18, "bold"))
    title.pack(anchor="w", pady=(0, 8))

    config = ttk.LabelFrame(outer, text="Server configuration", padding=10)
    config.pack(fill="x")

    ttk.Label(config, text="Bind IP / Host:").grid(row=0, column=0, sticky="w", padx=5, pady=5)
    ttk.Entry(config, textvariable=bind_var, width=20).grid(row=0, column=1, sticky="w", padx=5, pady=5)
    ttk.Label(config, text="Port:").grid(row=0, column=2, sticky="w", padx=5, pady=5)
    ttk.Entry(config, textvariable=port_var, width=9).grid(row=0, column=3, sticky="w", padx=5, pady=5)

    start_button = ttk.Button(config, text="Start Server", command=toggle_server)
    start_button.grid(row=0, column=4, padx=12, pady=5)
    ttk.Label(config, textvariable=status_var).grid(row=0, column=5, sticky="w", padx=5, pady=5)

    public = ttk.LabelFrame(outer, text="Public tunnel / JAR deployment", padding=10)
    public.pack(fill="x", pady=(10, 8))

    ttk.Label(public, text="Gateway Port:").grid(row=0, column=0, sticky="w", padx=5, pady=4)
    ttk.Entry(public, textvariable=web_port_var, width=9).grid(row=0, column=1, sticky="w", padx=5, pady=4)
    ttk.Label(public, text="Tunnel:").grid(row=0, column=2, sticky="w", padx=5, pady=4)
    ttk.Combobox(public, textvariable=tunnel_provider_var,
                 values=("Automatic", "LocalTunnel", "Cloudflare"),
                 state="readonly", width=13).grid(row=0, column=3, sticky="w", padx=5, pady=4)
    ttk.Label(public, text="Subdomain:").grid(row=0, column=4, sticky="w", padx=5, pady=4)
    ttk.Entry(public, textvariable=tunnel_subdomain_var, width=18).grid(row=0, column=5, sticky="w", padx=5, pady=4)
    tunnel_button = ttk.Button(public, text="Create Public Tunnel", command=toggle_tunnel)
    tunnel_button.grid(row=0, column=6, padx=7, pady=4)
    ttk.Button(public, text="Copy Endpoint", command=copy_tunnel_url).grid(row=0, column=7, padx=5, pady=4)

    ttk.Label(public, textvariable=tunnel_status_var).grid(
        row=1, column=0, columnspan=8, sticky="w", padx=5, pady=4
    )
    ttk.Label(public, textvariable=deployment_status_var).grid(
        row=1, column=5, columnspan=3, sticky="e", padx=5, pady=4
    )
    deployment_progress = ttk.Progressbar(public, mode="indeterminate", length=180)
    deployment_progress.grid(row=2, column=5, columnspan=3, sticky="e", padx=5, pady=4)
    ttk.Checkbutton(public, text="Create tunnel automatically when server starts",
                    variable=auto_tunnel_var).grid(row=3, column=0, columnspan=4, sticky="w", padx=5, pady=4)

    ttk.Label(public, text="Client JAR:").grid(row=4, column=0, sticky="w", padx=5, pady=4)
    ttk.Entry(public, textvariable=client_jar_var, width=48).grid(row=4, column=1, columnspan=5, sticky="ew", padx=5, pady=4)
    ttk.Button(public, text="Browse", command=choose_client_jar).grid(row=4, column=6, padx=5, pady=4)
    ttk.Button(public, text="Patch Endpoint", command=patch_selected_client).grid(row=4, column=7, padx=5, pady=4)

    ttk.Label(public, text="Host/Server JAR:").grid(row=5, column=0, sticky="w", padx=5, pady=4)
    ttk.Entry(public, textvariable=server_jar_var, width=48).grid(row=5, column=1, columnspan=5, sticky="ew", padx=5, pady=4)
    ttk.Button(public, text="Browse", command=choose_server_jar).grid(row=5, column=6, padx=5, pady=4)
    ttk.Button(public, text="Launch Host JAR", command=launch_server_jar).grid(row=5, column=7, padx=5, pady=4)

    ttk.Label(public, text="Host username:").grid(row=6, column=0, sticky="w", padx=5, pady=4)
    ttk.Entry(public, textvariable=host_username_var, width=20).grid(row=6, column=1, sticky="w", padx=5, pady=4)

    # Saved IP/endpoint management is intentionally omitted from the main
    # server window. Public tunnel endpoint, connected players, and server log
    # are the operational controls that matter while hosting. The legacy
    # saved-server functions remain available in the source for compatibility.

    bottom = ttk.Frame(outer)
    bottom.pack(fill="both", expand=True, pady=(4, 0))

    players_box = ttk.LabelFrame(bottom, text="Connected players", padding=8)
    players_box.pack(side="left", fill="both", expand=True, padx=(0, 5))
    players_tree = ttk.Treeview(players_box, columns=("username", "status"), show="headings")
    players_tree.heading("username", text="Username")
    players_tree.heading("status", text="Status")
    players_tree.column("username", width=180)
    players_tree.column("status", width=180)
    players_tree.pack(fill="both", expand=True)
    player_menu = tk.Menu(root, tearoff=0)
    player_menu.add_command(label="Is Admin/Host?", command=lambda: player_context_action("admin"))
    player_menu.add_command(label="Mute / Unmute", command=lambda: player_context_action("mute"))
    player_menu.add_command(label="Warn", command=lambda: player_context_action("warn"))
    player_menu.add_separator()
    player_menu.add_command(label="Kick", command=lambda: player_context_action("kick"))
    players_tree.bind("<Button-3>", show_player_context_menu)

    log_box = ttk.LabelFrame(bottom, text="Server log", padding=8)
    log_box.pack(side="right", fill="both", expand=True, padx=(5, 0))
    log_text = tk.Text(log_box, height=10, wrap="word", state="disabled")
    log_text.pack(fill="both", expand=True)

    gui_log("[GUI] WorldsGL multiplayer server ready (retail build).")
    gui_log("[GUI] Use 0.0.0.0 to accept game connections on all local interfaces.")
    gui_log("[GUI] Public mode serves the complete Client ZIP (JAR + lib + natives) and secure WSS /game endpoint through the tunnel.")
    gui_log("[GUI] Automatic tunnel uses Cloudflare first to avoid LocalTunnel interstitial pages; LocalTunnel remains available explicitly.")

    def periodic_refresh():
        refresh_players()
        if root.winfo_exists():
            root.after(1000, periodic_refresh)
    root.after(1000, periodic_refresh)

    root.mainloop()


if __name__ == "__main__":
    build_gui()
