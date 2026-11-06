package dev.tethium.desktop;

import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SessionIdenticonTest {
    private static boolean crosses(float[] a, float[] b, float[] c, float[] d) {
        double d1 = cross(c, d, a), d2 = cross(c, d, b), d3 = cross(a, b, c), d4 = cross(a, b, d);
        return d1 * d2 < 0 && d3 * d4 < 0;
    }
    private static double cross(float[] o, float[] p, float[] q) { return (double) (p[0] - o[0]) * (q[1] - o[1]) - (double) (p[1] - o[1]) * (q[0] - o[0]); }
    private static boolean simple(List<float[]> outline) {
        int n = outline.size();
        for (int i = 0; i < n; i++) for (int j = i + 2; j < n; j++) { if (i == 0 && j == n - 1) continue; if (crosses(outline.get(i), outline.get((i + 1) % n), outline.get(j), outline.get((j + 1) % n))) return false; }
        return true;
    }
    private static boolean concave(List<float[]> outline) {
        int n = outline.size(); boolean positive = false, negative = false;
        for (int i = 0; i < n; i++) { double s = cross(outline.get(i), outline.get((i + 1) % n), outline.get((i + 2) % n)); if (s > 1e-9) positive = true; if (s < -1e-9) negative = true; }
        return positive && negative;
    }
    @Test void outlinesNeverSelfIntersectAndSomeAreConcave() {
        int concave = 0, cornerKinds = 0;
        for (int i = 0; i < 200; i++) {
            var shape = SessionIdenticon.shape(SessionIdenticon.digest("сессия " + i)); var outline = shape.points().subList(0, shape.outline());
            assertTrue(simple(outline), "outline must not cross itself: " + i);
            if (concave(outline)) concave++;
            cornerKinds |= 1 << shape.outline();
            var facets = SessionIdenticon.facets(SessionIdenticon.digest("сессия " + i));
            assertTrue(facets.size() >= SessionIdenticon.prune(shape).outline(), "at least one facet per outline edge after pruning");
            for (var f : facets) for (int k = 0; k < 3; k++) { assertTrue(f.xs()[k] >= 0 && f.xs()[k] <= 1); assertTrue(f.ys()[k] >= 0 && f.ys()[k] <= 1); }
            for (int k = 1; k < facets.size(); k++) assertNotEquals(facets.get(k - 1).lightness(), facets.get(k).lightness());
        }
        assertTrue(concave >= 185, "nearly every outline has concave corners: " + concave);
        assertTrue(Integer.bitCount(cornerKinds) >= 4, "different numbers of outline points occur");
        assertEquals(SessionIdenticon.facets(SessionIdenticon.digest("интерпретатор")).size(), SessionIdenticon.facets(SessionIdenticon.digest("интерпретатор")).size());
    }
    @Test void rendersEverySizeWithTransparentCornersAndKnownNamesKeepTheirDrawnIcons() {
        for (int size : new int[]{22, 28, 33, 44, 66, 88}) { BufferedImage image = SessionIdenticon.render("парсер карт", size); assertEquals(size, image.getWidth()); assertEquals(0, image.getRGB(0, 0) >>> 24, "corner stays transparent at " + size); assertNotEquals(0, image.getRGB(size / 2, size / 2) >>> 24); }
        assertEquals("session-logic", IdentityIcons.asset(false, "Логика", null));
        assertEquals("hash:парсер карт", IdentityIcons.asset(false, "  Парсер   карт ", null));
        assertEquals("hash:project/unknown", IdentityIcons.asset(true, "Что угодно", "C:/git/unknown"));
        assertNotNull(IdentityIcons.of(false, "Парсер карт", null));
    }
    @Test void noFacetIsTooSmallOrTooThinToReadAtSidebarSize() {
        int inspected = 0;
        for (int i = 0; i < 300; i++) {
            byte[] hash = SessionIdenticon.digest("имя " + i);
            var shape = SessionIdenticon.prune(SessionIdenticon.shape(hash)); var pts = shape.points();
            assertTrue(shape.outline() >= 4, "at least four corners survive: " + i);
            assertTrue(simple(pts.subList(0, shape.outline())), "pruned outline must stay simple: " + i);
            for (var t : SessionIdenticon.triangulate(shape)) {
                double area = Math.abs(cross(pts.get(t[0]), pts.get(t[1]), pts.get(t[2]))) / 2, longest = 0;
                for (int e = 0; e < 3; e++) { var a = pts.get(t[e]); var b = pts.get(t[(e + 1) % 3]); longest = Math.max(longest, Math.hypot(a[0] - b[0], a[1] - b[1])); }
                assertTrue(area >= SessionIdenticon.MIN_AREA, "facet too small for name " + i + ": " + area);
                assertTrue(area / (longest * longest) >= SessionIdenticon.MIN_THICKNESS, "facet too thin for name " + i);
                inspected++;
            }
        }
        assertTrue(inspected > 1500, "shapes still have several facets each");
    }
}
