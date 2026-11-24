#!/usr/bin/env python3
"""Verify V5 against the last published declarations and resources before consolidation."""

import argparse
import json
import re
import subprocess
from collections import Counter
from pathlib import Path


# Revision used to write the published V4 save fixture. Keep this revision after the consolidation lands.
PUBLISHED = "20680d4b87a28940bb1ac6853320b7dde3737d8d"
LEGACY = {f"folkways_{mod}" for mod in
          ("golems", "epicfight", "carving", "cookery", "carts", "firearms", "create")}
CURRENT = {"folkways_core", "folkways_vanilla"}
CORE_NETWORK = {"ae2_network_buffers", "network_pattern", "network_recipes", "network_service",
                "rs2_network_buffers", "rs2_provider_tasks", "rs2_terminal"}
CORE_CARVING = {"chiselsandbits", "resolution", "schematic"}
PATH = r"[a-z0-9_.][a-z0-9_./-]*"
TABLE = Path("mods/core/src/main/java/io/github/izakyl/folkways/core/upgrade/V5_Namespaces.java")


def git(*args):
    return subprocess.check_output(["git", *args], text=True)


def ensure(ref):
    """Fetch the reference on demand, since CI checks out a shallow clone."""
    if subprocess.run(["git", "cat-file", "-e", f"{ref}^{{commit}}"], capture_output=True).returncode:
        subprocess.check_call(["git", "fetch", "--quiet", "--depth=1", "origin", ref])


def files(ref):
    if ref:
        ensure(ref)
        for name in git("ls-tree", "-r", "--name-only", ref, "mods", "tests/gametest").splitlines():
            yield Path(name)
    else:
        yield from Path("mods").glob("*/src/main/resources/**/*")
        yield from Path("tests/gametest").glob("*/*/resources/**/*.nbt")
        yield from Path("mods").glob("*/src/main/java/**/*.java")


