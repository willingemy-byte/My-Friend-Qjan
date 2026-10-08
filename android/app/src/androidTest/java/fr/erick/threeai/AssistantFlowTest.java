package fr.erick.threeai;

import android.content.Context;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.util.*;

@RunWith(AndroidJUnit4.class)
public class AssistantFlowTest {
    private View find(View v,String label){if(v instanceof TextView && label.equals(((TextView)v).getText().toString()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View match=find(g.getChildAt(i),label);if(match!=null)return match;}}return null;}
    private void edits(View v,List<EditText> result){if(v instanceof EditText)result.add((EditText)v);if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)edits(g.getChildAt(i),result);}}
    private View root(MainActivity a){return a.getWindow().getDecorView();}
    private void tap(MainActivity a,String label){View v=find(root(a),label);assertNotNull(label,v);v.performClick();}
    @Test public void memorySurvivesNavigationAndRecreation() throws Exception {
        Context c=ApplicationProvider.getApplicationContext();String large=String.join("",Collections.nCopies(18000,"Mémoire unique. "));
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{tap(a,"Mémoire");List<EditText> e=new ArrayList<>();edits(root(a),e);assertEquals(1,e.size());e.get(0).setText(large);tap(a,"Enregistrer");tap(a,"Chat");});
            scenario.recreate();scenario.onActivity(a->{tap(a,"Mémoire");List<EditText> e=new ArrayList<>();edits(root(a),e);assertEquals(large,e.get(0).getText().toString());});
        }
        assertEquals(large,new LocalStore(c).read("memory.txt",""));
    }
    @Test public void modelSwitchKeepsMemoryAndSecretsAreEncrypted() throws Exception {
        Context c=ApplicationProvider.getApplicationContext();LocalStore store=new LocalStore(c);store.write("memory.txt","Souvenir à conserver");SecretStore secrets=new SecretStore(c);
        secrets.set("test-secret","not-a-real-key");assertEquals("not-a-real-key",new SecretStore(c).get("test-secret"));
        assertFalse(c.getSharedPreferences("secrets",0).getString("test-secret","").contains("not-a-real-key"));
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{tap(a,"Réglages");List<EditText> e=new ArrayList<>();edits(root(a),e);e.get(1).setText("https://ecs.example.test/v1");e.get(2).setText("my-model");e.get(3).setText("test-endpoint-key");tap(a,"Enregistrer les réglages");tap(a,"Mémoire");});
        }
        assertEquals("Souvenir à conserver",store.read("memory.txt",""));assertEquals("my-model",c.getSharedPreferences("settings",0).getString("model_name",""));
        assertEquals("test-endpoint-key",secrets.get("model:https://ecs.example.test/v1"));assertEquals("",secrets.get("model:https://ollama.com/v1"));
        c.getSharedPreferences("settings",0).edit().clear().commit();secrets.set("model:https://ecs.example.test/v1","");
    }
    @Test public void sharedReportIsDraftOnly() throws Exception {
        Context c=ApplicationProvider.getApplicationContext();new LocalStore(c).write("conversations.json","[]");c.getSharedPreferences("settings",0).edit().putString("draft","").commit();
        Intent intent=new Intent(c,MainActivity.class).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"Rapport AIV de test");
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(intent)){
            scenario.onActivity(a->{List<EditText> e=new ArrayList<>();edits(root(a),e);assertEquals("Rapport AIV de test",e.get(0).getText().toString());tap(a,"Accès");assertNotNull(find(root(a),"Autoriser Shizuku"));});
        }
        assertEquals("[]",new LocalStore(c).read("conversations.json",""));
    }
    @Test public void budgetRejectsInsteadOfTruncatingMemory() throws Exception {
        org.json.JSONArray h=new org.json.JSONArray().put(LocalStore.message("user","Bonjour"));String memory=String.join("",Collections.nCopies(10000,"long memory "));
        try{LocalStore.payload(h,"prompt",memory,true,null,2048,128);fail("Expected budget error");}catch(java.io.IOException expected){}
        assertTrue(LocalStore.payload(h,"prompt",memory,true,null,262144,128).getJSONObject(0).getString("content").endsWith(memory));
    }
    private boolean contains(View v,String part){if(v instanceof TextView&&((TextView)v).getText().toString().contains(part))return true;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)if(contains(g.getChildAt(i),part))return true;}return false;}
    @Test public void accessStatusRemainsVisibleWithoutShizuku() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{tap(a,"Accès");assertTrue(contains(root(a),"Microphone :"));assertTrue(contains(root(a),"Contacts :"));assertTrue(contains(root(a),"Agenda :"));assertNotNull(find(root(a),"Demander les accès manquants"));assertTrue(contains(root(a),"Réparer les accès de 3AI"));});
            scenario.recreate();scenario.onActivity(a->{assertTrue(contains(root(a),"Contacts :"));assertTrue(contains(root(a),"Agenda :"));});
        }
    }

}
