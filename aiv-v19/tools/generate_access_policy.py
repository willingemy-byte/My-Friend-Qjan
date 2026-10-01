#!/usr/bin/env python3
"""Compile public access rules. Subscription verification belongs to a trusted caller."""
import argparse
import json
import re
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]

def validate(data):
    if not isinstance(data, dict) or set(data) != {'schema', 'tiers', 'reserved_tiers', 'service_minimum_tiers', 'distribution_profile'}:
        raise ValueError('Invalid access policy fields')
    if data['schema'] != 'aiv-access-policy/1' or data['tiers'] != {'free': 1, 'paid': 2, 'it': 3}:
        raise ValueError('Expected free=1, paid=2 and it=3')
    if any(type(v) is not int for v in data['tiers'].values()) or data['reserved_tiers'] != []:
        raise ValueError('Invalid tier identities')
    if data['distribution_profile'] not in ('personal', 'public'):
        raise ValueError('Distribution profile must be personal or public')
    services = data['service_minimum_tiers']
    if not isinstance(services, dict) or len(services) > 128:
        raise ValueError('Invalid services')
    for name, minimum in services.items():
        if not re.fullmatch(r'[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+', name) or len(name) > 80:
            raise ValueError('Invalid service name')
        if type(minimum) is not int or minimum not in (1, 2, 3):
            raise ValueError('Minimum tier must be 1, 2 or 3')
    return data

def render(data):
    validate(data)
    catalog=json.dumps(data, sort_keys=True, separators=(',', ':'))
    lines=['package fr.erick.journallocal;', '', '/** Generated public policy. No purchase/authentication check is performed here. */', 'public final class AccessPolicy {', '    private AccessPolicy() {}', '    public static final int TIER_FREE=1, TIER_PAID=2, TIER_IT=3;', '    public static final String CATALOG_JSON='+json.dumps(catalog)+';', '    public static int minimumTier(String service) {', '        if(service==null)return -1;', '        switch(service) {']
    for name, minimum in sorted(data['service_minimum_tiers'].items()):
        lines.append('            case '+json.dumps(name)+': return '+str(minimum)+';')
    lines += ['            default: return -1;', '        }', '    }', '    /** Caller must obtain verifiedTier from trusted entitlement state, never a UI preference. */', '    public static boolean allows(String service, int verifiedTier) {', '        return allowsMinimum(minimumTier(service), verifiedTier);', '    }', '    static boolean allowsMinimum(int minimumTier, int verifiedTier) {', '        if(verifiedTier!=TIER_FREE && verifiedTier!=TIER_PAID && verifiedTier!=TIER_IT)return false;', '        if(minimumTier!=TIER_FREE && minimumTier!=TIER_PAID && minimumTier!=TIER_IT)return false;', '        return verifiedTier>=minimumTier;', '    }', '}','']
    lines.insert(6,'    public static final int DISTRIBUTION_TIER='+str(3 if data['distribution_profile']=='personal' else 1)+';')
    return '\n'.join(lines)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--check', action='store_true');args=parser.parse_args()
    content=render(json.loads((ROOT/'config/access-policy.json').read_text()))
    target=ROOT/'app/src/main/java/fr/erick/journallocal/AccessPolicy.java'
    if args.check:
        if not target.is_file() or target.read_text()!=content: raise SystemExit('AccessPolicy.java is stale')
    else: target.write_text(content)
