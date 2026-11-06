"""Cut the mounted promo (riding to work, fighting from the saddle, the horse-drawn cart line) from its verified takes.

Captions live in promo-mounted.toml; build.py draws them. Run from the repository root after `npm run promo:mounted`:
    python3 tests/promo/film/mounted.py            # the film
    python3 tests/promo/film/mounted.py --stills   # source frames of every take, for review before cutting
"""
import argparse
import glob
import json
import os
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve().parent
os.environ["PROMO_TEXT"] = str(HERE / "promo-mounted.toml")
os.environ["PROMO_HEIGHT"] = "1080"
sys.path.insert(0, str(HERE))
import build  # noqa: E402

build.OUT = str(Path(build.WORK) / "mounted-shots")
Path(build.OUT).mkdir(parents=True, exist_ok=True)
TAKES = Path(build.T)
FILM = Path(build.FILM)
TRACK = os.environ.get("PROMO_MUSIC", "minecraft/sounds/records/blocks.ogg")
TRACK_START = float(os.environ.get("PROMO_MUSIC_START", 0))
MUSIC_GAIN = .16

# (shot, take, clip, caption place, pieces): pieces are (start, end, speed) in the clip's own seconds, cut from
# what the take's samples say happened (see plan()).
SHOTS = [
    ("ride", "mounted-work", "01-ride", "tl"),
    ("muster", "mounted-combat", "01-muster", "tl"),
    ("battle", "mounted-combat", "02-battle", "tl"),
    ("cart-load", "carts-line", "01-loading", "tl"),
    ("cart-haul", "carts-line", "02-haul", "tl"),
    ("cart-unload", "carts-line", "03-unloading", "tl"),
]


def probe(path):
    return json.loads(subprocess.check_output([
        "ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", str(path)], text=True))


def duration(path):
    return float(probe(path)["format"]["duration"])


def track_file(name):
    """A local file, or a Minecraft asset name looked up in the local asset indexes."""
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


def read_take(scope, clip):
    index = json.loads((TAKES / scope / "index.json").read_text())
    if index["context"].get("verified") is not True:
        raise ValueError(f"{scope}: the take did not verify what it films; re-record before editing")
    entries = [e for e in index["entries"] if e["kind"] == "clip" and e["name"] == clip]
    if len(entries) != 1:
        raise ValueError(f"{scope}: expected exactly one {clip} clip")
    gates = entries[0]["gates"]
    if gates["stop_status"] != "saved" or not gates["audio_muxed"] or gates["frames"] <= 0:
        raise ValueError(f"{scope}/{clip}: incomplete video or missing game audio")
    video = next(s for s in probe(TAKES / scope / f"{clip}.mp4")["streams"] if s["codec_type"] == "video")
    if (video["width"], video["height"]) != (1920, 1080):
        raise ValueError(f"{scope}: expected native 1920x1080 footage")
    return index


def seconds_where(samples, predicate, fallback):
    return next((s["atMs"] / 1000 for s in samples if predicate(s)), fallback)


