"""Cut the Folkways promo film from the promo takes. Every on-screen word lives in promo-text.toml."""
import json, os, re, subprocess, sys, tomllib, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
T = os.path.join(REPO, "tests", "out", "promo", "takes")     # what npm run promo:take films
FILM = os.path.join(REPO, "tests", "out", "promo", "film")   # the finished films and description notes
WORK = os.path.join(FILM, "work")                             # cut shots, filter graphs, gradients
OUT = os.path.join(WORK, "shots")
TXT = os.path.join(WORK, "txt")
FONTS = os.path.join(FILM, "fonts")
os.makedirs(OUT, exist_ok=True)
os.makedirs(TXT, exist_ok=True)
os.makedirs(FONTS, exist_ok=True)

WEIGHTS = ("Black", "Bold", "Medium", "Regular", "Light")
FONT = {w: os.path.join(FONTS, f"NotoSansCJKsc-{w}.otf") for w in WEIGHTS}
FONT_URL = "https://github.com/notofonts/noto-cjk/raw/main/Sans/OTF/SimplifiedChinese/NotoSansCJKsc-{}.otf"

def fetch_fonts():
    """Noto Sans CJK SC in fixed weights, downloaded once into the out folder rather than kept in the repo."""
    for weight, path in FONT.items():
        if not os.path.exists(path):
            print("downloading", os.path.basename(path), flush=True)
            urllib.request.urlretrieve(FONT_URL.format(weight), path + ".part")
            os.replace(path + ".part", path)
GOLD, CREAM = "0xE8B04A", "0xF2E6C8"
TEXT = tomllib.load(open(os.environ.get("PROMO_TEXT", os.path.join(HERE, "promo-text.toml")), "rb"))

def _take_height():
    """The tallest take's height: the film is cut at that size, and every take is scaled to it."""
    tallest = 1080
    for root, _, files in os.walk(T):
        for name in files:
            if name.endswith(".mp4"):
                probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                                        "stream=height", "-of", "csv=p=0", os.path.join(root, name)],
                                       capture_output=True, text=True).stdout.strip()
                tallest = max(tallest, int(probe or 0))
    return tallest

# Layout is written for 1920x1080 and scaled by K to the size the takes were filmed at.
H = int(os.environ.get("PROMO_HEIGHT", 0)) or _take_height()
W = H * 16 // 9
K = H / 1080

def px(v):
    return int(round(v * K))

# One unbroken slice of one take per shot. A slice is a run of (start, end, speed) pieces of its take, played back
# to back, so a wait in the middle is fast-forwarded rather than cut out; a gap between two pieces is a cut, for a
# wait with nothing on screen to watch. A shot may carry several sections of
# promo-text.toml one after another, each over its own pieces.
# place is where the words sit, picked from what is empty in that shot: tl/tr/tc top, bl/br/bc bottom.
# (file name, take, [(section, pieces), ...], game-audio gain, place)
def declared(shot, lead=0.5, ui=1.0, work=2.0):
    """A declaration shot cut by the moments its take stamped: the player's gesture at near speed, the colony's work
    sped up, and whatever follows it (the finished result, the chest opened on it) at speed again."""
    marks = json.load(open(os.path.join(T, "declare", "index.json")))["context"]["shots"][shot]["marks"]
    said, done, end = marks["declared"], marks["done"], marks["end"]
    return [(lead, said, ui), (said, done, work), (done, end, 1.0)]

def household(lead=0.5, work=2.0):
    """The workforce page browsed at its own pace, then the homestead at work, sped up until everything it shows has
    happened, and at its own pace again after: two sections, so the words stay off the panel."""
    marks = json.load(open(os.path.join(T, "household", "index.json")))["context"]["shot"]["marks"]
    closed, done, end = marks["closed"], marks["done"], marks["end"]
    return [("workforce", [(lead, closed, 1.0)]), ("household", [(closed, done, work), (done, end, 1.0)])]

