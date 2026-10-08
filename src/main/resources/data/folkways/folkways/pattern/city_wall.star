accepts = ["path"]
knobs = [
    count("height", 5, 14, default = 7, icon = "minecraft:ladder"),
    count("thickness", 3, 9, default = 5, icon = "minecraft:stone_brick_slab"),
    count("spacing", 0, 48, default = 20, icon = "minecraft:chiseled_stone_bricks"),
    flag("gate", default = True, icon = "minecraft:spruce_door"),
    items("wall", default = ["minecraft:stone_bricks"], icon = "minecraft:stone_bricks"),
    items("base", default = ["minecraft:cobblestone"], icon = "minecraft:cobblestone"),
    items("walkway", default = ["minecraft:smooth_stone"], icon = "minecraft:smooth_stone"),
    items("trim", default = ["minecraft:polished_andesite"], icon = "minecraft:polished_andesite"),
    items("timber", default = ["minecraft:stripped_spruce_log"], icon = "minecraft:stripped_spruce_log"),
    items("panel", default = ["minecraft:spruce_planks"], icon = "minecraft:spruce_planks"),
    items("roof", default = ["minecraft:deepslate_tiles"], icon = "minecraft:deepslate_tiles"),
]

def ceiling(value):
    return int(-(-value // 1))

def start_of(station):
    return station["a"]

def lane(run, width):
    return run.sized(width = width, anchor = "center", parity = "low")

def ground(site, run, width, z0, z1):
    found = site.survey(lane(run, width).at(z = z0).sized(depth = z1 - z0 + 1))
    if found.max == None:
        fail("No ground under the wall / 城墙下方没有地面")
    return found.min, found.max

def inward(points):
    area = 0
    for i in range(len(points)):
        a = points[i]
        b = points[(i + 1) % len(points)]
        area += a.x * b.z - b.x * a.z
    return -1 if area < 0 else 1

def seated(shape, scope, floor, top):
    if top < floor:
        return shape
    rock = terrain(scope.at(y = floor - scope.origin.y).sized(height = top - floor + 1), of = "ground")
    return shape if rock == None else subtract(shape, rock)

def root(value):
    guess = max(1.0, float(value))
    for _ in range(40):
        guess = (guess + value / guess) / 2
    return guess

def square_at(site, distance, size):
    cell = site.path.every(100000, offset = int(distance))[0]
    return cell.sized(width = size, depth = size, anchor = "center", parity = "low")

def ring(scope, gaps):
    edge = hollow(scope)
    return edge if not gaps else subtract(edge, group(gaps))

def merlons(scope, gaps):
    cells = []
    for x in range(scope.width):
        for z in range(scope.depth):
            rim = x == 0 or z == 0 or x == scope.width - 1 or z == scope.depth - 1
            if rim and (x + z) % 2 == 0:
                cells.append(scope.at(x = x, z = z).sized(width = 1, depth = 1))
    crest = group(cells)
    return crest if not gaps else subtract(crest, group(gaps))

def openings(runs, joins, thick, y, base):
    return [lane(runs[j], thick - 2).at(y = y - base).sized(height = 3) for j in joins]

def plan(runs, lengths, square, gate_length, spacing, gated, points, clearway):
    towers = [{"run": 0, "z": 0, "joins": [0]}]
    for i in range(1, len(runs)):
        towers.append({"run": i, "z": 0, "joins": [i - 1, i]})
    towers.append({"run": len(runs) - 1, "z": lengths[-1], "joins": [len(runs) - 1]})
    gate = None
    if gated:
        longest = 0
        best = -1
        for i in range(len(runs)):
            square_run = points[i].x == points[i + 1].x or points[i].z == points[i + 1].z
            score = lengths[i] + (1000 if square_run and lengths[i] >= square + gate_length + 6 else 0)
            if score > best:
                best = score
                longest = i
        z = lengths[longest] // 2
        if z - (gate_length - 1) // 2 < square // 2 + 3 or z + gate_length // 2 > lengths[longest] - (square - 1) // 2 - 3:
            fail("Wall too short for a gatehouse / 城墙太短，放不下城门")
        gate = {"run": longest, "z": z}
    middles = []
    if spacing > 0:
        for i in range(len(runs)):
            count = int(lengths[i] / spacing)
            for k in range(1, count):
                z = int(float(lengths[i]) * k / count + 0.5)
                if gate != None and gate["run"] == i and max(z - gate["z"], gate["z"] - z) < gate_length // 2 + square // 2 + clearway:
                    continue
                middles.append({"run": i, "z": z, "joins": [i]})
    return towers + middles, gate

def draw(site):
    points = site.path.points
    if len(points) < 2:
        fail("A city wall needs at least two path points / 城墙至少需要两个路径点")
    height = site.count("height")
    thick = site.count("thickness")
    spacing = site.count("spacing")
    if height < 5 or height > 14:
        fail("Wall height must be 5–14 blocks / 城墙高度须为 5–14 格")
    if thick < 3 or thick > 9:
        fail("Wall thickness must be 3–9 blocks / 城墙厚度须为 3–9 格")
    if spacing != 0 and (spacing < 12 or spacing > 48):
        fail("Tower spacing must be 0 or 12–48 blocks / 敌台间距须为 0 或 12–48 格")
    for key in ["wall", "base", "walkway", "trim", "timber", "panel", "roof"]:
        if not site.items(key):
            fail("Choose material for %s / 请选择材料" % key)
    runs = site.path.stretches
    lengths = [run.depth - 1 for run in runs]
    square = thick + 4
    total = 0
    for length in lengths:
        total += length
        if length < square + 3:
            fail("Each wall stretch must be at least %d blocks / 每段城墙至少 %d 格" % (square + 3, square + 3))
    for i in range(1, len(points) - 1):
        ax = points[i].x - points[i - 1].x
        az = points[i].z - points[i - 1].z
        bx = points[i + 1].x - points[i].x
        bz = points[i + 1].z - points[i].z
        if ax * bx + az * bz < 0:
            fail("A corner turns back too sharply / 转角过急，不得超过直角")
    if total > 120:
        fail("A wall runs at most 120 blocks / 城墙总长不得超过 120 格")
    gate_width = 3 if height < 7 else 5
    gate_length = gate_width + 8
    towers, gate = plan(runs, lengths, square, gate_length, spacing, site.flag("gate"), points,
        2 * height + 10)
    inner = inward(points)
    wall = site.items("wall")
    base = site.items("base")
    walkway = site.items("walkway")
    trim = site.items("trim")

    stations = [[] for _ in runs]
    serial = 0
    reached = [0.0]
    for i in range(len(runs)):
        dx = points[i + 1].x - points[i].x
        dz = points[i + 1].z - points[i].z
        reached.append(reached[-1] + root(dx * dx + dz * dz))
    whole = int(site.path.length)
    for tower in towers:
        tower["id"] = serial
        serial += 1
        z0 = tower["z"] - (square - 1) // 2
        i = tower["run"]
        along = reached[i] + (reached[i + 1] - reached[i]) * tower["z"] / lengths[i]
        tower["scope"] = square_at(site, min(whole, int(along + 0.5)), square)
        found = site.survey(tower["scope"].outset(x = 1, z = 1))
        if found.max == None:
            fail("No ground under the wall / 城墙下方没有地面")
        tower["low"] = found.min
        tower["level"] = found.max + height + 1
        tower["kind"] = "tower"
        for j in tower["joins"]:
            offset = 0 if j == tower["run"] else lengths[j]
            stations[j].append({"node": tower, "a": offset + z0, "b": offset + z0 + square - 1})
    if gate != None:
        gate["id"] = serial
        serial += 1
        gate["kind"] = "gate"
        z0 = gate["z"] - (gate_length - 1) // 2
        run = runs[gate["run"]]
        low, high = ground(site, run, square + 2, z0 - 1, z0 + gate_length)
        centre = run.at(z = gate["z"]).center()
        floor = site.ground(centre.x, centre.z)
        if floor == None:
            fail("No ground under the gate / 城门下方没有地面")
        gate["floor"] = floor
        gate["low"] = low
        gate["level"] = max(high, floor) + height + 2
        stations[gate["run"]].append({"node": gate, "a": z0, "b": z0 + gate_length - 1})
    for i in range(len(runs)):
        stations[i] = sorted(stations[i], key = start_of)
        for k in range(1, len(stations[i])):
            if stations[i][k]["a"] - stations[i][k - 1]["b"] < 3:
                fail("Towers and gate crowd each other; lengthen the wall or widen the spacing / 敌台与城门过于拥挤，请加长城墙或加大间距")

    chain = []
    walked = 0
    curtains = []
    for i in range(len(runs)):
        for k in range(len(stations[i])):
            here = stations[i][k]
            if not chain or chain[-1]["node"]["id"] != here["node"]["id"]:
                chain.append({"node": here["node"], "lo": walked + here["a"], "hi": walked + here["b"]})
            if k == len(stations[i]) - 1:
                continue
            after = stations[i][k + 1]
            gap = after["a"] - here["b"]
            bays = max(1, int(gap / 4.0 + 0.5))
            marks = []
            for m in range(1, bays):
                z = here["b"] + int(float(gap) * m / bays + 0.5)
                low, high = ground(site, runs[i], thick + 2, z - 1, z + 1)
                node = {"id": serial, "level": high + height, "kind": "curtain"}
                serial += 1
                chain.append({"node": node, "lo": walked + z, "hi": walked + z})
                marks.append((z, node))
            curtains.append((i, here, after, marks))
        walked += lengths[i]
    for _ in range(2):
        for k in range(1, len(chain)):
            reach = (chain[k]["lo"] - chain[k - 1]["hi"]) // 2
            chain[k]["node"]["level"] = max(chain[k]["node"]["level"], chain[k - 1]["node"]["level"] - reach)
        for k in range(len(chain) - 2, -1, -1):
            reach = (chain[k + 1]["lo"] - chain[k]["hi"]) // 2
            chain[k]["node"]["level"] = max(chain[k]["node"]["level"], chain[k + 1]["node"]["level"] - reach)

    clears = []
    bodies = []
    plinths = []
    walks = []
    edges = []
    crests = []
    rails = []
    outer = 0 if inner > 0 else thick - 1
    inner_edge = thick - 1 if inner > 0 else 0
    for i, here, after, marks in curtains:
        run = runs[i]
        base_y = run.origin.y
        band = lane(run, thick)
        steps = [(here["b"], here["node"]["level"])] + [(z, node["level"]) for z, node in marks] + [(after["a"], after["node"]["level"])]
        for s in range(len(steps) - 1):
            z0, l0 = steps[s]
            z1, l1 = steps[s + 1]
            depth = z1 - z0 + 1
            rise = l1 - l0
            bay = band.at(z = z0, y = l0 - base_y).sized(depth = depth)
            deck = arched(bay, bay, 0, end = rise)
            walks.append(deck)
            for x in [0, thick - 1]:
                edges.append(arched(bay.at(x = x).sized(width = 1), bay, 0, end = rise))
            clears.append(arched(bay.at(x = 1, y = 1).sized(width = thick - 2, height = 3), bay, 0, end = rise))
            low, high = ground(site, run, thick + 2, z0, z1)
            body = seated(under(deck, low + 1), lane(run, thick + 2).at(z = z0).sized(depth = depth), low + 1, max(l0, l1))
            bodies.append(body)
            plinths.append(conform(box(lane(run, thick + 2).at(z = z0).sized(depth = depth, height = 2)), offset = -1))
            outer_cells = []
            inner_cells = []
            first = 1 if s == 0 else 0
            for k in range(first, depth - 1):
                level = l0 + float(rise) * k / (depth - 1)
                y = ceiling(level) + 1 - base_y
                z = z0 + k
                tall = 2 if z % 2 == 0 else 1
                outer_cells.append(band.at(x = outer, z = z, y = y).sized(width = 1, depth = 1, height = tall))
                inner_cells.append(band.at(x = inner_edge, z = z, y = y).sized(width = 1, depth = 1))
            if outer_cells:
                crests.append(group(outer_cells))
            if inner_cells:
                rails.append(group(inner_cells))

    tops = []
    rims = []
    battlements = []
    for tower in towers:
        footprint = tower["scope"]
        base_y = footprint.origin.y
        level = tower["level"]
        low = tower["low"]
        if level - low - 1 >= 1:
            body = box(footprint.at(y = low + 1 - base_y).sized(height = level - low - 1))
            bodies.append(seated(body, footprint.outset(x = 1, z = 1), low + 1, level - 1))
        plinths.append(conform(box(footprint.outset(x = 1, z = 1).sized(height = 2)), offset = -1))
        deck = footprint.at(y = level - base_y)
        tops.append(deck)
        rims.append(hollow(deck))
        clears.append(footprint.inset(x = 1, z = 1).at(y = level + 1 - base_y).sized(height = 3))
        gaps = openings(runs, tower["joins"], thick, level + 1, base_y)
        battlements.append(ring(footprint.at(y = level + 1 - base_y).sized(height = 2), gaps))
        battlements.append(merlons(footprint.at(y = level + 3 - base_y), gaps))

    parts = [clear("headroom", group(clears))]
    for body in bodies:
        parts.append(part("wall", body, wall))
    for plinth in plinths:
        parts.append(part("plinth", plinth, base))
    for deck in walks:
        parts.append(part("walk", deck, walkway, fit = "layered"))
    for edge in edges:
        parts.append(part("edge", edge, trim, fit = "layered", labels = ["walk"]))
    parts.append(part("platform", group(tops), walkway, labels = ["walk"]))
    parts.append(part("cornice", group(rims), trim, labels = ["walk"]))
    if gate != None:
        parts += gatehouse(site, runs, gate, thick, square, gate_width, gate_length, inner, stations, height)
    for crest in crests:
        parts.append(part("parapet", crest, wall, fit = "connected", support = "walk"))
    for rail in rails:
        parts.append(part("parapet", rail, wall, fit = "connected", support = "walk"))
    parts.append(part("battlement", group(battlements), wall))
    return parts

def gatehouse(site, runs, gate, thick, square, gate_width, gate_length, inner, stations, height):
    run = runs[gate["run"]]
    base_y = run.origin.y
    band = lane(run, thick)
    level = gate["level"]
    floor = gate["floor"]
    low = gate["low"]
    start = gate["z"] - (gate_length - 1) // 2
    finish = start + gate_length - 1
    block = lane(run, square).at(z = start).sized(depth = gate_length)
    clearance = min(level - floor - 3, gate_width + 2)
    arch = gate_width // 2
    mouth = gate["z"] - gate_width // 2
    room = lane(run, square).at(z = mouth, y = floor + 1 - base_y).sized(depth = gate_width, height = clearance)
    opening = vault(room, arch)
    body = box(block.at(y = low + 1 - base_y).sized(height = level - low - 1))
    body = subtract(seated(body, lane(run, square + 2).at(z = start - 1).sized(depth = gate_length + 2), low + 1,
        level - 1), opening)
    parts = [
        part("gate", body, site.items("wall"), fit = "layered"),
        part("plinth", conform(box(lane(run, square + 2).at(z = start - 1).sized(depth = gate_length + 2, height = 2)),
            offset = -1), site.items("base")),
        clear("passage", group([opening, lane(run, square + 2).at(z = mouth, y = floor + 1 - base_y)
            .sized(depth = gate_width, height = 2)])),
        part("paving", lane(run, square + 2).at(z = mouth, y = floor - base_y).sized(depth = gate_width),
            site.items("base")),
        part("voussoir", subtract(vault(room, arch, grow = 1), opening), site.items("trim"), fit = "layered"),
    ]
    deck = block.at(y = level - base_y)
    parts.append(part("platform", deck, site.items("walkway"), labels = ["walk"]))
    parts.append(part("cornice", hollow(deck), site.items("trim"), labels = ["walk"]))

    stairs = []
    gaps = openings(runs, [gate["run"]], thick, level + 1, base_y)
    ramp_x = thick if inner > 0 else -3
    neighbours = stations[gate["run"]]
    for index in range(len(neighbours)):
        if neighbours[index]["node"]["id"] != gate["id"]:
            continue
        for direction in [-1, 1]:
            other = index + direction
            if other < 0 or other >= len(neighbours):
                continue
            limit = neighbours[other]["b"] if direction < 0 else neighbours[other]["a"]
            flight = climb(site, run, band, ramp_x, start if direction < 0 else finish, direction, limit, level)
            if flight == None:
                continue
            stairs.append(flight)
            edge = start if direction < 0 else finish
            gaps.append(band.at(x = ramp_x, z = edge, y = level + 1 - base_y).sized(width = 2, depth = 1, height = 2))
    for flight in stairs:
        parts.append(part("ramp", flight["body"], site.items("wall")))
        parts.append(part("ramp", flight["deck"], site.items("walkway"), fit = "layered", labels = ["walk"]))

    parts.append(part("battlement", group([ring(block.at(y = level + 1 - base_y), gaps),
        merlons(block.at(y = level + 2 - base_y), gaps)]), site.items("wall")))
    for flight in stairs:
        parts.append(part("railing", flight["rail"], site.items("wall"), fit = "connected", support = "walk"))

    hall = band.at(z = start + 2, y = level + 1 - base_y).sized(depth = gate_length - 4, height = 3)
    parts.append(part("hall", hollow(hall), site.items("panel")))
    long_sides = [hall.at(x = x, y = 3).sized(width = 1, height = 1) for x in [0, thick - 1]]
    short_sides = [hall.at(z = z, y = 3).sized(depth = 1, height = 1) for z in [0, hall.depth - 1]]
    along = "x" if site.path.headings[gate["run"]] in ["east", "west"] else "z"
    parts.append(part("beam", group(long_sides), site.items("timber"), orient = along))
    parts.append(part("beam", group(short_sides), site.items("timber"), orient = "z" if along == "x" else "x"))
    windows = []
    posts = []
    for z in range(hall.depth):
        for x in [0, thick - 1]:
            if z % 2 == 0:
                posts.append(hall.at(x = x, z = z).sized(width = 1, depth = 1, height = 4))
            else:
                windows.append(hall.at(x = x, z = z, y = 1).sized(width = 1, depth = 1, height = 2))
    for z in [0, hall.depth - 1]:
        windows.append(hall.at(x = 1, z = z).sized(width = thick - 2, depth = 1))
    parts.append(clear("window", group(windows)))
    parts.append(part("post", group(posts), site.items("timber"), orient = "y"))
    eaves = band.outset(x = 1).at(z = start + 1, y = level + 5 - base_y).sized(depth = gate_length - 2,
        height = (thick + 2) // 2 + 2)
    parts.append(part("roof", hip(eaves, rise = 1, run = 2), site.items("roof"), fit = "stepped"))
    crown = (min(eaves.width, eaves.depth) - 1) // 2
    top = int(1 + crown / 2.0)
    ridge = eaves.at(x = crown, z = crown, y = top).sized(width = eaves.width - 2 * crown,
        depth = eaves.depth - 2 * crown, height = 1)
    horns = [eaves.at(x = x, z = z, y = 1).sized(width = 1, depth = 1, height = 1)
        for x in [0, eaves.width - 1] for z in [0, eaves.depth - 1]]
    parts.append(part("ridge", group([ridge] + horns), site.items("roof")))
    return parts

def climb(site, run, band, x, edge, direction, limit, level):
    base_y = run.origin.y
    for length in range(4, 64):
        foot = edge + direction * length
        if (direction < 0 and foot <= limit + 1) or (direction > 0 and foot >= limit - 1):
            return None
        found = site.survey(band.at(x = x, z = foot).sized(width = 3, depth = 1))
        if found.max == None:
            return None
        rise = level - found.max
        if rise < 1 or rise * 2 > length - 1:
            continue
        near = foot if direction < 0 else edge + 1
        bottom = found.max if direction < 0 else level
        flight = band.at(x = x, z = near, y = bottom - base_y).sized(width = 3, depth = length)
        deck = arched(flight, flight, 0, end = rise if direction < 0 else -rise)
        floor = site.survey(flight).min
        body = under(deck, floor + 1)
        rock = terrain(flight.at(y = floor + 1 - bottom).sized(height = level - floor), of = "ground")
        if rock != None:
            body = subtract(body, rock)
        rail = []
        for k in range(length):
            if (direction < 0 and k == length - 1) or (direction > 0 and k == 0):
                continue
            t = float(k) / (length - 1)
            y = ceiling(found.max + rise * t if direction < 0 else level - rise * t) + 1 - base_y
            rail.append(band.at(x = x + (2 if x > 0 else 0), z = near + k, y = y).sized(width = 1, depth = 1))
        return {"deck": deck, "body": body, "rail": group(rail)}
    return None
