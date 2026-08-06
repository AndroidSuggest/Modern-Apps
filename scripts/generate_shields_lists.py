#!/usr/bin/env python3
"""Offline generator: Brave Shields filter lists -> web/src/main/assets/shields/

Developer-run, not part of the Gradle build. Fetches Brave's own default filter
lists and the resource bodies used by `$redirect` rules, then writes three assets
that `ShieldsEngine.kt` loads at runtime:

  filters.txt.br     Every default-enabled list from Brave's list_catalog.json,
                     comments stripped and concatenated. Brotli-compressed.
  resources.json.br  A `Vec<Resource>` (adblock-rust's own serde format) holding
                     the bodies served for `$redirect` / `$redirect-rule` rules.
                     Brotli-compressed.
  version.txt        SHA-256 of the two payloads above. `ShieldsEngine` compares
                     it against the tag stored beside its serialized engine cache
                     and reparses the lists when they differ.

The list set is read from Brave's catalog rather than hardcoded, so re-running
this script picks up upstream changes to what Brave enables by default. The
iOS-specific component is the only default-enabled entry that is skipped.

Scriptlet coverage: the `$redirect` bodies come from uBlock Origin's
`web_accessible_resources/`, indexed by its `redirect-resources.js` map — the
same two inputs adblock-rust's own `resource-assembler` reads. uBO's `##+js(...)`
scriptlets are ES modules since 1.53, so they are enumerated the way Brave does
it: node imports `resources/scriptlets.js` and dumps the `builtinScriptlets`
registry. Scriptlets marked `requiresTrust` are given a non-default permission
mask, which makes adblock-rust refuse to inject them — none of the bundled lists
are trusted sources.

Usage:  python3 scripts/generate_shields_lists.py
Requires: `brotli` and `node` on PATH.
"""

import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
ASSET_DIR = REPO_ROOT / "web" / "src" / "main" / "assets" / "shields"

BRAVE_RESOURCES = "https://raw.githubusercontent.com/brave/adblock-resources/master"
LIST_CATALOG = f"{BRAVE_RESOURCES}/filter_lists/list_catalog.json"
BRAVE_SCRIPTLETS = f"{BRAVE_RESOURCES}/dist/resources.json"

UBO = "https://raw.githubusercontent.com/gorhill/uBlock/master/src"
UBO_REDIRECT_MAP = f"{UBO}/js/redirect-resources.js"
UBO_WEB_ACCESSIBLE = f"{UBO}/web_accessible_resources"
UBO_WEB_ACCESSIBLE_API = (
    "https://api.github.com/repos/gorhill/uBlock/contents/src/web_accessible_resources"
)
UBO_SCRIPTLET_DIR = f"{UBO}/js/resources"
UBO_SCRIPTLET_API = (
    "https://api.github.com/repos/gorhill/uBlock/contents/src/js/resources"
)
# Imported by files under js/resources/ but living one directory up.
UBO_SCRIPTLET_SIBLINGS = ("urlskip.js", "arglist-parser.js", "jsonpath.js")

# adblock-rust refuses to inject a resource whose permission mask is not a subset of the
# filter's. Filters from untrusted lists carry mask 0, so any non-zero value here means
# "never injected" — which is what we want for uBO's trusted-* scriptlets.
TRUSTED_PERMISSION = 1

# Brave enables this for iOS only; every other default-enabled component applies.
SKIP_LIST_TITLES = {"Brave IOS-Specific Filters"}

# adblock-rust's MimeType::from_extension.
MIME_BY_EXTENSION = {
    ".css": "text/css",
    ".gif": "image/gif",
    ".html": "text/html",
    ".js": "application/javascript",
    ".json": "application/json",
    ".mp3": "audio/mp3",
    ".mp4": "video/mp4",
    ".png": "image/png",
    ".txt": "text/plain",
    ".xml": "text/xml",
}


def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "modern-apps-shields"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def fetch_text(url: str) -> str:
    return fetch(url).decode("utf-8", errors="replace")


# ---------------------------------------------------------------------------
# filters.txt
# ---------------------------------------------------------------------------
def default_list_urls() -> list[str]:
    catalog = json.loads(fetch_text(LIST_CATALOG))
    urls = []
    for component in catalog:
        if not component.get("default_enabled"):
            continue
        if component.get("title") in SKIP_LIST_TITLES:
            continue
        for source in component.get("sources", []):
            url = source.get("url")
            if url and url not in urls:
                urls.append(url)
    return urls


def strip_comments(list_text: str) -> str:
    """Drops comment and metadata lines. `#` alone never starts a filter comment —
    `##selector` is a cosmetic rule — so only `!` and `[Adblock...]` are removed."""
    kept = []
    for line in list_text.splitlines():
        line = line.strip()
        if not line or line.startswith("!"):
            continue
        if line.startswith("[") and line.lower().startswith("[adblock"):
            continue
        kept.append(line)
    return "\n".join(kept)


def build_filters() -> bytes:
    chunks = []
    for url in default_list_urls():
        print(f"  {url}")
        chunks.append(strip_comments(fetch_text(url)))
    return ("\n".join(chunks) + "\n").encode("utf-8")


# ---------------------------------------------------------------------------
# resources.json
# ---------------------------------------------------------------------------
MAP_DECLARATION = "export default new Map(["


