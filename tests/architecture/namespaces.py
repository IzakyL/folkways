#!/usr/bin/env python3
"""Check module resource namespaces and the translation keys referenced by production code."""

import json
import re
from pathlib import Path


def main():
    errors = []
    catalogs = {}
    for locale in ("en_us", "zh_cn", "zh_hk", "zh_tw"):
        words = {}
        for path in Path("mods").glob(f"*/src/main/resources/assets/*/lang/{locale}.json"):
            for key, value in json.loads(path.read_text()).items():
                if key in words:
                    errors.append(f"{path}: duplicate translation {key}")
                derived = re.search(r"\.(folkways_\w+)\.", key)
                ponder = re.match(r"(folkways_\w+)\.ponder\.", key)
                owner = derived[1] if derived else ponder[1] if ponder else None
                if owner is not None and owner != path.parts[-3]:
                    errors.append(f"{path}: translation subject belongs to {owner}: {key}")
                words[key] = value
        catalogs[locale] = words
    for locale, words in catalogs.items():
        for key in catalogs["en_us"].keys() ^ words.keys():
            errors.append(f"{locale}: translation key set differs: {key}")
    sources = {path: path.read_text() for path in Path("mods").glob("*/src/main/java/**/*.java")}
    constants = {}
    for path, source in sources.items():
        for name, value in re.findall(r'String\s+(\w+)\s*=\s*"([a-z0-9_]+)"', source):
            constants[path.stem + "." + name] = value
    for path, source in sources.items():
        if "upgrade" in path.parts:
            continue
        if re.search(r'fromNamespaceAndPath\(\s*"folkways"|"folkways:', source):
            errors.append(f"{path}: old content namespace outside migrations")
        refusal = re.search(r'enum\s+(\w+)\s+implements\s+RefusalKind(?:\.Named)?\s*\{(.*?);', source, re.S)
        if refusal:
            prefix = re.search(r'"(folkways\.refusal\.[a-z_]+\.)"', source)
            prefix = prefix[1] if prefix else "folkways.refusal." + refusal[1].removesuffix("Refusal").lower() + "."
            for name in re.findall(r'(?:^|,)\s*([A-Z][A-Z0-9_]+)', refusal[2]):
                if prefix + name.lower() not in catalogs["en_us"]:
                    errors.append(f"{path}: missing refusal translation {prefix + name.lower()}")
        for expression in re.findall(r'new Perk\(\s*("[^"]+"|[\w.]+)', source):
            perk = expression.strip('"') if expression.startswith('"') else constants.get(
                expression, constants.get(path.stem + "." + expression))
            if perk is not None:
                for suffix in ("", ".desc"):
                    if "folkways.perk." + perk + suffix not in catalogs["en_us"]:
                        errors.append(f"{path}: missing perk translation {perk + suffix}")
        # Custom options can use fallbacks; owned literal keys and finite enum/perk subjects must exist.
        literals = re.findall(r'(?:translatable|new Notice|Notice\.of|Notice\.text)\(\s*"([\w.]+)"\s*[,)]', source)
        for key in literals:
            if key.startswith("folkways.") and key not in catalogs["en_us"]:
                errors.append(f"{path}: missing translation {key}")
        for registry in re.findall(r'createRegistryKey\(\s*ResourceLocation.fromNamespaceAndPath\(\s*"(folkways_\w+)"\s*,\s*"([^"]+)"', source):
            namespace, kind = registry
            for obsolete in Path("mods").glob(f"*/src/*/resources/data/*/folkways/{kind}"):
                errors.append(f"{obsolete}: registry directory must use {namespace}/{kind}")
    for path in Path("mods").glob("*/src/*/resources/*/folkways"):
        errors.append(f"{path}: legacy namespace directory")
    if errors:
        raise SystemExit("\n".join(sorted(set(errors))))
    print(f"Content namespaces and four translation catalogs: OK ({len(catalogs['en_us'])} keys each)")


if __name__ == "__main__":
    main()
