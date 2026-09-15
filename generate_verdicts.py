#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Journal Local AIV — générateur HORS APK de scores de cohérence.

Ce script ne produit pas un verdict de sécurité. Il compare la fonction
attendue d'une application à ses permissions déclarées et documente les
écarts observés. Une permission déclarée ne prouve pas son usage.

Python 3.9+, stdlib uniquement.
"""
from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import os
import re
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

SCHEMA = "aiv-coherence-verdicts/2"
ENGINE = "generate_verdicts.py/2"

# Le test demandé "météo + CALL_PHONE => score < 65" est incompatible avec
# une pénalité critique de seulement 15 points. On utilise donc 40 pour les
# permissions critiques afin que le test d'acceptation soit cohérent.
P_SUSPICIOUS = 10
P_CRITICAL = 40
P_CLUSTER = 30
P_MANY = 15
P_NEVER = 20
CLUSTER_THRESHOLD = 3
MANY_THRESHOLD = 40

PERMISSION_CAPABILITIES: Dict[str, str] = {
    "INTERNET": "Accès réseau sortant",
    "ACCESS_NETWORK_STATE": "Lecture de l'état réseau",
    "READ_CONTACTS": "Lecture des contacts",
    "WRITE_CONTACTS": "Modification des contacts",
    "GET_ACCOUNTS": "Lecture des comptes du téléphone",
    "READ_CALL_LOG": "Lecture du journal d'appels",
    "WRITE_CALL_LOG": "Modification du journal d'appels",
    "CALL_PHONE": "Déclenchement d'un appel téléphonique",
    "READ_SMS": "Lecture des SMS",
    "SEND_SMS": "Envoi de SMS",
    "RECEIVE_SMS": "Réception des SMS",
    "CAMERA": "Accès caméra",
    "RECORD_AUDIO": "Accès microphone",
    "ACCESS_FINE_LOCATION": "Position précise",
    "ACCESS_BACKGROUND_LOCATION": "Position en arrière-plan",
    "QUERY_ALL_PACKAGES": "Visibilité étendue des applications installées",
    "GRANT_RUNTIME_PERMISSIONS": "Attribution de permissions d'exécution",
    "INSTALL_PACKAGES": "Installation de paquets",
    "DELETE_PACKAGES": "Suppression de paquets",
    "READ_LOGS": "Lecture de journaux système",
    "WRITE_SECURE_SETTINGS": "Modification de réglages système protégés",
    "BIND_ACCESSIBILITY_SERVICE": "Service d'accessibilité",
    "SYSTEM_ALERT_WINDOW": "Superposition d'interface",
    "PACKAGE_USAGE_STATS": "Statistiques d'utilisation des applications",
    "READ_PHONE_STATE": "État téléphonique",
    "RECEIVE_BOOT_COMPLETED": "Réception du démarrage",
    "FOREGROUND_SERVICE": "Service au premier plan",
    "REQUEST_INSTALL_PACKAGES": "Demande d'installation d'APK",
    "POST_NOTIFICATIONS": "Notifications",
    "READ_MEDIA_IMAGES": "Lecture des images",
    "READ_MEDIA_VIDEO": "Lecture des vidéos",
    "READ_MEDIA_AUDIO": "Lecture de l'audio",
    "ACTIVITY_RECOGNITION": "Reconnaissance d'activité",
    "BODY_SENSORS": "Capteurs corporels",
    "BLUETOOTH_CONNECT": "Connexion Bluetooth",
    "BLUETOOTH_SCAN": "Recherche Bluetooth",
    "NFC": "Accès NFC",
    "SCHEDULE_EXACT_ALARM": "Alarmes exactes",
}

CRITICAL = {"CALL_PHONE", "WRITE_CALL_LOG", "GRANT_RUNTIME_PERMISSIONS"}
OFFENSIVE = {
    "CALL_PHONE", "READ_CALL_LOG", "WRITE_CALL_LOG", "READ_SMS", "SEND_SMS",
    "RECEIVE_SMS", "RECORD_AUDIO", "CAMERA", "ACCESS_BACKGROUND_LOCATION",
    "GRANT_RUNTIME_PERMISSIONS", "INSTALL_PACKAGES", "DELETE_PACKAGES",
    "READ_LOGS", "WRITE_SECURE_SETTINGS", "BIND_ACCESSIBILITY_SERVICE",
    "SYSTEM_ALERT_WINDOW", "QUERY_ALL_PACKAGES", "PACKAGE_USAGE_STATS",
}

EXPECTED_BY_CATEGORY: Dict[str, Dict[str, List[str]]] = {
    "email": {
        "required": ["INTERNET", "ACCESS_NETWORK_STATE"],
        "suspicious": ["CALL_PHONE", "READ_CALL_LOG", "WRITE_CALL_LOG", "READ_SMS",
                       "SEND_SMS", "ACCESS_BACKGROUND_LOCATION", "QUERY_ALL_PACKAGES",
                       "PACKAGE_USAGE_STATS"],
        "never": ["GRANT_RUNTIME_PERMISSIONS", "INSTALL_PACKAGES", "DELETE_PACKAGES",
                  "WRITE_SECURE_SETTINGS"],
    },
    "messaging": {
        "required": ["INTERNET", "ACCESS_NETWORK_STATE"],
        "suspicious": ["READ_CALL_LOG", "WRITE_CALL_LOG", "CALL_PHONE",
                       "ACCESS_BACKGROUND_LOCATION", "QUERY_ALL_PACKAGES"],
        "never": ["GRANT_RUNTIME_PERMISSIONS", "INSTALL_PACKAGES", "DELETE_PACKAGES",
                  "WRITE_SECURE_SETTINGS", "BIND_ACCESSIBILITY_SERVICE"],
    },
    "video": {
        "required": ["INTERNET", "ACCESS_NETWORK_STATE"],
        "suspicious": ["READ_CONTACTS", "READ_CALL_LOG", "WRITE_CALL_LOG", "READ_SMS",
                       "SEND_SMS", "ACCESS_BACKGROUND_LOCATION", "QUERY_ALL_PACKAGES"],
        "never": ["GRANT_RUNTIME_PERMISSIONS", "INSTALL_PACKAGES", "DELETE_PACKAGES",
                  "WRITE_SECURE_SETTINGS"],
    },
    "contacts": {
        "required": ["READ_CONTACTS", "WRITE_CONTACTS"],
        "suspicious": ["INTERNET", "CALL_PHONE", "READ_CALL_LOG", "ACCESS_FINE_LOCATION",
                       "QUERY_ALL_PACKAGES"],
        "never": ["GRANT_RUNTIME_PERMISSIONS", "INSTALL_PACKAGES", "DELETE_PACKAGES",
                  "BIND_ACCESSIBILITY_SERVICE"],
    },
    "weather": {
        "required": ["INTERNET", "ACCESS_NETWORK_STATE"],
        "suspicious": ["CALL_PHONE", "READ_CALL_LOG", "WRITE_CALL_LOG", "READ_SMS",
                       "SEND_SMS", "READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS",
                       "RECORD_AUDIO", "CAMERA", "ACCESS_BACKGROUND_LOCATION",
                       "QUERY_ALL_PACKAGES"],
        "never": ["GRANT_RUNTIME_PERMISSIONS", "INSTALL_PACKAGES", "DELETE_PACKAGES",
                  "WRITE_SECURE_SETTINGS", "BIND_ACCESSIBILITY_SERVICE", "READ_LOGS"],
    },
    "browser": {
        "required": ["INTERNET", "ACCESS_NETWORK_STATE"],
        "suspicious": ["READ_SMS", "SEND_SMS", "READ_CALL_LOG", "WRITE_CALL_LOG",
                       "CALL_PHONE", "GET_ACCOUNTS", "ACCESS_BACKGROUND_LOCATION",
                       "QUERY_ALL_PACKAGES"],
        "never": ["GRANT_RUNTIME_PERMISSIONS", "INSTALL_PACKAGES", "DELETE_PACKAGES",
                  "WRITE_SECURE_SETTINGS", "READ_LOGS"],
    },
    "photos": {
        "required": [],
        "suspicious": ["INTERNET", "READ_CONTACTS", "CALL_PHONE", "READ_CALL_LOG",
                       "ACCESS_BACKGROUND_LOCATION", "QUERY_ALL_PACKAGES", "RECORD_AUDIO"],
        "never": ["GRANT_RUNTIME_PERMISSIONS", "INSTALL_PACKAGES", "DELETE_PACKAGES",
                  "WRITE_SECURE_SETTINGS"],
    },
    "social": {
        "required": ["INTERNET", "ACCESS_NETWORK_STATE"],
        "suspicious": ["READ_CALL_LOG", "WRITE_CALL_LOG", "CALL_PHONE",
                       "ACCESS_BACKGROUND_LOCATION", "QUERY_ALL_PACKAGES",
                       "PACKAGE_USAGE_STATS"],
        "never": ["GRANT_RUNTIME_PERMISSIONS", "INSTALL_PACKAGES", "DELETE_PACKAGES",
                  "WRITE_SECURE_SETTINGS", "READ_LOGS"],
    },
    "navigation": {
        "required": ["INTERNET", "ACCESS_FINE_LOCATION"],
        "suspicious": ["READ_CONTACTS", "READ_CALL_LOG", "CALL_PHONE", "READ_SMS",
                       "SEND_SMS", "RECORD_AUDIO", "QUERY_ALL_PACKAGES"],
        "never": ["GRANT_RUNTIME_PERMISSIONS", "INSTALL_PACKAGES", "DELETE_PACKAGES",
                  "WRITE_SECURE_SETTINGS"],
    },
    "utility": {
        "required": [],
        "suspicious": ["CALL_PHONE", "READ_CALL_LOG", "WRITE_CALL_LOG", "READ_SMS",
                       "SEND_SMS", "READ_CONTACTS", "ACCESS_BACKGROUND_LOCATION",
                       "QUERY_ALL_PACKAGES", "RECORD_AUDIO", "CAMERA"],
        "never": ["GRANT_RUNTIME_PERMISSIONS", "INSTALL_PACKAGES", "DELETE_PACKAGES",
                  "WRITE_SECURE_SETTINGS", "BIND_ACCESSIBILITY_SERVICE", "READ_LOGS"],
    },
    "secure_storage": {
        "required": [],
        "suspicious": ["CALL_PHONE", "READ_CALL_LOG", "WRITE_CALL_LOG", "READ_SMS",
                       "SEND_SMS", "RECEIVE_SMS", "READ_CONTACTS", "WRITE_CONTACTS",
                       "ACCESS_BACKGROUND_LOCATION", "RECORD_AUDIO", "CAMERA",
                       "QUERY_ALL_PACKAGES", "PACKAGE_USAGE_STATS"],
        "never": ["GRANT_RUNTIME_PERMISSIONS", "INSTALL_PACKAGES", "DELETE_PACKAGES"],
    },
    "system": {"required": [], "suspicious": [], "never": []},
    # Inconnu = pas de P1 : on refuse d'inventer la fonction de l'app.
    "unknown": {"required": [], "suspicious": [], "never": []},
}

CATEGORY_PREFIXES: List[Tuple[str, str]] = [
    ("com.google.android.gm", "email"),
    ("com.google.android.apps.tachyon", "video"),
    ("com.google.android.apps.maps", "navigation"),
    ("com.google.android.apps.photos", "photos"),
    ("com.google.android.youtube", "video"),
    ("com.whatsapp", "messaging"),
    ("org.telegram.messenger", "messaging"),
    ("org.thoughtcrime.securesms", "messaging"),
    ("com.facebook.katana", "social"),
    ("com.facebook.orca", "messaging"),
    ("com.instagram.android", "social"),
    ("com.twitter.android", "social"),
    ("com.snapchat.android", "social"),
    ("org.mozilla.firefox", "browser"),
    ("com.brave.browser", "browser"),
    ("com.opera", "browser"),
    ("com.accuweather", "weather"),
    ("com.wunderground", "weather"),
    ("com.samsung.knox.securefolder", "secure_storage"),
    ("com.sec.knox.foldercontainer", "secure_storage"),
    ("org.fdroid.fdroid", "utility"),
    ("com.termux", "utility"),
    ("com.google.android", "system"),
    ("com.android", "system"),
    ("com.samsung.android", "system"),
    ("com.sec.android", "system"),
]

@dataclass
class AppInfo:
    package: str
    label: str = ""
    version_code: int = 0
    version_name: str = ""
    permissions: List[str] = field(default_factory=list)

@dataclass
class Incoherence:
    permission: str
    severity: str
    reason: str

@dataclass
class CoherenceResult:
    package: str
    category: str
    score_local: int
    fingerprint: str
    incoherences_local: List[Incoherence]
    evaluated_local_ms: int
    score_llm: Optional[int] = None
    incoherences_llm: List[Incoherence] = field(default_factory=list)
    evaluated_llm_ms: int = 0
    trackers_exodus: List[str] = field(default_factory=list)
    permissions_exodus: List[str] = field(default_factory=list)

    @property
    def source(self) -> str:
        return "local+llm" if self.score_llm is not None else "local"

def now_ms() -> int:
    return int(time.time() * 1000)

def normalize_perm(p: str) -> str:
    p = (p or "").strip()
    prefix = "android.permission."
    return p[len(prefix):] if p.startswith(prefix) else p

def fingerprint_of(perms: Iterable[str]) -> str:
    normalized = sorted({normalize_perm(p) for p in perms if normalize_perm(p)})
    return hashlib.sha256("\n".join(normalized).encode("utf-8")).hexdigest()

def infer_category(package: str) -> str:
    for prefix, category in CATEGORY_PREFIXES:
        if (package or "").startswith(prefix):
            return category
    return "unknown"

def capability(p: str) -> str:
    return PERMISSION_CAPABILITIES.get(p, p)

def evaluate_local(app: AppInfo) -> CoherenceResult:
    perms = {normalize_perm(p) for p in app.permissions if normalize_perm(p)}
    category = infer_category(app.package)
    rules = EXPECTED_BY_CATEGORY.get(category, EXPECTED_BY_CATEGORY["unknown"])
    suspicious, never = set(rules["suspicious"]), set(rules["never"])
    penalties = 0
    issues: List[Incoherence] = []

    for p in sorted(perms):
        if p in suspicious:
            if p in CRITICAL:
                penalties += P_CRITICAL
                sev = "critical"
            else:
                penalties += P_SUSPICIOUS
                sev = "high"
            issues.append(Incoherence(
                p, sev,
                f"{capability(p)} est atypique pour la catégorie {category}; "
                "présence observée dans le manifeste, usage non prouvé."
            ))

    offensive_count = sum(1 for p in perms if p in OFFENSIVE)
    if offensive_count >= CLUSTER_THRESHOLD:
        penalties += P_CLUSTER
        issues.append(Incoherence(
            "*", "high",
            f"Cumul de {offensive_count} permissions sensibles/offensives; "
            "priorité de revue accrue, sans preuve d'abus."
        ))

    if len(perms) > MANY_THRESHOLD:
        penalties += P_MANY
        issues.append(Incoherence(
            "*", "medium", f"{len(perms)} permissions déclarées (> {MANY_THRESHOLD})."
        ))

    for p in sorted(perms):
        if p in never:
            penalties += P_NEVER
            issues.append(Incoherence(
                p, "critical",
                f"{capability(p)} est classée 'never' pour {category}; "
                "présence observée, usage non prouvé."
            ))

    return CoherenceResult(
        package=app.package,
        category=category,
        score_local=max(0, 100 - penalties),
        fingerprint=fingerprint_of(perms),
        incoherences_local=issues,
        evaluated_local_ms=now_ms(),
    )

def _adb(*args: str, timeout: int = 30) -> str:
    try:
        return subprocess.check_output(["adb", *args], stderr=subprocess.PIPE,
                                       timeout=timeout).decode("utf-8", errors="replace")
    except FileNotFoundError as e:
        raise RuntimeError("adb introuvable; installe Android platform-tools") from e
    except subprocess.CalledProcessError as e:
        raise RuntimeError(e.stderr.decode("utf-8", errors="replace").strip()) from e

def _requested_permissions_from_dumpsys(text: str) -> List[str]:
    lines, out, inside = text.splitlines(), [], False
    for line in lines:
        s = line.strip()
        if s == "requested permissions:":
            inside = True
            continue
        if not inside:
            continue
        if line and not line[0].isspace():
            break
        if not s:
            continue
        if s.endswith(":") and not s.startswith("android.permission."):
            break
        token = s.split()[0].rstrip(",")
        if ".permission." in token:
            out.append(normalize_perm(token))
    return sorted(set(out))

def collect_from_adb(include_system: bool) -> List[AppInfo]:
    _adb("start-server")
    cmd = ["shell", "pm", "list", "packages"] + ([] if include_system else ["-3"])
    raw = _adb(*cmd)
    packages = sorted({line.split("package:", 1)[1].strip()
                       for line in raw.splitlines() if line.startswith("package:")})
    apps: List[AppInfo] = []
    for i, pkg in enumerate(packages, 1):
        print(f"  [adb {i}/{len(packages)}] {pkg}", file=sys.stderr)
        try:
            dump = _adb("shell", "dumpsys", "package", pkg)
        except RuntimeError as e:
            print(f"    ignoré: {e}", file=sys.stderr)
            continue
        vm = re.search(r"\bversionCode=(\d+)", dump)
        vn = re.search(r"\bversionName=([^\s]+)", dump)
        apps.append(AppInfo(
            package=pkg,
            label=pkg,
            version_code=int(vm.group(1)) if vm else 0,
            version_name=vn.group(1) if vn else "",
            permissions=_requested_permissions_from_dumpsys(dump),
        ))
    return apps

def load_from_json(path: Path) -> List[AppInfo]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(data, dict):
        data = data.get("apps", data.get("applications", []))
    if not isinstance(data, list):
        raise ValueError("Le JSON doit contenir une liste d'applications")
    out: List[AppInfo] = []
    for row in data:
        if not isinstance(row, dict):
            continue
        pkg = str(row.get("package") or row.get("package_name") or row.get("packageName") or "").strip()
        if not pkg:
            continue
        perms = row.get("permissions", [])
        if not isinstance(perms, list):
            perms = []
        try:
            vc = int(row.get("version_code", row.get("versionCode", 0)) or 0)
        except (ValueError, TypeError):
            vc = 0
        out.append(AppInfo(pkg, str(row.get("label", "") or ""), vc,
                           str(row.get("version_name", row.get("versionName", "")) or ""),
                           [str(p) for p in perms if p]))
    return out

EXODUS_API = "https://reports.exodus-privacy.eu.org/api"
EXODUS_HTML = "https://reports.exodus-privacy.eu.org/en/reports/{pkg}/latest/"

def _http_get(url: str, accept_json: bool = True, timeout: int = 20) -> Optional[str]:
    req = urllib.request.Request(url, headers={
        "User-Agent": "JournalLocalAIV-CoherenceTool/2",
        "Accept": "application/json" if accept_json else "text/html",
    })
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.read().decode("utf-8", errors="replace")
    except Exception:
        return None

def _find_report_id(node: Any) -> Optional[int]:
    if isinstance(node, dict):
        reports = node.get("reports")
        if isinstance(reports, list) and reports and isinstance(reports[0], dict):
            rid = reports[0].get("id")
            if isinstance(rid, int):
                return rid
        for v in node.values():
            rid = _find_report_id(v)
            if rid is not None:
                return rid
    elif isinstance(node, list):
        for v in node:
            rid = _find_report_id(v)
            if rid is not None:
                return rid
    return None

def exodus_enrich(pkg: str, cache: Dict[str, Any]) -> Tuple[List[str], List[str]]:
    cached = cache.get(pkg)
    if isinstance(cached, dict) and isinstance(cached.get("trackers"), list) \
            and isinstance(cached.get("permissions"), list):
        return list(cached["trackers"]), list(cached["permissions"])

    trackers: List[str] = []
    permissions: List[str] = []
    body = _http_get(f"{EXODUS_API}/search/{urllib.parse.quote(pkg, safe='')}")
    rid = None
    if body:
        try:
            rid = _find_report_id(json.loads(body))
        except json.JSONDecodeError:
            pass
    if rid is not None:
        rep = _http_get(f"{EXODUS_API}/reports/{rid}")
        if rep:
            try:
                data = json.loads(rep)
                for t in data.get("trackers", []) if isinstance(data, dict) else []:
                    if isinstance(t, dict) and t.get("name"):
                        trackers.append(str(t["name"]))
                    elif isinstance(t, str):
                        trackers.append(t)
            except json.JSONDecodeError:
                pass
    html = _http_get(EXODUS_HTML.format(pkg=urllib.parse.quote(pkg, safe="")), accept_json=False)
    if html:
        permissions = sorted({normalize_perm(m.group(0)) for m in
                              re.finditer(r"android\.permission\.[A-Za-z0-9_\.]+", html)})
    trackers = sorted(set(trackers))
    cache[pkg] = {"trackers": trackers, "permissions": permissions, "fetched_at_ms": now_ms()}
    return trackers, permissions

SYSTEM_PROMPT = (
    "Tu audites uniquement la cohérence fonctionnelle entre la catégorie déclarée d'une app Android "
    "et ses permissions de manifeste. Une permission inhabituelle n'est pas une preuve d'abus. "
    "N'invente ni usage réel, ni intention, ni causalité. Réponds uniquement en JSON au format "
    '{"results":[{"package":"...","score":0,"category":"...","incoherences":'
    '[{"permission":"...","severity":"critical|high|medium|low","reason":"..."}]}]}.'
)

def _post_json(url: str, payload: Dict[str, Any], headers: Dict[str, str], timeout: int = 60) -> Optional[Dict[str, Any]]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    for attempt, delay in enumerate((1, 2, 4), 1):
        req = urllib.request.Request(url, data=body,
            headers={"Content-Type": "application/json", **headers}, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:
            print(f"  LLM tentative {attempt}/3 échouée: {e}", file=sys.stderr)
            if attempt < 3:
                time.sleep(delay)
    return None

def _extract_json(text: str) -> str:
    text = (text or "").strip()
    if text.startswith("```"):
        text = re.sub(r"^```[A-Za-z0-9_-]*\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    a, b = text.find("{"), text.rfind("}")
    return text[a:b+1] if 0 <= a < b else text

def _openai_text(data: Dict[str, Any]) -> str:
    if isinstance(data.get("output_text"), str):
        return data["output_text"]
    chunks: List[str] = []
    for item in data.get("output", []) if isinstance(data.get("output"), list) else []:
        if not isinstance(item, dict):
            continue
        for part in item.get("content", []) if isinstance(item.get("content"), list) else []:
            if isinstance(part, dict) and isinstance(part.get("text"), str):
                chunks.append(part["text"])
    return "\n".join(chunks)

def _parse_llm(parsed: Any, allowed: set) -> Dict[str, dict]:
    out: Dict[str, dict] = {}
    if not isinstance(parsed, dict) or not isinstance(parsed.get("results"), list):
        return out
    for row in parsed["results"]:
        if not isinstance(row, dict):
            continue
        pkg = str(row.get("package", ""))
        if pkg not in allowed:
            continue
        issues = []
        for x in row.get("incoherences", []) if isinstance(row.get("incoherences"), list) else []:
            if not isinstance(x, dict):
                continue
            sev = str(x.get("severity", "medium"))
            if sev not in {"critical", "high", "medium", "low"}:
                sev = "medium"
            issues.append({"permission": str(x.get("permission", ""))[:200],
                           "severity": sev, "reason": str(x.get("reason", ""))[:1000]})
        try:
            score = max(0, min(100, int(row.get("score", 0))))
        except (TypeError, ValueError):
            score = 0
        out[pkg] = {"score": score, "incoherences": issues}
    return out

def llm_evaluate(apps: Sequence[AppInfo], provider: str, model: str,
                 api_key: str, batch_size: int = 20) -> Dict[str, dict]:
    results: Dict[str, dict] = {}
    batch_size = max(1, min(20, batch_size))
    for start in range(0, len(apps), batch_size):
        batch = list(apps[start:start + batch_size])
        allowed = {a.package for a in batch}
        prompt = json.dumps([{"package": a.package, "label": a.label,
                              "category_local": infer_category(a.package),
                              "permissions": sorted({normalize_perm(p) for p in a.permissions})}
                             for a in batch], ensure_ascii=False)
        parsed = None
        if provider == "openai":
            data = _post_json("https://api.openai.com/v1/responses", {
                "model": model,
                "input": [{"role": "system", "content": SYSTEM_PROMPT},
                          {"role": "user", "content": prompt}],
            }, {"Authorization": f"Bearer {api_key}"})
            if data:
                try:
                    parsed = json.loads(_extract_json(_openai_text(data)))
                except Exception as e:
                    print(f"  parse OpenAI ignoré: {e}", file=sys.stderr)
        elif provider == "anthropic":
            data = _post_json("https://api.anthropic.com/v1/messages", {
                "model": model, "max_tokens": 4096, "system": SYSTEM_PROMPT,
                "messages": [{"role": "user", "content": prompt}],
            }, {"x-api-key": api_key, "anthropic-version": "2023-06-01"})
            if data:
                try:
                    parsed = json.loads(_extract_json(data["content"][0]["text"]))
                except Exception as e:
                    print(f"  parse Anthropic ignoré: {e}", file=sys.stderr)
        else:
            raise ValueError("provider inconnu")
        if parsed:
            results.update(_parse_llm(parsed, allowed))
    return results

def load_ip_ranges(path: Path) -> List[Tuple[ipaddress._BaseNetwork, str, str, str]]:
    out = []
    if not path.exists():
        return out
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        p = [x.strip() for x in line.split("|")]
        if len(p) < 4:
            continue
        try:
            out.append((ipaddress.ip_network(p[0], strict=False), p[1], p[2], p[3]))
        except ValueError:
            pass
    return out

def correlate_ips(ranges, journal: Optional[Path]) -> Dict[str, dict]:
    # Seulement table explicite ip+package; aucune attribution inventée.
    if not journal or not journal.exists():
        return {}
    try:
        conn = sqlite3.connect(str(journal))
        tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if "ips" not in tables:
            conn.close(); return {}
        cols = {r[1] for r in conn.execute("PRAGMA table_info(ips)")}
        if not {"ip", "package"}.issubset(cols):
            conn.close(); return {}
        rows = conn.execute("SELECT ip, package FROM ips WHERE ip IS NOT NULL AND package IS NOT NULL").fetchall()
        conn.close()
    except sqlite3.Error:
        return {}
    out: Dict[str, dict] = {}
    for ip_s, pkg in rows:
        try:
            ip = ipaddress.ip_address(ip_s)
        except ValueError:
            continue
        for net, name, cat, conf in ranges:
            if ip in net:
                out.setdefault(pkg, {}).setdefault("ips", []).append({
                    "ip": ip_s, "range": str(net), "service": name,
                    "category": cat, "confidence": conf,
                    "scope": "Correspondance CIDR seulement; pas une preuve du service exact."
                })
                break
    return out

def write_output(results: Sequence[CoherenceResult], ip_corr: Dict[str, dict], path: Path) -> None:
    payload = {
        "schema": SCHEMA,
        "engine": ENGINE,
        "generated_at_ms": now_ms(),
        "notice": "Indice de cohérence observée; ce n'est pas un verdict de sécurité.",
        "policy": {"suspicious": P_SUSPICIOUS, "critical": P_CRITICAL,
                   "cluster": P_CLUSTER, "many": P_MANY, "never": P_NEVER},
        "apps": [],
    }
    for r in sorted(results, key=lambda x: x.package):
        row = {
            "package": r.package, "category": r.category,
            "score_local": r.score_local, "score_llm": r.score_llm,
            "source": r.source, "fingerprint_sha256": r.fingerprint,
            "incoherences_local": [asdict(x) for x in r.incoherences_local],
            "incoherences_llm": [asdict(x) for x in r.incoherences_llm],
            "trackers_exodus": r.trackers_exodus,
            "permissions_exodus": r.permissions_exodus,
            "evaluated_local_ms": r.evaluated_local_ms,
            "evaluated_llm_ms": r.evaluated_llm_ms,
        }
        if r.package in ip_corr:
            row["ip_correlation"] = ip_corr[r.package]
        payload["apps"].append(row)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"✓ {path} ({len(results)} apps)", file=sys.stderr)

def self_test() -> None:
    assert evaluate_local(AppInfo("com.accuweather.demo", permissions=["INTERNET", "CALL_PHONE"])).score_local < 65
    assert evaluate_local(AppInfo("com.accuweather.demo", permissions=["INTERNET", "ACCESS_NETWORK_STATE"])).score_local == 100
    assert evaluate_local(AppInfo("com.google.android.gm", permissions=["CALL_PHONE", "WRITE_CALL_LOG", "GRANT_RUNTIME_PERMISSIONS"])).score_local < 30
    assert evaluate_local(AppInfo("org.example.unknown", permissions=["CALL_PHONE"])).score_local == 100
    assert evaluate_local(AppInfo("com.google.android.gm", permissions=["INSTALL_PACKAGES"])).score_local == 80
    assert fingerprint_of(["CALL_PHONE", "android.permission.INTERNET"]) == fingerprint_of(["INTERNET", "CALL_PHONE", "CALL_PHONE"])
    print("✓ self-test: 6/6", file=sys.stderr)

def main(argv: Optional[List[str]] = None) -> int:
    p = argparse.ArgumentParser(description="Générateur hors-APK de cohérence AIV")
    src = p.add_mutually_exclusive_group()
    src.add_argument("--collect", action="store_true")
    src.add_argument("--from-json", type=Path)
    p.add_argument("--include-system", action="store_true")
    p.add_argument("--evaluate", action="store_true")
    p.add_argument("--llm", action="store_true")
    p.add_argument("--provider", choices=["anthropic", "openai"], default="anthropic")
    p.add_argument("--model")
    p.add_argument("--api-key-env")
    p.add_argument("--batch-size", type=int, default=20)
    p.add_argument("--exodus", action="store_true")
    p.add_argument("--cache", type=Path, default=Path("tools/.cache_exodus.json"))
    p.add_argument("--ip-ranges", type=Path, default=Path("tools/known_ip_ranges.txt"))
    p.add_argument("--journal", type=Path)
    p.add_argument("--output", type=Path, default=Path("ai_verdicts.json"))
    p.add_argument("--self-test", action="store_true")
    a = p.parse_args(argv)

    if a.self_test:
        self_test()
        if not a.collect and not a.from_json:
            return 0
    if not a.collect and not a.from_json:
        p.error("fournis --collect ou --from-json, ou --self-test")

    apps = collect_from_adb(a.include_system) if a.collect else load_from_json(a.from_json)
    results = [evaluate_local(x) for x in apps] if a.evaluate else [
        CoherenceResult(x.package, infer_category(x.package), 100, fingerprint_of(x.permissions), [], 0)
        for x in apps
    ]
    by_pkg = {r.package: r for r in results}

    if a.llm:
        env = a.api_key_env or ("OPENAI_API_KEY" if a.provider == "openai" else "ANTHROPIC_API_KEY")
        key = os.environ.get(env)
        if not key:
            raise RuntimeError(f"clé API absente: variable {env}")
        model = a.model or ("gpt-5.6-luna" if a.provider == "openai" else "claude-sonnet-4-5")
        stamp = now_ms()
        for pkg, row in llm_evaluate(apps, a.provider, model, key, a.batch_size).items():
            if pkg not in by_pkg:
                continue
            r = by_pkg[pkg]
            r.score_llm = row["score"]
            r.evaluated_llm_ms = stamp
            r.incoherences_llm = [Incoherence(x["permission"], x["severity"], x["reason"])
                                   for x in row["incoherences"]]

    if a.exodus:
        cache: Dict[str, Any] = {}
        if a.cache.exists():
            try:
                raw = json.loads(a.cache.read_text(encoding="utf-8"))
                if isinstance(raw, dict): cache = raw
            except Exception:
                pass
        for i, r in enumerate(results, 1):
            print(f"  [Exodus {i}/{len(results)}] {r.package}", file=sys.stderr)
            r.trackers_exodus, r.permissions_exodus = exodus_enrich(r.package, cache)
            if i < len(results): time.sleep(0.35)
        a.cache.parent.mkdir(parents=True, exist_ok=True)
        a.cache.write_text(json.dumps(cache, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    corr = correlate_ips(load_ip_ranges(a.ip_ranges), a.journal) if a.journal else {}
    write_output(results, corr, a.output)
    return 0

if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
    except Exception as e:
        print(f"ERREUR: {e}", file=sys.stderr)
        raise SystemExit(2)
