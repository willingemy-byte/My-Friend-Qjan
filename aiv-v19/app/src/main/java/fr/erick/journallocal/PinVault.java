package fr.erick.journallocal;
import android.os.Build;
import android.security.keystore.*;
import java.security.*;
import java.security.spec.ECGenParameterSpec;

/** APK signing key and device export key are unrelated and never exchanged. */
public final class PinVault {
    private static final String ALIAS="fr.erick.journallocal.aiv.export.ec.v1";
    private final KeyStore store;private final PrivateKey privateKey;private final PublicKey publicKey;
    public final String securityLevel;
    public PinVault()throws Exception{
        synchronized(PinVault.class){
            store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
            if(!store.containsAlias(ALIAS)){
                if(Build.VERSION.SDK_INT>=28){try{generate(true);}catch(StrongBoxUnavailableException e){generate(false);}}
                else generate(false);
            }
            privateKey=(PrivateKey)store.getKey(ALIAS,null);publicKey=store.getCertificate(ALIAS).getPublicKey();
            KeyInfo info=(KeyInfo)KeyFactory.getInstance(privateKey.getAlgorithm(),"AndroidKeyStore").getKeySpec(privateKey,KeyInfo.class);
            if(Build.VERSION.SDK_INT>=31){int level=info.getSecurityLevel();securityLevel=level==KeyProperties.SECURITY_LEVEL_STRONGBOX?"STRONGBOX":level==KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT?"TEE":level==KeyProperties.SECURITY_LEVEL_SOFTWARE?"SOFTWARE":"UNKNOWN";}
            else securityLevel=info.isInsideSecureHardware()?"HARDWARE_UNSPECIFIED":"SOFTWARE";
        }
    }
    private void generate(boolean strong)throws Exception{
        KeyPairGenerator g=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore");
        KeyGenParameterSpec.Builder b=new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY).setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).setUserAuthenticationRequired(false);
        if(Build.VERSION.SDK_INT>=28)b.setIsStrongBoxBacked(strong);g.initialize(b.build());g.generateKeyPair();
    }
    public PublicKey publicKey(){return publicKey;}
    public String keyId(){return ChainStore.hex(ChainStore.digest().digest(publicKey.getEncoded()));}
    public byte[] sign(byte[] bytes)throws GeneralSecurityException{Signature s=Signature.getInstance("SHA256withECDSA");s.initSign(privateKey);s.update(bytes);return s.sign();}
    public static boolean verify(PublicKey key,byte[] bytes,byte[] signature)throws GeneralSecurityException{return ChainStore.verify(key,bytes,signature);}
}