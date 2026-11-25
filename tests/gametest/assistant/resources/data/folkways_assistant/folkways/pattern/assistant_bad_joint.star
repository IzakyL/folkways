capture = 0

def draw(site):
    return [blocks("ends", {point(0, 0, 0): "minecraft:stone", point(1, 0, 0): "minecraft:stone"}),
            joint(point(0, 0, 0), point(1, 0, 0))]
