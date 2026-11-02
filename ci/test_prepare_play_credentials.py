import base64
import importlib.util
import json
from pathlib import Path
import stat
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('credentials', Path(__file__).with_name('prepare_play_credentials.py'))
credentials = importlib.util.module_from_spec(spec)
spec.loader.exec_module(credentials)


class CredentialTest(unittest.TestCase):
    def environment(self, directory):
        return {'RUNNER_TEMP': directory, 'ANDROID_KEYSTORE_BASE64': base64.b64encode(b'fixture-key').decode(),
                'PLAY_SERVICE_ACCOUNT_JSON': json.dumps({'type': 'service_account', 'client_email': 'test@example.invalid',
                                                       'private_key': 'fixture', 'token_uri': 'https://example.invalid/token'})}

    def test_files_are_private_and_stay_outside_the_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            out = credentials.prepare(self.environment(directory))
            self.assertEqual(b'fixture-key', (out / 'upload.jks').read_bytes())
            for file in out.iterdir(): self.assertEqual(0o600, stat.S_IMODE(file.stat().st_mode))
            self.assertEqual(0o700, stat.S_IMODE(out.stat().st_mode))

    def test_missing_secrets_fail_before_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            environment = self.environment(directory); del environment['PLAY_SERVICE_ACCOUNT_JSON']
            with self.assertRaises(ValueError): credentials.prepare(environment)
            self.assertEqual([], list(Path(directory).iterdir()))

    def test_invalid_account_fails_before_writing_the_keystore(self):
        with tempfile.TemporaryDirectory() as directory:
            environment = self.environment(directory); environment['PLAY_SERVICE_ACCOUNT_JSON'] = '{}'
            with self.assertRaises(ValueError): credentials.prepare(environment)
            self.assertEqual([], list(Path(directory).iterdir()))

    def test_invalid_base64_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            environment = self.environment(directory); environment['ANDROID_KEYSTORE_BASE64'] = 'not valid base64'
            with self.assertRaises(ValueError): credentials.prepare(environment)
            self.assertEqual([], list(Path(directory).iterdir()))
