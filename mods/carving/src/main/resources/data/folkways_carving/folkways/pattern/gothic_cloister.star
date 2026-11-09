load("folkways_vanilla:materials.star", "MASONRY", "ROOFING", "offer")

detail = "bits"
grain = 8
accepts = ["zone", "path"]
knobs = [
    count("bays", 1, 5, default = 3),
    count("bay", 5, 8, default = 6),
    count("width", 5, 7, default = 6),
    count("height", 4, 6, default = 4),
    items("stone", offer(["minecraft:smooth_sandstone"], MASONRY), default = ["minecraft:smooth_sandstone"]),
    items("trim", offer(["minecraft:smooth_sandstone"], MASONRY), default = ["minecraft:smooth_sandstone"]),
    items("roof", ROOFING, default = ["minecraft:deepslate_tiles"]),
]
lang = {
    "en_us": {
        "title": "Gothic cloister",
        "bays": "Bays in a zone",
        "bay": "Bay length",
        "width": "Walk width",
        "height": "Springing height",
        "stone": "Limestone",
        "trim": "Mouldings and ribs",
        "roof": "Roof",
    },
    "zh_cn": {
        "title": "尖拱回廊",
        "bays": "区域开间数",
        "bay": "开间长度",
        "width": "廊宽",
        "height": "起拱高度",
        "stone": "石灰岩",
        "trim": "线脚与拱肋",
        "roof": "屋面",
    },
    "zh_hk": {
        "title": "尖拱迴廊",
        "bays": "區域開間數",
        "bay": "開間長度",
        "width": "廊寬",
        "height": "起拱高度",
        "stone": "石灰岩",
        "trim": "線腳與拱肋",
        "roof": "屋面",
    },
    "zh_tw": {
        "title": "尖拱迴廊",
        "bays": "區域開間數",
        "bay": "開間長度",
        "width": "廊寬",
        "height": "起拱高度",
        "stone": "石灰岩",
        "trim": "線腳與拱肋",
        "roof": "屋面",
    },
}

def root(value):
    guess = max(1.0, value)
    for _ in range(12):
        guess = (guess + value / guess) / 2.0
    return guess

def profile(span, rise, t):
    half = span / 2.0
    radius = (half * half + rise * rise) / (2.0 * half)
    distance = span * t - half
    distance = (distance if distance >= 0 else -distance) + radius - half
    return root(max(0.000001, radius * radius - distance * distance))

def rib(room, a, b, rise, radius):
    points = []
    span = root((b[0] - a[0]) * (b[0] - a[0]) + (b[2] - a[2]) * (b[2] - a[2]))
    for i in range(33):
        t = i / 32.0
        points.append([a[0] + (b[0] - a[0]) * t, a[1] + profile(span, rise, t),
            a[2] + (b[2] - a[2]) * t])
    return tube(room, points, radius)

def bay(site, room, d):
    w = room.width
    h = site.count("height")
    stone = site.items("stone")
    trim = site.items("trim")
    out = [part("plinth", fractional_box(room, [0, -1, 0], [w, 0, d]), stone)]
    # A half-block core with four quarter-block satellites, separated by readable grooves.
    for x in [0.5, w - 0.5]:
        for z in [0.5, d - 0.5]:
            out.append(part("main-shaft", tube(room, [[x, 0.375, z], [x, h - 0.25, z]], 0.3125), stone))
            for dx, dz in [[-0.375, 0], [0.375, 0], [0, -0.375], [0, 0.375]]:
                out.append(part("attached-shaft", tube(room,
                    [[x + dx, 0.375, z + dz], [x + dx, h - 0.25, z + dz]], 0.15625), trim))
            for y, height, radius in [[0, 0.25, 0.625], [0.25, 0.125, 0.5],
                [h - 0.375, 0.125, 0.4375], [h - 0.25, 0.25, 0.625]]:
                out.append(part("base-capital", fractional_box(room,
                    [x - radius, y, z - radius], [x + radius, y + height, z + radius]), trim))
    # Open on both sides and at both ends: only moulded pointed ribs, no solid spandrels or gables.
    rise = max(w - 1, site.count("bay") - 1) * 0.75
    for x in [0.5, w - 0.5]:
        out.append(part("arcade", rib(room, [x, h, 0.5], [x, h, d - 0.5], rise, 0.25), trim))
    for z in [0.5, d - 0.5]:
        out.append(part("transverse-arch", rib(room,
            [0.5, h, z], [w - 0.5, h, z], rise, 0.25), trim))
    eave = int(h + max(w - 1, site.count("bay") - 1) * 0.75 + 0.5) + 0.25
    for x in [0.5, w - 0.5]:
        out.append(part("eave-beam", tube(room, [[x, eave, 0], [x, eave, d]], 0.25), trim))
    diagonal = root((w - 1) * (w - 1) + (d - 1) * (d - 1))
    for reverse in [False, True]:
        out.append(part("diagonal-rib", rib(room, [0.5, h, d - 0.5 if reverse else 0.5],
            [w - 0.5, h, 0.5 if reverse else d - 0.5], max(rise, diagonal / 2.0), 0.15625), trim))
    return out

def roof_strip(room, depth, step):
    skin = intersect(pitched_roof(room, 2, thickness = 0.25),
        fractional_box(room, [0, 0, 0], [room.width, 4, depth]))
    # Narrow bargeboards close the one-block rises between level bays.
    drop = max(1, step if step > 0 else -step)
    skirt = pitched_roof(room.at(y = -drop), 2, thickness = drop + 0.25)
    ends = group([fractional_box(room, [0, -drop, z], [room.width, 4, z + 0.25])
        for z in [0, depth - 0.25]])
    return group([skin, intersect(skirt, ends)])

def roof(site, room, depth, step):
    # Low eaves and a light open gable keep the end portal legible.
    level = site.count("height") + max(room.width - 1, site.count("bay") - 1) * 0.75 + 0.5
    shape = room.at(y = int(level)).sized(height = 1)
    roofs = [roof_strip(shape, depth, step)]
    if room.name == "junction":
        cross = room.rotate(1).at(y = int(level)).sized(height = 1)
        roofs.append(roof_strip(cross, room.width, step))
    return roofs

def draw(site):
    out = []
    if site.kind == "path":
        bays = site.path.bays(site.count("bay") - 1, site.count("width"))
        rooms = [(one.room, one.length + 1, one.rise) for one in bays]
    else:
        room = site.zone.sized(width = site.count("width"), depth = site.count("bay"), height = 1)
        rooms = [(room.at(z = i * (site.count("bay") - 1)), room.depth, 0)
            for i in range(site.count("bays"))]
    roofs = []
    for room, depth, step in rooms:
        roofs.extend(roof(site, room, depth, step))
    # Keep the upper envelope at crossings: lower roof skins cannot penetrate the adjoining roof.
    below = group([under(shape = one, floor = site.zone.at(y = -1)) for one in roofs])
    for room, depth, step in rooms:
        out.extend(bay(site, room, depth))
    out.append(part("roof", subtract(group(roofs), below), site.items("roof")))
    return out
