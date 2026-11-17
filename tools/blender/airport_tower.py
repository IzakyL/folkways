"""Export and preview: /usr/local/bin/blender -b --python tools/blender/airport_tower.py

Run from any directory. Optional -- --create-jar PATH --preview-dir PATH.
Create textures are extracted only into build/airport-tower; never bundled.
North (-Z) is the operator/seat side. Blender uses (Minecraft X, -Z, Y).
The scene meshes and JSON share explicit face vertices and UVs, without modifiers.
"""
import argparse
import json
from pathlib import Path
import sys
import zipfile

import bpy
import bmesh
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument('--create-jar', type=Path, default=Path(
    '/home/izakyl/folkways/.blockwright-store/libs/create/sha512-11cc8fc049d2f67f/'
    'create-1.21.1-6.0.10.jar'))
parser.add_argument('--preview-dir', type=Path, default=Path(
    '/home/izakyl/tower-model-preview'))
args = parser.parse_args(sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else [])
ASSETS = ROOT / 'mods/airline/src/main/resources/assets/folkways_airline'
WORK = ROOT / 'build/airport-tower'
WORK.mkdir(parents=True, exist_ok=True)
args.preview_dir.mkdir(parents=True, exist_ok=True)
bpy.ops.object.select_all(action='SELECT')
bpy.ops.object.delete(use_global=False)
textures = {'andesite': 'create:block/andesite_casing', 'brass': 'create:block/brass_casing',
            'panel': 'folkways_airline:block/tower_panel', 'radar': 'folkways_airline:block/tower_radar',
            'andesite_metal': 'folkways_airline:block/tower_andesite_metal',
            'brass_metal': 'folkways_airline:block/tower_brass_metal'}


def image_texture(name, pixel):
    path = ASSETS / 'textures/block' / (name + '.png')
    path.parent.mkdir(parents=True, exist_ok=True)
    img = bpy.data.images.new(name, width=16, height=16, alpha=True)
    data = []
    for y in reversed(range(16)):
        for x in range(16):
            data.extend([v / 255 for v in pixel(x, y)] + [1])
    img.pixels = data
    img.filepath_raw = str(path)
    img.file_format = 'PNG'
    img.save()
    return path


def panel_pixel(x, y):
    # Unit-density atlas: keyboard (12x4), service panel (14x6), dark paint.
    if y < 4 and x < 12:
        if y in (1, 2) and x in (1, 3, 5, 7, 9):
            return (180, 178, 150)
        if x == 11 and y == 1:
            return (181, 139, 72)
        return (47, 56, 54)
    if 6 <= y < 12 and x < 14:
        if y in (7, 9) and 3 <= x <= 10:
            return (47, 56, 54)
        if y == 11:
            return (111, 115, 99)
        return (132, 137, 119)
    return (47, 56, 54)


def radar_pixel(x, y):
    # A single 14x7 face: radar at left, flight strips and status at right.
    if x >= 14 or y >= 7:
        return (47, 56, 54)
    if x in (0, 13) or y in (0, 6):
        return (181, 139, 72)
    if x >= 9:
        if y in (1, 3) and x < 13:
            return (168, 190, 151)
        return (27, 48, 42)
    if (x, y) in ((3, 2), (6, 3)):
        return (190, 215, 155)
    if x == 4 or y == 3 or (x in (2, 6) and y in (2, 4)):
        return (68, 112, 82)
    return (30, 60, 47)


def metal_pixel(x, y, base):
    # Borderless, unit-density metal grain in the console's Create-like palette.
    shade = ((x * 7 + y * 11 + x * y * 3) % 7 - 3) * 2
    return tuple(channel + shade for channel in base)


paths = {'panel': image_texture('tower_panel', panel_pixel),
         'radar': image_texture('tower_radar', radar_pixel),
         'andesite_metal': image_texture('tower_andesite_metal',
                                        lambda x, y: metal_pixel(x, y, (132, 137, 119))),
         'brass_metal': image_texture('tower_brass_metal',
                                     lambda x, y: metal_pixel(x, y, (181, 139, 72)))}
with zipfile.ZipFile(args.create_jar) as jar:
    for key in ('andesite', 'brass'):
        member = 'assets/create/textures/block/' + textures[key].split('/')[-1] + '.png'
        paths[key] = WORK / Path(member).name
        paths[key].write_bytes(jar.read(member))
materials = {}
for key, path in paths.items():
    mat = bpy.data.materials.new(key)
    mat.use_nodes = True
    nodes = mat.node_tree.nodes
    shader = nodes.get('Principled BSDF')
    shader.inputs['Roughness'].default_value = .78
    tex = nodes.new('ShaderNodeTexImage')
    tex.image = bpy.data.images.load(str(path))
    tex.interpolation = 'Closest'
    mat.node_tree.links.new(tex.outputs['Color'], shader.inputs['Base Color'])
    materials[key] = mat

