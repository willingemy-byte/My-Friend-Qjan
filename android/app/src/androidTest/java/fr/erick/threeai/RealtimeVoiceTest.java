package fr.erick.threeai;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RealtimeVoiceTest {
    private JSONObject sentence(String type,String id,String text,String stash)throws Exception{
        return new JSONObject().put("type","conversation.item.input_audio_transcription."+type).put("item_id",id).put("text",text).put("stash",stash).put("transcript",text);
    }
    @Test public void revisedPartialDoesNotDuplicateOrOverwriteFinalSentence()throws Exception{
        RealtimeTranscript state=new RealtimeTranscript();
        assertEquals("Je veu",state.accept(sentence("text","1","Je ","veu")));
        assertEquals("Je veux parler",state.accept(sentence("text","1","Je veux ","parler")));
        assertFalse(state.finalized());
        assertEquals("Je veux parler.",state.accept(sentence("completed","1","Je veux parler.","")));
        assertTrue(state.finalized());
        assertEquals("Je veux parler.",state.accept(sentence("text","1","ancienne hypothèse","")));
        assertEquals("Je veux parler. Sans attendre",state.accept(sentence("text","2","Sans ","attendre")));
        assertFalse(state.finalized());
        assertEquals("Je veux parler. Sans attendre.",state.accept(sentence("completed","2","Sans attendre.","")));
        assertTrue(state.finalized());
        assertEquals("Je veux parler. Sans attendre.",state.accept(sentence("completed","2","Sans attendre.","")));
    }
    @Test public void frenchPcmAndWorkspaceArePreservedAndForeignHostsRejected()throws Exception{
        assertEquals("wss://ws-synthetic.ap-southeast-1.maas.aliyuncs.com/api-ws/v1/realtime?model=qwen3-asr-flash-realtime",AlibabaRealtimeAsr.endpoint("https://ws-synthetic.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1"));
        assertEquals("wss://dashscope-intl.aliyuncs.com/api-ws/v1/realtime?model=qwen3-asr-flash-realtime",AlibabaRealtimeAsr.endpoint("https://dashscope-intl.aliyuncs.com/compatible-mode/v1"));
        for(String url:new String[]{"https://evil.test/v1","http://dashscope.aliyuncs.com/v1","https://maas.aliyuncs.com.evil.test/v1"}){
            try{AlibabaRealtimeAsr.endpoint(url);fail("Foreign endpoint accepted");}catch(java.io.IOException expected){}
        }
        JSONObject config=AlibabaRealtimeAsr.configuration().getJSONObject("session");
        assertEquals("fr",config.getJSONObject("input_audio_transcription").getString("language"));
        assertEquals(16000,config.getInt("sample_rate"));assertEquals("pcm",config.getString("input_audio_format"));
    }
    @Test public void spokenPhrasesStartBeforeWholeAnswerWithoutReadingCode() {
        String answer="Prix : 4.50 dollars. Voici la suite";
        int boundary=SpeechText.boundary(answer,0);
        assertEquals("Prix : 4.50 dollars.",answer.substring(0,boundary));
        assertEquals(boundary,SpeechText.boundary(answer,boundary));
        assertEquals(0,SpeechText.boundary("```java\nsecret();\n",0));
        String code="```java\nsecret();\n``` Puis un commentaire";
        assertFalse(SpeechText.clean(code.substring(0,SpeechText.boundary(code,0))).contains("secret"));
        assertFalse(SpeechText.clean("```java\nsecret();").contains("secret"));
    }
    private View find(View root,String text){if(root instanceof TextView&&text.equals(((TextView)root).getText().toString()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View match=find(((ViewGroup)root).getChildAt(i),text);if(match!=null)return match;}return null;}
    @Test public void visibleTranscriptAndOriginalDraftSurviveInterruptionAndRestart()throws Exception{
        Context context=ApplicationProvider.getApplicationContext();SharedPreferences prefs=context.getSharedPreferences("settings",Context.MODE_PRIVATE);String saved=prefs.getString("draft","");boolean continuous=prefs.getBoolean("conversation_voice",false);
        prefs.edit().putString("draft","Brouillon existant").putBoolean("conversation_voice",false).commit();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(activity->{try{
                java.lang.reflect.Field prefix=MainActivity.class.getDeclaredField("voiceDraftBase");prefix.setAccessible(true);prefix.set(activity,"Brouillon existant");
                java.lang.reflect.Method state=MainActivity.class.getDeclaredMethod("showVoiceState",String.class,int.class);state.setAccessible(true);state.invoke(activity,"Micro actif",70);
                java.lang.reflect.Method preview=MainActivity.class.getDeclaredMethod("showVoicePreview",String.class);preview.setAccessible(true);preview.invoke(activity,"Je veux parler");preview.invoke(activity,"Je veux parler sans attendre.");
                View transcript=find(activity.getWindow().getDecorView(),"Je veux parler sans attendre.");assertNotNull(transcript);assertTrue(transcript.isShown());
                java.lang.reflect.Method error=MainActivity.class.getDeclaredMethod("voiceError",String.class);error.setAccessible(true);error.invoke(activity,"Connexion interrompue");
                assertEquals("Brouillon existant Je veux parler sans attendre.",prefs.getString("draft",""));
            }catch(Exception e){throw new AssertionError(e);}});
            scenario.recreate();scenario.onActivity(activity->assertNotNull(find(activity.getWindow().getDecorView(),"Brouillon existant Je veux parler sans attendre.")));
        }finally{prefs.edit().putString("draft",saved).putBoolean("conversation_voice",continuous).commit();}
    }
}
