load("folkways:materials.star", "LOGS", "MASONRY", "PLANKS", "ROOFING", "offer")

detail = "bits"
accepts = ["zone", "path"]
knobs = [
    count("bay", 3, 6, default = 4),
    count("width", 3, 5, default = 3),
    count("height", 3, 5, default = 3),
    flag("railing", default = True),
    items("floor", offer(["minecraft:stone_bricks"], MASONRY), default = ["minecraft:stone_bricks"]),
    items("pillar", offer(["minecraft:stripped_spruce_log"], LOGS), default = ["minecraft:stripped_spruce_log"]),
    items("beam", offer(["minecraft:spruce_planks"], PLANKS), default = ["minecraft:spruce_planks"]),
    items("tiles", offer(["minecraft:gray_concrete"], ROOFING), default = ["minecraft:gray_concrete"]),
]
lang = {
    "en_us": {
        "title": "Curved gallery",
        "bay": "Bay spacing",
        "width": "Walk width",
        "height": "Column height",
        "railing": "Railing",
        "floor": "Stone paving",
        "pillar": "Tapered columns",
        "beam": "Timber",
        "tiles": "Dark roof",
    },
    "zh_cn": {
        "title": "曲顶长廊",
        "bay": "开间",
        "width": "廊宽",
        "height": "柱高",
        "railing": "栏杆",
        "floor": "石铺",
        "pillar": "收分圆柱",
        "beam": "木构",
        "tiles": "深色屋面",
    },
    "zh_hk": {
        "title": "曲頂長廊",
        "bay": "開間",
        "width": "廊寬",
        "height": "柱高",
        "railing": "欄杆",
        "floor": "石鋪",
        "pillar": "收分圓柱",
        "beam": "木構",
        "tiles": "深色屋面",
    },
    "zh_tw": {
        "title": "曲頂長廊",
        "bay": "開間",
        "width": "廊寬",
        "height": "柱高",
        "railing": "欄杆",
        "floor": "石鋪",
        "pillar": "收分圓柱",
        "beam": "木構",
        "tiles": "深色屋面",
    },
}

def root(value):
    guess = max(1.0, value)
    for _ in range(12):
        guess = (guess + value / guess) / 2.0
    return guess

def mix(a, b, t):
    return [a[i] + (b[i] - a[i]) * t for i in range(3)]

def distance(a, b):
    dx = a[0] - b[0]
    dz = a[2] - b[2]
    return root(dx * dx + dz * dz)

def rounded(points, radius):
    out = [points[0]]
    for i in range(1, len(points) - 1):
        a = points[i - 1]
        b = points[i]
        c = points[i + 1]
        left = mix(b, a, min(0.4, radius / distance(a, b)))
        right = mix(b, c, min(0.4, radius / distance(b, c)))
        for j in range(9):
            t = j / 8.0
            out.append(mix(mix(left, b, t), mix(b, right, t), t))
    out.append(points[-1])
    return out

def shifted(points, side, up):
    out = []
    for i in range(len(points)):
        a = points[max(0, i - 1)]
        b = points[min(len(points) - 1, i + 1)]
        length = distance(a, b)
        out.append([points[i][0] - (b[2] - a[2]) * side / length,
            points[i][1] + up, points[i][2] + (b[0] - a[0]) * side / length])
    return out

def line(site, room, points, radius, material = "beam"):
    return part(material, tube(room, points, radius), site.items(material))

