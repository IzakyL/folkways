#!/usr/bin/env python3
"""Check core domain dependencies, API boundaries and plugin access."""

import argparse
import json
import re
import subprocess
from pathlib import Path


PREFIX = "io.github.izakyl.folkways.core."
SOURCE = "mods/core/src/main/java/io/github/izakyl/folkways/core"
# Discard comments and literals so examples and diagnostic text do not create edges.
NON_CODE = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/')
REFERENCE = re.compile(r"\bio\.github\.izakyl\.folkways\.core(?:\.[A-Za-z_$][\w$]*)+")


def sources(ref, source=SOURCE):
    if ref:
        paths = subprocess.check_output(["git", "ls-tree", "-r", "--name-only", ref, source], text=True)
        for path in paths.splitlines():
            if path.endswith(".java"):
                yield path, subprocess.check_output(["git", "show", f"{ref}:{path}"], text=True)
    else:
        for path in sorted(Path(source).rglob("*.java")):
            yield str(path), path.read_text()


def components(graph):
    index = {}
    low = {}
    stack = []
    active = set()
    found = []

    def visit(node):
        index[node] = low[node] = len(index)
        stack.append(node)
        active.add(node)
        for target in sorted(graph[node]):
            if target not in index:
                visit(target)
                low[node] = min(low[node], low[target])
            elif target in active:
                low[node] = min(low[node], index[target])
        if low[node] == index[node]:
            group = []
            while True:
                member = stack.pop()
                active.remove(member)
                group.append(member)
                if member == node:
                    break
            if len(group) > 1:
                found.append(sorted(group))

    for node in sorted(graph):
        if node not in index:
            visit(node)
    return sorted(found)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", help="Inspect a git revision instead of the working tree")
    parser.add_argument("--json", action="store_true", help="Include dependency edges and source locations")
    parser.add_argument("--check", action="store_true", help="Fail on domain cycles or forbidden dependencies")
    args = parser.parse_args()
    units = []
    types = {}
    for path, source in sources(args.ref):
        code = NON_CODE.sub(lambda match: "\n" * match.group().count("\n"), source)
        package = re.search(r"\bpackage\s+([\w.]+)\s*;", code).group(1)
        types[f"{package}.{Path(path).stem}"] = package
        units.append((path, package, code))
    graph = {package: set() for package in types.values()}
    evidence = {}
    for path, package, code in units:
        # Imports include static members and nested types; longest owning top-level type wins.
        for match in REFERENCE.finditer(code):
            name = match.group()
            while name.startswith(PREFIX) and name not in types:
                name = name.rsplit(".", 1)[0]
            target = types.get(name) or (match.group() if match.group() in graph else None)
            if target and target != package:
                graph[package].add(target)
                edge = f"{package.removeprefix(PREFIX)} -> {target.removeprefix(PREFIX)}"
                evidence.setdefault(edge, []).append(f"{path}:{code.count(chr(10), 0, match.start()) + 1}")
    cycles = [[name.removeprefix(PREFIX) for name in group] for group in components(graph)]
    violations = []
    for package, targets in graph.items():
        parts = package.removeprefix(PREFIX).split(".")
        for target in targets:
            if tuple(parts[:2]) in {("work", "internal"), ("plan", "internal")}:
                if target.removeprefix(PREFIX).split(".")[0] == "runtime":
                    edge = f"{package.removeprefix(PREFIX)} -> {target.removeprefix(PREFIX)}"
                    violations.append(f"Runtime dependency {edge}: {', '.join(evidence[edge])}")
            if "api" in parts:
                if any(part in {"internal", "support", "runtime", "shell"}
                       for part in target.removeprefix(PREFIX).split(".")):
                    edge = f"{package.removeprefix(PREFIX)} -> {target.removeprefix(PREFIX)}"
                    violations.append(f"API dependency {edge}: {', '.join(evidence[edge])}")
    for module in ["vanilla", "cookery", "create", "warfare", "rts", "tacz", "firefighting", "modulargolems"]:
        for path, source in sources(args.ref, f"mods/{module}/src/main/java"):
            code = NON_CODE.sub(lambda match: "\n" * match.group().count("\n"), source)
            for match in REFERENCE.finditer(code):
                name = match.group()
                while name.startswith(PREFIX) and name not in types:
                    name = name.rsplit(".", 1)[0]
                target = types.get(name) or (match.group() if match.group() in graph else None)
                if target:
                    parts = target.removeprefix(PREFIX).split(".")
                    if "api" not in parts and parts[:2] != ["work", "support"]:
                        line = code.count(chr(10), 0, match.start()) + 1
                        violations.append(f"Plugin dependency {path}:{line} -> {target.removeprefix(PREFIX)}")
    domain_cycles = [group for group in cycles if len({name.split(".")[0] for name in group}) > 1]
    if args.json:
        print(json.dumps({"cycles": cycles, "domain_cycles": domain_cycles,
                          "violations": violations, "edges": evidence}, indent=2))
    else:
        print(f"{len(units)} types, {len(graph)} packages, {sum(map(len, graph.values()))} edges")
        print(f"{len(cycles)} strongly connected components with more than one package:")
        for group in cycles:
            print("  " + ", ".join(group))
        print(f"{len(domain_cycles)} cross-domain cycles, {len(violations)} boundary violations")
        for group in domain_cycles:
            print("  " + ", ".join(group))
        for violation in violations:
            print("  " + violation)
    if args.check and (domain_cycles or violations):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
