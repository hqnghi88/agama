import http.server
import os

class RangeHTTPServer(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Access-Control-Allow-Origin', '*')
        super().end_headers()

    def do_HEAD(self):
        self.send_response(200)
        self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Content-Length', os.path.getsize(self.translate_path(self.path)))
        self.end_headers()

    def do_GET(self):
        f = self.send_head()
        if f:
            try:
                self.copyfile(f, self.wfile)
            finally:
                f.close()

    def send_head(self):
        path = self.translate_path(self.path)
        try:
            stat = os.stat(path)
        except FileNotFoundError:
            self.send_error(404)
            return None
        
        length = stat.st_size
        
        range_header = self.headers.get('Range')
        if range_header and range_header.startswith('bytes='):
            parts = range_header[6:].split('-')
            start = int(parts[0]) if parts[0] else 0
            end = int(parts[1]) if parts[1] else length - 1
            end = min(end, length - 1)
            
            self.send_response(206)
            self.send_header('Content-type', self.guess_type(path))
            self.send_header('Content-Range', f'bytes {start}-{end}/{length}')
            self.send_header('Content-Length', str(end - start + 1))
            self.end_headers()
            
            f = open(path, 'rb')
            f.seek(start)
            return f
        
        return super().send_head()

if __name__ == '__main__':
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    server = http.server.HTTPServer(('', 8765), RangeHTTPServer)
    print('Serving on http://localhost:8765 with Range support')
    server.serve_forever()
