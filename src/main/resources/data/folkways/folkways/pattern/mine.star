accepts = ["zone"]
knobs = [
    choice("level", ["shallow", "copper", "iron", "gold", "diamond"], default = "iron",
        icon = "minecraft:iron_pickaxe"),
    count("branch", 4, 32, default = 12, icon = "minecraft:rail"),
    count("spacing", 2, 6, default = 3, icon = "minecraft:stone"),
    count("pairs", 1, 24, default = 6, icon = "minecraft:stone_pickaxe"),
    flag("chase", default = True, icon = "minecraft:raw_iron"),
    items("timber", default = ["minecraft:spruce_log"], icon = "minecraft:spruce_log"),
    items("planks", default = ["minecraft:spruce_planks"], icon = "minecraft:spruce_planks"),
    items("stairs", default = ["minecraft:spruce_stairs"], icon = "minecraft:spruce_stairs"),
    items("rail", default = ["minecraft:spruce_fence"], icon = "minecraft:spruce_fence"),
    items("gate", default = ["minecraft:spruce_fence_gate"], icon = "minecraft:spruce_fence_gate"),
    items("lamp", default = ["minecraft:lantern"], icon = "minecraft:lantern"),
    items("seal", default = ["minecraft:cobblestone"], icon = "minecraft:cobblestone"),
]

LEVELS = {"copper": 48, "iron": 16, "gold": -16, "diamond": -59}
SHALLOW = 16
FALLING = "minecraft:gravel|minecraft:sand|minecraft:red_sand|minecraft:suspicious_sand|minecraft:suspicious_gravel"
ORES = "#c:ores"
FLIGHT = 24
CHASE = 24
VEIN = 8
FLOODED = 4
COMPASS = {(1, 0): "east", (-1, 0): "west", (0, 1): "north", (0, -1): "south"}
STEP = "minecraft:oak_stairs"
GATE = "minecraft:oak_fence_gate"
AROUND = [(1, 0), (-1, 0), (0, 1), (0, -1)]
# One quarter of the stair's round, from the centre (4, 4): a flat landing, three steps down, then a
# landing that zigzags round the bend. Turned four times it closes a 32-cell lap of the 9-wide shaft.
QUARTER = [(-2, -4), (-1, -4), (0, -4), (1, -4), (2, -4), (2, -3), (3, -3), (3, -2)]
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

def flat(boxes):
    out = []
    for bx in boxes:
        out.extend(bx)
    return out

def unflat(values):
    return [values[k:k + 6] for k in range(0, len(values), 6)]

def cells_of(bx):
    return [(bx[0] + da, bx[1] + dy, bx[2] + db)
            for dy in range(bx[4]) for db in range(bx[5]) for da in range(bx[3])]

