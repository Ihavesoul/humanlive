#!/usr/bin/env python3
from __future__ import annotations
import http.server, socket, socketserver
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]; PORT=8765
class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self,*args,**kwargs): super().__init__(*args,directory=str(ROOT),**kwargs)
    def end_headers(self): self.send_header('Cache-Control','no-cache'); super().end_headers()
def lan_ip():
    s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
    try: s.connect(('8.8.8.8',80)); return s.getsockname()[0]
    except OSError: return '127.0.0.1'
    finally: s.close()
with socketserver.ThreadingTCPServer(('0.0.0.0',PORT),Handler) as server:
    print(f'Desktop: http://127.0.0.1:{PORT}/app/'); print(f'LAN:     http://{lan_ip()}:{PORT}/app/'); print('Ctrl+C to stop.')
    try: server.serve_forever()
    except KeyboardInterrupt: pass
