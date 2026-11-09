load("folkways_vanilla:materials.star", "FENCES", "GATES", "LAMPS", "LOGS", "MASONRY", "PLANKS", "STONE_STAIRS", "WOOD_STAIRS", "offer")

accepts = ["zone"]
capture = {"around": 10, "down": "all", "up": 2}
knobs = [
    count("y", -64, 319, default = 16, icon = "minecraft:iron_pickaxe"),
    count("spacing", 2, 6, default = 3, icon = "minecraft:stone"),
    count("main_east", 0, 128, default = 26, icon = "minecraft:stone_pickaxe"),
    count("branch_east", 0, 48, default = 12, icon = "minecraft:rail"),
    count("main_south", 0, 128, default = 0, icon = "minecraft:stone_pickaxe"),
    count("branch_south", 0, 48, default = 12, icon = "minecraft:rail"),
    count("main_west", 0, 128, default = 0, icon = "minecraft:stone_pickaxe"),
    count("branch_west", 0, 48, default = 12, icon = "minecraft:rail"),
    count("main_north", 0, 128, default = 0, icon = "minecraft:stone_pickaxe"),
    count("branch_north", 0, 48, default = 12, icon = "minecraft:rail"),
    flag("chase", default = True, icon = "minecraft:raw_iron"),
    items("timber", offer(["minecraft:spruce_log"], LOGS),
        default = ["minecraft:spruce_log"], icon = "minecraft:spruce_log"),
    items("planks", offer(["minecraft:spruce_planks"], PLANKS),
        default = ["minecraft:spruce_planks"], icon = "minecraft:spruce_planks"),
    items("stairs", offer(["minecraft:spruce_stairs"], WOOD_STAIRS, STONE_STAIRS),
        default = ["minecraft:spruce_stairs"], icon = "minecraft:spruce_stairs"),
    items("rail", offer(["minecraft:spruce_fence"], FENCES),
        default = ["minecraft:spruce_fence"], icon = "minecraft:spruce_fence"),
    items("gate", offer(["minecraft:spruce_fence_gate"], GATES),
        default = ["minecraft:spruce_fence_gate"], icon = "minecraft:spruce_fence_gate"),
    items("lamp", offer(["minecraft:lantern"], LAMPS),
        default = ["minecraft:lantern"], icon = "minecraft:lantern"),
    items("seal", offer(["minecraft:cobblestone"], MASONRY),
        default = ["minecraft:cobblestone"], icon = "minecraft:cobblestone"),
]
lang = {
    "en_us": {
        "title": "Mine",
        "y": "Mining Y",
        "spacing": "Branch spacing",
        "main_east": "East main length",
        "branch_east": "East branch length",
        "main_south": "South main length",
        "branch_south": "South branch length",
        "main_west": "West main length",
        "branch_west": "West branch length",
        "main_north": "North main length",
        "branch_north": "North branch length",
        "error.mine.directions": "Choose at least one main tunnel longer than zero",
        "chase": "Chase veins",
        "timber": "Timber",
        "planks": "Planks",
        "stairs": "Stairs",
        "rail": "Railing",
        "gate": "Gate",
        "lamp": "Lamps",
        "seal": "Seal",
        "error.mine.level": "The chosen Y lies above or too near the mine mouth",
        "error.mine.ground": "No ground under the mine mouth",
        "error.mine.water": "The mine mouth lies in water",
        "error.material": "Choose material for %s",
        "error.mine.lamp": "The lamp must be a lantern",
    },
    "zh_cn": {
        "title": "矿井",
        "y": "开采 Y 值",
        "spacing": "支巷间距",
        "main_east": "东主巷长度",
        "branch_east": "东支巷长度",
        "main_south": "南主巷长度",
        "branch_south": "南支巷长度",
        "main_west": "西主巷长度",
        "branch_west": "西支巷长度",
        "main_north": "北主巷长度",
        "branch_north": "北支巷长度",
        "error.mine.directions": "至少选择一个长度大于零的主巷方向",
        "chase": "追矿脉",
        "timber": "支护木",
        "planks": "木板",
        "stairs": "楼梯",
        "rail": "栏杆",
        "gate": "栅栏门",
        "lamp": "灯具",
        "seal": "封堵",
        "error.mine.level": "所选 Y 值高于矿口或离矿口太近",
        "error.mine.ground": "矿口下方没有地面",
        "error.mine.water": "矿口位于水中",
        "error.material": "请为%s选择材料",
        "error.mine.lamp": "灯具须为灯笼",
    },
    "zh_hk": {
        "title": "礦井",
        "y": "開採 Y 值",
        "spacing": "支巷間距",
        "main_east": "東主巷長度",
        "branch_east": "東支巷長度",
        "main_south": "南主巷長度",
        "branch_south": "南支巷長度",
        "main_west": "西主巷長度",
        "branch_west": "西支巷長度",
        "main_north": "北主巷長度",
        "branch_north": "北支巷長度",
        "error.mine.directions": "至少選擇一個長度大於零的主巷方向",
        "chase": "追礦脈",
        "timber": "支護木",
        "planks": "木板",
        "stairs": "樓梯",
        "rail": "欄杆",
        "gate": "柵欄門",
        "lamp": "燈具",
        "seal": "封堵",
        "error.mine.level": "所選 Y 值高於礦口或離礦口太近",
        "error.mine.ground": "礦口下方沒有地面",
        "error.mine.water": "礦口位於水中",
        "error.material": "請為%s選擇材料",
        "error.mine.lamp": "燈具須為燈籠",
    },
    "zh_tw": {
        "title": "礦井",
        "y": "開採 Y 值",
        "spacing": "支巷間距",
        "main_east": "東主巷長度",
        "branch_east": "東支巷長度",
        "main_south": "南主巷長度",
        "branch_south": "南支巷長度",
        "main_west": "西主巷長度",
        "branch_west": "西支巷長度",
        "main_north": "北主巷長度",
        "branch_north": "北支巷長度",
        "error.mine.directions": "至少選擇一個長度大於零的主巷方向",
        "chase": "追礦脈",
        "timber": "支護木",
        "planks": "木材",
        "stairs": "樓梯",
        "rail": "欄杆",
        "gate": "柵欄門",
        "lamp": "燈具",
        "seal": "封堵",
        "error.mine.level": "所選 Y 值高於礦口或離礦口太近",
        "error.mine.ground": "礦口下方沒有地面",
        "error.mine.water": "礦口位於水中",
        "error.material": "請為%s選擇材料",
        "error.mine.lamp": "燈具須為燈籠",
    },
}

