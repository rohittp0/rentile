#!/usr/bin/env python3
"""Loopback-only HTTPS bridge for the opt-in iOS corpus test.

The installed iOS simulator runtimes currently reject several live provider
certificate chains which macOS accepts. The bridge validates upstream TLS with
system curl and never logs paths or query strings. Production code is not
involved and cannot enable this bridge.
"""

from __future__ import annotations

import argparse
import http.server
import pathlib
import subprocess
import tempfile
import urllib.parse


ALLOWED_ORIGINS = {
    "https://api.maptiler.com",
    "https://dashboard.lascade.com",
    "https://server.arcgisonline.com",
    "https://tiles.stadiamaps.com",
}
FETCH_PATH_PREFIX = "/fetch/"
REQUEST_HEADERS = ("Accept", "If-Modified-Since", "If-None-Match")
RESPONSE_HEADERS = (
    "Cache-Control",
    "Content-Type",
    "ETag",
    "Last-Modified",
    "Location",
)


class HttpsBridge(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        target_url = decode_target_url(self.path)
        if target_url is None:
            self.send_error(403)
            return

        with tempfile.TemporaryDirectory(prefix="rentile-https-bridge-") as temp_dir:
            headers_path = pathlib.Path(temp_dir) / "headers"
            body_path = pathlib.Path(temp_dir) / "body"
            command = [
                "/usr/bin/curl",
                "--silent",
                "--show-error",
                "--max-time",
                "60",
                "--proto",
                "=https",
                "--dump-header",
                str(headers_path),
                "--output",
                str(body_path),
                "--write-out",
                "%{http_code}",
                "--config",
                "-",
            ]
            for name in REQUEST_HEADERS:
                value = self.headers.get(name)
                if value is not None:
                    command.extend(("--header", f"{name}: {value}"))
            curl_config = f'url = "{curl_config_value(target_url)}"\n'
            result = subprocess.run(
                command,
                capture_output=True,
                check=False,
                input=curl_config,
                text=True,
            )
            if result.returncode != 0 or not result.stdout.isdigit():
                self.send_error(502)
                return

            response_headers = parse_response_headers(headers_path.read_text())
            body = body_path.read_bytes()
            self.send_response(int(result.stdout))
            for name in RESPONSE_HEADERS:
                value = response_headers.get(name.lower())
                if value is not None:
                    self.send_header(name, value)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)

    def log_message(self, _format: str, *args: object) -> None:
        return


def decode_target_url(request_path: str) -> str | None:
    if not request_path.startswith(FETCH_PATH_PREFIX):
        return None
    encoded = request_path.removeprefix(FETCH_PATH_PREFIX)
    try:
        target_url = bytes.fromhex(encoded).decode("utf-8")
    except (UnicodeDecodeError, ValueError):
        return None
    if "\r" in target_url or "\n" in target_url:
        return None
    parsed = urllib.parse.urlsplit(target_url)
    origin = f"{parsed.scheme}://{parsed.netloc}"
    if origin not in ALLOWED_ORIGINS or parsed.username is not None or parsed.password is not None:
        return None
    return target_url


def curl_config_value(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"')


def parse_response_headers(raw_headers: str) -> dict[str, str]:
    parsed: dict[str, str] = {}
    for line in raw_headers.splitlines():
        if ":" not in line:
            continue
        name, value = line.split(":", 1)
        parsed[name.strip().lower()] = value.strip()
    return parsed


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    server = http.server.ThreadingHTTPServer(("127.0.0.1", args.port), HttpsBridge)
    print(f"Rentile HTTPS bridge ready on 127.0.0.1:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
