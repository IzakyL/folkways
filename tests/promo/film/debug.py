"""Cut the debug-overlay promo (a tour of a working colony with the overlay on) from its verified take.

The film carries no captions and no cards; promo-debug.toml is empty. Run from the repository root after `npm run promo:debug`:
    python3 tests/promo/film/debug.py            # the film
    python3 tests/promo/film/debug.py --stills   # source frames of every clip, for review before cutting
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve().parent
os.environ["PROMO_TEXT"] = str(HERE / "promo-debug.toml")
os.environ["PROMO_HEIGHT"] = "1080"
sys.path.insert(0, str(HERE))
import build  # noqa: E402
from mounted import duration, mix_music, track_file  # noqa: E402

build.OUT = str(Path(build.WORK) / "debug-shots")
Path(build.OUT).mkdir(parents=True, exist_ok=True)
TAKES = Path(build.T)
FILM = Path(build.FILM)
SCOPE = "debug-overlay"
TRACK = os.environ.get("PROMO_MUSIC", "minecraft/sounds/records/blocks.ogg")
SHOTS = [
    ("overview", "01-overview", "tl"),
    ("lift-graph", "02-lift-graph", "tl"),
    ("line", "03-line", "tl"),
    ("workshop", "04-workshop", "tl"),
    ("farmer", "06-farmer", "tl"),
    ("hungry", "07-hungry", "tl"),
    ("platform", "08-platform", "tl"),
    ("elevator", "09-elevator", "tl"),
]


def read_take():
    index = json.loads((TAKES / SCOPE / "index.json").read_text())
    if index["context"].get("verified") is not True:
        raise ValueError(f"{SCOPE}: the take did not verify what it films; re-record before editing")
    for _, clip, _ in SHOTS:
        entries = [e for e in index["entries"] if e["kind"] == "clip" and e["name"] == clip]
        if len(entries) != 1:
            raise ValueError(f"{SCOPE}: expected exactly one {clip} clip")
        gates = entries[0]["gates"]
        if gates["stop_status"] != "saved" or gates["frames"] <= 0:
            raise ValueError(f"{SCOPE}/{clip}: incomplete video")
    return index


def plan(index):
    """Each shot's pieces in its clip's own seconds; a take may override them with context.editPieces."""
    shots = []
    overrides = index["context"].get("editPieces", {})
    for name, clip, place in SHOTS:
        length = duration(TAKES / SCOPE / f"{clip}.mp4") - .2
        pieces = [(0.6, length, 1.0)]
        if name == "platform":
            # The walk down at speed, the wait on the platform fast-forwarded, the train coming in and the boarding
            # at speed again: cut by the moments the take stamped.
            marks = index["context"].get("platform-marks", {})
            down, seated = marks.get("platform"), marks.get("seated")
            if down is not None and seated is not None and seated - down > 10:
                wait = (down + 1.5, seated - 6.0)
                pieces = [(0.6, wait[0], 1.0), (wait[0], wait[1], 3.0), (wait[1], length, 1.0)]
        if name in overrides:
            pieces = [tuple(p) for p in overrides[name]]
        sections = [(name, pieces)]
        if name == "workshop":
            # The store's card only: the crafting it plans is over by the time the camera turns to the crafter.
            sections = [("workshop", [(0.6, 5.4, 1.0)])]
        shots.append({"name": name, "source": f"{SCOPE}/{clip}.mp4", "sections": sections, "place": place})
    return shots


def stills(directory):
    directory.mkdir(parents=True, exist_ok=True)
    for name, clip, _ in SHOTS:
        source = TAKES / SCOPE / f"{clip}.mp4"
        if not source.exists():
            continue
        seconds = duration(source)
        for i, at in enumerate([1, seconds * .25, seconds * .5, seconds * .75, seconds - 1], 1):
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{max(0, at):.2f}", "-i", str(source),
                            "-frames:v", "1", str(directory / f"{name}-source-{i}.png")], check=True)
    return directory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stills", action="store_true", help="only extract source frames for review")
    args = parser.parse_args()
    directory = FILM / "debug-stills"
    if args.stills:
        print(stills(directory))
        return
    index = read_take()
    stills(directory)
    build.fetch_fonts()
    build.gradients()
    music = track_file(TRACK)
    segments, timeline, offset = [], [], 0.0
    for shot in plan(index):
        print("render", shot["name"], shot["sections"], flush=True)
        path, _ = build.render_shot(shot["name"], shot["source"], shot["sections"], 0.8, shot["place"])
        actual = duration(path)
        segments.append(path)
        timeline.append({**shot, "start": offset, "duration": actual})
        offset += actual
    listing = Path(build.OUT) / "concat.txt"
    listing.write_text("".join(f"file '{Path(p).resolve()}'\n" for p in segments))
    native = Path(build.WORK) / "folkways-debug-native.mp4"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", str(listing),
                    "-map", "0", "-c", "copy", "-movflags", "+faststart", str(native)], check=True)
    output = FILM / "folkways-debug-overlay.mp4"
    mix_music(native, output, offset, music)
    report = {"duration": duration(output), "width": 1920, "height": 1080, "cuts": "hard", "music": TRACK,
              "timeline": timeline, "evidence": {k: index["context"].get(k) for k in ("waygraph", "summary")}}
    (FILM / "debug-edit.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    for shot in timeline:
        for i, fraction in enumerate([.2, .5, .8], 1):
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{shot['start'] + shot['duration'] * fraction:.2f}",
                            "-i", str(output), "-frames:v", "1",
                            str(directory / f"{shot['name'].replace(':', '-')}-film-{i}.png")], check=True)
    print(output, directory, sep="\n", flush=True)


if __name__ == "__main__":
    main()
