load("folkways_vanilla:materials.star", "MASONRY", "offer")

detail = "bits"
accepts = ["path", "zone"]
knobs = [
    count("span", 2, 3, default = 2),
    count("width", 3, 5, default = 3),
    count("camber", 1, 2, default = 1),
    items("stone", MASONRY, default = ["minecraft:stone_bricks"]),
    items("accent", offer(["minecraft:smooth_sandstone"], MASONRY),
        default = ["minecraft:smooth_sandstone"]),
    items("paving", MASONRY, default = ["minecraft:polished_andesite"]),
]
lang = {
    "en_us": {
        "title": "Stone arch bridge",
        "span": "Arch radius",
        "width": "Deck width",
        "camber": "Deck camber",
        "stone": "Masonry",
        "accent": "Voussoirs and mouldings",
        "paving": "Paving",
    },
    "zh_cn": {
        "title": "石拱桥",
        "span": "拱半径",
        "width": "桥面宽度",
        "camber": "桥面起拱",
        "stone": "桥身石材",
        "accent": "券石与线脚",
        "paving": "桥面铺石",
    },
    "zh_hk": {
        "title": "石拱橋",
        "span": "拱半徑",
        "width": "橋面寬度",
        "camber": "橋面起拱",
        "stone": "橋身石材",
        "accent": "券石與線腳",
        "paving": "橋面鋪石",
    },
    "zh_tw": {
        "title": "石拱橋",
        "span": "拱半徑",
        "width": "橋面寬度",
        "camber": "橋面起拱",
        "stone": "橋身石材",
        "accent": "券石與線腳",
        "paving": "橋面鋪石",
    },
}

def slab(room, low, high):
    return fractional_box(room, low, high)

def bridge(site, run):
    w = site.count("width")
    span = 2 * site.count("span")
    bays = max(1, min(6, (run.depth - 2) // (span + 2)))
    length = max(span + 4, min(44, run.depth))
    pitch = (length - 2.0) / bays
    radius = span // 2
    spring = 1
    deck = spring + radius + 1
    rise = site.count("camber")
    room = run.sized(width = w, depth = length, height = deck + rise + 3)
    stone = site.items("stone")
    accent = site.items("accent")
    out = []
    openings = []
    rings = []
    for i in range(bays):
        center = 1 + pitch * (i + 0.5)
        # Vault scopes remain integral; redistribute spare length into end abutments.
        z = int(center - radius)
        arch = room.at(z = z).sized(height = spring + radius, depth = span)
        openings.append(vault(arch, radius))
        rings.append(subtract(vault(arch, radius, grow = 1), vault(arch, radius)))
    cut = group(openings)
    body = intersect(arched(slab(room, [0, -rise, 0], [w, deck, length]), room, rise),
        slab(room, [0, 0, 0], [w, deck + rise, length]))
    out.append(part("spandrels", subtract(subtract(body, cut), group(rings)), stone))
    out.append(part("voussoirs", intersect(group(rings), body), accent))
    for i in range(1, bays):
        z = int(1 + pitch * i)
        # Pointed water noses on both sides of each solid river pier.
        for x, tip in [[0, -1], [w, w + 1]]:
            nose = extrude(room, [[x, z - 1], [tip, z], [x, z + 1]], height = 2)
            out.append(part("cutwater", nose, accent))
    for low, high, material, name in [
        ([-0.16, deck - 0.17, 0], [w + 0.16, deck + 0.08, length], accent, "cornice"),
        ([0, deck + 0.08, 0], [w, deck + 0.33, length], site.items("paving"), "deck"),
    ]:
        out.append(part(name, arched(slab(room, low, high), room, rise), material))
    for x in [0.02, w - 0.24]:
        for y, thick in [[deck + 0.375, 0.25], [deck + 1.125, 0.25]]:
            rail = slab(room, [x - 0.015, y, 0], [x + 0.235, y + thick, length])
            out.append(part("rail", arched(rail, room, rise), accent))
        for z in list(range(0, length - 1, 2)) + [length - 1]:
            thick = 0.375 if z % 4 == 0 or z == length - 1 else 0.25
            post = slab(room, [x + 0.11 - thick / 2, deck + 0.33, z + 0.4 - thick / 2],
                [x + 0.11 + thick / 2, deck + 1.375 if thick > 0.3 else deck + 1.125, z + 0.4 + thick / 2])
            out.append(part("baluster", arched(post, room, rise), accent))
            if thick > 0.3:
                cap = slab(room, [x - 0.14, deck + 1.375, z + 0.15],
                    [x + 0.36, deck + 1.625, z + 0.65])
                out.append(part("newel_cap", arched(cap, room, rise), accent))
    return out

def draw(site):
    runs = site.path.stretches if site.kind == "path" else [site.zone]
    out = []
    for run in runs:
        if site.kind == "path":
            run = run.sized(width = site.count("width"), anchor = "center", parity = "low")
        out.extend(bridge(site, run))
    return out
