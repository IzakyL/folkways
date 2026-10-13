"""Cut the view-from-above short from the overhead takes: its shots back to back, their words from promo-text.toml."""
import json, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import build

build.OUT = os.path.join(build.WORK, "overhead-shots")
os.makedirs(build.OUT, exist_ok=True)
TAKE = json.load(open(os.path.join(build.T, "overhead", "index.json")))["context"]
# The patterns in the order they are shown, each from a take of its own (overhead-patterns.take.ts).
PATTERNS = ["road", "arcade", "railway", "city_wall", "terraced_field", "quarry", "mine", "dock", "arch_bridge"]
# How long the residents' building of the road lasts on screen, however long it took them, and how fast the drawing
# of each pattern plays up to the moment it stands.
BUILDING_S, DRAWING = 14.0, 2.0
# Where the words sit: the top right, the one corner the view's own buttons and hints leave empty.
PLACE = "tr"
# A little lower than the film's own margin: while a line is drawn the view hangs a second row of buttons under
# its top ones, and a long caption reaches across to them.
build.MARGIN_Y = 136

def length(take):
    """How long a take's video runs: a shot is never cut past it, whatever its marks say."""
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=duration",
                            "-of", "csv=p=0", os.path.join(build.T, take)], capture_output=True, text=True).stdout
    return float(probe.strip()) - 0.1

def above(take):
    """The first shot by the moments its take stamped: everything the mouse does at its own pace, and the residents'
    work sped up to fit. Two sections: the view flown and the colony's markings worked, then the road drawn and built."""
    m = TAKE["shot"]["marks"]
    work = max(1.0, (m["built"] - m["handed"]) / BUILDING_S)
    return [("above", [(0.3, m["cards"], 1.0)]),
            ("above-draft", [(m["cards"], m["handed"], 1.0), (m["handed"], m["built"], work),
                             (m["built"], min(m["end"], length(take)), 1.0)])]

def pattern(name, shot, take):
    """A pattern's shot, the camera still throughout: each drawing sped up until it is raised, then looked at in
    its own time."""
    pieces, at = [], 0.3
    for draft in shot["drafts"]:
        pieces += [(at, draft["raised"], DRAWING), (draft["raised"], min(draft["end"], length(take)), 1.0)]
        at = draft["end"]
    return [(f"overhead-{name}", pieces)]

def shots():
    first = "overhead/01-overhead.mp4"
    listed = [("above", first, above(first), 0.4, PLACE)]
    for name in PATTERNS:
        shot = json.load(open(os.path.join(build.T, f"overhead-{name}", "index.json")))["context"]["shot"]
        take = f"overhead-{name}/{shot['name']}.mp4"
        listed.append((name, take, pattern(name, shot, take), 0.4, PLACE))
    return listed

def main():
    build.fetch_fonts()
    build.gradients()
    print(f"cutting at {build.W}x{build.H}", flush=True)
    segs = []
    for shot in shots():
        print("render", shot[0], flush=True)
        segs.append(build.render_shot(*shot))
    with open(os.path.join(build.OUT, "segments.json"), "w") as f:
        json.dump({"width": build.W, "height": build.H, "segments": segs}, f)
    os.environ["PROMO_SHOTS"], os.environ["PROMO_NAME"] = build.OUT, "folkways-overhead"
    # A track of its own, not the promo film's; PROMO_MUSIC still picks another.
    os.environ.setdefault("PROMO_MUSIC", "minecraft/sounds/music/game/creative/dreiton.ogg")
    import assemble  # joins the segments just written and lays the music under them

if __name__ == "__main__":
    main()
