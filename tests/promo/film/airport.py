"""Cut the airport promo (the field, its tower, and every Superb Warfare vehicle it commands) from its take.

Captions live in promo-airport.toml; build.py draws them. Run from the repository root after `npm run promo:airport`:
    python3 tests/promo/film/airport.py            # the film
    python3 tests/promo/film/airport.py --stills   # source frames of every clip, for review before cutting
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve().parent
os.environ["PROMO_TEXT"] = str(HERE / "promo-airport.toml")
os.environ["PROMO_HEIGHT"] = "1080"
sys.path.insert(0, str(HERE))
import build  # noqa: E402
from mounted import duration, track_file  # noqa: E402

build.OUT = str(Path(build.WORK) / "airport-shots")
Path(build.OUT).mkdir(parents=True, exist_ok=True)
TAKES = Path(build.T)
FILM = Path(build.FILM)
SCOPE = "airport"
TRACK = os.environ.get("PROMO_MUSIC", "minecraft/sounds/records/pigstep.ogg")
TRACK_START = float(os.environ.get("PROMO_MUSIC_START", 0))
MUSIC_GAIN = .16
# (shot, clip, caption place)
SHOTS = [
    ("airfield", "01-airfield", "tl"),
    ("tower", "02-tower", "bl"),
    ("armour", "03-armour", "tl"),
    ("helicopters", "05-helicopters", "tl"),
    ("takeoff", "06-takeoff", "tl"),
    ("catapult", "07-catapult", "tl"),
    ("carrier", "09-carrier", "tl"),
    ("deck-helicopter", "10-deck-helicopter", "tl"),
    ("deck-catapult", "11-deck-catapult", "tl"),
    ("deck-landing", "12-deck-landing", "tl"),
]
# Cut in only when the take's own evidence says the deck landing came off.
OPTIONAL = {"deck-landing": lambda evidence: (evidence.get("deckLanding") or {}).get("completed") is True}


# One speed for the whole of a shot: the long approaches run fast throughout, everything else at speed.
SPEED = {"armour": 1.5, "deck-helicopter": 2.0, "deck-landing": 2.5}
# The mark each shot's action ends on; the shot holds a few seconds past it, then cuts.
TAIL = 2.5


def plan(index):
    """Each shot as one piece of its clip at one speed, from the start to a little past its last mark."""
    context = index["context"]
    shots = []
    for name, clip, place in SHOTS:
        source = TAKES / SCOPE / f"{clip}.mp4"
        if not source.exists():
            continue
        if name in OPTIONAL and not OPTIONAL[name](context.get("evidence") or {}):
            continue
        length = duration(source) - .2
        marks = context.get(f"marks-{clip}", {})
        end = min(length, marks["done"] + TAIL) if "done" in marks else length
        pieces = [(0.5, end, SPEED.get(name, 1.0))]
        review = context.get("visualReview", {}).get("editPieces", {}).get(name)
        if review:
            pieces = [tuple(p) for p in review]
        shots.append({"name": name, "source": f"{SCOPE}/{clip}.mp4", "pieces": pieces, "place": place})
    return shots


def stills(directory):
    directory.mkdir(parents=True, exist_ok=True)
    for name, clip, _ in SHOTS:
        source = TAKES / SCOPE / f"{clip}.mp4"
        if not source.exists():
            continue
        seconds = duration(source)
        for i, at in enumerate([1, seconds * .2, seconds * .4, seconds * .6, seconds * .8, seconds - 1], 1):
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{max(0, at):.2f}", "-i", str(source),
                            "-frames:v", "1", str(directory / f"{name}-source-{i}.png")], check=True)
    return directory


def mix_music(source, output, seconds, music):
    """The film's track under the game's sound, ducked by it, faded in and out."""
    filters = (f"[0:a]aresample=48000,asplit[game][key];"
               f"[1:a]aresample=48000,volume={MUSIC_GAIN},afade=t=in:d=1.5,"
               f"afade=t=out:st={max(0.0, seconds - 3):.3f}:d=3[music];"
               "[music][key]sidechaincompress=threshold=0.02:ratio=6:attack=5:release=250[ducked];"
               "[game][ducked]amix=inputs=2:duration=first:normalize=0,"
               f"alimiter=limit=0.9:level=false:latency=true,atrim=duration={seconds}[audio]")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(source), "-stream_loop", "-1",
                    "-ss", str(TRACK_START), "-i", music, "-filter_complex", filters,
                    "-map", "0:v:0", "-map", "[audio]", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
                    "-t", str(seconds), "-movflags", "+faststart", str(output)], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stills", action="store_true", help="only extract source frames for review")
    args = parser.parse_args()
    directory = FILM / "airport-stills"
    if args.stills:
        print(stills(directory))
        return
    index = json.loads((TAKES / SCOPE / "index.json").read_text())
    build.fetch_fonts()
    build.gradients()
    music = track_file(TRACK)
    segments, timeline, offset = [], [], 0.0
    for shot in plan(index):
        print("render", shot["name"], shot["pieces"], flush=True)
        path, _ = build.render_shot(shot["name"], shot["source"], [(shot["name"], shot["pieces"])], 0.8,
                                    shot["place"])
        actual = duration(path)
        segments.append(path)
        timeline.append({**shot, "start": offset, "duration": actual})
        offset += actual
    listing = Path(build.OUT) / "concat.txt"
    listing.write_text("".join(f"file '{Path(p).resolve()}'\n" for p in segments))
    native = Path(build.WORK) / "folkways-airport-native.mp4"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", str(listing),
                    "-map", "0", "-c", "copy", "-movflags", "+faststart", str(native)], check=True)
    output = FILM / "folkways-airport.mp4"
    mix_music(native, output, offset, music)
    report = {"duration": duration(output), "width": 1920, "height": 1080, "cuts": "hard",
              "music": {"track": TRACK, "gain": MUSIC_GAIN}, "timeline": timeline,
              "evidence": index["context"].get("evidence")}
    (FILM / "airport-edit.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    directory.mkdir(parents=True, exist_ok=True)
    for shot in timeline:
        for i, fraction in enumerate([.2, .5, .8], 1):
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{shot['start'] + shot['duration'] * fraction:.2f}",
                            "-i", str(output), "-frames:v", "1",
                            str(directory / f"{shot['name'].replace(':', '-')}-film-{i}.png")], check=True)
    print(output, directory, sep="\n", flush=True)


if __name__ == "__main__":
    main()
