"""Resolve public configuration from the repository root; never read secrets."""
import json
from pathlib import Path

def config_source(key):
    repository = Path(__file__).resolve().parents[2]
    index = json.loads((repository/'AIV_CONFIG.json').read_text())
    if index.get('schema') != 'aiv-config-index/1':
        raise ValueError('Invalid configuration index')
    relative = Path(index['sources'][key])
    if relative.is_absolute() or '..' in relative.parts:
        raise ValueError('Configuration path must remain relative to the repository')
    resolved = (repository/relative).resolve()
    if not resolved.is_relative_to(repository):
        raise ValueError('Configuration path escapes repository')
    return resolved
