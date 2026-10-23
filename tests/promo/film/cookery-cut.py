"""Cut the Cookery takes into one short: the kitchen setting a table and diners eating at it, the vineyard built and picked,
grapes pressed, the barrel filled from its top and its wine bottled at the tap and drunk at the bar, all from the one
tavern view, and residents riding the elevator to bed. Each take is a fixed camera, so waits are quickened rather than cut; only the stretches that show nothing new are left out. The pieces
are (from, to, speed) in the take's own seconds, read off its contact sheet. Its words live in promo-cookery.toml:
one caption a feature, over the first shot of it; the tavern's later shots name sections that carry no words."""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
os.environ["PROMO_TEXT"] = os.path.join(HERE, "promo-cookery.toml")
import build

build.OUT = os.path.join(build.WORK, "cookery-shots")
os.makedirs(build.OUT, exist_ok=True)

SHOTS = [
    ("kitchen", "cookery-kitchen/01-kitchen.mp4", [
        ("kitchen", [(0.0, 38.0, 4.0), (38.0, 54.0, 2.0)]),
    ], 0.4, "bl"),
    ("vineyard", "cookery-wine/01-vineyard.mp4", [
        ("tavern", [(0.0, 36.0, 3.0), (36.0, 140.0, 10.0)]),
    ], 0.4, "bl"),
    ("pressing", "cookery-wine/02-pressing-and-fermentation.mp4", [
        ("tavern_pressing", [(0.0, 30.0, 10.0), (30.0, 65.0, 4.0)]),
    ], 0.4, "bl"),
    ("bottling", "cookery-wine/03-fermentation-and-bottling.mp4", [
        ("tavern_bottling", [(0.0, 9.0, 4.0), (9.0, 17.0, 1.5), (17.0, 78.0, 20.0), (78.0, 105.0, 3.0)]),
    ], 0.4, "bl"),
    ("bar", "cookery-wine/04-drinks-at-the-bar.mp4", [
        ("tavern_bar", [(0.0, 16.0, 2.0), (16.0, 40.0, 6.0)]),
    ], 0.4, "bl"),
    ("elevator", "create-elevator/01-elevator.mp4", [
        ("elevator", [(0.0, 15.0, 1.0), (15.0, 23.0, 4.0)]),
    ], 0.4, "bl"),
]
build.SHOTS = SHOTS


def main():
    build.fetch_fonts()
    build.gradients()
    print(f"cutting at {build.W}x{build.H}", flush=True)
    segs = []
    for shot in SHOTS:
        print("render", shot[0], flush=True)
        segs.append(build.render_shot(*shot))
    with open(os.path.join(build.OUT, "segments.json"), "w") as f:
        json.dump({"width": build.W, "height": build.H, "segments": segs}, f)
    os.environ["PROMO_SHOTS"], os.environ["PROMO_NAME"] = build.OUT, "folkways-cookery-cut"
    os.environ.setdefault("PROMO_MUSIC", "minecraft/sounds/music/game/comforting_memories.ogg")
    import assemble  # joins the segments just written and lays the music under them


if __name__ == "__main__":
    main()
