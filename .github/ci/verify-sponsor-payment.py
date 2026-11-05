#!/usr/bin/env python3
"""Credential-free release compilation and selected JVM tests, never APK deployment."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / '.github/ci/sponsor-payment-tests.json'
INIT = ROOT / '.github/ci/sponsor-payment-verification.init.gradle'


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args], text=True).strip()


def sources():
    paths = subprocess.check_output(['git', '-C', str(ROOT), 'ls-files', '-z']).decode().split('\0')
    return {name: digest(ROOT / name) for name in paths if name and (ROOT / name).is_file()}


def require_no_credentials():
    variables = ('SIGNING_STORE_FILE', 'SIGNING_STORE_PASSWORD', 'SIGNING_KEY_PASSWORD',
                 'SIGNING_KEY_ALIAS', 'KEYSTORE_FILE', 'KEYSTORE_PASSWORD',
                 'BAIDU_MAP_ANDROID_AK', 'AMAP_ANDROID_KEY', 'GOOGLE_SERVICE_ACCOUNT_KEY',
                 'SUPPLY_JSON_KEY_DATA')
    present = [key for key in variables if os.environ.get(key)]
    if present:
        raise RuntimeError('Verification must not receive credential bindings: ' + ', '.join(present))
    if any((ROOT / name).exists() for name in ('local.properties', 'release.keystore', 'mastodon/release.keystore')):
        raise RuntimeError('Use a clean checkout without local configuration or release keystore')


def verify_tracked_inputs():
    expected = json.loads((ROOT / '.github/ci/sponsor-payment-inputs.json').read_text())
    for name, checksum in expected['tracked_files'].items():
        if digest(ROOT / name) != checksum:
            raise RuntimeError('Tracked binary or font changed: ' + name)
    return expected


def verify_resolved_dependencies(evidence, expected):
    rows = json.loads((evidence / 'resolved-dependencies.json').read_text())
    for coordinate, checksum in expected['artifacts'].items():
        found = [row for row in rows if row['coordinate'] == coordinate]
        if not found or {row['sha256'] for row in found} != {checksum}:
            raise RuntimeError('Resolved dependency does not match the tested input: ' + coordinate)


def command(task, evidence):
    return [str(ROOT / 'gradlew'), '--no-daemon', '--max-workers=2', '--console=plain',
            '--no-watch-fs', '--build-cache', '--stacktrace',
            '-Dorg.gradle.java.home=' + os.environ['JAVA_HOME'],
            '-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=768m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8',
            '-Pkotlin.compiler.execution.strategy=in-process', '-PallowMissingMapKeys=true',
            '-I', str(INIT), '-I', str(ROOT / '.github/ci/sponsor-resource-cache.init.gradle'), task]


def run(stage):
    require_no_credentials()
    evidence = Path(os.environ['SPONSOR_CI_EVIDENCE_DIR']).resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    classes = json.loads(CONFIG.read_text())['classes']
    assert classes and len(classes) == len(set(classes))
    for name in classes:
        assert re.fullmatch(r'org\.joinmastodon\.[A-Za-z0-9_.]+Test', name), name
        assert any((ROOT / 'mastodon/src/test' / language / (name.replace('.', '/') + extension)).is_file()
                   for language, extension in (('java', '.java'), ('kotlin', '.kt'))), name
    head = git('rev-parse', 'HEAD')
    if os.environ.get('GITHUB_SHA') and head != os.environ['GITHUB_SHA']:
        raise RuntimeError('Checkout must match the exact workflow commit')
    expected_inputs = verify_tracked_inputs()
    before = sources()
    save(evidence / (stage + '-source-before.json'), {'head': head, 'files': before})
    task = ':mastodon:mergeReleaseResources' if stage == 'resources' else ':mastodon:testReleaseUnitTest'
    args = command(task, evidence)
    if stage == 'tests':
        for name in classes:
            args += ['--tests', name]
    started = time.time()
    if stage == 'resources':
        dependency_run = subprocess.run(command(':mastodon:recordSponsorVerificationDependencies', evidence),
                                        cwd=ROOT, stdin=subprocess.DEVNULL)
        if dependency_run.returncode:
            save(evidence / 'resources-report.json', {'head': head, 'resource_task_passed': False,
                 'dependency_resolution_exit': dependency_run.returncode, 'full_android_gate': False})
            return 1
    verify_resolved_dependencies(evidence, expected_inputs)
    process = subprocess.run(args, cwd=ROOT, stdin=subprocess.DEVNULL)
    after = sources()
    changed = sorted(name for name in before.keys() | after.keys() if before.get(name) != after.get(name))
    report = {'head': head, 'stage': stage, 'gradle_exit': process.returncode,
              'source_changes': changed, 'duration_seconds': round(time.time() - started, 2),
              'full_android_gate': False, 'signed_apk_created': False,
              'production_credentials_used': False}
    passed = process.returncode == 0 and not changed
    if stage == 'resources':
        report['resource_task_passed'] = passed
        if passed:
            cache = Path(os.environ['SPONSOR_CI_BUILD_CACHE']).resolve()
            entries = sorted(path for path in cache.iterdir()
                             if path.is_file() and re.fullmatch(r'[0-9a-f]{32}', path.name))
            if not entries:
                raise RuntimeError('Successful resource run produced no transferable normal Gradle cache entries')
            with tarfile.open(evidence / 'gradle-resource-cache.tar.gz', 'w:gz') as archive:
                for path in entries:
                    archive.add(path, arcname=path.name, recursive=False)
            save(evidence / 'gradle-resource-cache-manifest.json', {
                'head': head, 'gradle': '8.11.1', 'agp': '8.9.1',
                'kind': 'Normal input-keyed Gradle build cache; not a completed APK or manual partial output overlay',
                'entries': {path.name: digest(path) for path in entries},
                'archive_sha256': digest(evidence / 'gradle-resource-cache.tar.gz')})
    else:
        suites = []
        for name in classes:
            path = ROOT / 'mastodon/build/test-results/testReleaseUnitTest' / ('TEST-' + name + '.xml')
            if not path.is_file() or path.stat().st_mtime < started:
                suites.append({'name': name, 'status': 'not_executed_or_stale'})
                passed = False
                continue
            tree = ET.fromstring(path.read_bytes())
            suite = {'name': name, 'status': 'fresh', **{
                key: int(tree.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}}
            if any(suite[key] for key in ('failures', 'errors', 'skipped')):
                passed = False
            suites.append(suite)
        expected_renders = ['sponsor-payment-' + prefix + locale + '-' + theme + '-320-font160.png'
                            for prefix in ('', 'disabled-') for locale in ('en', 'zh')
                            for theme in ('light', 'dark')]
        missing_renders = [name for name in expected_renders
                           if not (evidence / 'ui/payments' / name).is_file()]
        if missing_renders:
            passed = False
        report.update(scope=json.loads(CONFIG.read_text())['scope'], suites=suites,
                      missing_payment_ui_renders=missing_renders,
                      selected_client_runtime_gate_passed=passed,
                      visual_acceptance='Pending inspection of real native renders; fixture is not device acceptance')
        report['totals'] = {key: sum(s.get(key, 0) for s in suites)
                            for key in ('tests', 'failures', 'errors', 'skipped')}
    save(evidence / (stage + '-source-after.json'), {'head': head, 'files': after})
    save(evidence / (stage + '-report.json'), report)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if passed else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', choices=('resources', 'tests'))
    sys.exit(run(parser.parse_args().stage))
