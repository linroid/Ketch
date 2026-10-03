#!/usr/bin/env python3
"""Checks the app's translations against English without building, as LocalizationResourcesTest
does: every translated string exists in English, keeps its placeholders, plurals have an "other"
form, and no Android-style escapes slip into Compose resources. Missing strings are listed; they
show in English.

Usage: tools/i18n/check_translations.py [language-folder-suffix ...]   e.g. zh ja zh-rTW
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMPOSE_ROOTS = [
    ROOT / 'app/shared/src/commonMain/composeResources',
    ROOT / 'app/desktop/src/main/composeResources',
]
ANDROID_ROOT = ROOT / 'app/android/src/main/res'
PLACEHOLDER = re.compile(r'%(\d+)\$[sd]')
BARE = re.compile(r'%(\d+[sd]|[sd])')
ANDROID_PLACEHOLDER = re.compile(r'%(\d+)\$[sd]')


def strings(folder: Path) -> dict:
    found = {}
    for file in sorted(folder.glob('*.xml')):
        tree = ET.parse(file)
        for node in tree.getroot():
            name = node.get('name')
            if node.tag == 'string':
                found[name] = (file.name, False, {'': ''.join(node.itertext())})
            elif node.tag == 'plurals':
                found[name] = (file.name, True,
                               {i.get('quantity'): ''.join(i.itertext()) for i in node.iter('item')})
    return found


def placeholders(text: str) -> set:
    return {int(m) for m in PLACEHOLDER.findall(text)}


def check(english: dict, translated: dict, where: str, compose: bool) -> list:
    problems = []
    for name, (file, plural, forms) in translated.items():
        if name not in english:
            problems.append(f'{where}/{file}: {name} is not in English')
            continue
        efile, eplural, eforms = english[name]
        if efile != file:
            problems.append(f'{where}/{file}: {name} belongs in {efile}')
        if eplural != plural:
            problems.append(f'{where}/{file}: {name} must be a {"plural" if eplural else "string"}')
            continue
        wanted = set().union(*(placeholders(t) for t in eforms.values()))
        for quantity, text in forms.items():
            label = f'{where}/{file}: {name}' + (f'[{quantity}]' if quantity else '')
            if compose and ("\\'" in text or '\\"' in text):
                problems.append(f'{label} escapes a quote; Compose shows the backslash')
            if BARE.search(text):
                problems.append(f'{label} has a placeholder without a position')
            if text != text.strip() or '\n' in text:
                problems.append(f'{label} starts or ends with whitespace')
            if not text.strip():
                problems.append(f'{label} is empty')
        if plural:
            if 'other' not in forms:
                problems.append(f'{where}/{file}: {name} has no "other" form')
            elif placeholders(forms['other']) != wanted:
                problems.append(f'{where}/{file}: {name}[other] has placeholders '
                                f'{sorted(placeholders(forms["other"]))}, English has {sorted(wanted)}')
        elif placeholders(forms['']) != wanted:
            problems.append(f'{where}/{file}: {name} has placeholders '
                            f'{sorted(placeholders(forms[""]))}, English has {sorted(wanted)}')
    return problems


def main() -> int:
    only = set(sys.argv[1:])
    problems, missing = [], []
    for root in COMPOSE_ROOTS + [ANDROID_ROOT]:
        if not root.is_dir():
            continue
        english = strings(root / 'values')
        for folder in sorted(root.glob('values-*')):
            suffix = folder.name[len('values-'):]
            if only and suffix not in only:
                continue
            translated = strings(folder)
            if not translated:
                continue
            where = str(folder.relative_to(ROOT))
            problems += check(english, translated, where, compose=root != ANDROID_ROOT)
            gone = sorted(set(english) - set(translated))
            if gone:
                missing.append(f'{where}: {len(gone)} strings missing: {", ".join(gone[:20])}'
                               + (' …' if len(gone) > 20 else ''))
    for line in missing:
        print('missing', line)
    for line in problems:
        print('PROBLEM', line)
    print(f'{len(problems)} problems')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
