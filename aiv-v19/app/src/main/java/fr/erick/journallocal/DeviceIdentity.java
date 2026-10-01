package fr.erick.journallocal;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Locale;

/**
 * Installation-local AIV signing identity.
 *
 * The private key is created in Android Keystore and never exported. This key is
 * deliberately separate from the APK signing key. It can sign future reports or
 * enrollment challenges without making claims about another application's key.
 */
final class DeviceIdentity {
    private static final String STORE="AndroidKeyStore";
    private static final String ALIAS="aiv-device-identity-v1";
    private static final Object LOCK=new Object();

    private DeviceIdentity(){}

    private static KeyStore keyStore()throws Exception{
        KeyStore ks=KeyStore.getInstance(STORE);
        ks.load(null);
        return ks;
    }

    private static void generate(boolean strongBox)throws Exception{
        KeyPairGenerator generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,STORE);
        KeyGenParameterSpec.Builder builder=new KeyGenParameterSpec.Builder(
            ALIAS,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false);
        if(strongBox&&Build.VERSION.SDK_INT>=28)builder.setIsStrongBoxBacked(true);
        generator.initialize(builder.build());
        generator.generateKeyPair();
    }

    private static void ensure()throws Exception{
        synchronized(LOCK){
            KeyStore ks=keyStore();
            if(ks.containsAlias(ALIAS))return;
            if(Build.VERSION.SDK_INT>=28){
                try{generate(true);return;}
                catch(Exception ignored){
                    ks=keyStore();
                    if(ks.containsAlias(ALIAS))return;
                }
            }
            generate(false);
        }
    }

    private static Certificate certificate()throws Exception{
        ensure();
        Certificate cert=keyStore().getCertificate(ALIAS);
        if(cert==null)throw new IllegalStateException("Certificat de la clé AIV indisponible");
        return cert;
    }

    static String keyId()throws Exception{
        return sha256(certificate().getPublicKey().getEncoded());
    }

    static JSONObject describe(){
        try{
            ensure();
            KeyStore ks=keyStore();
            PrivateKey privateKey=(PrivateKey)ks.getKey(ALIAS,null);
            Certificate cert=ks.getCertificate(ALIAS);
            boolean hardware=false;
            try{
                KeyFactory factory=KeyFactory.getInstance(privateKey.getAlgorithm(),STORE);
                KeyInfo info=factory.getKeySpec(privateKey,KeyInfo.class);
                hardware=info.isInsideSecureHardware();
            }catch(Exception ignored){}
            return EventStore.object(
                "schema","aiv-device-identity/1",
                "key_alias",ALIAS,
                "algorithm","EC_P256_SHA256",
                "key_id_sha256",sha256(cert.getPublicKey().getEncoded()),
                "public_key_spki_b64",Base64.encodeToString(cert.getPublicKey().getEncoded(),Base64.NO_WRAP),
                "inside_secure_hardware",hardware,
                "private_key_exportable",false,
                "scope","Identité cryptographique de cette installation AIV; distincte de la clé de signature APK et des identités des autres applications");
        }catch(Exception e){
            return EventStore.object("schema","aiv-device-identity/1","status","UNAVAILABLE","error",e.getClass().getSimpleName());
        }
    }

    static JSONObject sign(String canonical){
        if(canonical==null)canonical="";
        try{
            ensure();
            PrivateKey key=(PrivateKey)keyStore().getKey(ALIAS,null);
            Signature signer=Signature.getInstance("SHA256withECDSA");
            signer.initSign(key);
            byte[] bytes=canonical.getBytes(StandardCharsets.UTF_8);
            signer.update(bytes);
            return EventStore.object(
                "schema","aiv-signature/1",
                "key_id_sha256",keyId(),
                "algorithm","SHA256withECDSA",
                "payload_sha256",sha256(bytes),
                "signature_b64",Base64.encodeToString(signer.sign(),Base64.NO_WRAP));
        }catch(Exception e){
            return EventStore.object("schema","aiv-signature/1","status","UNAVAILABLE","error",e.getClass().getSimpleName());
        }
    }

    private static String sha256(byte[] bytes)throws Exception{
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out=new StringBuilder(digest.length*2);
        for(byte b:digest)out.append(String.format(Locale.ROOT,"%02x",b&255));
        return out.toString();
    }
}