FALLING = "minecraft:gravel|minecraft:sand|minecraft:red_sand|minecraft:suspicious_sand|minecraft:suspicious_gravel"
ORES = "#c:ores"
CHASE = 2048
VEIN = 8
COMPASS = {(1, 0): "east", (-1, 0): "west", (0, 1): "north", (0, -1): "south"}
STEP = "minecraft:oak_stairs"
GATE = "minecraft:oak_fence_gate"
AROUND = [(1, 0), (-1, 0), (0, 1), (0, -1)]
# One quarter of the stair's round, from the centre (4, 4): a flat landing, three steps down, then a
# landing that zigzags round the bend. Turned four times it closes a 32-cell lap of the 9-wide shaft.
QUARTER = [(-2, -4), (-1, -4), (0, -4), (1, -4), (2, -4), (2, -3), (3, -3), (3, -2)]
DIRS = ["east", "south", "west", "north"]
WORLD = [(1, 0), (0, 1), (-1, 0), (0, -1)]
LAP = 32
FALL = 12

def turned(v, r):
    for _ in range(r):
        v = (-v[1], v[0])
    return v

def lap():
    cells = []
    for r in range(4):
        for q in QUARTER:
            c = turned(q, r)
            cells.append((4 + c[0], 4 + c[1]))
    return cells

def disc(a, b):
    return 4 * ((a - 4) * (a - 4) + (b - 4) * (b - 4)) < 81

def beside(c, a, b):
    return c[0] - a in (-1, 0, 1) and c[1] - b in (-1, 0, 1)

LOOP = lap()
TRAVEL = [turned((1, 0), r) for r in range(4)]
OUT = [turned((0, -1), r) for r in range(4)]
INSIDE = [(a, b) for b in range(9) for a in range(9) if disc(a, b) and (a, b) not in LOOP]
RING = [c for c in INSIDE if [d for d in LOOP if beside(d, c[0], c[1])]]

def frame(site, ox, oz):
    one = site.zone.sized(width = 1, height = 1, depth = 1)
    o = one.origin
    ex = one.at(x = 1).origin
    ez = one.at(z = 1).origin
    return {"one": one, "ox": ox, "oz": oz, "px": o.x, "pz": o.z,
            "xx": ex.x - o.x, "xz": ex.z - o.z, "zx": ez.x - o.x, "zz": ez.z - o.z,
            "across": {True: "x" if ex.x != o.x else "z", False: "z" if ez.z != o.z else "x"}}

