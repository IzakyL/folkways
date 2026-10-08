accepts = ["zone"]
knobs = [
    count("rise", 1, 4, default = 1, icon = "minecraft:cobblestone_stairs"),
    count("spacing", 2, 8, default = 8, icon = "minecraft:water_bucket"),
    flag("fenced", default = True, icon = "minecraft:oak_fence_gate"),
    items("soil", default = ["minecraft:dirt"], icon = "minecraft:dirt"),
    items("bund", default = ["minecraft:grass_block"], icon = "minecraft:grass_block"),
    items("wall", default = ["minecraft:cobblestone", "minecraft:mossy_cobblestone"], icon = "minecraft:mossy_cobblestone"),
    items("cap", default = ["minecraft:stone_bricks"], icon = "minecraft:stone_bricks"),
    items("path", default = ["minecraft:mud_bricks"], icon = "minecraft:mud_bricks"),
    items("steps", default = ["minecraft:mud_brick_stairs"], icon = "minecraft:mud_brick_stairs"),
    items("fence", default = ["minecraft:spruce_fence"], icon = "minecraft:spruce_fence"),
    items("gate", default = ["minecraft:spruce_fence_gate"], icon = "minecraft:spruce_fence_gate"),
    items("lamp", default = ["minecraft:lantern"], icon = "minecraft:lantern"),
]

WATER = piece(key = {"w": "minecraft:water"}, layers = [["w"]])
GATE = piece(key = {"g": "minecraft:oak_fence_gate[facing=north]"}, layers = [["g"]])
SIDES = [(1, 0), (-1, 0), (0, 1), (0, -1)]
AROUND = SIDES + [(1, 1), (1, -1), (-1, 1), (-1, -1)]

