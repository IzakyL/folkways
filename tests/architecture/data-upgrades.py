#!/usr/bin/env python3
"""Keep save migrations independent and published Vn source files immutable."""

import re
import subprocess
from pathlib import Path

import runpy

# Use the same comment/literal stripping as the existing architecture check.
NON_CODE = runpy.run_path("tests/architecture/core-packages.py")["NON_CODE"]
FRAMEWORK = "io.github.izakyl.folkways.base.persist.upgrade"
STEP = re.compile(r"(?:^|/)V(\d+)_[^/]+\.java$")


def git(*args):
    return subprocess.check_output(["git", *args], text=True)


def main():
    errors = []
    for path in sorted(Path("mods").glob("*/src/main/java/**/*.java")):
        code = NON_CODE.sub(" ", path.read_text())
        package = re.search(r"\bpackage\s+([\w.]+)\s*;", code).group(1)
        migration = "upgrade" in package.split(".") and not package.startswith(FRAMEWORK)
        main_mod = bool(re.search(r"@Mod\s*\(", code))
        imports = re.findall(r"\bimport\s+(?:static\s+)?([\w.*]+)\s*;", code)
        for imported in imports:
            if migration and not imported.startswith(("java.", "net.minecraft.nbt.", FRAMEWORK + ".")):
                errors.append(f"{path}: migration imports business/platform code: {imported}")
            if not migration and not main_mod and ".upgrade." in imported and not imported.startswith(FRAMEWORK + "."):
                errors.append(f"{path}: business code imports a migration: {imported}")
        # Fully-qualified references must not bypass the import boundary.
        references = re.findall(r"\b(?:io|net|com|org)\.[\w.]+", code)
        for reference in references:
            if reference == package:
                continue
            if migration and not reference.startswith(("net.minecraft.nbt.", FRAMEWORK + ".")):
                errors.append(f"{path}: migration references forbidden type: {reference}")
            if not migration and not main_mod and ".upgrade." in reference and not reference.startswith(FRAMEWORK + "."):
                errors.append(f"{path}: business code references a migration: {reference}")
        if migration and "implements Step" in code and not STEP.search(str(path)):
            errors.append(f"{path}: step filenames must contain target: V3_Description.java")
        match = STEP.search(str(path))
        target = re.search(r"int\s+target\s*\(\s*\)\s*\{\s*return\s+(\d+)\s*;", code)
        bag_target = re.search(r'KeepSteps\.bag\s*\(\s*[^,]+,\s*(\d+)\s*,', code)
        if migration and match and not any(candidate and candidate[1] == match[1]
                                          for candidate in (target, bag_target)):
            errors.append(f"{path}: filename target must match literal target() or KeepSteps.bag target")

    # Compare every release, not just the latest: deleted or renamed shipped steps must also fail.
    for tag in git("tag", "--list", "v*").splitlines():
        version_path = "mods/core/src/main/java/io/github/izakyl/folkways/base/persist/upgrade/DataVersion.java"
        listing = git("ls-tree", "-r", "--name-only", tag, "mods").splitlines()
        if version_path not in listing:
            continue
        version_source = git("show", f"{tag}:{version_path}")
        current = int(re.search(r"\bCURRENT\s*=\s*(\d+)", version_source)[1])
        current_paths = [str(path) for path in Path("mods").glob("*/src/main/java/**/upgrade/V*.java")]
        for name in sorted(set(listing) | set(current_paths)):
            match = STEP.search(name)
            if "/upgrade/" in name and match and int(match[1]) <= current:
                path = Path(name)
                if (name not in listing or not path.is_file()
                        or path.read_bytes() != subprocess.check_output(["git", "show", f"{tag}:{name}"])):
                    errors.append(f"{name}: released migration frozen by {tag}")
    if errors:
        raise SystemExit("\n".join(sorted(set(errors))))
    print("Save upgrade boundaries and released steps: OK")


if __name__ == "__main__":
    main()
