load("folkways_vanilla:grading.star", "centreline", "earthworks")
lang = {
    "en_us": {
        "error.width": "Width must be odd",
        "error.runway.points": "Mark exactly two distinct level points",
        "error.runway.length": "Runway length must be 48–512 blocks (including both ends)",
        "error.earthworks": "The foundation needs more than 8 blocks of grading",
        "error.ground": "No ground beneath the foundation",
        "error.clearance.size": "Clearance exceeds 150000 cells; clear tall obstacles in smaller stages",
        "error.zone": "The marked zone is too small for these settings"
    },
    "zh_cn": {
        "error.width": "宽度必须为奇数",
        "error.runway.points": "请标记同高的两个不同点",
        "error.runway.length": "跑道长度须为 48–512 格（包含两端）",
        "error.earthworks": "地基挖填超过 8 格",
        "error.ground": "地基下方没有地面",
        "error.clearance.size": "净空挖掘超过 150000 格；请分段清除高障碍",
        "error.zone": "标记区域不足以容纳当前设置"
    },
    "zh_hk": {
        "error.width": "寬度必須為奇數",
        "error.runway.points": "請標記同高的兩個不同點",
        "error.runway.length": "跑道長度須為 48–512 格（包含兩端）",
        "error.earthworks": "地基挖填超過 8 格",
        "error.ground": "地基下方沒有地面",
        "error.clearance.size": "淨空挖掘超過 150000 格；請分段清除高障礙",
        "error.zone": "標記區域不足以容納目前設定"
    },
    "zh_tw": {
        "error.width": "寬度必須為奇數",
        "error.runway.points": "請標記同高的兩個不同點",
        "error.runway.length": "跑道長度須為 48–512 格（包含兩端）",
        "error.earthworks": "地基挖填超過 8 格",
        "error.ground": "地基下方沒有地面",
        "error.clearance.size": "淨空挖掘超過 150000 格；請分段清除高障礙",
        "error.zone": "標記區域不足以容納目前設定"
    }
}

# Keep these in step with Airport; approachGeometryMatchesJava exercises every distance.
APPROACH_LENGTH = 128
APPROACH_SLOPE = 20
GRAY = "minecraft:gray_concrete"
WHITE = "minecraft:white_concrete"
YELLOW = "minecraft:yellow_concrete"

def magnitude(n):
    return -n if n < 0 else n

def sign(n):
    return 0 if n == 0 else (1 if n > 0 else -1)

def runway(site):
    width = site.count("width")
    if width % 2 == 0:
        reject("error.width")
    marks = site.path.points
    if len(marks) != 2:
        reject("error.runway.points")
    a, b = marks
    if a.y != b.y or (a.x == b.x and a.z == b.z):
        reject("error.runway.points")
    distance = site.path.length
    length = int(distance + 0.5) + 1
    if length < 48 or length > 512:
        reject("error.runway.length")
    if a.x == b.x or a.z == b.z:
        return a, b, sign(b.x - a.x), sign(b.z - a.z), length, width
    return a, b, (b.x - a.x) / distance, (b.z - a.z) / distance, length, width

