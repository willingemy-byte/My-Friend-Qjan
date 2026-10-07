#!/usr/bin/env python3
from pathlib import Path
import tempfile,subprocess
source=(Path(__file__).parents[1]/'app/src/main/java/fr/erick/journallocal/PersonalBackup.java').read_text()
def method(signature):
 start=source.index(signature);end=source.index('{',start)+1;depth=1
 while depth:depth+=(source[end]=='{')-(source[end]=='}');end+=1
 return source[start:end]
java='import java.net.URI;class BackupInputTest{'+method('static String validateUrl(')+method('static String validateKey(')+'''
 static void noKey(String s){try{validateKey(s);throw new AssertionError("Secret accepted");}catch(IllegalArgumentException expected){}}
 static void noUrl(String s)throws Exception{try{validateUrl(s);throw new AssertionError("Bad URL accepted");}catch(IllegalArgumentException expected){}}
 public static void main(String[]args)throws Exception{
  if(!validateKey("sb_publishable_1234567890abcdef").startsWith("sb_publishable_"))throw new AssertionError();
  for(String s:new String[]{"sb_secret_1234567890abcdef","service_role","eyJhbGciOiJIUzI1NiJ9.payload.sig","postgres://admin:secret@host/db","password","sb_publishable_1234567890abcdef\\nsecret"})noKey(s);
  if(!validateUrl("https://abc123.supabase.co/").equals("https://abc123.supabase.co"))throw new AssertionError();
  for(String s:new String[]{"http://abc.supabase.co","https://abc.supabase.co.evil.test","https://secret@abc.supabase.co","https://abc.supabase.co:443","https://abc.supabase.co?secret=1","https://abc.supabase.co/rest/v1","https://localhost"})noUrl(s);
  System.out.println("PASS 15 backup-input cases: publishable accepted; secrets/JWT/administrator URLs refused; project URL constrained");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'BackupInputTest.java';p.write_text(java);subprocess.run(['javac','-d',tmp,str(p)],check=True);subprocess.run(['java','-cp',tmp,'BackupInputTest'],check=True)
