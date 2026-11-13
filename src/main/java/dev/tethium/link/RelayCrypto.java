package dev.tethium.link;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** End-to-end envelope used only outside the LAN. Cloudflare receives the room/auth derivatives, never this AES key. */
public final class RelayCrypto {
    private static final byte VERSION=1;
    private static final int NONCE=12,TAG=16;
    private static final SecureRandom RANDOM=new SecureRandom();
    private final SecretKeySpec key;
    private final String room,auth;

    public RelayCrypto(byte[] secret){
        if(secret==null||secret.length!=32)throw new IllegalArgumentException("Relay secret must be 32 bytes");
        room=text(derive(secret,"tethium-relay-room-v1"));
        auth=text(derive(secret,"tethium-relay-auth-v1"));
        key=new SecretKeySpec(derive(secret,"tethium-relay-aes-v1"),"AES");
    }
    public String room(){return room;}
    public String auth(){return auth;}
    public byte[] seal(byte[] plain){byte[] nonce=new byte[NONCE];RANDOM.nextBytes(nonce);return seal(plain,nonce);}
    byte[] seal(byte[] plain,byte[] nonce){
        if(nonce.length!=NONCE)throw new IllegalArgumentException("Bad nonce");
        try{var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));cipher.updateAAD(new byte[]{VERSION});byte[] encrypted=cipher.doFinal(plain);byte[] result=new byte[1+NONCE+encrypted.length];result[0]=VERSION;System.arraycopy(nonce,0,result,1,NONCE);System.arraycopy(encrypted,0,result,1+NONCE,encrypted.length);return result;}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public byte[] open(byte[] envelope)throws IOException{
        if(envelope==null||envelope.length<1+NONCE+TAG||envelope[0]!=VERSION)throw new IOException("Bad relay envelope");
        try{var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,envelope,1,NONCE));cipher.updateAAD(new byte[]{VERSION});return cipher.doFinal(envelope,1+NONCE,envelope.length-1-NONCE);}
        catch(Exception e){throw new IOException("Relay authentication failed",e);}
    }
    public static byte[] secret(){byte[] value=new byte[32];RANDOM.nextBytes(value);return value;}
    public static String encode(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    public static byte[] decode(String text){return Base64.getUrlDecoder().decode(text);}
    private static byte[] derive(byte[] secret,String label){
        try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private static String text(byte[] bytes){return encode(Arrays.copyOf(bytes,32));}
}