def clearance(site, spec):
    a, b, dx, dz, length, width = spec
    total = 0
    parts = []
    for end, outward in [(a, -1), (b, 1)]:
        removed = []
        for d in range(1, APPROACH_LENGTH + 1):
            # Scanner compares highest occupied y + 1 with runway y + 1 + d / slope.
            low = a.y + 1 + d // APPROACH_SLOPE
            for side in range(-width // 2 + 1, width // 2 + 1):
                x = int((end.x + 0.5 + outward * dx * d - dz * side) // 1)
                z = int((end.z + 0.5 + outward * dz * d + dx * side) // 1)
                high = site.highest(x, z, "!air")
                high = max(low, high if high != None else low)
                if total + high - low + 1 > 150000:
                    reject("error.clearance.size")
                total += high - low + 1
                removed.extend([(x, y, z) for y in range(low, high + 1)])
        parts.append(clear("clearance", cells(removed)))
    report("approach_length", APPROACH_LENGTH)
    report("approach_slope", APPROACH_SLOPE)
    report("clearance_cells", total)
    return parts

def foundation(site, spec):
    a, b, dx, dz, length, width = spec
    line = centreline(site, width)
    depth = 0
    for d in range(length):
        for side in range(-(width // 2), width // 2 + 1):
            x = a.x + dx * d - dz * side
            z = a.z + dz * d + dx * side
            ground = site.ground(x, z)
            if ground == None:
                reject("error.ground")
            depth = max(depth, magnitude(ground - a.y))
    if depth > 8:
        reject("error.earthworks")
    stations = [{"s": 0, "y": a.y, "bored": False},
        {"s": length - 1, "y": a.y, "bored": False}]
    works = earthworks(site, line, {"stations": stations, "depth": depth}, width, shoulder = 0, canopy = 4)
    parts = []
    if works["dig"]:
        parts.append(clear("grading", cells(works["dig"])))
    if works["fill"]:
        parts.append(part("foundation", cells(works["fill"]), [GRAY]))
    return parts

def spot(zone, x, z, y = 0):
    p = zone.sized(width = 1, height = 1, depth = 1).at(x = x, y = y, z = z).center()
    return (p.x, p.y, p.z)

def paint(listed):
    parts = []
    for material in [GRAY, WHITE, YELLOW]:
        positions = [p for p in listed if listed[p] == material]
        if positions:
            parts.append(part("paving", cells(positions), [material]))
    return parts

# Fill the inside of orthogonal turns. Half each straight is retained for the next fillet.
def fillets(listed, center, width):
    directions = [(1, 0), (0, 1), (-1, 0), (0, -1)]
    for p in center:
        arms = []
        for dx, dz in directions:
            reach = 0
            for d in range(1, width * 4 + 1):
                q = (p[0] + dx * d, p[1], p[2] + dz * d)
                if q not in center:
                    break
                reach = d
                neighbors = 0
                for sx, sz in directions:
                    if (q[0] + sx, q[1], q[2] + sz) in center:
                        neighbors += 1
                if neighbors != 2:
                    break
            arms.append(reach // 2)
        for i in range(4):
            j = (i + 1) % 4
            radius = min(width * 2, arms[i], arms[j])
            if radius < 1:
                continue
            ax, az = directions[i]
            bx, bz = directions[j]
            for a in range(radius + 1):
                for b in range(radius + 1):
                    inner = max(0, radius - width // 2 - 1)
                    if (radius - a) * (radius - a) + (radius - b) * (radius - b) >= inner * inner:
                        q = (p[0] + ax * a + bx * b, p[1], p[2] + az * a + bz * b)
                        if q not in listed:
                            listed[q] = GRAY

# Coordinates describe cell centres; keep the same half-cell caps as Footprint.Oriented.
def raster(spec):
    a, b, dx, dz, length, width = spec
    distance = (b.x - a.x) * dx + (b.z - a.z) * dz
    result = {}
    for x in range(min(a.x, b.x) - width, max(a.x, b.x) + width + 1):
        for z in range(min(a.z, b.z) - width, max(a.z, b.z) + width + 1):
            d = (x - a.x) * dx + (z - a.z) * dz
            side = -(x - a.x) * dz + (z - a.z) * dx
            if d >= -0.50000001 and d <= distance + 0.50000001 and magnitude(side) <= width / 2.0 + 0.00000001:
                result[(x, a.y, z)] = (d, side)
    return result

def rounded(x):
    return int((x + 0.5) // 1)

# Voxel traversal with one axis per step, even at exact corner crossings.
def staircase(a, b):
    x, y, z = a
    bx, by, bz = b
    nx, nz = magnitude(bx - x), magnitude(bz - z)
    sx, sz = sign(bx - x), sign(bz - z)
    ix, iz = 0, 0
    result = [(x, y, z)]
    for step in range(nx + nz):
        if ix < nx and (iz == nz or (2 * ix + 1) * nz <= (2 * iz + 1) * nx):
            x += sx
            ix += 1
        else:
            z += sz
            iz += 1
        result.append((x, y, z))
    return result

def pave_line(listed, center, a, b, width, excluded = {}):
    for p in staircase(a, b):
        if p in excluded:
            continue
        center[p] = True
        for x in range(-width // 2 + 1, width // 2 + 1):
            for z in range(-width // 2 + 1, width // 2 + 1):
                q = (p[0] + x, p[1], p[2] + z)
                if q not in excluded:
                    listed[q] = GRAY

# A rotated T junction can rasterize to a tiny square loop. Remove only degree-two redundant corners.
def tidy(center):
    for p in list(center):
        if p not in center:
            continue
        arms = [(x, z) for x, z in [(1, 0), (0, 1), (-1, 0), (0, -1)]
            if (p[0] + x, p[1], p[2] + z) in center]
        if len(arms) == 2:
            ax, az = arms[0]
            bx, bz = arms[1]
            if ax * bx + az * bz == 0 and (p[0] + ax + bx, p[1], p[2] + az + bz) in center:
                center.pop(p)

def trim_entries(center, occupied):
    for iteration in range(8):
        removed = []
        for p in center:
            neighbors = [(p[0] + x, p[1], p[2] + z) for x, z in [(1, 0), (0, 1), (-1, 0), (0, -1)]]
            if len([q for q in neighbors if q in center]) <= 1 and not any([q in occupied for q in neighbors]):
                removed.append(p)
        if not removed:
            break
        for p in removed:
            center.pop(p)
