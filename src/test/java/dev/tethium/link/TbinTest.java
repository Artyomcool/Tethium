package dev.tethium.link;

import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TbinTest {
    @Test void roundTripsTheJsonModelAndSendsKeysOnce(){
        var writer=new Tbin.Writer();var reader=new Tbin.Reader();
        var first=Json.object("id","a7e50fec","running",true,"count",-5,"big",1L<<40,"ratio",0.25,"none",(Object)null,"list",List.of(1,"два",false),"nested",Json.object("phase","Работает"));
        byte[] one=writer.encode(Frames.STATE,first);assertEquals(Frames.STATE,one[0]&255);
        assertEquals(first,reader.decode(one,1));
        byte[] two=writer.encode(Frames.STATE,first);
        assertEquals(first,reader.decode(two,1));
        assertTrue(two.length<one.length-30,"second frame carries key ids instead of names: "+two.length+" vs "+one.length);
        var decoded=reader.decode(writer.encode(Frames.LIVE,Json.object("big",1L<<40,"small",7)),1);
        assertTrue(decoded.path("big").isLong());assertTrue(decoded.path("small").isInt());
    }
    @Test void framesCompressLargeBodiesAndRejectCorruptSizes()throws Exception{
        var text=Json.object("text","слово ".repeat(400));byte[] raw=new Tbin.Writer().encode(Frames.LIVE,text);
        byte[] packed=Frames.pack(raw);assertEquals(1,packed[0]&1);assertTrue(packed.length<raw.length/4,"zstd shrinks repetitive text: "+packed.length);
        assertArrayEquals(raw,Frames.unpack(packed));
        byte[] tiny=Frames.pack(new byte[]{Frames.PING,7});assertEquals(0,tiny[0]&1);assertArrayEquals(new byte[]{Frames.PING,7},Frames.unpack(tiny));
        byte[] lying=packed.clone();lying[1]=0x7f;assertThrows(java.io.IOException.class,()->Frames.unpack(lying));
    }
}
