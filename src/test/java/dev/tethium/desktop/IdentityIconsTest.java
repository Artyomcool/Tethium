package dev.tethium.desktop;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class IdentityIconsTest {
    @Test void standardNamesUseOutlineVectorsInsteadOfUnapprovedArtwork() {
        assertEquals("svg:project-folder",IdentityIcons.asset(true,"Future","C:/git/Future"));
        for(String name:new String[]{"Новая сессия","Исследование","Эксперименты","Документация","Обслуживание","Архитектура","Любая другая"}) {
            assertEquals("svg:session-chat",IdentityIcons.asset(false,name,""));
            assertFalse(IdentityIcons.of(false,name,"") instanceof javax.swing.ImageIcon);
        }
        assertTrue(IdentityIcons.of(false,"Логика","") instanceof javax.swing.ImageIcon);
    }
    @Test void sameSessionNameSharesIdentityAcrossProjectsAndSpacing() {
        assertEquals("session-logic",IdentityIcons.asset(false,"Логика","C:/git/Tethium"));
        assertSame(IdentityIcons.of(false,"Логика","C:/git/Tethium"),IdentityIcons.of(false,"  ЛОГИКА  ","C:/git/SBG"));
        assertEquals("session-tasks",IdentityIcons.asset(false,"Мелкие   задачи",""));
        assertEquals(IdentityIcons.asset(false,"Будущая сессия","a"),IdentityIcons.asset(false,"будущая сессия","b"));
    }
    @Test void projectCountsDoNotChangeIdentityAndAllCurrentNamesLoad() {
        assertEquals("project-phoenix",IdentityIcons.asset(true,"H3Phoenix2  3","C:\\git\\H3Phoenix2"));
        for(String name:new String[]{"Графика","Логика","Верификация","Прогресс","Мелкие задачи","Новая сессия","Исследование","Эксперименты","Документация","Обслуживание","Архитектура"})
            assertEquals(22,IdentityIcons.of(false,name,"").getIconWidth());
        for(String name:new String[]{"Tethium","H3Phoenix2","SBG","UnclaimedStars","Future"})
            assertEquals(22,IdentityIcons.of(true,name,"C:/git/"+name).getIconHeight());
    }
}
