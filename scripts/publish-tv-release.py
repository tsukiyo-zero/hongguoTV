#!/usr/bin/env python3
"""Validate and publish a prepared TV release to GitHub and GitCode (stdlib only)."""
import argparse
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import time
import sys
import urllib.error
import urllib.parse
import urllib.request

REPO = 'tsukiyo-zero/hongguoTV'
CONFIG = Path.home() / '.config/hongguotv/publish.json'


class Failure(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Never forward API credentials to another host.


def request(url, method='GET', data=None, headers=None, missing=False, raw=False, attempts=3):
    """带重试的请求：GitHub 在国内网络下会间歇性 SSL EOF / 5xx，一次失败不应中断整条发布。"""
    if urllib.parse.urlsplit(url).scheme != 'https':
        raise Failure('Refusing non-HTTPS request')
    for attempt in range(attempts):
        req = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
        try:
            with urllib.request.build_opener(NoRedirect).open(req, timeout=120) as response:
                body = response.read()
                return body if raw else (json.loads(body) if body else {})
        except urllib.error.HTTPError as error:
            if missing and error.code == 404:
                return None
            if error.code >= 500 and attempt + 1 < attempts:
                time.sleep(2 * (attempt + 1))
                continue
            # Do not print URLs, signed upload links, response bodies or tokens.
            raise Failure(f'HTTP {error.code}: request failed; check token permissions and release state') from None
        except (OSError, ValueError):
            if attempt + 1 < attempts:
                time.sleep(2 * (attempt + 1))
                continue
            raise Failure('Network request or JSON response failed; retry the command') from None


def configure():
    values = {name: getpass.getpass(f'{name} (hidden): ').strip()
              for name in ('GITHUB_TOKEN', 'GITCODE_TOKEN')}
    if not all(values.values()):
        raise Failure('Both tokens are required')
    CONFIG.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(CONFIG, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, 'w') as stream:
        json.dump(values, stream)
    print(f'Credentials saved locally with mode 0600: {CONFIG}')


def credentials():
    values = {}
    if CONFIG.exists():
        if CONFIG.is_symlink() or stat.S_IMODE(CONFIG.stat().st_mode) & 0o077:
            raise Failure('Credential file must be a regular private file (chmod 600)')
        values = json.loads(CONFIG.read_text())
    result = {name: os.environ.get(name) or values.get(name) for name in ('GITHUB_TOKEN', 'GITCODE_TOKEN')}
    if not all(result.values()):
        raise Failure('Run --configure in your terminal or set GITHUB_TOKEN and GITCODE_TOKEN')
    return result


def bundle(directory):
    manifest = json.loads((directory / 'update.json').read_text())
    tag = manifest['tag']
    if not re.fullmatch(r'kotlin-v\d+\.\d+\.\d+', tag):
        raise Failure('Only versioned TV tags are supported; mac-stable is excluded')
    name = manifest['apk']['name']
    if Path(name).name != name or not name.endswith('.apk'):
        raise Failure('Invalid APK filename')
    files = [directory / name, directory / 'release-notes.md', directory / 'update.json']
    if manifest['packageName'] != 'io.github.tsukiyo_zero.guoyingtv' or manifest['minSdk'] != 23:
        raise Failure('Unexpected package or minSdk')
    apk = files[0].read_bytes()
    if len(apk) != manifest['apk']['size'] or hashlib.sha256(apk).hexdigest() != manifest['apk']['sha256']:
        raise Failure('APK does not match update.json')
    notes = files[1].read_text().strip()
    if not notes or notes != manifest['notes'].strip():
        raise Failure('Update notes differ from update.json')
    return tag, notes, files


class Publisher:
    def __init__(self, platform, token, tag, notes):
        self.platform, self.token, self.tag, self.notes = platform, token, tag, notes
        self.base = ('https://api.github.com/repos/' if platform == 'github' else
                     'https://api.gitcode.com/api/v5/repos/') + REPO
        self.title = '果映 TV v' + tag.removeprefix('kotlin-v')

    def api(self, path, method='GET', value=None, missing=False):
        url = self.base + path
        headers = {'Accept': 'application/json', 'User-Agent': 'hongguotv-release'}
        if self.platform == 'github':
            headers['Authorization'] = 'Bearer ' + self.token
            headers['X-GitHub-Api-Version'] = '2022-11-28'
        else:
            url += ('&' if '?' in url else '?') + urllib.parse.urlencode({'access_token': self.token})
        data = None
        if value is not None:
            data = json.dumps(value).encode()
            headers['Content-Type'] = 'application/json'
        try:
            return request(url, method, data, headers, missing)
        except Failure as error:
            raise Failure(f"{self.platform}: {method} {path.split(chr(63))[0]}: {error}") from None

    def get(self):
        release = self.api('/releases/tags/' + self.tag, missing=True)
        if release is not None or self.platform != 'github':
            return release
        # GitHub's tag endpoint may omit drafts; find the matching authenticated draft.
        for page in range(1, 101):
            releases = self.api(f'/releases?per_page=100&page={page}')
            if not isinstance(releases, list):
                raise Failure('github: invalid release-list response')
            for candidate in releases:
                if candidate.get('tag_name') == self.tag:
                    return candidate
            if len(releases) < 100:
                break
        return None

    def preflight(self):
        release = self.get()
        if release is not None:
            return release
        if self.platform == 'github':
            exists = self.api('/git/ref/tags/' + self.tag, missing=True) is not None
        else:
            # GitCode documents a paginated tag list, not GET /tags/:tag.
            exists = False
            for page in range(1, 101):
                tags = self.api(f'/tags?per_page=100&page={page}')
                if not isinstance(tags, list):
                    raise Failure('gitcode: invalid tag-list response')
                if any(row.get('name') == self.tag for row in tags):
                    exists = True
                    break
                if len(tags) < 100:
                    break
        if not exists:
            raise Failure(f'{self.platform}: tag missing; push tag and wait for mirror sync')
        return None

    def stage(self, release=None):
        if release is None:
            value = dict(tag_name=self.tag, name=self.title, body=self.notes)
            value.update(dict(draft=True, prerelease=False) if self.platform == 'github' else
                         dict(release_status='pre'))
            release = self.api('/releases', 'POST', value)
        return release

    def upload(self, release, file):
        existing = next((a for a in release.get('assets', []) if a.get('name') == file.name), None)
        if existing:
            verify_asset(existing, file)
            print(f'{self.platform}: verified existing {file.name}')
            return
        data = file.read_bytes()
        if self.platform == 'github':
            url = release['upload_url'].split('{')[0]
            if urllib.parse.urlsplit(url).hostname != 'uploads.github.com':
                raise Failure('Unexpected GitHub upload host')
            request(url + '?' + urllib.parse.urlencode({'name': file.name}), 'POST', data,
                    {'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/octet-stream',
                     'User-Agent': 'hongguotv-release'})
        else:
            upload = self.api('/releases/' + self.tag + '/upload_url?' +
                              urllib.parse.urlencode({'file_name': file.name}))
            host = urllib.parse.urlsplit(upload['url']).hostname or ''
            if not (host.endswith('.myhuaweicloud.com') or host.endswith('.gitcode.com')):
                raise Failure('Unexpected GitCode storage host')
            request(upload['url'], 'PUT', data, upload['headers'], raw=True)
        print(f'{self.platform}: uploaded {file.name}')

    def verify(self, files):
        release = self.get()
        if release is None:
            raise Failure(f'{self.platform}: staged release not readable; retry later')
        for file in files:
            asset = next((a for a in release.get('assets', []) if a.get('name') == file.name), None)
            if asset is None:
                raise Failure(f'{self.platform}: attachment not listed: {file.name}; retry later')
            verify_asset(asset, file)

    def finish(self):
        if self.platform == 'github':
            self.api('/releases/' + str(self.get()['id']), 'PATCH',
                     dict(name=self.title, body=self.notes, draft=False, prerelease=False, make_latest='true'))
        else:
            self.api('/releases/' + self.tag, 'PATCH',
                     dict(name=self.title, body=self.notes, release_status='latest'))
        release = self.get()
        if release.get('draft') or release.get('prerelease'):
            raise Failure(f'{self.platform}: release is not stable')
        latest = self.api('/releases/latest' + ('?type=latest' if self.platform == 'gitcode' else ''))
        if latest.get('tag_name') != self.tag:
            raise Failure(f'{self.platform}: Latest verification failed')
        print(f'{self.platform}: published {self.tag} as Latest')


def verify_asset(asset, file):
    expected = hashlib.sha256(file.read_bytes()).hexdigest()
    digest = (asset.get('digest') or '').removeprefix('sha256:')
    if digest:
        if digest != expected or asset.get('size', file.stat().st_size) != file.stat().st_size:
            raise Failure(f'Existing attachment differs: {file.name}; no files were deleted')
        return
    # GitCode may omit digests; verify via anonymous public download.
    url = asset['browser_download_url']
    host = urllib.parse.urlsplit(url).hostname or ''
    if urllib.parse.urlsplit(url).scheme != 'https' or not (
        host in ('github.com', 'api.github.com', 'gitcode.com', 'api.gitcode.com') or
        host.endswith('.gitcode.com')):
        raise Failure('Unexpected download host')
    try:
        with urllib.request.urlopen(url, timeout=120) as response:
            digestor = hashlib.sha256()
            count = 0
            while chunk := response.read(1024 * 1024):
                count += len(chunk)
                if count > file.stat().st_size:
                    raise Failure(f'Remote attachment differs: {file.name}')
                digestor.update(chunk)
        if count != file.stat().st_size or digestor.hexdigest() != expected:
            raise Failure(f'Remote attachment differs: {file.name}')
    except (urllib.error.URLError, OSError):
        raise Failure(f'Cannot verify download: {file.name}; retry later') from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--configure', action='store_true', help='Read hidden tokens into a private local file')
    parser.add_argument('--directory', type=Path, help='Prepared release directory containing three files')
    parser.add_argument('--publish', action='store_true', help='Upload to both platforms and mark Latest')
    args = parser.parse_args()
    if args.configure:
        configure()
        return
    if not args.directory:
        parser.error('--directory is required')
    tag, notes, files = bundle(args.directory)
    print(f'Validated {tag}: ' + ', '.join(file.name for file in files))
    if not args.publish:
        print('Local check only. Add --publish to upload; no credentials loaded or network writes performed.')
        return
    tokens = credentials()
    publishers = [Publisher(p, tokens[p.upper() + '_TOKEN'], tag, notes) for p in ('github', 'gitcode')]
    # Both platforms must pass read-only checks before the first mutation.
    checked = [(p, p.preflight()) for p in publishers]
    staged = [(p, p.stage(release)) for p, release in checked]
    for publisher, release in staged:
        for file in files:  # Manifest last.
            publisher.upload(release, file)
        publisher.verify(files)
    for publisher in publishers:
        publisher.finish()
    print('Both platforms verified. Publishing is not atomic; rerun safely after partial failure.')


if __name__ == '__main__':
    try:
        main()
    except (Failure, KeyError, ValueError, OSError) as error:
        # Unexpected JSON/config errors must not dump potentially sensitive content.
        print(str(error) if isinstance(error, Failure) else 'Invalid local files or API response; check configuration.', file=sys.stderr)
        sys.exit(1)
