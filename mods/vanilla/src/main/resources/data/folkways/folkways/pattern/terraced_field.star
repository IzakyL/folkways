load("folkways:materials.star", "EARTH", "FENCES", "GATES", "LAMPS", "MASONRY", "STONE_STAIRS", "offer")

accepts = ["zone"]
knobs = [
    count("rise", 1, 4, default = 1, icon = "minecraft:cobblestone_stairs"),
    count("spacing", 2, 8, default = 8, icon = "minecraft:water_bucket"),
    flag("fenced", default = True, icon = "minecraft:oak_fence_gate"),
    items("soil", offer(["minecraft:dirt"], EARTH),
        default = ["minecraft:dirt"], icon = "minecraft:dirt"),
    items("bund", offer(["minecraft:grass_block"], EARTH),
        default = ["minecraft:grass_block"], icon = "minecraft:grass_block"),
    items("wall", offer(["minecraft:cobblestone", "minecraft:mossy_cobblestone"], MASONRY),
        default = ["minecraft:cobblestone", "minecraft:mossy_cobblestone"], icon = "minecraft:mossy_cobblestone"),
    items("cap", offer(["minecraft:stone_bricks"], MASONRY),
        default = ["minecraft:stone_bricks"], icon = "minecraft:stone_bricks"),
    items("path", offer(["minecraft:mud_bricks"], MASONRY),
        default = ["minecraft:mud_bricks"], icon = "minecraft:mud_bricks"),
    items("steps", offer(["minecraft:mud_brick_stairs"], STONE_STAIRS),
        default = ["minecraft:mud_brick_stairs"], icon = "minecraft:mud_brick_stairs"),
    items("fence", offer(["minecraft:spruce_fence"], FENCES),
        default = ["minecraft:spruce_fence"], icon = "minecraft:spruce_fence"),
    items("gate", offer(["minecraft:spruce_fence_gate"], GATES),
        default = ["minecraft:spruce_fence_gate"], icon = "minecraft:spruce_fence_gate"),
    items("lamp", offer(["minecraft:lantern"], LAMPS),
        default = ["minecraft:lantern"], icon = "minecraft:lantern"),
]
lang = {
    "en_us": {
        "title": "Terraced Field",
        "rise": "Rise",
        "spacing": "Channel spacing",
        "fenced": "Fenced",
        "soil": "Soil",
        "bund": "Bund",
        "wall": "Wall",
        "cap": "Cap",
        "path": "Path",
        "steps": "Steps",
        "fence": "Fence",
        "gate": "Gate",
        "lamp": "Lamps",
        "error.field.ground": "No ground under the field",
        "error.field.size": "A terraced field must be 6–56 blocks on each side",
        "error.field.height": "Terrace height must be 1–4 blocks",
        "error.field.spacing": "Water spacing must be 2–8 blocks",
        "error.material": "Choose material for %s",
        "error.field.water": "The field is under water",
        "error.field.steep": "Too steep to terrace",
    },
    "zh_cn": {
        "title": "梯田",
        "rise": "每级高度",
        "spacing": "水渠间距",
        "fenced": "围栏",
        "soil": "土壤",
        "bund": "田埂",
        "wall": "挡墙",
        "cap": "压顶",
        "path": "小路",
        "steps": "台阶",
        "fence": "栅栏",
        "gate": "栅栏门",
        "lamp": "灯具",
        "error.field.ground": "田地下方没有地面",
        "error.field.size": "梯田每边须为 6–56 格",
        "error.field.height": "梯田台阶高须为 1–4 格",
        "error.field.spacing": "水眼间距须为 2–8 格",
        "error.material": "请为%s选择材料",
        "error.field.water": "田地大部分在水下",
        "error.field.steep": "坡度太陡，无法修成梯田",
    },
    "zh_hk": {
        "title": "梯田",
        "rise": "每級高度",
        "spacing": "水渠間距",
        "fenced": "圍欄",
        "soil": "土壤",
        "bund": "田埂",
        "wall": "擋牆",
        "cap": "壓頂",
        "path": "小路",
        "steps": "台階",
        "fence": "柵欄",
        "gate": "柵欄門",
        "lamp": "燈具",
        "error.field.ground": "田地下方沒有地面",
        "error.field.size": "梯田每邊須為 6–56 格",
        "error.field.height": "梯田每級高度須為 1–4 格",
        "error.field.spacing": "水眼間距須為 2–8 格",
        "error.material": "請為%s選擇材料",
        "error.field.water": "田地大部分在水下",
        "error.field.steep": "坡度太陡，無法修成梯田",
    },
    "zh_tw": {
        "title": "梯田",
        "rise": "每級高度",
        "spacing": "水渠間距",
        "fenced": "圍欄",
        "soil": "土壤",
        "bund": "田埂",
        "wall": "擋牆",
        "cap": "壓頂",
        "path": "小路",
        "steps": "臺階",
        "fence": "柵欄",
        "gate": "柵欄門",
        "lamp": "燈具",
        "error.field.ground": "田地下方沒有地面",
        "error.field.size": "梯田每邊須為 6–56 格",
        "error.field.height": "梯田每級高度須為 1–4 格",
        "error.field.spacing": "水眼間距須為 2–8 格",
        "error.material": "請為%s選擇材料",
        "error.field.water": "田地大部分在水下",
        "error.field.steep": "坡度太陡，無法修成梯田",
    },
}

