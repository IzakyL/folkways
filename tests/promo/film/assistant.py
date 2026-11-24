"""Cut the colony assistant promo from its takes: a station connected, a gate calibrated and opened, a fan smelter
calibrated and fed an order, a walker calibrated and sent into battle, and a walker's drill arm calibrated to dig a
picked box.

Captions live in promo-assistant.toml; build.py draws them. Run from the repository root after `npm run promo:assistant`:
    python3 tests/promo/film/assistant.py            # the film
    python3 tests/promo/film/assistant.py --stills   # source frames of every clip, for review before cutting
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve().parent
os.environ["PROMO_TEXT"] = str(HERE / "promo-assistant.toml")
os.environ["PROMO_HEIGHT"] = "1080"
sys.path.insert(0, str(HERE))
import build  # noqa: E402
from mounted import duration, track_file  # noqa: E402

build.OUT = str(Path(build.WORK) / "assistant-shots")
Path(build.OUT).mkdir(parents=True, exist_ok=True)
TAKES = Path(build.T)
FILM = Path(build.FILM)
TRACK = os.environ.get("PROMO_MUSIC", "minecraft/sounds/music/game/creative/aria_math.ogg")
TRACK_START = float(os.environ.get("PROMO_MUSIC_START", 0))
MUSIC_GAIN = .18
# (shot, take scope, clip, caption place)
SHOTS = [
    ("rail-ask", "assistant-rail", "01-ask", "bl"),
    ("door-ask", "assistant-door", "01-ask", "bl"),
    ("door-open", "assistant-door", "02-open", "tl"),
    ("smelter-ask", "assistant-smelter", "01-ask", "bl"),
    ("smelter-smelt", "assistant-smelter", "02-smelt", "tl"),
    ("walker-ask", "assistant-walker", "01-ask", "bl"),
    ("walker-command", "assistant-walker", "02-command", "tl"),
    ("drill-ask", "assistant-drill", "01-ask", "bl"),
    ("drill-dig", "assistant-drill", "02-dig", "tl"),
]
# How long the assistant's thinking may last on screen: a long stretch is sped up to fit, never slowed. Thinking may
# run far faster than the action, which stays within whole-ish speeds a viewer can follow.
THINKING = 14.0
THINKING_CAP = 64.0
TAIL = 2.5


def stretch(a, b, seconds, cap=8.0):
    """[a, b] played in about `seconds`, at whole-ish speeds, never faster than `cap`."""
    if b - a <= seconds:
        return [(a, b, 1.0)]
    return [(a, b, round(min(cap, (b - a) / seconds), 2))]


def waited(marks, start, end):
    """[start, end] at speed but for each wait the take marked (`wait-N` to `resume-N`), sped up."""
    pieces, at = [], start
    for key in sorted((k for k in marks if k.startswith("wait-")), key=lambda k: marks[k]):
        resume = marks.get("resume-" + key[5:])
        if resume is None or marks[key] < at or resume > end:
            continue
        if marks[key] > at:
            pieces.append((at, marks[key], 1.0))
        pieces += stretch(marks[key], resume, 2.0)
        at = resume
    pieces.append((at, end, 1.0))
    return [p for p in pieces if p[1] - p[0] > 0.05]


def pieces_for(name, marks, length):
    """The clip's run: the box drawn, the asking and every review at speed, the waits in between sped up."""
    if name == "drill-dig" and "picked" in marks and "dug" in marks:
        picked, dug = marks["picked"], marks["dug"]
        end = min(length, marks.get("cut", dug + TAIL) + 1.5)
        speed = max(2.0, round((dug - picked) / 14.0, 2))
        return [(0.5, picked, 1.0), (picked, dug, speed), (dug, end, 1.0)]
    if "asked" in marks:
        # From just before the box is drawn with the book (or the chat opened, for a plain question).
        start = max(0.5, marks.get("box", marks.get("open", 0.5)) - 0.3)
        reviews = sorted(v for k, v in marks.items() if k.startswith("review-"))
        approved = sorted(v for k, v in marks.items() if k.startswith("approved-"))
        done = next((marks[k] for k in ("hit", "fired", "raised", "first-block", "arrived") if k in marks), None)
        end = min(length, (done + 3.0) if done is not None else marks.get("answered", length) + TAIL)
        closed = marks.get("chat-closed")
        if closed is not None:
            end = min(length, closed + 4.5)
        cuts = [start, min(length, marks["asked"] + 1.5)]
        pieces = [(cuts[0], cuts[1], 1.0)]
        at = cuts[1]
        for review, decided in zip(reviews, approved):
            lead = max(at, review - 1.5)
            pieces += stretch(at, lead, THINKING, THINKING_CAP) if at == cuts[1] else stretch(at, lead, 4.0)
            pieces.append((lead, min(length, decided + 1.5), 1.0))
            at = min(length, decided + 1.5)
        if closed is not None:
            answered = max(at, marks.get("answered", closed) - 0.5)
            pieces += stretch(at, answered, 5.0)
            pieces.append((answered, end, 1.0))
        elif done is not None and done - 2.5 > at:
            # The wait for the action sped up, the action itself at speed.
            pieces += stretch(at, done - 2.5, 5.0)
            pieces.append((done - 2.5, end, 1.0))
        elif at < end:
            pieces += stretch(at, end, 5.0)
        return [p for p in pieces if p[1] - p[0] > 0.05]
    if any(k.startswith("wait-") for k in marks):
        end = min(length, marks["cut"] + 1.5) if "cut" in marks else length
        if name == "walker-command" and "attacked-1" in marks and "fired-1" in marks:
            # Let the first physical cannon turn play at its recorded speed.
            marks = {key: time for key, time in marks.items()
                     if not (key.startswith("wait-") and marks["attacked-1"] <= time <= marks["fired-1"])}
        return waited(marks, 0.5, end)
    done = next((marks[k] for k in ("cut", "raised", "hit", "fired", "attacked", "arrived") if k in marks), None)
    end = min(length, done + TAIL + 1.5) if done is not None else length
    return stretch(0.5, end, 22.0) if end - 0.5 > 30 else [(0.5, end, 1.0)]


