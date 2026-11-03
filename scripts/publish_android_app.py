"""Publish a project APK to the running Tethium local store (the host verifies and freezes it)."""
import argparse
import json
import os
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', required=True, type=Path)
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--title', default='')
    args = parser.parse_args()
    root, apk = args.project.resolve(strict=True), args.apk.resolve(strict=True)
    apk.relative_to(root)
    data = Path(os.environ.get('LOCALAPPDATA', Path.home())) / 'Tethium'
    host = json.loads((data / 'host.json').read_text(encoding='utf-8-sig'))
    def request(path, body=None):
        payload = None if body is None else json.dumps(body).encode('utf-8')
        req = Request(f"http://127.0.0.1:{host['port']}" + path, data=payload,
                      headers={'Authorization': 'Bearer ' + host['token'], 'Content-Type': 'application/json'})
        try:
            with urlopen(req, timeout=120) as response:
                return json.load(response)
        except HTTPError as error:
            raise RuntimeError(error.read().decode('utf-8', errors='replace')) from None
    projects = request('/state')['projects']
    project = next((p for p in projects if Path(p['root']).resolve() == root), None)
    if project is None:
        parser.error('Add this project to Tethium first')
    published = request('/projects/' + project['id'] + '/app-publish', {'path': str(apk), 'title': args.title})
    print(json.dumps(published, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
