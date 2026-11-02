"""Materialize Actions secrets in RUNNER_TEMP, never in the checkout or Gradle cache."""
import base64
import json
import os
from pathlib import Path
import shutil
import sys


def prepare(environment):
    names = ('RUNNER_TEMP', 'ANDROID_KEYSTORE_BASE64', 'PLAY_SERVICE_ACCOUNT_JSON')
    missing = [name for name in names if not environment.get(name)]
    if missing:
        raise ValueError('Missing required publishing configuration: ' + ', '.join(missing))
    keystore = base64.b64decode(environment['ANDROID_KEYSTORE_BASE64'], validate=True)
    if not keystore:
        raise ValueError('The upload keystore is empty.')
    account = json.loads(environment['PLAY_SERVICE_ACCOUNT_JSON'])
    if account.get('type') != 'service_account' or not all(account.get(k) for k in ('client_email', 'private_key', 'token_uri')):
        raise ValueError('Play credentials must be a service-account JSON key.')
    directory = Path(environment['RUNNER_TEMP']) / 'londontrainer-play'
    directory.mkdir(mode=0o700)
    try:
        for name, payload in [('upload.jks', keystore), ('service-account.json', json.dumps(account).encode())]:
            descriptor = os.open(directory / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, 'wb') as out:
                out.write(payload)
    except Exception:
        shutil.rmtree(directory)
        raise
    return directory


if __name__ == '__main__':
    if sys.argv[1:] == ['--clean']:
        shutil.rmtree(Path(os.environ['RUNNER_TEMP']) / 'londontrainer-play', ignore_errors=True)
    else:
        try:
            prepare(os.environ)
        except (ValueError, OSError):
            # Malformed JSON/base64 exceptions may include their input. Never print secret values.
            raise SystemExit('Publishing credentials are missing or invalid; check the play-internal environment secrets.') from None
