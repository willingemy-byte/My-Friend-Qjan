#!/usr/bin/env python3
"""Assemble local WebView assets without changing runtime loading or CSP."""
import argparse
import base64
import json
import re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def assemble():
    frontend=ROOT/'frontend'
    html=(frontend/'journal.template.html').read_text()
    parts=json.loads((frontend/'parts.json').read_text())
    seen=set()
    for part in parts:
        token,filename=part['token'],part['file']
        if token in seen or html.count(token)!=1 or not re.fullmatch(r'(style|script)-[0-9]{2}\.(css|js)',filename):
            raise ValueError('Invalid frontend part')
        seen.add(token)
        html=html.replace(token,(frontend/filename).read_text())
    if re.search(r'\{\{AIV_(STYLE|SCRIPT)_',html):
        raise ValueError('Missing frontend part')
    logo=(ROOT/'app/src/main/res/drawable/aiv_logo.jpg').read_bytes()
    html=html.replace('{{AIV_LOGO}}','data:image/jpeg;base64,'+base64.b64encode(logo).decode())
    return html

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args()
    content=assemble();target=ROOT/'app/src/main/assets/journal.html'
    if args.check:
        if target.read_text()!=content: raise SystemExit('Frontend asset is stale; run tools/build_frontend.py')
    else: target.write_text(content)
