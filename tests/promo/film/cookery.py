"""Copy the recorded Cookery takes, one clip per feature, and write the cast's model credits."""
import json
import shutil
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROMO = HERE.parents[2] / "tests" / "out" / "promo"
TAKES = ["cookery-kitchen", "cookery-dining", "cookery-wine", "create-elevator"]
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
    clips = [clip for take in TAKES for clip in sorted((PROMO / "takes" / take).glob("*.mp4"))]
    missing = [take for take in TAKES if not any(clip.parent.name == take and clip.stat().st_size for clip in clips)]
    if missing:
        raise FileNotFoundError(f"record these Cookery takes first: {', '.join(missing)}")
    OUT.mkdir(parents=True, exist_ok=True)
    for clip in clips:
        # Copy the source bytes, including every frame and the original audio tracks and metadata.
        destination = OUT / f"folkways-{clip.parent.name}-{clip.name}"
        shutil.copy2(clip, destination)
        print(destination, flush=True)
    write_credits()


if __name__ == "__main__":
    main()