def plan(indexes):
    """Each shot's pieces, read from its take's own samples rather than guessed."""
    shots = []
    for name, scope, clip, place in SHOTS:
        length = duration(TAKES / scope / f"{clip}.mp4") - .2
        context = indexes[scope]["context"]
        pieces = [(0.5, length, 1.0)]
        if name == "ride":
            # From the first steps until a rider has walked in through the workshop door, plus a beat.
            origin = context["site"]["origin"]
            inside = seconds_where(context["samples"], lambda s: any(
                not r["passenger"] and r["x"] > origin["x"] + 46.6 for r in s["riders"]), length - 3)
            end = min(length, inside + 4.0)
            # The walk to the horses and the arrival at the door at speed, the ride between a touch faster.
            pieces = [(0.5, 6.0, 1.0), (6.0, end - 6.0, 1.5), (end - 6.0, end, 1.0)]
        elif name == "muster":
            pieces = [(0.5, min(length, 21.0), 1.25)]
        elif name == "battle":
            # Until the last zombie falls, plus a beat.
            samples = context["battle"]
            kills = [sum(r["kills"] for r in s["riders"]) for s in samples]
            done = next((s["atMs"] / 1000 for s, k in zip(samples, kills) if k >= max(kills)), length)
            # The wave walking in a touch faster, the fighting at speed.
            pieces = [(1.5, 10.0, 1.5), (10.0, min(length, done + 2.5), 1.0)]
        elif name == "cart-unload":
            # The wheat coming off and the cart turning away; after that the road is empty.
            samples = context["unloadingSamples"]
            emptied = seconds_where(samples, lambda s: s["cart"]["wheat"] == 0, length)
            departed = seconds_where(samples, lambda s: s["cart"]["wheat"] == 0 and s["cart"]["moving"]
                                     and s["atMs"] / 1000 > emptied, length - 3)
            pieces = [(0.5, min(length, departed + 3.0), 1.0)]
        elif name == "cart-haul":
            # The road between the stations is long; the cart rolling along it reads well at double speed.
            pieces = [(0.5, length, 2.0)]
        review = context.get("visualReview", {}).get("editPieces", {}).get(name)
        if review:
            pieces = [tuple(p) for p in review]
        shots.append({"name": name, "scope": scope, "source": f"{scope}/{clip}.mp4", "pieces": pieces, "place": place})
    return shots


def stills(indexes, directory):
    directory.mkdir(parents=True, exist_ok=True)
    for name, scope, clip, _ in SHOTS:
        source = TAKES / scope / f"{clip}.mp4"
        seconds = duration(source)
        for i, at in enumerate([1, seconds * .2, seconds * .4, seconds * .6, seconds * .8, seconds - 1], 1):
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{max(0, at):.2f}", "-i", str(source),
                            "-frames:v", "1", str(directory / f"{name}-source-{i}.png")], check=True)
    return directory


def mix_music(source, output, seconds, music):
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stills", action="store_true", help="only extract source frames for review")
    args = parser.parse_args()
    indexes = {}
    for _, scope, clip, _ in SHOTS:
        indexes.setdefault(scope, read_take(scope, clip))
    directory = FILM / "mounted-stills"
    stills(indexes, directory)
    if args.stills:
        print(directory)
        return
    build.fetch_fonts()
    build.gradients()
    music = track_file(TRACK)
    segments, timeline, offset = [], [], 0.0
    for shot in plan(indexes):
        print("render", shot["name"], shot["pieces"], flush=True)
        path, _ = build.render_shot(shot["name"], shot["source"], [(shot["name"], shot["pieces"])], 0.8,
                                    shot["place"])
        actual = duration(path)
        segments.append(path)
        timeline.append({**shot, "start": offset, "duration": actual})
        offset += actual
    card = build.render_card("ending")
    if card:
        segments.append(card[0])
        timeline.append({"name": "card:ending", "start": offset, "duration": card[1]})
        offset += card[1]
    listing = Path(build.OUT) / "concat.txt"
    listing.write_text("".join(f"file '{Path(p).resolve()}'\n" for p in segments))
    native = Path(build.WORK) / "folkways-mounted-native.mp4"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", str(listing),
                    "-map", "0", "-c", "copy", "-movflags", "+faststart", str(native)], check=True)
    output = FILM / "folkways-mounted.mp4"
    mix_music(native, output, offset, music)
    report = {"duration": duration(output), "width": 1920, "height": 1080, "cuts": "hard",
              "music": {"track": TRACK, "gain": MUSIC_GAIN}, "timeline": timeline,
              "evidence": {scope: {k: index["context"].get(k) for k in ("rides", "stuckBesideHorse", "summary", "cavalry", "verdict")
                                   if k in index["context"]} for scope, index in indexes.items()}}
    (FILM / "mounted-edit.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    for shot in timeline:
        for i, fraction in enumerate([.2, .5, .8], 1):
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{shot['start'] + shot['duration'] * fraction:.2f}",
                            "-i", str(output), "-frames:v", "1",
                            str(directory / f"{shot['name'].replace(':', '-')}-film-{i}.png")], check=True)
    print(output, directory, sep="\n", flush=True)


if __name__ == "__main__":
    main()
