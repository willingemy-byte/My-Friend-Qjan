package fr.erick.threeai;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import org.json.JSONObject;
import okhttp3.*;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Foreground ASR only. The selected chat model remains independent. */
final class AlibabaRealtimeAsr {
    interface Listener {void ready();void partial(String text);void done(String text);void error(String message);}
    private final OkHttpClient client=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS).pingInterval(20,TimeUnit.SECONDS).build();
    private final Handler timer=new Handler(Looper.getMainLooper());
    private Session current;
    static String endpoint(String base)throws Exception{
        String origin=ApiClient.origin(AlibabaSpeech.compatible(base));
        return "wss"+origin.substring(5)+"/api-ws/v1/realtime?model=qwen3-asr-flash-realtime";
    }
    static JSONObject configuration()throws Exception{
        return event("session.update").put("session",new JSONObject().put("input_audio_format","pcm").put("sample_rate",16000)
            .put("input_audio_transcription",new JSONObject().put("language","fr"))
            .put("turn_detection",new JSONObject().put("type","server_vad").put("threshold",0.2).put("silence_duration_ms",1200)));
    }
    private static JSONObject event(String type)throws Exception{return new JSONObject().put("event_id",UUID.randomUUID().toString()).put("type",type);}
    synchronized void start(String base,String key,Listener listener)throws Exception{
        cancel();if(key==null||key.trim().isEmpty())throw new IOException("Ajouter la clé Alibaba dans Réglages avant la dictée.");
        Session session=new Session(listener);current=session;
        Request request=new Request.Builder().url(endpoint(base)).header("Authorization","Bearer "+key.trim()).header("OpenAI-Beta","realtime=v1").build();
        session.timeout(15000,"Connexion vocale trop longue. Réessayer; le brouillon est conservé.");
        session.socket=client.newWebSocket(request,session);
    }
    synchronized void append(byte[] pcm){if(current!=null)current.append(pcm);}
    synchronized void finish(){if(current!=null)current.finish();}
    synchronized void cancel(){if(current!=null){current.cancel();current=null;}}
    private final class Session extends WebSocketListener {
        final Listener listener;final RealtimeTranscript transcript=new RealtimeTranscript();
        WebSocket socket;boolean closed,ready,finishing;Runnable deadline;
        Session(Listener listener){this.listener=listener;}
        synchronized void timeout(int milliseconds,String message){clearTimeout();deadline=()->fail(message);timer.postDelayed(deadline,milliseconds);}
        synchronized void clearTimeout(){if(deadline!=null)timer.removeCallbacks(deadline);deadline=null;}
        synchronized void cancel(){closed=true;clearTimeout();if(socket!=null)socket.cancel();}
        synchronized void fail(String message){if(closed)return;closed=true;clearTimeout();if(socket!=null)socket.cancel();listener.error(message);}
        private boolean send(JSONObject value){if(socket==null||!socket.send(value.toString())){fail("Connexion vocale interrompue. Le texte déjà reconnu reste dans le brouillon.");return false;}return true;}
        synchronized void append(byte[] pcm){
            if(closed||!ready||finishing)return;
            if(socket.queueSize()>128000){fail("Connexion trop lente pour la voix en direct. Le brouillon est conservé.");return;}
            try{send(event("input_audio_buffer.append").put("audio",Base64.encodeToString(pcm,Base64.NO_WRAP)));}catch(Exception e){fail("Envoi audio impossible. Le brouillon est conservé.");}
        }
        synchronized void finish(){if(closed||finishing)return;finishing=true;try{if(send(event("session.finish")))timeout(15000,"La transcription finale n’est pas arrivée. Le texte affiché reste un brouillon à vérifier.");}catch(Exception e){fail("Fin de dictée impossible. Le brouillon est conservé.");}}
        @Override public synchronized void onOpen(WebSocket ws,Response response){
            socket=ws;if(closed){ws.cancel();return;}try{send(configuration());}catch(Exception e){fail("Configuration vocale impossible.");}
        }
        @Override public synchronized void onMessage(WebSocket ws,String raw){
            if(closed)return;
            try{
                JSONObject value=new JSONObject(raw);String type=value.optString("type");
                if(type.equals("session.updated")&&!ready&&!finishing){ready=true;clearTimeout();listener.ready();}
                else if(type.equals("conversation.item.input_audio_transcription.text")||type.equals("conversation.item.input_audio_transcription.completed")){listener.partial(transcript.accept(value));}
                else if(type.equals("session.finished")){closed=true;clearTimeout();ws.close(1000,"Finished");String text=transcript.text();if(!transcript.finalized()&&!text.trim().isEmpty())listener.error("Transcription incomplète. Le texte affiché reste un brouillon à vérifier.");else if(text.trim().isEmpty())listener.error("Aucune parole transcrite. Vérifier le micro et réessayer.");else listener.done(text);}
                else if(type.equals("error")||type.equals("conversation.item.input_audio_transcription.failed")){fail("Alibaba a refusé la transcription en direct. Vérifier le modèle vocal, la région et les droits du compte. Le brouillon est conservé.");}
            }catch(Exception e){fail("Réponse vocale invalide. Le brouillon est conservé.");}
        }
        @Override public synchronized void onFailure(WebSocket ws,Throwable error,Response response){fail(response==null?"Connexion vocale interrompue. Le brouillon est conservé.":"Voix en direct : HTTP "+response.code()+". Vérifier la région, la clé et l’accès au modèle vocal.");}
        @Override public synchronized void onClosed(WebSocket ws,int code,String reason){if(!closed)fail("Connexion vocale terminée avant la transcription finale. Le brouillon est conservé.");}
        @Override public synchronized void onClosing(WebSocket ws,int code,String reason){ws.close(code,"");}
    }
}
