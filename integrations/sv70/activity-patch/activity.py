"""Durable audit outbox; contains identifiers only, never PACS or certificate credentials."""
import hashlib
import hmac
import json
import os
import socket
import tempfile
from pathlib import Path
from uuid import uuid4

from certificate_broker.client_store import canonical


class ActivityOutbox:
    def __init__(self, store, certificate_factory):
        self.store, self.factory = store, certificate_factory
        self.path = store.path.parent / 'certificate-activity.json'

    def read(self):
        if not self.path.exists():
            return {'deviceId': str(uuid4()), 'pending': []}
        envelope = json.loads(self.path.read_text(encoding='utf-8'))
        expected = hmac.new(self.store.key, canonical(envelope['data']), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(expected, envelope['integrity']):
            raise ValueError('Activity outbox integrity failed')
        return envelope['data']

    def write(self, data):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        envelope = {'data': data, 'integrity': hmac.new(self.store.key, canonical(data), hashlib.sha256).hexdigest()}
        fd, temporary = tempfile.mkstemp(prefix='activity-', suffix='.tmp', dir=self.path.parent)
        try:
            with os.fdopen(fd, 'w', encoding='utf-8') as stream:
                json.dump(envelope, stream, ensure_ascii=False)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    def record(self, receipt, user, action, report_key=None):
        proof = receipt['serverResponse']
        with self.store.lock:
            data = self.read()
            body = {'eventId': str(uuid4()), 'action': action, 'clientInfo': {
                'pacsLoginId': str(user['user_id']), 'pacsAccountId': str(user['user_idx']),
                'computerName': socket.gethostname()[:128], 'deviceId': data['deviceId']}}
            if report_key:
                body['reportKeySha256'] = hashlib.sha256(canonical(report_key)).hexdigest()
            data['pending'].append({'authority': receipt['authority'], 'audience': proof['audience'],
                'verificationId': proof['verificationId'], 'body': body})
            self.write(data)

    def flush(self):
        if not self.path.exists():
            return
        with self.store.lock:
            pending = self.read()['pending'][:100]
        for event in pending:
            try:
                client = self.factory()
                if event['authority'] != client.base_url or event['audience'] != client.client_id:
                    continue  # Never send a previous authority's identifiers to a changed server.
                result = client.request('POST', '/certificate-proofs/' + event['verificationId'] + '/events', event['body'])
                if result.get('recorded') is not True:
                    return
            except Exception:
                return  # Keep the exact eventId for retry after restart or network recovery.
            with self.store.lock:
                data = self.read()
                data['pending'] = [item for item in data['pending'] if item['body']['eventId'] != event['body']['eventId']]
                self.write(data)
