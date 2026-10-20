"""Prepare cached promo Wine Fox models: python3 vendor.py [<OpenYSM root>].

Each model is reshaped into the layout ModelLibrary reads, and the bones YSM keeps hidden at rest are dropped,
since the resident renderer plays only idle/walk and would otherwise draw them. A layer (pre_parallelN, then
parallelN; the last scale wins) hides a bone when its scale is 0 in YSM's resting state, where every
v./ysm./q./ctrl. value is 0 (no helmet, no vehicle, fox form off, default expression) and `a ?? b` falls back
to b. A scale this cannot evaluate keeps its bone.
"""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import fcntl
import hashlib
import json
import math
import re
import shutil
import sys
import tempfile
import urllib.request

PICKS = {
    "18_wedding": "skin", "05_magical": "winefox", "16_tactics": "tactics",
    "20_survivor": "mitao", "06_hanfu": "default", "14_momo": "skin_pink",
    "19_nine_tailed": "skin", "21_saint": "skin", "13_matured": "default",
}
BUILTIN = "common/src/main/resources/assets/yes_steve_model/builtin/wine_fox"
LAYER = re.compile(r"^(pre_)?parallel\d+$")
HELPER = re.compile(r"^(gui|molang\d*|Molang\d*)$")
NAME = re.compile(r"\b(?:v|variable|ysm|q|query|ctrl|c|t|temp)\.[A-Za-z_][\w.]*(\s*\([^()]*\))?")
MATH = {
    "math_clamp": lambda x, low, high: min(max(x, low), high), "math_abs": abs, "math_min": min, "math_max": max,
    "math_sin": lambda x: math.sin(math.radians(x)), "math_cos": lambda x: math.cos(math.radians(x)),
}


def ternary(text):
    depth = 0
    for i, ch in enumerate(text):
        depth += {"(": 1, "[": 1, ")": -1, "]": -1}.get(ch, 0)
        if ch != "?" or depth:
            continue
        rest, nested, inner = text[i + 1:], 0, 0
        for j, cj in enumerate(rest):
            inner += {"(": 1, "[": 1, ")": -1, "]": -1}.get(cj, 0)
            if inner:
                continue
            if cj == "?":
                nested += 1
            elif cj == ":":
                if nested == 0:
                    return f"(({ternary(rest[:j])}) if ({ternary(text[:i])}) else ({ternary(rest[j + 1:])}))"
                nested -= 1
        raise ValueError("unmatched ?")
    return text


def parens(text):
    out, i = "", 0
    while i < len(text):
        if text[i] != "(":
            out += text[i]
            i += 1
            continue
        depth, j = 1, i + 1
        while depth:
            depth += {"(": 1, ")": -1}.get(text[j], 0)
            j += 1
        out += "(" + parens(ternary(text[i + 1:j - 1])) + ")"
        i = j
    return out


def molang(expr):
    if isinstance(expr, (int, float)):
        return float(expr)
    text = expr.strip().rstrip(";")
    if re.search(r"(?<![=!<>])=(?!=)", text):
        raise ValueError("assignment")
    text = re.sub(r"\b(math\.\w+)", lambda m: m.group(1).replace(".", "_"), text)
    text = re.sub(r"[\w.]+(\s*\([^()]*\))?\s*\?\?\s*", "", text)
    text = NAME.sub("0", text)
    text = text.replace("&&", " and ").replace("||", " or ")
    text = re.sub(r"!(?!=)", " not ", text)
    return float(eval(parens(ternary(text)), {"__builtins__": {}}, MATH))


def resting(scale):
    if isinstance(scale, dict):
        first = scale[min(scale, key=float)]
        scale = first.get("post", first.get("pre", first.get("vector"))) if isinstance(first, dict) else first
    try:
        return [molang(part) for part in (scale if isinstance(scale, list) else [scale])]
    except Exception:
        return None


def hidden_at_rest(animations):
    layers = sorted((name for name in animations if LAYER.match(name)),
                    key=lambda name: (not name.startswith("pre_"), int(re.search(r"\d+$", name).group())))
    last = {}
    for layer in layers:
        for bone, channels in (animations[layer].get("bones") or {}).items():
            if "scale" in channels:
                last[bone] = resting(channels["scale"])
    return {bone for bone, value in last.items() if value is not None and 0 in value}


