load("folkways:grading.star", "at", "centreline", "earthworks", "floor", "only", "profile", "trace")

accepts = ["path"]
knobs = [
    count("width", 3, 9, default = 5, icon = "minecraft:stone_brick_slab"),
    items("surface", default = ["minecraft:andesite", "minecraft:cobblestone", "minecraft:polished_andesite"],
        icon = "minecraft:andesite"),
    items("edge", default = ["minecraft:stone_bricks"], icon = "minecraft:stone_bricks"),
    items("fill", default = ["minecraft:dirt"], icon = "minecraft:dirt"),
    flag("lights", default = True, icon = "minecraft:lantern"),
    count("spacing", 6, 32, default = 12, icon = "minecraft:chain"),
    items("post", default = ["minecraft:spruce_fence"], icon = "minecraft:spruce_fence"),
    items("lamp", default = ["minecraft:lantern"], icon = "minecraft:lantern"),
]

POST = 3

def blend(listed):
    return mix({listed[i]: len(listed) - i for i in range(len(listed))})

def lights(line, columns, width, spacing):
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
                posts.extend([(spot[0], top + y, spot[1]) for y in range(1, POST + 1)])
                lamps.append((spot[0], top + POST + 1, spot[1]))
                break
        s += spacing
        side = -side
    return posts, lamps

def draw(site):
    width = site.count("width")
    if width < 3 or width > 9:
        fail("Road width must be 3–9 blocks / 道路宽度须为 3–9 格")
    for key in ["surface", "edge", "fill", "post", "lamp"]:
        if not site.items(key):
            fail("Choose material for %s / 请选择材料" % key)
    line = centreline(site, width)
    if line["length"] < 2 or line["length"] > 96:
        fail("Road length must be 2–96 blocks; split longer roads into several / 道路长度须为 2–96 格，更长的请分段修建")
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
        posts, lamps = lights(line, works["columns"], width, site.count("spacing"))
        if posts:
            parts.append(part("post", cells(posts), site.items("post")))
            parts.append(part("lamp", cells(lamps), site.items("lamp")))
    return parts
