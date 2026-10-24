"""Assemble the recorded Cookery takes into three films, and write the cast's model credits."""
import json
import shutil
import subprocess
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROMO = HERE.parents[2] / "tests" / "out" / "promo"
TAKES = {
    "cookery-kitchen": ["01-kitchen", "02-dining"],
    "cookery-wine": ["01-vineyard", "02-pressing-and-fermentation", "03-fermentation-and-bottling", "04-drinks-at-the-bar"],
    "create-elevator": ["01-elevator"],
}
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
    (OUT / "folkways-cookery-credits.txt").write_text("\n".join(credits), encoding="utf-8")


def main():
    groups = {take: [PROMO / "takes" / take / f"{name}.mp4" for name in names]
              for take, names in TAKES.items()}
    missing = [str(clip) for clips in groups.values() for clip in clips if not clip.exists() or not clip.stat().st_size]
    if missing:
        raise FileNotFoundError(f"record these Cookery clips first: {', '.join(missing)}")
    OUT.mkdir(parents=True, exist_ok=True)
    for take, clips in groups.items():
        destination = OUT / f"folkways-{take}.mp4"
        if len(clips) == 1:
            shutil.copy2(clips[0], destination)
        else:
            with tempfile.NamedTemporaryFile(mode="w", suffix=".txt") as listing:
                listing.write("".join(f"file '{clip.resolve()}'\n" for clip in clips))
                listing.flush()
                subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", listing.name,
                                "-map", "0", "-c", "copy", "-movflags", "+faststart", str(destination)], check=True)
        print(destination, flush=True)
    write_credits()


if __name__ == "__main__":
    main()
