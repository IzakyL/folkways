"""Prepare the standalone elevator promo from its real-time take, keeping the game's own sound."""
import glob
import json
from pathlib import Path
import subprocess

REPO = Path(__file__).resolve().parents[3]
TAKE = REPO / "tests/out/promo/takes/elevator"
OUT = REPO / "tests/out/promo/film"
OUT.mkdir(parents=True, exist_ok=True)
VIDEO = OUT / "folkways-elevator.mp4"
POSTER = OUT / "folkways-elevator-poster.png"
SOURCE = TAKE / "01-elevator.mp4"
TRACK = "minecraft/sounds/music/game/creative/biome_fest.ogg"
MUSIC_START = 45


def minecraft_track(name):
    patterns = [str(REPO / ".blockwright-store/runtime/*/assets/indexes/*.json"),
                str(Path.home() / ".gradle/caches/neoformruntime/assets/indexes/*.json")]
    for pattern in patterns:
        for index in sorted(glob.glob(pattern)):
            asset = json.loads(Path(index).read_text())["objects"].get(name)
            if asset:
                sound = Path(index).parents[1] / "objects" / asset["hash"][:2] / asset["hash"]
                if sound.is_file():
                    return sound
    raise FileNotFoundError(f"Minecraft music asset missing: {name}. Run a promo client to prepare its assets.")


context = json.loads((TAKE / "index.json").read_text())["context"]
segments = context.get("segments")
if segments:
    # Each shot is captured with the world frozen while the next camera is set.
    # Trim capture tails before joining, so the activity timestamps keep matching the film.
    SOURCE = OUT / "elevator-working-colony-cut.mp4"
    inputs, filters, labels = [], [], []
    for index, segment in enumerate(segments):
        inputs.extend(["-i", str(TAKE / (segment["name"] + ".mp4"))])
        seconds = segment["seconds"]
        filters.extend([f"[{index}:v]trim=duration={seconds},setpts=PTS-STARTPTS[v{index}]",
                        f"[{index}:a]atrim=duration={seconds},asetpts=PTS-STARTPTS[a{index}]"])
        labels.append(f"[v{index}][a{index}]")
    filters.append("".join(labels) + f"concat=n={len(segments)}:v=1:a=1[video][sound]")
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *inputs,
                    "-filter_complex", ";".join(filters), "-map", "[video]", "-map", "[sound]",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "16",
                    "-c:a", "aac", "-b:a", "192k", "-ar", "48000", str(SOURCE)], check=True)

music = minecraft_track(TRACK)

probe = json.loads(subprocess.check_output([
    "ffprobe", "-v", "error", "-show_format", "-of", "json", str(SOURCE),
]))
duration = min(45.0, float(probe["format"]["duration"]))
end = max(0.0, duration - 0.6)
subprocess.run([
    "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(SOURCE),
    "-ss", str(MUSIC_START), "-i", str(music), "-t", str(duration),
    "-vf", f"fade=t=in:st=0:d=0.4,fade=t=out:st={end}:d=0.6",
    "-filter_complex", (
        "[0:a]aresample=48000,volume=0.8[game];"
        "[1:a]aresample=48000,loudnorm=I=-22:TP=-2:LRA=9,"
        f"afade=t=in:d=1.4,afade=t=out:st={duration-2.5}:d=2.5[music];"
        "[game][music]amix=inputs=2:duration=first:normalize=0,alimiter=limit=0.95,"
        f"afade=t=in:d=0.4,afade=t=out:st={end}:d=0.6[audio]"
    ),
    "-map", "0:v", "-map", "[audio]",
    "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
    "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-movflags", "+faststart", str(VIDEO),
], check=True)

context = json.loads((TAKE / "index.json").read_text())["context"]
journeys = context["journeys"]
occupied = [state for state in journeys if state["passengers"] and 2 < state["second"] < duration - 2]
low = min(state["contactY"] for state in journeys)
high = max(state["contactY"] for state in journeys)
visible_lift = [state for state in occupied if state.get("shot", "01-elevator") in ("01-elevator", "04-upper-construction")]
frame = min(visible_lift or occupied, key=lambda state: abs(state["contactY"] - (low + (high - low) * 0.65)))
subprocess.run([
    "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-ss", str(frame["second"]),
    "-i", str(VIDEO), "-frames:v", "1", str(POSTER),
], check=True)
print(VIDEO)
print(POSTER)

# Carry the model authors and original metadata alongside the exported film.
credits = ["Wine Fox / 酒狐 model credits", "Source: https://github.com/OpenYSM/OpenYSM",
           "License: CC BY-NC-SA 4.0 — https://creativecommons.org/licenses/by-nc-sa/4.0/",
           "Promo adaptation: hidden YSM-only bones removed; resident animation rendering.", "",
           "Background music: Biome Fest — C418", "From Minecraft's original game assets: " + TRACK,
           "Excerpt starts at 00:45; normalized and mixed below in-game sound effects, with fades.", ""]
for folder in sorted((REPO / "tests/promo/models").glob("wine_fox_*")):
    metadata = json.loads((folder / "ysm.json").read_text())["metadata"]
    credits.append(metadata["name"])
    for author in metadata["authors"]:
        credits.append(f"  {author['name']} — {author.get('role', '')} {json.dumps(author.get('contact', {}), ensure_ascii=False)}")
    credits.append("")
(OUT / "folkways-elevator-credits.txt").write_text("\n".join(credits))
