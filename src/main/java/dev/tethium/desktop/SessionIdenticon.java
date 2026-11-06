package dev.tethium.desktop;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic "origami" icon for a session name without a drawn identity. The hash picks one hue, a star-shaped outline
 * (4..8 corners in angular order with varied radii, so concave corners happen but the outline never crosses itself), a few extra
 * points on the outline and inner vertices. A fan from the centre plus Delaunay edge flips inside the outline gives large facets,
 * each in its own shade of the hue. The Android renderer repeats the same arithmetic, so both screens show the same figure.
 */
final class SessionIdenticon {
    private SessionIdenticon() {}
    /** One facet in unit coordinates (0..1) with its lightness. */
    record Facet(float[] xs, float[] ys, float lightness, float hue) {}
    /** Outline vertices (angular order), then the centre, then inner points; outline count marks where the boundary ends. */
    record Shape(List<float[]> points, int outline) {}
    static byte[] digest(String key) { try { return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)); } catch (Exception e) { throw new IllegalStateException(e); } }
    static float hue(byte[] hash) { return (hash[0] & 0xff) / 255f; }
    /** A second hue for about a third of the facets: analogous, triadic or complementary to the first, chosen by the hash. */
    static final float[] HUE_OFFSETS = {0.09f, 0.33f, 0.5f};
    static float secondHue(byte[] hash) { float h = hue(hash) + HUE_OFFSETS[(hash[5] & 0xff) % HUE_OFFSETS.length]; return h - (float) Math.floor(h); }
    static final float[] SHADES = {0.30f, 0.40f, 0.50f, 0.60f, 0.70f, 0.80f};
    /** xorshift32 seeded from the hash: every renderer draws the same sequence. */
    static final class Rng {
        private int state;
        Rng(byte[] hash) { state = ((hash[1] & 0xff) << 24 | (hash[2] & 0xff) << 16 | (hash[3] & 0xff) << 8 | (hash[4] & 0xff)) | 1; }
        int next(int bound) { state ^= state << 13; state ^= state >>> 17; state ^= state << 5; return Math.floorMod(state, bound); }
        float unit() { return next(1 << 20) / (float) (1 << 20); }
    }
    static Shape shape(byte[] hash) {
        var rng = new Rng(hash);
        int corners = 4 + rng.next(5), inner = rng.next(3), extra = rng.next(3);
        float rotation = rng.unit() * (float) (Math.PI * 2), step = (float) (Math.PI * 2 / corners);
        var angles = new ArrayList<Float>(); var radii = new ArrayList<Float>();
        for (int i = 0; i < corners; i++) { angles.add(rotation + i * step + (rng.unit() - 0.5f) * 0.25f * step); radii.add(0.33f + rng.unit() * 0.14f); }
        // Notches: points on an edge pulled towards the centre guarantee concave corners while the outline stays star-shaped.
        int notches = corners >= 6 ? 1 + rng.next(2) : 1; int first = rng.next(corners);
        for (int k = 0; k < notches; k++) {
            int at = (first + k * (corners / 2)) % angles.size(); int next = (at + 1) % angles.size(); float t = 0.35f + rng.unit() * 0.3f;
            float ax = 0.5f + radii.get(at) * (float) Math.cos(angles.get(at)), ay = 0.5f + radii.get(at) * (float) Math.sin(angles.get(at));
            float bx = 0.5f + radii.get(next) * (float) Math.cos(angles.get(next)), by = 0.5f + radii.get(next) * (float) Math.sin(angles.get(next));
            float px = ax + (bx - ax) * t - 0.5f, py = ay + (by - ay) * t - 0.5f;
            float angle = (float) Math.atan2(py, px), radius = Math.max(0.18f, (float) Math.hypot(px, py) * (0.45f + rng.unit() * 0.2f));
            float base = angles.get(at); while (angle < base) angle += (float) (Math.PI * 2); while (angle > base + (float) (Math.PI * 2)) angle -= (float) (Math.PI * 2);
            angles.add(at + 1, angle); radii.add(at + 1, radius);
        }
        // Extra outline points sit on existing edges, so the outline stays simple; they are kept in angular order.
        for (int e = 0; e < extra; e++) {
            int k = rng.next(angles.size()); int next = (k + 1) % angles.size(); float t = 0.3f + rng.unit() * 0.4f;
            float ax = 0.5f + radii.get(k) * (float) Math.cos(angles.get(k)), ay = 0.5f + radii.get(k) * (float) Math.sin(angles.get(k));
            float bx = 0.5f + radii.get(next) * (float) Math.cos(angles.get(next)), by = 0.5f + radii.get(next) * (float) Math.sin(angles.get(next));
            float px = ax + (bx - ax) * t - 0.5f, py = ay + (by - ay) * t - 0.5f;
            float angle = (float) Math.atan2(py, px), radius = (float) Math.hypot(px, py);
            // Keep the angle within the sector it belongs to (wrap-aware), then insert after corner k.
            float base = angles.get(k); while (angle < base) angle += (float) (Math.PI * 2); while (angle > base + (float) (Math.PI * 2)) angle -= (float) (Math.PI * 2);
            angles.add(k + 1, angle); radii.add(k + 1, radius);
        }
        var points = new ArrayList<float[]>();
        for (int i = 0; i < angles.size(); i++) points.add(new float[]{0.5f + radii.get(i) * (float) Math.cos(angles.get(i)), 0.5f + radii.get(i) * (float) Math.sin(angles.get(i))});
        int outline = points.size();
        points.add(new float[]{0.5f, 0.5f});
        for (int j = 0; j < inner; j++) {
            int k = rng.next(outline); int next = (k + 1) % outline;
            float r = 0.45f * Math.min(radii.get(k), radii.get(next)) * (0.3f + rng.unit() * 0.7f);
            float a = angles.get(k) + (angles.get(next) - angles.get(k) + (next == 0 ? (float) (Math.PI * 2) : 0)) * (0.25f + rng.unit() * 0.5f);
            float[] p = {0.5f + r * (float) Math.cos(a), 0.5f + r * (float) Math.sin(a)};
            boolean crowded = false; for (var q : points) if (Math.hypot(q[0] - p[0], q[1] - p[1]) < 0.13) crowded = true;
            if (!crowded) points.add(p);
        }
        return new Shape(points, outline);
    }
    /** Fan from the centre, inner points split their triangle, then Lawson flips on interior edges only. */
    static List<int[]> triangulate(Shape shape) {
        var pts = shape.points(); int n = shape.outline(), centre = n;
        var triangles = new ArrayList<int[]>();
        for (int i = 0; i < n; i++) triangles.add(new int[]{i, (i + 1) % n, centre});
        for (int p = centre + 1; p < pts.size(); p++) {
            int[] host = null; for (var t : triangles) if (contains(pts, t, pts.get(p))) { host = t; break; }
            if (host == null) continue;
            triangles.remove(host);
            triangles.add(new int[]{host[0], host[1], p}); triangles.add(new int[]{host[1], host[2], p}); triangles.add(new int[]{host[2], host[0], p});
        }
        for (int pass = 0; pass < 24; pass++) {
            boolean flipped = false;
            outer:
            for (int i = 0; i < triangles.size(); i++) for (int j = i + 1; j < triangles.size(); j++) {
                int[] a = triangles.get(i), b = triangles.get(j); int[] shared = sharedEdge(a, b); if (shared == null) continue;
                if (shared[0] < n && shared[1] < n && (shared[1] == (shared[0] + 1) % n || shared[0] == (shared[1] + 1) % n)) continue; // outline edge
                int oa = opposite(a, shared), ob = opposite(b, shared);
                if (!convexQuad(pts, shared[0], oa, shared[1], ob)) continue;
                if (!inCircumcircle(pts, a, pts.get(ob))) continue;
                triangles.set(i, new int[]{shared[0], oa, ob}); triangles.set(j, new int[]{shared[1], ob, oa}); flipped = true; break outer;
            }
            if (!flipped) break;
        }
        return triangles;
    }
    private static int[] sharedEdge(int[] a, int[] b) { for (int e = 0; e < 3; e++) { int u = a[e], v = a[(e + 1) % 3]; for (int f = 0; f < 3; f++) { int x = b[f], y = b[(f + 1) % 3]; if (u == x && v == y || u == y && v == x) return new int[]{u, v}; } } return null; }
    private static int opposite(int[] t, int[] edge) { for (int v : t) if (v != edge[0] && v != edge[1]) return v; return -1; }
    private static double cross(float[] o, float[] a, float[] b) { return (double) (a[0] - o[0]) * (b[1] - o[1]) - (double) (a[1] - o[1]) * (b[0] - o[0]); }
    private static boolean contains(List<float[]> pts, int[] t, float[] p) {
        double d1 = cross(pts.get(t[0]), pts.get(t[1]), p), d2 = cross(pts.get(t[1]), pts.get(t[2]), p), d3 = cross(pts.get(t[2]), pts.get(t[0]), p);
        return !((d1 < 0 || d2 < 0 || d3 < 0) && (d1 > 0 || d2 > 0 || d3 > 0));
    }
    private static boolean convexQuad(List<float[]> pts, int a, int b, int c, int d) {
        // a-b-c-d in order around the quad; convex when consecutive turns keep one sign.
        double s1 = cross(pts.get(a), pts.get(b), pts.get(c)), s2 = cross(pts.get(b), pts.get(c), pts.get(d)), s3 = cross(pts.get(c), pts.get(d), pts.get(a)), s4 = cross(pts.get(d), pts.get(a), pts.get(b));
        return s1 > 1e-9 && s2 > 1e-9 && s3 > 1e-9 && s4 > 1e-9 || s1 < -1e-9 && s2 < -1e-9 && s3 < -1e-9 && s4 < -1e-9;
    }
    private static boolean inCircumcircle(List<float[]> pts, int[] t, float[] p) {
        double ax = pts.get(t[0])[0] - p[0], ay = pts.get(t[0])[1] - p[1], bx = pts.get(t[1])[0] - p[0], by = pts.get(t[1])[1] - p[1], cx = pts.get(t[2])[0] - p[0], cy = pts.get(t[2])[1] - p[1];
        double det = (ax * ax + ay * ay) * (bx * cy - cx * by) - (bx * bx + by * by) * (ax * cy - cx * ay) + (cx * cx + cy * cy) * (ax * by - bx * ay);
        double orient = cross(pts.get(t[0]), pts.get(t[1]), pts.get(t[2]));
        return orient > 0 ? det > 1e-12 : det < -1e-12;
    }
    /** Facets smaller than this share of the unit square, or thinner than this area/longest-edge² ratio, read as noise at 22 px. */
    static final double MIN_AREA = 0.025, MIN_THICKNESS = 0.14;
    private static double area(List<float[]> pts, int[] t) { return Math.abs(cross(pts.get(t[0]), pts.get(t[1]), pts.get(t[2]))) / 2; }
    private static double longestEdge(List<float[]> pts, int[] t) {
        double m = 0; for (int e = 0; e < 3; e++) { var a = pts.get(t[e]); var b = pts.get(t[(e + 1) % 3]); m = Math.max(m, Math.hypot(a[0] - b[0], a[1] - b[1])); } return m;
    }
    private static boolean tiny(List<float[]> pts, int[] t) { double a = area(pts, t), l = longestEdge(pts, t); return a < MIN_AREA || l > 0 && a / (l * l) < MIN_THICKNESS; }
    /** Removes the vertex that causes the smallest facet until every facet is readable; the centre and at least four outline corners stay. */
    static Shape prune(Shape shape) {
        var pts = new ArrayList<>(shape.points()); int outline = shape.outline();
        for (int guard = 0; guard < 24; guard++) {
            var triangles = triangulate(new Shape(pts, outline));
            int[] worst = null; double worstArea = Double.MAX_VALUE;
            for (var t : triangles) if (tiny(pts, t)) { double a = area(pts, t); if (a < worstArea) { worstArea = a; worst = t; } }
            if (worst == null) break;
            int victim = -1;
            for (int v : worst) if (v > outline) { victim = v; break; } // inner points go first
            if (victim < 0 && outline > 4) {
                // The outline vertex closest to the line between its neighbours changes the silhouette least.
                double best = Double.MAX_VALUE;
                for (int v : worst) {
                    if (v >= outline) continue;
                    var prev = pts.get((v + outline - 1) % outline); var next = pts.get((v + 1) % outline);
                    double d = Math.abs(cross(prev, next, pts.get(v))) / Math.max(1e-6, Math.hypot(next[0] - prev[0], next[1] - prev[1]));
                    if (d < best) { best = d; victim = v; }
                }
            }
            if (victim < 0) break;
            pts.remove(victim); if (victim < outline) outline--;
        }
        return new Shape(pts, outline);
    }
    static List<Facet> facets(byte[] hash) {
        var shape = prune(shape(hash)); var pts = shape.points(); var triangles = triangulate(shape); var rng = new Rng(hash); for (int i = 0; i < 64; i++) rng.next(2);
        var result = new ArrayList<Facet>(); int previous = -1; float first = hue(hash), second = secondHue(hash); boolean usedSecond = false;
        for (var t : triangles) {
            int shade = rng.next(SHADES.length); if (shade == previous) shade = (shade + 2) % SHADES.length; previous = shade;
            boolean other = rng.next(3) == 0; usedSecond |= other;
            result.add(new Facet(new float[]{pts.get(t[0])[0], pts.get(t[1])[0], pts.get(t[2])[0]}, new float[]{pts.get(t[0])[1], pts.get(t[1])[1], pts.get(t[2])[1]}, SHADES[shade], other ? second : first));
        }
        // Every figure shows both hues: when chance gave none to the second, the last facet takes it.
        if (!usedSecond && !result.isEmpty()) { var last = result.getLast(); result.set(result.size() - 1, new Facet(last.xs(), last.ys(), last.lightness(), second)); }
        return result;
    }
    static Color hsl(float h, float s, float l) {
        float c = (1 - Math.abs(2 * l - 1)) * s, x = c * (1 - Math.abs((h * 6) % 2 - 1)), m = l - c / 2; float r, g, b; int k = (int) (h * 6) % 6;
        switch (k) { case 0 -> { r = c; g = x; b = 0; } case 1 -> { r = x; g = c; b = 0; } case 2 -> { r = 0; g = c; b = x; } case 3 -> { r = 0; g = x; b = c; } case 4 -> { r = x; g = 0; b = c; } default -> { r = c; g = 0; b = x; } }
        return new Color(clamp(r + m), clamp(g + m), clamp(b + m));
    }
    private static float clamp(float v) { return Math.max(0, Math.min(1, v)); }
    static BufferedImage render(String key, int size) {
        byte[] hash = digest(key); float hue = hue(hash); var facets = facets(hash);
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB); var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        var shadow = new Path2D.Float();
        for (var f : facets) { shadow.moveTo(f.xs()[0] * size, f.ys()[0] * size + size * 0.05f); shadow.lineTo(f.xs()[1] * size, f.ys()[1] * size + size * 0.05f); shadow.lineTo(f.xs()[2] * size, f.ys()[2] * size + size * 0.05f); shadow.closePath(); }
        g.setColor(new Color(0, 0, 0, 70)); g.fill(shadow);
        float seam = Math.max(0.8f, size * 0.018f); var seamColor = hsl(hue, 0.55f, 0.22f); var seamPaint = new Color(seamColor.getRed(), seamColor.getGreen(), seamColor.getBlue(), 150);
        for (var f : facets) {
            var path = new Path2D.Float(); path.moveTo(f.xs()[0] * size, f.ys()[0] * size); path.lineTo(f.xs()[1] * size, f.ys()[1] * size); path.lineTo(f.xs()[2] * size, f.ys()[2] * size); path.closePath();
            g.setColor(hsl(f.hue(), 0.55f, f.lightness())); g.fill(path);
            g.setColor(seamPaint); g.setStroke(new BasicStroke(seam, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(path);
        }
        g.dispose(); return image;
    }
}
