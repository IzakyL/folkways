load("folkways_vanilla:materials.star", "MASONRY")
load("folkways_airline:airfield.star", "spot")
accepts = ["zone"]
knobs = [
    choice("heading", [option("north"), option("east"), option("south"), option("west")],
        default = "north", icon = "minecraft:compass"),
    count("height", 2, 96, default = 44, icon = "minecraft:scaffolding"),
    items("structure", MASONRY, default = ["minecraft:stone_bricks"], icon = "minecraft:stone_bricks"),
]
lang = {
    "en_us": {
        "title": "Airport tower",
        "heading": "Facing",
        "heading.north": "North",
        "heading.east": "East",
        "heading.south": "South",
        "heading.west": "West",
        "height": "Height",
        "structure": "Structure material",
        "error.material": "Choose structure material"
    },
    "zh_cn": {
        "title": "塔台",
        "heading": "朝向",
        "heading.north": "北",
        "heading.east": "东",
        "heading.south": "南",
        "heading.west": "西",
        "height": "高度",
        "structure": "结构材料",
        "error.material": "请选择结构材料"
    },
    "zh_hk": {
        "title": "塔台",
        "heading": "朝向",
        "heading.north": "北",
        "heading.east": "東",
        "heading.south": "南",
        "heading.west": "西",
        "height": "高度",
        "structure": "結構材料",
        "error.material": "請選擇結構材料"
    },
    "zh_tw": {
        "title": "塔臺",
        "heading": "朝向",
        "heading.north": "北",
        "heading.east": "東",
        "heading.south": "南",
        "heading.west": "西",
        "height": "高度",
        "structure": "結構材料",
        "error.material": "請選擇結構材料"
    }
}

def draw(site):
    if not site.items("structure"):
        reject("error.material")
    zone = site.zone
    if zone.width < 3 or zone.depth < 3:
        reject("error.zone")
    height = site.count("height")
    structure = [spot(zone, x, z, y) for x in range(3) for z in range(3) for y in range(height - 1)
        if x != 0 or z != 0]
    heading = site.choice("heading")
    dx, dz = {"north": (0, -1), "east": (1, 0), "south": (0, 1), "west": (-1, 0)}[heading]
    compass = ["north", "east", "south", "west"]
    turns = {"north": 0, "east": 1, "south": 2, "west": 3}
    local_heading = compass[(turns[heading] - turns[zone.facing]) % 4]
    top = spot(zone, 1, 1, height - 1)
    return [part("structure", cells(structure), site.items("structure")),
        part("access", cells([spot(zone, 0, 0, y) for y in range(height - 1)]), ["minecraft:scaffolding"]),
        stamp("tower", piece(key = {"t": "folkways_airline:airport_tower[facing=%s]" % local_heading}, layers = [["t"]]),
            zone.sized(width = 1, height = 1, depth = 1).at(x = 1, y = height - 1, z = 1)),
        part("seat", cells([(top[0] + dx, top[1], top[2] + dz)]), ["create:white_seat"])]
