#!/usr/bin/env python3
"""Report webCoRE piston pause state and disabled statements straight from the hub.

Neither fact is in the Automation Map export. Pause is not published as a boolean by
webCoRE (unlike Rule Machine, which keeps `paused` in its own app state); webCoRE renders
it into the app label instead, and the export strips label markup. Disabled statements are
read during decoding but only to drop them, so the export cannot show which parts were
skipped.

Usage:
    python webcore-piston-state.py <hub-ip>
    HUB_IP=<hub-ip> python webcore-piston-state.py

Read-only. Needs the hub's local endpoints reachable without a login.
"""

import base64
import json
import os
import re
import sys
import urllib.request

PAUSED = re.compile(r"<span[^>]*>[^<]*paused[^<]*</span>", re.I)
TAGS = re.compile(r"<[^>]+>")


def get(hub, path):
    with urllib.request.urlopen(f"http://{hub}/{path}", timeout=20) as r:
        return json.load(r)


def pistons(hub):
    """Every webCoRE piston, with pause read from the label markup."""
    found, stack = [], list(get(hub, "hub2/appsList")["apps"])
    while stack:
        node = stack.pop()
        data = node.get("data") or {}
        if data.get("type") == "webCoRE Piston":
            found.append({
                "id": data["id"],
                "name": TAGS.sub("", str(data["name"])).strip(),
                "paused": bool(PAUSED.search(str(data["name"]))),
                "disabled": bool(data.get("disabled")),
            })
        stack.extend(node.get("children") or [])
    return sorted(found, key=lambda p: p["id"])


def saved_piston(hub, piston_id):
    """The saved piston document: base64 JSON split across chunk:N settings."""
    settings = get(hub, f"installedapp/configure/json/{piston_id}")["settings"]
    keys = sorted((k for k in settings if k.startswith("chunk:")),
                  key=lambda k: int(k.split(":")[1]))
    blob = "".join(
        (settings[k].get("value") if isinstance(settings[k], dict) else settings[k]) or ""
        for k in keys
    )
    return json.loads(base64.b64decode(blob)) if blob else None


def statements(node, path="", disabled=None, total=None):
    """Walk the statement tree. `di` marks a disabled statement and takes its subtree."""
    if disabled is None:
        disabled, total = [], []
    if isinstance(node, dict):
        if "t" in node and "$" in node:
            path = f"{path}/{node['$']}"
            total.append(path)
            if node.get("di"):
                disabled.append({"statement": path, "type": node.get("t")})
        for value in node.values():
            statements(value, path, disabled, total)
    elif isinstance(node, list):
        for value in node:
            statements(value, path, disabled, total)
    return disabled, total


def main():
    hub = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("HUB_IP")
    if not hub:
        sys.exit("usage: webcore-piston-state.py <hub-ip>   (or set HUB_IP)")
    for piston in pistons(hub):
        try:
            off, all_stmts = statements((saved_piston(hub, piston["id"]) or {}).get("s"))
        except Exception as exc:
            print(f'{piston["id"]}  ERROR {exc}')
            continue
        print(f'{piston["id"]}  paused={str(piston["paused"]):5} '
              f'disabled={str(piston["disabled"]):5} statements={len(all_stmts):3} '
              f'disabled_statements={len(off)}  {piston["name"][:44]}')
        for item in off:
            print(f'        di -> {item["statement"]} ({item["type"]})')


if __name__ == "__main__":
    main()
