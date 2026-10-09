package fr.erick.threeai;

import java.util.*;

/** Display keeps the original. Only spoken text passes through here. */
final class SpeechText {
    static String clean(String value) {
        return value.replaceAll("(?s)```.*?(?:```|$)", " Un bloc de code est affiché. ")
            .replaceAll("(?s)~~~.*?(?:~~~|$)", " Un bloc de code est affiché. ")
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
    /** Emit complete phrases only, outside code, links and decimal numbers. */
    static int boundary(String text,int start){
        boolean fence=false,inline=false;int brackets=0;
        for(int i=start;i<text.length();i++){
            if(text.startsWith("```",i)||text.startsWith("~~~",i)){fence=!fence;i+=2;if(!fence)return i+1;continue;}
            char c=text.charAt(i);if(fence)continue;if(c=='`'){inline=!inline;continue;}if(inline)continue;
            if(c=='['||c=='(')brackets++;if(c==']'||c==')')brackets=Math.max(0,brackets-1);if(brackets>0)continue;
            boolean separator=c=='.'||c=='!'||c=='?'||c=='\n';
            if(separator&&i+1<text.length()&&Character.isWhitespace(text.charAt(i+1)))return i+1;
            if(i-start>=350&&Character.isWhitespace(c))return i;
        }
        return start;
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

