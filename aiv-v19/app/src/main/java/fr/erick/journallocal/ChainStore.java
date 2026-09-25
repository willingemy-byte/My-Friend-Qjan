package fr.erick.journallocal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;

/** Platform-independent wire format; no JSON reserialization during verification. */
public final class ChainStore {
    public static final String GENESIS = new String(new char[64]).replace('\0','0');
    private ChainStore() {}
    public static String hex(byte[] bytes) {
        char[] out=new char[bytes.length*2],digits="0123456789abcdef".toCharArray();
        for(int i=0;i<bytes.length;i++){int x=bytes[i]&255;out[i*2]=digits[x>>>4];out[i*2+1]=digits[x&15];}return new String(out);
    }
    public static byte[] unhex(String s) {
        if(s==null||!s.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("SHA-256 hex invalide");
        byte[] b=new byte[32];for(int i=0;i<32;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;
    }
    public static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    public static String pack(String... fields){StringBuilder b=new StringBuilder();for(String f:fields){if(f==null)b.append("-1:");else b.append(f.getBytes(StandardCharsets.UTF_8).length).append(':').append(f);}return b.toString();}
    public static String hash(String previous,String payload,long timestamp,long sequence) {
        byte[] p=payload.getBytes(StandardCharsets.UTF_8);MessageDigest d=digest();
        d.update("AIV-CHAIN-1\0".getBytes(StandardCharsets.US_ASCII));d.update(unhex(previous));
        d.update(ByteBuffer.allocate(20).putLong(sequence).putLong(timestamp).putInt(p.length).array());d.update(p);return hex(d.digest());
    }
    public static boolean verify(PublicKey key,byte[] data,byte[] signature)throws GeneralSecurityException {
        Signature s=Signature.getInstance("SHA256withECDSA");s.initVerify(key);s.update(data);return s.verify(signature);
    }
}