def room(m, a, y, b, w = 1, h = 1, d = 1):
    return m["one"].at(x = m["ox"] + a, y = y, z = m["oz"] + b).sized(width = w, height = h, depth = d)

def spot(m, a, y, b):
    x = m["ox"] + a
    z = m["oz"] + b
    return point(m["px"] + m["xx"] * x + m["zx"] * z, y, m["pz"] + m["xz"] * x + m["zz"] * z)

def scoped(m, bx):
    return room(m, bx[0], bx[1], bx[2], bx[3], bx[4], bx[5])

def walk(i):
    c = LOOP[i % LAP]
    return c[0], c[1], (i % LAP) // 8, i % 8

def stepped(i):
    return i % 8 in (1, 2, 3)

def drop(i):
    return FALL * (i // LAP) + 3 * ((i % LAP) // 8) + max(0, min(i % 8, 4) - 1)

def into(i):
    c = LOOP[i % LAP]
    p = LOOP[(i - 1) % LAP]
    return (c[0] - p[0], c[1] - p[1])

def near(cells, a, b):
    return [c for c in cells if beside(c, a, b)]

def rows(low, h):
    out = []
    for b in range(9):
        xs = [c[0] for c in INSIDE if c[1] == b]
        if xs:
            out.append([min(xs), low, b, max(xs) - min(xs) + 1, h, 1])
    return out

def span(w, out, lat, t0, t1, s0, s1, y, h):
    a1 = w[0] + t0 * out[0] + s0 * lat[0]
    b1 = w[1] + t0 * out[1] + s0 * lat[1]
    a2 = w[0] + t1 * out[0] + s1 * lat[0]
    b2 = w[1] + t1 * out[1] + s1 * lat[1]
    return [min(a1, a2), y, min(b1, b2), max(a1, a2) - min(a1, a2) + 1, h, max(b1, b2) - min(b1, b2) + 1]

def stair(site, m, a, y, b, facing, w = 1, role = "stairs"):
    shape = piece(key = {"s": "%s[facing=%s,half=bottom]" % (STEP, facing)}, layers = [["s" * w]])
    return stamp(role, shape, room(m, a, y, b, w), swap = {STEP: site.items("stairs")})

def nestled(site, m, cells, dug):
    for c in cells:
        below = (c[0], c[1] - 1, c[2])
        if (tuple(c[:3]) not in dug and below not in dug and site.matches(spot(m, c[0], c[1], c[2]), "solid")
            and site.matches(spot(m, below[0], below[1], below[2]), "solid")):
            return [lamp(site, m, c[0], c[1], c[2])]
    return []

def dug_of(boxes):
    dug = {}
    for bx in boxes:
        for c in cells_of(bx):
            dug[c] = True
    return dug

def lamp(site, m, a, y, b, hanging = False):
    state = site.items("lamp")[0] + ("[hanging=true]" if hanging else "[hanging=false]")
    return stamp("lamp", piece(key = {"l": state}, layers = [["l"]]), room(m, a, y, b))

def cells_of(bx):
    return [(bx[0] + da, bx[1] + dy, bx[2] + db)
            for dy in range(bx[4]) for db in range(bx[5]) for da in range(bx[3])]

def depth_of(site, y0):
    wanted = site.altitude + y0 - site.count("y")
    if wanted >= 14:
        return LAP * (wanted // FALL) + 8 * (wanted % FALL // 3) + 1 + wanted % 3
    reject("error.mine.level")

def protected(site):
    return "|".join([v for key in ["timber", "planks", "stairs", "rail", "gate", "lamp"]
                     for v in site.items(key)])

def cut(shape, taken):
    return subtract(shape, taken) if shape != None and taken != None else shape

def bare(site, m, bx):
    scope = scoped(m, bx)
    return cut(terrain(scope, of = "!air"), terrain(scope, of = protected(site)))

def guarded(site, m, boxes, underground, dug, y0):
    seal = []
    prop = []
    dig = []
    seen = {}
    for bx in underground:
        low = bx[1] - 1
        high = min(y0, bx[1] + bx[4] + 1)
        if low >= high:
            continue
        around = [bx[0] - 1, low, bx[2] - 1, bx[3] + 2, high - low, bx[5] + 2]
        if terrain(scoped(m, around), of = "air|fluid") == None:
            continue
        found = []
        for c in cells_of(around):
            if c in dug or c in seen:
                continue
            seen[c] = True
            p = spot(m, c[0], c[1], c[2])
            if site.matches(p, "air|fluid"):
                found.append(p)
        if found:
            seal.append(part("seal", cells(found), site.items("seal")))
    for bx in boxes:
        loose = terrain(scoped(m, [bx[0], bx[1] + bx[4], bx[2], bx[3], 1, bx[5]]), of = FALLING)
        if loose != None:
            prop.append(cut(loose, m.get("roads")))
        solid = bare(site, m, bx)
        if solid != None:
            dig.append(clear("dig", solid))
    if prop:
        seal.append(part("prop", group(prop), site.items("seal")))
    return seal + dig

def chase(site, m, boxes):
    dug = dug_of(boxes)
    looked = {}
    found = []
    for c in dug:
        if len(found) >= CHASE:
            break
        for d in [(1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1), (0, 1, 0)]:
            seed = (c[0] + d[0], c[1] + d[1], c[2] + d[2])
            if seed in dug or seed in looked:
                continue
            looked[seed] = True
            if not site.matches(spot(m, seed[0], seed[1], seed[2]), ORES):
                continue
            vein = [seed]
            for k in range(VEIN):
                if k >= len(vein) or len(vein) >= VEIN or len(found) + len(vein) >= CHASE:
                    break
                at = vein[k]
                for e in [(1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1), (0, 1, 0)]:
                    near = (at[0] + e[0], at[1] + e[1], at[2] + e[2])
                    if near in dug or near in looked or len(vein) >= VEIN:
                        continue
                    looked[near] = True
                    if site.matches(spot(m, near[0], near[1], near[2]), ORES):
                        vein.append(near)
            found.extend(vein[:CHASE - len(found)])
    return [[c[0], c[1], c[2], 1, 1, 1] for c in found]

def fence(role, site, m, cells):
    return part(role, group([room(m, c[0], c[1], c[2]) for c in cells]), site.items("rail"))

def head(site):
    zone = site.zone
    m = frame(site, (zone.width - 1) // 2 - 4, (zone.depth - 1) // 2 - 4)
    ground = site.survey(room(m, -1, 0, -1, 11, 1, 11))
    if ground.median == None:
        reject("error.mine.ground")
    if ground.water > 0.25:
        reject("error.mine.water")
    heights = {}
    for a in range(-1, 10):
        for b in range(-1, 10):
            p = spot(m, a, 0, b)
            heights[(a, b)] = site.ground(p.x, p.z)
    levels = sorted([h for h in heights.values() if h != None])
    y0 = levels[len(levels) * 3 // 4]
    n = depth_of(site, y0)
    boxes = [[-1, y0 + 1, -1, 11, 4, 11]]
    corners = [(-1, -1), (9, -1), (-1, 9), (9, 9)]
    parts = []
    footing = []
    for c in heights:
        if (c[0] in (-1, 9) or c[1] in (-1, 9)) and heights[c] != None and y0 - heights[c] > 1:
            base = max(heights[c] + 1, y0 - 12)
            footing.append(room(m, c[0], base, c[1], h = y0 - base))
    if footing:
        parts.append(part("footing", group(footing), site.items("seal")))
    # The first steps sink through the deck; the well in the middle stays open.
    slot = [room(m, LOOP[i][0], y0, LOOP[i][1]) for i in range(1, LAP) if drop(i) <= 3]
    slot.extend([room(m, c[0], y0, c[1]) for c in INSIDE])
    parts.append(part("deck", subtract(room(m, -1, y0, -1, 11, 1, 11), group(slot)), site.items("planks")))
    # One fence round the shaft mouth, closed off by a gate where the stair begins.
    gate = (LOOP[0][0] + OUT[0][0], LOOP[0][1] + OUT[0][1])
    rails = {}
    for i in range(LAP):
        if drop(i) >= 3:
            rails[LOOP[i]] = True
    for i in range(5):
        for c in heights:
            if not disc(c[0], c[1]) and c != gate and beside(c, LOOP[i][0], LOOP[i][1]):
                rails[c] = True
    for i in range(2):
        for c in near(RING, LOOP[i][0], LOOP[i][1]):
            rails[c] = True
    parts.append(fence("rail", site, m, [(c[0], y0 + 1, c[1]) for c in rails]))
    facing = COMPASS[(-OUT[0][0], -OUT[0][1])]
    parts.append(stamp("gate", piece(key = {"g": "%s[facing=%s,open=false,in_wall=false]" % (GATE, facing)},
        layers = [["g"]]), room(m, gate[0], y0 + 1, gate[1]), swap = {GATE: site.items("gate")}))
    parts.append(part("post", group([room(m, -1, y0 + 1, 4, h = 4), room(m, 9, y0 + 1, 4, h = 4)]),
        site.items("timber"), orient = "y"))
    parts.append(part("beam", room(m, -2, y0 + 5, 4, 13, 1, 1), site.items("timber"), orient = "along"))
    for eave in [(2, y0 + 4, "north"), (3, y0 + 5, "north"), (5, y0 + 5, "south"), (6, y0 + 4, "south")]:
        parts.append(stair(site, m, -2, eave[1], eave[0], eave[2], w = 13, role = "roof"))
    parts.append(lamp(site, m, 4, y0 + 4, 3, hanging = True))
    for c in corners:
        parts.append(lamp(site, m, c[0], y0 + 1, c[1]))
    return m, {"y0": y0, "n": n}, parts, boxes

def flight(site, m, k, dug):
    y0 = k["y0"]
    n = k["n"]
    i0 = 0
    i1 = n
    floor = y0 - drop(n)
    boxes = []
    planks = []
    posts = []
    built = []
    nooks = []
    for i in range(i0, i1 + 1):
        a, b, side, q = walk(i)
        y = y0 - drop(i)
        boxes.append([a, y + 1, b, 1, 3, 1])
        if stepped(i) and i != n:
            back = into(i)
            built.append(stair(site, m, a, y, b, COMPASS[(-back[0], -back[1])]))
        else:
            planks.append(room(m, a, y, b))
        if q == 6 and i > 2:
            nooks.append([(a + o[0], y + 2, b + o[1]) for o in AROUND if not disc(a + o[0], b + o[1])])
        if q == 2 and n - i > 1 and drop(i) > 4:
            out = OUT[side]
            posts.append(room(m, a + out[0], y + 1, b + out[1], h = 3))
            built.append(part("cap", room(m, a, y + 4, b), site.items("timber"),
                orient = m["across"][out[0] != 0]))
    if i1 == n:
        la, lb = LOOP[(n + 1) % LAP]
        boxes.append([la, floor + 1, lb, 1, 3, 1])
        planks.append(room(m, la, floor, lb))
    # The well in the middle goes down with the stair to the floor.
    top = y0 - drop(i0)
    low = y0 - drop(i1 + 1) if i1 < n else floor
    well = rows(low + 1, top - low) if top > low else []
    # A rail beside each step, at the height one stands on it, until the drop to the floor is a short one.
    rails = {}
    for i in range(i0, n + 1):
        level = y0 - drop(i) + 1
        if level <= low:
            break
        if level > top or level < floor + 3:
            continue
        for c in near(RING, LOOP[i % LAP][0], LOOP[i % LAP][1]):
            rails[(c[0], level, c[1])] = True
    for nook in nooks:
        built.extend(nestled(site, m, nook, dug))
    parts = []
    parts.append(part("landing", group(planks), site.items("planks")))
    if posts:
        parts.append(part("post", group(posts), site.items("timber"), orient = "y"))
    if rails:
        parts.append(fence("rail", site, m, rails.keys()))
    return parts + built, boxes + well

def axes(m, d):
    v = WORLD[d]
    out = (v[0] * m["xx"] + v[1] * m["xz"], v[0] * m["zx"] + v[1] * m["zz"])
    return out, (-out[1], out[0])

def course(m, d):
    out, lat = axes(m, d)
    return (4 + 8 * out[0], 4 + 8 * out[1]), out, lat

def length(site, d):
    return site.count("main_" + DIRS[d])

def passages(site, m, k):
    yf = k["y0"] - drop(k["n"])
    boxes = []
    for d in range(4):
        total = length(site, d)
        if total == 0:
            continue
        w, out, lat = course(m, d)
        boxes.append(span(w, out, lat, 1, total, -1, 1, yf + 1, 3))
        size = site.count("branch_" + DIRS[d])
        if size > 0:
            for t in range(site.count("spacing") + 2, total + 1, site.count("spacing") + 1):
                for sign in [-1, 1]:
                    boxes.append(span(w, out, lat, t, t, 2 * sign, (size + 1) * sign, yf + 1, 2))
    return boxes

def junction(k):
    yf = k["y0"] - drop(k["n"])
    # Stay outside the shaft: crossing its centre would cut through the last stair and its rail.
    boxes = [[-4, yf + 1, -4, 17, 3, 3], [-4, yf + 1, 10, 17, 3, 3],
             [-4, yf + 1, -1, 3, 3, 11], [10, yf + 1, -1, 3, 3, 11]]
    a, b, side, _ = walk(k["n"])
    boxes.append(span((a, b), OUT[side], TRAVEL[side], 1, 3, -1, 1, yf + 1, 3))
    return boxes

def hub(site, m, k):
    boxes = junction(k)
    floors = []
    for bx in boxes:
        scope = room(m, bx[0], bx[1] - 1, bx[2], bx[3], 1, bx[5])
        missing = terrain(scope, of = "air|fluid")
        if missing != None:
            floors.append(missing)
    parts = []
    if floors:
        parts.append(part("landing", group(floors), site.items("planks")))
    return parts

def drift(site, m, k, d, dug):
    yf = k["y0"] - drop(k["n"])
    size = site.count("branch_" + DIRS[d])
    period = site.count("spacing") + 1
    total = length(site, d)
    w, out, lat = course(m, d)
    t0 = 1
    t1 = total
    boxes = [span(w, out, lat, t0, t1, -1, 1, yf + 1, 3)]
    posts = []
    built = []
    nooks = []
    for t in range(t0, t1 + 1):
        if t == 1 or t % period == (1 + period // 2) % period:
            for s in [-2, 2]:
                bx = span(w, out, lat, t, t, s, s, yf + 1, 3)
                # A post must not occupy a crossing that another direction will open later.
                post = cut(bare(site, m, bx), m["roads"])
                if post != None:
                    posts.append(post)
            cap = span(w, out, lat, t, t, -1, 1, yf + 4, 1)
            cap = bare(site, m, cap)
            if cap != None:
                built.append(part("cap", cap, site.items("timber"), orient = "along"))
            nooks.append([span(w, out, lat, t + dt, t + dt, s, s, yf + 2, 1) for dt in [-1, 1] for s in [2, -2]])
        if size > 0 and t > 1 and t % period == 1:
            for sign in [-1, 1]:
                boxes.append(span(w, out, lat, t, t, 2 * sign, (size + 1) * sign, yf + 1, 2))
                nooks.append([span(w, out, lat, t + dt, t + dt, (size + 1 + ds) * sign, (size + 1 + ds) * sign,
                    yf + 1, 1) for dt, ds in [(0, 1), (1, 0), (-1, 0)]])
                if size > 20:
                    nooks.append([span(w, out, lat, t + 1, t + 1, (size // 2 + 1) * sign, (size // 2 + 1) * sign,
                        yf + 1, 1)])
    for nook in nooks:
        cells = [c for c in nook if not site.matches(spot(m, c[0], c[1], c[2]), protected(site))]
        built.extend(nestled(site, m, cells, dug))
    parts = []
    if posts:
        parts.append(part("post", group(posts), site.items("timber"), orient = "y"))
    return parts + built

def draw(site):
    for key in ["timber", "planks", "stairs", "rail", "gate", "lamp", "seal"]:
        if not site.items(key):
            reject("error.material", translated(key))
    light = site.items("lamp")[0]
    if light.startswith("#") or not light.endswith("lantern"):
        reject("error.mine.lamp")
    if not [d for d in DIRS if site.count("main_" + d) > 0]:
        reject("error.mine.directions")
    m, k, head_parts, head_boxes = head(site)
    y0 = k["y0"]
    n = k["n"]
    floor = y0 - drop(n)
    shaft = [[walk(i)[0], y0 - drop(i) + 1, walk(i)[1], 1, 3, 1] for i in range(n + 1)]
    la, lb = LOOP[(n + 1) % LAP]
    shaft.append([la, floor + 1, lb, 1, 3, 1])
    shaft += rows(floor + 1, y0 - floor)
    roads = passages(site, m, k) + junction(k)
    boxes = head_boxes + shaft + roads
    chased = chase(site, m, boxes) if site.flag("chase") else []
    dug = dug_of(boxes + chased)
    m["roads"] = group([scoped(m, bx) for bx in boxes + chased])
    parts = guarded(site, m, boxes + chased, shaft + roads + chased, dug, y0)
    parts += head_parts
    flight_parts, _ = flight(site, m, k, dug)
    parts += flight_parts
    parts += hub(site, m, k)
    for d in range(4):
        if length(site, d) > 0:
            parts += drift(site, m, k, d, dug)
    return parts