def median(values):
    ordered = sorted(values)
    return ordered[(len(ordered) - 1) // 2]

def magnitude(value):
    return value if value >= 0 else -value

def nearest(value):
    return int(value + 0.5) if value >= 0 else -int(-value + 0.5)

def mean(values):
    total = 0
    for value in values:
        total += value
    return float(total) / len(values)

def cell(work, x, z, y, width = 1, depth = 1, height = 1):
    return work.at(x = x, z = z, y = y - work.origin.y).sized(width = width, depth = depth, height = height)

def height_at(site, work, x, z, seen):
    spot = work.at(x = x, z = z).origin
    key = (spot.x, spot.z)
    if key not in seen:
        seen[key] = site.ground(spot.x, spot.z)
    return seen[key]

def ground(site, work, seen):
    grid = []
    for z in range(work.depth):
        row = []
        for x in range(work.width):
            found = height_at(site, work, x, z, seen)
            if found == None:
                fail("No ground under the field / 田地下方没有地面")
            row.append(found)
        grid.append(row)
    return grid

def fall(values):
    third = max(1, len(values) // 3)
    return mean(values[:third]) - mean(values[-third:])

def rows(grid):
    return [median(row[1:-1]) for row in grid[1:-1]]

def columns(grid):
    return [median([row[x] for row in grid[1:-1]]) for x in range(1, len(grid[0]) - 1)]

def smooth(grid, reach):
    depth = len(grid)
    width = len(grid[0])
    found = []
    for z in range(depth):
        row = []
        for x in range(width):
            near = []
            for dz in range(max(0, z - reach), min(depth, z + reach + 1)):
                near += grid[dz][max(0, x - reach):min(width, x + reach + 1)]
            row.append(mean(near))
        found.append(row)
    return found

def lips(surface, levels, rise, least):
    # A terrace ends where the ground drops through the contour halfway down to the next one, so its lip follows
    # the hillside rather than a ruled line. The lips are eased along x, then kept far enough apart that every
    # terrace has room for its crops and for the steps down onto it.
    depth = len(surface)
    width = len(surface[0])
    count = len(levels)
    found = []
    for k in range(count - 1):
        contour = levels[k + 1] + rise / 2.0
        line = []
        for x in range(width):
            column = min(max(x, 1), width - 2)
            lip = depth - 2
            for z in range(1, depth - 1):
                if surface[z][column] < contour:
                    lip = z - 1
                    break
            line.append(lip)
        eased = [nearest(mean(line[max(0, x - 3):min(width, x + 4)])) for x in range(width)]
        for x in range(1, width - 1):
            if eased[x - 1] == eased[x + 1]:
                eased[x] = eased[x - 1]
        found.append(eased)
    for x in range(width):
        before = 0
        for k in range(count - 1):
            lip = max(found[k][x], before + least + 1)
            lip = min(lip, depth - 1 - (count - 1 - k) * (least + 1))
            found[k][x] = lip
            before = lip
    for line in found:
        line[0] = line[1]
        line[width - 1] = line[width - 2]
    return found

def closest(cells, taken, x, z):
    best = None
    far = 0
    for (cx, cz) in cells:
        if (cx, cz) in taken:
            continue
        gap = (cx - x) * (cx - x) + (cz - z) * (cz - z)
        if best == None or gap < far:
            best = (cx, cz)
            far = gap
    return best

def spread(region, allowed, across, down, size = 0):
    # across by down spots laid evenly over a strip of field, following the strip's curve: the strip is cut into
    # equal shares along x, and each share's spots are spread evenly over how far that share reaches in z.
    xs = [x for (x, _) in region]
    first = min(xs)
    span = max(xs) - first + 1
    picks = {}
    for j in range(across):
        start = first + span * j // across
        end = first + span * (j + 1) // across
        zs = [z for (x, z) in region if x >= start and x < end]
        if not zs:
            continue
        low = min(zs)
        high = max(zs)
        rows = max(down, -(-(high - low + 1) // size)) if size > 0 else down
        for i in range(rows):
            spot = closest(allowed, picks, (start + end - 1) // 2, low + (high - low + 1) * (2 * i + 1) // (2 * rows))
            if spot != None:
                picks[spot] = True
    return picks.keys()

def wets(hole, at, reach):
    return magnitude(hole[0] - at[0]) <= reach and magnitude(hole[1] - at[1]) <= reach

def dry(region, holes, reach):
    return [at for at in region if not [hole for hole in holes if wets(hole, at, reach)]]

def hydrate(region, allowed, reach):
    # Farmland drinks from water up to reach cells away, so one hole waters a square of it. Take the fewest holes
    # an even grid over the strip needs, one more across or down only if the strip's curve leaves some dry.
    if not allowed:
        return []
    size = 2 * reach + 1
    columns = {}
    for (x, z) in region:
        columns.setdefault(x, []).append(z)
    span = max(columns.keys()) - min(columns.keys()) + 1
    deep = median([max(zs) - min(zs) + 1 for zs in columns.values()])
    across = -(-span // size)
    down = -(-deep // size)
    for (a, d) in [(across, down), (across + 1, down), (across, down + 1), (across + 1, down + 1)]:
        holes = spread(region, allowed, a, d, size)
        if not dry(region, holes, reach):
            return holes
    holes = {hole: True for hole in spread(region, allowed, across, down, size)}
    for at in region:
        if not [hole for hole in holes if wets(hole, at, reach)]:
            spot = closest(allowed, holes, at[0], at[1])
            if spot != None:
                holes[spot] = True
    return holes.keys()

def lit(lamps, x, z, y, reach):
    for (lx, lz, ly) in lamps:
        if magnitude(lx - x) + magnitude(lz - z) + magnitude(ly - y) <= reach:
            return True
    return False

def light(spots, ground, fixed, width, depth, reach):
    # A lantern keeps monsters off every cell it lights at all. Lay the fewest lanterns an even grid over the
    # whole field needs so that every cell a mob could stand on is within reach of one.
    grids = []
    for across in range(1, width + 1):
        for down in range(1, depth + 1):
            if (-(-width // across)) // 2 + (-(-depth // down)) // 2 < reach:
                grids.append((across * down, magnitude(float(width) / across - float(depth) / down), across, down))
    for (_, _, across, down) in sorted(grids)[:12]:
        picks = {}
        for j in range(across):
            for i in range(down):
                spot = closest(spots, picks, width * (2 * j + 1) // (2 * across), depth * (2 * i + 1) // (2 * down))
                if spot != None:
                    picks[spot] = True
        lamps = fixed + [(x, z, spots[(x, z)]) for (x, z) in picks]
        if not [at for at in ground if not lit(lamps, at[0], at[1], at[2], reach)]:
            return picks.keys()
    picks = {}
    for (x, z, y) in ground:
        lamps = fixed + [(px, pz, spots[(px, pz)]) for (px, pz) in picks]
        if not lit(lamps, x, z, y, reach):
            picks[closest(spots, picks, x, z)] = True
    return picks.keys()

def palette(items):
    if len(items) < 2:
        return items
    return mix({item: 1 for item in items})

def draw(site):
    zone = site.zone.sized(height = 1)
    if zone.width < 6 or zone.depth < 6 or zone.width > 48 or zone.depth > 48:
        fail("A terraced field must be 6–48 blocks on each side / 梯田每边须为 6–48 格")
    rise = site.count("rise")
    spacing = site.count("spacing")
    if rise < 1 or rise > 4:
        fail("Terrace height must be 1–4 blocks / 梯田台阶高须为 1–4 格")
    if spacing < 2 or spacing > 8:
        fail("Water spacing must be 2–8 blocks / 水眼间距须为 2–8 格")
    for key in ["soil", "bund", "wall", "cap", "path", "steps", "fence", "gate", "lamp"]:
        if not site.items(key):
            fail("Choose material for %s / 请选择材料" % key)
    if site.survey(zone).water > 0.2:
        fail("The field is under water / 田地大部分在水下")
    seen = {}
    work = zone
    grid = ground(site, work, seen)
    if magnitude(fall(columns(grid))) > magnitude(fall(rows(grid))) + 0.5:
        work = zone.rotate(1)
        grid = ground(site, work, seen)
    if fall(rows(grid)) < -0.5:
        work = work.rotate(2)
        grid = ground(site, work, seen)
    width = work.width
    depth = work.depth

    surface = smooth(grid, 2)
    top = median(surface[1][1:-1])
    bottom = median(surface[depth - 2][1:-1])
    # rise is the step the terraces would like; a slope too steep for it gets taller steps. A one-block step needs
    # no bank, so its terraces can be as narrow as the stair between them; taller ones keep room for a bank.
    count = 0
    least = 0
    for step in range(rise, 5):
        count = max(1, nearest((top - bottom) / step) + 1)
        least = 1 if step == 1 else max(3, step + 1)
        if count * (least + 1) - 1 <= depth - 2:
            rise = step
            break
        count = 0
    if count == 0:
        fail("Too steep to terrace / 坡度太陡，无法修成梯田")
    levels = [nearest(bottom) + (count - 1 - k) * rise for k in range(count)]
    edges = lips(surface, levels, rise, least)

    level = {}
    band = {}
    for z in range(depth):
        for x in range(width):
            k = len([line for line in edges if line[x] < z])
            level[(x, z)] = levels[k]
            band[(x, z)] = k

    lanes = max(1, (width + 4) // 18)
    paths = [width * (2 * t + 1) // (2 * lanes) for t in range(lanes)]
    gate = paths[len(paths) // 2]
    fenced = site.flag("fenced")

    # The field keeps to ground its terraces sit close to. Where the hill bulges or falls away at the zone's edge
    # by more than a terrace, the terraces stop short and the hill is left as it is; what a path cannot reach
    # along its own terrace goes too. Nothing inside is holed, and the paths always run through.
    off = {}
    for z in range(depth):
        for x in range(width):
            off[(x, z)] = magnitude(grid[z][x] - level[(x, z)])
    outside = {}
    seen_off = {}
    for start in off:
        if start in seen_off or off[start] <= rise:
            continue
        if not (start[0] == 0 or start[1] == 0 or start[0] == width - 1 or start[1] == depth - 1):
            continue
        patch = [start]
        seen_off[start] = True
        for i in range(width * depth):
            if i >= len(patch):
                break
            (x, z) = patch[i]
            for (dx, dz) in SIDES:
                at = (x + dx, z + dz)
                if at in off and at not in seen_off and off[at] > rise:
                    seen_off[at] = True
                    patch.append(at)
        if [at for at in patch if off[at] > rise + 1]:
            for at in patch:
                outside[at] = True
    kept = {}
    queue = []
    for p in paths:
        for z in range(depth):
            kept[(p, z)] = True
            queue.append((p, z))
    for i in range(width * depth):
        if i >= len(queue):
            break
        (x, z) = queue[i]
        for (dx, dz) in SIDES:
            at = (x + dx, z + dz)
            if at in level and at not in outside and at not in kept and level[at] == level[(x, z)]:
                kept[at] = True
                queue.append(at)

    # A drop of one block needs nothing but the soil's own edge. A deeper one ends in a bank: earth and turf up to
    # two blocks, stone beyond. Where higher ground rises more than two over the field, a stone wall holds it back.
    lip = {}
    bank = {}
    spill = {}
    edge = {}
    for (x, z) in kept:
        y = level[(x, z)]
        low = y
        high = y
        for (dx, dz) in SIDES:
            at = (x + dx, z + dz)
            if at in kept:
                if level[at] < y:
                    spill[(x, z)] = True
                if level[at] < y - 1:
                    low = min(low, level[at])
                continue
            found = height_at(site, work, at[0], at[1], seen)
            found = y - 64 if found == None else found
            if found < y:
                spill[(x, z)] = True
            if found < y - 1:
                low = min(low, found)
            high = max(high, found)
        if x in paths:
            pass
        elif high > y + 2:
            bank[(x, z)] = min(high, y + 4) - y
        elif low < y:
            lip[(x, z)] = low
        if fenced:
            for (dx, dz) in AROUND:
                if (x + dx, z + dz) not in kept:
                    edge[(x, z)] = True
                    break
    tops = {at: level[at] + bank.get(at, 0) for at in kept}

    steps = []
    for p in paths:
        for z in range(depth - 1):
            here = level[(p, z)]
            there = level[(p, z + 1)]
            if here == there:
                continue
            rise_by = magnitude(here - there)
            low = min(here, there)
            if here < there:
                lane = cell(work, p, z + 1 - rise_by, low + 1, depth = rise_by + 1)
            else:
                lane = cell(work, p, z, low + 1, depth = rise_by + 1).rotate(2)
            steps.append(under(arched(lane, lane, 0, end = rise_by), low))

    stretch = {}
    for x in range(width):
        stretch[x] = len([p for p in paths if p < x])
    strips = {}
    open_cells = {}
    for (x, z) in kept:
        if x not in paths and (x, z) not in lip and (x, z) not in bank and (x, z) not in edge:
            open_cells[(x, z)] = True
            strips.setdefault((band[(x, z)], stretch[x]), {})[(x, z)] = True
    # Farmland also drinks from water one block above it, so a terrace one step up waters the edge of the one
    # below: work down the hill and water only what the terrace above has left dry.
    reach = spacing // 2
    holes = []
    for key in sorted(strips.keys()):
        strip = strips[key]
        thirsty = {}
        for at in strip:
            if not [hole for hole in holes
                if wets(hole, at, reach) and level[hole] - level[at] >= 0 and level[hole] - level[at] <= 1]:
                thirsty[at] = True
        if thirsty:
            holes += hydrate(thirsty, [at for at in strip if at not in spill], reach)
    turf = []
    # Where a terrace narrows to nothing but its brink there is no cell that would hold water; water it from the
    # nearest one that will, on its level or the one above, and leave it in turf if there is none.
    for at in sorted(open_cells.keys()):
        if at in holes or [hole for hole in holes
            if wets(hole, at, reach) and level[hole] - level[at] >= 0 and level[hole] - level[at] <= 1]:
            continue
        near = [spot for spot in open_cells if spot not in spill and wets(spot, at, reach)
            and level[spot] - level[at] >= 0 and level[spot] - level[at] <= 1]
        if near:
            holes.append(closest(near, {}, at[0], at[1]))
        else:
            open_cells.pop(at)
            turf.append(at)
    wet = {hole: True for hole in holes}

    fixed = []
    if fenced:
        fixed = [(x, depth - 1, tops[(x, depth - 1)] + 2) for x in [gate - 1, gate + 1] if (x, depth - 1) in edge]
    ground_cells = []
    for (x, z) in kept:
        if (x, z) in wet or ((x, z) in edge and x not in paths):
            continue
        ground_cells.append((x, z, tops[(x, z)] + 1))
    spots = {at: level[at] + 2 for at in open_cells if at not in wet}
    posts = list(light(spots, ground_cells, fixed, width, depth, 13))

    dig = []
    for z in range(depth):
        start = None
        for x in range(width + 1):
            if start != None and x < width and (x, z) in kept and level[(x, z)] == level[(start, z)]:
                continue
            if start != None:
                y = level[(start, z)]
                high = y
                for c in range(start, x):
                    spot = work.at(x = c, z = z).origin
                    found = site.highest(spot.x, spot.z, "solid")
                    if found != None:
                        high = max(high, min(found, y + 20))
                if high > y:
                    cut = terrain(cell(work, start, z, y + 1, width = x - start, height = high - y))
                    if cut != None:
                        dig.append(cut)
            start = x if x < width and (x, z) in kept else None

    kerb = []
    walls = []
    bunds = []
    faces = []
    soil = []
    lanes_cells = []
    bed = []
    for ((x, z), up) in bank.items():
        y = level[(x, z)]
        walls.append(cell(work, x, z, y, height = up))
        kerb.append(cell(work, x, z, y + up))
    for ((x, z), low) in lip.items():
        y = level[(x, z)]
        if y - low > 2:
            kerb.append(cell(work, x, z, y))
            walls.append(cell(work, x, z, low + 1, height = y - low - 1))
        else:
            bunds.append(cell(work, x, z, y))
            if y - low > 1:
                faces.append(cell(work, x, z, low + 1, height = y - low - 1))
    for (x, z) in edge:
        if x not in paths and (x, z) not in lip and (x, z) not in bank:
            bunds.append(cell(work, x, z, level[(x, z)]))
    for (x, z) in turf:
        bunds.append(cell(work, x, z, level[(x, z)]))
    for p in paths:
        for z in range(depth):
            lanes_cells.append(cell(work, p, z, level[(p, z)]))
    for z in range(depth):
        start = None
        for x in range(width + 1):
            plain = x < width and (x, z) in open_cells and (x, z) not in wet and (x, z) not in posts
            if start != None and (not plain or level[(x, z)] != level[(start, z)]):
                strip = cell(work, start, z, level[(start, z)], width = x - start)
                soil.append(strip)
                bed.append(strip)
                start = None
            if plain and start == None:
                start = x
    for at in holes + posts:
        bed.append(cell(work, at[0], at[1], level[at]))

    parts = []
    if dig:
        parts.append(clear("dig", dig))
    below = fill_below(group(soil + lanes_cells + bed + bunds))
    fill = soil + faces + ([below] if below != None else [])
    if fill:
        parts.append(part("fill", group(fill), site.items("soil")))
    if kerb:
        footing = fill_below(group(kerb + walls))
        wall = walls + ([footing] if footing != None else [])
        if wall:
            parts.append(part("wall", group(wall), palette(site.items("wall"))))
        parts.append(part("kerb", group(kerb), site.items("cap")))
    if bunds:
        parts.append(part("bund", group(bunds), site.items("bund")))
    parts.append(part("path", group(lanes_cells), site.items("path")))
    parts += [stamp("water", WATER, cell(work, x, z, level[(x, z)]), fit = "tile") for (x, z) in holes]
    if steps:
        parts.append(part("steps", group(steps), site.items("steps") + site.items("path"), fit = "stepped"))

    lights = [cell(work, x, z, y) for (x, z, y) in fixed]
    if fenced:
        ring = []
        for (x, z) in edge:
            if (x in paths and z != 0) or (x, z) == (gate, depth - 1):
                continue
            # A post beside a higher one stands as tall, so the fence runs unbroken over a step.
            high = max([tops[(x, z)]] + [tops[(x + dx, z + dz)] for (dx, dz) in SIDES if (x + dx, z + dz) in edge])
            ring.append(cell(work, x, z, tops[(x, z)] + 1, height = high - tops[(x, z)] + 1))
        if ring:
            parts.append(part("fence", group(ring), site.items("fence")))
        parts.append(stamp("gate", GATE, cell(work, gate, depth - 1, level[(gate, depth - 1)] + 1),
            swap = {"minecraft:oak_fence_gate": site.items("gate")}))
    poles = []
    for (x, z) in posts:
        poles.append(cell(work, x, z, level[(x, z)], height = 2))
        lights.append(cell(work, x, z, level[(x, z)] + 2))
    if poles:
        parts.append(part("post", group(poles), site.items("fence")))
    if lights:
        parts.append(part("lamp", group(lights), site.items("lamp")))
    report("terraces", count)
    report("water", len(holes))
    report("lamps", len(lights))
    report("kept", len(kept))
    return parts
