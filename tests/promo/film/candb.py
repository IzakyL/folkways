"""Cut the C&B short with the existing promo titles, sound mix, crossfades and delivery encodes."""
import json, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
os.environ["PROMO_TEXT"] = os.path.join(HERE, "promo-candb.toml")
os.environ.setdefault("PROMO_MUSIC", "minecraft/sounds/music/game/creative/biome_fest.ogg")
# The track stays almost silent for its first minute; start where it can already be heard.
os.environ.setdefault("PROMO_MUSIC_START", "70")
import build

build.OUT = os.path.join(build.WORK, "candb-shots")
os.makedirs(build.OUT, exist_ok=True)
TAKE = os.path.join(build.T, "candb")


def length(name, folder=TAKE):
    probe = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0",
                            os.path.join(folder, name)], capture_output=True, text=True, check=True)
    return float(probe.stdout.strip()) - 0.1


def main():
    build.fetch_fonts()
    # A lighter top veil for the landscape shots; keep the shared films' gradients untouched.
    build.gradient = lambda top: os.path.join(build.OUT, f"gradient-{top}-{build.H}.png")
    for top in (True, False):
        expression = "135*pow(1-Y/H,1.6)" if top else "180*pow(Y/H,1.3)"
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i",
                        f"color=black:s={build.W}x{build.px(420)},format=rgba", "-vf",
                        f"geq=r=0:g=0:b=0:a='{expression}'", "-frames:v", "1", build.gradient(top)], check=True)
    construction = length("01-construction.mp4")
    # Keep the whole real construction, compressed to 18 seconds; the last three seconds hold the finished roof.
    work = max(0.1, construction - 3.0)
    pieces = [(0.0, work, max(1.0, work / 18.0)), (work, construction, 1.0)]
    shots = [("construction", "candb/01-construction.mp4", [("construction", pieces)], 0.25, "tl")]
    patterns = ["pavilion", "gothic_cloister", "stone_arch_bridge", "rotunda", "torii", "rampart", "curved_gallery"]
    for pattern in patterns:
        folder = "candb-" + pattern
        end = length(pattern + ".mp4", os.path.join(build.T, folder))
        context = json.load(open(os.path.join(build.T, folder, "index.json")))["context"]
        drafts = context["shot"]["drafts"]
        if len(drafts) != 4 or len(context.get("printing", [])) != 4:
            raise RuntimeError(f"{pattern}: need four completed mouse placements and printer completion records")
        for instance in range(1, 5):
            sync = context[f"sync-{instance}"]
            if sync["server"]["empty"] or sync["client"]["cells"] != sync["server"]["cells"]:
                raise RuntimeError(f"{pattern} instance {instance}: live client voxels do not match the server")
        pieces = []
        for index, draft in enumerate(drafts):
            hold = draft["end"] if index == len(drafts) - 1 else min(draft["end"], draft["raised"] + 1.2)
            # Keep every real drawing gesture at normal speed; compress panel work and the server's print wait.
            pieces.extend([(draft["start"], draft["drawn"], 1.0),
                           (draft["drawn"], draft["raised"], 6.0),
                           (draft["raised"], min(end, hold), 1.0)])
        shots.append((pattern, folder + "/" + pattern + ".mp4",
                      [(pattern, pieces)], 0.25, "tr"))
    build.SHOTS = shots
    segments = []
    for shot in shots:
        print("render", shot[0], flush=True)
        segments.append(build.render_shot(*shot))
    with open(os.path.join(build.OUT, "segments.json"), "w") as f:
        json.dump({"width": build.W, "height": build.H, "segments": segments}, f)
    os.environ["PROMO_SHOTS"], os.environ["PROMO_NAME"] = build.OUT, "folkways-candb"
    import assemble


if __name__ == "__main__":
    main()
