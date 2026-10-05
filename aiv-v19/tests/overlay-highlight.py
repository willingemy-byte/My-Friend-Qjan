#!/usr/bin/env python3
"""Run production highlight traversal/drawing requests with synthetic Android nodes.
Checks geometry, labels, node lifetime and timer replacement, not Android rendering.
"""
from pathlib import Path
import ast, os, subprocess, tempfile
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'app/src/main/java/fr/erick/journallocal'
source=(JAVA/'ScreenIntegrityService.java').read_text()
def extract(signature):
    start=source.index(signature);end=source.index('{',start)+1;depth=1
    while depth:depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[start:end]
methods='\n'.join(extract(x) for x in ('private static String clean(', 'static JSONObject node(', 'private WindowManager.LayoutParams params(', 'private boolean highlightStillPresent(', 'private void removeHighlight(', 'private void drawHighlight(', 'private void probeHighlight('))
tree=ast.parse((ROOT/'tests/accessibility-snapshots.py').read_text())
stubs=ast.literal_eval(next(n.value for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='stubs' for t in n.targets)))
stubs['android/graphics/Rect.java']=stubs['android/graphics/Rect.java'].replace('public Rect(){}','public Rect(){}public int width(){return right-left;}public int height(){return bottom-top;}')
s=stubs['android/view/accessibility/AccessibilityNodeInfo.java']
s=s.replace('public String text=', 'public int window=7;public String pkg="example.chat";public String text=')
s=s.replace('copy.text=n.text;', 'copy.window=n.window;copy.pkg=n.pkg;copy.text=n.text;')
s=s.replace('public void recycle()', 'public int getWindowId(){return window;}public CharSequence getPackageName(){return pkg;}public void recycle()')
stubs['android/view/accessibility/AccessibilityNodeInfo.java']=s
stubs['fr/erick/journallocal/EventStore.java']=stubs['fr/erick/journallocal/EventStore.java'].replace('static JSONObject object(', 'static String clockScope(){return "fixture";}static JSONObject object(')
stubs.update({
 'android/graphics/PixelFormat.java':'package android.graphics;public class PixelFormat{public static final int TRANSLUCENT=-3;}',
 'android/graphics/Paint.java':'package android.graphics;public class Paint{public static final int ANTI_ALIAS_FLAG=1;public enum Style{STROKE,FILL}public Paint(int flags){}public void setColor(int c){}public void setStyle(Style s){}public void setStrokeWidth(int w){}public void setTextSize(int s){}}',
 'android/graphics/Canvas.java':'package android.graphics;public class Canvas{public String label="";public void drawRect(int a,int b,int c,int d,Paint p){}public void drawText(String s,int x,int y,Paint p){label=s;}}',
 'android/view/Gravity.java':'package android.view;public class Gravity{public static final int TOP=1,LEFT=2;}',
 'android/view/View.java':'package android.view;import android.graphics.Canvas;public class View{public View(Object context){}public int getWidth(){return 29;}public int getHeight(){return 38;}protected void onDraw(Canvas c){}public void draw(Canvas c){onDraw(c);}}',
 'android/view/WindowManager.java':'''package android.view;import android.graphics.Canvas;public class WindowManager{public View current;public LayoutParams last;public Canvas canvas;public int added;public void addView(View v,LayoutParams p){current=v;last=p;added++;canvas=new Canvas();v.draw(canvas);}public void removeView(View v){if(current!=v)throw new IllegalStateException("wrong view");current=null;}public static class LayoutParams{public static final int TYPE_ACCESSIBILITY_OVERLAY=2032,FLAG_NOT_FOCUSABLE=8,FLAG_LAYOUT_IN_SCREEN=256,FLAG_NOT_TOUCHABLE=16;public int width,height,type,flags,x,y,gravity;public LayoutParams(int w,int h,int t,int f,int format){width=w;height=h;type=t;flags=f;}}}''',
 'android/os/SystemClock.java':'package android.os;public class SystemClock{public static long now=1400;public static long elapsedRealtime(){return now;}}',
 'android/os/Handler.java':'package android.os;import java.util.*;public class Handler{public List<Runnable> timers=new ArrayList<>();public void postDelayed(Runnable r,long ms){timers.add(r);}}',
})
probe=r'''package fr.erick.journallocal;
import java.util.*;import org.json.*;import android.graphics.*;import android.view.*;import android.view.accessibility.*;import android.os.*;
public class HighlightProbe{
 static HighlightProbe active;WindowManager windows=new WindowManager();Handler main=new Handler();View highlight;JSONObject highlightedSource;long highlightedFinding;int highlightGeneration,refreshes;String highlightReason="";AccessibilityNodeInfo root;
 int dp(int n){return n;}void refresh(){refreshes++;}AccessibilityNodeInfo getRootInActiveWindow(){return root==null?null:AccessibilityNodeInfo.obtain(root);}
 __METHODS__
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 public static void main(String[] args){check(OverlayRules.healthy(true,true,true,true,true,true),"operational runtime should be healthy");check(OverlayRules.healthy(true,true,true,true,false,false),"optional VPN must not make runtime unhealthy");check(!OverlayRules.healthy(true,true,false,true,true,true),"failed correlation must make runtime unhealthy");check(!OverlayRules.coverageComplete(true,false,false),"missing visual evidence must keep coverage partial");check(!OverlayRules.coverageComplete(true,true,true),"VPN coverage gap must keep coverage partial");check(OverlayRules.coverageComplete(true,true,false),"complete evidence coverage not recognized");HighlightProbe p=new HighlightProbe();active=p;p.root=new AccessibilityNodeInfo();p.root.clickable=false;AccessibilityNodeInfo button=new AccessibilityNodeInfo();button.text="Partager";button.bounds=new Rect(10,20,110,60);p.root.children.add(button);
  p.probeHighlight();check(p.windows.current!=null&&p.windows.last.x==10&&p.windows.last.y==20&&p.windows.last.width==100&&p.windows.last.height==40,"diagnostic did not frame exact node bounds");check(p.windows.canvas.label.equals("TEST AIV · aucune anomalie")&&p.highlightedFinding==0,"diagnostic invented an anomaly");check((p.windows.last.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)!=0,"highlight blocked input");
  JSONObject frontend=EventStore.object("package_name","example.chat","window_id",7,"element",node(button));p.drawHighlight(frontend,184,false);check(p.highlightedFinding==184&&p.windows.canvas.label.contains("#184"),"finding identifier lost");p.main.timers.get(0).run();check(p.windows.current!=null&&p.highlightedFinding==184,"previous expiry removed newer highlight");p.main.timers.get(1).run();check(p.windows.current==null&&p.highlightedFinding==0,"highlight did not expire");
  button.text="Autre";int added=p.windows.added;p.drawHighlight(frontend,185,false);check(p.windows.added==added,"disappeared element framed");button.text="Partager";p.root.pkg="different.app";check(!p.highlightStillPresent(frontend),"different app framed");p.root.pkg="example.chat";p.root.window=8;check(!p.highlightStillPresent(frontend),"different window framed");p.root.window=7;button.visible=false;check(!p.highlightStillPresent(frontend),"invisible node framed");
  check(AccessibilityNodeInfo.obtained==AccessibilityNodeInfo.recycled,"highlight traversal leaked nodes");p.root=null;p.probeHighlight();check(p.highlightReason.equals("NO_ACCESSIBLE_TREE"),"no-tree coverage hidden");System.out.println("Production highlight geometry/nodes/timers: "+checks+" checks passed");
 }
}'''.replace('__METHODS__',methods)
with tempfile.TemporaryDirectory() as folder:
    root=Path(folder);src=root/'src';classes=root/'classes';classes.mkdir()
    for name,body in stubs.items():
        p=src/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body)
    (src/'fr/erick/journallocal/HighlightProbe.java').write_text(probe)
    jar=os.environ['AIV_JSON_JAR']
    subprocess.run(['javac','-encoding','UTF-8','-cp',jar,'-d',str(classes),*map(str,src.rglob('*.java')),str(JAVA/'OverlayRules.java')],check=True,timeout=30)
    subprocess.run(['java','-cp',str(classes)+os.pathsep+jar,'fr.erick.journallocal.HighlightProbe'],check=True,timeout=30)