def inventory(ref, namespaces):
    namespace = "(?:" + "|".join(sorted(namespaces)) + ")"
    literal = re.compile(r'(' + namespace + r'):(' + PATH + r')')
    declaration = re.compile(
        r'(?:fromNamespaceAndPath|Scenes\.scene)\(\s*"(' + namespace + r')"\s*,\s*"(' + PATH + r')"')
    constant = r'\b((?:[A-Z][\w]*\.)?[A-Z_]+)\s*'
    location = re.compile(r'fromNamespaceAndPath\(\s*' + constant + r',\s*"(' + PATH + r')"')
    suffix = re.compile(constant + r'\+\s*":(' + PATH + r')"')
    ids = set()
    for path in files(ref):
        name = path.as_posix()
        if not ref and not path.is_file():
            continue
        if "/upgrade/" in name or not ("/src/" in name or name.startswith("tests/gametest/")):
            continue
        main = "/src/main/" in name
        if not main and not name.endswith(".nbt"):
            continue
        if "/resources/" in name:
            resource_path = name.split("/resources/", 1)[1].split("/")
            # Chisels & Bits patterns also ship in an optional embedded data pack.
            if "data" in resource_path:
                resource_path = resource_path[resource_path.index("data"):]
            kind, owner, *parts = resource_path if len(resource_path) > 1 else (*resource_path, "")
            if owner in namespaces and parts:
                relative = "/".join(parts)
                if kind == "assets" and not relative.startswith("lang/"):
                    ids.add(f"{owner}:{relative}")
                    if relative.startswith("textures/gui/sprites/") and relative.endswith(".png"):
                        ids.add(f"{owner}:{relative.removeprefix('textures/gui/sprites/')[:-4]}")
                if kind == "data" and len(parts) > 1:
                    resource = str(Path("/".join(parts[1:])).with_suffix(""))
                    if path.suffix != ".star":
                        ids.add(f"{owner}:{resource}")
                    if parts[0] == "tags":
                        ids.add(f"{owner}:{resource.split('/', 1)[1]}")
                    if path.suffix == ".star":
                        ids.add(f"{owner}:{path.name}")
                        ids.add(f"{owner}:{path.stem}")
            if path.suffix not in {".json", ".star"}:
                continue
        elif path.suffix != ".java":
            continue
        content = git("show", f"{ref}:{name}") if ref else path.read_text()
        # UUID seeds and concatenated paths ending with a slash aren't concrete IDs.
        ids.update(f"{ns}:{key}" for ns, key in literal.findall(content) + declaration.findall(content)
                   if not key.endswith("/"))
        if path.suffix == ".java":
            # Resolve the local namespace constants and the shared mod ID constants.
            constants = {"FolkwaysCoreMod.CORE_ID": "folkways_core",
                         "FolkwaysCoreMod.MOD_ID": "folkways_core",
                         "FolkwaysVanillaMod.ID": "folkways_vanilla"}
            for field, value in re.findall(r'\bString ([A-Z_]+)\s*=\s*([\w.]+|"[^"]+");', content):
                constants[field] = constants.get(value, value.strip('"'))
            for field, key in location.findall(content) + suffix.findall(content):
                owner = constants.get(field)
                if owner in namespaces and not key.endswith("/"):
                    ids.add(f"{owner}:{key}")

            for field, owner in re.findall(
                    r'\b(\w+)\s*=\s*DeferredRegister.createItems\(([\w.]+|"[^"]+")\)', content):
                owner = constants.get(owner, owner.strip('"'))
                if owner in namespaces:
                    ids.update(f"{owner}:{key}" for key in re.findall(
                        r'\b' + field + r'\.registerItem\(\s*"(' + PATH + r')"', content))

            # Cookery's finite procedural recipes and its local id(String path) helper.
            for owner, prefix, first, second in re.findall(
                    r'fromNamespaceAndPath\("(' + namespace + r')",\s*"(' + PATH
                    + r')"\s*\+\s*\([^?]+\?\s*"([^"]+)"\s*:\s*"([^"]+)"\)\)', content):
                ids.update((f"{owner}:{prefix}{first}", f"{owner}:{prefix}{second}"))
            factory = re.search(r'ResourceLocation id\(String path\)\s*\{\s*return '
                                r'ResourceLocation.fromNamespaceAndPath\("(' + namespace + r')", path\);', content)
            if factory:
                ids.update(f"{factory[1]}:{key}" for key in re.findall(r'\bid\("(' + PATH + r')"\)', content))
        if "/lang/" in name:
            for key in json.loads(content):
                match = re.fullmatch(
                    r"folkways\.(?:choice|delegation|doing|urge|vocation)\.(" + namespace
                    + r")\.([a-z0-9_/-]+)(?:\.about)?", key)
                scene = re.fullmatch(r"(" + namespace + r")\.ponder\.([a-z0-9_/-]+)\..+", key)
                if match or scene:
                    ids.add(":".join((match or scene).groups()))
    return ids


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", default=PUBLISHED, help="Git revision of the published mods")
    args = parser.parse_args()
    entries = re.findall(r'Map\.entry\("([^"]+)",\s*"([^"]+)"\)', TABLE.read_text())
    mapping = dict(entries)
    if len(entries) != len(mapping):
        raise SystemExit("Duplicate V5 source ID")
    published = inventory(args.ref, LEGACY)
    counts = Counter(value.split(":")[0] for value in published)
    if counts.keys() != LEGACY:
        raise SystemExit(f"Reference {args.ref} is missing published namespaces: {sorted(LEGACY - counts.keys())}")
    current = inventory(None, CURRENT)
    expected = {old: "folkways_vanilla:" + old.split(":", 1)[1] for old in published
                if old.startswith("folkways_carving:") and old.split(":", 1)[1] not in CORE_CARVING}
    core_network = {f"folkways_core:{path}" for path in CORE_NETWORK}
    published_core = inventory(args.ref, {"folkways_core"})
    unpublished = core_network - published_core
    missing_network = core_network - current
    migrated_network = core_network & mapping.keys()
    wrong = {f"{old} -> {new}" for old, new in expected.items() if mapping.get(old) != new}
    missing = published - mapping.keys()
    dangling = {f"{old} -> {new}" for old, new in entries if new not in current}
    for label, ids in (("Missing V5 sources", missing), ("Missing V5 targets", dangling), ("Incorrect V5 destinations", wrong),
                       ("Unpublished network sources", unpublished), ("Missing Core network IDs", missing_network),
                       ("Core network IDs in V5 migration", migrated_network)):
        if ids:
            print(label + "\n  " + "\n  ".join(sorted(ids)))
    print(f"Published IDs: {len(published)} ({dict(sorted(counts.items()))}); V5 entries: {len(entries)}")
    return bool(missing or dangling or wrong or unpublished or missing_network or migrated_network)


if __name__ == "__main__":
    raise SystemExit(main())
