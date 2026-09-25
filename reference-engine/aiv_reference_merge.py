#!/usr/bin/env python3
import argparse, json
from pathlib import Path

def load(p): return json.loads(Path(p).read_text(encoding="utf-8"))

def normalize_device(data):
    if isinstance(data, dict) and "apps" in data:
        return {a["package_name"]: a for a in data["apps"]}
    if isinstance(data, dict):
        return {k: dict(v, package_name=k) for k,v in data.items()}
    raise ValueError("device input must be an object or {apps:[...]}")

def merge(device, bayton, exodus):
    bperms=bayton.get("permissions", {})
    trackers=exodus.get("trackers", exodus if isinstance(exodus,dict) else {})
    apps=[]
    for package, app in sorted(normalize_device(device).items()):
        raw=app.get("permissions") or app.get("permission_meta") or []
        names=[]
        for p in raw:
            names.append(p if isinstance(p,str) else p.get("name"))
        enriched=[]
        for name in filter(None,names):
            meta=bperms.get(name,{})
            enriched.append({
                "name":name,
                "bayton_aosp":meta or None,
                "device":next((x for x in raw if isinstance(x,dict) and x.get("name")==name),None)
            })
        apps.append({"package_name":package,"permissions":enriched})
    return {
        "schema":"aiv-reference/1",
        "sources":{
            "device":{"kind":"observed_device_inventory"},
            "bayton_aosp":{"kind":"permission_catalog"},
            "exodus":{"kind":"tracker_catalog"}
        },
        "apps":apps,
        "tracker_catalog":trackers
    }

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--device",required=True); p.add_argument("--bayton",required=True)
    p.add_argument("--exodus",required=True); p.add_argument("--output",required=True)
    a=p.parse_args()
    out=merge(load(a.device),load(a.bayton),load(a.exodus))
    Path(a.output).write_text(json.dumps(out,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
if __name__=="__main__": main()
