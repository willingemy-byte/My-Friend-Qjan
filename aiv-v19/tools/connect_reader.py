#!/usr/bin/env python3
from pathlib import Path
root=Path(__file__).resolve().parents[1]
p=root/'app/src/main/assets/journal.html'
if not p.is_file() or p.stat().st_size < 1000:
    raise SystemExit('journal.html missing or incomplete')
print('Using committed journal.html:', p.stat().st_size, 'bytes')
