package fr.erick.threeai;

import java.util.*;

/** Display keeps the original. Only spoken text passes through here. */
final class SpeechText {
    static String clean(String value) {
        return value.replaceAll("(?s)```.*?```", " Un bloc de code est affiché. ")
            .replaceAll("(?s)~~~.*?~~~", " Un bloc de code est affiché. ")
            .replaceAll("!\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
            .replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1")
            .replaceAll("https?://\\S+", " lien ")
            .replaceAll("(?m)^\\s*(?:[-*+] |\\d+[.)] )", "")
            .replaceAll("(?m)^\\s*[#>]+\\s*", "")
            .replaceAll("(?m)^\\s*[-=_|: ]{3,}\\s*$", "")
            .replaceAll("[*_`#<>\\[\\]~«»“”\"]", "")
            .replace('|', ',').replace('(', ',').replace(')', ',')
            .replaceAll("\\n\\s*\\n", ". ").replace('\n', ' ')
            .replaceAll("\\s+", " ").trim();
    }
    static List<String> chunks(String text,int limit) {
        ArrayList<String> result=new ArrayList<>();int start=0;
        while(start<text.length()){
            int end=Math.min(start+limit,text.length());
            if(end<text.length()) {int space=text.lastIndexOf(' ',end);if(space>start+limit/2)end=space;else if(Character.isHighSurrogate(text.charAt(end-1)))end--;}
            result.add(text.substring(start,end).trim());start=end;while(start<text.length()&&Character.isWhitespace(text.charAt(start)))start++;
        }
        return result;
    }
}