def depth_of(site, y0):
    level = site.choice("level")
    wanted = SHALLOW if level == "shallow" else site.altitude + y0 - LEVELS[level]
    if wanted >= 14:
        return LAP * (wanted // FALL) + 8 * (wanted % FALL // 3) + 1 + wanted % 3
    fail("The chosen level lies above or too near the mine mouth / 所选矿层高于矿口或离矿口太近")

def flooded(site, m, boxes):
    found = []
    for bx in boxes:
        if terrain(scoped(m, bx), of = "fluid") == None:
            continue
        for c in cells_of(bx):
            if site.matches(spot(m, c[0], c[1], c[2]), "fluid"):
                found.append(site.altitude + c[1])
    if len(found) > FLOODED:
        top = max(found)
        fail("Water or lava floods the way on from height %d; drain it and the mine goes on" % top
            + " / 前方高度 %d 起被水或岩浆淹没，排干后矿井会继续挖" % top)

def guarded(site, m, boxes):
    flooded(site, m, boxes)
    seal = []
    prop = []
    dig = []
    for bx in boxes:
        scope = scoped(m, bx)
        wet = terrain(scope.outset(x = 1, y = 1, z = 1), of = "fluid")
        if wet != None:
            seal.append(wet)
        loose = terrain(scoped(m, [bx[0], bx[1] + bx[4], bx[2], bx[3], 1, bx[5]]), of = FALLING)
        if loose != None:
            prop.append(loose)
        solid = terrain(scope, of = "!air")
        if solid != None:
            dig.append(solid)
    parts = []
    if seal:
        parts.append(part("seal", group(seal), site.items("seal")))
    if prop:
        parts.append(part("prop", group(prop), site.items("seal")))
    if dig:
        parts.append(clear("dig", group(dig)))
    return parts

def chase(site, m, boxes):
    dug = dug_of(boxes)
    looked = {}
    found = []
    for c in dug:
        if len(found) >= CHASE:
            break
        if not site.matches(spot(m, c[0], c[1], c[2]), "air"):
            continue
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
        fail("No ground under the mine mouth / 矿口下方没有地面")
    if ground.water > 0.25:
        fail("The mine mouth lies in water / 矿口位于水中")
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
    parts = guarded(site, m, boxes)
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
    return grown(parts, keep = {"ox": m["ox"], "oz": m["oz"], "y0": y0, "n": n, "i": 0, "t": 0,
        "dug": flat(boxes)})

def flight(site, m, k, chased):
    y0 = k["y0"]
    n = k["n"]
    i0 = k["i"]
    i1 = min(i0 + FLIGHT - 1, n)
    floor = y0 - drop(n)
    boxes = []
    planks = []
    posts = []
    built = []
    lined = []
    nooks = []
    for i in range(i0, i1 + 1):
        a, b, side, q = walk(i)
        y = y0 - drop(i)
        boxes.append([a, y + 1, b, 1, 3, 1])
        h = min(4, y0 - y)
        for o in AROUND:
            if h > 0 and not disc(a + o[0], b + o[1]):
                bare = terrain(room(m, a + o[0], y, b + o[1], h = h), of = "air")
                if bare != None:
                    lined.append(bare)
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
    # The well in the middle goes down with the stair, one band of layers a round, down to the floor.
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
    dug = dug_of(boxes + well + chased)
    for nook in nooks:
        built.extend(nestled(site, m, nook, dug))
    parts = guarded(site, m, boxes + well + chased)
    if lined:
        parts.append(part("lining", group(lined), site.items("seal")))
    parts.append(part("landing", group(planks), site.items("planks")))
    if posts:
        parts.append(part("post", group(posts), site.items("timber"), orient = "y"))
    if rails:
        parts.append(fence("rail", site, m, rails.keys()))
    return grown(parts + built, keep = {"ox": k["ox"], "oz": k["oz"], "y0": y0, "n": n, "i": i1 + 1, "t": 0,
        "dug": flat(boxes + well + chased)})

def drift(site, m, k, chased):
    y0 = k["y0"]
    n = k["n"]
    yf = y0 - drop(n)
    length = site.count("branch")
    period = site.count("spacing") + 1
    total = period * site.count("pairs") + 2
    a, b, side, j = walk(n)
    w = (a, b)
    out = OUT[side]
    lat = TRAVEL[side]
    t0 = k["t"] + 1
    t1 = min(k["t"] + 2 * period, total)
    boxes = [span(w, out, lat, t0, t1, -1, 1, yf + 1, 3)]
    posts = []
    built = []
    nooks = []
    for t in range(t0, t1 + 1):
        if t == 1 or t % period == (1 + period // 2) % period:
            for s in [-2, 2]:
                posts.append(scoped(m, span(w, out, lat, t, t, s, s, yf + 1, 3)))
            cap = span(w, out, lat, t, t, -1, 1, yf + 4, 1)
            built.append(part("cap", scoped(m, cap), site.items("timber"), orient = "along"))
            nooks.append([span(w, out, lat, t + dt, t + dt, s, s, yf + 2, 1) for dt in [-1, 1] for s in [2, -2]])
        if t > 1 and t % period == 1:
            for sign in [-1, 1]:
                boxes.append(span(w, out, lat, t, t, 2 * sign, (length + 1) * sign, yf + 1, 2))
                nooks.append([span(w, out, lat, t + dt, t + dt, (length + 1 + ds) * sign, (length + 1 + ds) * sign,
                    yf + 1, 1) for dt, ds in [(0, 1), (1, 0), (-1, 0)]])
                if length > 20:
                    niche = span(w, out, lat, t + 1, t + 1, (length // 2 + 1) * sign, (length // 2 + 1) * sign,
                        yf + 1, 1)
                    boxes.append(niche)
                    built.append(lamp(site, m, niche[0], niche[1], niche[2]))
    dug = dug_of(boxes + chased)
    for nook in nooks:
        built.extend(nestled(site, m, nook, dug))
    parts = guarded(site, m, boxes + chased)
    if posts:
        parts.append(part("post", group(posts), site.items("timber"), orient = "y"))
    return grown(parts + built, keep = {"ox": k["ox"], "oz": k["oz"], "y0": y0, "n": n, "i": k["i"], "t": t1,
        "dug": flat(boxes + chased)})

def grow(site):
    for key in ["timber", "planks", "stairs", "rail", "gate", "lamp", "seal"]:
        if not site.items(key):
            fail("Choose material for %s / 请为 %s 选择材料" % (key, key))
    light = site.items("lamp")[0]
    if light.startswith("#") or not light.endswith("lantern"):
        fail("The lamp must be a lantern / 灯具须为灯笼")
    k = site.kept
    if not k:
        return head(site)
    m = frame(site, k["ox"], k["oz"])
    chased = chase(site, m, unflat(k["dug"])) if site.flag("chase") else []
    if k["i"] <= k["n"]:
        return flight(site, m, k, chased)
    if k["t"] < (site.count("spacing") + 1) * site.count("pairs") + 2:
        return drift(site, m, k, chased)
    if not chased:
        return []
    return grown(guarded(site, m, chased), keep = {"ox": k["ox"], "oz": k["oz"], "y0": k["y0"], "n": k["n"],
        "i": k["i"], "t": k["t"], "dug": flat(chased)})
