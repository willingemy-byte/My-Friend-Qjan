package fr.erick.threeai;

import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.*;
import org.mozilla.geckoview.*;

@RunWith(AndroidJUnit4.class)
public class SettingsBrowserTest {
    private View find(View v,String label){if(v instanceof TextView&&label.equals(((TextView)v).getText().toString()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View match=find(g.getChildAt(i),label);if(match!=null)return match;}}return null;}
    private void edits(View v,List<EditText> fields){if(v instanceof EditText)fields.add((EditText)v);if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)edits(g.getChildAt(i),fields);}}
    private List<EditText> fields(MainActivity a){List<EditText> result=new ArrayList<>();edits(a.getWindow().getDecorView(),result);return result;}
    private void tap(MainActivity a,String title){View v=find(a.getWindow().getDecorView(),title);assertNotNull(title,v);v.performClick();}
    private void reset(Context c)throws Exception{c.getSharedPreferences("settings",0).edit().clear().commit();new SecretStore(c).set("settings_draft_key","");}
    @Test public void unfinishedConfigurationSurvivesTabsRecreationAndFreshLaunch()throws Exception{
        Context c=ApplicationProvider.getApplicationContext();reset(c);String endpoint="https://custom.example.test/compatible-mode/v1";
        try{
            try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
                scenario.onActivity(a->{tap(a,"Réglages");List<EditText> f=fields(a);f.get(1).setText(endpoint);f.get(2).setText("");f.get(3).setText("synthetic-draft-secret");tap(a,"Chat");tap(a,"Réglages");assertEquals(endpoint,fields(a).get(1).getText().toString());assertEquals("synthetic-draft-secret",fields(a).get(3).getText().toString());});
                scenario.recreate();scenario.onActivity(a->{assertEquals(endpoint,fields(a).get(1).getText().toString());assertEquals("",fields(a).get(2).getText().toString());assertEquals("synthetic-draft-secret",fields(a).get(3).getText().toString());});
            }
            try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){scenario.onActivity(a->{tap(a,"Réglages");assertEquals(endpoint,fields(a).get(1).getText().toString());assertEquals("synthetic-draft-secret",fields(a).get(3).getText().toString());});}
            assertFalse(c.getSharedPreferences("secrets",0).getAll().toString().contains("synthetic-draft-secret"));
            assertFalse(c.getSharedPreferences("settings",0).getAll().toString().contains("synthetic-draft-secret"));
            assertEquals("",new SecretStore(c).get("model:"+endpoint));
        }finally{reset(c);}
    }
    @Test public void saveKeyBeforeModelThenActivateWithoutProviderSelector()throws Exception{
        Context c=ApplicationProvider.getApplicationContext();reset(c);String endpoint="https://custom.example.test/v1";SecretStore secrets=new SecretStore(c);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{tap(a,"Réglages");List<EditText> f=fields(a);assertNotNull(find(a.getWindow().getDecorView(),"Navigateur intégré"));f.get(1).setText(endpoint);f.get(2).setText("");f.get(3).setText("synthetic-save-secret");tap(a,"Enregistrer les réglages");assertEquals("",c.getSharedPreferences("settings",0).getString("model_base",""));});
            assertEquals("synthetic-save-secret",secrets.get("model:"+endpoint));
            scenario.onActivity(a->{tap(a,"Mémoire");tap(a,"Réglages");fields(a).get(2).setText("deepseek-custom");tap(a,"Enregistrer les réglages");assertEquals("",fields(a).get(3).getText().toString());});
            scenario.recreate();scenario.onActivity(a->{assertEquals(endpoint,fields(a).get(1).getText().toString());assertEquals("deepseek-custom",fields(a).get(2).getText().toString());assertFalse(c.getSharedPreferences("settings",0).contains("settings_draft_model_base"));});
            assertEquals("synthetic-save-secret",secrets.get("model:"+endpoint));assertEquals("",secrets.get("settings_draft_key"));assertEquals("",secrets.get("model:https://ollama.com/v1"));
        }finally{reset(c);secrets.set("model:"+endpoint,"");}
    }
    @Test public void geckoRendersJavascriptAndKeepsSessionAcrossRecreation()throws Exception{
        Context c=ApplicationProvider.getApplicationContext();new SecretStore(c).set("browser_session","");
        ExecutorService server=Executors.newSingleThreadExecutor();
        try(ServerSocket listener=new ServerSocket(0)){
            server.submit(()->{try(Socket client=listener.accept()){
                BufferedReader in=new BufferedReader(new InputStreamReader(client.getInputStream()));String line;while((line=in.readLine())!=null&&!line.isEmpty()){}
                byte[] page="<!doctype html><title>Initial</title><h1>3AI navigateur natif</h1><script>document.title='3AI_GECKO_JS_OK';</script>".getBytes("UTF-8");
                OutputStream out=client.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+page.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));out.write(page);out.flush();
            }catch(Exception e){throw new RuntimeException(e);}});
            Intent intent=new Intent(c,BrowserActivity.class).putExtra("url","about:blank");CountDownLatch rendered=new CountDownLatch(1);final GeckoSession[] original=new GeckoSession[1];
            try(ActivityScenario<BrowserActivity> scenario=ActivityScenario.launch(intent)){
                scenario.onActivity(a->{assertTrue(a.engineView() instanceof GeckoView);original[0]=a.currentSession();a.currentSession().setContentDelegate(new GeckoSession.ContentDelegate(){@Override public void onTitleChange(GeckoSession session,String title){if("3AI_GECKO_JS_OK".equals(title))rendered.countDown();}});a.navigate("http://127.0.0.1:"+listener.getLocalPort()+"/");});
                assertTrue("Gecko must render HTML and execute JavaScript",rendered.await(30,TimeUnit.SECONDS));
                scenario.recreate();scenario.onActivity(a->{assertSame(original[0],a.currentSession());assertTrue(a.currentSession().isOpen());assertNotNull(a.engineView().getSession());});
            }
        }finally{server.shutdownNow();new SecretStore(c).set("browser_session","");}
    }
}
