"""Render one continuous, real-time kitchen and dining take, then assemble the usual two films."""
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


def shots(_context=None):
    """One camera take, with a brief opening caption and no time skipped or accelerated."""
    source = "01-kitchen-dining.mp4"
    duration = min(50.0, length(source))
    if duration < 45.0:
        raise ValueError(f"kitchen and dining take is too short ({duration:.2f}s); record at least 45.1 seconds")
    # Adjacent ranges from the same source keep every frame in order at 1x. The section boundary
    # only removes the opening text; it introduces no shot change, dissolve, hold, or jump in time.
    return [
        ("kitchen-dining", "cookery/" + source, [
            ("kitchen_dining", [(0.0, 8.0, 1.0)]),
            ("watch", [(8.0, duration, 1.0)]),
        ], 0.4, "tl"),
    ]


def main():
    build.SHOTS = shots()
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
