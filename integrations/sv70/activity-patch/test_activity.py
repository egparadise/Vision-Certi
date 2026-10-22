import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

from certificate_broker.activity import ActivityOutbox
from certificate_broker.client_store import ClientStore


class ActivityTests(unittest.TestCase):
    def test_offline_queue_survives_restart_and_retries_same_id_without_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ClientStore(directory, b't' * 32)
            client = Mock(base_url='https://cert.example', client_id='pacs')
            client.request.side_effect = OSError('offline')
            outbox = ActivityOutbox(store, lambda: client)
            receipt = {'authority': 'https://cert.example', 'serverResponse': {'verificationId': 'proof-id', 'audience': 'pacs'}}
            user = {'user_id': 'reader', 'user_idx': 42, 'password': 'must-not-save', 'token': 'must-not-save'}
            outbox.record(receipt, user, 'LOGIN_SUCCEEDED')
            first = outbox.read()['pending'][0]['body']
            outbox.flush()
            reopened = ActivityOutbox(store, lambda: client)
            self.assertEqual(reopened.read()['pending'][0]['body'], first)
            self.assertNotIn('must-not-save', reopened.path.read_text())
            client.request.side_effect = None
            client.request.return_value = {'recorded': True, 'duplicate': True}
            reopened.flush()
            self.assertEqual(reopened.read()['pending'], [])
            self.assertEqual(client.request.call_args.args[2]['eventId'], first['eventId'])
            reopened.record(receipt, user, 'APPROVAL_REQUESTED', ['https://pacs.example', 12, 7])
            queued = reopened.read()['pending'][0]['body']
            self.assertEqual(queued['clientInfo']['deviceId'], first['clientInfo']['deviceId'])
            self.assertEqual(len(queued['reportKeySha256']), 64)
            client.base_url = 'https://other.example'
            client.request.reset_mock()
            reopened.flush()
            client.request.assert_not_called()
            self.assertEqual(len(reopened.read()['pending']), 1)

    def test_outbox_tampering_is_not_published(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ClientStore(directory, b't' * 32)
            client = Mock()
            outbox = ActivityOutbox(store, lambda: client)
            outbox.write({'deviceId': 'device', 'pending': []})
            value = json.loads(outbox.path.read_text())
            value['data']['deviceId'] = 'changed'
            outbox.path.write_text(json.dumps(value))
            with self.assertRaises(ValueError):
                outbox.flush()
            client.request.assert_not_called()


if __name__ == '__main__':
    unittest.main()
