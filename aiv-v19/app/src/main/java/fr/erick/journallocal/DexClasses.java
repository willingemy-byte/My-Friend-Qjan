package fr.erick.journallocal;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reads class definitions, not arbitrary strings, from standard DEX 035–040. */
public final class DexClasses {
    private static long u32(byte[] b,long p)throws IOException{
        if(p<0||p+4>b.length)throw new IOException("DEX offset");int i=(int)p;
        return (b[i]&255L)|((b[i+1]&255L)<<8)|((b[i+2]&255L)<<16)|((b[i+3]&255L)<<24);
    }
    public static Set<String> read(byte[] b)throws IOException{
        if(b.length<112||b[0]!='d'||b[1]!='e'||b[2]!='x'||b[3]!='\n'||b[7]!=0)throw new IOException("DEX header");
        String version=new String(b,4,3,StandardCharsets.US_ASCII);
        if(!Arrays.asList("035","036","037","038","039","040").contains(version))throw new IOException("DEX version unsupported");
        if(u32(b,40)!=0x12345678L||u32(b,32)!=b.length||u32(b,36)!=112)throw new IOException("DEX layout");
        long strings=u32(b,56),stringOff=u32(b,60),types=u32(b,64),typeOff=u32(b,68),count=u32(b,96),classOff=u32(b,100);
        if(count>250000||strings>2000000||types>1000000||classOff+count*32>b.length||stringOff+strings*4>b.length||typeOff+types*4>b.length)throw new IOException("DEX bounds");
        Set<String> names=new HashSet<>();for(long i=0;i<count;i++){
            long type=u32(b,classOff+i*32);if(type>=types)throw new IOException("DEX type");
            long str=u32(b,typeOff+type*4);if(str>=strings)throw new IOException("DEX string");
            long offset=u32(b,stringOff+str*4);if(offset>=b.length)throw new IOException("DEX offset");int at=(int)offset,n=0;
            while(true){if(at>=b.length||n++==5)throw new IOException("DEX uleb");if((b[at++]&128)==0)break;}
            int start=at;while(at<b.length&&b[at]!=0&&at-start<=2048)at++;
            if(at>=b.length||at-start>2048)throw new IOException("DEX class length");
            String s=new String(b,start,at-start,StandardCharsets.UTF_8);
            if(s.startsWith("L")&&s.endsWith(";"))names.add(s.substring(1,s.length()-1));
        }return names;
    }
}
