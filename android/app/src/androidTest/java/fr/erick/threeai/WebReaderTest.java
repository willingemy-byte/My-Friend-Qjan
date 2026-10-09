package fr.erick.threeai;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class WebReaderTest {
    static final class Page extends ConnectionDiagnosticsTest.Reply {
        final String body,type,location;
        Page(URL url,int code,String body,String type,String location){super(url,code);this.body=body;this.type=type;this.location=location;}
        public String getContentType(){return type;}
        public String getHeaderField(String name){return "Location".equals(name)?location:null;}
        public InputStream getInputStream(){return new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }
    @Test public void explicitLinksAreUniqueAndLimited(){
        assertEquals(Arrays.asList("https://example.test/a","https://github.com/erick","https://example.test/b"),WebReader.links("http://ignore.test https://example.test/a. https://example.test/a https://github.com/erick https://example.test/b https://example.test/c"));
    }
    @Test public void htmlIsReadWithoutForwardingGithubToken()throws Exception{
        Page page=new Page(new URL("https://example.test/page"),200,"<html><head><title>hidden</title></head><body><h1>Bonjour</h1><script>secret script</script><p>Texte accessible</p></body></html>","text/html",null);
        String result=new WebReader(url->page).read(page.getURL().toString(),"github-private-token");
        assertTrue(result.contains("Bonjour"));assertTrue(result.contains("Texte accessible"));assertFalse(result.contains("secret script"));assertFalse(result.contains("hidden"));assertNull(page.getRequestProperty("Authorization"));assertFalse(page.getInstanceFollowRedirects());
    }
    @Test public void githubProfileUsesOfficialApiAndListsRepositories()throws Exception{
        List<Page> pages=new ArrayList<>();WebReader reader=new WebReader(url->{
            assertEquals("api.github.com",url.getHost());
            String body=url.getPath().endsWith("/repos")?"[{\"full_name\":\"erick/3AI\",\"html_url\":\"https://github.com/erick/3AI\",\"default_branch\":\"main\"}]":"{\"login\":\"erick\",\"name\":\"Erick\",\"bio\":\"Projet Android\"}";
            Page page=new Page(url,200,body,"application/json",null);pages.add(page);return page;
        });
        String result=reader.read("https://github.com/erick","test-token");assertTrue(result.contains("erick/3AI"));assertTrue(result.contains("Projet Android"));assertEquals(2,pages.size());for(Page page:pages)assertEquals("Bearer test-token",page.getRequestProperty("Authorization"));
    }
    @Test public void downgradeAndServerRefusalAreReported()throws Exception{
        for(int code:new int[]{302,404}){
            final int[] calls={0};WebReader reader=new WebReader(url->{calls[0]++;return new Page(url,code,"","text/html","http://unsafe.example.test");});
            try{reader.read("https://example.test/page","");fail("Expected refusal");}catch(IOException e){assertTrue(e.getMessage().contains(code==302?"HTTPS":"HTTP 404"));}assertEquals(1,calls[0]);
        }
    }
}
