#!/usr/bin/env python3
"""Serves the Loose Leaf web version, and optionally a library folder.

    python3 serve.py                                  the app only (same as any static server)
    python3 serve.py --library /mnt/nas/loose-leaf    plus the Library module's storage

With --library, the Library module in the app can keep copies of saved PDFs in
that folder (for example a folder on a NAS that is mounted on this machine),
and list, open, rename, move and delete them. Without it the app works exactly
as on any static web server and uploads nothing.

Everyone who can reach this port can read and change the library. Set a
passphrase to limit that:

    LOOSELEAF_KEY='something long' python3 serve.py --library ...

Over plain http the passphrase and the PDFs travel unencrypted on the network,
so use it on a home network or a VPN, or put it behind https.

Python standard library only. The API (all under /api/library, JSON answers):
    GET    /api/library                 is there a library, does it need a passphrase
    GET    /api/library/list?path=      folders and PDFs in a folder
    GET    /api/library/file?path=      one PDF
    PUT    /api/library/file?path=      store a PDF; never replaces, a taken name gets a number
    POST   /api/library/mkdir?path=     new folder
    POST   /api/library/move?from=&to=  rename or move; never replaces
    DELETE /api/library/file?path=      delete a PDF or an empty folder
"""

import argparse
import hmac
import json
import os
import sys
import urllib.parse
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

WEB_ROOT = os.path.dirname(os.path.realpath(__file__))
API = '/api/library'
MAX_NAME = 180


class Refused(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status
        self.message = message


class Library:
    def __init__(self, root, max_bytes):
        self.root = os.path.realpath(root)
        self.max_bytes = max_bytes

    def resolve(self, rel, must_be_pdf=False):
        """A path given by the browser -> a real path that is certainly inside the library."""
        if '\x00' in rel or '\\' in rel:
            raise Refused(400, 'That name cannot be used.')
        parts = [p for p in rel.split('/') if p]
        for p in parts:
            if p.startswith('.') or len(p) > MAX_NAME or any(ord(c) < 32 for c in p):
                raise Refused(400, 'That name cannot be used.')
        if must_be_pdf and (not parts or not parts[-1].lower().endswith('.pdf')):
            raise Refused(400, 'Only PDF files can be kept in the library.')
        path = os.path.join(self.root, *parts)
        real = os.path.realpath(path)
        if real != self.root and not real.startswith(self.root + os.sep):
            raise Refused(400, 'That is outside the library.')
        return real

    def list(self, rel):
        folder = self.resolve(rel)
        if not os.path.isdir(folder):
            raise Refused(404, 'That folder does not exist.')
        folders, files = [], []
        with os.scandir(folder) as it:
            for e in it:
                if e.name.startswith('.'):
                    continue
                if e.is_dir(follow_symlinks=False):
                    folders.append(e.name)
                elif e.is_file(follow_symlinks=False) and e.name.lower().endswith('.pdf'):
                    st = e.stat()
                    files.append({'name': e.name, 'size': st.st_size, 'modified': int(st.st_mtime * 1000)})
        folders.sort(key=str.lower)
        files.sort(key=lambda f: f['name'].lower())
        return {'folders': folders, 'files': files}

    def free_name(self, path):
        """Opens a new file for writing. If the name is taken, 'name (2).pdf' and so on."""
        stem, ext = os.path.splitext(path)
        for n in range(1, 1000):
            candidate = path if n == 1 else f'{stem} ({n}){ext}'
            try:
                return candidate, os.open(candidate, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o644)
            except FileExistsError:
                continue
        raise Refused(409, 'Too many files with that name.')

    def store(self, rel, stream, length):
        if length <= 0 or length > self.max_bytes:
            raise Refused(413, 'That file is too large for the library.')
        path = self.resolve(rel, must_be_pdf=True)
        if not os.path.isdir(os.path.dirname(path)):
            raise Refused(404, 'That folder does not exist.')
        head = stream.read(min(5, length))
        if head != b'%PDF-':
            raise Refused(400, 'That is not a PDF.')
        final, fd = self.free_name(path)
        try:
            with os.fdopen(fd, 'wb') as out:
                out.write(head)
                left = length - len(head)
                while left > 0:
                    chunk = stream.read(min(1 << 20, left))
                    if not chunk:
                        raise Refused(400, 'The upload was cut short.')
                    out.write(chunk)
                    left -= len(chunk)
        except BaseException:
            try:
                os.remove(final)
            except OSError:
                pass
            raise
        return {'path': os.path.relpath(final, self.root).replace(os.sep, '/')}

    def mkdir(self, rel):
        path = self.resolve(rel)
        if path == self.root:
            raise Refused(400, 'Give the folder a name.')
        try:
            os.mkdir(path)
        except FileExistsError:
            raise Refused(409, 'Something with that name is already there.')
        except FileNotFoundError:
            raise Refused(404, 'That folder does not exist.')
        return {}

    def move(self, src, dst):
        a = self.resolve(src)
        if a == self.root or not os.path.lexists(a):
            raise Refused(404, 'That does not exist any more.')
        b = self.resolve(dst, must_be_pdf=os.path.isfile(a))
        if os.path.lexists(b):
            raise Refused(409, 'Something with that name is already there.')
        if not os.path.isdir(os.path.dirname(b)):
            raise Refused(404, 'That folder does not exist.')
        if os.path.isdir(a) and (b + os.sep).startswith(a + os.sep):
            raise Refused(400, 'A folder cannot be moved into itself.')
        os.rename(a, b)
        return {}

    def delete(self, rel):
        path = self.resolve(rel)
        if path == self.root or not os.path.lexists(path):
            raise Refused(404, 'That does not exist any more.')
        if os.path.isdir(path):
            try:
                os.rmdir(path)
            except OSError:
                raise Refused(409, 'That folder is not empty.')
        else:
            os.remove(path)
        return {}


class Handler(SimpleHTTPRequestHandler):
    library = None
    key = ''
    protocol_version = 'HTTP/1.1'

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=WEB_ROOT, **kwargs)

    # ---- routing

    def do_GET(self):
        if self.is_api():
            return self.api('GET')
        if self.path.split('?')[0].endswith('.py'):
            return self.send_error(404)
        super().do_GET()

    def do_PUT(self):
        self.api('PUT') if self.is_api() else self.send_error(405)

    def do_POST(self):
        self.api('POST') if self.is_api() else self.send_error(405)

    def do_DELETE(self):
        self.api('DELETE') if self.is_api() else self.send_error(405)

    def is_api(self):
        p = self.path.split('?')[0]
        return p == API or p.startswith(API + '/')

    def end_headers(self):
        if not self.is_api():
            self.send_header('Cache-Control', 'no-cache')  # always ask whether a file changed
        super().end_headers()

    # ---- the library API

    def api(self, method):
        url = urllib.parse.urlsplit(self.path)
        q = {k: v[0] for k, v in urllib.parse.parse_qs(url.query, keep_blank_values=True).items()}
        action = url.path[len(API):].strip('/')
        try:
            if self.library is None:
                raise Refused(404, 'This copy of Loose Leaf has no library.')
            # A page on another site cannot add this header without our permission, which we never give.
            if self.headers.get('X-Loose-Leaf') != '1':
                raise Refused(403, 'Not allowed.')
            if (method, action) == ('GET', ''):
                return self.json(200, {'library': True, 'passphrase': bool(self.key)})
            if self.key and not hmac.compare_digest(self.headers.get('X-Library-Key', '').encode(), self.key.encode()):
                raise Refused(401, 'The passphrase is missing or wrong.')
            lib = self.library
            if (method, action) == ('GET', 'list'):
                return self.json(200, lib.list(q.get('path', '')))
            if (method, action) == ('GET', 'file'):
                return self.send_pdf(lib.resolve(q.get('path', ''), must_be_pdf=True))
            if (method, action) == ('PUT', 'file'):
                length = int(self.headers.get('Content-Length') or 0)
                return self.json(201, lib.store(q.get('path', ''), self.rfile, length))
            self.drain()
            if (method, action) == ('POST', 'mkdir'):
                return self.json(201, lib.mkdir(q.get('path', '')))
            if (method, action) == ('POST', 'move'):
                return self.json(200, lib.move(q.get('from', ''), q.get('to', '')))
            if (method, action) == ('DELETE', 'file'):
                return self.json(200, lib.delete(q.get('path', '')))
            raise Refused(404, 'No such action.')
        except Refused as e:
            self.close_connection = True  # an upload may be only half read
            self.json(e.status, {'error': e.message})
        except OSError as e:
            self.close_connection = True
            self.json(500, {'error': f'The library folder could not be used: {e.strerror or e}'})

    def drain(self):
        length = int(self.headers.get('Content-Length') or 0)
        if 0 < length <= 65536:
            self.rfile.read(length)
        elif length:
            self.close_connection = True

    def json(self, status, value):
        body = json.dumps(value).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(body)

    def send_pdf(self, path):
        if not os.path.isfile(path):
            raise Refused(404, 'That file does not exist any more.')
        with open(path, 'rb') as f:
            size = os.fstat(f.fileno()).st_size
            self.send_response(200)
            self.send_header('Content-Type', 'application/pdf')
            self.send_header('Content-Length', str(size))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.copyfile(f, self.wfile)


