load("folkways:materials.star", "FENCES", "MASONRY", "WALLS", "offer")

accepts = ["path"]
knobs = [
    count("width", 3, 15, default = 5, icon = "minecraft:stone_brick_slab"),
    items("arch", offer(["minecraft:stone_bricks"], MASONRY),
        default = ["minecraft:stone_bricks"], icon = "minecraft:stone_bricks"),
    items("deck", offer(["minecraft:smooth_stone"], MASONRY),
        default = ["minecraft:smooth_stone"], icon = "minecraft:smooth_stone"),
    items("parapet", offer(["minecraft:stone_brick_wall"], WALLS, FENCES),
        default = ["minecraft:stone_brick_wall"], icon = "minecraft:stone_brick_wall"),
    items("abutment", offer(["minecraft:chiseled_stone_bricks"], MASONRY),
        default = ["minecraft:chiseled_stone_bricks"], icon = "minecraft:chiseled_stone_bricks"),
    items("accent", offer(["minecraft:polished_andesite"], MASONRY),
        default = ["minecraft:polished_andesite"], icon = "minecraft:polished_andesite"),
]
lang = {
    "en_us": {
        "title": "Arch Bridge",
        "width": "Width",
        "arch": "Arch",
        "deck": "Deck",
        "parapet": "Parapet",
        "abutment": "Abutments",
        "accent": "Accent",
    },
    "zh_cn": {
        "title": "拱桥",
        "width": "宽度",
        "arch": "拱",
        "deck": "桥面",
        "parapet": "护栏",
        "abutment": "桥台",
        "accent": "点缀",
    },
    "zh_hk": {
        "title": "拱橋",
        "width": "寬度",
        "arch": "拱",
        "deck": "橋面",
        "parapet": "護欄",
        "abutment": "橋台",
        "accent": "點綴",
    },
    "zh_tw": {
        "title": "拱橋",
        "width": "寬度",
        "arch": "拱",
        "deck": "橋面",
        "parapet": "護欄",
        "abutment": "橋臺",
        "accent": "點綴",
    },
}

def approach(site, band, z, y, direction):
    for distance in range(2, 13):
        outer = z + direction * distance
        row = band.at(z = outer).sized(depth = 1)
        heights = []
        for x in range(-1, band.width + 1):
            spot = row.at(x = x).sized(width = 1).center()
            ground = site.ground(spot.x, spot.z)
            water = site.highest(spot.x, spot.z, "fluid")
            if ground == None or (water != None and water > ground):
                break
            if ground > y or (y - ground) * 2 > distance:
                break
            heights.append(ground)
        if len(heights) != band.width + 2:
            continue
        deck = []
        trim = []
        rails = []
        for x in range(band.width):
            ground = heights[x + 1]
            base = ground if direction < 0 else y
            end = y - ground if direction < 0 else ground - y
            start = z - distance if direction < 0 else z
            lane = band.at(x = x, z = start, y = base).sized(width = 1, depth = distance + 1)
            deck.append(arched(lane, lane, 0, end = end))
            if x == 0 or x == band.width - 1:
                trim.append(arched(lane, lane, 0, end = end))
                rails.append(arched(lane.at(y = 1), lane, 0, end = end))
        apron = [row.at(x = x, y = heights[x + 1]).sized(width = 1) for x in [-1, band.width]]
        return {"deck": group(deck + apron), "trim": trim + apron, "rails": rails,
                "floor": min(heights)}
    reject("folkways.draft.error.bridge.landing")

def height_at(z, span, rise, dy):
    t = float(z) / span
    return dy * t + 4 * rise * t * (1 - t)

