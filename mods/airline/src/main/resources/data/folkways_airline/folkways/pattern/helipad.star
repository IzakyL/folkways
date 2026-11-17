load("folkways_airline:airfield.star", "GRAY", "WHITE", "YELLOW", "magnitude", "paint", "spot")
accepts = ["zone"]
knobs = [
    count("size", 7, 31, default = 11, icon = "minecraft:white_concrete"),
    choice("heading", [option("ns", icon = "minecraft:compass"), option("ew", icon = "minecraft:compass")],
        default = "ns", icon = "minecraft:compass"),
    flag("circle", default = False, icon = "minecraft:yellow_concrete"),
]
lang = {
    "en_us": {
        "title": "Helipad",
        "size": "Side length (odd)",
        "heading": "H orientation",
        "heading.ns": "North–south",
        "heading.ew": "East–west",
        "circle": "TLOF ○"
    },
    "zh_cn": {
        "title": "直升机坪",
        "size": "边长（奇数）",
        "heading": "H 朝向",
        "heading.ns": "南北",
        "heading.ew": "东西",
        "circle": "TLOF ○"
    },
    "zh_hk": {
        "title": "直升機坪",
        "size": "邊長（奇數）",
        "heading": "H 朝向",
        "heading.ns": "南北",
        "heading.ew": "東西",
        "circle": "TLOF ○"
    },
    "zh_tw": {
        "title": "直升機坪",
        "size": "邊長（奇數）",
        "heading": "H 朝向",
        "heading.ns": "南北",
        "heading.ew": "東西",
        "circle": "TLOF ○"
    }
}

def draw(site):
    size = site.count("size")
    if size % 2 == 0:
        reject("error.width")
    zone = site.zone
    if zone.width < size or zone.depth < size:
        reject("error.zone")
    radius = size // 2
    reach = max(2, radius - 2)
    thick = max(1, (2 * reach + 1) // 9)
    across = max(1, int((2 * reach + 1) * 0.6) // 2)
    inner = across - thick + 1
    listed = {}
    for x in range(size):
        for z in range(size):
            a, b = (z - radius, x - radius) if site.choice("heading") == "ew" else (x - radius, z - radius)
            material = GRAY
            if site.flag("circle") and magnitude((x - radius) * (x - radius) + (z - radius) * (z - radius) - (radius - 1) * (radius - 1)) <= radius - 1:
                material = YELLOW
            if x == 0 or z == 0 or x == size - 1 or z == size - 1:
                material = WHITE
            elif magnitude(a) <= across and magnitude(b) <= reach and (magnitude(a) >= inner or magnitude(b) < thick):
                material = WHITE
            listed[spot(zone, x, z)] = material
    return paint(listed)
