package dev.tethium.desktop;

import com.formdev.flatlaf.FlatDarkLaf;
import javax.swing.*;
import java.awt.*;

public final class Theme {
    public static final int UNIT = 4, GAP = 8, INSET = 12, GUTTER = 16;
    public static final Color BACKGROUND = new Color(0x222326), SIDEBAR = new Color(0x1b1c1f),
            SURFACE = new Color(0x292b30), FIELD = new Color(0x2e3036), BORDER = new Color(0x3e4149),
            TEXT = new Color(0xeeeef2), MUTED = new Color(0xa3a7b3), ACCENT = new Color(0xa8baff),
            PRIMARY = new Color(0x829aee), SELECTED = new Color(0x343d59),
            GREEN = new Color(0x9bcbb2), AMBER = new Color(0xe4bd82), RED = new Color(0xe4a2a4);
    private Theme() {}
    public static void install() {
        FlatDarkLaf.setup();
        UIManager.put("defaultFont", new Font("Segoe UI", Font.PLAIN, 13));
        UIManager.put("Panel.background", BACKGROUND); UIManager.put("Label.foreground", TEXT);
        UIManager.put("Component.foreground", TEXT); UIManager.put("Component.borderColor", BORDER);
        UIManager.put("Component.focusColor", PRIMARY); UIManager.put("Component.focusedBorderColor", PRIMARY);
        UIManager.put("Component.arc", 10); UIManager.put("Component.focusWidth", 1);
        UIManager.put("Button.arc", 10); UIManager.put("Button.background", SURFACE); UIManager.put("Button.foreground", TEXT);
        UIManager.put("Button.borderColor", BORDER); UIManager.put("Button.focusedBorderColor", PRIMARY);
        UIManager.put("Button.hoverBackground", new Color(0x3b3e47)); UIManager.put("Button.pressedBackground", SELECTED);
        UIManager.put("Button.toolbar.hoverBackground", SURFACE); UIManager.put("Button.toolbar.pressedBackground", SELECTED);
        UIManager.put("Button.margin", new Insets(3, 8, 3, 8)); UIManager.put("Button.minimumWidth", 0);
        for (String type : new String[]{"TextField", "TextArea", "PasswordField", "FormattedTextField", "ComboBox", "Spinner"}) {
            UIManager.put(type + ".background", FIELD); UIManager.put(type + ".foreground", TEXT);
            UIManager.put(type + ".selectionBackground", SELECTED); UIManager.put(type + ".selectionForeground", TEXT);
            UIManager.put(type + ".caretForeground", ACCENT);
        }
        UIManager.put("TextComponent.arc", 10); UIManager.put("TextField.margin", new Insets(3, 8, 3, 8));
        UIManager.put("ComboBox.padding", new Insets(3, 8, 3, 8)); UIManager.put("ComboBox.buttonBackground", FIELD);
        UIManager.put("Tree.background", SIDEBAR); UIManager.put("Tree.foreground", TEXT); UIManager.put("Tree.rowHeight", 28);
        UIManager.put("Tree.selectionBackground", SELECTED); UIManager.put("Tree.selectionForeground", TEXT);
        UIManager.put("Tree.selectionInactiveBackground", SELECTED); UIManager.put("Tree.selectionArc", 8);
        UIManager.put("Tree.selectionInsets", new Insets(0, 4, 0, 4)); UIManager.put("Tree.paintLines", false); UIManager.put("Tree.wideSelection", true);
        UIManager.put("List.background", SURFACE); UIManager.put("List.selectionBackground", SELECTED); UIManager.put("List.selectionForeground", TEXT);
        UIManager.put("List.selectionInactiveBackground", SELECTED); UIManager.put("List.selectionInactiveForeground", TEXT);
        UIManager.put("ScrollPane.background", BACKGROUND); UIManager.put("Viewport.background", BACKGROUND);
        UIManager.put("ScrollBar.width", 8); UIManager.put("ScrollBar.track", BACKGROUND); UIManager.put("ScrollBar.thumb", BORDER);
        UIManager.put("ScrollBar.thumbArc", 8); UIManager.put("ScrollBar.trackArc", 8);
        UIManager.put("SplitPane.background", SIDEBAR); UIManager.put("SplitPaneDivider.gripDotCount", 0);
        UIManager.put("ProgressBar.background", BORDER); UIManager.put("ProgressBar.foreground", PRIMARY); UIManager.put("ProgressBar.arc", 4);
        UIManager.put("PopupMenu.background", SURFACE); UIManager.put("MenuItem.background", SURFACE);
        UIManager.put("MenuItem.selectionBackground", SELECTED); UIManager.put("ToolTip.background", SURFACE); UIManager.put("ToolTip.foreground", TEXT);
        // Swing hides tooltips after 4 s by default; here they stay until the pointer leaves the component.
        ToolTipManager.sharedInstance().setDismissDelay(Integer.MAX_VALUE); ToolTipManager.sharedInstance().setInitialDelay(400); ToolTipManager.sharedInstance().setReshowDelay(100);
        UIManager.put("TitlePane.background", SIDEBAR); UIManager.put("TitlePane.foreground", MUTED); UIManager.put("TitlePane.unifiedBackground", false);
        JFrame.setDefaultLookAndFeelDecorated(true);
    }
    public static JButton button(String text, String tooltip, Runnable action) {
        var button = new JButton(text); button.setToolTipText(tooltip); button.addActionListener(e -> action.run());
        button.getAccessibleContext().setAccessibleName(text.isBlank() ? tooltip : text); return button;
    }
    static JButton iconButton(String icon, String tooltip, Runnable action) {
        var button = button("", tooltip, action); button.setIcon(icon(icon)); toolbar(button); return button;
    }
    static void toolbar(JButton button) { button.putClientProperty("JButton.buttonType", "toolBarButton"); button.setMargin(new Insets(3, 6, 3, 6)); }
    static void primary(JButton button) {
        button.setBackground(PRIMARY); button.setForeground(new Color(0x161c32));
        button.putClientProperty("FlatLaf.style", "borderColor: #829aee; hoverBackground: #9baff7; pressedBackground: #7089dd; focusedBorderColor: #b7c6ff");
    }
    static Icon icon(String name) { return UiIcons.of(name, 16); }
    public static Icon menuArrowIcon() {
        return new Icon() {
            public int getIconWidth() { return 10; }
            public int getIconHeight() { return 10; }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                var canvas = (Graphics2D) g.create(); canvas.translate(x, y); canvas.setColor(c.getForeground());
                canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                canvas.fillPolygon(new int[]{1, 9, 5}, new int[]{3, 3, 7}, 3); canvas.dispose();
            }
        };
    }
    public static Icon previewIcon(boolean expand) { return icon(expand ? "preview-expand" : "preview-collapse"); }
    static String hex(Color color) { return "#%06x".formatted(color.getRGB() & 0xffffff); }
    public static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
}

