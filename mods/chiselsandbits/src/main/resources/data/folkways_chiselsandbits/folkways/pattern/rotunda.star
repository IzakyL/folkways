load("folkways:materials.star", "MASONRY", "offer")

detail = "bits"
accepts = ["zone"]
knobs = [
    count("radius", 3, 4, default = 3, icon = "minecraft:smooth_quartz"),
    count("height", 3, 4, default = 3, icon = "minecraft:smooth_quartz"),
    items("stone", offer(["minecraft:smooth_quartz"], MASONRY),
        default = ["minecraft:smooth_quartz"], icon = "minecraft:smooth_quartz"),
    items("trim", offer(["minecraft:smooth_sandstone"], MASONRY),
        default = ["minecraft:smooth_sandstone"], icon = "minecraft:smooth_sandstone"),
    items("floor", offer(["minecraft:polished_diorite"], MASONRY),
        default = ["minecraft:polished_diorite"], icon = "minecraft:polished_diorite"),
]
lang = {
    "en_us": {
        "title": "Rotunda",
        "radius": "Dome radius",
        "height": "Column height",
        "stone": "Marble",
        "trim": "Floor, cornices and ribs",
        "floor": "Steps"
    },
    "zh_cn": {
        "title": "穹顶圆厅",
        "radius": "穹顶半径",
        "height": "柱高",
        "stone": "大理石",
        "trim": "地面、檐口与肋",
        "floor": "台阶"
    },
    "zh_hk": {
        "title": "穹頂圓廳",
        "radius": "穹頂半徑",
        "height": "柱高",
        "stone": "大理石",
        "trim": "地面、簷口與肋",
        "floor": "台階"
    },
    "zh_tw": {
        "title": "穹頂圓廳",
        "radius": "穹頂半徑",
        "height": "柱高",
        "stone": "大理石",
        "trim": "地面、簷口與肋",
        "floor": "臺階"
    }
}

def ring(room, radius, base, height, thickness):
    outer = frustum(room, radius, radius, base = base, height = height)
    inner = frustum(room, radius - thickness, radius - thickness, base = base, height = height)
    return subtract(outer, inner)

def draw(site):
    r = site.count("radius")
    h = site.count("height")
    d = 2 * r
    room = site.zone.sized(width = d + 2, height = h + r + 2, depth = d + 2)
    step = room.sized(height = 1)
    out = []
    for radius, base, height in [(r * 100 + 100, 0, 34), (r * 100 + 65, 34, 33), (r * 100 + 30, 67, 33)]:
        out.append(part("step", frustum(step, radius, radius, base = base, height = height), site.items("floor")))
    body = room.at(x = 1, y = 1, z = 1).sized(width = d, height = h, depth = d)
    out.append(part("floor", frustum(body, r * 100, r * 100, height = 25), site.items("trim")))
    # Paired front columns frame a clear entrance through the open colonnade.
    columns = [(r - 2, 0), (r + 1, 0), (d - 1, r - 2), (d - 1, r + 1),
               (r + 1, d - 1), (r - 2, d - 1), (0, r + 1), (0, r - 2)]
    for x, z in columns:
        col = body.at(x = x, z = z).sized(width = 1, depth = 1)
        out.append(part("column", frustum(col, 36, 29, base = 25), site.items("stone")))
        out.append(part("capital", frustum(col, 47, 47, base = h * 100 - 25), site.items("trim")))
    drum = body.at(y = h).sized(height = 1)
    out.append(part("entablature", ring(drum, r * 100, 0, 25, 65), site.items("trim")))
    out.append(part("drum", ring(drum, r * 100 - 8, 25, 50, 55), site.items("stone")))
    out.append(part("cornice", ring(drum, r * 100, 75, 25, 65), site.items("trim")))
    spring = drum.at(y = 1).sized(height = r)
    shell = subtract(dome(spring), dome(spring.at(x = 1, z = 1).sized(
        width = d - 2, height = r - 1, depth = d - 2)))
    eye = cylinder(spring.at(x = r - 1, z = r - 1).sized(width = 2, depth = 2))
    out.append(part("dome", subtract(shell, eye), site.items("stone")))
    # Raised meridians interrupt the voxel rings; the shell itself uses quiet smooth marble.
    directions = [[1, 0], [0.7071, 0.7071], [0, 1], [-0.7071, 0.7071],
        [-1, 0], [-0.7071, -0.7071], [0, -1], [0.7071, -0.7071]]
    profile = [[1, 0], [0.9914, 0.1305], [0.9659, 0.2588], [0.9239, 0.3827],
        [0.8660, 0.5], [0.7934, 0.6088], [0.7071, 0.7071], [0.6088, 0.7934],
        [0.5, 0.8660], [0.3827, 0.9239], [0.2588, 0.9659], [0, 1]]
    for dx, dz in directions:
        points = [[r + dx * (r + 0.03) * across, (r + 0.03) * up,
            r + dz * (r + 0.03) * across] for across, up in profile]
        out.append(part("rib", subtract(tube(spring, points, 0.16), eye), site.items("trim")))
    eye_height = r * 1.0
    for _ in range(8):
        eye_height = (eye_height + (r * r - 1.12) / eye_height) / 2.0
    rim = []
    for dx, dz in [[1, 0], [0.9239, 0.3827], [0.7071, 0.7071], [0.3827, 0.9239],
                   [0, 1], [-0.3827, 0.9239], [-0.7071, 0.7071], [-0.9239, 0.3827],
                   [-1, 0], [-0.9239, -0.3827], [-0.7071, -0.7071], [-0.3827, -0.9239],
                   [0, -1], [0.3827, -0.9239], [0.7071, -0.7071], [0.9239, -0.3827], [1, 0]]:
        rim.append([r + 1.06 * dx, eye_height + 0.04, r + 1.06 * dz])
    out.append(part("oculus-rim", tube(spring, rim, 0.16), site.items("trim")))
    return out
