load("folkways:materials.star", "FENCES", "LAMPS", "LOGS", "MASONRY", "PLANKS", "WOOD_SLABS", "WOOD_STAIRS", "offer")

accepts = ["path"]
knobs = [
    count("width", 3, 9, default = 3, icon = "minecraft:spruce_slab"),
    choice("head", [
        option("t", icon = "minecraft:spruce_trapdoor"), option("l", icon = "minecraft:spruce_stairs"),
        option("square", icon = "minecraft:spruce_planks"), option("none", icon = "minecraft:barrier"),
    ], default = "t", icon = "minecraft:oak_boat"),
    flag("shelter", default = False, icon = "minecraft:spruce_stairs"),
    items("deck", offer(["minecraft:spruce_planks"], PLANKS),
        default = ["minecraft:spruce_planks"], icon = "minecraft:spruce_planks"),
    items("beam", offer(["minecraft:stripped_spruce_log"], LOGS),
        default = ["minecraft:stripped_spruce_log"], icon = "minecraft:stripped_spruce_log"),
    items("pile", offer(["minecraft:spruce_log"], LOGS),
        default = ["minecraft:spruce_log"], icon = "minecraft:spruce_log"),
    items("rail", offer(["minecraft:spruce_fence"], FENCES),
        default = ["minecraft:spruce_fence"], icon = "minecraft:spruce_fence"),
    items("landing", offer(["minecraft:cobblestone"], MASONRY),
        default = ["minecraft:cobblestone"], icon = "minecraft:cobblestone"),
    items("lantern", offer(["minecraft:lantern"], LAMPS),
        default = ["minecraft:lantern"], icon = "minecraft:lantern"),
    items("roof", offer(["minecraft:dark_oak_stairs", "minecraft:dark_oak_slab"], WOOD_STAIRS, WOOD_SLABS),
        default = ["minecraft:dark_oak_stairs", "minecraft:dark_oak_slab"], icon = "minecraft:dark_oak_stairs"),
]
lang = {
    "en_us": {
        "title": "Dock",
        "width": "Width",
        "head": "Head",
        "head.t": "T-shaped",
        "head.l": "L-shaped",
        "head.square": "Square",
        "head.none": "None",
        "shelter": "Shelter",
        "deck": "Deck",
        "beam": "Beams",
        "pile": "Piles",
        "rail": "Railing",
        "landing": "Landing",
        "lantern": "Lanterns",
        "roof": "Roof",
        "error.dock.points": "A dock needs exactly two path points, shore then water",
        "error.dock.length": "Dock length must be 6–64 blocks",
        "error.dock.width": "Dock width must be 3–9 blocks",
        "error.material": "Choose material for %s",
        "error.dock.start": "Start the dock on dry land",
        "error.dock.end": "The dock must end over water",
        "error.dock.shore": "The dock must cross the shore only once",
        "error.dock.ground": "No ground under the landing",
        "error.dock.bank": "The bank stands too high above the water",
        "error.dock.reach": "The dock is too short to reach open water",
        "error.dock.depth": "The water is too deep to drive piles",
        "error.dock.aground": "The dock would run aground",
    },
    "zh_cn": {
        "title": "码头",
        "width": "宽度",
        "head": "栈桥尽头",
        "head.t": "T 形",
        "head.l": "L 形",
        "head.square": "方台",
        "head.none": "无",
        "shelter": "雨棚",
        "deck": "桥面",
        "beam": "梁",
        "pile": "桩",
        "rail": "栏杆",
        "landing": "岸台",
        "lantern": "灯具",
        "roof": "棚顶",
        "error.dock.points": "码头只支持两个路径点：先岸上，后水面",
        "error.dock.length": "码头长度须为 6–64 格",
        "error.dock.width": "码头宽度须为 3–9 格",
        "error.material": "请为%s选择材料",
        "error.dock.start": "码头须从岸上起步",
        "error.dock.end": "码头终点须在水面上",
        "error.dock.shore": "码头只能跨过一次岸线",
        "error.dock.ground": "码头岸台下方没有地面",
        "error.dock.bank": "岸比水面高出太多",
        "error.dock.reach": "码头太短，伸不进水里",
        "error.dock.depth": "水太深，无法打桩",
        "error.dock.aground": "码头会搁浅在岸上",
    },
    "zh_hk": {
        "title": "碼頭",
        "width": "寬度",
        "head": "棧橋盡頭",
        "head.t": "T 形",
        "head.l": "L 形",
        "head.square": "方台",
        "head.none": "無",
        "shelter": "雨棚",
        "deck": "橋面",
        "beam": "梁",
        "pile": "樁",
        "rail": "欄杆",
        "landing": "岸台",
        "lantern": "燈具",
        "roof": "棚頂",
        "error.dock.points": "碼頭只支援兩個路徑點：先岸上，後水面",
        "error.dock.length": "碼頭長度須為 6–64 格",
        "error.dock.width": "碼頭寬度須為 3–9 格",
        "error.material": "請為%s選擇材料",
        "error.dock.start": "碼頭須從岸上起步",
        "error.dock.end": "碼頭終點須在水面上",
        "error.dock.shore": "碼頭只能跨過一次岸線",
        "error.dock.ground": "碼頭岸台下方沒有地面",
        "error.dock.bank": "岸比水面高出太多",
        "error.dock.reach": "碼頭太短，伸不進水裏",
        "error.dock.depth": "水太深，無法打樁",
        "error.dock.aground": "碼頭會擱淺在岸上",
    },
    "zh_tw": {
        "title": "碼頭",
        "width": "寬度",
        "head": "棧橋盡頭",
        "head.t": "T 形",
        "head.l": "L 形",
        "head.square": "方臺",
        "head.none": "無",
        "shelter": "雨棚",
        "deck": "橋面",
        "beam": "梁",
        "pile": "樁",
        "rail": "欄杆",
        "landing": "岸臺",
        "lantern": "燈具",
        "roof": "棚頂",
        "error.dock.points": "碼頭只支援兩個路徑點：先岸上，後水面",
        "error.dock.length": "碼頭長度須為 6–64 格",
        "error.dock.width": "碼頭寬度須為 3–9 格",
        "error.material": "請為%s選擇材料",
        "error.dock.start": "碼頭須從岸上起步",
        "error.dock.end": "碼頭終點須在水面上",
        "error.dock.shore": "碼頭只能跨過一次岸線",
        "error.dock.ground": "碼頭岸臺下方沒有地面",
        "error.dock.bank": "岸比水面高出太多",
        "error.dock.reach": "碼頭太短，伸不進水裡",
        "error.dock.depth": "水太深，無法打樁",
        "error.dock.aground": "碼頭會擱淺在岸上",
    },
}

