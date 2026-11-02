package dev.tethium.desktop;

/** Shared spacing and palette for Swing's HTML renderer (CSS 1 compatible). */
final class ChatTheme {
    private ChatTheme() {}
    static String page(String body) {
        return "<html><head><style>" +
                "body{font-family:Segoe UI;font-size:14px;color:" + Theme.hex(Theme.TEXT) + ";background:" + Theme.hex(Theme.BACKGROUND) + ";margin:8px 16px;}" +
                "p{margin:0 0 8px 0;}h1{font-size:21px;margin:12px 0 8px 0;}h2{font-size:18px;margin:12px 0 8px 0;}h3{font-size:15px;margin:12px 0 8px 0;}" +
                "a{color:" + Theme.hex(Theme.ACCENT) + ";text-decoration:none;}" +
                "pre{background:" + Theme.hex(Theme.SIDEBAR) + ";padding:12px;margin:8px 0 12px 0;font-size:13px;}" +
                "code{font-family:Consolas;font-size:13px;color:#d1dafa;}" +
                "ul,ol{margin:4px 0 8px 0;padding-left:20px;}li{margin:0 0 4px 0;}li p{margin:0 0 4px 0;}" +
                "blockquote{margin:8px 0;padding:4px 12px;border-left:2px solid " + Theme.hex(Theme.BORDER) + ";color:" + Theme.hex(Theme.MUTED) + ";}" +
                ".message{padding:12px;margin:4px 0 12px 0;}" +
                ".user{padding:12px;margin:4px 0 12px 0;background:" + Theme.hex(Theme.SURFACE) + ";border-left:2px solid " + Theme.hex(Theme.PRIMARY) + ";}" +
                ".label{font-size:11px;font-weight:bold;color:" + Theme.hex(Theme.MUTED) + ";margin:0 0 8px 0;}" +
                ".live{color:" + Theme.hex(Theme.ACCENT) + ";}.muted{color:" + Theme.hex(Theme.MUTED) + ";}" +
                "hr{border:0;height:1px;background:" + Theme.hex(Theme.BORDER) + ";margin:12px 0;}" +
                "</style></head><body>" + body + "</body></html>";
    }
}
