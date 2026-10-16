#!/usr/bin/env python3
"""Loopback-only wire fixture, not a speech recognizer. Never deploy this as STT."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_POST(self):
        if self.path != '/transcriptions' or self.headers.get('Authorization') != 'Bearer separate-speech-key':
            self.send_error(403)
            return
        count = int(self.headers.get('Content-Length', '0'))
        if not 0 < count < 2_000_000:
            self.send_error(413)
            return
        body = self.rfile.read(count)
        if not all(value in body for value in (b'fixture-whisper', b'audio/wav', b'RIFF', b'WAVE')):
            self.send_error(400)
            return
        result = json.dumps({'text': 'Check the fixture.'}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(result)))
        self.end_headers()
        self.wfile.write(result)

if __name__ == '__main__':
    print('READY speech wire fixture http://127.0.0.1:18647', flush=True)
    ThreadingHTTPServer(('127.0.0.1', 18647), Handler).serve_forever()