def draw(site):
    w = site.count("width")
    h = site.count("height")
    bay = site.count("bay")
    room = site.zone
    raw = [[p.x, p.y, p.z] for p in site.path.points] if site.kind == "path" else [
        [w / 2.0, 0, 0.5], [w / 2.0, 0, bay * 3 + 0.5]]
    if site.kind == "path":
        room = room.rotate({"north": 2, "east": 1, "south": 0, "west": 3}[site.facing]).mirror()
        low = [min([p[i] for p in raw]) for i in range(3)]
        raw = [[p[i] - low[i] + (0.5 if i != 1 else 0) for i in range(3)] for p in raw]
    points = rounded(raw, bay * 0.65)
    roof_width = w + 1.6
    out = [part("floor", profiled_sweep(room, points, w + 0.5, -1, 0, 0, 1), site.items("floor")),
        part("roof", profiled_sweep(room, points, roof_width, h - 0.15, 1.15, 0.30, 0.25, flat_ends = True), site.items("tiles"))]
    # Open timber ties replace a full underlay, keeping the thicker tile rolls within the material budget.
    # A low rolled crest and transverse tile rolls make the roof readable without noisy stone.
    out.append(line(site, room, shifted(points, 0, h + 1.25), 0.1875, "tiles"))
    walked_tiles = 0.0
    next_tile = 0.0
    for i in range(1, len(points)):
        a = points[i - 1]
        b = points[i]
        length = distance(a, b)
        right = [-(b[2] - a[2]) / length, 0, (b[0] - a[0]) / length]
        for j in range(int(length / 0.75) + 2):
            position = next_tile + j * 0.75
            if position >= walked_tiles and position <= walked_tiles + length:
                center = mix(a, b, (position - walked_tiles) / length)
                roll = []
                for k in range(25):
                    u = 2 * k / 24.0 - 1
                    crown = 1 - u * u
                    edge = u * u * u * u
                    roll.append([center[0] + right[0] * u * roof_width / 2.0,
                        center[1] + h + 0.10 + 1.15 * crown * crown + 0.30 * edge * edge,
                        center[2] + right[2] * u * roof_width / 2.0])
                out.append(line(site, room, roll, 0.125, "tiles"))
        walked_tiles += length
        next_tile = (int(walked_tiles / 0.75) + 1) * 0.75
    # Rolled verge mouldings finish both planar gables.
    for end, neighbor in [[points[0], points[1]], [points[-1], points[-2]]]:
        length = distance(end, neighbor)
        right = [-(neighbor[2] - end[2]) / length, 0, (neighbor[0] - end[0]) / length]
        verge = []
        for j in range(25):
            u = 2 * j / 24.0 - 1
            crown = 1 - u * u
            edge = u * u * u * u
            verge.append([end[0] + right[0] * u * roof_width / 2.0,
                end[1] + h - 0.025 + 1.15 * crown * crown + 0.30 * edge * edge,
                end[2] + right[2] * u * roof_width / 2.0])
        out.append(line(site, room, verge, 0.16, "tiles"))
    for side in [-1, 1]:
        out.append(line(site, room, shifted(points, side * roof_width / 2.0, h + 0.275), 0.125, "tiles"))
        out.append(line(site, room, shifted(points, side * (w / 2.0 - 0.25), h + 0.05), 0.17))
        if site.flag("railing"):
            for up in [0.28, 0.95]:
                out.append(line(site, room, shifted(points, side * (w / 2.0 - 0.25), up), 0.125))
    # Evenly spaced frames follow the same rounded centreline as the roof and paving.
    walked = 0.0
    next_post = 0.0
    frames = []
    for i in range(1, len(points)):
        a = points[i - 1]
        b = points[i]
        length = distance(a, b)
        for j in range(int(length / bay) + 2):
            position = next_post + j * bay
            if position <= walked + length and position >= walked:
                frames.append([mix(a, b, (position - walked) / length),
                    [-(b[2] - a[2]) / length, 0, (b[0] - a[0]) / length]])
        walked += length
        next_post = (int(walked / bay) + 1) * bay
    end = points[-1]
    prev = points[-2]
    length = distance(prev, end)
    if distance(frames[-1][0], end) > 0.5:
        frames.append([end, [-(end[2] - prev[2]) / length, 0, (end[0] - prev[0]) / length]])
    for center, right in frames:
        x, y, z = center
        # Thin tapered circular shafts keep the passage open.
        for side in [-1, 1]:
            px = x + right[0] * side * (w / 2.0 - 0.25)
            pz = z + right[2] * side * (w / 2.0 - 0.25)
            column = offset_scope(room, px - 0.5, y, pz - 0.5).sized(width = 1, height = h, depth = 1)
            out.append(part("pillar", frustum(column, 23, 17, height = h * 100), site.items("pillar")))
            out.append(line(site, room, [[px, y - 0.05, pz], [px, y + 0.18, pz]], 0.31, "floor"))
            # Concave knee braces join each column to the transverse curved tie.
            brace = []
            for j in range(9):
                t = j / 8.0
                reach = 0.85 * t
                brace.append([px - right[0] * side * reach, y + h - 0.8 + 0.8 * (2 * t - t * t),
                    pz - right[2] * side * reach])
            out.append(line(site, room, brace, 0.125))
        tie = []
        for j in range(17):
            t = 2 * j / 16.0 - 1
            u = t * (w / 2.0 + 0.3) / (roof_width / 2.0)
            crown = 1 - u * u
            edge = u * u * u * u
            tie.append([x + right[0] * t * (w / 2.0 + 0.3),
                y + h - 0.30 + 1.15 * crown * crown + 0.3 * edge * edge,
                z + right[2] * t * (w / 2.0 + 0.3)])
        out.append(line(site, room, tie, 0.15))
    return out
