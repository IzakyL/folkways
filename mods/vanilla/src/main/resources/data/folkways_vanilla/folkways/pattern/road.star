load("folkways_vanilla:grading.star", "at", "centreline", "earthworks", "floor", "only", "profile", "trace")
load("folkways_vanilla:materials.star", "EARTH", "FENCES", "LAMPS", "MASONRY", "WALLS", "offer")

accepts = ["path"]
knobs = [
    count("width", 3, 9, default = 5, icon = "minecraft:stone_brick_slab"),
    items("surface", offer(["minecraft:andesite", "minecraft:cobblestone", "minecraft:polished_andesite"], MASONRY, ["minecraft:gravel"]),
        default = ["minecraft:andesite", "minecraft:cobblestone", "minecraft:polished_andesite"],
        icon = "minecraft:andesite"),
    items("edge", offer(["minecraft:stone_bricks"], MASONRY),
        default = ["minecraft:stone_bricks"], icon = "minecraft:stone_bricks"),
    items("fill", offer(["minecraft:dirt"], EARTH, MASONRY),
        default = ["minecraft:dirt"], icon = "minecraft:dirt"),
    flag("lights", default = True, icon = "minecraft:lantern"),
    count("spacing", 6, 32, default = 12, icon = "minecraft:chain"),
    items("post", offer(["minecraft:spruce_fence"], FENCES, WALLS),
        default = ["minecraft:spruce_fence"], icon = "minecraft:spruce_fence"),
    items("lamp", offer(["minecraft:lantern"], LAMPS),
        default = ["minecraft:lantern"], icon = "minecraft:lantern"),
]
lang = {
    "en_us": {
        "title": "Road",
        "width": "Width",
        "surface": "Surface",
        "edge": "Edge",
        "fill": "Fill",
        "lights": "Lights",
        "spacing": "Light spacing",
        "post": "Posts",
        "lamp": "Lamps",
        "error.material": "Choose material for %s",
        "error.road.length": "A road runs at least 2 blocks",
    },
    "zh_cn": {
        "title": "道路",
        "width": "宽度",
        "surface": "路面",
        "edge": "路缘",
        "fill": "填土",
        "lights": "路灯",
        "spacing": "路灯间距",
        "post": "灯柱",
        "lamp": "灯具",
        "error.material": "请为%s选择材料",
        "error.road.length": "道路长度至少 2 格",
    },
    "zh_hk": {
        "title": "道路",
        "width": "寬度",
        "surface": "路面",
        "edge": "路緣",
        "fill": "填土",
        "lights": "路燈",
        "spacing": "路燈間距",
        "post": "燈柱",
        "lamp": "燈具",
        "error.material": "請為%s選擇材料",
        "error.road.length": "道路長度至少 2 格",
    },
    "zh_tw": {
        "title": "道路",
        "width": "寬度",
        "surface": "路面",
        "edge": "路緣",
        "fill": "填土",
        "lights": "路燈",
        "spacing": "路燈間距",
        "post": "燈柱",
        "lamp": "燈具",
        "error.material": "請為%s選擇材料",
        "error.road.length": "道路長度至少 2 格",
    },
}

POST = 3

def blend(listed):
    return mix({listed[i]: len(listed) - i for i in range(len(listed))})

def lights(site, line, columns, width, spacing):
    posts = []
    lamps = []
    half = width / 2.0
    s = spacing / 2.0
    side = 1
    for _ in range(int(line["length"] / spacing) + 1):
        if s > line["length"] - 2:
            break
        x, z, run = at(line, s)
        for lateral in [half + 0.5, half + 0.9, half + 0.2]:
            spot = (floor(x - run["uz"] * side * lateral), floor(z + run["ux"] * side * lateral))
            found = columns.get(spot)
            if found != None and found[0] == 1:
                top = found[1]
                if not all([site.matches(point(spot[0], top + y, spot[1]), "air|replaceable")
                            for y in range(1, POST + 2)]):
                    continue
                posts.extend([(spot[0], top + y, spot[1]) for y in range(1, POST + 1)])
                lamps.append((spot[0], top + POST + 1, spot[1]))
                break
        s += spacing
        side = -side
    return posts, lamps

def draw(site):
    width = site.count("width")
    for key in ["surface", "edge", "fill", "post", "lamp"]:
        if not site.items(key):
            reject("error.material", translated(key))
    line = centreline(site, width)
    if line["length"] < 2:
        reject("error.road.length")
    plan = profile(site, line, width)
    works = earthworks(site, line, plan, width)
    spine = trace(plan)
    whole = sweep(spine, width = width)
    inner = sweep(spine, width = width - 2)
    parts = []
    dug = only(site, works["dig"], int(width / 2) + plan["depth"] + 3, "solid|replaceable")
    if dug != None:
        parts.append(clear("dig", dug))
    crown = only(site, works["crown"], int(width / 2) + 2, "foliage")
    if crown != None:
        parts.append(clear("canopy", crown))
    if works["fill"]:
        parts.append(part("fill", cells(works["fill"]), site.items("fill")))
    parts.append(part("surface", inner, blend(site.items("surface")), fit = "layered"))
    parts.append(part("edge", subtract(whole, inner), site.items("edge"), fit = "layered"))
    if site.flag("lights"):
        posts, lamps = lights(site, line, works["columns"], width, site.count("spacing"))
        if posts:
            parts.append(part("post", cells(posts), site.items("post")))
            parts.append(part("lamp", cells(lamps), site.items("lamp")))
    return parts
