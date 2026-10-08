"""Rebuild the promo Wine Fox models from an OpenYSM checkout: python3 vendor.py <OpenYSM root>.

Each model is reshaped into the layout ModelLibrary reads, and the bones YSM keeps hidden at rest are dropped,
since the resident renderer plays only idle/walk and would otherwise draw them. A layer (pre_parallelN, then
parallelN; the last scale wins) hides a bone when its scale is 0 in YSM's resting state, where every
v./ysm./q./ctrl. value is 0 (no helmet, no vehicle, fox form off, default expression) and `a ?? b` falls back
to b. A scale this cannot evaluate keeps its bone.
"""
from pathlib import Path
import json
import math
import re
import shutil
import sys

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


if len(sys.argv) != 2:
    raise SystemExit(__doc__.splitlines()[0])
source = Path(sys.argv[1]) / BUILTIN
here = Path(__file__).resolve().parent
for stale in here.glob("wine_fox_*"):
    shutil.rmtree(stale)
for upstream, skin in PICKS.items():
    origin, out = source / upstream, here / ("wine_fox_" + upstream.split("_", 1)[1])
    (out / "textures").mkdir(parents=True)
    shutil.copy(origin / "models/main.json", out / "main.json")
    shutil.copy(origin / "animations/main.animation.json", out / "main.animation.json")
    shutil.copy(origin / "textures" / f"{skin}.png", out / "textures" / f"{skin}.png")
    shutil.copy(origin / "ysm.json", out / "ysm.json")
    animations = json.loads((out / "main.animation.json").read_text())["animations"]
    before, after, cubes = strip(out / "main.json", animations)
    print(f"{out.name}: bones {before} -> {after}, {cubes} cubes")
