accepts = ["path"]
phases = ["frame", "roof", "dressing"]
knobs = [
    count("width", 3, 7, default = 5, icon = "minecraft:stone_brick_slab"),
    count("bay", 3, 6, default = 4, icon = "minecraft:stripped_spruce_log"),
    count("height", 3, 5, default = 3, icon = "minecraft:ladder"),
    choice("roof", ["gable", "shed"], default = "gable", icon = "minecraft:deepslate_tile_stairs"),
    choice("pitch", ["low", "steep"], default = "low", icon = "minecraft:deepslate_tile_slab"),
    flag("pavilions", default = True, icon = "minecraft:deepslate_tiles"),
    flag("railing", default = True, icon = "minecraft:spruce_fence"),
    flag("lights", default = True, icon = "minecraft:lantern"),
    items("floor", default = ["minecraft:stone_bricks", "minecraft:polished_andesite"], icon = "minecraft:stone_bricks"),
    items("footing", default = ["minecraft:cobblestone"], icon = "minecraft:cobblestone"),
    items("pillar", default = ["minecraft:stripped_spruce_log"], icon = "minecraft:stripped_spruce_log"),
    items("beam", default = ["minecraft:spruce_planks"], icon = "minecraft:spruce_planks"),
    items("tiles", default = ["minecraft:deepslate_tiles"], icon = "minecraft:deepslate_tiles"),
    items("ridge", default = ["minecraft:polished_deepslate"], icon = "minecraft:polished_deepslate"),
    items("fence", default = ["minecraft:spruce_fence"], icon = "minecraft:spruce_fence"),
    items("lamp", default = ["minecraft:lantern"], icon = "minecraft:lantern"),
]

# A covered walk along a drawn line. Each straight stretch is split into bays of near the dialled
# length; each bay stands level on its own ground, at most one cell above or below the bay before it,
# so on a slope the walk climbs in steps and its roof steps with it. Where the line bends (and at its
# ends, when asked) a square pavilion with a hipped roof takes the turn.

SOLIDS = "solid|replaceable|foliage"

def blend(listed):
    return mix({listed[i]: len(listed) - i for i in range(len(listed))})

def size(value):
    return -value if value < 0 else value

def pitch(site):
    return (1, 2) if site.choice("pitch") == "low" else (1, 1)

