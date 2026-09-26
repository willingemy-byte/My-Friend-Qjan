#!/usr/bin/env python3
"""Explicit maintainer refresh only. Never runs on a phone; no installed-app data sent."""
import argparse,hashlib,json,datetime,urllib.request
from pathlib import Path
URLS={'bayton':'https://raw.githubusercontent.com/jasonbayton/11ty/main/_src/_data/android_permissions.json','exodus':'https://reports.exodus-privacy.eu.org/api/trackers'}
def main():
    p=argparse.ArgumentParser();p.add_argument('--bayton-file',type=Path);p.add_argument('--exodus-file',type=Path);p.add_argument('--output',type=Path,default=Path(__file__).resolve().parents[1]/'app/src/main/assets/catalogs');a=p.parse_args();outputs={}
    for name,url in URLS.items():
        file=getattr(a,name+'_file')
        if file:raw=file.read_bytes()
        else:
            with urllib.request.urlopen(url,timeout=30) as r:raw=r.read(4*1024*1024+1)
        if len(raw)>4*1024*1024:raise ValueError('Reference exceeds 4 MiB')
        source=json.loads(raw)
        if name=='bayton':
            assert isinstance(source['permissions'],dict) and len(source['permissions'])>100
            data={'permissions':source['permissions'],'reviewed':source['lastReviewed'],'android_version':source['androidVersion'],'api_level':source['apiLevel'],'aosp_source':source['source'],'strings_source':source['stringsSource']}
        else:
            assert isinstance(source['trackers'],dict) and len(source['trackers'])>100
            data={'trackers':[{k:t.get(k) for k in ('id','name','code_signature','network_signature','categories','website')} for t in source['trackers'].values()]}
            assert len({t['id'] for t in data['trackers']})==len(data['trackers'])
        data.update(schema='aiv-bundled-'+name+'/1',source=url,fetched_utc=datetime.datetime.now(datetime.timezone.utc).date().isoformat(),upstream_sha256=hashlib.sha256(raw).hexdigest())
        outputs[name]=json.dumps(data,ensure_ascii=False,sort_keys=True,separators=(',',':'))+'\n'
    a.output.mkdir(parents=True,exist_ok=True)
    for name,contents in outputs.items():
        target=a.output/(name+'.json');tmp=target.with_suffix('.tmp');tmp.write_text(contents,encoding='utf-8');tmp.replace(target)
        print(name,hashlib.sha256(contents.encode()).hexdigest())
if __name__=='__main__':main()
