package fr.erick.journallocal;
import android.content.Context;
import org.json.JSONObject;
import java.net.URI;
import java.security.MessageDigest;
/** Optional BYO Supabase, isolated from the central license service. */
final class PersonalBackup {
 static boolean enabled(Context c){try{return c.getSharedPreferences("aiv_backup_choice",0).getBoolean("enabled",false)&&ProductAccess.paidEnabled(c)&&ProtectedSettings.read(c,"backup").optBoolean("tested");}catch(Exception e){return false;}}
 static String validateUrl(String value)throws Exception{
  URI u=new URI(value.trim());if(!"https".equals(u.getScheme())||u.getHost()==null||!u.getHost().matches("[a-z0-9-]+\\.supabase\\.co")||u.getUserInfo()!=null||u.getPort()!=-1||u.getQuery()!=null||u.getFragment()!=null||!(u.getPath().isEmpty()||"/".equals(u.getPath())))throw new IllegalArgumentException("Entrer uniquement l’URL HTTPS de votre projet Supabase.");return "https://"+u.getHost();
 }
 static String validateKey(String value){String key=value.trim();if(!key.matches("sb_publishable_[A-Za-z0-9_-]{16,200}"))throw new IllegalArgumentException("Seule une clé sb_publishable_... est acceptée. Une secret key ou service_role peut contourner RLS et exposer vos données; elle ne doit jamais être enregistrée dans AIV.");return key;}
 static synchronized void configure(Context c,String url,String key)throws Exception{
  ProductAccess.requireFounder();if(ArchiveSync.isRunning())throw new IllegalStateException("Attendre la fin de la synchronisation avant de changer de projet.");
  String safeUrl=validateUrl(url),safeKey=validateKey(key);disable(c);JSONObject old=ProtectedSettings.read(c,"backup"),next=new JSONObject().put("url",safeUrl).put("key",safeKey).put("enabled",false);
  if(safeUrl.equals(old.optString("url")))for(String field:new String[]{"access_token","refresh_token","user_id","expires_at"})if(old.has(field))next.put(field,old.get(field));
  ProtectedSettings.save(c,"backup",next);
 }
 static synchronized JSONObject auth(Context c)throws Exception{
  JSONObject s=ProtectedSettings.read(c,"backup");if(s.optString("url").isEmpty())throw new IllegalStateException("Supabase personnel non configuré");
  if(s.optLong("expires_at")>System.currentTimeMillis()/1000+60)return s;
  boolean refresh=!s.optString("refresh_token").isEmpty();JSONObject body=refresh?new JSONObject().put("refresh_token",s.getString("refresh_token")):new JSONObject();
  JSONObject result=call(s,refresh?"/auth/v1/token?grant_type=refresh_token":"/auth/v1/signup",body,false);
  s.put("access_token",result.getString("access_token")).put("refresh_token",result.getString("refresh_token")).put("user_id",result.getJSONObject("user").getString("id")).put("expires_at",System.currentTimeMillis()/1000+result.getLong("expires_in"));ProtectedSettings.save(c,"backup",s);return s;
 }
 static JSONObject call(JSONObject settings,String path,JSONObject body,boolean bearer)throws Exception{
  java.net.HttpURLConnection h=(java.net.HttpURLConnection)new java.net.URL(settings.getString("url")+path).openConnection();h.setInstanceFollowRedirects(false);h.setConnectTimeout(15000);h.setReadTimeout(60000);h.setRequestMethod("POST");h.setDoOutput(true);h.setRequestProperty("Content-Type","application/json");h.setRequestProperty("apikey",settings.getString("key"));if(bearer)h.setRequestProperty("Authorization","Bearer "+settings.getString("access_token"));
  try{byte[] b=body.toString().getBytes("UTF-8");h.setFixedLengthStreamingMode(b.length);try(java.io.OutputStream out=h.getOutputStream()){out.write(b);}int code=h.getResponseCode();java.io.InputStream in=code<300?h.getInputStream():h.getErrorStream();java.io.ByteArrayOutputStream data=new java.io.ByteArrayOutputStream();if(in!=null)try(java.io.InputStream source=in){byte[] buf=new byte[8192];int n;while((n=source.read(buf))!=-1){if(data.size()+n>2_000_000)throw new java.io.IOException("Réponse Supabase trop grande");data.write(buf,0,n);}}JSONObject reply=new JSONObject(new String(data.toByteArray(),"UTF-8"));if(code<200||code>=300)throw new java.io.IOException("Test Supabase refusé : HTTP "+code);return reply;}finally{h.disconnect();}
 }
 static JSONObject rpc(Context c,JSONObject body)throws Exception{ProductAccess.requireFounder();return call(auth(c),"/rest/v1/rpc/aiv_personal_archive",new JSONObject().put("body",body),true);}
 static JSONObject test(Context c)throws Exception{
  JSONObject result=rpc(c,new JSONObject().put("action","probe"));
  if(result.optInt("schema_version")!=1||!result.optBoolean("read_ok")||!result.optBoolean("write_ok"))throw new java.io.IOException("Schéma ou droits Supabase incompatibles");
  JSONObject s=ProtectedSettings.read(c,"backup");s.put("tested",true).put("test",result).put("test_at",System.currentTimeMillis());ProtectedSettings.save(c,"backup",s);return result;
 }
 static void enable(Context c)throws Exception{ProductAccess.requireFounder();JSONObject s=ProtectedSettings.read(c,"backup");if(!s.optBoolean("tested"))throw new IllegalStateException("Exécuter d’abord le test de connexion réel.");if(!c.getSharedPreferences("aiv_backup_choice",0).edit().putBoolean("enabled",true).commit())throw new java.io.IOException("Choix non enregistré");}
 static JSONObject testStatus(Context c)throws Exception{JSONObject s=ProtectedSettings.read(c,"backup");JSONObject test=s.optJSONObject("test");if(test==null)test=new JSONObject();return test.put("tested",s.optBoolean("tested"));}
 static void disable(Context c){if(!c.getSharedPreferences("aiv_backup_choice",0).edit().putBoolean("enabled",false).commit())throw new IllegalStateException("Échec de désactivation");}
 static String destination(Context c)throws Exception{JSONObject s=ProtectedSettings.read(c,"backup");return FounderUpgrade.hex(MessageDigest.getInstance("SHA-256").digest((s.getString("url")+"\n"+s.getString("user_id")).getBytes("UTF-8")));}
}
