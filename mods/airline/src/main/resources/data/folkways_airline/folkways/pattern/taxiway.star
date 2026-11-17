load("folkways_airline:airfield.star", "GRAY", "WHITE", "YELLOW", "magnitude", "paint", "sign", "fillets", "pave_line")
accepts = ["path"]
capture = 32
knobs = [count("width", 3, 63, default = 9, icon = "minecraft:gray_concrete"),
    flag("centerline", default = True, icon = "minecraft:yellow_concrete")]
lang = {
    "en_us": {
        "title": "Taxi area",
        "centerline": "Optional yellow guide line",
        "width": "Grade width (odd; max wingspan = width − 2)",
        "error.taxiway.path": "Use level path segments, at most 512 blocks in total"
    },
    "zh_cn": {
        "title": "滑行区域",
        "centerline": "黄色引导线（可选）",
        "width": "分级铺面宽度（奇数；准入翼展＝宽度－2）",
        "error.taxiway.path": "请使用同高路径，总长不超过 512 格"
    },
    "zh_hk": {
        "title": "滑行區域",
        "centerline": "黃色引導線（可選）",
        "width": "分級鋪面寬度（奇數；准入翼展＝寬度－2）",
        "error.taxiway.path": "請使用同高路徑，總長不超過 512 格"
    },
    "zh_tw": {
        "title": "滑行區域",
        "centerline": "黃色引導線（可選）",
        "width": "分級鋪面寬度（奇數；准入翼展＝寬度－2）",
        "error.taxiway.path": "請使用同高路徑，總長不超過 512 格"
    }
}

def yellow(site, listed, p):
    return listed[p] == YELLOW if p in listed else site.matches(point(p[0], p[1], p[2]), YELLOW)

def draw(site):
    width = site.count("width")
    if width % 2 == 0:
        reject("error.width")
    marks = site.path.points
    listed = {}
    center = {}
    length = 0
    for i in range(len(marks) - 1):
        a, b = marks[i], marks[i + 1]
        if a.y != b.y or (a.x == b.x and a.z == b.z):
            reject("error.taxiway.path")
        dx, dz = sign(b.x - a.x), sign(b.z - a.z)
        run = magnitude(b.x - a.x) + magnitude(b.z - a.z)
        length += run
        if length > 511:
            reject("error.taxiway.path")
        if a.x != b.x and a.z != b.z:
            pave_line(listed, center, (a.x, a.y, a.z), (b.x, b.y, b.z), width)
            continue
        for d in range(run + 1):
            p = (a.x + dx * d, a.y, a.z + dz * d)
            center[p] = True
            reach = width // 2
            for side in range(-reach, reach + 1):
                listed[(p[0] - dz * side, p[1], p[2] + dx * side)] = GRAY
        if i > 0:
            for x in range(-width // 2 + 1, width // 2 + 1):
                for z in range(-width // 2 + 1, width // 2 + 1):
                    listed[(a.x + x, a.y, a.z + z)] = GRAY
    fillets(listed, center, width)
    for p in list(listed):
        at = point(p[0], p[1], p[2])
        if site.matches(at, WHITE):
            listed[p] = WHITE
        elif site.matches(at, YELLOW):
            listed[p] = YELLOW
    if site.flag("centerline"):
        for p in center:
            if listed[p] != WHITE:
                listed[p] = YELLOW
    report("max_wingspan", width - 2)
    return paint(listed)
