package dev.tethium.link;

import org.junit.jupiter.api.Test;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class RelayCryptoTest {
    @Test void derivesStableRoutingNamesAndAuthenticatesEveryEnvelope()throws Exception{
        byte[] secret=new byte[32];for(int i=0;i<secret.length;i++)secret[i]=(byte)i;
        var crypto=new RelayCrypto(secret);
        assertEquals("FfjrJvKOcPVScsckTxNVRTqMgOrvQCrPrs0u1aVljR4",crypto.room());
        assertEquals("XiA4jmGk2TU7UiyLInTiAbhAol7gNSfDv75CE-_NVSo",crypto.auth());
        byte[] nonce=HexFormat.of().parseHex("000102030405060708090a0b"),plain="state delta".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] sealed=crypto.seal(plain,nonce);assertArrayEquals(plain,crypto.open(sealed));
        sealed[sealed.length-1]^=1;assertThrows(java.io.IOException.class,()->crypto.open(sealed));
    }
    @Test void tbinCarriesHttpChunksWithoutBase64()throws Exception{
        byte[] bytes=new byte[4097];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i*31);
        byte[] encoded=new Tbin.Writer().encode(Frames.HTTP_RESPONSE,dev.tethium.json.Json.object("id",7,"body",bytes,"done",false));
        var decoded=new Tbin.Reader().decode(encoded,1);assertArrayEquals(bytes,decoded.path("body").binaryValue());assertEquals(7,decoded.path("id").asInt());
    }
}
