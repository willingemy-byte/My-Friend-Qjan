package fr.erick.journallocal;
import java.security.*;import java.util.Base64;import org.json.JSONObject;
public final class EntitlementVerifierTest {
 static KeyPair key;static String pub;static long now=100000;
 static JSONObject claims(){return new JSONObject().put("schema_version",1).put("aud","aiv-founder").put("key_id","device-A").put("licence_id","L1").put("founder",true).put("founder_number",1000).put("status","ACTIVE").put("issued_at",now).put("expires_at",now+604800);}
 static JSONObject sign(JSONObject c)throws Exception{byte[] payload=c.toString().getBytes("UTF-8");Signature sig=Signature.getInstance("SHA256withRSA");sig.initSign(key.getPrivate());sig.update(payload);return new JSONObject().put("payload",Base64.getEncoder().encodeToString(payload)).put("signature",Base64.getEncoder().encodeToString(sig.sign()));}
 static void rejected(JSONObject proof,String id,long clock)throws Exception{try{EntitlementVerifier.verify(proof,pub,id,clock);throw new AssertionError("Bad entitlement accepted");}catch(SecurityException expected){}}
 public static void main(String[] args)throws Exception{
  KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);key=g.generateKeyPair();pub=Base64.getEncoder().encodeToString(key.getPublic().getEncoded());JSONObject valid=sign(claims());
  if(EntitlementVerifier.verify(valid,pub,"device-A",now).getInt("founder_number")!=1000)throw new AssertionError();
  rejected(valid,"device-B",now);rejected(valid,"device-A",now+604801);rejected(valid,"device-A",now-301);
  for(String field:new String[]{"status","founder","aud","schema_version","founder_number"}){
   JSONObject c=claims();c.put(field,field.equals("status")?"REVOKED":field.equals("founder")?false:field.equals("aud")?"other":field.equals("schema_version")?2:1001);rejected(sign(c),"device-A",now);
  }
  JSONObject modified=sign(claims());modified.put("payload",Base64.getEncoder().encodeToString(claims().put("key_id","evil").toString().getBytes("UTF-8")));rejected(modified,"evil",now);
  System.out.println("PASS entitlement RSA signature, payload tampering, identity, expiration, future clock, status, founder, audience, schema and 1001");
 }
}