SHOTS = [
    ("card:disclaimer",),
    ("household", "household/01-household.mp4", household(), 0.4, "bl"),          # grass left of the beds
    ("workshop", "declare/01-workshop.mp4", [("workshop", declared("workshop"))], 0.4, "tl"),
    ("field", "declare/02-field.mp4", [("field", declared("field"))], 0.4, "tl"),
    ("woodlot", "declare/03-woodlot.mp4", [("woodlot", declared("woodlot"))], 0.4, "tl"),
    ("tour", "tour/01-colony.mp4", [("tour", [(0.0, 15.8, 1.0)])], 0.6, "bl"),     # grass along the near edge
    ("patterns", "patterns/01-patterns.mp4", [                                       # open sky over the meadow
        ("schematic", [(0.5, 15.8, 1.25)]),
        ("draft", [(15.8, 24.0, 1.25), (24.0, 37.0, 3.0), (37.0, 58.0, 1.25)]),
    ], 0.5, "tl"),
    ("logistics", "logistics/01-logistics.mp4", [("logistics", [(0.0, 36.0, 2.0)])], 0.0, "tr"),  # sky right of the chains
    ("rail", "rail/01-rail.mp4", [                                                   # grass outside the station fence
        # the wait on the platform sped up, the riders boarding at speed, then a cut from the last one seated to the
        # train pulling out
        ("rail", [(0.0, 7.5, 1.0), (7.5, 13.0, 4.0), (13.0, 15.5, 1.0), (20.0, 24.5, 1.0)]),
    ], 0.7, "bl"),
    ("models", "models/01-models.mp4", [("models", [(0.0, 45.0, 2.0)])], 0.0, "tc"),  # bare grass above the fence
    ("airship", "airship/01-airship.mp4", [("airship", [(2.0, 31.8, 1.3)])], 0.5, "tl"),  # fog left of the ship
    ("card:ending",),
]

_n = 0
def txtfile(s):
    global _n
    _n += 1
    p = os.path.join(TXT, f"t{_n:03d}.txt")
    with open(p, "w") as f:
        f.write(s)
    return p

def alpha(a, b, d=0.5):
    return (f"if(lt(t,{a}),0,if(lt(t,{a}+{d}),(t-{a})/{d},"
            f"if(lt(t,{b}-{d}),1,if(lt(t,{b}),({b}-t)/{d},0))))")

_measured = {}
def ink_right(weight, size, text):
    """How far right of where it is drawn a run of text leaves ink, measured by drawing it once."""
    key = (weight, size, text)
    if key not in _measured:
        log = subprocess.run(["ffmpeg", "-hide_banner", "-f", "lavfi", "-i", f"color=black:s={W * 2}x{px(200)}",
                              "-vf", f"drawtext=fontfile='{FONT[weight]}':textfile='{txtfile(text)}':fontsize={size}:"
                                     f"fontcolor=white:x=10:y=20,bbox=min_val=40",
                              "-frames:v", "1", "-f", "null", "-"], capture_output=True, text=True).stderr
        found = re.search(r"x2:(\d+)", log)
        _measured[key] = int(found.group(1)) - 10 if found else 0
    return _measured[key]

MARK = re.compile(r"\[\^(\w+)\]")

def runs(line):
    """A line split into its words and its footnote marks: [(text, mark or None), ...]."""
    out, at = [], 0
    for m in MARK.finditer(line):
        out.append((line[at:m.start()], m.group(1)))
        at = m.end()
    if at < len(line) or not out:
        out.append((line[at:], None))
    return out

def dt(weight, text, size, x, y, al, color="white", shadow=3):
    return (f"drawtext=fontfile='{FONT[weight]}':textfile='{txtfile(text)}':fontsize={size}:fontcolor={color}:"
            f"x={x}:y={y}:alpha='{al}':shadowcolor=black@0.6:shadowx={px(shadow)}:shadowy={px(shadow)}")

