#!/usr/bin/env python3
"""One TV release workflow. Default is a read-only plan; --publish is explicit authorization."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
BUILD = 'kotlin-tv/app/build.gradle.kts'
VERSION_FILES = [BUILD, 'README.md', 'kotlin-tv/README.md', 'AGENTS.md', 'kotlin-tv/RELEASE_NOTES.md']
TOOLCHAIN = Path.home() / '.config/hongguotv/release.json'
GITCODE = 'https://gitcode.com/tsukiyo-zero/hongguoTV.git'


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


publisher = module('tv_publisher', 'publish-tv-release.py')
packager = module('tv_packager', 'prepare-kotlin-release.py')
Failure = publisher.Failure


def run(args, env=None, log=None):
    if log:
        with Path(log).open('w') as stream:
            result = subprocess.run(args, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT)
    else:
        result = subprocess.run(args, cwd=ROOT, env=env, text=True, capture_output=True)
    if result.returncode:
        # Neither subprocess environment nor API response bodies belong in error messages.
        raise Failure('Command failed: ' + str(args[0]) + (f'; inspect {log}' if log else '; check repository/access state'))
    return '' if log else result.stdout.rstrip('\r\n')


def git(*args):
    return run(['git', *args])


def semver(value):
    if not re.fullmatch(r'\d+\.\d+\.\d+', value) or value!='.'.join(map(str, map(int, value.split('.')))):
        raise Failure('Version must be three decimal numbers, for example 0.1.7')
    return tuple(map(int, value.split('.')))


def source_version(text):
    version = re.search(r'versionName\s*=.*?\?:\s*"([0-9.]+)"', text)
    code = re.search(r'versionCode\s*=.*?\?:\s*(\d+)', text)
    if not version or not code:
        raise Failure('Cannot identify Gradle version defaults')
    return version.group(1), int(code.group(1))


def mirror_asset_url(url):
    """GitHub 附件直链 → GitCode 同路径；其他域名返回 None。"""
    prefix = 'https://github.com/tsukiyo-zero/hongguoTV/releases/download/'
    if url.startswith(prefix):
        return 'https://gitcode.com/tsukiyo-zero/hongguoTV/releases/download/' + url[len(prefix):]
    return None


def read_public(url, limit=80*1024*1024, _from_mirror=False):
    host = urllib.parse.urlsplit(url).hostname or ''
    if urllib.parse.urlsplit(url).scheme != 'https' or not (
        host in ('github.com', 'api.github.com', 'gitcode.com', 'api.gitcode.com') or host.endswith('.gitcode.com')):
        raise Failure('Unexpected public asset host')
    # GitHub 的 release 附件直链偶发 504；先重试，再退回 GitCode 上同路径的同一份附件。
    # 两平台每次都上传同一套文件，镜像兜底不改变“清单与实物一致”的校验语义。
    attempts = 2
    for attempt in range(attempts):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'hongguotv-release'}), timeout=120) as response:
                data = response.read(limit+1)
            if len(data)>limit:
                raise Failure('Public asset exceeds size limit')
            return data
        except urllib.error.HTTPError as error:
            # 404 明确表示该平台没有这个附件（历史版本在 GitCode 上常见），不重试、不镜像。
            if error.code in (404, 410):
                raise Failure(f'HTTP {error.code}: asset missing') from None
            # 5xx 多为网关超时：先重试，仍失败再退回另一个平台的同路径附件。
            if attempt + 1 < attempts:
                time.sleep(2)
                continue
            mirror = None if _from_mirror else mirror_asset_url(url)
            if mirror:
                return read_public(mirror, limit, _from_mirror=True)
            raise Failure(f'HTTP {error.code}: asset download failed') from None
        except (OSError, ValueError):
            if attempt + 1 < attempts:
                time.sleep(2)
                continue
            mirror = None if _from_mirror else mirror_asset_url(url)
            if mirror:
                return read_public(mirror, limit, _from_mirror=True)
            raise Failure('Anonymous asset download failed; retry later') from None


def releases(api):
    result = []
    for page in range(1, 101):
        rows = api.api(f'/releases?per_page=100&page={page}')
        if not isinstance(rows, list):
            raise Failure('Invalid release inventory')
        result.extend(r for r in rows if re.fullmatch(r'kotlin-v\d+\.\d+\.\d+', r.get('tag_name', '')))
        if len(rows)<100:
            return result
    raise Failure('Release inventory too large')


def manifest(release):
    """读取一个已发布版本的清单；该平台缺附件时返回 None（历史版本在 GitCode 上常见）。"""
    asset = next((a for a in release.get('assets', []) if a.get('name')=='update.json'), None)
    if asset is not None:
        try:
            data = json.loads(read_public(asset['browser_download_url'], 128*1024))
            if (data['tag'] != release['tag_name'] or data['packageName'] not in ('com.hongguotv.nativeapp','io.github.tsukiyo_zero.guoyingtv')
                    or not re.fullmatch(re.escape(data['tag'].removeprefix('kotlin-v'))+r'(?:-[0-9A-Za-z.+-]+)?', data['versionName'])
                    or not isinstance(data['versionCode'], int) or not 1<=data['versionCode']<=2147483647):
                raise Failure('Published manifest identity/version mismatch')
            return data
        except Failure as error:
            if 'asset missing' not in str(error):
                raise
            # 附件被删或未同步：退回用 APK 元数据判断，避免整体发布被历史数据卡死。
    if True:
        # Early releases predate updater manifests. Read actual APK metadata instead of
        # guessing a code from the tag or silently ignoring a possibly higher versionCode.
        candidates = [a for a in release.get('assets', []) if a.get('name', '').endswith('.apk')]
        if not candidates or len(candidates)>5:
            raise Failure(f"Cannot verify historical APK metadata: {release['tag_name']}")
        _, build_tools = tools_env()
        found = []
        for apk in candidates:
            try:
                raw = read_public(apk['browser_download_url'])
            except Failure as error:
                if 'asset missing' in str(error):
                    continue
                raise
            with tempfile.TemporaryDirectory(prefix='hongguotv-release-inspect-') as temp:
                path = Path(temp)/'baseline.apk'
                path.write_bytes(raw)
                badging = run([str(build_tools/'aapt'), 'dump', 'badging', str(path)])
            line = next((line for line in badging.splitlines() if line.startswith('package:')), '')
            fields = dict(re.findall(r"(\w+)='([^']*)'", line))
            if fields.get('name') not in ('com.hongguotv.nativeapp','io.github.tsukiyo_zero.guoyingtv'):
                continue
            code = int(fields['versionCode'])
            if not 1<=code<=2147483647:
                raise Failure('Invalid historical APK versionCode')
            found.append(dict(tag=release['tag_name'], packageName=fields['name'], versionName=fields['versionName'],
                              versionCode=code, apk=dict(name=apk['name'], size=len(raw), sha256=hashlib.sha256(raw).hexdigest())))
        if not found:
            # 该平台已没有可读的 APK（附件被删或未同步）：跳过这个历史版本，
            # 版本规划用其他平台/更新的版本即可。
            return None
        return max(found, key=lambda m: m['versionCode'])



def select_version(source, published, occupied, override=None):
    highest = max([semver(source[0])] + [semver(m['tag'].removeprefix('kotlin-v')) for m in published])
    candidate = override or '.'.join(map(str, (*highest[:2], highest[2]+1)))
    if semver(candidate)<=highest:
        raise Failure('New version must exceed source and all published versions')
    code = max([source[1]] + [m['versionCode'] for m in published])+1
    if code>2147483647 or 'kotlin-v'+candidate in occupied:
        raise Failure('Version conflicts with an existing tag/draft; inspect or resume that release')
    return candidate, code


def inventory(apis, override=None):
    all_releases = {name: releases(api) for name, api in apis.items()}
    stable = [(name, r) for name, rows in all_releases.items() for r in rows
              if not r.get('draft') and not r.get('prerelease')]
    with ThreadPoolExecutor(max_workers=4) as pool:
        observed = [
            row for row in pool.map(lambda item: (item[0], item[1], manifest(item[1])), stable)
            if row[2] is not None
        ]
    if not observed:
        raise Failure('No published baseline; initial release requires manual preparation')
    occupied = {r['tag_name'] for rows in all_releases.values() for r in rows}
    for api in apis.values():
        for page in range(1, 101):
            tags = api.api(f'/tags?per_page=100&page={page}')
            occupied.update(t['name'] for t in tags)
            if len(tags)<100:
                break
        else:
            raise Failure('Tag inventory too large')
    latest = [api.api('/releases/latest'+('?type=latest' if name=='gitcode' else ''))['tag_name'] for name, api in apis.items()]
    if len(set(latest))!=1:
        raise Failure('Platforms have different Latest tags; resolve or resume the unfinished release first')
    source = source_version((ROOT/BUILD).read_text())
    version, code = select_version(source, [o[2] for o in observed], occupied, override)
    baseline = max(observed, key=lambda o: (o[2]['versionCode'], o[0]=='github'))
    return dict(version=version, code=code, tag='kotlin-v'+version, base=git('rev-parse', 'HEAD'),
                previous=source[0], previousPackage=baseline[2]['packageName'], baselinePlatform=baseline[0], baselineTag=baseline[1]['tag_name'])


def tools_env():
    env = os.environ.copy()
    if TOOLCHAIN.exists():
        if TOOLCHAIN.is_symlink() or TOOLCHAIN.stat().st_mode & 0o077:
            raise Failure('Local release.json must be private (chmod 600)')
        allowed = {'JAVA_HOME', 'ANDROID_HOME', 'GRADLE_USER_HOME', 'HONGGUOTV_GRADLE', 'HONGGUOTV_KEYSTORE',
                   'HONGGUOTV_KEY_ALIAS', 'HONGGUOTV_STORE_PASSWORD', 'HONGGUOTV_KEY_PASSWORD'}
        config = json.loads(TOOLCHAIN.read_text())
        if set(config)-allowed or not all(isinstance(v, str) for v in config.values()):
            raise Failure('Invalid local toolchain configuration')
        for key, value in config.items():
            env.setdefault(key, value)
    sdk = env.get('ANDROID_HOME', '')
    local = ROOT/'kotlin-tv/local.properties'
    if not sdk and local.exists():
        match = re.search(r'^sdk.dir=(.+)$', local.read_text(), re.M)
        if match:
            sdk = match.group(1)
            env['ANDROID_HOME'] = sdk
    candidates = sorted((p for p in (Path(sdk)/'build-tools').glob('*/aapt') if re.fullmatch(r'\d+\.\d+\.\d+', p.parent.name)),
                        key=lambda p: tuple(map(int, p.parent.name.split('.')))) if sdk else []
    if not candidates:
        raise Failure('Configure ANDROID_HOME (or local release.json) before publishing')
    return env, candidates[-1].parent


def notes_for(plan, notes_file=None):
    if notes_file:
        notes = Path(notes_file).read_text().strip()
    else:
        titles = git('log', '--no-merges', '--format=%s', plan['baselineTag']+'..'+plan['base'], '--', 'kotlin-tv/app', 'kotlin-tv/core').splitlines()
        changes = list(dict.fromkeys(re.sub(r'^(feat|fix)(\([^)]*\))?!?:\s*', '', t) for t in titles if re.match(r'^(feat|fix)(\([^)]*\))?!?:', t)))
        if not changes:
            raise Failure('No unreleased TV feature/fix commits; supply reviewed --notes-file if a release is intended')
        migration = '本版更名为果映 TV 并使用新包名，需单独安装，不能覆盖旧包名版本。本机数据及登录态不自动迁移，需重新登录获取账户内容；当前未提供图形界面的本机记录迁移入口。' if plan.get('previousPackage')=='com.hongguotv.nativeapp' else '保持新包名与原签名，同包名旧版可覆盖更新并保留数据。'
        notes = f"果映 TV {plan['version']} 更新说明\n\n"+'\n'.join('- '+t for t in changes)+'\n\n支持 Android 6.0 及以上；'+migration
    if not notes or len(notes)>1500:
        raise Failure('Release notes must contain 1..1500 characters')
    return notes


def save(path, state):
    temp = path.with_suffix('.tmp')
    temp.write_text(json.dumps(state, ensure_ascii=False, indent=2)+'\n')
    temp.replace(path)


def prepare_commit(state, checkpoint):
    branch = 'feature-release-'+state['version']
    branch_exists = subprocess.run(['git', 'show-ref', '--verify', '--quiet', 'refs/heads/'+branch], cwd=ROOT).returncode==0
    if not branch_exists:
        git('switch', '-c', branch, state['base'])
    else:
        git('switch', branch)
    if git('rev-parse', 'HEAD')!=state['base']:
        if source_version((ROOT/BUILD).read_text())!=(state['version'], state['code']) or git('rev-parse', 'HEAD^')!=state['base'] or git('status', '--porcelain'):
            raise Failure('Release branch differs from recorded plan; no files overwritten')
        state['prepared'] = git('rev-parse', 'HEAD')
        save(checkpoint, state)
        return
    rendered = {}
    for name in VERSION_FILES:
        old = git('show', state['base']+':'+name)+'\n'
        if name==BUILD:
            new = re.sub(r'(versionName\s*=.*?\?:\s*")[0-9.]+(")', lambda m: m[1]+state['version']+m[2], old)
            new = re.sub(r'(versionCode\s*=.*?\?:\s*)\d+', lambda m: m[1]+str(state['code']), new)
        elif name.endswith('RELEASE_NOTES.md'):
            new = state['notes'].strip()+'\n'
        else:
            new = old.replace(state['previous'], state['version'])
            old_code = source_version(git('show', state['base']+':'+BUILD))[1]
            new = new.replace('versionCode '+str(old_code), 'versionCode '+str(state['code']))
        rendered[name] = new
    dirty = git('status', '--porcelain', '--untracked-files=normal')
    # A partially written preparation can resume only when every dirty file exactly matches our output.
    for line in dirty.splitlines():
        name = line[3:]
        if name not in rendered or (ROOT/name).read_text()!=rendered[name]:
            raise Failure('Uncommitted changes differ from release preparation; preserve and inspect them first')
    for name, value in rendered.items():
        (ROOT/name).write_text(value)
    git('add', '--', *VERSION_FILES)
    git('commit', '-m', 'chore: 准备 TV '+state['version']+' 正式发布', '-m', '自动递增版本与内部版本号，更新发布说明和正式版本文档；保持包名、最低系统与签名连续性。')
    state['prepared'] = git('rev-parse', 'HEAD')
    save(checkpoint, state)


def certificate(apk, build_tools, env):
    output = run([str(build_tools/'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], env)
    certificates = sorted(re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)', output))
    if not certificates:
        raise Failure('APK certificate missing')
    return certificates, 'Verified using v1 scheme (JAR signing): true' in output


def build(state, checkpoint, apis):
    env, build_tools = tools_env()
    directory = checkpoint.parent
    if not state.get('built'):
        if git('rev-parse', 'HEAD')!=state['prepared']:
            raise Failure('Build must use the recorded preparation commit')
        env.update(HONGGUOTV_VERSION_NAME=state['version'], HONGGUOTV_VERSION_CODE=str(state['code']))
        print('构建与自动检查（一次）…', flush=True)
        run([env.get('HONGGUOTV_GRADLE', str(ROOT/'kotlin-tv/gradlew')), '-p', 'kotlin-tv', ':core:test',
             ':app:testDebugUnitTest', ':app:assembleDebug', ':app:lintDebug', ':app:assembleRelease', '--console=plain'], env, directory/'build.log')
        release = packager.prepare(ROOT/'kotlin-tv/app/build/outputs/apk/release/app-release.apk', build_tools/'aapt',
                                   ROOT/'outputs/releases', state['tag'], state['notes'])
        state['directory'] = str(release.relative_to(ROOT))
    release = ROOT/state['directory']
    _, _, files = publisher.bundle(release)
    current = json.loads((release/'update.json').read_text())
    if (current['versionName'], current['versionCode'])!=(state['version'], state['code']):
        raise Failure('Built APK metadata differs from plan')
    hashes = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
    if state.get('hashes') and state['hashes']!=hashes:
        raise Failure('Prepared release files changed; refusing to resume')
    baseline = directory/'baseline.apk'
    if not baseline.exists() or not state.get('baselineHash'):
        prior = apis[state['baselinePlatform']].api('/releases/tags/'+state['baselineTag'])
        previous = manifest(prior)
        if baseline.exists():
            raw = baseline.read_bytes()
        else:
            asset = next(a for a in prior['assets'] if a['name']==previous['apk']['name'])
            raw = read_public(asset['browser_download_url'])
        if len(raw)!=previous['apk']['size'] or hashlib.sha256(raw).hexdigest()!=previous['apk']['sha256']:
            raise Failure('Published signing baseline failed digest verification')
        temporary = baseline.with_suffix('.tmp')
        temporary.write_bytes(raw)
        temporary.replace(baseline)
        state['baselineHash'] = hashlib.sha256(raw).hexdigest()
        save(checkpoint, state)
    if hashlib.sha256(baseline.read_bytes()).hexdigest()!=state.get('baselineHash'):
        raise Failure('Cached signing baseline changed; inspect before resuming')
    cert, v1 = certificate(files[0], build_tools, env)
    if not v1 or cert!=certificate(baseline, build_tools, env)[0]:
        raise Failure('Signing continuity or Android 6 v1 verification failed')
    state.update(built=True, hashes=hashes, certificates=cert)
    save(checkpoint, state)


def merge(state, checkpoint, github):
    if not state.get('pr'):
        prs = github.api('/pulls?state=all&head=tsukiyo-zero:feature-release-'+state['version'])
        pr = next((p for p in prs if p['head']['sha']==state['prepared']), None)
        if pr is None:
            git('push', '-u', 'origin', 'feature-release-'+state['version'])
            pr = github.api('/pulls', 'POST', dict(title='chore: 准备 TV '+state['version']+' 正式发布',
                head='feature-release-'+state['version'], base='main', body=state['notes']+'\n\n统一脚本：完整检查与原签名校验通过；维护者已通过 --publish 授权正式发布。'))
        state['pr'], state['prUrl'] = pr['number'], pr['html_url']
        save(checkpoint, state)
        print('发布准备 PR：'+state['prUrl'], flush=True)
    pr = github.api('/pulls/'+str(state['pr']))
    if not pr.get('merged'):
        if pr['state']!='open' or github.api('/git/ref/heads/main')['object']['sha']!=state['base']:
            raise Failure('PR closed or main advanced after checks; inspect before merging')
        result = github.api('/pulls/'+str(state['pr'])+'/merge', 'PUT', dict(sha=state['prepared'], merge_method='merge',
            commit_title='chore: 合入 TV '+state['version']+' 正式发布准备', commit_message='统一脚本完成构建测试与签名连续性校验，按维护者 --publish 授权发布。'))
        if not result.get('merged'):
            raise Failure('Release preparation PR did not merge')
        state['source'] = result['sha']
    else:
        state['source'] = pr['merge_commit_sha']
    git('fetch', 'origin', 'main')
    if git('rev-parse', state['source']+'^{tree}')!=git('rev-parse', state['prepared']+'^{tree}'):
        raise Failure('Merged source differs from verified build; no tag created')
    save(checkpoint, state)


def sync_tags(state, tokens):
    exists = subprocess.run(['git', 'show-ref', '--verify', '--quiet', 'refs/tags/'+state['tag']], cwd=ROOT).returncode==0
    if exists:
        if git('rev-parse', state['tag']+'^{}')!=state['source']:
            raise Failure('Local tag points elsewhere; no tags overwritten')
    else:
        git('tag', '-a', state['tag'], state['source'], '-m', 'chore: 发布 TV '+state['version'])
    git('push', 'origin', 'refs/tags/'+state['tag'])
    with tempfile.TemporaryDirectory(prefix='hongguotv-git-auth-') as temp:
        askpass = Path(temp)/'askpass'
        askpass.write_text('#!/usr/bin/env python3\nimport os,sys\nprint("tsukiyo-zero" if "username" in sys.argv[1].lower() else os.environ["HONGGUOTV_PUSH_TOKEN"])\n')
        askpass.chmod(0o700)
        env = dict(os.environ, GIT_ASKPASS=str(askpass), GIT_TERMINAL_PROMPT='0', HONGGUOTV_PUSH_TOKEN=tokens['GITCODE_TOKEN'])
        run(['git', '-c', 'credential.helper=', 'push', GITCODE, state['source']+':refs/heads/main', 'refs/tags/'+state['tag']], env)
    for url in ('git@github.com:tsukiyo-zero/hongguoTV.git', GITCODE):
        refs = dict(line.split()[::-1] for line in git('ls-remote', url, 'refs/tags/'+state['tag'], 'refs/tags/'+state['tag']+'^{}').splitlines())
        if refs.get('refs/tags/'+state['tag']+'^{}', refs.get('refs/tags/'+state['tag']))!=state['source']:
            raise Failure('Remote tag source verification failed')


def publish(state, checkpoint, tokens):
    apis = {name: publisher.Publisher(name, tokens[name.upper()+'_TOKEN'], state['tag'], state['notes']) for name in ('github', 'gitcode')}
    _, _, files = publisher.bundle(ROOT/state['directory'])
    checked = [(p, p.preflight()) for p in apis.values()]
    if not state.get('stagingStarted'):
        if any(release is not None for _, release in checked):
            raise Failure('A release/draft appeared after planning; inspect it before resuming, no existing release changed')
        state['stagingStarted'] = True
        save(checkpoint, state)
    staged = [(p, p.stage(release)) for p, release in checked]
    for api, release in staged:
        for file in files:
            api.upload(release, file)
        api.verify(files)
    for api in apis.values():
        api.finish()
        release = api.get()
        public = manifest(release)
        if public!=json.loads((ROOT/state['directory']/'update.json').read_text()):
            raise Failure('Published public manifest differs from local bundle')
    state['complete'] = True
    save(checkpoint, state)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--publish', action='store_true')
    parser.add_argument('--version', help='Optional explicit semantic version; default increments patch')
    parser.add_argument('--notes-file', type=Path)
    parser.add_argument('--resume', metavar='VERSION', help='Resume the recorded version, never auto-select another')
    args = parser.parse_args()
    if args.resume and not args.publish:
        raise Failure('--resume requires --publish')
    tokens = publisher.credentials()
    apis = {name: publisher.Publisher(name, tokens[name.upper()+'_TOKEN'], '', '') for name in ('github', 'gitcode')}
    if args.resume:
        semver(args.resume)
        checkpoint = ROOT/'outputs/release-jobs'/args.resume/'state.json'
        state = json.loads(checkpoint.read_text())
        if state['version']!=args.resume or (args.version and args.version!=args.resume):
            raise Failure('Resume version differs from recorded plan')
        if state.get('complete'):
            print('该发布任务已完成；不会重复创建版本：'+state['tag'])
            return
        if git('status', '--porcelain') and state.get('prepared'):
            raise Failure('Preserve uncommitted changes before resuming')
    else:
        pending = [p.parent.name for p in (ROOT/'outputs/release-jobs').glob('*/state.json')
                   if not json.loads(p.read_text()).get('complete')]
        if pending:
            raise Failure('Unfinished release job(s): '+', '.join(sorted(pending))+'; use --publish --resume VERSION, do not allocate another version')
        if args.publish:
            if git('branch', '--show-current')!='main' or git('status', '--porcelain'):
                raise Failure('Publish must start on clean main; no changes stashed or discarded')
            print('快进同步 main…', flush=True)
            git('fetch', 'origin', 'main')
            git('merge', '--ff-only', 'origin/main')
        state = inventory(apis, args.version)
        state['notes'] = notes_for(state, args.notes_file)
        checkpoint = ROOT/'outputs/release-jobs'/state['version']/'state.json'
        print(f"准备 TV {state['version']} / versionCode {state['code']}，基于 {state['base'][:12]}", flush=True)
        print(state['notes'], flush=True)
        if not args.publish:
            print('只读预演结束：未改版本、未提交、未构建、未创建标签或发行版。')
            return
        if checkpoint.exists():
            raise Failure('Recorded job already exists; inspect and use --resume '+state['version'])
        checkpoint.parent.mkdir(parents=True, exist_ok=True)
        save(checkpoint, state)
    if not state.get('prepared'):
        prepare_commit(state, checkpoint)
    if not state.get('source'):
        if git('rev-parse', 'HEAD')!=state['prepared']:
            git('switch', 'feature-release-'+state['version'])
        build(state, checkpoint, apis)
        merge(state, checkpoint, apis['github'])
    else:
        build(state, checkpoint, apis)
    sync_tags(state, tokens)
    publish(state, checkpoint, tokens)
    git('switch', 'main')
    git('merge', '--ff-only', state['source'])
    print('双平台发布与 Latest 校验完成：'+state['tag']+'；记录：'+str(checkpoint))


if __name__=='__main__':
    try:
        main()
    except (Failure, OSError, ValueError, KeyError, StopIteration):
        # Failure messages are constructed locally; unexpected data/config exceptions stay private.
        error = sys.exc_info()[1]
        print(str(error) if isinstance(error, Failure) else 'Invalid local/API state; inspect the release job and configuration.', file=sys.stderr)
        sys.exit(1)
