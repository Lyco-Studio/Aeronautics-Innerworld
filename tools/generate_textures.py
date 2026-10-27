#!/usr/bin/env python3
"""Rebuild the original pixel textures using only the Python standard library."""
from pathlib import Path
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/aeronautics_innerworld/textures'
STEEL = (109, 115, 119, 255)
DARK = (57, 59, 63, 255)
EDGE = (146, 150, 149, 255)
COPPER = (164, 119, 93, 255)
BRASS = (181, 153, 106, 255)
AMBER = (255, 186, 57, 255)


class Canvas:
    def __init__(self, size, color=(0, 0, 0, 0), height=None):
        self.size = size
        self.height = height or size
        self.pixels = [[color for _ in range(size)] for _ in range(self.height)]

    def rect(self, x0, y0, x1, y1, color):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.pixels[y][x] = color

    def dot(self, x, y, color):
        self.pixels[y][x] = color

    def grain(self):
        for y, row in enumerate(self.pixels):
            for x, rgba in enumerate(row):
                value = ((x // 2) * 374761393 + (y // 2) * 668265263) & 0xffffffff
                value = ((value ^ (value >> 13)) * 1274126177) & 0xffffffff
                delta = (value ^ (value >> 16)) % 5 - 2
                if rgba[3]:
                    row[x] = tuple(max(0, min(255, value + delta)) for value in rgba[:3]) + (rgba[3],)

    def save(self, name):
        def chunk(kind, data):
            return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
        rows = b''.join(b'\0' + bytes(c for pixel in row for c in pixel) for row in self.pixels)
        data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', self.size, self.height, 8, 6, 0, 0, 0))
        data += chunk(b'IDAT', zlib.compress(rows, 9)) + chunk(b'IEND', b'')
        path = ROOT / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)


def plate():
    c = Canvas(16, (109, 117, 116, 255))
    c.rect(0, 0, 15, 0, (145, 151, 146, 255))
    c.rect(0, 1, 0, 14, (131, 138, 134, 255))
    c.rect(1, 1, 14, 1, (124, 133, 128, 255))
    c.rect(0, 15, 15, 15, (94, 102, 100, 255))
    c.rect(15, 1, 15, 15, (102, 109, 106, 255))
    c.rect(2, 2, 13, 13, (121, 92, 65, 255))
    for x in (2, 6, 10):
        c.rect(x, 2, x + 1, 12, (135, 102, 74, 255))
        c.rect(x + 2, 3, x + 3, 13, (110, 82, 59, 255))
        c.rect(x + 1, 4, x + 1, 9, (146, 113, 84, 255))
    c.rect(2, 2, 13, 2, (151, 119, 89, 255))
    c.rect(2, 13, 13, 13, (100, 77, 56, 255))
    for x, y in ((0, 4), (14, 9), (4, 0), (10, 14)):
        c.rect(x, y, x + 1, y + 1, (117, 127, 122, 255))
    return c


def generate():
    for name, color in (('steel_trim', (113, 120, 124)), ('copper_trim', (163, 117, 91)),
                        ('brass_trim', (177, 151, 111)), ('dark_metal', (56, 59, 65))):
        c = Canvas(16)
        for y in range(16):
            for x in range(16):
                shift = (9 if y < 2 else 4 if y < 5 else -10 if y > 12 else 0)
                shift += 5 if x < 2 else -6 if x > 12 else 0
                c.dot(x, y, tuple(channel + shift for channel in color) + (255,))
        for x, y in ((3, 3), (8, 6), (5, 10), (10, 11)):
            c.rect(x, y, x + 2, y + 1, tuple(channel + 4 for channel in color) + (255,))
        c.grain()
        c.save('block/' + name + '.png')

    c = Canvas(32)
    c.rect(5, 3, 26, 28, DARK)
    c.rect(6, 4, 25, 27, COPPER)
    c.rect(9, 5, 24, 25, STEEL)
    c.rect(7, 5, 8, 26, BRASS)
    c.rect(10, 26, 24, 27, (220, 202, 157, 255))
    c.rect(16, 9, 17, 19, BRASS)
    c.rect(12, 18, 21, 19, BRASS)
    for x, y in ((15, 8), (11, 18), (19, 18)):
        c.rect(x, y, x + 3, y + 3, DARK)
        c.rect(x + 1, y + 1, x + 2, y + 2, AMBER)
    c.dot(23, 6, EDGE)
    c.dot(23, 24, EDGE)
    c.grain()
    c.save('item/expedition_journal.png')

    def connector(canvas, x, y, next_y, side=1):
        edge, route = x + 8 * side, x + 25 * side
        canvas.rect(min(edge, route), y + 8, max(edge, route), y + 8, COPPER)
        canvas.rect(route, y + 8, route, next_y + 8, COPPER)
        canvas.rect(min(edge, route), next_y + 8, max(edge, route), next_y + 8, COPPER)

    c = Canvas(128, height=256)
    connector(c, 58, 12, 40)
    connector(c, 58, 40, 68)
    c.rect(25, 76, 50, 76, COPPER)
    c.rect(66, 76, 91, 76, COPPER)
    for x in (25, 91):
        c.rect(x, 76, x, 96, COPPER)
    connector(c, 25, 96, 124)
    connector(c, 91, 96, 124, -1)
    c.save('gui/tree_surface.png')
    c = Canvas(128, height=256)
    for y in (12, 40, 68, 96):
        connector(c, 58, y, y + 28)
    c.save('gui/tree_deep.png')

    c = plate()
    c.rect(2, 3, 13, 12, BRASS)
    c.rect(3, 4, 12, 11, DARK)
    for x in (5, 8, 11):
        c.rect(x, 5, x, 8, (86, 207, 193, 255))
        c.dot(x, 10, AMBER)
    c.grain()
    c.save('block/core_console.png')

    c = plate()
    for i in range(2, 14):
        c.dot(i, i, COPPER)
        c.dot(15 - i, i, BRASS)
    c.rect(6, 6, 9, 9, AMBER)
    c.grain()
    c.save('block/core_seal.png')

    c = Canvas(32)
    c.rect(6, 4, 25, 27, DARK)
    c.rect(7, 5, 24, 26, BRASS)
    c.rect(9, 7, 22, 24, STEEL)
    c.rect(11, 9, 20, 20, DARK)
    c.rect(15, 10, 16, 19, (86, 207, 193, 255))
    c.rect(12, 14, 19, 15, (86, 207, 193, 255))
    c.rect(14, 8, 17, 11, AMBER)
    c.rect(12, 22, 19, 23, COPPER)
    c.grain()
    c.save('item/core_surveyor.png')

    c = Canvas(32)
    c.rect(8, 3, 23, 28, DARK)
    c.rect(6, 7, 25, 24, STEEL)
    for y in (7, 13, 19, 25):
        c.rect(7, y, 24, y + 1, COPPER)
    c.rect(12, 5, 19, 26, BRASS)
    c.rect(14, 8, 17, 23, (54, 106, 104, 255))
    c.grain()
    c.save('item/core_heart.png')

    c = Canvas(128, DARK)
    c.rect(0, 0, 63, 31, STEEL)
    c.rect(0, 18, 63, 21, COPPER)
    for x in range(4, 64, 8):
        c.rect(x, 22, x + 2, 29, DARK)
        c.dot(x, 23, BRASS)
    c.rect(0, 40, 43, 59, STEEL)
    c.rect(10, 50, 21, 53, DARK)
    c.rect(11, 51, 20, 52, AMBER)
    c.rect(64, 0, 99, 37, STEEL)
    c.rect(64, 14, 99, 18, COPPER)
    c.rect(64, 29, 99, 37, BRASS)
    c.rect(64, 40, 95, 65, STEEL)
    c.rect(64, 57, 95, 61, COPPER)
    c.rect(0, 70, 19, 79, BRASS)
    c.rect(2, 72, 17, 77, (86, 207, 193, 255))
    c.rect(5, 73, 14, 76, (192, 242, 208, 255))
    c.grain()
    c.save('entity/core_warden.png')

    c = plate()
    c.rect(3, 2, 12, 13, DARK)
    for y in (3, 7, 11):
        c.rect(1, y, 14, y + 1, COPPER)
        c.rect(3, y, 12, y, BRASS)
    c.rect(6, 2, 9, 13, DARK)
    c.rect(7, 3, 8, 12, (86, 207, 193, 255))
    c.grain()
    c.save('block/deep_bore_coupler.png')

    c = Canvas(32)
    c.rect(6, 3, 25, 28, DARK)
    c.rect(7, 4, 24, 27, COPPER)
    c.rect(9, 6, 22, 25, STEEL)
    for y in (9, 15, 21):
        c.rect(4, y, 27, y + 1, BRASS)
    c.rect(12, 7, 19, 24, DARK)
    c.rect(14, 9, 17, 22, (86, 207, 193, 255))
    c.dot(14, 9, (192, 242, 208, 255))
    c.grain()
    c.save('item/pressure_matrix.png')

    c = plate()
    c.rect(3, 5, 4, 10, (85, 88, 81, 255))
    c.rect(3, 5, 3, 9, (151, 154, 135, 255))
    c.rect(10, 5, 12, 8, (128, 134, 130, 255))
    c.rect(10, 5, 12, 5, (155, 160, 151, 255))
    c.rect(10, 8, 12, 8, (93, 103, 100, 255))
    c.dot(11, 6, (80, 89, 87, 255))
    c.grain()
    c.save('block/industrial_casing.png')

    c = plate()
    for y in range(16):
        for x in range(16):
            r = (x - 7.5) ** 2 + (y - 7.5) ** 2
            if r < 37:
                c.dot(x, y, BRASS if r >= 24 else COPPER if r >= 13 else DARK)
    c.rect(6, 6, 9, 9, EDGE)
    c.rect(7, 7, 8, 8, STEEL)
    c.grain()
    c.save('block/cutter_hub.png')

    c = Canvas(16, (156, 171, 170, 255))
    for y in range(16):
        for x in range(16):
            if (x + y) % 6 < 2:
                c.dot(x, y, (94, 109, 112, 255))
    c.rect(0, 0, 15, 0, (212, 222, 215, 255))
    c.rect(0, 15, 15, 15, DARK)
    c.grain()
    c.save('block/cutter_blade.png')

    c = plate()
    c.rect(3, 3, 12, 12, COPPER)
    c.rect(4, 4, 11, 11, DARK)
    c.rect(7, 5, 8, 9, BRASS)
    c.rect(6, 8, 9, 9, AMBER)
    c.rect(7, 10, 8, 10, AMBER)
    c.dot(5, 5, (105, 168, 132, 255))
    c.dot(10, 5, (239, 125, 58, 255))
    c.grain()
    c.save('block/controller_front.png')

    c = plate()
    c.rect(4, 1, 5, 14, COPPER)
    for y in (5, 7, 9, 11):
        c.rect(7, y, 12, y, DARK)
        c.dot(13, y, EDGE)
    c.rect(3, 6, 5, 9, BRASS)
    c.grain()
    c.save('block/controller_side.png')

    c = plate()
    c.rect(3, 3, 12, 12, COPPER)
    c.rect(4, 4, 11, 11, BRASS)
    c.rect(5, 5, 10, 10, DARK)
    c.rect(6, 6, 9, 9, STEEL)
    c.rect(7, 7, 8, 8, EDGE)
    c.grain()
    c.save('block/controller_back.png')

    c = plate()
    c.rect(3, 2, 12, 13, COPPER)
    c.rect(4, 3, 11, 12, DARK)
    c.rect(5, 4, 6, 11, STEEL)
    c.rect(9, 4, 10, 11, STEEL)
    c.rect(6, 7, 6, 8, BRASS)
    c.rect(9, 7, 9, 8, BRASS)
    c.rect(6, 2, 9, 2, AMBER)
    c.grain()
    c.save('block/bore_station_front.png')

    c = Canvas(16, (105, 113, 116, 255))
    c.rect(0, 0, 15, 0, (144, 151, 151, 255))
    c.rect(0, 1, 0, 14, (132, 140, 141, 255))
    c.rect(0, 15, 15, 15, (90, 99, 104, 255))
    c.rect(15, 1, 15, 15, (98, 107, 111, 255))
    c.rect(2, 2, 13, 13, (128, 135, 138, 255))
    c.rect(2, 2, 13, 3, (142, 149, 151, 255))
    c.rect(2, 4, 3, 12, (136, 144, 146, 255))
    c.rect(3, 12, 13, 13, (114, 124, 129, 255))
    c.rect(12, 4, 13, 11, (121, 130, 134, 255))
    for x, y in ((4, 5), (9, 8), (6, 10)):
        c.rect(x, y, x + 2, y + 1, (132, 139, 142, 255))
    for x, y in ((1, 1), (13, 13)):
        c.rect(x, y, x + 1, y + 1, (119, 99, 72, 255))
        c.dot(x, y, (177, 148, 106, 255))
    c.grain()
    c.save('block/refractory_hull.png')
    hull = [row[:] for row in c.pixels]

    c = Canvas(16)
    c.rect(0, 0, 15, 15, (118, 129, 129, 255))
    c.rect(1, 1, 14, 14, COPPER)
    c.rect(2, 2, 13, 13, (76, 84, 79, 255))
    c.rect(3, 3, 12, 12, (0, 0, 0, 0))
    c.rect(3, 3, 12, 3, (158, 179, 182, 255))
    c.rect(3, 4, 3, 12, (120, 150, 156, 255))
    for x,y in ((1,1),(14,1),(1,14),(14,14)):
        c.dot(x,y,EDGE)
    for x,y in ((4,4),(5,4),(4,5),(10,11),(11,11)):
        c.dot(x,y,(131,163,153,255))
    c.save('block/mantle_viewport.png')

    c = Canvas(16)
    c.pixels = [row[:] for row in hull]
    c.rect(1, 1, 14, 14, (90, 99, 104, 255))
    c.rect(2, 2, 13, 13, (138, 145, 148, 255))
    c.rect(3, 3, 12, 12, (121, 131, 136, 255))
    c.rect(3, 3, 12, 3, (153, 160, 161, 255))
    c.rect(3, 12, 12, 12, (99, 111, 118, 255))
    c.rect(6, 2, 9, 2, BRASS)
    c.rect(6, 13, 9, 13, (123, 87, 43, 255))
    c.grain()
    c.save('block/pressure_hatch.png')

    c = plate()
    for y in range(16):
        for x in range(16):
            radius = (x - 7.5) ** 2 + (y - 7.5) ** 2
            if radius < 44:
                c.dot(x,y,BRASS if radius > 32 else DARK)
    for x,y in ((7,4),(9,6),(9,9),(6,9),(4,6)):
        c.rect(x,y,x+2,y+1,STEEL)
    c.rect(7,7,8,8,COPPER)
    c.grain()
    c.save('block/molten_thruster.png')

    c = plate()
    for x in (2, 9):
        c.rect(x, 2, x + 4, 13, COPPER)
        c.rect(x + 1, 3, x + 3, 12, (83, 89, 91, 255))
        c.rect(x + 1, 4, x + 1, 11, (130, 150, 158, 255))
        c.rect(x + 2, 10, x + 3, 12, (92, 108, 110, 255))
        for y in (5, 8, 11):
            c.dot(x + 3, y, (175, 158, 123, 255))
    c.grain()
    c.save('block/mantle_ballast.png')

    c = plate()
    c.rect(2, 2, 13, 11, COPPER)
    c.rect(3, 3, 12, 10, DARK)
    for y in (4,6,8):
        c.rect(4,y,11,y,EDGE)
    c.rect(4,12,6,13,AMBER)
    c.rect(9,12,11,13,(105,168,132,255))
    c.grain()
    c.save('block/cabin_regulator.png')

    c = plate()
    c.rect(2, 2, 13, 13, BRASS)
    c.rect(3, 3, 12, 12, (194, 193, 179, 255))
    c.rect(4, 4, 11, 5, (205, 204, 192, 255))
    for x, y in ((5, 4), (9, 4), (11, 6), (11, 9), (8, 11), (4, 9), (4, 6)):
        c.dot(x, y, (126, 118, 98, 255))
    c.rect(7, 7, 8, 9, (116, 108, 90, 255))
    for x, y in ((8, 7), (9, 6), (10, 5)):
        c.dot(x, y, (154, 106, 74, 255))
    c.dot(7, 8, BRASS)
    c.grain()
    c.save('block/echo_sounder.png')

    c = plate()
    c.rect(2, 2, 13, 13, COPPER)
    c.rect(3, 3, 12, 12, DARK)
    for x in (4, 7, 10):
        c.rect(x, 4, x + 1, 11, EDGE)
        c.rect(x, 6, x + 1, 6, STEEL)
        c.rect(x, 9, x + 1, 9, STEEL)
    c.grain()
    c.save('block/mantle_sampler.png')

    c = Canvas(16, (57, 61, 65, 255))
    for y in range(16):
        for x in range(16):
            if (y + x // 4) % 4 == 0:
                c.dot(x, y, (39, 42, 48, 255))
    for x, y in ((3, 4), (10, 3), (7, 10), (13, 13)):
        c.rect(x - 1, y - 1, x + 1, y + 1, (23, 89, 99, 255))
        c.rect(x, y - 1, x + 1, y, (86, 207, 193, 255))
        c.dot(x, y - 1, (179, 243, 214, 255))
    c.grain()
    c.save('block/harmonic_ore.png')

    c = Canvas(16)
    c.pixels = [row[:] for row in hull]
    for x, y in ((6, 0), (6, 1), (7, 2), (7, 3), (8, 4), (8, 5), (7, 6), (7, 7),
                 (8, 8), (9, 9), (9, 10), (8, 11), (8, 12), (7, 13), (7, 14), (6, 15)):
        c.dot(x, y, (23, 29, 29, 255))
        c.dot(x + 1, y, (45, 47, 42, 255))
        if y in (4, 5, 9, 10):
            c.dot(x, y, (194, 91, 42, 255))
    for x, y in ((10, 6), (11, 5), (12, 5), (5, 10), (4, 11)):
        c.dot(x, y, (39, 46, 44, 255))
    c.save('block/damaged_hull.png')

    c = plate()
    c.rect(2, 2, 13, 13, (114, 124, 119, 255))
    c.rect(3, 3, 12, 12, STEEL)
    for i in range(4, 12):
        c.dot(i, i, COPPER)
        c.dot(15 - i, i, COPPER)
    c.rect(6, 6, 9, 9, BRASS)
    c.grain()
    c.save('block/emergency_patch.png')

    c = Canvas(32)
    c.rect(7, 11, 24, 24, (52, 59, 65, 255))
    c.rect(10, 7, 21, 27, (52, 59, 65, 255))
    c.rect(11, 9, 19, 22, (23, 89, 99, 255))
    c.rect(15, 7, 20, 19, (86, 207, 193, 255))
    c.rect(16, 8, 18, 17, (179, 243, 214, 255))
    c.rect(8, 19, 13, 25, (118, 97, 76, 255))
    c.rect(19, 21, 23, 25, (118, 97, 76, 255))
    c.grain()
    c.save('item/raw_harmonic.png')

    c = Canvas(32)
    for y in range(3, 29):
        half = int((y - 3) / 2) if y < 15 else int((29 - y) / 2)
        for x in range(16 - half, 17 + half):
            c.dot(x, y, (34, 116, 133, 255) if x > 16 else (86, 207, 193, 255))
    c.rect(14, 11, 16, 20, (179, 243, 214, 255))
    c.save('item/harmonic_crystal.png')

    for stabilized in (False, True):
        c = Canvas(32)
        c.rect(10, 3, 21, 28, DARK)
        c.rect(11, 5, 20, 26, EDGE)
        c.rect(12, 7, 19, 24, (23, 89, 99, 255))
        c.rect(13, 8, 16, 23, (86, 207, 193, 255))
        for y in (4, 14, 26):
            c.rect(9, y, 22, y + 1, BRASS if stabilized else COPPER)
        c.rect(14, 1, 17, 4, BRASS)
        if stabilized:
            c.rect(18, 9, 19, 12, AMBER)
            c.rect(18, 19, 19, 22, AMBER)
        c.grain()
        c.save('item/' + ('stabilized_coolant_cell' if stabilized else 'coolant_cell') + '.png')

    c = Canvas(32)
    c.rect(11, 4, 20, 9, DARK)
    c.rect(13, 5, 18, 8, EDGE)
    c.rect(5, 9, 26, 27, DARK)
    c.rect(6, 10, 25, 25, COPPER)
    c.rect(7, 12, 24, 23, STEEL)
    c.rect(6, 15, 25, 17, DARK)
    c.rect(12, 14, 14, 18, BRASS)
    c.rect(18, 14, 20, 18, BRASS)
    c.rect(13, 20, 18, 21, EDGE)
    c.grain()
    c.save('item/repair_kit.png')

    c = plate()
    c.rect(3, 3, 12, 12, COPPER)
    c.rect(4, 4, 11, 11, DARK)
    c.rect(6, 5, 9, 10, (23, 89, 99, 255))
    c.rect(7, 6, 8, 9, (86, 207, 193, 255))
    for x, y in ((4, 4), (10, 4), (4, 10), (10, 10)):
        c.rect(x, y, x + 1, y + 1, BRASS)
    c.rect(6, 2, 9, 2, AMBER)
    c.rect(6, 13, 9, 13, AMBER)
    c.grain()
    c.save('block/relay_beacon.png')

    c = Canvas(32)
    c.rect(4, 13, 24, 24, DARK)
    c.rect(7, 9, 26, 19, STEEL)
    c.rect(5, 14, 23, 22, (56, 68, 72, 255))
    c.rect(8, 10, 24, 16, (145, 165, 164, 255))
    c.rect(9, 10, 24, 11, (190, 206, 196, 255))
    c.rect(24, 12, 26, 20, COPPER)
    c.rect(7, 17, 21, 18, (23, 89, 99, 255))
    c.rect(11, 16, 12, 20, (86, 207, 193, 255))
    c.rect(17, 16, 18, 20, (86, 207, 193, 255))
    c.rect(14, 13, 16, 15, BRASS)
    c.grain()
    c.save('item/relic_alloy.png')

    c = plate()
    c.rect(2, 2, 13, 13, COPPER)
    c.rect(3, 3, 12, 12, BRASS)
    c.rect(4, 4, 11, 11, DARK)
    c.rect(6, 5, 9, 10, STEEL)
    c.rect(7, 6, 8, 9, (12, 22, 24, 255))
    for x in (2, 12):
        c.rect(x, 6, x + 1, 9, EDGE)
    c.grain()
    c.save('block/pressure_cannon.png')

    c = Canvas(32)
    c.rect(14, 9, 17, 27, STEEL)
    c.rect(14, 10, 14, 26, EDGE)
    c.rect(11, 5, 20, 12, COPPER)
    c.rect(13, 2, 18, 10, EDGE)
    c.rect(15, 1, 16, 6, (211, 223, 215, 255))
    c.rect(12, 11, 19, 13, BRASS)
    c.rect(10, 24, 13, 28, COPPER)
    c.rect(18, 24, 21, 28, COPPER)
    c.rect(15, 19, 16, 23, (86, 207, 193, 255))
    c.grain()
    c.save('item/pressure_bolt.png')

    c = Canvas(32)
    for y in range(4, 28):
        half = min(11, y - 2, 29 - y)
        c.rect(16 - half, y, 16 + half, y, COPPER if y % 6 < 2 else (72, 54, 47, 255))
    c.rect(12, 11, 14, 22, BRASS)
    c.rect(15, 8, 17, 24, (92, 69, 54, 255))
    c.rect(19, 13, 20, 20, (228, 119, 39, 255))
    c.grain()
    c.save('item/molten_scale.png')

    c = Canvas(64, (56, 47, 43, 255))
    for y in range(64):
        for x in range(64):
            if (x + (y // 5) * 3) % 10 < 2:
                c.dot(x, y, (111, 71, 45, 255))
            elif y % 7 < 2:
                c.dot(x, y, (39, 36, 34, 255))
    c.rect(0, 30, 31, 31, COPPER)
    c.rect(48, 32, 55, 36, AMBER)
    c.rect(50, 33, 51, 35, (255, 234, 159, 255))
    c.rect(40, 50, 59, 51, BRASS)
    c.grain()
    c.save('entity/lava_stalker.png')

    c = Canvas(64, (55, 66, 62, 255))
    for y in range(64):
        for x in range(64):
            if (x + (y // 6) * 4) % 12 < 3:
                c.dot(x, y, (113, 133, 115, 255))
            elif y % 8 < 2:
                c.dot(x, y, (29, 48, 45, 255))
    c.rect(0, 30, 31, 31, COPPER)
    c.rect(48, 32, 55, 36, (86, 207, 193, 255))
    c.rect(50, 33, 51, 35, (192, 242, 208, 255))
    c.rect(40, 50, 59, 51, COPPER)
    c.grain()
    c.save('entity/mantle_grazer.png')

    c = Canvas(32)
    c.rect(9, 5, 22, 19, DARK)
    c.rect(6, 10, 25, 22, COPPER)
    c.rect(8, 7, 23, 18, STEEL)
    c.rect(9, 10, 22, 18, BRASS)
    c.rect(11, 12, 20, 16, (23, 89, 99, 255))
    c.rect(12, 12, 15, 13, (86, 207, 193, 255))
    c.rect(10, 20, 21, 25, DARK)
    for x in (11, 15, 19):
        c.rect(x, 21, x + 1, 24, EDGE)
    c.grain()
    c.save('item/heatweave_helmet.png')

    c = Canvas(32)
    c.rect(3, 6, 28, 13, COPPER)
    c.rect(7, 8, 24, 28, DARK)
    c.rect(8, 10, 23, 26, STEEL)
    c.rect(13, 4, 18, 9, (0, 0, 0, 0))
    c.rect(8, 17, 23, 18, COPPER)
    c.rect(14, 11, 17, 23, BRASS)
    c.rect(15, 13, 16, 21, (86, 207, 193, 255))
    c.rect(5, 8, 8, 11, EDGE)
    c.rect(23, 8, 26, 11, EDGE)
    c.grain()
    c.save('item/heatweave_chestplate.png')

    c = Canvas(64, height=32)
    c.rect(0, 0, 31, 15, STEEL)
    c.rect(0, 14, 31, 15, COPPER)
    c.rect(8, 8, 15, 15, DARK)
    c.rect(8, 9, 15, 12, BRASS)
    c.rect(9, 10, 14, 11, (23, 89, 99, 255))
    c.rect(9, 14, 14, 15, EDGE)
    c.rect(16, 16, 39, 31, STEEL)
    c.rect(16, 29, 39, 31, COPPER)
    c.rect(23, 20, 24, 28, BRASS)
    c.rect(24, 21, 24, 26, (86, 207, 193, 255))
    c.rect(40, 16, 63, 31, DARK)
    c.rect(40, 20, 63, 23, COPPER)
    c.rect(40, 24, 63, 31, STEEL)
    c.grain()
    c.save('models/armor/heatweave_layer_1.png')

    c = Canvas(32)
    c.rect(22, 2, 24, 9, DARK)
    c.rect(22, 1, 24, 2, BRASS)
    c.rect(22, 3, 22, 8, EDGE)
    c.rect(8, 7, 24, 28, DARK)
    c.rect(6, 10, 25, 25, DARK)
    c.rect(8, 8, 23, 26, COPPER)
    c.rect(7, 11, 24, 24, STEEL)
    c.rect(9, 27, 22, 28, COPPER)
    for x, y in ((8, 9), (23, 9), (8, 25), (23, 25)):
        c.dot(x, y, BRASS)
    c.rect(9, 10, 22, 19, BRASS)
    c.rect(10, 11, 21, 18, (24, 55, 49, 255))
    c.rect(11, 14, 20, 14, (43, 91, 71, 255))
    c.rect(15, 12, 15, 17, (43, 91, 71, 255))
    for x, y in ((15, 12), (17, 13), (18, 14), (17, 15), (15, 16)):
        c.dot(x, y, (118, 187, 122, 255))
    c.dot(15, 14, AMBER)
    c.rect(10, 21, 14, 23, DARK)
    c.rect(11, 20, 13, 24, DARK)
    c.dot(12, 21, EDGE)
    c.dot(12, 23, EDGE)
    c.rect(18, 21, 21, 24, DARK)
    c.rect(18, 21, 20, 23, (185, 79, 39, 255))
    c.dot(19, 21, AMBER)
    c.save('item/drill_remote.png')


if __name__ == '__main__':
    generate()