def plan():
    shots = []
    for name, scope, clip, place in SHOTS:
        source = TAKES / scope / f"{clip}.mp4"
        if not source.exists():
            continue
        index = json.loads((TAKES / scope / "index.json").read_text())
        context = index.get("context", {})
        marks = context.get(f"marks-{clip}", {})
        pieces = pieces_for(name, marks, duration(source) - .2)
        review = context.get("visualReview", {}).get("editPieces", {}).get(name)
        if review and name not in {"rail-ask", "drill-dig"}:
            pieces = [tuple(p) for p in review]
        shots.append({"name": name, "source": f"{scope}/{clip}.mp4", "pieces": pieces, "place": place})
    return shots


def stills(directory):
    directory.mkdir(parents=True, exist_ok=True)
    for name, scope, clip, _ in SHOTS:
        source = TAKES / scope / f"{clip}.mp4"
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
    directory = FILM / "assistant-stills"
    if args.stills:
        print(stills(directory))
        return
    build.fetch_fonts()
    build.gradients()
    music = track_file(TRACK)
    segments, timeline, offset = [], [], 0.0
    for shot in plan():
        print("render", shot["name"], shot["pieces"], flush=True)
        path, _ = build.render_shot(shot["name"], shot["source"], [(shot["name"], shot["pieces"])], 0.8, shot["place"])
        actual = duration(path)
        segments.append(path)
        timeline.append({**shot, "start": offset, "duration": actual})
        offset += actual
    listing = Path(build.OUT) / "concat.txt"
    listing.write_text("".join(f"file '{Path(p).resolve()}'\n" for p in segments))
    native = Path(build.WORK) / "folkways-assistant-native.mp4"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", str(listing),
                    "-map", "0", "-c", "copy", "-movflags", "+faststart", str(native)], check=True)
    output = FILM / "folkways-assistant.mp4"
    mix_music(native, output, offset, music)
    report = {"duration": duration(output), "width": 1920, "height": 1080, "cuts": "hard",
              "music": {"track": TRACK, "gain": MUSIC_GAIN}, "timeline": timeline}
    (FILM / "assistant-edit.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    directory.mkdir(parents=True, exist_ok=True)
    for shot in timeline:
        for i, fraction in enumerate([.2, .5, .8], 1):
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{shot['start'] + shot['duration'] * fraction:.2f}",
                            "-i", str(output), "-frames:v", "1",
                            str(directory / f"{shot['name']}-film-{i}.png")], check=True)
    print(output, directory, sep="\n", flush=True)


if __name__ == "__main__":
    main()
