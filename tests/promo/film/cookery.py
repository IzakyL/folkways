"""Cut the native Cookery short. Run from the repository root after filming the cookery take."""
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
os.environ["PROMO_TEXT"] = os.path.join(HERE, "promo-cookery.toml")
import build

build.OUT = os.path.join(build.WORK, "cookery-shots")
TAKE = os.path.join(build.T, "cookery")


def length(name):
    """Keep cuts inside the recorded video, including when a take ends before its last mark."""
    result = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=duration",
         "-of", "csv=p=0", os.path.join(TAKE, name)],
        capture_output=True, text=True, check=True,
    )
    duration = float(result.stdout.strip()) - 0.1
    if duration <= 0:
        raise ValueError(f"empty cookery take: {name}")
    return duration


def chain(marks, duration, target=35.0):
    """Show every production step; accelerate the middle, preserving the opening and finished dish."""
    end = min(float(marks.get("end") or duration), duration)
    done = marks.get("done")
    if done is not None:
        # Four seconds after completion are enough to see delivery, without a long idle hold.
        end = min(end, max(0.0, float(done)) + 4.0)
    if end <= target:
        return [(0.0, end, 1.0)]
    first, last = 2.0, end - 4.0
    speed = (last - first) / (target - 6.0)
    return [(0.0, first, 1.0), (first, last, speed), (last, end, 1.0)]


def shots(context):
    kitchen = min(6.0, length("01-kitchen.mp4"))
    order = length("02-order.mp4")  # Keep the complete UI interaction at its recorded pace.
    production = chain((context.get("chain") or {}).get("marks") or {}, length("03-chain.mp4"))
    result = min(6.0, length("04-result.mp4"))
    return [
        ("card:disclaimer",),
        ("kitchen", "cookery/01-kitchen.mp4", [("kitchen", [(0.0, kitchen, 1.0)])], 0.4, "bl"),
        ("order", "cookery/02-order.mp4", [("order", [(0.0, order, 1.0)])], 0.4, "bl"),
        ("chain", "cookery/03-chain.mp4", [("chain", production)], 0.4, "bl"),
        ("result", "cookery/04-result.mp4", [("result", [(0.0, result, 1.0)])], 0.4, "bl"),
        ("card:ending",),
    ]


def main():
    with open(os.path.join(TAKE, "index.json")) as file:
        context = json.load(file)["context"]
    build.SHOTS = shots(context)
    os.makedirs(build.OUT, exist_ok=True)
    build.fetch_fonts()
    build.gradients()
    print(f"cutting at {build.W}x{build.H}", flush=True)
    segments = []
    for shot in build.SHOTS:
        print("render", shot[0], flush=True)
        segment = build.render_card(shot[0][5:]) if shot[0].startswith("card:") else build.render_shot(*shot)
        if segment:
            segments.append(segment)
    with open(os.path.join(build.OUT, "segments.json"), "w") as file:
        json.dump({"width": build.W, "height": build.H, "segments": segments}, file)
    os.environ["PROMO_SHOTS"], os.environ["PROMO_NAME"] = build.OUT, "folkways-cookery"
    os.environ.setdefault("PROMO_MUSIC", "minecraft/sounds/music/game/comforting_memories.ogg")
    import assemble  # joins this cut's segments and writes upload and preview films


if __name__ == "__main__":
    main()
