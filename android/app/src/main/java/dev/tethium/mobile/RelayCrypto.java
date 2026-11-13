package dev.tethium.mobile;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Android half of the Cloudflare relay envelope. Keep its test vector in sync with dev.tethium.link.RelayCrypto. */
final class RelayCrypto {
    private static final byte VERSION=1;
    private static final int NONCE=12,TAG=16;
    private static final SecureRandom RANDOM=new SecureRandom();
    private final SecretKeySpec key;
    final String room,auth;
    RelayCrypto(String encoded){this(Base64.getUrlDecoder().decode(encoded));}
    RelayCrypto(byte[] secret){
        if(secret==null||secret.length!=32)throw new IllegalArgumentException("Relay secret must be 32 bytes");
        room=text(derive(secret,"tethium-relay-room-v1"));auth=text(derive(secret,"tethium-relay-auth-v1"));key=new SecretKeySpec(derive(secret,"tethium-relay-aes-v1"),"AES");
    }
    byte[] seal(byte[] plain){byte[] nonce=new byte[NONCE];RANDOM.nextBytes(nonce);return seal(plain,nonce);}
    byte[] seal(byte[] plain,byte[] nonce){
        try{var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));cipher.updateAAD(new byte[]{VERSION});byte[] encrypted=cipher.doFinal(plain);byte[] result=new byte[1+NONCE+encrypted.length];result[0]=VERSION;System.arraycopy(nonce,0,result,1,NONCE);System.arraycopy(encrypted,0,result,1+NONCE,encrypted.length);return result;}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    byte[] open(byte[] envelope)throws IOException{
        if(envelope==null||envelope.length<1+NONCE+TAG||envelope[0]!=VERSION)throw new IOException("Bad relay envelope");
        try{var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,envelope,1,NONCE));cipher.updateAAD(new byte[]{VERSION});return cipher.doFinal(envelope,1+NONCE,envelope.length-1-NONCE);}
        catch(Exception e){throw new IOException("Relay authentication failed",e);}
    }
    private static byte[] derive(byte[] secret,String label){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String text(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(bytes,32));}
}
