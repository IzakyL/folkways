#!/usr/bin/env python3
"""Keep save migrations independent and published Vn source files immutable."""

import re
import subprocess
from pathlib import Path

import runpy

# Use the same comment/literal stripping as the existing architecture check.
NON_CODE = runpy.run_path("tests/architecture/core-packages.py")["NON_CODE"]
FRAMEWORK = "io.github.izakyl.folkways.core.base.persist.upgrade"
CODEC_ADAPTER = "io.github.izakyl.folkways.core.upgrade.ComponentCodecs"
# Only Folkways packages can hold save migrations; third-party "upgrade" packages are ordinary dependencies.
OWN = "io.github.izakyl.folkways."
VALUE_CODEC = "com.mojang.serialization.Codec"
VERSION_PATH = Path("mods/core/src/main/java/io/github/izakyl/folkways/core/base/persist/upgrade/DataVersion.java")
STEP = re.compile(r"(?:^|/)V(\d+)_[^/]+\.java$")
# Package moves since a release, applied to its paths and sources; already-relocated text is left unchanged.
RELOCATIONS = [
    (re.compile(r"(io[./]github[./]izakyl[./]folkways)([./])(base|library|effect|front|upgrade"
                r"|FolkwaysMod|FolkwaysConfig|FolkwaysClientConfig|ExtensionGameTests)\b"), r"\1\2core\2\3"),
    (re.compile(r"(io[./]github[./]izakyl[./]folkways)([./])plugins\b"), r"\1\2vanilla"),
    (re.compile(r"(io[./]github[./]izakyl[./]folkways)([./])rts\b"), r"\1\2vanilla\2rts"),
    (re.compile(r"(io[./]github[./]izakyl[./]folkways)([./])firefighting\b"), r"\1\2create\2firefighting"),
    (re.compile(r"(io[./]github[./]izakyl[./]folkways)([./])(warfare|tacz)\b"), r"\1\2firearms\2\3"),
    (re.compile(r"\bFolkwaysMod\b"), "FolkwaysCoreMod"),
    (re.compile(r"\bFolkwaysEpicFight\b"), "FolkwaysEpicFightMod"),
]


def relocate(text):
    for pattern, replacement in RELOCATIONS:
        text = pattern.sub(replacement, text)
    return text


def git(*args):
    return subprocess.check_output(["git", *args], text=True)


def main():
    errors = []
    version_source = NON_CODE.sub(" ", VERSION_PATH.read_text())
    baseline = int(re.search(r"\bBASELINE\s*=\s*(\d+)", version_source)[1])
    current = int(re.search(r"\bCURRENT\s*=\s*(\d+)", version_source)[1])
    for path in sorted(Path("mods").glob("*/src/main/java/**/*.java")):
        code = NON_CODE.sub(" ", path.read_text())
        package = re.search(r"\bpackage\s+([\w.]+)\s*;", code).group(1)
        migration = "upgrade" in package.split(".") and not package.startswith(FRAMEWORK)
        component_codecs = package == "io.github.izakyl.folkways.core.upgrade" and path.name == "ComponentCodecs.java"
        component_registry = str(path).endswith("/front/engine/registry/FolkwaysDataComponents.java")
        main_mod = bool(re.search(r"@Mod\s*\(", code))
        imports = re.findall(r"\bimport\s+(?:static\s+)?([\w.*]+)\s*;", code)
        for imported in imports:
            codec_import = component_codecs and imported == VALUE_CODEC
            adapter_import = component_registry and imported == CODEC_ADAPTER
            if migration and not codec_import and not imported.startswith(
                    ("java.", "net.minecraft.nbt.", FRAMEWORK + ".")):
                errors.append(f"{path}: migration imports business/platform code: {imported}")
            if (not migration and not main_mod and not adapter_import and ".upgrade." in imported
                    and imported.startswith(OWN) and not imported.startswith(FRAMEWORK + ".")):
                errors.append(f"{path}: business code imports a migration: {imported}")
        # Fully-qualified references must not bypass the import boundary.
        references = re.findall(r"\b(?:io|net|com|org)\.[\w.]+", code)
        for reference in references:
            if reference == package:
                continue
            codec_reference = component_codecs and reference == VALUE_CODEC
            adapter_reference = component_registry and reference == CODEC_ADAPTER
            if migration and not codec_reference and not reference.startswith(("net.minecraft.nbt.", FRAMEWORK + ".")):
                errors.append(f"{path}: migration references forbidden type: {reference}")
            if (not migration and not main_mod and not adapter_reference and ".upgrade." in reference
                    and reference.startswith(OWN) and not reference.startswith(FRAMEWORK + ".")):
                errors.append(f"{path}: business code references a migration: {reference}")
        if migration and "implements Step" in code and not STEP.search(str(path)):
            errors.append(f"{path}: step filenames must contain target: V3_Description.java")
        match = STEP.search(str(path))
        if migration and match and not baseline < int(match[1]) <= current:
            errors.append(f"{path}: migration target outside ({baseline}, {current}]")
        target = re.search(r"int\s+target\s*\(\s*\)\s*\{\s*return\s+(\d+)\s*;", code)
        bag_target = re.search(r'KeepSteps\.bag\s*\(\s*[^,]+,\s*(\d+)\s*,', code)
        if migration and match and not any(candidate and candidate[1] == match[1]
                                          for candidate in (target, bag_target)):
            errors.append(f"{path}: filename target must match literal target() or KeepSteps.bag target")

    # Compare every release, not just the latest: deleted or renamed shipped steps must also fail.
    for tag in git("tag", "--list", "v*").splitlines():
        version_path = str(VERSION_PATH)
        released = {relocate(name): name for name in git("ls-tree", "-r", "--name-only", tag, "mods").splitlines()}
        if version_path not in released:
            continue
        version_source = git("show", f"{tag}:{released[version_path]}")
        current = int(re.search(r"\bCURRENT\s*=\s*(\d+)", version_source)[1])
        current_paths = [str(path) for path in Path("mods").glob("*/src/main/java/**/upgrade/V*.java")]
        for name in sorted(set(released) | set(current_paths)):
            match = STEP.search(name)
            if "/upgrade/" in name and match and int(match[1]) <= current:
                path = Path(name)
                if (name not in released or not path.is_file()
                        or path.read_text() != relocate(git("show", f"{tag}:{released[name]}"))):
                    errors.append(f"{name}: released migration frozen by {tag}")
    if errors:
        raise SystemExit("\n".join(sorted(set(errors))))
    print("Save upgrade boundaries and released steps: OK")


if __name__ == "__main__":
    main()
