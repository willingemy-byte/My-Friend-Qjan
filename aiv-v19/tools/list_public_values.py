"""Export the exact public values and aliases from their authoritative sources."""
import argparse
import json
from pathlib import Path
from public_config import config_source

root=Path(__file__).resolve().parents[2]
defaults=json.loads(config_source('DEFAULTS_CONFIG').read_text())
access=json.loads(config_source('ACCESS_POLICY_CONFIG').read_text())
values={}
for section, fields in defaults.items():
    if isinstance(fields, dict):
        for key, value in fields.items():values[f'{section.upper()}_{key.upper()}']=value
values.update(USER_TIER_FREE=access['tiers']['free'],USER_TIER_PAID=access['tiers']['paid'],USER_TIER_IT=access['tiers']['it'],DISTRIBUTION_PROFILE=access['distribution_profile'])
for key, value in access['service_minimum_tiers'].items():values[f'SERVICE_{key.upper().replace(".", "_")}_MIN_TIER']=value
index=json.loads((root/'AIV_CONFIG.json').read_text())
values.update(index['sources'])
artifact={'schema':'aiv-public-values/1','generated_from':'AIV_CONFIG.json','values':values,'secrets_required_for_unsigned_build':[],
          'optional_secret_names':{'AIV_APK_SIGNING_KEY_BASE64':'Proposition pour une future signature CI : fichier de clé privée encodé en base64, non consommé par le build actuel.',
                                   'AIV_APK_SIGNING_PASSWORD':'Proposition pour une future signature CI : mot de passe de cette clé, non consommé par le build actuel.'},
          'android_keystore':'Identité de chaque installation générée dans Android Keystore ; jamais placée dans GitHub Secrets.'}
artifact['editing']={'index':'AIV_CONFIG.json', 'defaults':index['sources']['DEFAULTS_CONFIG'], 'tiers_and_services':index['sources']['ACCESS_POLICY_CONFIG'], 'inventory':'AIV_VALUES.json est généré ; modifier les sources puis exécuter les générateurs.', 'scope':'Valeurs embarquées à la compilation ; une modification nécessite de reconstruire l’APK.'}
parser=argparse.ArgumentParser()
parser.add_argument('--check',action='store_true')
args=parser.parse_args()
content=json.dumps(artifact,ensure_ascii=False,indent=2)+'\n'
if args.check:
    if (root/'AIV_VALUES.json').read_text()!=content: raise SystemExit('AIV_VALUES.json is stale')
else: (root/'AIV_VALUES.json').write_text(content)
print(f'{len(values)} valeurs publiques ; aucun secret nécessaire pour compiler sans signature.')
