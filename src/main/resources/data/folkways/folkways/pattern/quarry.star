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
        fail("A quarry must be 8–64 blocks on each side / 采石场每边须为 8–64 格")
    depth = site.count("depth")
    if depth < 0 or depth > 16:
        fail("Quarry depth must be 0–16 blocks / 采石场深度须为 0–16 格")
    if site.survey(zone).water > 0.2:
        fail("The quarry is under water / 采石场大部分在水下")
    width = zone.width
    deep = zone.depth
    top = {}
    rim = None
    for z in range(deep):
        for x in range(width):
            spot = zone.at(x = x, z = z).origin
            ground = site.ground(spot.x, spot.z)
            if ground == None:
                fail("No ground under the quarry / 采石场下方没有地面")
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
        fail("Nothing here stands above the quarry's floor / 这里没有高于采石场底面的地面")
    report("floor", floor)
    report("cuts", len(dig))
    return [clear("dig", dig)]
