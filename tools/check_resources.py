#!/usr/bin/env python3
"""Check resource syntax, translations, recipe unlocks and generated textures."""
import gzip
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
        for text in (en[key], zh[key]):
            assert '%' not in re.sub(r'%%|%(?:[1-9]\d*\$)?s', '', text), f'Invalid translation format: {key}'
        assert len(re.findall(r'%(?:\d+\$)?[sdf]', en[key])) == len(re.findall(r'%(?:\d+\$)?[sdf]', zh[key])), key
    ponder_source = ROOT / 'src/main/java/io/github/alphaly/aeronauticsinnerworld/client/ponder'
    plugin = (ponder_source / 'InnerworldPonderPlugin.java').read_text()
    layouts = set(re.findall(r'addStoryBoard\("([^"]+)"', plugin))
    assert layouts == {path.stem for path in (assets / 'ponder').glob('*.nbt')}, 'Missing or unused Ponder layout'
    storyboards = (ponder_source / 'InnerworldPonderScenes.java').read_text()
    for part in re.split(r'public static void \w+\(SceneBuilder builder, SceneBuildingUtil util\)', storyboards)[1:]:
        name, title = re.search(r'begin\(builder, "([^"]+)", "([^"]+)"\)', part).groups()
        prefix = 'aeronautics_innerworld.ponder.' + name + '.'
        assert name in layouts and en[prefix + 'header'] == title, f'Untranslated Ponder title: {name}'
        for index, text in enumerate(re.findall(r'narrate\(scene, "([^"]+)"', part), 1):
            assert en[prefix + 'text_' + str(index)] == text, f'Ponder caption order changed: {name} #{index}'
    with tempfile.TemporaryDirectory(prefix='innerworld-ponder-') as directory:
        generator = runpy.run_path(str(ROOT / 'tools/generate_ponder_structures.py'))['generate']
        generator.__globals__['ROOT'] = Path(directory)
        generator()
        for path in (assets / 'ponder').glob('*.nbt'):
            assert gzip.decompress(path.read_bytes()) == gzip.decompress((Path(directory) / path.name).read_bytes()), f'Ponder layout needs regeneration: {path.stem}'
    data = RESOURCES / 'data/aeronautics_innerworld'
    unlocks = {recipe for path in (data / 'advancement/recipes').glob('*.json')
               for recipe in documents[path].get('rewards', {}).get('recipes', [])}
    for path in (data / 'recipe').glob('*.json'):
        assert 'aeronautics_innerworld:' + path.stem in unlocks, f'Missing recipe unlock: {path.stem}'
    journal = assets / 'patchouli_books/expedition/en_us'
    entries = {'aeronautics_innerworld:' + path.relative_to(journal / 'entries').with_suffix('').as_posix(): documents[path]
               for path in (journal / 'entries').rglob('*.json')}
    categories = {'aeronautics_innerworld:' + path.stem for path in (journal / 'categories').glob('*.json')}
    advancements = {'aeronautics_innerworld:' + path.relative_to(data / 'advancement').with_suffix('').as_posix()
                    for path in (data / 'advancement').rglob('*.json')}
    templates = {'aeronautics_innerworld:' + path.stem for path in (journal / 'templates').glob('*.json')}
    for path in (journal / 'templates').glob('*.json'):
        for component in documents[path]['components']:
            height = component.get('height', 16 if component['type'] == 'patchouli:item' else 9)
            assert component.get('y', 0) + height <= 156, f'Journal component exceeds its page: {path.stem}'
    for name, entry in entries.items():
        assert entry['category'] in categories, f'Missing journal category: {name}'
        for field in ('advancement', 'turnin'):
            assert field not in entry or entry[field] in advancements, f'Missing journal milestone: {name}'
        for page in entry['pages']:
            kind = page['type']
            assert kind.startswith('patchouli:') or kind in templates, f'Missing journal template: {kind}'
            if kind == 'patchouli:quest':
                assert page['trigger'] in advancements, f'Missing quest completion: {name}'
            if kind == 'patchouli:relations':
                assert set(page['entries']) <= entries.keys(), f'Broken journal task link: {name}'
            for field in ('recipe', 'recipe2'):
                if field in page:
                    recipe = page[field].removeprefix('aeronautics_innerworld:')
                    assert (data / 'recipe' / (recipe + '.json')) in documents, f'Broken journal recipe: {name}'
    for key in en:
        if key.startswith('book.aeronautics_innerworld.tasks.'):
            for text in (en[key], zh[key]):
                for target in re.findall(r'\$\(l:([^)]*)\)', text):
                    assert 'aeronautics_innerworld:' + target in entries, f'Broken translated journal link: {key}'
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
            elif isinstance(value, str):
                if value.startswith('book.aeronautics_innerworld.'):
                    assert value in en, f'Missing book translation: {value}'
                elif value.startswith('{'):
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
