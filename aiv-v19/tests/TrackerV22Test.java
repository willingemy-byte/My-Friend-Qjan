import fr.erick.journallocal.*;
import java.util.*;
import java.io.*;
import java.nio.*;
import java.util.zip.*;
public final class TrackerV22Test {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static byte[] dex(boolean definition){byte[] data=new byte[172];ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);b.put("dex\n035\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));b.putInt(32,data.length);b.putInt(36,112);b.putInt(40,0x12345678);b.putInt(56,1);b.putInt(60,112);b.putInt(64,1);b.putInt(68,116);b.putInt(96,definition?1:0);b.putInt(100,120);b.putInt(112,152);b.putInt(116,0);b.putInt(120,0);b.position(152);b.put((byte)16);b.put("Lorg/sample/Sdk;\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));return data;}
 public static void main(String[] args)throws Exception{
  TrackerMatcher m=new TrackerMatcher(Arrays.asList(new TrackerMatcher.Signature(1,"Synthetic SDK","tracker\\.example","org\\.sample\\."),new TrackerMatcher.Signature(2,"Shared endpoint","tracker\\.example","")));
  check(m.network("api.TRACKER.example.").size()==2,"multiple upstream candidates preserved");check(m.network("api.tracker.example").get(0).boundary,"domain boundary");
  check(!m.network("nottracker.example").get(0).boundary,"substring is not a verified domain boundary");check(!m.network("tracker.example.evil.test").get(0).boundary,"suffix confusion is partial only");
  for(String h:new String[]{"","203.0.113.5","https://tracker.example/path","tracker.example:443",".tracker.example","tracker..example"})check(m.network(h).isEmpty(),"no inferred hostname: "+h);
  Set<String> classes=DexClasses.read(dex(true));check(classes.contains("org/sample/Sdk"),"class definition parsed");check(m.code(classes).contains(1),"static signature");
  check(m.code(DexClasses.read(dex(false))).isEmpty(),"referenced string alone is not an embedded class definition");
  byte[] corrupt=dex(true);ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN).putInt(100,1000000);try{DexClasses.read(corrupt);throw new AssertionError("bounds rejected");}catch(IOException expected){}
  if(args.length>0){int classesCount=0;try(ZipFile zip=new ZipFile(args[0])){Enumeration<? extends ZipEntry> es=zip.entries();while(es.hasMoreElements()){ZipEntry e=es.nextElement();if(e.getName().matches("classes[0-9]*\\.dex")){ByteArrayOutputStream out=new ByteArrayOutputStream();zip.getInputStream(e).transferTo(out);classesCount+=DexClasses.read(out.toByteArray()).size();}}}check(classesCount>20,"actual built APK classes parsed");System.out.println("Built APK: "+classesCount+" defined classes parsed");}
  System.out.println("PASS network signatures, boundaries, multi-candidates, IP/URL rejection, DEX definitions and bounds");
 }
}
