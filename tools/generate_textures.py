#!/usr/bin/env python3
"""Rebuild the original pixel textures using only the Python standard library."""
from pathlib import Path
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/aeronautics_innerworld/textures'
STEEL = (79, 89, 91, 255)
DARK = (34, 42, 44, 255)
EDGE = (126, 142, 142, 255)
COPPER = (160, 96, 52, 255)
BRASS = (198, 155, 76, 255)
AMBER = (255, 186, 57, 255)


class Canvas:
    def __init__(self, size, color=(0, 0, 0, 0)):
        self.size = size
        self.pixels = [[color for _ in range(size)] for _ in range(size)]

    def rect(self, x0, y0, x1, y1, color):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.pixels[y][x] = color

    def dot(self, x, y, color):
        self.pixels[y][x] = color

    def grain(self):
        for y, row in enumerate(self.pixels):
            for x, rgba in enumerate(row):
                delta = ((x * 43 + y * 29 + x * y * 7) % 7) - 3
                if rgba[3]:
                    row[x] = tuple(max(0, min(255, value + delta)) for value in rgba[:3]) + (rgba[3],)

    def save(self, name):
        def chunk(kind, data):
            return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
        rows = b''.join(b'\0' + bytes(c for pixel in row for c in pixel) for row in self.pixels)
        data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', self.size, self.size, 8, 6, 0, 0, 0))
        data += chunk(b'IDAT', zlib.compress(rows, 9)) + chunk(b'IEND', b'')
        path = ROOT / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)


def plate():
    c = Canvas(16, STEEL)
    c.rect(0, 0, 15, 0, EDGE)
    c.rect(0, 1, 0, 14, EDGE)
    c.rect(0, 15, 15, 15, DARK)
    c.rect(15, 1, 15, 15, DARK)
    for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
        c.rect(x - 1, y - 1, x + 1, y + 1, DARK)
        c.dot(x, y, EDGE)
    return c


def generate():
    c = plate()
    c.rect(4, 1, 5, 14, COPPER)
    c.rect(5, 1, 5, 14, BRASS)
    c.rect(10, 1, 11, 14, COPPER)
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

    c = plate()
    for y in (3, 7, 11):
        c.rect(3, y, 12, y + 1, (108, 94, 83, 255))
        c.rect(3, y, 12, y, (144, 123, 98, 255))
    c.rect(1, 5, 2, 10, COPPER)
    c.rect(13, 5, 14, 10, COPPER)
    c.grain()
    c.save('block/refractory_hull.png')

    c = Canvas(16)
    c.rect(0, 0, 15, 15, DARK)
    c.rect(1, 1, 14, 14, COPPER)
    c.rect(2, 2, 13, 13, BRASS)
    c.rect(3, 3, 12, 12, (0, 0, 0, 0))
    c.rect(3, 3, 12, 3, (98, 126, 117, 255))
    c.rect(3, 4, 3, 12, (72, 94, 90, 255))
    for x,y in ((1,1),(14,1),(1,14),(14,14)):
        c.dot(x,y,EDGE)
    for x,y in ((5,5),(6,6),(10,10),(11,11)):
        c.dot(x,y,(158,192,173,255))
    c.save('block/mantle_viewport.png')

    c = plate()
    c.rect(2, 2, 13, 13, COPPER)
    c.rect(3, 3, 12, 12, DARK)
    c.rect(4, 4, 11, 11, STEEL)
    c.rect(5, 7, 10, 8, BRASS)
    c.rect(7, 5, 8, 10, BRASS)
    c.rect(7, 7, 8, 8, DARK)
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
    c.rect(3, 2, 12, 13, COPPER)
    c.rect(4, 3, 11, 12, DARK)
    c.rect(5, 4, 10, 11, (83, 113, 102, 255))
    for y in (5,8,10):
        c.rect(5,y,10,y,BRASS)
    c.rect(7,2,8,13,EDGE)
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
