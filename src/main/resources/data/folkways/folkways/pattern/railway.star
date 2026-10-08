accepts = ["path"]
knobs = [
    items("track", default = ["create:track"], icon = "create:track"),
    items("ballast", default = ["minecraft:tuff"], icon = "minecraft:tuff"),
    items("bed", default = ["minecraft:cobblestone"], icon = "minecraft:cobblestone"),
    items("masonry", default = ["minecraft:bricks"], icon = "minecraft:bricks"),
    items("accent", default = ["minecraft:polished_andesite"], icon = "minecraft:polished_andesite"),
]

AIR = "minecraft:air"
FULL = "minecraft:stone_bricks"
FAMILY = ["minecraft:stone_bricks", "minecraft:stone_brick_slab", "minecraft:stone_brick_stairs",
    "minecraft:stone_brick_wall"]
KNOB = {"ballast": "ballast", "bed": "bed", "masonry": "masonry", "accent": "accent"}

HEAD = 4
COVER = 2
DEEP = 5
SLOPE = 9
SPAN = 8
PORTAL = 6
LONGEST = 30
DIAGONAL = 1.4142135

COMPASS = {(0, -1): "north", (0, 1): "south", (1, 0): "east", (-1, 0): "west"}
BENDS = {(1, 0): (10, 10), (0, 1): (7, 7), (1, 1): (10, 7), (1, 2): (7, 10)}

def size(value):
    return -value if value < 0 else value

def sign(value):
    if value > 0:
        return 1
    if value < 0:
        return -1
    return 0

def nearest(value):
    return int(value + 0.5) if value >= 0 else -int(-value + 0.5)

def lower(value):
    whole = int(value)
    return whole - 1 if value < whole else whole

def root(value):
    if value <= 0:
        return 0.0
    guess = value if value > 1 else 1.0
    for _ in range(20):
        guess = (guess + value / guess) / 2
    return guess

def slab(half = "bottom"):
    return "minecraft:stone_brick_slab[type=%s]" % half

def stair(way, half = "bottom"):
    return "minecraft:stone_brick_stairs[facing=%s,half=%s]" % (COMPASS[way], half)

def heading(a, b):
    dx = b.x - a.x
    dz = b.z - a.z
    across = size(dx)
    along = size(dz)
    if across > 0 and along > 0 and across * 2 >= along and along * 2 >= across:
        return sign(dx), sign(dz), (across + along + 1) // 2, size(across - along)
    if across > along:
        return sign(dx), 0, across, along
    return 0, sign(dz), along, across

def bend(one, other):
    if one["sx"] == other["sx"] and one["sz"] == other["sz"]:
        return 0, 0
    dot = one["sx"] * other["sx"] + one["sz"] * other["sz"]
    if dot < 0:
        fail("A railway cannot turn back on itself; bend at most 90° at a point" +
            " / 铁路不能折返：每个转折点最多转 90°")
    diagonal = one["sx"] != 0 and one["sz"] != 0
    if dot == 0:
        return BENDS[(0, 1)] if diagonal else BENDS[(1, 0)]
    return BENDS[(1, 2)] if diagonal else BENDS[(1, 1)]

def climbs(rise, unit):
    rise = size(rise)
    need = rise * 4 if rise < 4 else rise * 3
    need = max(need, 6)
    return int(need / unit + 0.999)

