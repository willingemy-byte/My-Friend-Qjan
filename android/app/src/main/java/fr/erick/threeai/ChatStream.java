package fr.erick.threeai;

import org.json.*;
import java.io.*;

/** Bounded SSE parser. Reasoning is never included in the answer or speech. */
final class ChatStream {
    interface Listener { void update(String answer,boolean reasoning); }
    static final class Result {
        final String text,finish;
        Result(String text,String finish){this.text=text;this.finish=finish;}
        boolean complete(){return "stop".equals(finish);}
    }
    static Result read(Reader source,Listener listener)throws Exception {
        StringBuilder line=new StringBuilder(),event=new StringBuilder(),answer=new StringBuilder();String finish="interrupted";
        int size=0,ch;long deadline=System.nanoTime()+300_000_000_000L;boolean done=false;
        while(!done&&(ch=source.read())!=-1){
            if(++size>2*1024*1024||System.nanoTime()>deadline)throw new IOException("Réponse trop longue ou délai total dépassé.");
            if(ch=='\r')continue;
            if(ch!='\n'){line.append((char)ch);if(line.length()>262144)throw new IOException("Trame trop volumineuse.");continue;}
            String s=line.toString();line.setLength(0);
            if(s.startsWith("data:")){if(event.length()>0)event.append('\n');event.append(s.substring(5).trim());}
            else if(s.isEmpty()&&event.length()>0){String data=event.toString();event.setLength(0);
                if("[DONE]".equals(data)){done=true;continue;}
                JSONObject root=new JSONObject(data);if(root.has("error"))throw new IOException("Le serveur a interrompu la génération.");
                JSONArray choices=root.optJSONArray("choices");if(choices==null||choices.length()==0)continue;
                JSONObject choice=choices.getJSONObject(0),delta=choice.optJSONObject("delta");
                if(delta!=null){String part=delta.isNull("content")?"":delta.optString("content","");answer.append(part);listener.update(answer.toString(),part.isEmpty()&&!delta.isNull("reasoning_content")&&delta.has("reasoning_content"));}
                if(!choice.isNull("finish_reason")&&choice.has("finish_reason"))finish=choice.getString("finish_reason");
            }
        }
        if(answer.toString().trim().isEmpty())throw new IOException("Aucune réponse textuelle reçue. Vérifier le modèle et le budget de génération, qui peut inclure sa réflexion.");
        return new Result(answer.toString(),finish);
    }
}
