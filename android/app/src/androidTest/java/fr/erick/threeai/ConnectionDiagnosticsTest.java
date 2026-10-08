package fr.erick.threeai;

import android.content.Context;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONArray;
import javax.net.ssl.HttpsURLConnection;
import java.net.*;
import java.io.*;
import java.security.cert.Certificate;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ConnectionDiagnosticsTest {
    static class Reply extends HttpsURLConnection {
        final int status; boolean errorBodyRead;
        Reply(URL url,int status){super(url);this.status=status;}
        public void connect(){} public void disconnect(){} public boolean usingProxy(){return false;}
        public String getCipherSuite(){return "TEST";}
        public Certificate[] getLocalCertificates(){return null;}
        public Certificate[] getServerCertificates(){return null;}
        public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
        public int getResponseCode()throws IOException{if(status<0)throw new UnknownHostException("not reachable");return status;}
        public String getContentType(){return status==403?"text/html":"application/json";}
        public InputStream getErrorStream(){errorBodyRead=true;return new ByteArrayInputStream("echoed-private-key-and-message".getBytes());}
    }
    @Test public void serverRefusalsKeepExactStatusWithoutLeakingSecrets()throws Exception{
        for(int code:new int[]{401,403}){
            Reply reply=new Reply(new URL("https://ollama.com/v1/chat/completions"),code);
            ApiClient api=new ApiClient(url->reply);
            try{api.chat("https://ollama.com/v1","gemma4:31b","synthetic-private-key",new JSONArray(),0,128);fail("Expected HTTP refusal");}
            catch(ApiClient.HttpFailure e){assertEquals(code,e.code);assertTrue(e.getMessage().contains("https://ollama.com"));assertTrue(e.getMessage().contains("HTTP "+code));assertFalse(e.getMessage().contains("synthetic-private-key"));assertFalse(e.getMessage().contains("echoed-private"));assertEquals(code==403,e.getMessage().contains("Réponse HTML"));}
            assertFalse(reply.errorBodyRead);assertFalse(reply.getInstanceFollowRedirects());assertEquals("Bearer synthetic-private-key",reply.getRequestProperty("Authorization"));
        }
    }
    @Test public void dnsFailureAndMissingKeyAreDistinctFromHttpRefusal()throws Exception{
        final int[] opened={0};ApiClient api=new ApiClient(url->{opened[0]++;return new Reply(url,-1);});
        try{api.chat("https://ollama.com/v1","gemma4:31b","",new JSONArray(),0,128);fail("Expected missing key");}catch(IOException e){assertTrue(e.getMessage().contains("Clé absente"));}assertEquals(0,opened[0]);
        try{api.chat("https://ollama.com/v1","gemma4:31b","Bearer wrong-paste",new JSONArray(),0,128);fail("Expected bad paste");}catch(IOException e){assertTrue(e.getMessage().contains("Clé mal collée"));}assertEquals(0,opened[0]);
        try{api.chat("https://ollama.com/v1","gemma4:31b","test-key",new JSONArray(),0,128);fail("Expected DNS failure");}catch(IOException e){assertTrue(e.getMessage().contains("nom du serveur introuvable"));assertFalse(e instanceof ApiClient.HttpFailure);}assertEquals(1,opened[0]);
    }
    private View find(View v,String label){if(v instanceof TextView&&label.equals(((TextView)v).getText().toString()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View result=find(g.getChildAt(i),label);if(result!=null)return result;}}return null;}
    private boolean contains(View v,String text){if(v instanceof TextView&&((TextView)v).getText().toString().contains(text))return true;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)if(contains(g.getChildAt(i),text))return true;}return false;}
    private void edits(View v,List<EditText> result){if(v instanceof EditText)result.add((EditText)v);if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)edits(g.getChildAt(i),result);}}
    @Test public void diagnosticsUseSavedEndpointAndNeverDisplayKey()throws Exception{
        Context c=ApplicationProvider.getApplicationContext();String endpoint="https://ollama.com/v1";SecretStore secrets=new SecretStore(c);
        c.getSharedPreferences("settings",0).edit().clear().putString("model_base",endpoint).putString("model_name","gemma4:31b").commit();secrets.set("model:"+endpoint,"synthetic-secret-never-show");
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{String report=a.connectionReport();assertTrue(report.contains("enregistrée pour cette adresse"));assertTrue(report.contains("gemma4:31b"));assertFalse(report.contains("synthetic-secret-never-show"));View root=a.getWindow().getDecorView();find(root,"Réglages").performClick();List<EditText> fields=new ArrayList<>();edits(root,fields);fields.get(1).setText("https://other.example.test/v1");fields.get(3).setText("new-unsaved-key");find(root,"Tester la connexion").performClick();assertTrue(contains(root,"Modifications non enregistrées"));assertTrue(a.connectionReport().contains("https://ollama.com"));assertFalse(a.connectionReport().contains("new-unsaved-key"));});
            assertEquals(endpoint,c.getSharedPreferences("settings",0).getString("model_base",""));assertEquals("synthetic-secret-never-show",secrets.get("model:"+endpoint));assertEquals("",secrets.get("model:https://other.example.test/v1"));
        }finally{secrets.set("model:"+endpoint,"");c.getSharedPreferences("settings",0).edit().clear().commit();}
    }
}
