#!/usr/bin/env python3
"""Builds the system overlay that makes a QF unit fade between apps.

The overlay replaces the framework's activity, task and wallpaper transition animations with plain fades, and gives the
task transitions of the default activity style, which the QF firmware leaves empty, those animations. It is shipped in the
app (assets) and installed from there as a Magisk module; see AppFadeOverlay.

Usage: build.py <framework-res.apk pulled from the unit> [--sdk <Android SDK>]
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

PACKAGE = 'com.andrerinas.openheadunit.appfade'
DURATION_MS = 250

HERE = Path(__file__).resolve().parent
OUTPUT = HERE.parent.parent / 'app' / 'src' / 'main' / 'assets' / 'qf_app_fade' / 'OhuAppFade.apk'

# Each animation is (from alpha, to alpha, interpolator). Within an app, the window that comes forward fades in over the
# one it replaces, which stays as it is until then; the window that leaves fades out over the one under it.
OPEN_ENTER = (0.0, 1.0, 'linear')
HOLD = (1.0, 1.0, 'linear')
CLOSE_EXIT = (1.0, 0.0, 'linear')
# Between apps both sides fade. Android draws the home screen under every other app, also while it is coming forward, so
# an app that held there would hide the whole fade and then vanish. The one coming in gains early and the one leaving
# goes late, which keeps the black behind them out of sight whichever of the two is on top.
CROSS_ENTER = (0.0, 1.0, 'decelerate_quad')
CROSS_EXIT = (1.0, 0.0, 'accelerate_quad')

ANIMATIONS = {}
for prefix in ('activity', 'task', 'wallpaper', 'wallpaper_intra'):
    ANIMATIONS[prefix + '_open_enter'] = OPEN_ENTER
    ANIMATIONS[prefix + '_open_exit'] = HOLD
    ANIMATIONS[prefix + '_close_enter'] = HOLD
    ANIMATIONS[prefix + '_close_exit'] = CLOSE_EXIT
for prefix in ('task', 'wallpaper'):
    ANIMATIONS[prefix + '_open_enter'] = CROSS_ENTER
    ANIMATIONS[prefix + '_open_exit'] = CROSS_EXIT

STYLE_ITEMS = {}
for kind, prefix in (('activityOpen', 'activity_open'), ('activityClose', 'activity_close'),
                     ('taskOpen', 'task_open'), ('taskClose', 'task_close'),
                     # A task brought to the front opens; one sent to the back closes.
                     ('taskToFront', 'task_open'), ('taskToBack', 'task_close'),
                     ('wallpaperOpen', 'wallpaper_open'), ('wallpaperClose', 'wallpaper_close'),
                     ('wallpaperIntraOpen', 'wallpaper_intra_open'), ('wallpaperIntraClose', 'wallpaper_intra_close')):
    STYLE_ITEMS[kind + 'EnterAnimation'] = prefix + '_enter'
    STYLE_ITEMS[kind + 'ExitAnimation'] = prefix + '_exit'

MANIFEST = f'''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="{PACKAGE}" android:versionCode="3" android:versionName="3">
    <uses-sdk android:minSdkVersion="28" android:targetSdkVersion="29" />
    <overlay android:targetPackage="android" android:isStatic="true" android:priority="999" />
    <application android:hasCode="false" android:label="OHU app fade" />
</manifest>
'''


# A style that is overlaid replaces the original whole, so the items the fade does not change are carried over.
KEPT_ITEMS = {
    f'fragment{kind}{side}Animation': f'animator/fragment_{name}_{side.lower()}'
    for kind, name in (('Open', 'open'), ('Close', 'close'), ('Fade', 'fade'))
    for side in ('Enter', 'Exit')
}
KEPT_ITEMS['launchTaskBehindTargetAnimation'] = 'anim/launch_task_behind_target'
KEPT_ITEMS['launchTaskBehindSourceAnimation'] = 'anim/launch_task_behind_source'


def anim(fade):
    start, end, interpolator = fade
    return f'''<?xml version="1.0" encoding="utf-8"?>
<alpha xmlns:android="http://schemas.android.com/apk/res/android"
    android:fromAlpha="{start}" android:toAlpha="{end}" android:duration="{DURATION_MS}"
    android:interpolator="@android:interpolator/{interpolator}" />
'''


def styles():
    items = '\n'.join(f'        <item name="android:{attr}">@*android:anim/{name}</item>'
                      for attr, name in STYLE_ITEMS.items())
    items += ''.join(f'\n        <item name="android:{attr}">@*android:{name}</item>'
                     for attr, name in KEPT_ITEMS.items())
    return f'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Animation.Activity" parent="@android:style/Animation">
{items}
    </style>
</resources>
'''


def run(*command):
    print('>', ' '.join(str(part) for part in command))
    subprocess.run([str(part) for part in command], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('framework')
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME') or
                        os.path.expanduser('~/AppData/Local/Android/Sdk'))
    args = parser.parse_args()
    tools = sorted((Path(args.sdk) / 'build-tools').iterdir())[-1]
    exe = '.exe' if os.name == 'nt' else ''
    bat = '.bat' if os.name == 'nt' else ''
    keystore = Path.home() / '.android' / 'debug.keystore'

    with tempfile.TemporaryDirectory() as temp:
        work = Path(temp)
        (work / 'res' / 'anim').mkdir(parents=True)
        (work / 'res' / 'values').mkdir()
        for name, fade in ANIMATIONS.items():
            (work / 'res' / 'anim' / f'{name}.xml').write_text(anim(fade), encoding='utf-8')
        (work / 'res' / 'values' / 'styles.xml').write_text(styles(), encoding='utf-8')
        (work / 'AndroidManifest.xml').write_text(MANIFEST, encoding='utf-8')
        run(tools / f'aapt2{exe}', 'compile', '--dir', work / 'res', '-o', work / 'res.zip')
        run(tools / f'aapt2{exe}', 'link', '-o', work / 'unaligned.apk', '--manifest', work / 'AndroidManifest.xml',
            '-I', args.framework, '--no-resource-removal', work / 'res.zip')
        run(tools / f'zipalign{exe}', '-f', '4', work / 'unaligned.apk', work / 'aligned.apk')
        run(tools / f'apksigner{bat}', 'sign', '--ks', keystore, '--ks-pass', 'pass:android',
            '--key-pass', 'pass:android', '--out', work / 'signed.apk', work / 'aligned.apk')
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(work / 'signed.apk', OUTPUT)
    print('wrote', OUTPUT, OUTPUT.stat().st_size, 'bytes')


if __name__ == '__main__':
    sys.exit(main())
