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

    def polygon(self, points, color):
        for y in range(max(0, min(p[1] for p in points)), min(self.height, max(p[1] for p in points) + 1)):
            for x in range(max(0, min(p[0] for p in points)), min(self.size, max(p[0] for p in points) + 1)):
                inside = False
                for (ax, ay), (bx, by) in zip(points, points[1:] + points[:1]):
                    if (ay > y + .5) != (by > y + .5) and x + .5 < (bx - ax) * (y + .5 - ay) / (by - ay) + ax:
                        inside = not inside
                if inside:
                    self.dot(x, y, color)

    def line(self, x0, y0, x1, y1, color):
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx, sy = 1 if x0 < x1 else -1, 1 if y0 < y1 else -1
        error = dx + dy
        while True:
            self.dot(x0, y0, color)
            if x0 == x1 and y0 == y1:
                break
            step = 2 * error
            if step >= dy:
                error += dy
                x0 += sx
            if step <= dx:
                error += dx
                y0 += sy

    def ellipse(self, x0, y0, x1, y1, color):
        cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
        rx, ry = (x1 - x0 + 1) / 2, (y1 - y0 + 1) / 2
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                if ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 <= 1:
                    self.dot(x, y, color)

    def ramp(self, x0, y0, x1, y1, top, bottom, steps=6):
        for y in range(y0, y1 + 1):
            level = round((y - y0) / max(1, y1 - y0) * (steps - 1)) / (steps - 1)
            color = tuple(round(a + (b - a) * level) for a, b in zip(top, bottom))
            self.rect(x0, y, x1, y, color)

    def light(self, amount=18):
        for y, row in enumerate(self.pixels):
            for x, rgba in enumerate(row):
                if rgba[3]:
                    level = round((.6 * x / max(1, self.size - 1) + .4 * y / max(1, self.height - 1)) * 6) / 6
                    shift = round(amount * (.5 - level))
                    row[x] = tuple(max(0, min(255, value + shift)) for value in rgba[:3]) + (rgba[3],)

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
    c.light(10)
    return c