def grades(leg, first, last):
    dy = leg["y1"] - leg["y0"]
    if dy == 0:
        return []
    unit = DIAGONAL if leg["diagonal"] else 1.0
    room = last - first - 2
    longest = int(LONGEST / unit)
    way = sign(dy)
    for count in range(1, 9):
        rises = [size(dy) // count] * count
        for extra in range(size(dy) - (size(dy) // count) * count):
            rises[extra] += 1
        rises = [way * rise for rise in rises]
        span = min(longest, (room - 2 * (count - 1)) // count)
        if span < 1 or span < max([climbs(rise, unit) for rise in rises]):
            continue
        used = count * span + 2 * (count - 1)
        at = first + 1 + (room - used) // 2
        found = []
        height = leg["y0"]
        for rise in rises:
            found.append((at, at + span, height, height + rise))
            height += rise
            at += span + 2
        return found
    fail("Too steep to climb here: lengthen the run or ease the heights" +
        " / 坡度过陡：请加长线路或减小高差")

def route(site):
    points = site.path.points
    legs = []
    x, y, z = points[0].x, points[0].y, points[0].z
    for index in range(1, len(points)):
        sx, sz, steps, off = heading(point(x, y, z), points[index])
        if off > 2 or steps == 0:
            fail("Each stretch of track runs straight along an axis or a 45° diagonal; line its ends up" +
                " / 每段轨道只能沿坐标轴或 45° 斜线笔直铺设：请对齐两端")
        legs.append({"x": x, "z": z, "sx": sx, "sz": sz, "n": steps, "y0": y, "y1": points[index].y,
            "diagonal": sx != 0 and sz != 0, "head": 0, "tail": steps})
        x, y, z = x + steps * sx, points[index].y, z + steps * sz
    bends = []
    for index in range(len(legs) - 1):
        one = legs[index]
        other = legs[index + 1]
        into, out = bend(one, other)
        one["tail"] = one["n"] - into
        other["head"] = out
        bends.append((index, into, out))
    total = 0
    for leg in legs:
        if leg["tail"] - leg["head"] < 1:
            fail("Too tight a bend: leave about ten blocks of straight track either side of each turn" +
                " / 转弯过急：每个转折点两侧请各留约十格直线")
        leg["climbs"] = grades(leg, leg["head"], leg["tail"])
        total += leg["n"]
    if total < 4 or total > 160:
        fail("A railway runs 4–160 blocks / 铁路全长须为 4–160 格")
    return legs, bends

def level(leg, s):
    height = leg["y0"]
    for first, last, low, high in leg["climbs"]:
        if s <= first:
            return height
        if s < last:
            return low + (high - low) * (s - first) / (last - first)
        height = high
    return height

def samples(legs, bends):
    found = []
    walked = 0.0
    for index in range(len(legs)):
        leg = legs[index]
        unit = DIAGONAL if leg["diagonal"] else 1.0
        head = leg["head"]
        tail = leg["tail"]
        for half in range(2 * head, 2 * tail + 1):
            s = half / 2.0
            found.append({"x": leg["x"] + s * leg["sx"], "z": leg["z"] + s * leg["sz"], "f": level(leg, s),
                "dx": leg["sx"] / unit, "dz": leg["sz"] / unit, "s": walked})
            walked += 0.5 * unit
        if index < len(bends):
            other = legs[index + 1]
            ax = leg["x"] + tail * leg["sx"]
            az = leg["z"] + tail * leg["sz"]
            bx = other["x"] + other["head"] * other["sx"]
            bz = other["z"] + other["head"] * other["sz"]
            chord = root((bx - ax) * (bx - ax) + (bz - az) * (bz - az))
            handle = chord / 3.0
            ux = leg["sx"] / unit
            uz = leg["sz"] / unit
            vunit = DIAGONAL if other["diagonal"] else 1.0
            vx = other["sx"] / vunit
            vz = other["sz"] / vunit
            px, pz = ax + ux * handle, az + uz * handle
            qx, qz = bx - vx * handle, bz - vz * handle
            count = int(chord * 2) + 1
            lastx, lastz = ax, az
            for step in range(1, count):
                t = step / float(count)
                u = 1.0 - t
                x = ax * u * u * u + px * 3 * u * u * t + qx * 3 * u * t * t + bx * t * t * t
                z = az * u * u * u + pz * 3 * u * u * t + qz * 3 * u * t * t + bz * t * t * t
                dx = x - lastx
                dz = z - lastz
                length = root(dx * dx + dz * dz)
                walked += length
                found.append({"x": x, "z": z, "f": float(leg["y1"]), "dx": dx / length, "dz": dz / length,
                    "s": walked})
                lastx, lastz = x, z
            walked += root((bx - lastx) * (bx - lastx) + (bz - lastz) * (bz - lastz)) - 0.5 * vunit
    return found

def runs(kinds):
    found = []
    start = 0
    for i in range(1, len(kinds) + 1):
        if i == len(kinds) or kinds[i] != kinds[start]:
            found.append((kinds[start], start, i - 1))
            start = i
    return found

def classify(site, line):
    kinds = []
    for sample in line:
        x = nearest(sample["x"])
        z = nearest(sample["z"])
        floor = lower(sample["f"] + 0.25)
        ground = site.ground(x, z)
        water = site.highest(x, z, "fluid")
        sample["ground"] = ground
        if (water != None and (ground == None or water > ground)) or ground == None or ground < floor - DEEP:
            kinds.append("bridge")
        elif ground >= floor + HEAD + 1 + COVER:
            kinds.append("tunnel")
        else:
            kinds.append("open")
    for kind, first, last in runs(kinds):
        if kind != "open" and last - first < (11 if kind == "tunnel" else 5):
            for i in range(first, last + 1):
                kinds[i] = "open"
    found = runs(kinds)
    for index in range(1, len(found) - 1):
        kind, first, last = found[index]
        if kind == "open" and last - first < 5 and found[index - 1][0] == found[index + 1][0]:
            for i in range(first, last + 1):
                kinds[i] = found[index - 1][0]
    for i in range(len(line)):
        line[i]["kind"] = kinds[i]
    for kind, first, last in runs(kinds):
        for i in range(first, last + 1):
            line[i]["portal"] = kind == "tunnel" and (i - first < 2 or last - i < 2)
            line[i]["abut"] = kind == "open" and ((first > 0 and i - first < 2) or (last < len(line) - 1 and last - i < 2))
        report(kind, line[last]["s"] - line[first]["s"] + 0.5)
    return runs(kinds)

def arches(site, line, found):
    for kind, first, last in found:
        if kind != "bridge":
            continue
        start = line[max(0, first - 1)]["s"]
        end = line[min(len(line) - 1, last + 1)]["s"]
        gap = end - start
        spans = max(1, int((gap + SPAN - 1) / SPAN))
        piers = [start + gap * m / spans for m in range(1, spans)]
        faces = [(start - 1.0, start)] + [(p - 1.0, p + 1.0) for p in piers] + [(end, end + 1.0)]
        crown = min([line[i]["f"] for i in range(first, last + 1)]) - 2
        crown = lower(crown + 0.25)
        below = crown - 64
        for i in range(first, last + 1):
            x = nearest(line[i]["x"])
            z = nearest(line[i]["z"])
            for top in [site.ground(x, z), site.highest(x, z, "fluid")]:
                if top != None and top > below:
                    below = top
        for i in range(first, last + 1):
            s = line[i]["s"]
            line[i]["soffit"] = None
            for m in range(len(faces) - 1):
                left = faces[m][1]
                right = faces[m + 1][0]
                if s <= left or s >= right:
                    continue
                reach = (right - left) / 2.0
                middle = (left + right) / 2.0
                rise = min(4.0, reach, crown - below - 1.0)
                if rise < 1:
                    line[i]["soffit"] = (crown + 1.0, False)
                else:
                    radius = (reach * reach + rise * rise) / (2 * rise)
                    d = s - middle
                    line[i]["soffit"] = (crown - radius + root(max(0.0, radius * radius - d * d)), rise >= 2)

def put(out, x, y, z, role, state):
    out[point(x, y, z)] = (role, state)

def dig(site, out, x, y, z):
    at = point(x, y, z)
    if not site.matches(at, "air|fluid"):
        out[at] = ("cut", AIR)

def upto(out, x, z, bottom, top, role, state, half):
    halves = nearest(top * 2)
    for y in range(bottom, halves // 2):
        put(out, x, y, z, role, state)
    if halves % 2 == 1:
        put(out, x, halves // 2, z, role, half)
        return halves // 2 + 1
    return halves // 2

def reach(sample):
    kind = sample["kind"]
    if kind == "bridge":
        return 3.6
    if kind == "tunnel":
        return PORTAL + 0.5 if sample["portal"] else 3.5
    ground = sample["ground"]
    depth = SLOPE if ground == None else min(SLOPE, size(ground - lower(sample["f"] + 0.25)) + 2)
    return 3.5 + depth

def raster(line):
    best = {}
    for index in range(len(line)):
        sample = line[index]
        nx = -sample["dz"]
        nz = sample["dx"]
        wide = reach(sample)
        for half in range(-int(wide * 2), int(wide * 2) + 1):
            t = half / 2.0
            x = nearest(sample["x"] + nx * t)
            z = nearest(sample["z"] + nz * t)
            dx = x - sample["x"]
            dz = z - sample["z"]
            far = dx * dx + dz * dz
            if far > wide * wide:
                continue
            key = (x, z)
            if key not in best or far < best[key][0]:
                best[key] = (far, index)
    return best

def inward(sample, x, z):
    dx = sample["x"] - x
    dz = sample["z"] - z
    if size(dx) > 1.5 * size(dz):
        return (sign(dx), 0)
    if size(dz) > 1.5 * size(dx):
        return (0, sign(dz))
    return None

def slope(site, out, x, z, q, f, way, ground):
    rise = q - 3
    fill = f - 1 - rise
    cut = f - 1 + rise
    if ground < fill:
        for y in range(ground + 1, fill):
            put(out, x, y, z, "bed", FULL)
        put(out, x, fill, z, "bed", FULL if way == None else stair(way))
    elif ground > cut or (rise == 1 and ground == cut):
        for y in range(cut + 1, ground + 1):
            dig(site, out, x, y, z)
        put(out, x, cut, z, "bed", FULL if way == None else stair((-way[0], -way[1])))

def open_column(site, out, x, z, q, height, abut, ground):
    core = "masonry" if abut else "bed"
    if q <= 1:
        top = upto(out, x, z, lower(height), height + 1, "ballast", FULL, slab())
    elif q == 2:
        top = upto(out, x, z, lower(height), height + 0.5, "ballast", FULL, slab())
    else:
        top = upto(out, x, z, lower(height) - 1, height, "accent", FULL, slab())
    for y in range(ground + 1, lower(height) - (1 if q >= 3 else 0)):
        put(out, x, y, z, core, FULL)
    for y in range(top, max(ground, lower(height) + HEAD) + 1):
        dig(site, out, x, y, z)

def bridge_column(site, out, sample, x, z, q, ground):
    floor = lower(sample["f"] + 0.25)
    if q <= 1:
        put(out, x, floor, z, "ballast", FULL)
    elif q == 2:
        put(out, x, floor, z, "ballast", slab())
    else:
        put(out, x, floor, z, "masonry", FAMILY[3])
    put(out, x, floor - 1, z, "accent" if q >= 3 else "masonry", FULL)
    if q <= 2:
        for y in range(floor + 1, floor + HEAD + 1):
            dig(site, out, x, y, z)
    if ground == None:
        ground = floor - 64
    if sample["soffit"] == None:
        for y in range(ground + 1, floor - 1):
            put(out, x, y, z, "masonry", FULL)
        return
    soffit, ringed = sample["soffit"]
    base = lower(soffit)
    part = soffit - base
    ring = "accent" if ringed else "masonry"
    if part >= 0.75:
        base += 1
    elif part >= 0.25:
        put(out, x, base, z, ring, slab("top"))
        base += 1
        ring = "masonry"
    for y in range(base, floor - 1):
        put(out, x, y, z, ring if y == base else "masonry", FULL)

def bore(q, lift):
    if lift >= 1 and lift <= 4:
        return q <= 2
    return lift == 5 and q <= 1

def lining(way, q, lift):
    if q == 3 and lift <= 5:
        return FULL
    if q == 2 and lift == 5:
        return slab("top") if way == None else stair((-way[0], -way[1]), "top")
    if (q <= 1 and lift == 6) or (q == 2 and lift == 6):
        return FULL
    return None

def ring(q, lift):
    return (q == 3 and lift >= 0 and lift <= 5) or (q, lift) in [(2, 5), (2, 6), (1, 6), (0, 6), (0, 7)]

def tunnel_column(site, out, sample, x, z, q, way):
    floor = lower(sample["f"] + 0.25)
    portal = sample["portal"]
    if q <= 1:
        put(out, x, floor, z, "ballast", FULL)
    elif q == 2:
        put(out, x, floor, z, "ballast", slab())
    top = PORTAL + 2 if portal else 6
    for lift in range(-1, top + 1):
        y = floor + lift
        if lift > 0 and bore(q, lift):
            dig(site, out, x, y, z)
            continue
        if lift == 0 and q <= 2:
            continue
        if portal:
            if lift == top:
                put(out, x, y, z, "accent", slab())
            elif ring(q, lift):
                state = lining(way, q, lift)
                put(out, x, y, z, "accent", state if state != None and "stairs" in state else FULL)
            else:
                put(out, x, y, z, "masonry", FULL)
            continue
        if lift < 0 and q < 3:
            continue
        state = lining(way, q, lift)
        if state != None:
            put(out, x, y, z, "masonry", state)

def rails(site, legs, bends):
    material = site.items("track")
    if not material:
        fail("Choose the track to lay / 请选择要铺设的轨道")
    laid = {}
    joints = []
    ends = []
    for leg in legs:
        sx, sz = leg["sx"], leg["sz"]
        shape = ("pd" if sx * sz > 0 else "nd") if leg["diagonal"] else ("zo" if sz != 0 else "xo")
        state = "%s[shape=%s]" % (material[0], shape)
        inside = []
        for first, last, low, high in leg["climbs"]:
            inside.extend(range(first + 1, last))
            joints.append(joint(point(leg["x"] + first * sx, low + 1, leg["z"] + first * sz),
                point(leg["x"] + last * sx, high + 1, leg["z"] + last * sz)))
        for i in range(leg["head"], leg["tail"] + 1):
            if i not in inside:
                laid[point(leg["x"] + i * sx, int(level(leg, i)) + 1, leg["z"] + i * sz)] = state
        ends.append((point(leg["x"] + leg["head"] * sx, leg["y0"] + 1, leg["z"] + leg["head"] * sz),
            point(leg["x"] + leg["tail"] * sx, leg["y1"] + 1, leg["z"] + leg["tail"] * sz)))
    for index, into, out in bends:
        joints.append(joint(ends[index][1], ends[index + 1][0]))
    return laid, joints

def draw(site):
    for key in ["ballast", "bed", "masonry", "accent"]:
        if not site.items(key):
            fail("Choose material for %s / 请选择材料" % key)
    legs, bends = route(site)
    line = samples(legs, bends)
    found = classify(site, line)
    arches(site, line, found)
    out = {}
    for key, best in raster(line).items():
        x, z = key
        sample = line[best[1]]
        far = root(best[0])
        q = int(far + 0.5)
        way = inward(sample, x, z) if far > 0.3 else None
        kind = sample["kind"]
        ground = site.ground(x, z)
        if kind == "bridge":
            bridge_column(site, out, sample, x, z, q, ground)
        elif kind == "tunnel":
            if q > 3 and (not sample["portal"] or ground == None or ground < lower(sample["f"] + 0.25) + 3):
                continue
            tunnel_column(site, out, sample, x, z, q, way)
        else:
            f = lower(sample["f"] + 0.25)
            if ground == None:
                ground = f - DEEP - SLOPE
            if q <= 3:
                open_column(site, out, x, z, q, sample["f"], sample["abut"], ground)
            else:
                slope(site, out, x, z, q, f, way, ground)
    laid, joints = rails(site, legs, bends)
    for at, state in laid.items():
        out[at] = ("track", state)
    grouped = {}
    for at, made in out.items():
        role, state = made
        if role not in grouped:
            grouped[role] = {}
        grouped[role][at] = state
    parts = []
    for role in ["cut", "bed", "masonry", "accent", "ballast", "track"]:
        if role not in grouped:
            continue
        swap = {}
        if role in KNOB:
            for block in FAMILY:
                swap[block] = site.items(KNOB[role])
        parts.append(blocks(role, grouped[role], swap = swap))
    return parts + joints
