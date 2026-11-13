package dev.tethium.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class RelayCryptoTest {
    @Test public void matchesDesktopDerivationAndEnvelope()throws Exception{
        byte[] secret=new byte[32];for(int i=0;i<secret.length;i++)secret[i]=(byte)i;
        var crypto=new RelayCrypto(secret);
        assertEquals("FfjrJvKOcPVScsckTxNVRTqMgOrvQCrPrs0u1aVljR4",crypto.room);
        assertEquals("XiA4jmGk2TU7UiyLInTiAbhAol7gNSfDv75CE-_NVSo",crypto.auth);
        byte[] nonce=new byte[12];for(int i=0;i<nonce.length;i++)nonce[i]=(byte)i;
        byte[] plain="state delta".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertArrayEquals(plain,crypto.open(crypto.seal(plain,nonce)));
    }
    @Test public void tbinCarriesHttpChunksWithoutBase64()throws Exception{
        byte[] bytes=new byte[4097];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i*31);
        byte[] encoded=new Tbin.Writer().encode(Connection.HTTP_RESPONSE,Connection.json("id",7,"body",bytes,"done",false));
        Object decoded=new Tbin.Reader().decode(encoded,1);assertTrue(decoded instanceof org.json.JSONObject);assertArrayEquals(bytes,(byte[])((org.json.JSONObject)decoded).get("body"));
    }
}
