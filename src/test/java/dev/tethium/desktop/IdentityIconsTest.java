package dev.tethium.desktop;
import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;
class IdentityIconsTest {
    @Test void unknownNamesGetHashedPatternsAndOnlyTheDefaultSessionKeepsTheGlyph() {
        assertEquals("hash:project/future",IdentityIcons.asset(true,"Future","C:/git/Future"));assertTrue(IdentityIcons.of(true,"Future","C:/git/Future") instanceof javax.swing.ImageIcon);assertNotEquals(IdentityIcons.asset(true,"Future","C:/git/Future"),IdentityIcons.asset(false,"Future",""));
        assertEquals("svg:session-chat",IdentityIcons.asset(false,"Новая сессия",""));assertFalse(IdentityIcons.of(false,"Новая сессия","") instanceof javax.swing.ImageIcon);
        for(String name:new String[]{"Исследование","Эксперименты","Документация","Обслуживание","Архитектура","Любая другая"}) {
            assertEquals("hash:"+IdentityIcons.normalize(name),IdentityIcons.asset(false,name,""));
            assertTrue(IdentityIcons.of(false,name,"") instanceof javax.swing.ImageIcon);
        }
        assertTrue(IdentityIcons.of(false,"Логика","") instanceof javax.swing.ImageIcon);
    }
    @Test void sameSessionNameSharesIdentityAcrossProjectsAndSpacing() {
        assertEquals("session-logic",IdentityIcons.asset(false,"Логика","C:/git/Tethium"));
        assertSame(IdentityIcons.of(false,"Логика","C:/git/Tethium"),IdentityIcons.of(false,"  ЛОГИКА  ","C:/git/SBG"));
        assertEquals("session-tasks",IdentityIcons.asset(false,"Мелкие   задачи",""));
        assertEquals(IdentityIcons.asset(false,"Будущая сессия","a"),IdentityIcons.asset(false,"будущая сессия","b"));
    }
    @Test void projectsHaveNoRegistryAnymoreAndCountsDoNotChangeIdentity() {
        assertEquals("hash:project/h3phoenix2",IdentityIcons.asset(true,"H3Phoenix2  3","C:\\git\\H3Phoenix2"),"project artwork lives in the project, not in Tethium");
        for(String name:new String[]{"Графика","Логика","Верификация","Прогресс","Мелкие задачи","Новая сессия","Исследование"})
            assertEquals(22,IdentityIcons.of(false,name,"").getIconWidth());
        for(String name:new String[]{"Tethium","H3Phoenix2","SBG","UnclaimedStars","Future"})
            assertEquals(22,IdentityIcons.of(true,name,"C:/git/"+name).getIconHeight());
    }
    @Test void projectIconFromTheProjectReplacesTheHashedPatternUntilItsVersionChanges() throws Exception {
        var image=new BufferedImage(40,30,BufferedImage.TYPE_INT_ARGB);var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",out);
        String png=Base64.getEncoder().encodeToString(out.toByteArray());
        var fallback=IdentityIcons.ofProject("p1","Future","C:/git/Future");
        assertTrue(IdentityIcons.projectIconStale("p1","100:1"));
        IdentityIcons.registerProjectIcon("p1","100:1",png);
        assertFalse(IdentityIcons.projectIconStale("p1","100:1"));assertTrue(IdentityIcons.projectIconStale("p1","100:2"),"a changed file is fetched again");
        var own=IdentityIcons.ofProject("p1","Future","C:/git/Future");
        assertNotSame(fallback,own);assertEquals(22,own.getIconWidth());assertEquals(22,own.getIconHeight());
        IdentityIcons.registerProjectIcon("p1","100:3","");
        assertSame(fallback,IdentityIcons.ofProject("p1","Future","C:/git/Future"),"an unreadable icon falls back to the hashed pattern");
        assertFalse(IdentityIcons.projectIconStale("p1",""));assertSame(fallback,IdentityIcons.ofProject("p1","Future","C:/git/Future"),"a removed file drops the cached icon");
    }
}