def strip(geometry_path, animations):
    doc = json.loads(geometry_path.read_text())
    geometry = doc["minecraft:geometry"][0]
    bones = geometry["bones"]
    children = {}
    for bone in bones:
        children.setdefault(bone.get("parent"), []).append(bone["name"])
    stack = list(hidden_at_rest(animations) | {bone["name"] for bone in bones if HELPER.match(bone["name"])})
    dropped = set()
    while stack:
        bone = stack.pop()
        if bone not in dropped:
            dropped.add(bone)
            stack.extend(children.get(bone, []))
    geometry["bones"] = [bone for bone in bones if bone["name"] not in dropped]
    geometry_path.write_text(json.dumps(doc, ensure_ascii=False, separators=(",", ":")))
    return len(bones), len(geometry["bones"]), sum(len(bone.get("cubes", [])) for bone in geometry["bones"])


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
MANIFEST = HERE / "sources.json"
STATE = HERE / ".prepared.json"


def sha256(file):
    return hashlib.sha256(file.read_bytes()).hexdigest()


def cached_sources(manifest, cache):
    """Fetch only missing/corrupt inputs, verifying each pinned file before publishing it."""
    def fetch(item):
        relative, expected = item
        target = cache / relative
        if target.is_file() and sha256(target) == expected:
            return
        url = f"https://raw.githubusercontent.com/OpenYSM/OpenYSM/{manifest['revision']}/{relative}"
        print(f"Downloading {relative}", flush=True)
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError(f"SHA-256 mismatch: {relative}")
        target.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as output:
            temporary = Path(output.name)
            output.write(data)
        try:
            temporary.replace(target)
        finally:
            temporary.unlink(missing_ok=True)

    with ThreadPoolExecutor(max_workers=6) as pool:
        list(pool.map(fetch, manifest["sha256"].items()))
    return cache / BUILTIN


def ready(fingerprint):
    try:
        state = json.loads(STATE.read_text())
        return (state["fingerprint"] == fingerprint and bool(state["sha256"])
                and all(sha256(HERE / file) == expected for file, expected in state["sha256"].items()))
    except (OSError, ValueError, KeyError):
        return False


def render(source, destination):
    for upstream, skin in PICKS.items():
        origin = source / upstream
        out = destination / ("wine_fox_" + upstream.split("_", 1)[1])
        (out / "textures").mkdir(parents=True)
        shutil.copy(origin / "models/main.json", out / "main.json")
        shutil.copy(origin / "animations/main.animation.json", out / "main.animation.json")
        shutil.copy(origin / "textures" / f"{skin}.png", out / "textures" / f"{skin}.png")
        shutil.copy(origin / "ysm.json", out / "ysm.json")
        animations = json.loads((out / "main.animation.json").read_text())["animations"]
        before, after, cubes = strip(out / "main.json", animations)
        print(f"{out.name}: bones {before} -> {after}, {cubes} cubes")


def prepare(checkout=None):
    manifest = json.loads(MANIFEST.read_text())
    fingerprint = hashlib.sha256(Path(__file__).read_bytes() + MANIFEST.read_bytes()).hexdigest()
    cache = REPO / ".blockwright-store/promo-models" / manifest["revision"]
    cache.mkdir(parents=True, exist_ok=True)
    # The store can be shared by worktrees; serialize downloads and model publication.
    with (cache / ".lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if checkout is None and ready(fingerprint):
            print("Wine Fox models are ready (no download needed).")
            return
        source = Path(checkout) / BUILTIN if checkout is not None else cached_sources(manifest, cache)
        # Download and transform every input before replacing any existing model.
        with tempfile.TemporaryDirectory(prefix=".prepare-", dir=HERE) as temporary:
            staging = Path(temporary)
            render(source, staging)
            files = {str(file.relative_to(staging)): sha256(file)
                     for file in sorted(staging.rglob("*")) if file.is_file()}
            STATE.unlink(missing_ok=True)
            for model in sorted(staging.iterdir()):
                target = HERE / model.name
                if target.exists():
                    shutil.rmtree(target)
                model.replace(target)
            if checkout is None:
                STATE.write_text(json.dumps({"fingerprint": fingerprint, "sha256": files}, indent=2) + "\n")


if __name__ == "__main__":
    if len(sys.argv) > 2:
        raise SystemExit(__doc__.splitlines()[0])
    try:
        prepare(sys.argv[1] if len(sys.argv) == 2 else None)
    except Exception as error:
        raise SystemExit(f"Cannot prepare Wine Fox models: {error}\nRetry with: npm run setup:promo-models")
