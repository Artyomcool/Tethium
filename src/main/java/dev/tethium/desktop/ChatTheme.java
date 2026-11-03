package dev.tethium.desktop;

/** Shared spacing and palette for Swing's HTML renderer (CSS 1 compatible). */
final class ChatTheme {
    private ChatTheme() {}
    /** Assistant messages are labelled by the executor that produced them; unknown or legacy values stay as stored. */
    static String executorLabel(String provider) {
        if (provider == null || provider.isBlank() || provider.equals("null")) return "CODEX";
        return provider.toUpperCase(java.util.Locale.ROOT);
    }
    static String page(String body) {
        return "<html><head><style>" +
                "body{font-family:Segoe UI;font-size:14px;color:" + Theme.hex(Theme.TEXT) + ";background:" + Theme.hex(Theme.BACKGROUND) + ";margin:8px 16px;}" +
                "p{margin:0 0 5px 0;}h1{font-size:21px;margin:12px 0 8px 0;}h2{font-size:18px;margin:12px 0 8px 0;}h3{font-size:15px;margin:12px 0 8px 0;}" +
                "a{color:" + Theme.hex(Theme.ACCENT) + ";text-decoration:none;}" +
                "pre{background:" + Theme.hex(Theme.SIDEBAR) + ";padding:12px;margin:8px 0 12px 0;font-size:13px;}" +
                "code{font-family:Consolas;font-size:13px;color:#d1dafa;}" +
                "ul,ol{margin:4px 0 8px 0;padding-left:20px;}li{margin:0 0 4px 0;}li p{margin:0 0 4px 0;}" +
                "blockquote{margin:8px 0;padding:4px 12px;border-left:2px solid " + Theme.hex(Theme.BORDER) + ";color:" + Theme.hex(Theme.MUTED) + ";}" +
                ".message{padding:8px;margin:2px 0 8px 0;}" +
                ".user{padding:8px;margin:2px 0 8px 0;background:" + Theme.hex(Theme.SURFACE) + ";border-left:2px solid " + Theme.hex(Theme.PRIMARY) + ";}" +
                ".label{font-size:11px;font-weight:bold;color:" + Theme.hex(Theme.MUTED) + ";margin:0 0 5px 0;}" +
                ".time{font-weight:normal;}.gallery{margin:0;}.gallery img{margin:0 6px 6px 0;}" +
                ".live{color:" + Theme.hex(Theme.ACCENT) + ";}.muted{color:" + Theme.hex(Theme.MUTED) + ";}" +
                "table{border-collapse:collapse;margin:12px 0;}th,td{border:1px solid #555b68;padding:6px 10px;text-align:left;}th{background:#303541;}" +
                "hr{border:0;height:1px;background:" + Theme.hex(Theme.BORDER) + ";margin:12px 0;}" +
                "</style></head><body>" + body + "</body></html>";
    }
}