def text_block(weight, text, size, horiz, edge, y, al, color="white", shadow=3, mark_color=GOLD):
    """One drawtext per line (drawtext draws a stray glyph at line breaks) plus one per footnote mark, each line
    placed by its measured width: horiz 'l' starts it at edge, 'r' ends it at edge, 'c' centres it on edge."""
    out = []
    mark_size = int(size * 0.55)
    gap = max(2, size // 16)
    for i, line in enumerate(text.split("\n")):
        ly = y + i * (int(size * 1.18) + int(size * 0.45))
        # lay the line out run by run, each mark pushing what follows it to the right
        laid, cursor = [], 0
        for words, mark in runs(line):
            if words:
                laid.append((cursor, words, False))
                cursor += ink_right(weight, size, words)
            if mark:
                cursor += gap
                laid.append((cursor, mark, True))
                cursor += ink_right(weight, mark_size, mark) + gap * 2
        width = cursor
        x0 = {"l": edge, "r": edge - width, "c": edge - width // 2}[horiz]
        for dx, words, is_mark in laid:
            if is_mark:
                out.append(dt(weight, words, mark_size, x0 + dx, ly - int(size * 0.08), al, color=mark_color,
                              shadow=max(1, shadow - 1)))
            else:
                out.append(dt(weight, words, size, x0 + dx, ly, al, color=color, shadow=shadow))
    return out

def block_height(text, size):
    n = text.count("\n") + 1
    return n * int(size * 1.18) + (n - 1) * int(size * 0.45)

TAG, MAIN, MARGIN_X, MARGIN_Y = 28, 48, 110, 96

def caption_filters(place, tag, text, a, b):
    """drawtext filters for one block of words at the given corner."""
    al = alpha(a, b)
    vert, horiz = place[0], place[1]
    main_h = block_height(text, px(MAIN))
    tag_h = px(TAG + 22) if tag else 0
    top = px(MARGIN_Y) if vert == "t" else H - px(MARGIN_Y) - main_h - tag_h
    edge = {"l": px(MARGIN_X), "r": W - px(MARGIN_X), "c": W // 2}[horiz]
    out = []
    if tag:
        out += text_block("Bold", tag, px(TAG), horiz, edge, top, al, color=GOLD, shadow=2)
    out += text_block("Bold", text, px(MAIN), horiz, edge, top + tag_h, al)
    return out

def atempo(speed):
    """atempo takes at most 2x a step, so faster pieces chain it."""
    steps = []
    while speed > 2.0:
        steps.append("atempo=2.0")
        speed /= 2.0
    if abs(speed - 1.0) > 1e-6:
        steps.append(f"atempo={speed:.6f}")
    return ",".join(steps) + "," if steps else ""

def render_shot(name, src, sections, gain, place):
    pieces = [piece for _, run_ in sections for piece in run_]
    out_len = sum((b - a) / sp for a, b, sp in pieces)
    out = os.path.join(OUT, name + ".mp4")
    first, last = pieces[0][0], pieces[-1][1]
    inputs = ["-ss", str(first), "-t", str(last - first), "-i", os.path.join(T, src)]
    chain, vs, as_ = [], [], []
    for i, (a, b, sp) in enumerate(pieces):
        a, b = a - first, b - first
        chain.append(f"[0:v]trim={a}:{b},setpts=(PTS-STARTPTS)/{sp},fps=60,"
                     f"scale={W}:{H}:flags=lanczos,format=yuv420p[p{i}]")
        chain.append(f"[0:a]atrim={a}:{b},asetpts=PTS-STARTPTS,{atempo(sp)}"
                     f"volume={gain if sp <= 2 else 0},aresample=48000,aformat=channel_layouts=stereo[q{i}]")
        vs.append(f"[p{i}]")
        as_.append(f"[q{i}]")
    chain.append("".join(vs) + f"concat=n={len(pieces)}:v=1:a=0[v0]")
    chain.append("".join(as_) + f"concat=n={len(pieces)}:v=0:a=1[a]")
    cur = "v0"
    filters = []
    grad = gradient(place[0] == "t")
    pos = "0:0" if place[0] == "t" else "0:H-h"
    start = 0.0
    for k, (section, run_) in enumerate(sections):
        span = sum((b - a) / sp for a, b, sp in run_)
        scene = TEXT.get("scenes", {}).get(section, {})
        caps = scene.get("captions", [])
        if caps:
            # every line of the section at once, over its own stretch of the shot, on a gradient on its side
            lo, hi = round(start + 0.7, 2), round(start + span - 0.5, 2)
            inputs += ["-loop", "1", "-t", str(out_len), "-i", grad]
            n = inputs.count("-i") - 1
            chain.append(f"[{n}:v]format=rgba,fade=t=in:st={lo}:d=0.5:alpha=1,"
                         f"fade=t=out:st={hi - 0.5}:d=0.5:alpha=1[g{k}]")
            chain.append(f"[{cur}][g{k}]overlay={pos}:shortest=1[vg{k}]")
            cur = f"vg{k}"
            filters += caption_filters(place, scene.get("tag", ""), "\n".join(caps), lo, hi)
        start += span
    if filters:
        chain.append(f"[{cur}]" + ",".join(filters) + "[vt]")
        cur = "vt"
    run(inputs, chain, cur, out_len, out)
    return out, out_len

CARD_STYLE = {  # weight, size, colour
    "big": ("Black", 150, "white"),
    "label": ("Bold", 32, GOLD),
    "body": ("Regular", 40, "white"),
    "url": ("Bold", 56, "white"),
    "note": ("Light", 32, "0xBBBBBB"),
}

def render_card(key):
    card = TEXT.get("cards", {}).get(key)
    if not card:
        return None
    length = float(card["seconds"])
    out = os.path.join(OUT, f"card-{key}.mp4")
    al = alpha(0.6, length - 0.6, 0.8)
    blocks = []
    for line in card["lines"]:
        style, text = line["style"], line.get("text", "")
        if style == "gap" or not text:
            blocks.append((None, px(36)))
            continue
        weight, size, color = CARD_STYLE[style]
        blocks.append(((weight, px(size), color, text), block_height(text, px(size)) + px(28)))
    # centred on the screen, unless the card names where its top-left corner sits (in 1920x1080 pixels)
    if "left" in card or "top" in card:
        horiz, edge, y = "l", px(card.get("left", MARGIN_X)), px(card.get("top", MARGIN_Y))
    else:
        horiz, edge, y = "c", W // 2, (H - sum(h for _, h in blocks)) // 2
    filters = []
    for spec, h in blocks:
        if spec:
            weight, size, color, text = spec
            filters += text_block(weight, text, size, horiz, edge, y, al, color=color, shadow=0)
        y += h
    inputs = ["-f", "lavfi", "-i", f"color=0x0b0b0c:s={W}x{H}:r=60:d={length}",
              "-f", "lavfi", "-t", str(length), "-i", "anullsrc=r=48000:cl=stereo"]
    run(inputs, ["[0:v]" + ",".join(filters) + "[vt]", "[1:a]anull[a]"], "vt", length, out)
    return out, length

def run(inputs, chain, vout, length, out):
    fc = os.path.join(TXT, os.path.basename(out) + ".fc")
    with open(fc, "w") as f:
        f.write(";\n".join(chain))
    subprocess.run(["ffmpeg", "-v", "error", "-y", *inputs, "-/filter_complex", fc,
                    "-map", f"[{vout}]", "-map", "[a]", "-t", f"{length:.3f}",
                    "-c:v", "libx264", "-preset", "fast", "-crf", "14", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-b:a", "192k", out], check=True)

def gradient(top):
    return os.path.join(WORK, f"grad-{'top' if top else 'bottom'}-{H}.png")

def gradients():
    for top, expr in ((False, "225*pow(Y/H,1.3)"), (True, "215*pow(1-Y/H,1.3)")):
        p = gradient(top)
        if not os.path.exists(p):
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"color=black:s={W}x{px(420)},format=rgba",
                            "-vf", f"geq=r=0:g=0:b=0:a='{expr}'", "-frames:v", "1", p], check=True)

def notes():
    """The footnotes for the video's description, in the order their marks first appear on screen."""
    order = []
    texts = []
    for shot in SHOTS:
        if shot[0].startswith("card:"):
            card = TEXT.get("cards", {}).get(shot[0][5:]) or {}
            texts += [line.get("text", "") for line in card.get("lines", [])]
        else:
            for section, _ in shot[2]:
                scene = TEXT.get("scenes", {}).get(section, {})
                texts += [scene.get("tag", "")] + scene.get("captions", [])
    for text in texts:
        for mark in MARK.findall(text):
            if mark not in order:
                order.append(mark)
    written = {str(k): v for k, v in TEXT.get("notes", {}).items()}
    lines = [f"[{mark}] {written.get(mark, '（未填写）')}" for mark in order]
    with open(os.path.join(FILM, "description-notes.txt"), "w") as f:
        f.write("\n".join(lines) + ("\n" if lines else ""))
    missing = [m for m in order if m not in written]
    if missing:
        print("notes without text in [notes]:", ", ".join(missing), flush=True)

def main():
    only = set(sys.argv[1:])
    fetch_fonts()
    gradients()
    print(f"cutting at {W}x{H}", flush=True)
    segs = []
    for shot in SHOTS:
        name = shot[0]
        if only and name not in only:
            continue
        print("render", name, flush=True)
        seg = render_card(name[5:]) if name.startswith("card:") else render_shot(*shot)
        if seg:
            segs.append(seg)
    if not only:
        with open(os.path.join(OUT, "segments.json"), "w") as f:
            json.dump({"width": W, "height": H, "segments": segs}, f)
    notes()

if __name__ == "__main__":
    main()
