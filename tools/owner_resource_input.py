"""Signed, encrypted commands for the owner's temporary Android test phone.

Only public keys and encrypted envelopes enter the public repository. Inspect
each screenshot before deciding the next UI action. No game states are forged.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time
from Crypto.Cipher import AES, PKCS1_OAEP
from Crypto.Hash import SHA256
from Crypto.PublicKey import RSA
from Crypto.Random import get_random_bytes
from Crypto.Signature import pss

CMD_BRANCH = 'live-cmd-kickoff-20261113'
RES_BRANCH = 'live-res-kickoff-20261113'
AAD = b'peerlink-owner-phone-lab-v1'


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def encode(value):
    return base64.b64encode(value).decode()


def decode(value):
    return base64.b64decode(value, validate=True)


def seal(payload, recipient, signer):
    key = get_random_bytes(32)
    cipher = AES.new(key, AES.MODE_GCM, nonce=get_random_bytes(12))
    cipher.update(AAD)
    ciphertext, tag = cipher.encrypt_and_digest(canonical(payload))
    result = {'v': 1, 'key': encode(PKCS1_OAEP.new(recipient, hashAlgo=SHA256).encrypt(key)),
              'nonce': encode(cipher.nonce), 'ciphertext': encode(ciphertext), 'tag': encode(tag)}
    result['signature'] = encode(pss.new(signer).sign(SHA256.new(canonical(result))))
    return result


def unseal(envelope, recipient, sender):
    value = dict(envelope)
    signature = decode(value.pop('signature'))
    if value.get('v') != 1:
        raise ValueError('Unknown protocol version')
    pss.new(sender).verify(SHA256.new(canonical(value)), signature)
    key = PKCS1_OAEP.new(recipient, hashAlgo=SHA256).decrypt(decode(value['key']))
    cipher = AES.new(key, AES.MODE_GCM, nonce=decode(value['nonce']))
    cipher.update(AAD)
    return json.loads(cipher.decrypt_and_verify(decode(value['ciphertext']), decode(value['tag'])))


def api(path, payload=None, optional=False):
    command = ['gh', 'api', path]
    if payload is not None:
        command += ['--method', 'POST', '--input', '-']
    result = subprocess.run(command, input=None if payload is None else canonical(payload),
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=40)
    if result.returncode:
        if optional and b'404' in result.stderr:
            return None
        raise RuntimeError('GitHub lab transport request failed')
    return json.loads(result.stdout)


def read_file(repository, branch, path):
    data = api(f'repos/{repository}/contents/{path}?ref={branch}', optional=True)
    if data is None:
        return None
    if data.get('encoding') != 'base64':
        data = api(f'repos/{repository}/git/blobs/{data["sha"]}')
    return base64.b64decode(data['content'])


def put_file(repository, branch, path, data):
    old = api(f'repos/{repository}/contents/{path}?ref={branch}', optional=True)
    value = {'branch': branch, 'message': f'Update temporary phone lab {path}', 'content': encode(data)}
    if old is not None:
        value['sha'] = old['sha']
    result = subprocess.run(['gh', 'api', f'repos/{repository}/contents/{path}', '--method', 'PUT', '--input', '-'],
                            input=canonical(value), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=40)
    if result.returncode:
        raise RuntimeError('GitHub lab transport update failed')


def ensure_branch(repository, name):
    if api(f'repos/{repository}/git/ref/heads/{name}', optional=True) is None:
        head = api(f'repos/{repository}/git/ref/heads/main')['object']['sha']
        api(f'repos/{repository}/git/refs', {'ref': 'refs/heads/' + name, 'sha': head})



def receive():
    repository = os.environ['GITHUB_REPOSITORY']
    requests, replies = 'resource-input-requests', 'resource-input-ready'
    controller = RSA.import_key(Path('tools/match-resource-controller.pub').read_bytes())
    server = RSA.generate(3072)
    ensure_branch(repository, requests)
    ensure_branch(repository, replies)
    run_id = os.environ['GITHUB_RUN_ID']
    status = {'run_id': run_id, 'server_public_key': server.public_key().export_key().decode(),
              'controller_fingerprint': hashlib.sha256(controller.export_key()).hexdigest()}
    put_file(repository, replies, 'ready.json', canonical(status))
    print('ENCRYPTED_RESOURCE_INPUT_READY', run_id, flush=True)
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        raw = read_file(repository, requests, 'input.json')
        if raw:
            try:
                value = unseal(json.loads(raw), server, controller)
                if value.get('run_id') == run_id:
                    return value['config']
            except (ValueError, KeyError, TypeError):
                pass
        time.sleep(5)
    raise RuntimeError('Encrypted resource input was not delivered')
