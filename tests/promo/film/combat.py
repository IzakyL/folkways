"""Hard-cut the six reviewed combat takes, using build.py captions and native game audio."""
import argparse
import glob
import json
import os
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve().parent
os.environ["PROMO_TEXT"] = str(HERE / "promo-combat.toml")
os.environ["PROMO_HEIGHT"] = "1080"
sys.path.insert(0, str(HERE))
import build

build.OUT = str(Path(build.WORK) / "combat-shots")
Path(build.OUT).mkdir(parents=True, exist_ok=True)
TAKES = Path(build.T)
FILM = Path(build.FILM)
TRACK = os.environ.get("PROMO_MUSIC", "minecraft/sounds/records/pigstep.ogg")
TRACK_START = float(os.environ.get("PROMO_MUSIC_START", 0))
MUSIC_GAIN = .12
SHOTS = [
    ("rts", "combat-rts", "01-command", "tr"),
    ("gate", "combat-squad-gate", "01-defence", "tl"),
    ("flank", "combat-squad-flank", "01-defence", "tl"),
    ("stations", "combat-emplacements", "01-battery", "tl"),
    ("damage", "combat-damage", "01-recovery", "tr"),
    ("repair", "combat-repair", "01-repair", "tr"),
]


def probe(path):
    return json.loads(subprocess.check_output([
        "ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", str(path)], text=True))


def duration(path):
    return float(probe(path)["format"]["duration"])


def track_file(name):
    """Resolve a local file or Minecraft asset name, as in assemble.py."""
    if Path(name).is_file():
        return name
    patterns = [str(HERE.parents[2] / ".blockwright-store/runtime/*/assets/indexes/*.json"),
                os.path.expanduser("~/.gradle/caches/neoformruntime/assets/indexes/*.json")]
    for index in (Path(p) for pattern in patterns for p in sorted(glob.glob(pattern))):
        found = json.loads(index.read_text())["objects"].get(name)
        if found:
            path = index.parent.parent / "objects" / found["hash"][:2] / found["hash"]
            if path.is_file():
                return str(path)
    raise ValueError(f"no local game asset store holds {name}; set PROMO_MUSIC to an available track or file")


def mix_music(source, output, seconds, music):
    # Build each export from native audio so preview cuts never splice or double-mix the music.
    filters = (f"[0:a]aresample=48000,asplit[game][key];"
               f"[1:a]aresample=48000,volume={MUSIC_GAIN},afade=t=in:d=1.5,"
               f"afade=t=out:st={max(0, seconds - 3):.3f}:d=3[music];"
               "[music][key]sidechaincompress=threshold=0.02:ratio=6:attack=5:release=250[ducked];"
               "[game][ducked]amix=inputs=2:duration=first:normalize=0,"
               f"alimiter=limit=0.85:level=false:latency=true,atrim=duration={seconds}[audio]")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(source), "-stream_loop", "-1",
                    "-ss", str(TRACK_START), "-i", music, "-filter_complex", filters,
                    "-map", "0:v:0", "-map", "[audio]", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
                    "-t", str(seconds), "-movflags", "+faststart", str(output)], check=True)


def read_take(scope, clip, reviewed=True):
    index = json.loads((TAKES / scope / "index.json").read_text())
    return index


def event_time(samples, predicate, fallback):
    return next((s["atMs"] / 1000 for s in samples if predicate(s)), fallback)


def plan(indexes):
    shots = []
    for name, scope, clip, place in SHOTS:
        source = f"{scope}/{clip}.mp4"
        end = duration(TAKES / source) - .15
        context = indexes[scope]["context"]
        if name == "rts":
            samples = context["01-command"]["samples"]
            stopped = context["stoppedPositions"]["still"]["time"]
            end = min(end, (stopped - samples[0]["time"]) / 20 + 2)
        elif name in {"gate", "flank"}:
            last_kill = event_time(context["samples"],
                                   lambda s: sum(p["kills"] for p in s["crew"]) >= 10, end - 2)
            end = min(end, max(18, last_kill + 2))
        elif name == "damage":
            end = min(end, 75)
        pieces = context.get("visualReview", {}).get("editPieces", [(0, round(end, 3), 1)])
        shots.append({"name": name, "scope": scope, "source": source, "section": name,
                      "pieces": pieces, "place": place})
    return shots


def extract_stills(indexes):
    directory = FILM / "combat-stills"
    directory.mkdir(exist_ok=True)
    for name, scope, clip, _ in SHOTS:
        source = TAKES / scope / f"{clip}.mp4"
        seconds = duration(source)
        times = [1, seconds * .2, seconds * .4, seconds * .6, seconds * .8, seconds - 1]
        if name == "damage":
            context = indexes[scope]["context"]
            times = [1, 12, 21, 26, 35, 70, 74.9]
            times += [event_time(context["samples"], lambda s: s["fires"] < context["before"]["fires"], 8)]
        elif name == "repair":
            context = indexes[scope]["context"]
            samples = context.get("samples", [])
            maximum = context["summary"]["maximumMissing"]
            crater = event_time(samples, lambda s: s["missing"] > 0, 3.2)
            times += [crater, crater + 1,
                      event_time(samples, lambda s: 0 < s["missing"] < maximum, 15.7),
                      event_time(samples, lambda s: s["atMs"] / 1000 > crater and s["missing"] == 0, 40.5)]
        for i, at in enumerate(times, 1):
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", str(max(0, at)), "-i", str(source),
                            "-frames:v", "1", str(directory / f"{name}-source-{i}.png")], check=True)
    return directory


