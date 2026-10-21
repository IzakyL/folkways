"""Export the complete original Cookery recording and a six-times-speed viewing copy."""
import json
import shutil
import subprocess
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROMO = HERE.parents[2] / "tests" / "out" / "promo"
TAKE = PROMO / "takes" / "cookery" / "01-kitchen-dining.mp4"
OUT = PROMO / "film"


def write_credits():
    """Retain the cast's source, license and original author metadata beside the recording."""
    credits = ["Wine Fox / 酒狐 model credits", "Source: https://github.com/OpenYSM/OpenYSM",
               "License: CC BY-NC-SA 4.0 — https://creativecommons.org/licenses/by-nc-sa/4.0/",
               "Promo adaptation: hidden YSM-only bones removed; resident animation rendering.", ""]
    for folder in sorted((HERE.parent / "models").glob("wine_fox_*")):
        metadata = json.loads((folder / "ysm.json").read_text(encoding="utf-8"))["metadata"]
        credits.append(metadata["name"])
        for author in metadata["authors"]:
            credits.append(f"  {author['name']} — {author.get('role', '')} {json.dumps(author.get('contact', {}), ensure_ascii=False)}")
        credits.append("")
    (OUT / "folkways-cookery-elevator-credits.txt").write_text("\n".join(credits), encoding="utf-8")


def main():
    if not TAKE.is_file() or TAKE.stat().st_size == 0:
        raise FileNotFoundError(f"record the Cookery take first: {TAKE}")
    OUT.mkdir(parents=True, exist_ok=True)
    destination = OUT / "folkways-cookery-raw.mp4"
    # Copy the source bytes, including every frame and the original audio tracks and metadata.
    shutil.copy2(TAKE, destination)
    write_credits()
    print(destination, flush=True)
    accelerated = OUT / "folkways-cookery-2x.mp4"
    subprocess.run([
        "ffmpeg", "-hide_banner", "-y", "-i", str(destination),
        "-map", "0:v:0", "-map", "0:a:0?",
        "-vf", "setpts=PTS/2,fps=60",
        "-af", "atempo=2",
        "-c:v", "libx264", "-crf", "20", "-preset", "fast", "-threads", "4",
        "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k",
        "-movflags", "+faststart", str(accelerated),
    ], check=True)
    print(accelerated, flush=True)


if __name__ == "__main__":
    main()
