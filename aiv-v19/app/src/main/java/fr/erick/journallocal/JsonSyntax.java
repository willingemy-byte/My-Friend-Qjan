package fr.erick.journallocal;
/** Strict JSON grammar; Android JSONObject alone accepts non-JSON syntax. */
final class JsonSyntax {
    private final String s;private int at;private JsonSyntax(String s){this.s=s;}
    static boolean valid(String s){try{JsonSyntax p=new JsonSyntax(s);p.value(0);p.space();return p.at==s.length();}catch(IllegalArgumentException e){return false;}}
    private void bad(){throw new IllegalArgumentException("JSON invalide");}
    private void space(){while(at<s.length()&&" \t\r\n".indexOf(s.charAt(at))>=0)at++;}
    private boolean take(char c){space();if(at<s.length()&&s.charAt(at)==c){at++;return true;}return false;}
    private void need(char c){if(!take(c))bad();}
    private void string(){need('"');boolean end=false;while(at<s.length()){char c=s.charAt(at++);if(c=='"'){end=true;break;}if(c<32)bad();if(c=='\\'){if(at>=s.length())bad();c=s.charAt(at++);if(c=='u'){for(int i=0;i<4;i++)if(at>=s.length()||Character.digit(s.charAt(at++),16)<0)bad();}else if("\"\\/bfnrt".indexOf(c)<0)bad();}}if(!end)bad();}
    private void value(int depth){
        if(depth>64)bad();space();if(at>=s.length())bad();char c=s.charAt(at);if(c=='"'){string();return;}
        if(take('{')){if(take('}'))return;do{string();need(':');value(depth+1);}while(take(','));need('}');return;}
        if(take('[')){if(take(']'))return;do{value(depth+1);}while(take(','));need(']');return;}
        for(String literal:new String[]{"true","false","null"})if(s.startsWith(literal,at)){at+=literal.length();return;}
        int begin=at;while(at<s.length()&&"0123456789+-.eE".indexOf(s.charAt(at))>=0)at++;if(begin==at||!s.substring(begin,at).matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))bad();
    }
}