def film_position(shot, start, end):
    offset = shot["start"]
    for a, b, speed in shot["pieces"]:
        if a <= start and end <= b:
            return offset + (start - a) / speed
        offset += (b - a) / speed
    raise ValueError(f"{shot['name']}: preview interval is absent from the reviewed main edit")


def preview(output, timeline, indexes):
    portions = []
    for name, scope, _, _ in SHOTS:
        review = indexes[scope]["context"].get("visualReview", {})
        intervals = review.get("previewPieces", [(1, 8)] if name in {"gate", "flank"} else [])
        portions += [(name, b - a, a) for a, b in intervals]
    filters, pieces = [], []
    for i, (name, seconds, source_start) in enumerate(portions):
        shot = next(s for s in timeline if s["name"] == name)
        begin = film_position(shot, source_start, source_start + seconds)
        pieces.append({"shot": name, "sourceStart": source_start, "duration": seconds,
                       "filmStart": begin})
        filters += [f"[0:v]trim={begin}:{begin + seconds},setpts=PTS-STARTPTS,scale=1920:1080,fps=30[v{i}]",
                    f"[0:a]atrim={begin}:{begin + seconds},asetpts=PTS-STARTPTS[a{i}]"]
    filters.append("".join(f"[v{i}][a{i}]" for i in range(len(portions)))
                   + f"concat=n={len(portions)}:v=1:a=1[v][a]")
    path = output.with_name("folkways-combat-preview.mp4")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(output), "-filter_complex", ";".join(filters),
                    "-map", "[v]", "-map", "[a]", "-c:v", "libx264", "-preset", "fast",
                    "-crf", "20", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", str(path)], check=True)
    return path, pieces


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stills", action="store_true", help="extract verified source frames before visual approval")
    args = parser.parse_args()
    indexes = {scope: read_take(scope, clip, reviewed=not args.stills) for _, scope, clip, _ in SHOTS}
    stills = extract_stills(indexes)
    if args.stills:
        print(stills)
        return
    build.fetch_fonts()
    build.gradients()
    music = track_file(TRACK)
    segments, timeline = [], []
    offset = 0
    for shot in plan(indexes):
        print("render", shot["name"], flush=True)
        build.MARGIN_Y = 220 if shot["name"] in {"damage", "repair"} else 96
        path, _ = build.render_shot(shot["name"], shot["source"],
                                    [(shot["section"], shot["pieces"])], 1, shot["place"], shade=False)
        actual = duration(path)
        segments.append(path)
        timeline.append({**shot, "start": offset, "duration": actual})
        offset += actual
    listing = Path(build.OUT) / "concat.txt"
    listing.write_text("".join(f"file '{Path(p).resolve()}'\n" for p in segments))
    output = FILM / "folkways-combat.mp4"
    native = Path(build.WORK) / "folkways-combat-native.mp4"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", str(listing),
                    "-map", "0", "-c", "copy", "-movflags", "+faststart", str(native)], check=True)
    native_short, preview_pieces = preview(native, timeline, indexes)
    short = output.with_name("folkways-combat-preview.mp4")
    mix_music(native, output, offset, music)
    mix_music(native_short, short, sum(p["duration"] for p in preview_pieces), music)
    clips = [e for index in indexes.values() for e in index["entries"] if e["kind"] == "clip"]
    report = {"duration": duration(output), "previewDuration": duration(short), "width": 1920, "height": 1080,
              "captureFps": 30, "exportFps": 60, "cuts": "hard",
              "audio": "native game audio at unity gain with quiet, ducked Minecraft music",
              "music": {"track": TRACK, "start": TRACK_START, "gain": MUSIC_GAIN,
                        "fadeIn": 1.5, "fadeOut": 3, "gameGain": 1, "limiter": .85,
                        "ducking": {"threshold": .02, "ratio": 6, "attackMs": 5, "releaseMs": 250}},
              "timeline": timeline, "previewPieces": preview_pieces, "site": indexes["combat-rts"]["context"]["site"],
              "recordedFrames": sum(e["gates"]["frames"] for e in clips),
              "droppedFrames": sum(e["gates"]["dropped"] for e in clips),
              "evidence": {scope: index["context"]["summary"] for scope, index in indexes.items()},
              "visualReviews": {scope: index["context"]["visualReview"] for scope, index in indexes.items()}}
    (FILM / "combat-edit.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    for shot in timeline:
        for i, fraction in enumerate([.2, .5, .8], 1):
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", str(shot["start"] + shot["duration"] * fraction),
                            "-i", str(output), "-frames:v", "1", str(stills / f"{shot['name']}-film-{i}.png")],
                           check=True)
    print(output, short, stills, sep="\n", flush=True)


if __name__ == "__main__":
    main()
