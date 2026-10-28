#!/usr/bin/env python3
"""Send one JSON action to the optional development-client agent bridge."""
import argparse
import json
from pathlib import Path
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
DIRECTORY = ROOT / 'run/client/agent'

def call(action, timeout=25):
    DIRECTORY.mkdir(parents=True, exist_ok=True)
    if (DIRECTORY / 'request.json').exists():
        raise RuntimeError('An earlier request is still pending')
    request = dict(action, id=uuid.uuid4().hex)
    temporary = DIRECTORY / 'request.tmp'
    temporary.write_text(json.dumps(request, ensure_ascii=False))
    temporary.replace(DIRECTORY / 'request.json')
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = DIRECTORY / 'response.json'
        if result.exists():
            reply = json.loads(result.read_text())
            if reply['id'] == request['id']:
                if not reply['ok']:
                    raise RuntimeError(reply['result'])
                return reply['result']
        time.sleep(.1)
    raise TimeoutError('Client did not answer; inspect the client window and log')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', nargs='?', default='state', help='Action JSON or state')
    parser.add_argument('--file', type=Path, help='Read action JSON from a file')
    args = parser.parse_args()
    try:
        action = json.loads(args.file.read_text()) if args.file else ({'action': 'state'} if args.action == 'state' else json.loads(args.action))
        print(json.dumps(call(action), ensure_ascii=False, indent=2))
    except (ValueError, RuntimeError, TimeoutError) as error:
        sys.exit(str(error))
