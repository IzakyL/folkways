#!/usr/bin/env python3
"""Cuts the film again from a run's recordings, leaving out the train shots in which nobody got on or off.

  python3 tests/bench/tools/recut.py [reports dir] [output mp4]

Reads the cuts the run's report kept (each with its camera, where it starts in that camera's recording and how long it
runs), the film feed for how many rode each train, and the census for the clock. The recordings are the run's own
tests/out/bench/film/large-colony-<camera>-realtime.mp4, so this works only until the next run overwrites them.
The cuts kept are written beside the film (<output>.cuts.json), for the overlay (tests/bench/film/overlay.ts).
"""
import bisect, collections, json, os, subprocess, sys, tempfile

HERE = "tests/out/bench"
reports = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "reports")
out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "film", "large-colony-recut.mp4")

video = json.load(open(os.path.join(reports, "large-colony.json")))["video"]
cuts, speed = video["cut"]["cuts"], video["cut"]["speed"]

census = [json.loads(line) for line in open(os.path.join(reports, "large-colony.census.ndjson"))]
walls, ticks = [c["epoch_ms"] for c in census], [c["game_time"] for c in census]

def tick_at(ms):
    i = min(max(bisect.bisect(walls, ms), 1), len(walls) - 1)
    return ticks[i - 1] + (ms - walls[i - 1]) * (ticks[i] - ticks[i - 1]) / (walls[i] - walls[i - 1])

riders = collections.defaultdict(list)
for line in open(os.path.join(reports, "large-colony.film-feed.csv")):
    f = line.rstrip("\n").split(",")
    if len(f) > 7 and f[1] == "T":
        riders[f[2]].append((int(f[0]), f[3] != "", int(f[7])))

def exchange(cut):
    """How many got off and on the train of a cut while it was on the air."""
    a, b = tick_at(cut["from_ms"]), tick_at(cut["to_ms"])
    rows = [r for r in riders[cut["train"]] if a - 40 <= r[0] <= b + 40]
    standing = [n for _, stood, n in rows if stood]
    if not rows or not standing:
        return 0, 0
    least = min(standing)
    return max(0, rows[0][2] - least), max(0, rows[-1][2] - least)

kept = []
for i, cut in enumerate(cuts):
    if cut.get("train"):
        off, on = exchange(cut)
        print(f"shot {i} {cut['spot']}: {off} off, {on} on" + ("" if off or on else "  -> left out"))
        if not (off or on):
            continue
    kept.append(cut)

with tempfile.TemporaryDirectory() as tmp:
    files = []
    for i, cut in enumerate(kept):
        source = os.path.join(HERE, "film", f"large-colony-{cut['camera']}-realtime.mp4")
        files.append(os.path.join(tmp, f"{i:04d}.mp4"))
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", str(cut["at_s"]), "-t", str(cut["length_s"]), "-i", source, "-an",
                        "-vf", f"setpts=PTS/{speed},fps=30", "-c:v", "libx264", "-preset", "fast", "-crf", "20", files[-1]], check=True)
    listing = os.path.join(tmp, "list.txt")
    open(listing, "w").write("".join(f"file '{f}'\n" for f in files))
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", listing, "-c", "copy", "-movflags", "+faststart", out], check=True)

json.dump(kept, open(os.path.splitext(out)[0] + ".cuts.json", "w"))
print(f"{len(kept)} of {len(cuts)} shots, {sum(c['length_s'] for c in kept) / speed:.1f} s: {out}")
