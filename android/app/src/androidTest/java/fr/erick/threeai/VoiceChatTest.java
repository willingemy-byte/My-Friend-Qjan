package fr.erick.threeai;

import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.zip.*;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class VoiceChatTest {
    @Test public void spokenTextRemovesFormattingButKeepsFrenchAndNumbers(){
        String original="## Résumé\n**Bonjour Erick**.\n- Prix : 4,50 $\n- [Documentation](https://example.test)\n```java\nsecret_code();\n```\n2 + 2 = 4.";
        String clean=SpeechText.clean(original);assertTrue(clean.contains("Bonjour Erick"));assertTrue(clean.contains("4,50 $"));assertTrue(clean.contains("2 + 2 = 4"));assertTrue(clean.contains("Documentation"));assertFalse(clean.contains("*"));assertFalse(clean.contains("#"));assertFalse(clean.contains("https://"));assertFalse(clean.contains("secret_code"));assertTrue(original.contains("**"));
        String value=String.join(" ",Collections.nCopies(2000,"Bonjour été 😀"));for(String part:SpeechText.chunks(value,550)){assertTrue(part.length()<=550);assertFalse(Character.isHighSurrogate(part.charAt(part.length()-1)));}
    }
    @Test public void sseKeepsPartialAnswerSkipsUsageAndDoesNotExposeReasoning()throws Exception{
        String stream="data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"private reasoning\"}}]}\r\n\r\n"+
            "data: {\"choices\":[{\"delta\":{\"content\":\"Bonjour \"}}]}\n\n"+
            "data: {\"choices\":[{\"delta\":{\"content\":\"Erick\"},\"finish_reason\":\"length\"}]}\n\n"+
            "data: {\"choices\":[],\"usage\":{\"total_tokens\":8}}\n\ndata: [DONE]\n\n";
        List<String> updates=new ArrayList<>();boolean[] thinking={false};ChatStream.Result result=ChatStream.read(new StringReader(stream),(text,reasoning)->{updates.add(text);thinking[0]|=reasoning;});assertEquals("Bonjour Erick",result.text);assertEquals("length",result.finish);assertFalse(result.complete());assertTrue(thinking[0]);assertFalse(updates.toString().contains("private reasoning"));
    }
    @Test public void prematureNetworkEndIsPartialAndEmptyAnswerIsExplicit()throws Exception{
        ChatStream.Result result=ChatStream.read(new StringReader("data: {\"choices\":[{\"delta\":{\"content\":\"Début\"}}]}\n\n"),(text,reasoning)->{});assertEquals("Début",result.text);assertFalse(result.complete());
        try{ChatStream.read(new StringReader("data: [DONE]\n\n"),(text,reasoning)->{});fail("Empty accepted");}catch(IOException expected){assertTrue(expected.getMessage().contains("Aucune réponse"));}
    }
    @Test public void wavHasRealPcmHeaderAndExactLength(){byte[] pcm=new byte[32000],audio=VoiceCapture.wav(pcm);assertEquals(32044,audio.length);ByteBuffer b=ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN);assertEquals(32036,b.getInt(4));assertEquals(16000,b.getInt(24));assertEquals(32000,b.getInt(40));assertEquals(1,b.getShort(22));}
    @Test public void voiceEndpointCannotReuseKeyOnForeignServer()throws Exception{
        assertEquals("https://ws-synthetic.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1",AlibabaSpeech.compatible("https://ws-synthetic.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1"));
        for(String endpoint:new String[]{"https://evil.test/v1","https://maas.aliyuncs.com.evil.test/v1","http://dashscope-intl.aliyuncs.com/v1"})try{AlibabaSpeech.compatible(endpoint);fail("Foreign accepted");}catch(Exception expected){}
    }
    @Test public void pdfAndWordTextCanBeAttachedWithoutSendingBinary()throws Exception{
        Context c=ApplicationProvider.getApplicationContext();Attachments vault=new Attachments(c);PDFBoxResourceLoader.init(c);String id=UUID.randomUUID().toString();JSONObject pdf=new JSONObject().put("id",id).put("name","rapport.pdf").put("mime","application/pdf");File f=new File(vault.directory,id);
        try{try(PDDocument doc=new PDDocument()){PDPage page=new PDPage();doc.addPage(page);try(PDPageContentStream stream=new PDPageContentStream(doc,page)){stream.beginText();stream.setFont(PDType1Font.HELVETICA,12);stream.newLineAtOffset(30,700);stream.showText("Rapport de test");stream.endText();}doc.save(f);}assertTrue(vault.extract(pdf).contains("Rapport de test"));assertTrue(vault.dataForChat(new JSONArray().put(pdf)).contains("Fichier joint : rapport.pdf"));}finally{f.delete();}
        id=UUID.randomUUID().toString();f=new File(vault.directory,id);JSONObject word=new JSONObject().put("id",id).put("name","memoire.docx");try{try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(f))){zip.putNextEntry(new ZipEntry("word/document.xml"));zip.write("<w:document xmlns:w=\"urn:w\"><w:p><w:t>Mémoire française</w:t></w:p></w:document>".getBytes("UTF-8"));zip.closeEntry();}assertTrue(vault.extract(word).contains("Mémoire française"));}finally{f.delete();}
    }
    private View find(View v,String label){if(v instanceof TextView&&label.equals(((TextView)v).getText().toString()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View match=find(((ViewGroup)v).getChildAt(i),label);if(match!=null)return match;}return null;}
    @Test public void imageReferenceSurvivesRestartAndChatUsesCompactComposer()throws Exception{
        Context c=ApplicationProvider.getApplicationContext();LocalStore local=new LocalStore(c);String saved=local.read("conversations.json","[]");Attachments vault=new Attachments(c);String id=UUID.randomUUID().toString();File file=new File(vault.directory,id);Bitmap bitmap=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.CYAN);try(OutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();JSONObject item=new JSONObject().put("id",id).put("name","image-test.png").put("mime","image/png").put("image",true);
        try{local.write("conversations.json",new JSONArray().put(LocalStore.message("user","Image à conserver").put("attachments",new JSONArray().put(item))).toString());
            try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
                scenario.onActivity(a->{View root=a.getWindow().getDecorView();assertNotNull(find(root,"Ouvrir : image-test.png"));assertNotNull(find(root,"Joindre"));find(root,"Menu").performClick();assertTrue(find(root,"Réglages").isShown());find(root,"Chat").performClick();});
                scenario.recreate();scenario.onActivity(a->{assertNotNull(find(a.getWindow().getDecorView(),"Ouvrir : image-test.png"));});
            }
            assertTrue(vault.imageData(item).startsWith("data:image/jpeg;base64,"));assertTrue(new JSONObject(local.read("conversations.json","[]").substring(1,local.read("conversations.json","[]").length()-1)).has("attachments"));
        }finally{local.write("conversations.json",saved);file.delete();}
    }
}