# Only complete 16x16 faces use casing; small parts crop borderless metal or dedicated artwork.
SWATCHES = {'dark': [0, 14]}
elements = []


def box(name, lo, hi, material='andesite', special=None):
    x, y, z = lo
    X, Y, Z = hi
    vertices = [(x, y, z), (X, y, z), (X, Y, z), (x, Y, z),
                (x, y, Z), (X, y, Z), (X, Y, Z), (x, Y, Z)]
    # Ordered top-left, bottom-left, bottom-right, top-right as viewed from outside.
    faces = {'north': (2, 1, 0, 3), 'south': (7, 4, 5, 6),
             'east': (6, 5, 1, 2), 'west': (3, 0, 4, 7),
             'up': (7, 6, 2, 3), 'down': (0, 1, 5, 4)}
    result = {'from': lo, 'to': hi, 'faces': {}}
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata([(a - 8, 8 - c, b) for a, b, c in vertices], [], list(faces.values()))
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.collection.objects.link(obj)
    uv_layer = mesh.uv_layers.new()
    for poly, (side, indices) in zip(mesh.polygons, faces.items()):
        width, height = {'north': (X-x, Y-y), 'south': (X-x, Y-y),
                         'east': (Z-z, Y-y), 'west': (Z-z, Y-y),
                         'up': (X-x, Z-z), 'down': (X-x, Z-z)}[side]
        key = material
        if material in SWATCHES:
            key = 'panel'
            u, v = SWATCHES[material]
            uv = [u, v, u + min(width, 2), v + min(height, 2)]
        else:
            uv = [0, 0, width, height]
        if material in ('andesite', 'brass') and (width, height) != (16, 16):
            key = material + '_metal'
        if special and side in special:
            key, uv = special[side]
        result['faces'][side] = {'uv': uv, 'texture': '#' + key}
        obj.data.materials.append(materials[key])
        poly.material_index = len(obj.data.materials) - 1
        u, v, U, V = uv
        coords = [(U, v), (U, V), (u, V), (u, v)]
        if side in ('up', 'down'):
            coords = [(u, V), (U, V), (U, v), (u, v)]
        for loop, coord in zip(poly.loop_indices, coords):
            uv_layer.data[loop].uv = (coord[0] / 16, 1 - coord[1] / 16)
    elements.append(result)


# Nine broad forms; switches, lamps, vents and fasteners belong to the textures.
box('Plinth', [0, 0, 0], [16, 1, 16])
box('Equipment cabinet', [1, 1, 2], [15, 7, 15], special={
    'north': ('panel', [0, 6, 14, 12]), 'south': ('panel', [0, 6, 14, 12])})
box('Desktop', [0, 7, 0], [16, 8, 16], 'brass')
box('Monitor pedestal', [4, 8, 9], [12, 9, 13], 'andesite')
box('Monitor enclosure', [1, 9, 7], [15, 16, 14], 'andesite', {
    'north': ('radar', [0, 0, 14, 7]), 'south': ('panel', [0, 6, 14, 13])})
box('Keyboard', [1, 8, 1], [13, 8.5, 5], 'dark', {'up': ('panel', [0, 0, 12, 4])})
box('Microphone foot', [13, 8, 4], [15, 9, 6], 'brass')
box('Microphone stem', [13.5, 9, 4.5], [14.5, 11, 5.5], 'dark')
box('Microphone head', [13, 11, 4], [15, 12, 6], 'dark')
model = {'parent': 'minecraft:block/block', 'textures': {'particle': textures['andesite'], **textures},
         'elements': elements}
# Validate the exact export, including face references and pixel bounds.
with zipfile.ZipFile(args.create_jar) as jar:
    for value in model['textures'].values():
        namespace, resource = value.split(':')
        relative = 'textures/' + resource + '.png'
        if namespace == 'create':
            assert 'assets/create/' + relative in jar.namelist(), value
        else:
            assert (ASSETS / relative).is_file(), value
for element in elements:
    assert all(-16 <= v <= 32 for v in element['from'] + element['to'])
    assert all(a < b for a, b in zip(element['from'], element['to']))
    if 'rotation' in element:
        assert element['rotation']['angle'] in (-45, -22.5, 0, 22.5, 45)
    for face in element['faces'].values():
        assert face['texture'][1:] in textures
        assert all(0 <= v <= 16 for v in face['uv'])
        if face['texture'] in ('#andesite', '#brass'):
            assert face['uv'] == [0, 0, 16, 16], face
output = ASSETS / 'models/block/airport_tower.json'
output.write_text(json.dumps(model, indent=2) + '\n')
assert json.loads(output.read_text()) == model
print('Validated and exported', len(elements), 'cuboids')
scene = bpy.context.scene
scene.render.engine = 'CYCLES'
scene.cycles.samples = 32
scene.render.resolution_x = 900
scene.render.resolution_y = 900
scene.render.resolution_percentage = 100
scene.world.color = (.22, .22, .22)
scene.view_settings.view_transform = 'Standard'
scene.render.image_settings.file_format = 'PNG'