def cell(band, x, z, y, height = 1):
    return band.at(x = x, z = z, y = y).sized(width = 1, depth = 1, height = height)

def row(band, x0, x1, z, y):
    return band.at(x = x0, z = z, y = y).sized(width = x1 - x0 + 1, depth = 1)

def lane(band, x, z0, z1, y):
    return band.at(x = x, z = z0, y = y).sized(width = 1, depth = z1 - z0 + 1)

def spaced(first, last, most):
    gap = last - first
    if gap <= 0:
        return [first]
    count = max(1, (gap + most - 1) // most)
    return [first + (gap * i + count // 2) // count for i in range(count + 1)]

def look(site, band, seen, x, z):
    if (x, z) not in seen:
        at = band.at(x = x, z = z).sized(width = 1, depth = 1).center()
        ground = site.ground(at.x, at.z)
        water = site.highest(at.x, at.z, "minecraft:water")
        if water == None or (ground != None and water <= ground):
            water = None
        seen[(x, z)] = (ground, water)
    return seen[(x, z)]

def ramp(site, band, seen, back, deck, width):
    across = range(-1, width + 1)
    if all([look(site, band, seen, x, back - 1)[0] == None or look(site, band, seen, x, back - 1)[0] >= deck for x in across]):
        return None
    for distance in range(2, 13):
        outer = back - distance
        heights = {}
        for x in across:
            ground, water = look(site, band, seen, x, outer)
            if ground == None or water != None or ground > deck or (deck - ground) * 2 > distance:
                break
            heights[x] = ground
        if len(heights) != len(across):
            continue
        lanes = []
        for x in across:
            strip = band.at(x = x, z = outer, y = heights[x]).sized(width = 1, depth = distance + 1)
            lanes.append(arched(strip, strip, 0, end = deck - heights[x]))
        steps = group(lanes)
        return group([steps, under(steps, min(heights.values()))])
    return None

def extent(shape, width, arm):
    if shape == "t":
        return -arm, width - 1 + arm
    if shape == "l":
        return 0, width - 1 + 2 * arm
    if shape == "square":
        return -1, width
    return 0, width - 1

def roof(width, layers, depth):
    shaped = []
    for k in range(layers):
        eaves = ""
        gable = ""
        for x in range(width):
            if x == k and x == width - 1 - k:
                mark = "^"
            elif x == k:
                mark = ">"
            elif x == width - 1 - k:
                mark = "<"
            else:
                mark = " "
            eaves += mark
            gable += "#" if mark == " " and k > 0 and x > k and x < width - 1 - k else mark
        shaped.append([eaves, gable] + [eaves] * (depth - 4) + [gable, eaves])
    return shaped

def draw(site):
    if len(site.path.points) != 2:
        reject("error.dock.points")
    length = site.path.length
    if length < 6 or length > 64:
        reject("error.dock.length")
    width = site.count("width")
    if width < 3 or width > 9:
        reject("error.dock.width")
    for key in ["deck", "beam", "pile", "rail", "landing", "lantern", "roof"]:
        if not site.items(key):
            reject("error.material", translated(key))
    shape = site.choice("head")
    shelter = site.flag("shelter")
    run = site.zone
    for _ in range(4):
        if run.facing == site.path.headings[0]:
            break
        run = run.rotate(1)
    run = run.sized(height = 1).at(y = -run.origin.y)
    band = run.sized(width = width, anchor = "center", parity = "low")
    span = band.depth - 1
    seen = {}
    middle = (width - 1) // 2

    if look(site, band, seen, middle, 0)[1] != None:
        reject("error.dock.start")
    if look(site, band, seen, middle, span)[1] == None:
        reject("error.dock.end")
    shore = span
    for z in range(span, -1, -1):
        if look(site, band, seen, middle, z)[1] == None:
            break
        shore = z
    for z in range(1, shore):
        if look(site, band, seen, middle, z)[1] != None:
            reject("error.dock.shore")
    level = max([look(site, band, seen, middle, z)[1] for z in range(shore, span + 1)])

    back = min(0, shore - 2)
    bank = level + 1
    for z in range(back, shore):
        for x in range(-1, width + 1):
            ground = look(site, band, seen, x, z)[0]
            if ground == None:
                reject("error.dock.ground")
            bank = max(bank, ground)
    deck = bank
    if deck - level > 4:
        reject("error.dock.bank")

    depth = 5 if shelter else 4
    head = span - depth + 1
    if head <= shore:
        reject("error.dock.reach")
    lo, hi = extent(shape, width, 2 if width <= 4 else 3)
    for z in range(shore, span + 1):
        xs = range(lo, hi + 1) if z >= head else range(width)
        for x in xs:
            ground = look(site, band, seen, x, z)[0]
            if ground == None or deck - ground > 32:
                reject("error.dock.depth")
            if ground >= deck:
                reject("error.dock.aground")

    landing = []
    for z in range(back, shore):
        for x in range(-1, width + 1):
            floor = min(look(site, band, seen, x, z)[0] + 1, deck)
            landing.append(cell(band, x, z, floor, deck - floor + 1))
    steps = ramp(site, band, seen, back, deck, width)
    if steps != None:
        landing.append(steps)

    lines = spaced(shore, head, 4)[:-1]
    fronts = spaced(lo, hi, 4)
    tops = {}
    for z in lines:
        for x in [0, width - 1]:
            tops[(x, z)] = deck
        if width >= 6:
            for x in spaced(0, width - 1, 3)[1:-1]:
                tops[(x, z)] = deck - 1
    for x in fronts + [middle]:
        tops[(x, span)] = deck
    for x in fronts + [0, width - 1]:
        tops[(x, head)] = deck if x <= 0 or x >= width - 1 else deck - 1
    for z in spaced(head, span, 4):
        for x in [lo, hi]:
            tops[(x, z)] = deck
    piles = []
    for x, z in sorted(tops.keys()):
        ground = look(site, band, seen, x, z)[0]
        if tops[(x, z)] > ground:
            piles.append(cell(band, x, z, ground + 1, tops[(x, z)] - ground))

    beams = []
    for z in lines:
        beams.append(row(band, 0, width - 1, z, deck - 1))
    for z in [head, span]:
        beams.append(row(band, lo, hi, z, deck - 1))
    for x in [lo, hi]:
        beams.append(lane(band, x, head + 1, span - 1, deck - 1))
    edges = [lane(band, x, shore - 1, head - 1, deck) for x in [0, width - 1]]
    edges += [row(band, lo, hi, z, deck) for z in [head, span]]
    edges += [lane(band, x, head + 1, span - 1, deck) for x in [lo, hi]]
    boards = [band.at(z = shore - 1, y = deck).sized(depth = head - shore + 1),
              band.at(x = lo, z = head, y = deck).sized(width = hi - lo + 1, depth = depth)]

    parts = [part("landing", group(landing), site.items("landing"), fit = "layered", labels = ["deck"])]
    for beam in beams:
        parts.append(part("beam", beam, site.items("beam"), orient = "along"))
    parts.append(part("deck", group(boards), site.items("deck")))
    for edge in edges:
        parts.append(part("beam", edge, site.items("beam"), orient = "along", labels = ["deck"]))
    parts.append(part("pile", group(piles), site.items("pile"), orient = "y", labels = ["deck"]))

    rails = [lane(band, 0, shore - 1, head if lo < 0 else span, deck + 1),
             lane(band, width - 1, shore - 1, head if hi > width - 1 else span, deck + 1)]
    if lo < 0:
        rails += [row(band, lo, 0, head, deck + 1), lane(band, lo, head, span, deck + 1)]
    if hi > width - 1:
        rails += [row(band, width - 1, hi, head, deck + 1), lane(band, hi, head, span, deck + 1)]

    posts = {(0, shore - 1): 1, (width - 1, shore - 1): 1, (lo, span): 1, (hi, span): 1}
    lit = list(posts.keys())
    for x in [lo, hi]:
        posts[(x, head)] = 1
    for x in ([0] if lo < -1 else []) + ([width - 1] if hi > width else []):
        posts[(x, head)] = 1
        lit.append((x, head))
    for x in fronts:
        if x != middle:
            posts[(x, span)] = 1
    hung = []
    last = shore - 1
    for z in lines:
        if z - last >= 7 and head - z >= 4:
            hung += [(0, z), (width - 1, z)]
            last = z

    if shelter:
        left = max(lo, -1)
        right = min(hi, width)
        for x in [left, right]:
            for z in [head, span - 1]:
                posts[(x, z)] = 3
            parts.append(part("beam", lane(band, x, head, span - 1, deck + 4), site.items("beam"),
                orient = "along"))
        for z in [head, span - 1]:
            parts.append(part("beam", row(band, left + 1, right - 1, z, deck + 4), site.items("beam"),
                orient = "along"))
        cover = right - left + 3
        layers = (cover + 1) // 2
        slope = piece(key = {">": "minecraft:oak_stairs[facing=east]", "<": "minecraft:oak_stairs[facing=west]",
                             "^": "minecraft:oak_slab", "#": "minecraft:oak_planks", " ": None},
                      layers = roof(cover, layers, depth + 1))
        parts.append(stamp("roof", slope, band.at(x = left - 1, z = head - 1, y = deck + 4)
            .sized(width = cover, depth = depth + 1, height = layers), fit = "exact",
            swap = {"minecraft:oak_stairs": site.items("roof"), "minecraft:oak_slab": site.items("roof"),
                    "minecraft:oak_planks": site.items("deck")}))
        lit = [spot for spot in lit if posts.get(spot, 1) == 1]

    ground, water = look(site, band, seen, middle, span + 1)
    if ground != None and ground < level:
        bottom = max(ground + 1, level - 1, look(site, band, seen, middle, span)[0] + 1)
        ladder = piece(key = {"w": "minecraft:ladder[facing=north,waterlogged=true]", "l": "minecraft:ladder[facing=north]"},
                       layers = [["w" if y <= level else "l"] for y in range(bottom, deck + 1)])
        parts.append(stamp("ladder", ladder, cell(band, middle, span + 1, bottom, deck - bottom + 1), fit = "exact"))

    for rail in rails:
        parts.append(part("rail", rail, site.items("rail"), fit = "connected", support = "deck"))
    for spot in sorted(posts.keys()):
        parts.append(part("post", cell(band, spot[0], spot[1], deck + 1, posts[spot]), site.items("pile"),
            fit = "connected", support = "deck", orient = "y", over = ["rail"]))
    for spot in lit:
        parts.append(part("lantern", cell(band, spot[0], spot[1], deck + 2), site.items("lantern"),
            fit = "connected", support = "post"))
    for spot in hung:
        parts.append(part("lantern", cell(band, spot[0], spot[1], deck + 2), site.items("lantern"),
            fit = "connected", support = "rail"))
    return parts