def main():
    ap = argparse.ArgumentParser(description='Serve the Loose Leaf web version.')
    ap.add_argument('--port', type=int, default=8377)
    ap.add_argument('--bind', default='0.0.0.0')
    ap.add_argument('--library', default=os.environ.get('LOOSELEAF_LIBRARY'),
                    help='folder to keep saved copies in (or set LOOSELEAF_LIBRARY); leave out for no library')
    ap.add_argument('--max-mb', type=int, default=300, help='largest PDF the library accepts')
    args = ap.parse_args()

    if args.library:
        root = os.path.realpath(os.path.expanduser(args.library))
        if not os.path.isdir(root):
            sys.exit(f'The library folder does not exist: {root}\nCreate it first (or mount the NAS share), then start again.')
        if root == WEB_ROOT or root.startswith(WEB_ROOT + os.sep) or WEB_ROOT.startswith(root + os.sep):
            sys.exit('The library folder must not be inside (or around) the web-version folder.')
        Handler.library = Library(root, args.max_mb * 1024 * 1024)
        Handler.key = os.environ.get('LOOSELEAF_KEY', '')
        print(f'Library: {root} ({"passphrase set" if Handler.key else "NO passphrase: anyone who can reach this port can use it"})')
    else:
        print('No library folder given: serving the app only.')
    print(f'Loose Leaf on http://{args.bind}:{args.port}/')
    ThreadingHTTPServer((args.bind, args.port), Handler).serve_forever()


if __name__ == '__main__':
    main()
