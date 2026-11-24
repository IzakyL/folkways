load("folkways_vanilla:materials.star", "MASONRY", "ROOFING", "offer")

detail = "bits"
accepts = ["path", "zone"]
knobs = [
    count("height", 5, 7, default = 5),
    count("spacing", 12, 28, default = 24),
    flag("gate", default = True),
    items("stone", offer(["minecraft:light_gray_concrete"], MASONRY), default = ["minecraft:light_gray_concrete"]),
    items("trim", offer(["minecraft:polished_andesite"], MASONRY), default = ["minecraft:polished_andesite"]),
    items("roof", ROOFING, default = ["minecraft:deepslate_tiles"]),
]
lang = {
    "en_us": {"title": "Rampart", "height": "Wall height", "spacing": "Tower spacing", "gate": "Arched gate",
        "stone": "Wall masonry", "trim": "Cornices and voussoirs", "roof": "Tower caps"},
    "zh_cn": {"title": "收分城防", "height": "墙身高度", "spacing": "塔楼间距", "gate": "拱券城门",
        "stone": "墙身石材", "trim": "檐口与券石", "roof": "塔帽"},
    "zh_hk": {"title": "收分城防", "height": "牆身高度", "spacing": "塔樓間距", "gate": "拱券城門",
        "stone": "牆身石材", "trim": "簷口與券石", "roof": "塔帽"},
    "zh_tw": {"title": "收分城防", "height": "牆身高度", "spacing": "塔樓間距", "gate": "拱券城門",
        "stone": "牆身石材", "trim": "簷口與券石", "roof": "塔帽"},
}

def slab(out, site, room, low, high, name = "trim", cut = None):
    shape = fractional_box(room, low, high)
    out.append(part(name, subtract(shape, cut) if cut != None else shape, site.items(name)))

def tower(site, room, passage):
    h = site.count("height")
    out = []
    drum = room.at(x = -1, z = -3).sized(width = 7, depth = 7, height = h)
    out.append(part("foot", frustum(drum, 290, 265, height = 65), site.items("trim")))
    out.append(part("tower-base", frustum(drum, 265, 245, base = 65, height = h * 100 - 65),
        site.items("stone")))
    upper = drum.at(y = h).sized(height = 2)
    cuts = [frustum(upper, 190, 190), passage]
    for turn in range(4):
        face = upper.rotate(turn)
        cuts.append(fractional_box(face, [3.32, 0.6, 0], [3.68, 1.7, 2.0]))
        cuts.append(fractional_box(face, [3.05, 1.25, 0], [3.95, 1.5, 2.0]))
        cuts.append(fractional_box(face, [2.7, 0.6, 1.2], [4.3, 1.7, 2.4]))
    out.append(part("tower", subtract(frustum(upper, 245, 245), group(cuts)), site.items("stone")))
    collar = upper.at(y = 2).sized(height = 1)
    for base, radius in [[0, 255], [25, 280]]:
        out.append(part("collar", frustum(collar, radius, radius, base = base, height = 25), site.items("trim")))
    roof = drum.at(y = h + 2).sized(height = 4)
    out.append(part("roof", frustum(roof, 320, 1, base = 50, height = 300), site.items("roof")))
    return out

def draw(site):
    h = site.count("height")
    runs = site.path.stretches if site.kind == "path" else [site.zone.sized(depth = 25)]
    rooms = [run.sized(width = 5, anchor = "center", parity = "low").sized(height = h) for run in runs]
    towers = []
    passages = []
    for index in range(len(rooms)):
        room = rooms[index]
        n = room.depth
        count = max(1, (n - 2) // site.count("spacing") + 1)
        for i in range(count + 1):
            at = i * (n - 1) // count
            passages.append(fractional_box(room, [1.5, h, at - 3], [3.5, h + 2, at + 3]))
            gate_bay = site.flag("gate") and index == 0 and n >= 13 and at > n // 2 - 6 and at < n // 2 + 6
            if (i < count or index == len(rooms) - 1) and not gate_bay:
                towers.append(room.at(z = at))
    passage = group(passages)
    out = []
    for index in range(len(rooms)):
        room = rooms[index]
        n = room.depth
        # Three broad setbacks read as masonry courses instead of dozens of bright voxel ledges.
        body = group([fractional_box(room, [0.25, 0, 0], [4.75, h * 0.3, n]),
            fractional_box(room, [0.75, h * 0.3, 0], [4.25, h * 0.6, n]),
            fractional_box(room, [1.25, h * 0.6, 0], [3.75, h, n])])
        if site.flag("gate") and index == 0 and n >= 13:
            door = room.at(z = n // 2 - 2).sized(height = 4, depth = 4)
            hole = vault(door, 2)
            body = subtract(body, vault(door, 2, grow = 1))
            ring = subtract(intersect(vault(door, 2, grow = 1), box(room)), hole)
            joints = []
            for dz, dy in [[0.966, 0.259], [0.707, 0.707], [0.259, 0.966],
                           [-0.259, 0.966], [-0.707, 0.707], [-0.966, 0.259]]:
                for x in [0, 5]:
                    joints.append(tube(door, [[x, 2 + dy * 1.95, 2 + dz * 1.95],
                        [x, 2 + dy * 3.05, 2 + dz * 3.05]], 0.125))
            out.append(part("voussoirs", subtract(ring, group(joints)), site.items("trim")))
        out.append(part("stone", body, site.items("stone")))
        # Corbel courses spread the crown above the broad setbacks.
        for y, edge in [[h - 0.75, 0.9], [h - 0.5, 0.65], [h - 0.25, 0.4]]:
            slab(out, site, room, [edge, y, 0], [5 - edge, y + 0.25, n])
        for x in [0.4, 3.85]:
            slab(out, site, room, [x, h, 0], [x + 0.75, h + 0.45, n], "stone", passage)
            for z in range(1, n - 1, 3):
                slab(out, site, room, [x, h + 0.4, z], [x + 0.75, h + 1.1, z + 1.5], "stone", passage)
                cap = tube(room, [[x + 0.375, h + 1.1, z + 0.375],
                    [x + 0.375, h + 1.1, z + 1.125]], 0.375)
                out.append(part("merlon-cap", subtract(cap, passage), site.items("trim")))
    for room in towers:
        out.extend(tower(site, room, passage))
    return out
