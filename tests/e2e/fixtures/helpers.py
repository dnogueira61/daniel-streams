"""
Common Test Fixtures & Utilities for E2E Test Suite
"""

import os
import sys
import json
import ssl
import subprocess
import urllib.request
import urllib.error
from typing import Dict, Any, Optional, Tuple

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
CHANNELS_JSON_PATH = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets", "channels.json")
APP_SRC_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "java", "com", "dlive", "ptstream")
MOCK_CHANNELS_PATH = os.path.join(os.path.dirname(__file__), "mock_channels.json")
DOM_HARNESS_PATH = os.path.join(os.path.dirname(__file__), "dom_sandbox_harness.js")

def get_ssl_context():
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx

class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, hdrs, newurl):
        return None

def probe_http_endpoint(
    url: str,
    headers: Optional[Dict[str, str]] = None,
    timeout: float = 5.0,
    allow_redirects: bool = True
) -> Tuple[int, Dict[str, str], bytes]:
    """
    Probe an HTTP endpoint, returning (status_code, response_headers, body_snippet).
    Handles redirects according to allow_redirects.
    """
    default_headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
    if headers:
        default_headers.update(headers)

    req = urllib.request.Request(url, headers=default_headers)
    ctx = get_ssl_context()

    if allow_redirects:
        opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=ctx))
    else:
        opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=ctx), NoRedirectHandler)

    try:
        with opener.open(req, timeout=timeout) as resp:
            status = getattr(resp, "status", 200)
            resp_headers = {k.lower(): v for k, v in resp.headers.items()}
            body = resp.read(1048576)
            return status, resp_headers, body
    except urllib.error.HTTPError as e:
        resp_headers = {k.lower(): v for k, v in e.headers.items()} if e.headers else {}
        body = e.read(1048576) if hasattr(e, "read") else b""
        return e.code, resp_headers, body
    except Exception as e:
        return 0, {"error": str(e)}, b""

def load_master_channels():
    if not os.path.exists(CHANNELS_JSON_PATH):
        raise FileNotFoundError(f"channels.json not found at {CHANNELS_JSON_PATH}")
    with open(CHANNELS_JSON_PATH, "r", encoding="utf-8") as f:
        return json.load(f)

def run_node_script(script_body: str, timeout: float = 5.0) -> Tuple[int, str, str]:
    """
    Executes a Node.js snippet importing dom_sandbox_harness.js.
    """
    full_script = f"""
    const harness = require({json.dumps(DOM_HARNESS_PATH)});
    {script_body}
    """
    proc = subprocess.run(
        ["node", "-e", full_script],
        capture_output=True,
        text=True,
        timeout=timeout,
        cwd=PROJECT_ROOT
    )
    return proc.returncode, proc.stdout, proc.stderr

def run_gradle_command(task: str, timeout: float = 60.0) -> Tuple[int, str, str]:
    """
    Executes a gradle task with proper JAVA_HOME and PATH.
    """
    env = os.environ.copy()
    jbr_path = r"C:\Program Files\Android\Android Studio\jbr"
    if os.path.exists(jbr_path):
        env["JAVA_HOME"] = jbr_path
        env["PATH"] = os.path.join(jbr_path, "bin") + os.pathsep + env.get("PATH", "")

    gradlew = os.path.join(PROJECT_ROOT, "gradlew.bat" if sys.platform == "win32" else "gradlew")
    proc = subprocess.run(
        [gradlew, task, "--no-daemon", "--stacktrace"],
        capture_output=True,
        text=True,
        timeout=timeout,
        cwd=PROJECT_ROOT,
        env=env
    )
    return proc.returncode, proc.stdout, proc.stderr
