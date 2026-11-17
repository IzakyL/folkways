load("folkways_airline:airfield.star", "GRAY", "WHITE", "clearance", "foundation", "runway", "YELLOW", "paint", "raster", "rounded", "pave_line", "magnitude")
accepts = ["path"]
capture = {"around": 64, "up": "all", "down": 2}
knobs = [
    count("width", 5, 63, default = 15, icon = "minecraft:gray_concrete"),
    flag("entries", default = True, icon = "minecraft:yellow_concrete"),
    count("taxi_width", 3, 63, default = 9, icon = "minecraft:gray_concrete"),
    flag("clearance", default = False, icon = "minecraft:iron_shovel"),
]
lang = {
    "en_us": {
        "entries": "Taxi entries and parallel taxiway",
        "taxi_width": "Taxi grade width (odd; max wingspan = width − 2)",
        "title": "Runway",
        "width": "Width",
        "clearance": "Clear approaches (1:20)"
    },
    "zh_cn": {
        "entries": "滑行入口与平行滑行道",
        "taxi_width": "滑行道分级宽度（奇数；准入翼展＝宽度－2）",
        "title": "跑道",
        "width": "宽度",
        "clearance": "净空平整（1:20）"
    },
    "zh_hk": {
        "entries": "滑行入口與平行滑行道",
        "taxi_width": "滑行道分級寬度（奇數；准入翼展＝寬度－2）",
        "title": "跑道",
        "width": "寬度",
        "clearance": "淨空平整（1:20）"
    },
    "zh_tw": {
        "entries": "滑行入口與平行滑行道",
        "taxi_width": "滑行道分級寬度（奇數；准入翼展＝寬度－2）",
        "title": "跑道",
        "width": "寬度",
        "clearance": "淨空平整（1:20）"
    }
}

def entry(spec, d, side):
    a, b, dx, dz, length, width = spec
    return (rounded(a.x + dx * d + dz * side), a.y, rounded(a.z + dz * d - dx * side))

def draw(site):
    spec = runway(site)
    a, b, dx, dz, length, width = spec
    occupied = raster(spec)
    distance = (b.x - a.x) * dx + (b.z - a.z) * dz
    listed = {}
    fill = []
    dig = []
    keys = max(4, min(12, length // 16))
    aim = max(2, min(6, length // 48))
    for p in occupied:
        d, side = occupied[p]
        ground = site.ground(p[0], p[2])
        if ground == None:
            reject("error.ground")
        if magnitude(ground - a.y) > 8:
            reject("error.earthworks")
        fill.extend([(p[0], y, p[2]) for y in range(ground + 1, a.y)])
        dig.extend([(p[0], y, p[2]) for y in range(a.y + 1, max(a.y + 4, ground) + 1)])
        edge = magnitude(side) >= width / 2.0 - 1
        threshold = (d >= 2 and d < keys + 2) or (d > distance - keys - 2 and d <= distance - 2)
        stripe = threshold and magnitude(side) < width / 2.0 - 2 and rounded(side) % 2 == 0
        centre = d > keys + 3 and d < distance - keys - 3 and magnitude(side) < 0.65 and int(d) % 10 < 6
        aiming = (magnitude(d - distance / 6) < aim / 2.0 or magnitude(d - distance * 5 / 6) < aim / 2.0)
        aiming = aiming and width >= 9 and magnitude(side) >= 2 and magnitude(side) < width / 4.0
        listed[p] = WHITE if edge or stripe or centre or aiming else GRAY
    parts = []
    if fill:
        parts.append(part("foundation", cells(fill), [GRAY]))
    if dig:
        parts.append(clear("grading", cells(dig)))
    if site.flag("entries"):
        taxi_width = site.count("taxi_width")
        if taxi_width % 2 == 0:
            reject("error.width")
        offset = width // 2 + taxi_width * 2 + 8
        taxi = {}
        center = {}
        pave_line(taxi, center, entry(spec, 12, offset), entry(spec, distance - 12, offset), taxi_width, occupied)
        for d in [12, distance - 12]:
            pave_line(taxi, center, entry(spec, d, width / 2.0), entry(spec, d, offset), taxi_width, occupied)
        for d, shift in [(distance / 3, offset), (distance * 2 / 3, -offset)]:
            pave_line(taxi, center, entry(spec, d, width / 2.0), entry(spec, d + shift, offset), taxi_width, occupied)
        for p in center:
            taxi[p] = YELLOW
        parts.extend(paint(taxi))
        report("taxi_wingspan", taxi_width - 2)
        report("entries", 4)
    parts.extend(paint(listed))
    if site.flag("clearance"):
        parts.extend(clearance(site, spec))
    return parts
