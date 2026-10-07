package fr.erick.journallocal;
import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.security.KeyStore;
import org.json.JSONObject;
/** Separate encrypted preferences; existing identity alias and databases are untouched. */
final class ProtectedSettings {
 private static final String ALIAS="aiv-personal-settings-v1";
 private static javax.crypto.SecretKey key()throws Exception{
  KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
  if(!ks.containsAlias(ALIAS)){KeyGenerator g=KeyGenerator.getInstance("AES","AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();}
  return (javax.crypto.SecretKey)ks.getKey(ALIAS,null);
 }
 static synchronized JSONObject read(Context c,String name)throws Exception{
  String saved=c.getSharedPreferences("aiv_private_settings",0).getString(name,"");if(saved.isEmpty())return new JSONObject();
  JSONObject data=new JSONObject(saved);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(data.getString("iv"),Base64.NO_WRAP)));cipher.updateAAD(name.getBytes("UTF-8"));
  return new JSONObject(new String(cipher.doFinal(Base64.decode(data.getString("data"),Base64.NO_WRAP)),"UTF-8"));
 }
 static synchronized void save(Context c,String name,JSONObject value)throws Exception{
  Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(name.getBytes("UTF-8"));
  JSONObject data=new JSONObject().put("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).put("data",Base64.encodeToString(cipher.doFinal(value.toString().getBytes("UTF-8")),Base64.NO_WRAP));
  if(!c.getSharedPreferences("aiv_private_settings",0).edit().putString(name,data.toString()).commit())throw new java.io.IOException("Paramètres non enregistrés");
 }
}
