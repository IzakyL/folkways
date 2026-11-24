load("folkways_vanilla:materials.star", "LOGS", "MASONRY", "PLANKS", "ROOFING", "offer")

detail = "bits"
grain = 4
knobs = [
    count("width", 7, 11, default = 9, icon = "minecraft:stone_bricks"),
    count("height", 3, 5, default = 4, icon = "minecraft:stripped_spruce_log"),
    flag("railing", default = True, icon = "minecraft:spruce_fence"),
    items("floor", offer(["minecraft:stone_bricks"], MASONRY),
        default = ["minecraft:stone_bricks"], icon = "minecraft:stone_bricks"),
    items("pillar", offer(["minecraft:stripped_spruce_log"], LOGS),
        default = ["minecraft:stripped_spruce_log"], icon = "minecraft:stripped_spruce_log"),
    items("beam", offer(["minecraft:spruce_planks"], PLANKS, LOGS),
        default = ["minecraft:spruce_planks"], icon = "minecraft:spruce_planks"),
    items("tiles", offer(["minecraft:deepslate_tiles"], ROOFING),
        default = ["minecraft:deepslate_tiles"], icon = "minecraft:deepslate_tiles"),
    items("ridge", offer(["minecraft:polished_deepslate"], ROOFING),
        default = ["minecraft:polished_deepslate"], icon = "minecraft:polished_deepslate"),
]
lang = {
    "en_us": {
        "title": "Flying-eave pavilion",
        "width": "Platform width",
        "height": "Column height",
        "railing": "Railing",
        "floor": "Platform",
        "pillar": "Columns",
        "beam": "Timber",
        "tiles": "Roof tiles",
        "ridge": "Ridges",
    },
    "zh_cn": {
        "title": "飞檐亭",
        "width": "台基宽度",
        "height": "柱高",
        "railing": "栏杆",
        "floor": "台基",
        "pillar": "圆柱",
        "beam": "木构",
        "tiles": "屋瓦",
        "ridge": "脊饰",
    },
    "zh_hk": {
        "title": "飛簷亭",
        "width": "台基寬度",
        "height": "柱高",
        "railing": "欄杆",
        "floor": "台基",
        "pillar": "圓柱",
        "beam": "木構",
        "tiles": "屋瓦",
        "ridge": "脊飾",
    },
    "zh_tw": {
        "title": "飛簷亭",
        "width": "臺基寬度",
        "height": "柱高",
        "railing": "欄杆",
        "floor": "臺基",
        "pillar": "圓柱",
        "beam": "木構",
        "tiles": "屋瓦",
        "ridge": "脊飾",
    },
}

def timber(site, room, low, high):
    return part("timber", fractional_box(room, low, high), site.items("beam"))

def draw(site):
    n = site.count("width")
    h = site.count("height")
    room = site.zone.sized(width = n, depth = n, anchor = "center", parity = "low").sized(height = h + 6)
    out = [part("plinth", fractional_box(room, [0, 0, 0], [n, 0.65, n]), site.items("floor")),
        part("coping", fractional_box(room, [-0.15, 0.65, -0.15], [n + 0.15, 1, n + 0.15]), site.items("floor"))]
    for step in range(3):
        out.append(part("step", fractional_box(room, [n / 2.0 - 1.5, 0, n + step * 0.5],
            [n / 2.0 + 1.5, 0.75 - step * 0.25, n + (step + 1) * 0.5]), site.items("floor")))
    for x in [1, n - 2]:
        for z in [1, n - 2]:
            post = room.at(x = x, y = 1, z = z).sized(width = 1, height = h, depth = 1)
            out.append(part("column", cylinder(post), site.items("pillar")))
            out.append(part("shoe", fractional_box(room, [x - 0.1, 1, z - 0.1],
                [x + 1.1, 1.3, z + 1.1]), site.items("floor")))
    for side in [1.25, n - 1.75]:
        out.append(timber(site, room, [1, h + 0.75, side], [n - 1, h + 1.65, side + 0.5]))
        out.append(timber(site, room, [side, h + 0.75, 1], [side + 0.5, h + 1.65, n - 1]))
        if site.flag("railing"):
            for y in [1.4, 2.2]:
                out.append(timber(site, room, [1.5, y, side + 0.1], [n - 1.5, y + 0.25, side + 0.4]))
                out.append(timber(site, room, [side + 0.1, y, 1.5], [side + 0.4, y + 0.25, n - 1.5]))
            for x in range(2, n - 2):
                out.append(timber(site, room, [x + 0.4, 1.5, side + 0.16], [x + 0.65, 2.2, side + 0.41]))
                out.append(timber(site, room, [side + 0.16, 1.5, x + 0.4], [side + 0.41, 2.2, x + 0.65]))
    out.append(clear("entry", fractional_box(room, [n / 2.0 - 1.25, 1, n - 2],
        [n / 2.0 + 1.25, 2.6, n]), over = ["timber"]))
    # Quarter-circle cuts keep every bracket attached to its column and lintel.
    for turn in range(4):
        face = room.rotate(turn)
        for end in [False, True]:
            bracket = face.at(x = 1, y = h - 1, z = 1).sized(width = 2, height = 2, depth = 1)
            if end:
                bracket = face.at(x = n - 3, y = h - 1, z = 1).sized(width = 2, height = 2, depth = 1).mirror()
            hollow = cylinder(bracket.at(y = -1).sized(width = 4, height = 2), axis = "z")
            out.append(part("bracket", subtract(fractional_box(bracket, [0, 0, 0.3], [2, 1.75, 0.7]), hollow),
                site.items("beam")))
    roof = room.at(x = -1, y = h + 1, z = -1).sized(width = n + 2, depth = n + 2, height = 4)
    out.append(part("soffit", curved_hip(roof, rise = 2.6, upturn = 1.15, thickness = 0.18), site.items("beam")))
    out.append(part("roof", subtract(curved_hip(roof, rise = 2.6, upturn = 1.15, thickness = 0.43),
        curved_hip(roof, rise = 2.6, upturn = 1.15, thickness = 0.18)),
        site.items("tiles")))
    cap = room.at(x = n // 2, y = h + 4, z = n // 2).sized(width = 1, depth = 1, height = 1)
    out.append(part("finial", sphere(cap), site.items("ridge")))
    out.append(part("finial_tip", cone(cap.at(y = 1)), site.items("ridge")))
    return out
