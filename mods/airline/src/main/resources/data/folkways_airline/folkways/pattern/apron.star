load("folkways_airline:airfield.star", "GRAY", "WHITE", "YELLOW", "paint", "spot", "fillets", "magnitude")
accepts = ["zone"]
knobs = [count("stands", 1, 8, default = 2, icon = "minecraft:white_concrete"),
    count("size", 7, 127, default = 23, icon = "minecraft:gray_concrete")]
lang = {
    "en_us": {
        "title": "Apron",
        "stands": "Stand count",
        "size": "Stand spacing and lead-in length (odd)"
    },
    "zh_cn": {
        "title": "停机坪",
        "stands": "停机位数量",
        "size": "机位间距与引导线长度（奇数）"
    },
    "zh_hk": {
        "title": "停機坪",
        "stands": "停機位數量",
        "size": "機位間距與引導線長度（奇數）"
    },
    "zh_tw": {
        "title": "停機坪",
        "stands": "停機位數量",
        "size": "機位間距與引導線長度（奇數）"
    }
}

def draw(site):
    size = site.count("size")
    count = site.count("stands")
    if size % 2 == 0:
        reject("error.width")
    zone = site.zone
    width = count * (size + 2)
    depth = size + max(7, size // 2) + 4
    if zone.width < width or zone.depth < depth:
        reject("error.zone")
    listed = {}
    for x in range(width):
        for z in range(depth):
            listed[spot(zone, x, z)] = GRAY
    for i in range(count):
        center = size // 2 + 1 + i * (size + 2)
        for z in range(depth - size - 2, depth - 3):
            listed[spot(zone, center, z)] = YELLOW
        for x in range(center - 1, center + 2):
            listed[spot(zone, x, depth - 3)] = WHITE
    report("max_length", depth - 4)
    report("max_wingspan", size)
    return paint(listed)
