"""Cut the what's-new short from the changes take: UI and pickers, maid models, .nbt blueprints, groundwork, sweep,
and packing by hand: the parcel sent by frogport and used at the far end. The postbox-and-train shot is left out of the
cut (posted() still cuts it, should it come back). Its words live in promo-changes.toml."""
import json, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
os.environ["PROMO_TEXT"] = os.path.join(HERE, "promo-changes.toml")
import build

build.OUT = os.path.join(build.WORK, "changes-shots")
os.makedirs(build.OUT, exist_ok=True)
TAKE = os.path.join(build.T, "changes")
INDEX = json.load(open(os.path.join(TAKE, "index.json")))["context"]
# The groundwork is shot on real ground, in a take of its own (the second test in changes.take.ts).
GROUND = os.path.join(build.T, "changes-ground")
INDEX.update(json.load(open(os.path.join(GROUND, "index.json")))["context"])


def length(name, take=TAKE):
    """How long a take's video runs: a shot is never cut past it, whatever its marks say."""
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=duration",
                            "-of", "csv=p=0", os.path.join(take, name)], capture_output=True, text=True).stdout
    return float(probe.strip()) - 0.1


def marks(key):
    shot = INDEX.get(key) or {}
    return shot.get("marks") or {}


def whole(key, name, speed=1.0):
    end = marks(key).get("end") or length(name)
    return [(0.0, min(end, length(name)), speed)]


def picker(name):
    """The UI shot ends on the picker's rule, not on the ground the panel leaves behind."""
    m = marks("ui")
    end = (m.get("rule") or m.get("end") or length(name)) + 1.2
    return [(0.0, min(end, length(name)), 1.0)]


def packed(key, name):
    """The short walk to the packager a little quickened, the packing and the parcel at their own pace."""
    m = marks(key)
    packing = m.get("packing") or m.get("posted") or m.get("end") or length(name)
    end = m.get("end") or length(name)
    return [(0.0, min(packing + 1.0, end), 1.5), (min(packing + 1.0, end), min(end, length(name)), 1.0)]


def worked(key, name, work_s=12.0):
    """The book worked at its own pace, then the crew's work quickened to a dozen seconds, and the levelled ground
    looked at in its own time."""
    m = marks(key)
    end = min(m.get("end") or length(name, GROUND), length(name, GROUND))
    drawn = m.get("drawn")
    done = max([m.get(k) or 0 for k in ("cleared", "filled", "weeded", "felled", "hauled")] + [0])
    if not drawn or done <= drawn:
        return [(0.0, end, 1.0)]
    return [(0.0, drawn, 1.0), (drawn, done, max(1.0, (done - drawn) / work_s)), (done, end, 1.0)]


def posted(key, name):
    """As packed, then the wait for the train quickened to a few seconds, and its pulling out at its own pace."""
    m = marks(key)
    pieces = packed(key, name)
    departed = m.get("departed")
    if not departed:
        return pieces
    after = pieces[-1][0]
    wait_from = max(after, min((m.get("posted") or after) + 2.0, departed))
    # The train is seen pulling in, taking the parcel and pulling out at its own pace.
    wait_to = max(wait_from, min(m.get("arrived") or departed, departed) - 1.5)
    end = min(m.get("end") or length(name), length(name))
    cut = pieces[:-1] + [(after, wait_from, 1.0),
                         (wait_from, wait_to, max(1.0, (wait_to - wait_from) / 4.0)),
                         (wait_to, end, 1.0)]
    return [piece for piece in cut if piece[1] - piece[0] > 0.05]


def landed(key, name, work_s=6.0):
    """The parcel landing at the far port at its own pace, the builder laying the kiln quickened to a few seconds,
    and the kiln standing whole looked at a moment."""
    m = marks(key)
    end = min(m.get("end") or length(name), length(name))
    start = max(0.0, (m.get("landed") or 0.0) - 1.0)
    first, built = m.get("first"), m.get("built")
    if not first or not built or built <= first:
        return [(start, end, 1.0)]
    lay_from = min(first + 1.5, built)
    cut = [(start, lay_from, 1.0), (lay_from, built, max(1.0, (built - lay_from) / work_s)), (built, end, 1.0)]
    return [piece for piece in cut if piece[1] - piece[0] > 0.05]


SHOTS = [
    ("ui", "changes/01-ui.mp4", [("ui", picker("01-ui.mp4"))], 0.4, "bl"),
    ("maids", "changes/02-maids.mp4", [("maids", whole("maids", "02-maids.mp4"))], 0.0, "bl"),
    ("blueprint", "changes/03-blueprint.mp4", [("blueprint", whole("blueprint", "03-blueprint.mp4"))], 0.5, "bl"),
    ("groundwork", "changes-ground/04-groundwork.mp4", [("groundwork", worked("groundwork", "04-groundwork.mp4"))], 0.4, "tl"),
    ("sweep", "changes/05-sweep.mp4", [("sweep", whole("sweep", "05-sweep.mp4"))], 0.4, "tr"),
    ("frogport", "changes/06-frogport.mp4", [("packing", packed("frogport", "06-frogport.mp4"))], 0.4, "tl"),
    ("far", "changes/06b-frogport-far.mp4", [("far", landed("frogport_far", "06b-frogport-far.mp4"))], 0.4, "tl"),
]
# Only this cut's own shots make up its notes, and it has no footnotes.
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
    os.environ["PROMO_SHOTS"], os.environ["PROMO_NAME"] = build.OUT, "folkways-changes"
    # A track of its own, not the promo film's nor the view-from-above short's; PROMO_MUSIC still picks another.
    os.environ.setdefault("PROMO_MUSIC", "minecraft/sounds/music/game/comforting_memories.ogg")
    import assemble  # joins the segments just written and lays the music under them


if __name__ == "__main__":
    main()
