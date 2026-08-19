#!/usr/bin/env python3
"""
Harvest Hubitat's current compatible-device catalogue into Automation Map format.

Why this script exists:
- Hubitat's documentation page is authoritative but currently dynamically rendered.
- No documented machine-readable feed is exposed.
- The registry should therefore be data-driven and refreshable, not hard-coded.

Usage:
    python harvest_hubitat_compatible_devices.py --out hubitat_devices.json

Dependencies:
    pip install playwright beautifulsoup4
    playwright install chromium

The parser deliberately preserves raw row data if Hubitat changes the page layout.
Review generated records before promoting them to a production registry.
"""

import argparse, json, re
from pathlib import Path
from bs4 import BeautifulSoup
from playwright.sync_api import sync_playwright

URL = "https://docs2.hubitat.com/en/devices/list-of-compatible-devices"

def clean(s):
    return re.sub(r"\s+", " ", s or "").strip()

def normalise_protocol(text):
    t = text.lower()
    if "zigbee" in t: return "ZIGBEE"
    if "z-wave" in t or "zwave" in t: return "ZWAVE"
    if "matter" in t: return "MATTER"
    if "bluetooth" in t or "ble" in t: return "BLUETOOTH"
    if "wifi" in t or "wi-fi" in t or "lan" in t or "ethernet" in t: return "LAN"
    return None

def harvest():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1600, "height": 1200})
        page.goto(URL, wait_until="networkidle", timeout=90000)
        html = page.content()
        browser.close()

    soup = BeautifulSoup(html, "html.parser")
    tables = soup.find_all("table")
    records = []

    for table_idx, table in enumerate(tables):
        headers = [clean(x.get_text(" ", strip=True)) for x in table.find_all("th")]
        rows = table.find_all("tr")
        for tr in rows:
            cells = [clean(x.get_text(" ", strip=True)) for x in tr.find_all(["td","th"])]
            if not cells or cells == headers:
                continue

            raw = dict(zip(headers, cells)) if headers and len(headers) == len(cells) else {
                f"column_{i+1}": v for i, v in enumerate(cells)
            }
            joined = " | ".join(cells)

            # Conservative extraction. Keep raw data for manual/schema refinement.
            manufacturer = cells[0] if cells else None
            product = cells[1] if len(cells) > 1 else cells[0]
            driver = next((v for k,v in raw.items() if "driver" in k.lower()), None)
            model = next((v for k,v in raw.items() if "model" in k.lower()), None)
            protocol = normalise_protocol(joined)

            records.append({
                "manufacturer": manufacturer,
                "product": product,
                "model": model,
                "protocol": protocol,
                "recommendedDriver": driver,
                "support": "HUBITAT_NATIVE",
                "source": {
                    "authority": "Hubitat Compatible Devices",
                    "url": URL,
                    "tableIndex": table_idx,
                    "raw": raw
                }
            })

    # Deduplicate identical rendered rows
    seen, unique = set(), []
    for r in records:
        key = json.dumps(r, sort_keys=True)
        if key not in seen:
            seen.add(key)
            unique.append(r)
    return unique

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="hubitat_compatible_devices_harvest.json")
    args = ap.parse_args()

    devices = harvest()
    payload = {
        "schemaVersion": "0.1",
        "source": URL,
        "deviceCount": len(devices),
        "deviceEntries": devices
    }
    Path(args.out).write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"Wrote {len(devices)} records to {args.out}")

if __name__ == "__main__":
    main()
