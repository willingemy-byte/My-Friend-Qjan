package fr.erick.threeai;

import android.util.Base64;
import org.json.*;
import javax.net.ssl.HttpsURLConnection;
import java.net.*;
import java.io.*;
import java.util.*;

/** ASR and TTS are separate from the user's chosen chat model. */
final class AlibabaSpeech {
    private final ApiClient api=new ApiClient();
    private volatile HttpsURLConnection download;
    void cancel(){api.cancel();HttpsURLConnection c=download;if(c!=null)c.disconnect();}
    static String compatible(String base)throws Exception{
        String clean=ApiClient.base(base);String host=new URI(clean).getHost();
        if(!host.endsWith(".maas.aliyuncs.com")&&!host.equals("dashscope-intl.aliyuncs.com")&&!host.equals("dashscope.aliyuncs.com"))throw new IOException("Configurer une adresse Alibaba Model Studio pour la voix Alibaba.");
        return ApiClient.origin(clean)+"/compatible-mode/v1";
    }
    String transcribe(String base,String key,byte[] wav)throws Exception{
        if(key.isEmpty())throw new IOException("Clé Alibaba absente pour la voix.");
        JSONObject part=new JSONObject().put("type","input_audio").put("input_audio",new JSONObject().put("data","data:audio/wav;base64,"+Base64.encodeToString(wav,Base64.NO_WRAP)));
        JSONObject body=new JSONObject().put("model","qwen3-asr-flash").put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content",new JSONArray().put(part)))).put("stream",false).put("asr_options",new JSONObject().put("enable_itn",true));
        JSONObject root=new JSONObject(api.request(compatible(base),"/chat/completions","POST",body,Collections.singletonMap("Authorization","Bearer "+key)));
        String text=root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content","").trim();if(text.isEmpty())throw new IOException("Alibaba n’a pas retourné de transcription. Réessayer avec une phrase courte.");return text;
    }
    File synthesize(String base,String key,String voice,String text,File directory)throws Exception{
        if(text.length()>600)throw new IOException("Segment vocal trop long.");if(key.isEmpty())throw new IOException("Clé Alibaba absente pour la voix.");
        JSONObject body=new JSONObject().put("model","qwen3-tts-flash").put("input",new JSONObject().put("text",text).put("voice",voice).put("language_type","French"));
        JSONObject root=new JSONObject(api.request(ApiClient.origin(compatible(base)),"/api/v1/services/aigc/multimodal-generation/generation","POST",body,Collections.singletonMap("Authorization","Bearer "+key)));
        JSONObject audio=root.getJSONObject("output").getJSONObject("audio");String link=audio.optString("url","");URI uri=new URI(link);
        // Some documented responses contain an HTTP OSS URL. Upgrade the same signed URL to TLS.
        if("http".equals(uri.getScheme())&&uri.getHost()!=null&&uri.getHost().endsWith(".aliyuncs.com"))link="https"+link.substring(4);
        uri=new URI(link);if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null||uri.getHost()==null||!uri.getHost().endsWith(".aliyuncs.com"))throw new IOException("Adresse du fichier vocal non prise en charge.");
        File file=File.createTempFile("threeai-voice-",".wav",directory);boolean saved=false;
        HttpsURLConnection c=(HttpsURLConnection)new URL(link).openConnection();download=c;
        try{c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(30000);
            if(c.getResponseCode()!=200)throw new IOException("Fichier vocal Alibaba indisponible.");
            try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(file)){byte[] buffer=new byte[8192];int n,total=0;while((n=in.read(buffer))!=-1){total+=n;if(total>8*1024*1024)throw new IOException("Fichier vocal trop volumineux.");out.write(buffer,0,n);}}
            saved=true;return file;
        }finally{c.disconnect();if(download==c)download=null;if(!saved)file.delete();}
    }
}
