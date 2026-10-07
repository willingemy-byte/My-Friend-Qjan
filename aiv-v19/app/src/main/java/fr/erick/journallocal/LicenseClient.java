package fr.erick.journallocal;
import android.content.Context;
import android.util.Base64;
import org.json.JSONObject;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.net.*;
import java.io.*;
import java.util.UUID;
/** Only license identity/payment state is sent here. No EventStore access. */
final class LicenseClient {
 static JSONObject claims(Context c)throws Exception{
  JSONObject stored=ProtectedSettings.read(c,"license");JSONObject envelope=stored.getJSONObject("entitlement");
  return EntitlementVerifier.verify(envelope,EditionConfig.LICENSE_PUBLIC_KEY,DeviceIdentity.keyId(),System.currentTimeMillis()/1000);
 }
 static boolean active(Context c){try{claims(c);return true;}catch(Exception e){return false;}}
 static int founderNumber(Context c){try{return claims(c).getInt("founder_number");}catch(Exception e){return 0;}}
 static JSONObject request(Context c,String path,JSONObject payload)throws Exception{
  if(EditionConfig.LICENSE_URL.isEmpty()||EditionConfig.LICENSE_PUBLIC_KEY.isEmpty())throw new IOException("Serveur de licence non configuré : paiement indisponible dans cette build de validation.");
  JSONObject identity=DeviceIdentity.describe();String pub=identity.getString("public_key_spki_b64");
  JSONObject challenge=post(EditionConfig.LICENSE_URL+"/challenge",new JSONObject().put("public_key",pub),null,null);
  String body=payload.toString();String nonce=challenge.getString("nonce");JSONObject signature=DeviceIdentity.sign(nonce+"\n"+path+"\n"+body);
  return post(EditionConfig.LICENSE_URL+path,payload,nonce,signature.getString("signature_b64"));
 }
 static JSONObject createOrder(Context c)throws Exception{
  JSONObject state=ProtectedSettings.read(c,"license");String id=state.optString("request_id","");if(id.isEmpty()){id=UUID.randomUUID().toString();state.put("request_id",id);ProtectedSettings.save(c,"license",state);}
  JSONObject response=request(c,"/orders",new JSONObject().put("request_id",id));
  if(response.optBoolean("sold_out"))throw new IOException("Offre fondateur terminée");
  state.put("order_id",response.getString("order_id"));ProtectedSettings.save(c,"license",state);return response;
 }
 static void confirm(Context c)throws Exception{
  JSONObject state=ProtectedSettings.read(c,"license");String order=state.optString("order_id","");
  Exception captureError=null;
  if(!order.isEmpty())try{request(c,"/capture",new JSONObject().put("order_id",order));}catch(Exception e){captureError=e;}
  JSONObject response=request(c,"/entitlement",new JSONObject());
  // Removal after a verified authoritative denial only; transient network failure preserves receipt.
  if(!response.optBoolean("active")){state.remove("entitlement");ProtectedSettings.save(c,"license",state);if(captureError!=null)throw captureError;throw new IOException("Paiement non confirmé ou licence révoquée");}
  state.put("entitlement",response.getJSONObject("entitlement"));ProtectedSettings.save(c,"license",state);claims(c);
 }
 static void refreshIfNeeded(Context c){
  if(EditionConfig.LICENSE_URL.isEmpty())return;
  try{if(!ProtectedSettings.read(c,"license").has("entitlement"))return;}catch(Exception e){return;}
  android.content.SharedPreferences p=c.getSharedPreferences("aiv_license_refresh",0);long now=System.currentTimeMillis();
  if(now-p.getLong("last_attempt_ms",0)<3600000)return;
  p.edit().putLong("last_attempt_ms",now).apply();
  new Thread(()->{try{confirm(c);}catch(Exception ignored){}},"aiv-license-refresh").start();
 }
 static JSONObject release(Context c)throws Exception{if(!active(c))throw new SecurityException("Licence Founder requise");return request(c,"/release",new JSONObject());}
 static JSONObject post(String url,JSONObject body,String nonce,String signature)throws Exception{
  HttpURLConnection h=(HttpURLConnection)new URL(url).openConnection();h.setInstanceFollowRedirects(false);h.setConnectTimeout(15000);h.setReadTimeout(60000);h.setRequestMethod("POST");h.setDoOutput(true);h.setRequestProperty("Content-Type","application/json");if(nonce!=null){h.setRequestProperty("X-AIV-Nonce",nonce);h.setRequestProperty("X-AIV-Signature",signature);}
  try{byte[] bytes=body.toString().getBytes("UTF-8");h.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=h.getOutputStream()){out.write(bytes);}int code=h.getResponseCode();InputStream in=code<300?h.getInputStream():h.getErrorStream();ByteArrayOutputStream data=new ByteArrayOutputStream();if(in!=null)try(InputStream source=in){byte[] buf=new byte[4096];int n;while((n=source.read(buf))!=-1){if(data.size()+n>2_000_000)throw new IOException("Réponse distante trop grande");data.write(buf,0,n);}}JSONObject result=new JSONObject(new String(data.toByteArray(),"UTF-8"));if(code<200||code>=300)throw new IOException(result.optString("error","HTTP "+code));return result;}finally{h.disconnect();}
 }
}