WATER = piece(key = {"w": "minecraft:water"}, layers = [["w"]])
# How many fence posts stand under a field lantern: as tall as the road pattern's lamp posts.
POST = 3

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
                reject("error.field.ground")
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
    # Each cell's mean over the square reach around it, read off running sums so no square is added up twice.
    depth = len(grid)
    width = len(grid[0])
    sums = [[0.0] * (width + 1)]
    for z in range(depth):
        above = sums[z]
        row = [0.0]
        run = 0.0
        for x in range(width):
            run += grid[z][x]
            row.append(above[x + 1] + run)
        sums.append(row)
    found = []
    for z in range(depth):
        z0 = max(0, z - reach)
        z1 = min(depth, z + reach + 1)
        top = sums[z0]
        bottom = sums[z1]
        row = []
        for x in range(width):
            x0 = max(0, x - reach)
            x1 = min(width, x + reach + 1)
            row.append((bottom[x1] - bottom[x0] - top[x1] + top[x0]) / ((z1 - z0) * (x1 - x0)))
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

def spread(region, allowed, across, down, size = 0):
    # across by down spots laid evenly over a strip of field, following the strip's curve: the strip is cut into
    # equal shares along x, and each share's spots are spread evenly over how far that share reaches in z.
    # allowed is keyed by cell, so the spot nearest each mark is found by looking outward from the mark.
    xs = [x for (x, _) in region]
    first = min(xs)
    span = max(xs) - first + 1
    zs_all = [z for (_, z) in region]
    deep = max(zs_all) - min(zs_all) + 1
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
            spot = nearby(allowed, picks, (start + end - 1) // 2, low + (high - low + 1) * (2 * i + 1) // (2 * rows), span, deep)
            if spot != None:
                picks[spot] = True
    return picks.keys()

def wetted(holes, reach):
    # Every cell some hole waters, on any level: the square of reach around each.
    found = {}
    for (hx, hz) in holes:
        for dx in range(-reach, reach + 1):
            for dz in range(-reach, reach + 1):
                found[(hx + dx, hz + dz)] = True
    return found

def dry(region, holes, reach):
    wet = wetted(holes, reach)
    return [at for at in region if at not in wet]

def soak(watered, level, hole, reach):
    # Marks what a hole waters: every cell within reach on its level or one below it.
    for dx in range(-reach, reach + 1):
        for dz in range(-reach, reach + 1):
            at = (hole[0] + dx, hole[1] + dz)
            if at in level and level[hole] - level[at] >= 0 and level[hole] - level[at] <= 1:
                watered[at] = True

def hydrate(region, allowed, reach):
    # Farmland drinks from water up to reach cells away, so one hole waters a square of it. Take the fewest holes
    # an even grid over the strip needs, one more across or down only if the strip's curve leaves some dry.
    if not allowed:
        return []
    allowed = {at: True for at in allowed}
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
    wet = wetted(holes, reach)
    reaches = max(columns.keys()) - min(columns.keys()) + 1 + max([max(zs) for zs in columns.values()]) - min([min(zs) for zs in columns.values()]) + 1
    for at in region:
        if at not in wet:
            spot = nearby(allowed, holes, at[0], at[1], reaches, 0)
            if spot != None:
                holes[spot] = True
                for cell_at in wetted([spot], reach):
                    wet[cell_at] = True
    return holes.keys()

def apart(a, x, z, y):
    # How far a lantern is from a cell, block by block along each axis.
    dx = a[0] - x
    dz = a[1] - z
    dy = a[2] - y
    return (dx if dx >= 0 else -dx) + (dz if dz >= 0 else -dz) + (dy if dy >= 0 else -dy)

def dark(lamps, ground, reach):
    # The first cell no lantern lights, or None: stops at the first, as one is enough to turn a layout down. The
    # lantern that lit the last cell is tried first, as the next cell is most often beside it.
    last = None
    for (x, z, y) in ground:
        if last != None and apart(last, x, z, y) <= reach:
            continue
        last = None
        for lamp in lamps:
            if apart(lamp, x, z, y) <= reach:
                last = lamp
                break
        if last == None:
            return (x, z, y)
    return None

def nearby(spots, taken, x, z, width, depth):
    # The free spot nearest (x, z): looked for ring by ring outward while the rings are smaller than the spots
    # themselves, and among all the spots once they are not, so a sparse field costs no more than a full one.
    for r in range(width + depth):
        if (2 * r + 1) * (2 * r + 1) > 2 * len(spots):
            break
        best = None
        far = 0
        for dx in range(-r, r + 1):
            for dz in ([-r, r] if magnitude(dx) != r else range(-r, r + 1)):
                at = (x + dx, z + dz)
                if at in spots and at not in taken:
                    gap = dx * dx + dz * dz
                    if best == None or gap < far:
                        best = at
                        far = gap
        if best != None:
            return best
    best = None
    far = 0
    for (cx, cz) in spots:
        if (cx, cz) in taken:
            continue
        gap = (cx - x) * (cx - x) + (cz - z) * (cz - z)
        if best == None or gap < far:
            best = (cx, cz)
            far = gap
    return best

def light(spots, ground, fixed, width, depth, reach, high):
    # A lantern keeps monsters off every cell it lights at all. Lay the fewest lanterns an even grid over the
    # whole field needs so that every cell a mob could stand on is within reach of one. A lantern stands `high`
    # above the ground it lights, which its reach across the field gives up.
    grids = []
    for across in range(1, width + 1):
        for down in range(1, depth + 1):
            if (-(-width // across)) // 2 + (-(-depth // down)) // 2 + high <= reach:
                grids.append((across * down, magnitude(float(width) / across - float(depth) / down), across, down))
    for (_, _, across, down) in sorted(grids)[:6]:
        picks = {}
        for j in range(across):
            for i in range(down):
                spot = nearby(spots, picks, width * (2 * j + 1) // (2 * across), depth * (2 * i + 1) // (2 * down), width, depth)
                if spot != None:
                    picks[spot] = True
        lamps = fixed + [(x, z, spots[(x, z)]) for (x, z) in picks]
        if dark(lamps, ground, reach) == None:
            return picks.keys()
    # No even grid will do on this ground: lay a lantern by each cell still dark, in turn.
    picks = {}
    lamps = list(fixed)
    last = None
    for (x, z, y) in ground:
        if last != None and apart(last, x, z, y) <= reach:
            continue
        last = None
        for lamp in lamps:
            if apart(lamp, x, z, y) <= reach:
                last = lamp
                break
        if last == None:
            # The nearest spot may stand too far above or below this cell to light it; then no lantern is laid
            # for it, as one that lights nothing here only adds to the field.
            spot = nearby(spots, picks, x, z, width, depth)
            if spot == None or apart((spot[0], spot[1], spots[spot]), x, z, y) > reach:
                continue
            picks[spot] = True
            last = (spot[0], spot[1], spots[spot])
            lamps.append(last)
    return picks.keys()

def palette(items):
    if len(items) < 2:
        return items
    return mix({item: 1 for item in items})

def draw(site):
    zone = site.zone.sized(height = 1)
    if zone.width < 6 or zone.depth < 6 or zone.width > 56 or zone.depth > 56:
        reject("error.field.size")
    rise = site.count("rise")
    spacing = site.count("spacing")
    if rise < 1 or rise > 4:
        reject("error.field.height")
    if spacing < 2 or spacing > 8:
        reject("error.field.spacing")
    for key in ["soil", "bund", "wall", "cap", "path", "steps", "fence", "gate", "lamp"]:
        if not site.items(key):
            reject("error.material", translated(key))
    if site.survey(zone).water > 0.2:
        reject("error.field.water")
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
        reject("error.field.steep")
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
    watered = {}
    for key in sorted(strips.keys()):
        strip = strips[key]
        thirsty = {at: True for at in strip if at not in watered}
        if thirsty:
            for hole in hydrate(thirsty, [at for at in strip if at not in spill], reach):
                holes.append(hole)
                soak(watered, level, hole, reach)
    turf = []
    # Where a terrace narrows to nothing but its brink there is no cell that would hold water; water it from the
    # nearest one that will, on its level or the one above, and leave it in turf if there is none.
    for at in sorted(open_cells.keys()):
        if at in watered:
            continue
        spot = None
        far = 0
        for dx in range(-reach, reach + 1):
            for dz in range(-reach, reach + 1):
                near = (at[0] + dx, at[1] + dz)
                if near in open_cells and near not in spill and level[near] - level[at] >= 0 and level[near] - level[at] <= 1:
                    if spot == None or dx * dx + dz * dz < far:
                        spot = near
                        far = dx * dx + dz * dz
        if spot != None:
            holes.append(spot)
            soak(watered, level, spot, reach)
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
    spots = {at: level[at] + POST + 1 for at in open_cells if at not in wet}
    posts = list(light(spots, ground_cells, fixed, width, depth, 13, POST))

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
        poles.append(cell(work, x, z, level[(x, z)], height = POST + 1))
        lights.append(cell(work, x, z, level[(x, z)] + POST + 1))
    if poles:
        parts.append(part("post", group(poles), site.items("fence")))
    if lights:
        parts.append(part("lamp", group(lights), site.items("lamp")))
    report("terraces", count)
    report("water", len(holes))
    report("lamps", len(lights))
    report("kept", len(kept))
    return parts