def ceiling(value):
    return int(-(-value // 1))

def shores(site, band, span, rise, dy, floor):
    low = []
    beds = {}
    for z in range(span + 1):
        spot = band.at(z = z).sized(depth = 1).center()
        bed = site.ground(spot.x, spot.z)
        water = site.highest(spot.x, spot.z, "fluid")
        if water != None and (bed == None or water > bed):
            bed = water
        beds[z] = bed
        if bed == None or bed <= height_at(z, span, rise, dy) - 3:
            low.append(z)
    if len(low) == 0 or min(span - 2, max(low)) - max(2, min(low)) < 2:
        return 2, span - 2, floor + 1
    first = max(2, min(low))
    last = min(span - 2, max(low))
    edges = [beds[first], beds[last]]
    edges = [bed for bed in edges if bed != None]
    return first, last, (max(edges) + 1 if edges else floor + 1)

def clears(first, depth, spring, rise, ring, span, arch_rise, dy):
    half = depth / 2.0
    radius = (half * half + rise * rise) / (2.0 * rise)
    centre = spring + rise - radius
    for k in range(depth):
        offset = k + 0.5 - half
        room = height_at(first + k, span, arch_rise, dy) - ring - centre + 0.001
        if room < 0 or radius * radius - offset * offset > room * room:
            return False
    return True

def draw(site):
    if len(site.path.points) != 2:
        reject("folkways.draft.error.bridge.points")
    length = site.path.length
    if length < 6 or length > 96:
        reject("folkways.draft.error.bridge.span")
    width = site.count("width")
    if width < 3 or width > 15:
        reject("folkways.draft.error.bridge.width")
    dy = site.path.points[1].y - site.path.points[0].y
    if max(dy, -dy) > length / 4:
        reject("folkways.draft.error.bridge.steep")
    for key in ["arch", "deck", "parapet", "abutment", "accent"]:
        if not site.items(key):
            reject("folkways.draft.error.material", translated("folkways.draft.knob." + key))
    run = site.path.stretches[0]
    band = run.sized(width = width, anchor = "center", parity = "low")
    span = band.depth - 1
    rise = max(1, int(length / 12))
    shoulder = max(2, min(4, int(length / 10) + 1))
    deck = arched(band, band, rise, end = dy)
    floor = min(0, dy) - shoulder
    first, last, spring = shores(site, band, span, rise, dy, floor)
    depth = last - first + 1
    ring = 1 if depth < 14 else 2
    lift = max(1, depth // 2)
    for _ in range(depth):
        if lift == 1 or clears(first, depth, spring, lift, ring, span, rise, dy):
            break
        lift -= 1
    if lift == depth // 2:
        for _ in range(16):
            if not clears(first, depth, spring + 1, lift, ring, span, rise, dy):
                break
            spring += 1
    floor = min(floor, spring - 1)
    room = band.at(z = first, y = floor).sized(depth = depth, height = spring - floor + lift)
    opening = vault(room, lift)
    arch = subtract(under(deck, floor), opening)
    voussoirs = subtract(vault(room, lift, grow = ring), opening)
    key = 1 if depth % 2 == 1 else 2
    keystone = intersect(voussoirs, band.at(z = first + (depth - key) // 2, y = spring)
        .sized(depth = key, height = lift + ring + 1))
    cornice = [arched(band.at(x = x).sized(width = 1), band, rise, end = dy) for x in [-1, width]]
    ends = group([
        band.sized(depth = 2).at(y = -4).sized(height = 4),
        band.sized(depth = 2).at(z = span - 1, y = dy - 4).sized(height = 4),
    ])
    near = approach(site, band, 0, 0, -1)
    far = approach(site, band, span, dy, 1)
    foundations = group([under(near["deck"], near["floor"]), under(far["deck"], far["floor"])])
    trim = near["trim"] + far["trim"]
    for x in [0, width - 1]:
        trim.append(arched(band.at(x = x).sized(width = 1), band, rise, end = dy))
    parts = [
        part("abutment", group([foundations, ends]), site.items("abutment"), fit = "layered"),
        part("arch", arch, site.items("arch"), fit = "layered"),
        part("ring", voussoirs, site.items("accent"), fit = "layered"),
        part("keystone", keystone, site.items("abutment"), fit = "layered"),
        part("deck", group([deck, near["deck"], far["deck"]]), site.items("deck"), fit = "layered"),
        part("edge", group(trim), site.items("arch"), fit = "layered", labels = ["deck"]),
        part("cornice", group(cornice), site.items("accent"), fit = "layered"),
    ]
    cuts = [0, span]
    for z in range(span):
        before = ceiling(height_at(z, span, rise, dy))
        after = ceiling(height_at(z + 1, span, rise, dy))
        if before != after:
            cut = z if after > before else z + 1
            if cut not in cuts:
                cuts.append(cut)
    cuts = sorted(cuts)
    breaks = [0]
    for i in range(len(cuts) - 1):
        gap = cuts[i + 1] - cuts[i]
        count = max(1, int((gap + 5) / 6))
        for j in range(1, count + 1):
            breaks.append(cuts[i] + int(float(j) * gap / count + 0.5))
    bays = len(breaks) - 1
    levels = [ceiling(max([height_at(z, span, rise, dy)
        for z in range(breaks[i], breaks[i + 1] + 1)])) for i in range(bays)]
    for side in range(2):
        x = 0 if side == 0 else width - 1
        rails = [near["rails"][side], far["rails"][side]]
        for i in range(bays):
            rails.append(band.at(x = x, z = breaks[i], y = levels[i] + 1)
                .sized(width = 1, depth = breaks[i + 1] - breaks[i] + 1))
        parts.append(part("parapet", group(rails), site.items("parapet"), fit = "connected", support = "deck"))
    return parts
