"""Cut the storage promo (stores from many mods, ME and Refined Storage orders, a singing backpack) from its take.

Captions live in promo-storage.toml; build.py draws them. Run from the repository root after `npm run promo:storage`:
    python3 tests/promo/film/storage.py            # the film
    python3 tests/promo/film/storage.py --stills   # source frames of every clip, for review before cutting
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve().parent
os.environ["PROMO_TEXT"] = str(HERE / "promo-storage.toml")
os.environ["PROMO_HEIGHT"] = "1080"
sys.path.insert(0, str(HERE))
import build  # noqa: E402
from mounted import duration, track_file  # noqa: E402

build.OUT = str(Path(build.WORK) / "storage-shots")
Path(build.OUT).mkdir(parents=True, exist_ok=True)
TAKES = Path(build.T)
FILM = Path(build.FILM)
SCOPE = "storage"
TRACK = os.environ.get("PROMO_MUSIC", "minecraft/sounds/records/blocks.ogg")
TRACK_START = float(os.environ.get("PROMO_MUSIC_START", 0))
MUSIC_GAIN = .16
# The last shot plays the resident's own disc: the film's music is gone by then.
OWN_MUSIC = "music"
SHOTS = [
    ("enroll", "01-enroll", "tl"),
    ("row", "02-row", "tl"),
    ("ae2-order", "03-ae2-order", "tl"),
    ("rs2-order", "04-rs2-order", "tl"),
    ("terminal", "05-terminal", "tl"),
    ("smelt", "06-smelt", "tl"),
    ("profile", "07-profile", "tl"),
    ("gear-up", "08-gear-up", "tl"),
    ("music", "09-music", "tl"),
]


def read_take():
    index = json.loads((TAKES / SCOPE / "index.json").read_text())
    return index


def waits(start, events, length, quick=1.0, slow=3.0, lead=2.5, tail=3.0):
    """Pieces that play each event at speed and the waits between them fast: (from, to, speed) in clip seconds."""
    times = sorted(t for t in events if t is not None and start < t < length)
    pieces, at = [], start
    for t in times:
        if t - lead > at + 1.0:
            pieces.append((at, t - lead, slow))
            at = t - lead
        if t + tail > at:
            pieces.append((at, min(length, t + tail), quick))
            at = min(length, t + tail)
    if length - at > 0.5:
        pieces.append((at, length, quick))
    return pieces


def plan(index):
    """Each shot's pieces in its clip's own seconds, cut by the moments the take marked."""
    context = index["context"]
    shots = []
    for name, clip, place in SHOTS:
        length = duration(TAKES / SCOPE / f"{clip}.mp4") - .2
        marks = context.get(f"marks-{clip}", {})
        pieces = [(0.5, length, 1.0)]
        if name == "enroll":
            # Ten stores in a row read well a touch faster than life.
            pieces = [(0.5, length, 1.25)]
        elif name in ("ae2-order", "rs2-order"):
            # The resident walking up and ordering, the network crafting, the delivery: each at speed, the walks
            # between them fast.
            done = marks.get("done", length)
            pieces = waits(0.5, [marks.get("ordered"), marks.get("crafting"), marks.get("delivering"), done],
                           min(length, done + 3.0), slow=3.0)
        elif name == "smelt":
            done = marks.get("done", length)
            pieces = waits(0.5, [marks.get("carrying"), marks.get("returned"), done], min(length, done + 2.5),
                           slow=4.0)
        elif name == "gear-up":
            done = marks.get("done", length)
            pieces = waits(0.5, [marks.get("worn"), marks.get("upgrade"), marks.get("disc"), done],
                           min(length, done + 2.0), slow=3.0)
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


def mix_music(source, output, seconds, music, quiet_from):
    """The film's track under the game's sound, ducked by it, and faded out before the resident's own disc plays."""
    fade = max(0.0, quiet_from - 2.5)
    filters = (f"[0:a]aresample=48000,asplit[game][key];"
               f"[1:a]aresample=48000,volume={MUSIC_GAIN},afade=t=in:d=1.5,"
               f"afade=t=out:st={fade:.3f}:d=2.5[music];"
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
    directory = FILM / "storage-stills"
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
        print("render", shot["name"], shot["pieces"], flush=True)
        # The disc's shot keeps the game's sound at full: it is the point of the shot.
        gain = 1.0 if shot["name"] == OWN_MUSIC else 0.8
        path, _ = build.render_shot(shot["name"], shot["source"], [(shot["name"], shot["pieces"])], gain,
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
    native = Path(build.WORK) / "folkways-storage-native.mp4"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", str(listing),
                    "-map", "0", "-c", "copy", "-movflags", "+faststart", str(native)], check=True)
    output = FILM / "folkways-storage.mp4"
    quiet_from = next((s["start"] for s in timeline if s["name"] == OWN_MUSIC), offset)
    mix_music(native, output, offset, music, quiet_from)
    report = {"duration": duration(output), "width": 1920, "height": 1080, "cuts": "hard",
              "music": {"track": TRACK, "gain": MUSIC_GAIN, "fadesBefore": quiet_from}, "timeline": timeline,
              "evidence": {k: index["context"].get(k) for k in ("evidence",)}}
    (FILM / "storage-edit.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    for shot in timeline:
        for i, fraction in enumerate([.2, .5, .8], 1):
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{shot['start'] + shot['duration'] * fraction:.2f}",
                            "-i", str(output), "-frames:v", "1",
                            str(directory / f"{shot['name'].replace(':', '-')}-film-{i}.png")], check=True)
    print(output, directory, sep="\n", flush=True)


if __name__ == "__main__":
    main()
