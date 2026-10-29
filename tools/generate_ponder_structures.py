"""Build the original demonstration layouts used by the Ponder storyboards."""
import gzip
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/aeronautics_innerworld/ponder'
MOD = 'aeronautics_innerworld:'


def string(value):
    encoded = value.encode('utf-8')
    return struct.pack('>H', len(encoded)) + encoded


def compound(entries):
    return b''.join(bytes([kind]) + string(name) + data for name, kind, data in entries) + b'\0'


def nbt_list(kind, entries):
    return bytes([kind]) + struct.pack('>i', len(entries)) + b''.join(entries)


class Layout:
    def __init__(self):
        self.blocks = {}
        self.fill((0, 0, 0), (8, 0, 8), 'minecraft:gray_concrete')

    def put(self, x, y, z, name, **properties):
        self.blocks[x, y, z] = (name if ':' in name else MOD + name, tuple(sorted(properties.items())))

    def fill(self, start, end, name, **properties):
        for x in range(start[0], end[0] + 1):
            for y in range(start[1], end[1] + 1):
                for z in range(start[2], end[2] + 1):
                    self.put(x, y, z, name, **properties)

    def engine(self, x, y, z, facing):
        self.put(x, y, z, 'heat_engine', facing=facing, active='false')

    def save(self, name):
        palette = list(dict.fromkeys(self.blocks.values()))
        states = [compound([('Name', 8, string(block))] +
                           ([('Properties', 10, compound([(key, 8, string(value)) for key, value in props]))] if props else []))
                  for block, props in palette]
        blocks = [compound([('pos', 9, nbt_list(3, [struct.pack('>i', n) for n in pos])),
                            ('state', 3, struct.pack('>i', palette.index(state)))])
                  for pos, state in sorted(self.blocks.items())]
        root = compound([('DataVersion', 3, struct.pack('>i', 3955)),
                         ('size', 9, nbt_list(3, [struct.pack('>i', n) for n in (9, 9, 9)])),
                         ('palette', 9, nbt_list(10, states)), ('blocks', 9, nbt_list(10, blocks)),
                         ('entities', 9, nbt_list(10, []))])
        ROOT.mkdir(parents=True, exist_ok=True)
        (ROOT / (name + '.nbt')).write_bytes(gzip.compress(b'\x0a\0\0' + root, mtime=0))


def generate():
    drill = Layout()
    drill.put(2, 2, 3, 'simulated:gray_portable_engine', facing='south', lit='true')
    drill.put(2, 2, 4, 'drill_controller', facing='south')
    drill.put(2, 1, 4, 'create:andesite_casing')
    drill.put(2, 2, 5, 'multi_axis_cutter')
    drill.put(3, 2, 5, 'minecraft:chest', facing='south')
    drill.fill((2, 1, 6), (3, 3, 6), 'minecraft:deepslate')
    drill.save('drill_assembly')

    connection = Layout()
    connection.fill((1, 5, 1), (3, 5, 3), 'minecraft:deepslate')
    connection.put(2, 6, 2, 'drill_controller', facing='down')
    connection.put(2, 7, 2, 'create:gearbox', axis='x')
    connection.put(2, 7, 1, 'simulated:gray_portable_engine', facing='south', lit='true')
    connection.put(1, 6, 2, 'bore_station', facing='south')
    connection.put(2, 5, 2, 'multi_axis_cutter')
    connection.put(3, 5, 2, 'bore_station', facing='south')
    connection.put(2, 5, 3, 'minecraft:barrel', facing='up')
    connection.put(3, 5, 3, 'minecraft:air')
    connection.fill((4, 1, 4), (7, 1, 7), 'minecraft:deepslate')
    connection.save('layer_connection')

    cabin = Layout()
    cabin.fill((2, 1, 2), (6, 4, 6), 'refractory_hull')
    cabin.fill((3, 2, 3), (5, 3, 5), 'minecraft:air')
    for y in (2, 3):
        cabin.put(2, y, 4, 'pressure_hatch', facing='west', open='true')
        cabin.put(4, y, 6, 'mantle_viewport')
    cabin.put(4, 2, 2, 'cabin_regulator', facing='south')
    cabin.put(4, 5, 4, 'simulated:physics_assembler', face='floor', facing='north')
    cabin.save('sealed_probe')

    heat = Layout()
    heat.fill((2, 1, 2), (6, 1, 6), 'refractory_hull')
    heat.fill((2, 2, 2), (6, 2, 2), 'refractory_hull')
    heat.put(4, 2, 2, 'cabin_regulator', facing='south')
    heat.engine(4, 2, 1, 'north')
    heat.engine(2, 2, 4, 'north')
    heat.put(2, 2, 5, 'molten_thruster', facing='south')
    heat.engine(6, 2, 4, 'north')
    heat.put(6, 2, 5, 'molten_thruster', facing='south')
    heat.save('heat_exchange')

    propulsion = Layout()
    propulsion.fill((2, 1, 1), (6, 1, 6), 'refractory_hull')
    for x in (3, 5):
        propulsion.engine(x, 2, 2, 'north')
        propulsion.put(x, 2, 3, 'create:clutch', axis='z', powered='true')
        propulsion.put(x, 2, 4, 'create:gearshift', axis='z', powered='false')
        propulsion.put(x, 2, 5, 'molten_thruster', facing='south')
        propulsion.put(x, 3, 3, 'minecraft:lever', face='floor', facing='south', powered='true')
        propulsion.put(x, 3, 4, 'minecraft:lever', face='floor', facing='south', powered='false')
        propulsion.put(x, 2, 1, 'mantle_ballast', facing='up')
        propulsion.put(x, 1, 1, 'create:gearbox', axis='x')
        propulsion.engine(x, 1, 2, 'south')
    propulsion.save('propulsion_trim')

    sample = Layout()
    sample.fill((1, 1, 1), (3, 1, 5), 'refractory_hull')
    sample.put(3, 2, 4, 'mantle_sampler', facing='east')
    sample.engine(2, 2, 4, 'west')
    sample.put(2, 2, 2, 'echo_sounder', facing='south')
    sample.engine(2, 2, 1, 'north')
    sample.fill((7, 1, 3), (7, 3, 5), 'minecraft:deepslate')
    sample.put(7, 2, 4, 'harmonic_ore')
    sample.save('survey_sampling')


if __name__ == '__main__':
    generate()
