"""Capture the four tabs using bounds from Android's UI tree, not screen guesses."""
import pathlib
import re
import subprocess
import xml.etree.ElementTree as ET

OUT = pathlib.Path("qa"); OUT.mkdir(exist_ok=True)
def adb(*args):
    return subprocess.check_output(["adb", *args])

adb("shell", "pm", "clear", "fr.erick.threeai")
adb("shell", "am", "start", "-W", "-n", "fr.erick.threeai/.MainActivity")
for name, label in [("chat", None), ("memoire", "Mémoire"), ("reglages", "Réglages"), ("acces", "Accès")]:
    if label:
        found = None
        for attempt in range(2):
            adb("shell", "uiautomator", "dump", "/sdcard/window.xml")
            data = adb("shell", "cat", "/sdcard/window.xml")
            root = ET.fromstring(data)
            found = next((node for node in root.iter("node") if node.attrib.get("text") == label), None)
            if found is not None: break
        if found is None: raise RuntimeError("Tab missing: " + label)
        x1,y1,x2,y2 = map(int, re.findall(r"\d+", found.attrib["bounds"]))
        adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
    adb("shell", "uiautomator", "dump", "/sdcard/window.xml")
    (OUT / (name + ".xml")).write_bytes(adb("shell", "cat", "/sdcard/window.xml"))
    (OUT / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))
adb("shell", "am", "force-stop", "fr.erick.threeai")
print("Four native tabs captured successfully.")
