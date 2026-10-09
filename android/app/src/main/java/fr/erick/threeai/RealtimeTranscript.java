package fr.erick.threeai;

import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** A partial transcript replaces the current sentence; it is not a text delta. */
final class RealtimeTranscript {
    private final Map<String,String> sentences = new LinkedHashMap<>();
    private final java.util.Set<String> completed = new java.util.HashSet<>();
    String accept(JSONObject event) {
        String id=event.optString("item_id"), type=event.optString("type");
        if(id.isEmpty())return text();
        if(type.equals("conversation.item.input_audio_transcription.completed")){
            sentences.put(id,event.optString("transcript"));completed.add(id);
        }else if(type.equals("conversation.item.input_audio_transcription.text")&&!completed.contains(id)){
            sentences.put(id,event.optString("text")+event.optString("stash"));
        }
        return text();
    }
    boolean finalized(){return !sentences.isEmpty()&&completed.containsAll(sentences.keySet());}
    String text(){StringBuilder result=new StringBuilder();for(String sentence:sentences.values()){if(sentence.trim().isEmpty())continue;if(result.length()>0)result.append(' ');result.append(sentence.trim());}return result.toString();}
}