def parse_redirect_map(mapfile: str) -> list[dict]:
    """Ports adblock-rust's `read_redirectable_resource_mapping`: coerce the JS
    `Map` literal in redirect-resources.js into JSON and parse it strictly."""
    lines = mapfile.splitlines()
    start = lines.index(MAP_DECLARATION)
    body = []
    for line in lines[start:]:
        if re.match(r"^\s*\]\s*\)", line):
            break
        line = line.split("//")[0]
        line = re.sub(r"\s*/\*[^'\"]*\*/\s*$", "", line)
        body.append(line)

    text = "".join(body)
    text += "]"
    text = text[len(MAP_DECLARATION) - 1 :].replace("'", '"')
    text = "".join(text.split())
    text = re.sub(r",([\],\}])", r"\1", text)
    text = re.sub(r"([\{,])([a-zA-Z][a-zA-Z0-9_]*):", r'\1"\2":', text)

    entries = []
    for name, props in json.loads(text):
        # adblock-rust ignores parameterised resources; they need a JS engine.
        if props.get("params") is not None:
            continue
        alias = props.get("alias")
        if alias is None:
            aliases = []
        elif isinstance(alias, str):
            aliases = [alias]
        else:
            aliases = list(alias)
        entries.append({"name": name, "aliases": aliases})
    return entries


DUMP_SCRIPTLETS_MJS = """\
import { builtinScriptlets } from './resources/scriptlets.js';
const out = [];
for (const entry of builtinScriptlets) {
    if (typeof entry.fn !== 'function') continue;
    const resource = {
        name: entry.name,
        aliases: entry.aliases ?? [],
        kind: { mime: entry.name.endsWith('.fn') ? 'fn/javascript' : 'application/javascript' },
        content: Buffer.from(entry.fn.toString(), 'utf8').toString('base64'),
        dependencies: entry.dependencies ?? [],
    };
    if (entry.requiresTrust === true) resource.permission = %d;
    out.push(resource);
}
process.stdout.write(JSON.stringify(out));
""" % TRUSTED_PERMISSION


def build_scriptlets() -> list[dict]:
    """Enumerates uBO's `##+js(...)` scriptlets by importing their ES modules in node.

    Each entry becomes a `Resource` whose content is the raw `function name(...) {...}`
    source; adblock-rust detects the name and calls it with the filter's arguments,
    emitting any `.fn` dependencies alongside it."""
    names = [
        item["name"]
        for item in json.loads(fetch_text(UBO_SCRIPTLET_API))
        if item["type"] == "file"
    ]

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "resources").mkdir()
        (root / "package.json").write_text('{"type":"module"}')
        (root / "dump.mjs").write_text(DUMP_SCRIPTLETS_MJS)
        for name in names:
            (root / "resources" / name).write_bytes(fetch(f"{UBO_SCRIPTLET_DIR}/{name}"))
        for name in UBO_SCRIPTLET_SIBLINGS:
            (root / name).write_bytes(fetch(f"{UBO}/js/{name}"))

        dumped = subprocess.run(
            ["node", "dump.mjs"],
            cwd=root,
            check=True,
            capture_output=True,
            env={**os.environ, "NODE_OPTIONS": "--no-warnings"},
        ).stdout

    return json.loads(dumped)


def build_resources() -> bytes:
    print("  redirect-resources.js")
    entries = parse_redirect_map(fetch_text(UBO_REDIRECT_MAP))

    available = {
        item["name"]
        for item in json.loads(fetch_text(UBO_WEB_ACCESSIBLE_API))
        if item["type"] == "file"
    }

    resources = []
    for entry in entries:
        name = entry["name"]
        if name not in available:
            print(f"  ! {name} listed in the map but missing upstream, skipping")
            continue
        raw = fetch(f"{UBO_WEB_ACCESSIBLE}/{name}")
        mime = MIME_BY_EXTENSION.get(Path(name).suffix, "application/octet-stream")
        if mime in ("application/javascript", "text/html", "text/plain"):
            raw = raw.decode("utf-8").replace("\r", "").encode("utf-8")
        resources.append(
            {
                "name": name,
                "aliases": entry["aliases"],
                "kind": {"mime": mime},
                "content": base64.b64encode(raw).decode("ascii"),
            }
        )
    print(f"  {len(resources)} redirect resources")

    scriptlets = build_scriptlets()
    print(f"  {len(scriptlets)} uBO scriptlets")
    resources.extend(scriptlets)

    brave = json.loads(fetch_text(BRAVE_SCRIPTLETS))
    print(f"  {len(brave)} Brave scriptlets")
    resources.extend(brave)

    return json.dumps(resources, separators=(",", ":")).encode("utf-8")


# ---------------------------------------------------------------------------
def brotli_compress(data: bytes, destination: Path) -> None:
    destination.write_bytes(data)
    subprocess.run(
        ["brotli", "--force", "--quality=11", str(destination)],
        check=True,
    )
    destination.unlink()


def main() -> int:
    for tool in ("brotli", "node"):
        if shutil.which(tool) is None:
            print(f"{tool} not found on PATH", file=sys.stderr)
            return 1

    ASSET_DIR.mkdir(parents=True, exist_ok=True)

    print("filters:")
    filters = build_filters()
    print("resources:")
    resources = build_resources()

    brotli_compress(filters, ASSET_DIR / "filters.txt")
    brotli_compress(resources, ASSET_DIR / "resources.json")

    digest = hashlib.sha256(filters + resources).hexdigest()
    (ASSET_DIR / "version.txt").write_text(digest + "\n")

    for name in ("filters.txt.br", "resources.json.br", "version.txt"):
        path = ASSET_DIR / name
        print(f"{path.relative_to(REPO_ROOT)}  {path.stat().st_size:,} bytes")
    print(f"raw: filters {len(filters):,}  resources {len(resources):,}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