def area(name, location, power, size):
    data = bpy.data.lights.new(name, 'AREA')
    data.energy = power
    data.shape = 'DISK'
    data.size = size
    obj = bpy.data.objects.new(name, data)
    scene.collection.objects.link(obj)
    obj.location = location
    obj.rotation_euler = (Vector((0, 0, 8)) - obj.location).to_track_quat('-Z', 'Y').to_euler()


area('Large softbox', (-16, 22, 32), 16000, 20)
area('Rear fill', (16, -16, 22), 11000, 18)
camera_data = bpy.data.cameras.new('Preview')
camera = bpy.data.objects.new('Preview', camera_data)
scene.collection.objects.link(camera)
scene.camera = camera
camera_data.type = 'ORTHO'
camera_data.ortho_scale = 27
scene.world.color = (.15, .18, .19)
for name, location in [('front', (0, 34, 21)), ('rear', (26, -32, 24)), ('top', (-25, 30, 40))]:
    camera.location = location
    camera.rotation_euler = (Vector((0, 0, 8)) - camera.location).to_track_quat('-Z', 'Y').to_euler()
    scene.render.filepath = str(args.preview_dir / ('tower-preview-' + name + '.png'))
    bpy.ops.render.render(write_still=True)

# Render jar models with the same camera scale, lighting and face/UV convention.
for obj in list(scene.objects):
    if obj.type == 'MESH':
        bpy.data.objects.remove(obj, do_unlink=True)
with zipfile.ZipFile(args.create_jar) as jar:
    for reference, member in (
            ('content-observer', 'content_observer/block'),
            ('redstone-link', 'redstone_link/transmitter')):
        data = json.loads(jar.read('assets/create/models/block/' + member + '.json'))
        for key, resource in data['textures'].items():
            while resource.startswith('#'):
                resource = data['textures'][resource[1:]]
            path = WORK / (resource.replace(':', '_').replace('/', '_') + '.png')
            path.write_bytes(jar.read('assets/' + resource.replace(':', '/textures/') + '.png'))
            mat = bpy.data.materials.new(reference + key)
            mat.use_nodes = True
            shader = mat.node_tree.nodes.get('Principled BSDF')
            shader.inputs['Roughness'].default_value = .78
            tex = mat.node_tree.nodes.new('ShaderNodeTexImage')
            tex.image = bpy.data.images.load(str(path))
            tex.interpolation = 'Closest'
            mat.node_tree.links.new(tex.outputs['Color'], shader.inputs['Base Color'])
            mat.node_tree.links.new(tex.outputs['Alpha'], shader.inputs['Alpha'])
            materials[key] = mat
        for element in data['elements']:
            special = {side: (face['texture'][1:], face['uv'])
                       for side, face in element['faces'].items()}
            box(reference, element['from'], element['to'], next(iter(data['textures'])), special)
            mesh = bpy.data.meshes[-1]
            sides = ['north', 'south', 'east', 'west', 'up', 'down']
            for poly, side in zip(mesh.polygons, sides):
                if side not in element['faces']:
                    # Jar elements deliberately omit internal faces.
                    poly.material_index = 0
                    continue
                steps = element['faces'][side].get('rotation', 0) // 90
                layer = mesh.uv_layers.active.data
                coords = [layer[i].uv.copy() for i in poly.loop_indices]
                for i, index in enumerate(poly.loop_indices):
                    layer[index].uv = coords[(i + steps) % 4]
            bm = bmesh.new()
            bm.from_mesh(mesh)
            bm.faces.ensure_lookup_table()
            bmesh.ops.delete(bm, geom=[bm.faces[i] for i, side in enumerate(sides)
                                      if side not in element['faces']], context='FACES')
            bm.to_mesh(mesh)
            bm.free()
        camera.location = (-25, 30, 40)
        camera.rotation_euler = (Vector((0, 0, 8)) - camera.location).to_track_quat('-Z', 'Y').to_euler()
        scene.render.filepath = str(args.preview_dir / ('create-ref-' + reference + '.png'))
        bpy.ops.render.render(write_still=True)
        for obj in list(scene.objects):
            if obj.type == 'MESH':
                bpy.data.objects.remove(obj, do_unlink=True)

# Assemble the three tower views without an external image dependency.
views = [bpy.data.images.load(str(args.preview_dir / ('tower-preview-' + name + '.png')))
         for name in ('front', 'rear', 'top')]
width, height = views[0].size
combined = bpy.data.images.new('Tower views', width=width * 3, height=height, alpha=True)
source = [list(view.pixels) for view in views]
pixels = []
for row in range(height):
    for view in source:
        pixels.extend(view[row * width * 4:(row + 1) * width * 4])
combined.pixels = pixels
combined.filepath_raw = str(args.preview_dir / 'tower-all.png')
combined.file_format = 'PNG'
combined.save()
