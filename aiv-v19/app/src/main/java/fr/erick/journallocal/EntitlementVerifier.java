package fr.erick.journallocal;
import org.json.JSONObject;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
/** Pure verifier used by Android and host negative-security tests. */
final class EntitlementVerifier {
 static JSONObject verify(JSONObject envelope,String publicKey,String expectedKeyId,long now)throws Exception{
  byte[] payload=Base64.getDecoder().decode(envelope.getString("payload"));
  PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
  Signature sig=Signature.getInstance("SHA256withRSA");sig.initVerify(key);sig.update(payload);
  if(!sig.verify(Base64.getDecoder().decode(envelope.getString("signature"))))throw new SecurityException("Signature de licence invalide");
  JSONObject claims=new JSONObject(new String(payload,"UTF-8"));
  if(claims.getInt("schema_version")!=1||!"aiv-founder".equals(claims.getString("aud"))||!"ACTIVE".equals(claims.getString("status"))||!claims.getBoolean("founder")||!expectedKeyId.equals(claims.getString("key_id"))||now>claims.getLong("expires_at")||now+300<claims.getLong("issued_at")||claims.getLong("expires_at")-claims.getLong("issued_at")>7*86400)throw new SecurityException("Licence inactive ou à revalider");
  int number=claims.getInt("founder_number");if(number<1||number>1000)throw new SecurityException("Numéro Founder invalide");return claims;
 }
}
