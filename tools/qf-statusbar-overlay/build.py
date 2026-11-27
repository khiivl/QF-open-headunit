#!/usr/bin/env python3
"""Builds the system overlay that takes the Home and Back buttons off a QF unit's status bar.

SystemUI inflates each of the two buttons from a layout of its own and looks them up by id afterwards, so the overlay
keeps both views and makes them gone. It is shipped in the app (assets) and installed from there as a Magisk module; see
StatusBarButtons.

Usage: build.py <SystemUI apk pulled from the unit> <framework-res.apk pulled from the unit> [--sdk <Android SDK>]
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

PACKAGE = 'com.andrerinas.openheadunit.statusbuttons'
VERSION = 1
LAYOUTS = ('home', 'back')

HERE = Path(__file__).resolve().parent
OUTPUT = HERE.parent.parent / 'app' / 'src' / 'main' / 'assets' / 'qf_status_buttons' / 'OhuStatusButtons.apk'

MANIFEST = f'''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="{PACKAGE}" android:versionCode="{VERSION}" android:versionName="{VERSION}">
    <uses-sdk android:minSdkVersion="28" android:targetSdkVersion="29" />
    <overlay android:targetPackage="com.android.systemui" android:isStatic="false" android:priority="500" />
    <application android:hasCode="false" android:label="OHU status bar buttons" />
</manifest>
'''


def layout(name):
    return f'''<?xml version="1.0" encoding="utf-8"?>
<com.android.systemui.statusbar.policy.KeyButtonView xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/{name}"
    android:layout_width="0dp"
    android:layout_height="0dp"
    android:visibility="gone" />
'''


def run(*command):
    print('>', ' '.join(str(part) for part in command))
    subprocess.run([str(part) for part in command], check=True)


def view_ids(aapt2, systemui):
    table = subprocess.run([str(aapt2), 'dump', 'resources', str(systemui)], check=True, capture_output=True,
                           encoding='utf-8', errors='replace').stdout
    ids = {}
    for name in LAYOUTS:
        found = re.search(rf'resource (0x[0-9a-f]+) id/{name}\b(?![_.])', table)
        if not found:
            sys.exit(f'SystemUI has no id/{name}')
        ids[name] = found.group(1)
    return ids


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('systemui')
    parser.add_argument('framework')
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME') or
                        os.path.expanduser('~/AppData/Local/Android/Sdk'))
    args = parser.parse_args()
    tools = sorted((Path(args.sdk) / 'build-tools').iterdir())[-1]
    exe = '.exe' if os.name == 'nt' else ''
    bat = '.bat' if os.name == 'nt' else ''
    keystore = Path.home() / '.android' / 'debug.keystore'
    ids = view_ids(tools / f'aapt2{exe}', args.systemui)

    with tempfile.TemporaryDirectory() as temp:
        work = Path(temp)
        (work / 'res' / 'layout').mkdir(parents=True)
        for name in LAYOUTS:
            (work / 'res' / 'layout' / f'{name}.xml').write_text(layout(name), encoding='utf-8')
        # The views have to carry SystemUI's own ids, so this package's ids of the same names are given its numbers.
        stable = [f'{PACKAGE}:id/{name} = {number}' for name, number in ids.items()]
        (work / 'ids.txt').write_text('\n'.join(stable) + '\n', encoding='utf-8')
        (work / 'AndroidManifest.xml').write_text(MANIFEST, encoding='utf-8')
        run(tools / f'aapt2{exe}', 'compile', '--dir', work / 'res', '-o', work / 'res.zip')
        run(tools / f'aapt2{exe}', 'link', '-o', work / 'unaligned.apk', '--manifest', work / 'AndroidManifest.xml',
            '-I', args.framework, '--stable-ids', work / 'ids.txt', '--no-resource-removal', work / 'res.zip')
        run(tools / f'zipalign{exe}', '-f', '4', work / 'unaligned.apk', work / 'aligned.apk')
        run(tools / f'apksigner{bat}', 'sign', '--ks', keystore, '--ks-pass', 'pass:android',
            '--key-pass', 'pass:android', '--out', work / 'signed.apk', work / 'aligned.apk')
        run(tools / f'aapt2{exe}', 'dump', 'xmltree', '--file', 'res/layout/home.xml', work / 'signed.apk')
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(work / 'signed.apk', OUTPUT)
    print('wrote', OUTPUT, OUTPUT.stat().st_size, 'bytes')


if __name__ == '__main__':
    sys.exit(main())
