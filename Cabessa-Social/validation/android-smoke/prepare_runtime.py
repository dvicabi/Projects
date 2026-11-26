#!/usr/bin/env python3
"""Provision pinned official emulator inputs without sdkmanager's remote resolver.

Only a task-specific directory is written. Compiler platforms/build-tools are
linked from the existing SDK. The runtime's archives were first checked against
Google's metadata SHA-1 values, then pinned with SHA-256 in runtime-lock.json.
"""
import concurrent.futures
import hashlib
import json
import subprocess
import sys
from pathlib import Path

root = Path(sys.argv[1]).resolve()
compiler_sdk = Path(sys.argv[2]).resolve()
lock = json.loads(Path(__file__).with_name('runtime-lock.json').read_text())
(root / 'downloads').mkdir(parents=True, exist_ok=True)


def install(record):
    archive = root / 'downloads' / record['name']
    subprocess.run(['curl', '--fail', '--location', '--retry', '2', '--connect-timeout', '20',
                    '--max-time', '480', '--output', str(archive), record['url']], check=True)
    with archive.open('rb') as stream:
        if hashlib.file_digest(stream, 'sha256').hexdigest() != record['sha256']:
            raise ValueError('Runtime archive checksum mismatch: ' + record['name'])
    destination = root / record['directory']
    destination.mkdir(parents=True, exist_ok=True)
    subprocess.run(['unzip', '-q', '-o', str(archive), '-d', str(destination)], check=True)
    print('Verified and installed ' + record['name'], flush=True)


with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    list(pool.map(install, lock['archives']))
sdk = root / 'sdk'
for name in ('platforms', 'build-tools'):
    destination = sdk / name
    if not destination.exists():
        destination.symlink_to(compiler_sdk / name, target_is_directory=True)
avd = root / 'avd' / 'CabessaApi35.avd'
avd.mkdir(parents=True, exist_ok=True)
(root / 'avd' / 'CabessaApi35.ini').write_text(f'avd.ini.encoding=UTF-8\npath={avd}\ntarget=android-35\n')
(avd / 'config.ini').write_text(f'''AvdId=CabessaApi35
PlayStore.enabled=false
abi.type=x86_64
avd.ini.displayname=Cabessa Android framework verification
avd.ini.encoding=UTF-8
disk.dataPartition.size=2048M
fastboot.forceColdBoot=yes
hw.audioInput=no
hw.camera.back=none
hw.camera.front=none
hw.cpu.arch=x86_64
hw.cpu.ncore=2
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader
hw.initialOrientation=Portrait
hw.keyboard=yes
hw.lcd.density=320
hw.lcd.height=1280
hw.lcd.width=720
hw.mainKeys=no
hw.ramSize=2048
hw.sdCard=no
image.sysdir.1={sdk}/system-images/android-35/google_apis/x86_64/
runtime.network.latency=none
runtime.network.speed=full
showDeviceFrame=no
tag.display=Google APIs
tag.id=google_apis
vm.heapSize=256
''')
print('Runtime SDK: ' + str(sdk))
