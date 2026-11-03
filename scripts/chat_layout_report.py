"""Summarize the GUI flight recorder around a reported chat jump (local files only)."""
import argparse
from collections import Counter
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', help='Tethium session UUID')
    parser.add_argument('--minutes', type=float, default=10)
    parser.add_argument('--at', help='End of interval, ISO timestamp; omitted timezone means local time')
    parser.add_argument('--directory', type=Path, default=Path(os.environ.get('LOCALAPPDATA', str(Path.home()))) / 'Tethium/diagnostics/chat-layout')
    args = parser.parse_args()
    end = datetime.fromisoformat(args.at) if args.at else datetime.now().astimezone()
    if end.tzinfo is None:
        end = end.astimezone()
    start_ms, end_ms = (end - timedelta(minutes=args.minutes)).timestamp() * 1000, end.timestamp() * 1000
    counts, operations, replacements = Counter(), Counter(), Counter()
    recent, widths, sessions, builds = [], set(), set(), set()
    events = automatic = reversals = dropped = 0
    last_jump = {}
    for file in sorted(args.directory.glob('*.jsonl')):
        if file.stat().st_mtime * 1000 < start_ms:
            continue
        with file.open(encoding='utf-8') as stream:
            for line in stream:
                try:
                    item = json.loads(line)
                except (ValueError, UnicodeError):
                    continue  # The last line may still be flushing.
                when = item.get('time', 0)
                if not start_ms <= when <= end_ms:
                    continue
                if args.session and item.get('session') != args.session and item.get('event') != 'open':
                    continue
                kind = item.get('event', '')
                if kind == 'open':
                    builds.add(item.get('build', ''))
                events += 1
                counts[kind] += 1
                sessions.add(item.get('session', ''))
                widths.add(item.get('viewportW', 0))
                if kind == 'summary':
                    dropped += item.get('rateDropped', 0)
                if kind == 'history.page.replace':
                    for flag in ['rangeChanged', 'widthChanged', 'htmlChanged', 'forceTail']:
                        if item.get(flag):
                            replacements[flag] += 1
                if kind == 'scroll' and abs(item.get('dy', 0)) >= 8 and (item.get('inputAgeMs', -1) < 0 or item['inputAgeMs'] > 1000):
                    automatic += 1
                    operations[item.get('operation', '')] += 1
                    # File prefix identifies a GUI; segment changes must not break correlation.
                    owner = (file.name.rsplit('-', 1)[0], item.get('session'))
                    previous = last_jump.get(owner)
                    if previous and when - previous['time'] < 2000 and item['dy'] * previous['dy'] < 0:
                        reversals += 1
                    last_jump[owner] = item
                    recent.append({k: item.get(k) for k in ['time', 'session', 'event', 'dy', 'scrollY', 'scrollMax', 'extent', 'viewportW', 'viewportH', 'textY', 'textH', 'document', 'operation', 'operationAgeMs', 'inputAgeMs', 'stack']})
    print(json.dumps(dict(fromTime=datetime.fromtimestamp(start_ms / 1000, timezone.utc).isoformat(), toTime=end.isoformat(), events=events, sessions=sorted(sessions - {''}), builds=sorted(builds), eventCounts=counts, replacementReasons=replacements, viewportWidths=sorted(widths), movementsWithoutRecentMouseOrKeyboard=automatic, quickDirectionReversals=reversals, precedingOperations=operations, rateLimitedEvents=dropped, recentMovements=sorted(recent, key=lambda x: x['time'])[-60:], note='Movements without recent input are candidates, not proof of a bug; explicit navigation and tail-follow also move the viewport.'), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
