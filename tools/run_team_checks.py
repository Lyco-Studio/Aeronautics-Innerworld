#!/usr/bin/env python3
"""Run two native Gradle clients with separate project caches."""
import argparse
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]


def run(world):
    (ROOT / 'build').mkdir(exist_ok=True)
    control = ROOT / 'run/team-control'
    if control.is_dir():
        shutil.rmtree(control)
    control.mkdir(parents=True)
    guest_options = ROOT / 'run/team-guest/options.txt'
    if not guest_options.is_file():
        guest_options.parent.mkdir(parents=True, exist_ok=True)
        source = ROOT / 'run/client/options.txt'
        if source.is_file():
            shutil.copyfile(source, guest_options)
    shared = ['classes', 'integrationTestClasses', 'createMinecraftArtifacts', 'extractEmbeddedModDependencies',
              'generateModMetadata', 'processResources', 'compileJava', 'compileIntegrationTestJava',
              'generateIntegrationWorldPreset', 'processIntegrationTestResources', 'downloadAssets']
    host_args = ['./gradlew', '--offline', f'-PcheckWorld={world}', 'runTeamHostClient']
    guest_args = ['./gradlew', '--offline', '--no-daemon', '-Dorg.gradle.jvmargs=-Xmx512m',
                  '--project-cache-dir', str(ROOT / 'run/team-guest/gradle-cache'), 'runTeamGuestClient']
    for task in shared:
        guest_args.extend(['-x', task])
    clients = []
    deadline = time.monotonic() + 180
    try:
        with (ROOT / 'build/team-host.log').open('w') as host_log, (ROOT / 'build/team-guest.log').open('w') as guest_log:
            host = subprocess.Popen(host_args, cwd=ROOT, stdout=host_log, stderr=subprocess.STDOUT, start_new_session=True)
            clients.append(host)
            while not (control / 'ready').is_file() and host.poll() is None and time.monotonic() < deadline:
                time.sleep(.25)
            if not (control / 'ready').is_file():
                raise RuntimeError('Host startup failed; inspect build/team-host.log')
            clients.append(subprocess.Popen(guest_args, cwd=ROOT, stdout=guest_log, stderr=subprocess.STDOUT, start_new_session=True))
            while any(client.poll() is None for client in clients) and time.monotonic() < deadline:
                if any(client.poll() not in (None, 0) for client in clients):
                    raise RuntimeError('A client failed; inspect build/team-host.log and build/team-guest.log')
                time.sleep(.25)
            if any(client.poll() != 0 for client in clients):
                raise RuntimeError('Client timeout or failure; inspect the team logs')
            if not all((control / name).is_file() and (control / name).read_text() == 'verified'
                       for name in ('host.result', 'guest.result')):
                raise RuntimeError('Team results are incomplete')
            print('Two real clients verified cooperation, shared victory and safe supply transit.')
    finally:
        active = any(client.poll() is None for client in clients)
        for client in clients:
            if client.poll() is None:
                os.killpg(client.pid, signal.SIGTERM)
        if active:
            for name in ('teamHostClient', 'teamGuestClient'):
                pattern = re.escape(str(ROOT / f'build/moddev/{name}RunVmArgs.txt'))
                subprocess.run(['pkill', '-f', pattern], check=False)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--world', default='RuntimeBaseline')
    run(parser.parse_args().world)