def courses(across, rise, run):
    return (across // 2 * rise + run - 1) // run + 2

def level_of(site, scope):
    return site.settle(scope, at = "median").origin.y

# Ground under a room standing level at its own origin: dug clear above, footing filled in below,
# and a floor course at y = -1. A step up or down from the room before is ramped over its first two rows.
# A turned box leaves cells out where a cornerwise line crosses them, so a room that does not lie
# square to the world is swept along its middle line instead, as a road is.
def slab(room, y, tall):
    if room.square:
        return box(room.at(y = y).sized(height = tall))
    a = room.sized(depth = 1).center()
    b = room.at(z = room.depth - 1).sized(depth = 1).center()
    return sweep([a, b], width = room.width, height = tall, y = a.y + y)

def groundwork(site, room, tall, step):
    out = []
    dug = terrain(room.sized(height = tall), of = SOLIDS) if room.square else slab(room, 0, tall)
    if dug != None:
        out.append(clear("dig", dug))
    out.append(part("floor", slab(room, -1, 1), blend(site.items("floor")), labels = ["floor"]))
    below = fill_below(slab(room, -1, 1))
    if below != None:
        out.append(part("footing", below, site.items("footing")))
    if step != 0 and room.depth > 2:
        # Up or down from the floor before, over the first two rows, a slab at a time.
        a = room.sized(depth = 1).center()
        b = room.at(z = 2).sized(depth = 1).center()
        own = a.y - 1
        if step > 0:
            out.append(clear("ramp", sweep([[a.x, own, a.z], [b.x, own, b.z]], width = room.width), over = ["floor"]))
        ramp = sweep([[a.x, own - step, a.z], [b.x, own, b.z]], width = room.width)
        out.append(part("ramp", ramp, blend(site.items("floor")), fit = "layered", labels = ["floor"]))
    return out

def posts_of(room, tall, ends):
    corners = []
    for edge in comp(room.sized(height = tall), "edges"):
        if edge.name in ends:
            corners.append(edge)
    return corners

def bay(site, room, step, last):
    report("bays")
    tall = site.count("height")
    out = groundwork(site, room, tall + 2, step)
    ends = ["back-left", "back-right"] + (["front-left", "front-right"] if last else [])
    posts = posts_of(room, tall, ends)
    out.append(part("pillar", group(posts), site.items("pillar"), orient = "y", labels = ["pillar"]))
    top = room.at(y = tall).sized(height = 1)
    out.append(part("beam", group(comp(top, "left") + comp(top, "right")), site.items("beam"), orient = "z"))
    out.append(part("tie", group(comp(top, "back") + (comp(top, "front") if last else [])), site.items("beam"),
        orient = "x"))
    if site.flag("railing"):
        rail = room.sized(height = 1)
        sides = group(comp(rail, "left") + comp(rail, "right"))
        out.append(part("rail", subtract(sides, group(posts)), site.items("fence"), labels = ["rail"]))
    out.append(ROOF(room.at(y = tall + 1)))
    return out

def roof(site, over):
    rise, run = pitch(site)
    eaves = over.outset(x = 1)
    if site.choice("roof") == "gable":
        eaves = eaves.sized(height = courses(eaves.width, rise, run))
        shape = gable(eaves, ridge = "z", rise = rise, run = run)
        return [part("tiles", shape, site.items("tiles"), fit = "stepped", labels = ["roof"]),
                part("ridge", group(comp(shape, "ridge")), site.items("ridge"), labels = ["roof"])]
    turned = eaves.rotate(1)
    turned = turned.sized(height = courses(2 * turned.depth, rise, run))
    return [part("tiles", shed(turned, rise = rise, run = run), site.items("tiles"), fit = "stepped",
        labels = ["roof"])]

def pavilion(site, room):
    report("pavilions")
    tall = site.count("height") + 1
    out = groundwork(site, room, tall + 2, 0)
    posts = posts_of(room, tall, ["front-left", "front-right", "back-left", "back-right"])
    out.append(part("pillar", group(posts), site.items("pillar"), orient = "y", labels = ["pillar"]))
    out.append(part("beam", group(comp(room.at(y = tall).sized(height = 1), "sides")), site.items("beam")))
    rise, run = pitch(site)
    eaves = room.at(y = tall + 1).outset(x = 1, z = 1)
    eaves = eaves.sized(height = courses(min(eaves.width, eaves.depth), rise, run))
    out.append(part("tiles", hip(eaves, rise = rise, run = run), site.items("tiles"), fit = "stepped",
        labels = ["roof"]))
    return out

# A lantern set on the railing, only where nothing earlier stands.
def lamp(site, cell):
    if overlaps(scope = cell, by = "parts") or not overlaps(scope = cell.at(y = -1), by = "rail"):
        return []
    report("lamps")
    return [part("lamp", box(cell), site.items("lamp"))]

def stretch(site, run, start, end):
    bays = split(run, "z", ["~%d*" % site.count("bay")], names = ["bay"],
        emit = "bays" if run.square else None)
    levels = [level_of(site, one) for one in bays]
    for _ in range(2):
        for i in range(len(levels)):
            before = start if i == 0 else levels[i - 1]
            if before != None:
                levels[i] = max(before - 1, min(before + 1, levels[i]))
        for i in range(len(levels) - 1, -1, -1):
            after = end if i == len(levels) - 1 else levels[i + 1]
            if after != None:
                levels[i] = max(after - 1, min(after + 1, levels[i]))
    if (start != None and size(levels[0] - start) > 1) or (end != None and size(levels[-1] - end) > 1):
        fail("Too steep for one stretch: add a point or lengthen it / 这一段太陡：请加一个点或拉长这一段")
    out = []
    for i in range(len(bays)):
        before = start if i == 0 else levels[i - 1]
        step = 0 if before == None else levels[i] - before
        if step != 0:
            report("steps")
        room = bays[i].at(y = levels[i] - bays[i].origin.y)
        out.append(BAY(room, step, i == len(bays) - 1))
        # Called from here rather than from the bay, so the lamp sees the bay's railing: a query never
        # sees what the rules above it drew.
        if site.flag("railing") and site.flag("lights") and i % 2 == 0:
            middle = room.at(z = room.depth // 2).sized(depth = 1, height = 1)
            out.append(LAMP(comp(middle, "left")[0].at(y = 1)))
    return out

def tallied(site, scope):
    report("framed", tally("bay"))
    return []

ROOF = rule("roof", roof, phase = "roof", label = "roof")
BAY = rule("bay", bay, phase = "frame", label = "bay")
PAVILION = rule("pavilion", pavilion, phase = "frame", priority = 1, label = "pavilion")
STRETCH = rule("stretch", stretch, phase = "frame", priority = 2)
LAMP = rule("lamp", lamp, phase = "dressing")
TALLY = rule("tally", tallied, phase = "dressing")

def draw(site):
    width = site.count("width")
    for key in ["floor", "footing", "pillar", "beam", "tiles", "ridge", "fence", "lamp"]:
        if not site.items(key):
            fail("Choose material for %s / 请选择材料" % key)
    runs = site.path.stretches
    length = 0
    for one in runs:
        length += one.depth
    if length < 6 or length > 96:
        fail("An arcade runs 6–96 blocks; split longer ones / 风雨廊长度须为 6–96 格，更长的请分段修建")
    side = width + 2
    half = side // 2
    at_ends = site.flag("pavilions")
    rooms = []
    for j in range(len(runs) + 1):
        if (j > 0 and j < len(runs)) or at_ends:
            spot = runs[j].sized(depth = 1) if j < len(runs) else runs[j - 1].at(z = runs[j - 1].depth).sized(depth = 1)
            room = spot.sized(width = side, depth = side, anchor = "center", parity = "low")
            rooms.append(room.at(y = level_of(site, room) - room.origin.y))
        else:
            rooms.append(None)
    out = []
    for j in range(len(runs)):
        lane = runs[j].sized(width = width, anchor = "center", parity = "low")
        back = half + 1 if rooms[j] != None else 0
        front = half if rooms[j + 1] != None else 0
        if lane.depth - back - front < site.count("bay"):
            fail("Stretch %d is too short for a bay between its pavilions / 第 %d 段太短，放不下一个开间" % (j + 1, j + 1))
        out.append(STRETCH(lane.inset(back = back, front = front),
            None if rooms[j] == None else rooms[j].origin.y,
            None if rooms[j + 1] == None else rooms[j + 1].origin.y))
    for room in rooms:
        if room != None:
            out.append(PAVILION(room))
    out.append(TALLY(runs[0]))
    return out
