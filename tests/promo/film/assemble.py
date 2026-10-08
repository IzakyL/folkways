"""Join the rendered shots with crossfades, lay the music under them and encode two films:
folkways-promo-upload.mp4 at the size the takes were filmed at, for the video platform, and
folkways-promo-preview.mp4 at 720p30, small enough to stream for a look."""
import glob, json, os, subprocess, sys
HERE = os.path.dirname(os.path.abspath(__file__))
FILM = os.path.normpath(os.path.join(HERE, "..", "..", "out", "promo", "film"))
WORK = os.path.join(FILM, "work")
# The music is one of Minecraft's own tracks, read from the game assets the test clients already downloaded.
# PROMO_MUSIC picks another track by its asset name (or a file path), PROMO_MUSIC_START where in it to begin.
TRACK = os.environ.get("PROMO_MUSIC", "minecraft/sounds/music/game/creative/aria_math.ogg")
TRACK_START = float(os.environ.get("PROMO_MUSIC_START", 0))
ASSET_INDEXES = [os.path.join(HERE, "..", "..", "..", ".blockwright-store", "runtime", "*", "assets", "indexes", "*.json"),
                 os.path.expanduser("~/.gradle/caches/neoformruntime/assets/indexes/*.json")]
GRAPH = os.path.join(WORK, "txt", "final.fc")
XF = 0.6
cut = json.load(open(os.path.join(WORK, "shots", "segments.json")))
W, H, segs = cut["width"], cut["height"], cut["segments"]
total = sum(d for _, d in segs) - XF * (len(segs) - 1)
print(f"total {total:.2f}s at {W}x{H}", flush=True)

def track_file(name):
    """The track's file in a game asset store, looked up by its name in that store's index."""
    if os.path.isfile(name):
        return name
    for index in (path for pattern in ASSET_INDEXES for path in sorted(glob.glob(pattern))):
        found = json.load(open(index))["objects"].get(name)
        if found:
            path = os.path.join(os.path.dirname(os.path.dirname(index)), "objects", found["hash"][:2], found["hash"])
            if os.path.isfile(path):
                return path
    sys.exit(f"no game asset store holds {name}; run any test client once (npm run promo:take) to download one")

MUSIC = track_file(TRACK)
inp, fc = [], []
for p, _ in segs: inp += ["-i", p]
inp += ["-ss", str(TRACK_START), "-i", MUSIC]
v, a, acc = "0:v", "0:a", segs[0][1]
for i in range(1, len(segs)):
    off = acc - XF
    fc.append(f"[{v}][{i}:v]xfade=transition=fade:duration={XF}:offset={off:.3f}[v{i}]")
    fc.append(f"[{a}][{i}:a]acrossfade=d={XF}[a{i}]")
    v, a = f"v{i}", f"a{i}"
    acc = off + segs[i][1]
n = len(segs)
fc.append(f"[{v}]fade=t=in:st=0:d=1.2,fade=t=out:st={total-1.8:.3f}:d=1.8,split[full][small]")
fc.append("[small]scale=1280:720:flags=lanczos,fps=30[preview]")
fc.append(f"[{n}:a]aresample=48000,volume=0.9[m]")
fc.append(f"[{a}][m]amix=inputs=2:duration=first:normalize=0,afade=t=in:d=1,afade=t=out:st={total-2.5:.3f}:d=2.5,"
          "alimiter=limit=0.95,asplit[afull][apreview]")
open(GRAPH, "w").write(";\n".join(fc))

# The upload keeps all the detail the platform will re-encode from; the preview is capped to stream anywhere.
four_k = H >= 2160
upload = ["-map", "[full]", "-map", "[afull]",
          "-c:v", "libx264", "-preset", "slow", "-crf", "16",
          "-maxrate", "60M" if four_k else "20M", "-bufsize", "120M" if four_k else "40M",
          "-profile:v", "high", "-level:v", "5.2" if four_k else "4.2",
          "-g", "120", "-keyint_min", "120", "-sc_threshold", "0", "-pix_fmt", "yuv420p",
          "-c:a", "aac", "-b:a", "320k", "-movflags", "+faststart", os.path.join(FILM, "folkways-promo-upload.mp4")]
preview = ["-map", "[preview]", "-map", "[apreview]",
           "-c:v", "libx264", "-preset", "slow", "-crf", "24", "-maxrate", "2.5M", "-bufsize", "5M",
           "-profile:v", "high", "-level:v", "4.0", "-g", "60", "-keyint_min", "60", "-sc_threshold", "0",
           "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart",
           os.path.join(FILM, "folkways-promo-preview.mp4")]
subprocess.run(["ffmpeg", "-v", "error", "-y", *inp, "-/filter_complex", GRAPH,
                *upload, *preview], check=True)
