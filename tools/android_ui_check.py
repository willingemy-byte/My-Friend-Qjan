"""Inspect native chat, keyboard and drawer from actual Android UI bounds."""
import json
import pathlib
import re
import subprocess
import xml.etree.ElementTree as ET

OUT = pathlib.Path("qa"); OUT.mkdir(exist_ok=True)
def adb(*args):
    return subprocess.check_output(["adb", *args])
launcher_recoveries=0
def tree():
    global launcher_recoveries
    for attempt in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/window.xml")
        root=ET.fromstring(adb("shell", "cat", "/sdcard/window.xml"))
        # Recover only the emulator launcher; never dismiss an ANR belonging to 3AI.
        if any(n.attrib.get("text")=="Pixel Launcher isn't responding" for n in root.iter("node")):
            close=next(n for n in root.iter("node") if n.attrib.get("text")=="Close app")
            x1,y1,x2,y2=bounds(close);adb("shell","input","tap",str((x1+x2)//2),str((y1+y2)//2));launcher_recoveries+=1
            adb("shell","am","start","-W","-n","fr.erick.threeai/.MainActivity")
            continue
        return root
    raise RuntimeError("Emulator launcher remains unavailable")
def bounds(node):
    return tuple(map(int,re.findall(r"\d+",node.attrib["bounds"])))
def tap(label):
    for attempt in range(2):
        root=tree()
        node=next((n for n in root.iter("node") if n.attrib.get("text")==label),None)
        if node is not None:
            x1,y1,x2,y2=bounds(node);adb("shell","input","tap",str((x1+x2)//2),str((y1+y2)//2));return
        scroll=next((n for n in root.iter("node") if n.attrib.get("scrollable")=="true"),None)
        if scroll is not None:
            x1,y1,x2,y2=bounds(scroll);adb("shell","input","swipe",str((x1+x2)//2),str(y2-100),str((x1+x2)//2),str(y1+100),"250")
    raise RuntimeError("Control missing: "+label)
def capture(name):
    tree();(OUT/(name+".xml")).write_bytes(adb("shell","cat","/sdcard/window.xml"));(OUT/(name+".png")).write_bytes(adb("exec-out","screencap","-p"))

adb("install","-r","android/app/build/outputs/apk/debug/app-debug.apk")
adb("pull","/sdcard/Android/data/fr.erick.threeai/files/preview-image-test.png",str(OUT/"image-ouverte.png"))
adb("shell","am","force-stop","com.google.android.apps.nexuslauncher")
adb("shell","pm","clear","fr.erick.threeai")
adb("shell","am","start","-W","-n","fr.erick.threeai/.MainActivity")
capture("chat")
for attempt in range(3):
    root=tree();edit=next(n for n in root.iter("node") if n.attrib.get("class")=="android.widget.EditText");x1,y1,x2,y2=bounds(edit);adb("shell","input","tap",str((x1+x2)//2),str((y1+y2)//2));
    root=tree()
    if any(n.attrib.get("package")=="com.google.android.inputmethod.latin" for n in root.iter("node")):break
else: raise RuntimeError("Keyboard did not open")
adb("shell","input","text","Bonjour%ssans%sperdre%sle%schat")
capture("chat-clavier")
root=tree();messages=next(n for n in root.iter("node") if n.attrib.get("content-desc")=="Messages du chat");x1,y1,x2,y2=bounds(messages)
if y2-y1<200: raise RuntimeError("Keyboard leaves too little room for messages: "+str(y2-y1))
if not any(n.attrib.get("text")=="Envoyer" for n in root.iter("node")): raise RuntimeError("Send hidden by keyboard")
(OUT/"keyboard-check.json").write_text(json.dumps({"chat_height_px_with_keyboard":y2-y1,"send_visible":True,"launcher_recoveries":launcher_recoveries},indent=2))
adb("shell","input","keyevent","4")
tap("Menu");capture("panneau");tap("Fermer le panneau")
for name,label in [("memoire","Mémoire"),("reglages","Réglages"),("acces","Accès")]:
    tap("Menu");tap(label);capture(name)
adb("shell","am","force-stop","fr.erick.threeai")
print("Native chat, keyboard, drawer and settings verified.")
