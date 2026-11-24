load("folkways_vanilla:materials.star", "MASONRY", "PLANKS", "ROOFING", "offer")

detail = "bits"
accepts = ["zone"]
knobs = [
    count("width", 7, 11, default = 9, icon = "minecraft:quartz_block"),
    count("height", 4, 6, default = 5, icon = "minecraft:quartz_block"),
    items("frame", offer(["minecraft:red_concrete"], PLANKS),
        default = ["minecraft:red_concrete"], icon = "minecraft:red_concrete"),
    items("cap", offer(["minecraft:polished_blackstone"], ROOFING),
        default = ["minecraft:polished_blackstone"], icon = "minecraft:polished_blackstone"),
    items("foot", offer(["minecraft:polished_andesite"], MASONRY),
        default = ["minecraft:polished_andesite"], icon = "minecraft:polished_andesite"),
]
lang = {
    "en_us": {
        "title": "Torii",
        "width": "Span",
        "height": "Height",
        "frame": "Vermilion frame",
        "cap": "Dark cap",
        "foot": "Footings"
    },
    "zh_cn": {
        "title": "鸟居",
        "width": "跨度",
        "height": "高度",
        "frame": "朱红构架",
        "cap": "深色笠木",
        "foot": "柱础"
    },
    "zh_hk": {
        "title": "鳥居",
        "width": "跨度",
        "height": "高度",
        "frame": "朱紅構架",
        "cap": "深色笠木",
        "foot": "柱礎"
    },
    "zh_tw": {
        "title": "鳥居",
        "width": "跨度",
        "height": "高度",
        "frame": "朱紅構架",
        "cap": "深色笠木",
        "foot": "柱礎"
    }
}

def draw(site):
    w = site.count("width")
    h = site.count("height")
    room = site.zone.sized(width = w, height = h + 2, depth = 3)
    out = []
    for x, lean in [(1, -16), (w - 3, 16)]:
        foot = room.at(x = x, z = 1).sized(width = 2, height = 1, depth = 1)
        out.append(part("foot", frustum(foot, 46, 42, height = 45), site.items("foot")))
        post = room.at(x = x, z = 1).sized(width = 2, height = h, depth = 1)
        out.append(part("post", frustum(post, 38, 27, lean = lean, height = h * 100 - 55), site.items("frame")))
    beam = room.at(y = h - 1, z = 1).sized(width = w, height = 1, depth = 1)
    out.append(part("kasagi", curved_beam(beam, 50, 35), site.items("cap")))
    out.append(part("shimaki", curved_beam(beam, 28, 35), site.items("frame")))
    tie = room.at(y = h - 2, z = 1).sized(width = w, height = 1, depth = 1)
    out.append(part("nuki", curved_beam(tie, 26, 0), site.items("frame")))
    plaque = room.at(x = w // 2, y = h - 2, z = 1).sized(width = 1, height = 2, depth = 1)
    out.append(part("gakuzuka", frustum(plaque, 18, 18, height = 125), site.items("frame")))
    return out