def generate_items():
    ink = (51, 59, 67, 255)
    shadow = (76, 87, 99, 255)
    iron = (125, 139, 151, 255)
    light = (169, 182, 189, 255)
    glint = (200, 209, 208, 255)
    copper = (159, 112, 85, 255)
    copper_light = (190, 144, 107, 255)
    leather = (112, 80, 59, 255)
    leather_light = (143, 107, 75, 255)
    brass = (173, 150, 106, 255)
    paper = (208, 209, 192, 255)
    blue = (74, 126, 143, 255)
    blue_light = (117, 174, 181, 255)
    blue_dark = (48, 91, 110, 255)

    def save(canvas, name):
        canvas.light()
        canvas.grain()
        canvas.save('item/' + name + '.png')

    c = Canvas(32)
    c.polygon([(5,6),(23,3),(27,6),(27,26),(10,29),(5,26)], ink)
    c.polygon([(8,8),(24,5),(25,7),(10,10)], paper)
    c.polygon([(10,10),(26,7),(26,26),(10,29)], leather)
    c.polygon([(5,6),(8,6),(11,9),(11,28),(8,27),(5,25)], leather_light)
    c.line(7,8,7,24,copper_light)
    c.polygon([(12,11),(24,9),(24,24),(12,26)], shadow)
    c.line(12,11,23,9,iron)
    c.line(13,24,23,22,iron)
    c.ellipse(14,13,23,22,ink)
    c.ellipse(15,13,22,20,brass)
    c.ellipse(17,15,20,18,paper)
    c.line(18,17,20,14,copper)
    c.rect(24,17,27,19,copper)
    c.rect(25,18,26,19,brass)
    c.polygon([(18,25),(21,24),(21,30),(19,28),(18,30)], copper)
    for x,y in ((12,12),(23,10),(12,24),(23,22)):
        c.dot(x,y,light)
    save(c, 'expedition_journal')

    for stabilized in (False, True):
        c = Canvas(32)
        c.rect(13,2,18,5,ink)
        c.rect(14,2,17,4,brass if stabilized else iron)
        c.rect(14,2,16,2,glint)
        c.polygon([(9,5),(22,5),(24,8),(24,25),(21,29),(10,29),(7,25),(7,8)],ink)
        c.polygon([(10,7),(21,7),(22,9),(22,24),(20,27),(11,27),(9,24),(9,9)],blue_dark)
        c.rect(11,8,20,23,blue)
        c.rect(12,8,18,12,(111, 139, 151, 255))
        c.ramp(12,13,19,24,(137, 185, 190, 255),(82, 137, 155, 255))
        c.rect(17,15,20,24,(85, 143, 159, 255))
        c.rect(12,13,19,13,(157, 197, 195, 255))
        c.rect(11,9,12,19,(166, 199, 201, 255))
        c.rect(13,9,13,14,(139, 178, 187, 255))
        for y in (5,24):
            c.polygon([(9,y),(22,y),(24,y+2),(22,y+4),(9,y+4),(7,y+2)],copper if not stabilized else iron)
            c.line(10,y,21,y,copper_light if not stabilized else light)
            c.line(10,y+3,21,y+3,leather if not stabilized else shadow)
        c.rect(8,10,9,23,shadow)
        c.rect(22,10,23,23,shadow)
        c.rect(8,11,8,22,iron)
        if stabilized:
            for x in (5,24):
                c.rect(x,11,x+2,23,ink)
                c.rect(x,12,x+1,22,iron)
                c.rect(x,12,x,21,light)
                for y in (14,18,22):
                    c.rect(x,y,x+2,y,shadow)
            c.rect(9,19,22,21,brass)
            c.rect(10,19,21,19,(194, 177, 139, 255))
            c.rect(14,19,17,22,ink)
            c.rect(15,20,16,21,blue_light)
        save(c,'stabilized_coolant_cell' if stabilized else 'coolant_cell')

    c = Canvas(32)
    c.rect(11,3,20,8,ink)
    c.rect(12,3,19,6,iron)
    c.rect(14,4,17,7,(0,0,0,0))
    c.polygon([(5,10),(22,8),(28,12),(28,25),(10,29),(3,24),(3,13)],ink)
    c.polygon([(5,11),(22,9),(26,12),(10,15),(4,13)],iron)
    c.line(6,11,21,9,light)
    c.polygon([(4,15),(9,17),(9,27),(4,24)],leather)
    c.polygon([(10,16),(27,13),(27,25),(10,28)],shadow)
    c.polygon([(11,17),(25,15),(25,23),(11,26)],iron)
    c.line(11,26,24,24,shadow)
    c.line(11,17,24,15,light)
    c.line(10,15,26,12,copper)
    for x,y in ((13,16),(22,14)):
        c.rect(x,y,x+2,y+5,ink)
        c.rect(x,y,x+1,y+3,brass)
        c.dot(x,y,copper_light)
    c.line(14,23,20,22,light)
    c.line(15,24,21,23,shadow)
    save(c,'repair_kit')

    c = Canvas(32)
    c.ellipse(12,1,19,8,ink)
    c.ellipse(14,3,17,6,brass)
    c.ellipse(15,4,16,5,(0,0,0,0))
    c.polygon([(8,8),(23,8),(28,13),(28,23),(23,28),(8,28),(3,23),(3,13)],ink)
    c.polygon([(8,8),(22,8),(27,13),(27,21),(22,26),(8,26),(4,22),(4,13)],copper)
    c.line(8,8,22,8,copper_light)
    c.ellipse(6,9,25,25,shadow)
    c.ellipse(7,9,24,24,brass)
    c.ellipse(9,11,22,22,paper)
    c.ellipse(10,12,21,21,(193, 199, 187, 255))
    for x,y in ((15,12),(20,16),(15,21),(10,16)):
        c.rect(x,y,x+1,y+1,shadow)
    c.polygon([(13,17),(20,12),(17,19)],copper)
    c.polygon([(15,16),(11,21),(17,18)],blue_dark)
    c.rect(15,16,16,17,brass)
    c.rect(12,27,19,29,ink)
    c.line(13,27,18,27,iron)
    c.rect(26,16,29,19,ink)
    c.rect(27,16,28,18,iron)
    save(c,'core_surveyor')

    c = Canvas(32)
    c.rect(21,1,23,7,ink)
    c.rect(21,1,22,3,brass)
    c.rect(21,4,21,6,light)
    c.polygon([(10,5),(23,5),(27,9),(26,26),(22,30),(8,29),(5,25),(5,10)],ink)
    c.polygon([(10,6),(23,6),(25,9),(24,26),(21,28),(9,27),(7,24),(7,10)],leather)
    c.polygon([(10,7),(21,7),(23,9),(23,20),(9,20),(8,10)],iron)
    c.line(10,7,21,7,light)
    c.ellipse(9,8,22,20,shadow)
    c.ellipse(10,8,21,19,brass)
    c.ellipse(12,10,19,17,paper)
    c.line(15,13,18,10,copper)
    c.line(15,13,13,16,shadow)
    c.dot(15,10,shadow)
    c.dot(18,13,shadow)
    c.dot(12,13,shadow)
    c.dot(15,16,shadow)
    c.rect(10,22,14,25,ink)
    c.line(12,22,14,20,light)
    c.dot(14,20,glint)
    c.rect(18,22,21,25,ink)
    c.rect(19,22,20,24,copper)
    c.dot(19,22,copper_light)
    for x,y in ((8,10),(22,8),(8,25),(22,26)):
        c.dot(x,y,brass)
    save(c,'drill_remote')

    c = Canvas(32)
    c.polygon([(3,21),(8,15),(8,9),(13,10),(17,3),(23,7),(23,16),(28,20),(27,26),(19,29),(9,28)],ink)
    c.polygon([(4,22),(9,16),(14,18),(20,16),(27,21),(25,26),(17,28),(8,26)],shadow)
    c.polygon([(8,24),(11,19),(17,20),(20,26),(13,27)],leather)
    c.polygon([(9,11),(12,12),(15,21),(10,23),(8,17)],blue_dark)
    c.polygon([(10,12),(12,13),(13,20),(10,21)],blue_light)
    c.polygon([(17,4),(22,8),(21,21),(15,24),(14,15)],blue_dark)
    c.polygon([(17,5),(19,8),(18,21),(15,22),(15,14)],blue_light)
    c.line(17,7,16,18,(164, 204, 202, 255))
    c.polygon([(24,16),(26,19),(23,25),(20,25),(21,20)],blue)
    c.line(24,18,22,23,blue_light)
    c.rect(6,22,8,23,iron)
    c.rect(21,27,23,27,iron)
    save(c,'raw_harmonic')

    c = Canvas(32)
    c.polygon([(19,2),(26,10),(25,17),(15,29),(6,25),(5,17)],blue_dark)
    c.polygon([(19,3),(21,11),(15,23),(7,25),(6,18)],blue_light)
    c.polygon([(21,11),(25,10),(24,17),(15,28),(15,23)],blue)
    c.polygon([(19,3),(25,10),(21,11)],(149, 192, 192, 255))
    c.polygon([(6,18),(13,13),(18,7),(15,15),(8,23)],(161, 204, 201, 255))
    c.line(21,12,16,21,(101, 160, 174, 255))
    c.line(8,25,14,27,(99, 159, 168, 255))
    save(c,'harmonic_crystal')

    c = Canvas(32)
    c.polygon([(9,7),(21,5),(28,12),(26,24),(20,29),(8,27),(3,21),(3,12)],ink)
    c.polygon([(10,8),(20,6),(26,12),(24,23),(19,27),(9,25),(5,20),(5,12)],copper)
    c.polygon([(10,9),(19,8),(24,12),(22,21),(18,25),(10,23),(7,19),(7,13)],shadow)
    c.polygon([(12,10),(18,9),(21,13),(19,22),(13,23),(10,19),(10,13)],blue_dark)
    c.polygon([(14,11),(17,10),(18,14),(16,21),(13,21),(12,17)],blue_light)
    c.line(14,12,13,17,(161, 198, 195, 255))
    for y in (10,21):
        c.polygon([(4,y+2),(24,y),(27,y+2),(6,y+5)],iron)
        c.line(6,y+2,23,y,light)
    for x,y in ((7,10),(23,9),(7,23),(21,24)):
        c.rect(x,y,x+1,y+1,brass)
    save(c,'pressure_matrix')

    c = Canvas(32)
    c.polygon([(5,14),(21,7),(29,12),(28,20),(22,25),(7,27),(2,21),(2,17)],ink)
    c.polygon([(5,15),(21,8),(28,12),(22,18),(7,22),(3,19)],light)
    c.polygon([(4,20),(7,23),(22,19),(27,14),(27,19),(21,24),(7,26),(4,23)],iron)
    c.polygon([(7,23),(21,20),(21,24),(7,26)],shadow)
    c.line(6,15,20,9,glint)
    c.line(7,22,21,18,glint)
    c.line(12,14,18,12,blue_dark)
    c.line(13,15,19,13,blue)
    c.line(14,16,20,14,shadow)
    c.polygon([(22,16),(24,14),(25,15),(23,18)],copper)
    save(c,'relic_alloy')

    c = Canvas(32)
    c.polygon([(18,5),(22,2),(28,3),(29,9),(25,14),(22,13),(10,27),(5,28),(3,25),(4,21)],ink)
    c.polygon([(19,6),(23,3),(27,4),(26,9),(23,12),(20,11),(7,26),(5,25)],iron)
    c.polygon([(22,4),(26,4),(24,8),(22,9),(21,7)],light)
    c.line(19,9,6,24,light)
    c.line(21,12,9,26,shadow)
    c.polygon([(6,23),(10,23),(9,26),(6,27),(4,25)],copper)
    # The quarrel head is separate from the shaft and tail fins.
    c.polygon([(23,1),(30,1),(29,9),(24,7)],ink)
    c.polygon([(25,2),(29,2),(27,6)],glint)
    c.polygon([(24,3),(27,6),(24,7)],iron)
    c.polygon([(7,19),(11,23),(9,25),(5,22)],copper)
    c.line(7,19,10,22,copper_light)
    c.polygon([(5,23),(4,18),(8,19),(8,22)],shadow)
    c.polygon([(9,25),(12,25),(11,29),(7,29)],shadow)
    c.line(9,26,10,27,light)
    save(c,'pressure_bolt')

    c = Canvas(32)
    c.polygon([(17,2),(25,8),(29,18),(24,25),(14,30),(6,25),(3,17),(8,8)],(64, 51, 47, 255))
    c.polygon([(17,4),(24,9),(27,18),(22,24),(14,28),(7,24),(5,17),(9,9)],(128, 85, 61, 255))
    c.polygon([(17,5),(18,13),(14,26),(7,23),(6,17),(10,10)],copper)
    c.polygon([(18,13),(24,10),(26,18),(21,23),(14,27)],(107, 73, 57, 255))
    c.line(16,7,9,16,copper_light)
    c.line(9,16,9,21,(180, 129, 87, 255))
    c.line(10,22,14,25,copper_light)
    c.line(18,14,15,24,(151, 108, 73, 255))
    c.line(20,16,18,23,(90, 65, 55, 255))
    c.line(22,11,24,17,(176, 119, 72, 255))
    c.dot(24,18,(192, 138, 86, 255))
    save(c,'molten_scale')

    c = Canvas(32)
    c.polygon([(10,4),(21,4),(26,8),(28,15),(27,23),(22,28),(9,28),(4,24),(3,15),(6,8)],ink)
    c.polygon([(10,5),(20,5),(25,9),(26,14),(5,14),(7,9)],iron)
    c.line(11,5,19,5,glint)
    c.line(8,7,10,6,light)
    c.polygon([(7,12),(23,12),(26,15),(25,21),(21,24),(9,24),(5,21),(5,15)],copper)
    c.polygon([(9,13),(21,13),(24,16),(23,20),(20,22),(10,22),(7,20),(7,16)],ink)
    c.polygon([(10,14),(20,14),(22,16),(21,20),(10,20),(8,18)],blue_dark)
    c.polygon([(10,14),(17,14),(13,19),(9,19),(9,16)],blue)
    c.line(10,14,17,14,blue_light)
    c.line(9,15,9,17,blue_light)
    c.rect(11,24,20,27,shadow)
    for x in (12,15,18):
        c.rect(x,24,x+1,26,iron)
        c.dot(x,24,light)
    for x in (3,24):
        c.rect(x,19,x+3,24,shadow)
        c.rect(x,20,x+2,23,iron)
        c.rect(x,20,x,22,light)
    for x,y in ((6,15),(24,15),(8,22),(22,22)):
        c.dot(x,y,brass)
    save(c,'heatweave_helmet')

    c = Canvas(32)
    c.polygon([(4,6),(11,4),(13,7),(18,7),(20,4),(27,6),(30,12),(25,16),(24,28),(8,29),(7,16),(1,12)],ink)
    c.polygon([(4,7),(10,5),(12,9),(19,9),(21,5),(26,7),(28,12),(23,14),(22,27),(9,27),(9,14),(3,12)],leather)
    c.polygon([(4,7),(9,6),(10,11),(7,14),(3,11)],iron)
    c.polygon([(22,6),(26,8),(28,12),(24,14),(21,11)],iron)
    c.line(4,7,8,6,light)
    c.line(23,7,26,9,light)
    c.polygon([(10,12),(14,10),(14,24),(10,25)],shadow)
    c.polygon([(17,10),(21,12),(21,25),(17,24)],shadow)
    c.polygon([(10,12),(13,11),(13,22),(10,23)],iron)
    c.polygon([(18,11),(21,12),(21,23),(18,22)],iron)
    c.line(10,13,10,20,light)
    c.rect(14,11,16,25,leather_light)
    c.rect(9,24,22,26,ink)
    c.rect(10,24,21,25,copper)
    c.rect(14,23,17,26,brass)
    c.rect(15,24,16,25,ink)
    c.ellipse(17,13,21,17,ink)
    c.ellipse(18,14,20,16,blue)
    c.dot(18,14,blue_light)
    c.line(20,17,20,21,copper)
    c.line(20,21,23,22,copper)
    save(c,'heatweave_chestplate')

    c = Canvas(32)
    c.polygon([(11,2),(20,2),(23,5),(24,10),(28,13),(27,23),(22,26),(21,30),(10,30),(9,26),(4,23),(3,13),(8,10),(8,5)],ink)
    c.polygon([(12,3),(19,3),(21,6),(21,25),(19,29),(12,29),(10,25),(10,6)],shadow)
    c.rect(12,5,18,26,blue_dark)
    c.rect(13,7,15,24,blue)
    c.rect(14,9,15,21,blue_light)
    c.rect(12,5,18,6,iron)
    c.rect(12,25,18,26,iron)
    for y in (8,14,20):
        c.polygon([(6,y+2),(23,y),(26,y+2),(9,y+5)],copper)
        c.line(7,y+2,22,y,copper_light)
        c.line(10,y+4,24,y+2,leather)
    for x in (5,23):
        c.rect(x,11,x+2,23,iron)
        c.rect(x,12,x,21,light)
        c.rect(x,15,x+2,16,shadow)
        c.rect(x,20,x+2,21,shadow)
    c.rect(13,2,18,4,light)
    c.rect(13,27,18,29,iron)
    c.rect(14,3,17,3,glint)
    save(c,'core_heart')

    c = Canvas(32, iron)
    c.rect(0,0,15,15,leather)
    c.ramp(1,1,14,14,(149, 113, 83, 255),(110, 78, 58, 255))
    c.ramp(2,2,13,13,(127, 93, 68, 255),(97, 70, 55, 255))
    c.rect(0,0,15,0,light)
    c.rect(0,1,0,14,iron)
    c.rect(15,1,15,15,shadow)
    c.rect(1,15,14,15,shadow)
    for x,y in ((2,2),(12,2),(2,12),(12,12)):
        c.rect(x,y,x+1,y+1,ink)
        c.dot(x,y,light)
    c.ramp(16,0,31,15,(224, 226, 213, 255),(180, 190, 182, 255))
    c.rect(18,2,28,2,(230, 232, 220, 255))
    c.rect(18,3,18,8,(214, 222, 213, 255))
    for x,y in ((23,3),(26,4),(28,7),(27,10),(23,12),(20,10),(19,7),(20,4)):
        c.dot(x,y,shadow)
    c.dot(23,4,shadow)
    c.dot(23,11,shadow)
    c.dot(20,7,shadow)
    c.dot(27,7,shadow)
    c.line(23,8,27,4,copper)
    c.line(23,8,21,11,blue_dark)
    c.rect(23,7,24,8,brass)
    c.rect(0,16,15,31,leather)
    c.rect(1,17,14,30,shadow)
    c.ramp(2,18,13,29,light,iron)
    c.rect(3,19,12,28,shadow)
    c.rect(4,20,11,27,leather)
    for y in (22,25):
        c.rect(5,y,10,y,leather_light)
    c.rect(16,16,31,31,shadow)
    c.ramp(17,17,30,29,(151, 165, 177, 255),(109, 124, 140, 255))
    c.rect(18,18,29,19,light)
    c.rect(18,28,29,29,ink)
    c.line(20,23,23,26,shadow)
    c.line(23,26,27,22,shadow)
    for x,y in ((18,18),(29,18),(18,28),(29,28)):
        c.dot(x,y,brass)
    c.grain()
    c.save('item/field_instruments.png')


