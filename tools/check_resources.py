#!/usr/bin/env python3
"""Check resource syntax, translations, recipe unlocks and generated textures."""
import hashlib
import json
from pathlib import Path
import re
import runpy
import tempfile
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / 'src/main/resources'


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f'Duplicate JSON key: {key}')
        result[key] = value
    return result


def pixel_hash(path):
    data = path.read_bytes()
    offset = 8
    header = b''
    compressed = b''
    while offset < len(data):
        length = int.from_bytes(data[offset:offset + 4], 'big')
        kind = data[offset + 4:offset + 8]
        payload = data[offset + 8:offset + 8 + length]
        if kind == b'IHDR':
            header = payload
        elif kind == b'IDAT':
            compressed += payload
        offset += length + 12
    return hashlib.sha256(header + zlib.decompress(compressed)).digest()


def check():
    documents = {path: json.loads(path.read_text(), object_pairs_hook=unique_object)
                 for path in RESOURCES.rglob('*.json')}
    assets = RESOURCES / 'assets/aeronautics_innerworld'
    en = documents[assets / 'lang/en_us.json']
    zh = documents[assets / 'lang/zh_cn.json']
    assert en.keys() == zh.keys(), 'Translation keys differ'
    for key in en:
        assert len(re.findall(r'%(?:\d+\$)?[sdf]', en[key])) == len(re.findall(r'%(?:\d+\$)?[sdf]', zh[key])), key
    data = RESOURCES / 'data/aeronautics_innerworld'
    unlocks = documents[data / 'advancement/recipes/expedition_equipment.json']['rewards']['recipes']
    for path in (data / 'recipe').glob('*.json'):
        assert 'aeronautics_innerworld:' + path.stem in unlocks, f'Missing recipe unlock: {path.stem}'
    for document in documents.values():
        def visit(value):
            if isinstance(value, dict):
                translation = value.get('translate', '')
                if translation.startswith(('book.aeronautics_innerworld.', 'message.aeronautics_innerworld.')):
                    assert translation in en, f'Missing translation: {translation}'
                for item in value.values():
                    visit(item)
            elif isinstance(value, list):
                for item in value:
                    visit(item)
            elif isinstance(value, str) and value.startswith('{'):
                visit(json.loads(value))
        visit(document)
    textures = assets / 'textures'
    before = {path.relative_to(textures): pixel_hash(path) for path in textures.rglob('*.png')}
    with tempfile.TemporaryDirectory(prefix='innerworld-textures-') as directory:
        generated = Path(directory)
        generator = runpy.run_path(str(ROOT / 'tools/generate_textures.py'))['generate']
        generator.__globals__['ROOT'] = generated
        generator()
        after = {path.relative_to(generated): pixel_hash(path) for path in generated.rglob('*.png')}
    assert before == after, 'Generated textures differ from committed resources'
    for path in (ROOT / 'build/libs').glob('*.jar'):
        if path.name.endswith('-sources.jar'):
            continue
        with zipfile.ZipFile(path) as archive:
            assert 'META-INF/LICENSE' in archive.namelist(), 'Release license missing'
            assert not any('/integration/' in name for name in archive.namelist()), 'Runtime checks entered release jar'
    print(f'Checked {len(documents)} JSON resources, {len(en)} bilingual entries and {len(before)} original textures.')


if __name__ == '__main__':
    check()
