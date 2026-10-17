accepts = ["zone"]
knobs = [
    count("depth", 0, 16, default = 3, icon = "minecraft:stone_pickaxe"),
]
lang = {
    "en_us": {
        "title": "Quarry",
        "depth": "Depth",
    },
    "zh_cn": {
        "title": "采石场",
        "depth": "深度",
    },
    "zh_hk": {
        "title": "採石場",
        "depth": "深度",
    },
    "zh_tw": {
        "title": "採石場",
        "depth": "深度",
    },
}

# An open quarry: the ground inside the zone is cut away from the top down to a flat floor, depth below the lowest
# ground on the zone's edge. The sides step down a block a cell, so the floor is walked onto from wherever the ground
# outside is level with the edge; where the hill stands above the edge it is left as a face.

# How far above its floor a column is cut: a hillside taller than this keeps its top.
TALLEST = 48

def cell(work, x, z, y, width = 1, height = 1):
    return work.at(x = x, z = z, y = y - work.origin.y).sized(width = width, depth = 1, height = height)

def draw(site):
    zone = site.zone.sized(height = 1)
    if zone.width < 8 or zone.depth < 8 or zone.width > 64 or zone.depth > 64:
        reject("folkways.draft.error.quarry.size")
    depth = site.count("depth")
    if depth < 0 or depth > 16:
        reject("folkways.draft.error.quarry.depth")
    if site.survey(zone).water > 0.2:
        reject("folkways.draft.error.quarry.water")
    width = zone.width
    deep = zone.depth
    top = {}
    rim = None
    for z in range(deep):
        for x in range(width):
            spot = zone.at(x = x, z = z).origin
            ground = site.ground(spot.x, spot.z)
            if ground == None:
                reject("folkways.draft.error.quarry.ground")
            found = site.highest(spot.x, spot.z, "solid")
            top[(x, z)] = max(ground, found) if found != None else ground
            if (x == 0 or z == 0 or x == width - 1 or z == deep - 1) and (rim == None or ground < rim):
                rim = ground
    floor = rim - depth
    level = {}
    for z in range(deep):
        for x in range(width):
            level[(x, z)] = max(floor, rim - min(x, z, width - 1 - x, deep - 1 - z))

    # One cut to each run of a row that comes down to the same level, as tall as its highest column.
    dig = []
    for z in range(deep):
        start = None
        for x in range(width + 1):
            cut = x < width and top[(x, z)] > level[(x, z)]
            if start != None and cut and level[(x, z)] == level[(start, z)]:
                continue
            if start != None:
                y = level[(start, z)]
                high = min(max([top[(c, z)] for c in range(start, x)]), y + TALLEST)
                found = terrain(cell(zone, start, z, y + 1, width = x - start, height = high - y))
                if found != None:
                    dig.append(found)
            start = x if cut else None
    if not dig:
        reject("folkways.draft.error.quarry.empty")
    report("floor", floor)
    report("cuts", len(dig))
    return [clear("dig", dig)]