def generate():
    generate_items()
    for name, color in (('steel_trim', (113, 124, 135)), ('copper_trim', (163, 117, 91)),
                        ('brass_trim', (177, 151, 111)), ('dark_metal', (56, 59, 65))):
        c = Canvas(16)
        for y in range(16):
            for x in range(16):
                shift = (14, 14, 10, 10, 6, 6, 3, 3, 0, 0, -3, -3, -8, -8, -14, -14)[y]
                shift += (7, 7, 4, 4, 1, 1, -2, -2, -3, -3, 0, 0, 2, 2, -5, -5)[x]
                if name == 'dark_metal':
                    shift = round(shift * .55)
                c.dot(x, y, tuple(channel + shift for channel in color) + (255,))
        for x, y in ((3, 3), (8, 6), (5, 10), (10, 11)):
            c.rect(x, y, x + 2, y + 1, tuple(channel + 4 for channel in color) + (255,))
        c.grain()
        c.save('block/' + name + '.png')

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

    c = Canvas(16, (105, 113, 118, 255))
    c.rect(0, 0, 15, 0, (143, 150, 154, 255))
    c.rect(0, 1, 0, 14, (129, 138, 144, 255))
    c.rect(0, 15, 15, 15, (90, 99, 106, 255))
    c.rect(15, 1, 15, 15, (97, 106, 113, 255))
    c.ramp(2, 2, 13, 13, (142, 152, 162, 255), (112, 125, 140, 255))
    c.rect(2, 2, 13, 2, (143, 151, 157, 255))
    c.rect(2, 3, 3, 11, (135, 144, 151, 255))
    c.rect(3, 12, 13, 13, (114, 124, 133, 255))
    c.rect(12, 4, 13, 11, (120, 130, 139, 255))
    c.rect(4, 3, 11, 4, (132, 140, 147, 255))
    c.rect(4, 10, 11, 11, (124, 132, 139, 255))
    c.rect(4, 5, 7, 5, (130, 138, 145, 255))
    c.rect(9, 9, 11, 9, (123, 132, 140, 255))
    c.rect(4, 13, 7, 13, (120, 130, 138, 255))
    for x, y in ((1, 1), (13, 13)):
        c.rect(x, y, x + 1, y + 1, (94, 103, 111, 255))
        c.dot(x, y, (158, 165, 170, 255))
        c.dot(x + 1, y, (127, 138, 146, 255))
    c.grain()
    c.save('block/refractory_hull.png')
    hull = [row[:] for row in c.pixels]

    c = Canvas(16)
    c.rect(0, 0, 15, 15, (111, 122, 132, 255))
    c.rect(1, 1, 14, 14, (139, 148, 154, 255))
    c.rect(2, 2, 13, 13, (67, 78, 88, 255))
    c.rect(3, 3, 12, 12, (0, 0, 0, 0))
    c.rect(3, 3, 12, 3, (164, 183, 195, 255))
    c.rect(3, 4, 3, 12, (124, 151, 172, 255))
    for x,y in ((1,1),(14,1),(1,14),(14,14)):
        c.dot(x,y,(163, 119, 91, 255))
    for x,y in ((4,4),(5,4),(4,5),(10,11),(11,11)):
        c.dot(x,y,(146,177,196,255))
    c.save('block/mantle_viewport.png')

    c = Canvas(16)
    c.pixels = [row[:] for row in hull]
    c.rect(1, 1, 14, 14, (90, 99, 104, 255))
    c.rect(2, 2, 13, 13, (138, 147, 154, 255))
    c.ramp(3, 3, 12, 12, (140, 151, 163, 255), (110, 125, 142, 255))
    c.rect(3, 3, 12, 3, (153, 162, 169, 255))
    c.rect(3, 12, 12, 12, (99, 112, 125, 255))
    c.rect(4, 4, 5, 10, (127, 138, 149, 255))
    for x, y in ((3, 3), (11, 3), (3, 11), (11, 11)):
        c.dot(x, y, (162, 169, 174, 255))
        c.dot(x + 1, y, (109, 120, 131, 255))
    c.rect(6, 13, 9, 13, (147, 125, 92, 255))
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

    c = Canvas(16, (147, 103, 80, 255))
    c.rect(1, 0, 3, 15, (174, 130, 101, 255))
    c.rect(4, 0, 6, 15, (166, 119, 92, 255))
    c.rect(12, 0, 15, 15, (131, 92, 73, 255))
    c.rect(0, 1, 15, 2, (169, 125, 97, 255))
    c.rect(0, 13, 15, 14, (135, 96, 77, 255))
    for y in (5, 10):
        c.rect(1, y, 14, y, (143, 103, 82, 255))
    c.grain()
    c.save('block/mantle_ballast.png')

    c = Canvas(16, (65, 72, 79, 255))
    c.rect(1, 1, 14, 14, (79, 86, 94, 255))
    c.rect(3, 2, 12, 12, (53, 61, 70, 255))
    for y in (3, 6, 9, 12):
        c.rect(4, y, 11, y, (60, 69, 78, 255))
    c.grain()
    c.save('block/cabin_regulator.png')

    c = plate()
    c.rect(3, 2, 12, 13, DARK)
    c.ramp(4, 2, 11, 13, (182, 134, 99, 255), (126, 81, 65, 255))
    c.ramp(6, 3, 9, 12, (194, 151, 106, 255), (146, 97, 73, 255))
    for y in (4, 8, 12):
        c.rect(4, y, 11, y, (112, 126, 140, 255))
        c.rect(4, y, 5, y, (155, 169, 178, 255))
    c.rect(6, 5, 9, 7, (66, 76, 80, 255))
    c.ramp(7, 5, 8, 7, (88, 113, 123, 255), (61, 83, 95, 255), steps=3)
    c.dot(7, 5, (114, 140, 146, 255))
    c.grain()
    c.save('block/heat_engine.png')
    c.ramp(7, 5, 8, 7, (162, 196, 192, 255), (100, 156, 164, 255), steps=3)
    c.dot(7, 5, (194, 214, 202, 255))
    c.save('block/heat_engine_active.png')

    c = Canvas(16, (197, 197, 185, 255))
    c.rect(2, 2, 13, 4, (210, 210, 200, 255))
    c.rect(2, 11, 13, 13, (185, 187, 179, 255))
    for x, y in ((5, 4), (8, 3), (10, 4), (12, 6), (12, 9), (10, 11), (5, 11), (3, 9), (3, 6)):
        c.dot(x, y, (107, 112, 113, 255))
    c.rect(5, 12, 9, 12, (162, 165, 157, 255))
    c.rect(7, 7, 8, 9, (107, 112, 113, 255))
    for x, y in ((8, 7), (9, 6), (10, 5), (11, 4)):
        c.dot(x, y, (157, 103, 79, 255))
    c.dot(7, 8, (155, 143, 116, 255))
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

    c = Canvas(64, height=32)
    c.rect(0, 0, 31, 15, (115, 130, 143, 255))
    c.rect(8, 0, 15, 7, (144, 158, 168, 255))
    c.rect(9, 1, 14, 2, (163, 176, 183, 255))
    c.rect(0, 10, 31, 12, (145, 101, 79, 255))
    c.rect(8, 9, 15, 14, COPPER)
    c.rect(9, 10, 14, 12, (48, 69, 82, 255))
    c.rect(10, 10, 13, 11, (74, 126, 143, 255))
    c.rect(10, 10, 11, 10, (117, 174, 181, 255))
    c.rect(9, 14, 14, 15, DARK)
    for x in (9, 11, 13):
        c.rect(x, 14, x, 15, (145, 158, 168, 255))
    for x in (1, 18):
        c.rect(x, 12, x + 3, 14, DARK)
        c.rect(x + 1, 12, x + 2, 13, EDGE)
    c.rect(16, 16, 39, 31, (112, 80, 59, 255))
    c.rect(20, 20, 22, 28, (125, 139, 151, 255))
    c.rect(25, 20, 27, 28, (125, 139, 151, 255))
    c.rect(20, 21, 20, 26, (169, 182, 189, 255))
    c.rect(23, 20, 24, 28, (143, 107, 75, 255))
    c.rect(26, 23, 27, 25, DARK)
    c.dot(26, 23, (117, 174, 181, 255))
    c.rect(16, 29, 39, 30, (68, 58, 53, 255))
    c.rect(23, 29, 24, 30, BRASS)
    c.rect(40, 16, 55, 31, (112, 80, 59, 255))
    c.rect(40, 20, 55, 23, (125, 139, 151, 255))
    c.rect(44, 20, 47, 20, (169, 182, 189, 255))
    c.rect(40, 24, 55, 24, COPPER)
    c.grain()
    c.save('models/armor/heatweave_layer_1.png')

if __name__ == '__main__':
    generate()
