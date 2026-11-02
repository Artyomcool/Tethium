package dev.tethium.desktop;

import javax.swing.*;
import javax.swing.text.*;
import javax.swing.text.html.*;

/** HTML chat that wraps even preformatted lines and unbroken paths to the viewport. */
final class ChatTranscript extends JEditorPane {
    ChatTranscript() {
        setEditorKit(new HTMLEditorKit() {
            private final ViewFactory factory = new HTMLFactory() {
                @Override public View create(Element element) {
                    View view = super.create(element);
                    // Swing's LineView for <pre> lays out at Integer.MAX_VALUE and refuses to shrink.
                    // Use flowing paragraphs while keeping the original document (and copied text) intact.
                    if (view instanceof javax.swing.text.html.ParagraphView) {
                        return new javax.swing.text.html.ParagraphView(element) {
                            @Override protected SizeRequirements calculateMinorAxisRequirements(int axis, SizeRequirements r) {
                                r = super.calculateMinorAxisRequirements(axis, r);
                                r.minimum = 0;
                                return r;
                            }
                        };
                    }
                    if (view instanceof InlineView) {
                        return new InlineView(element) {
                            @Override public float getMinimumSpan(int axis) {
                                return axis == View.X_AXIS ? 0 : super.getMinimumSpan(axis);
                            }
                        };
                    }
                    return view;
                }
            };
            @Override public ViewFactory getViewFactory() { return factory; }
        });
        setEditable(false);
        setBackground(Theme.BACKGROUND);
        putClientProperty(HONOR_DISPLAY_PROPERTIES, true);
        putClientProperty(W3C_LENGTH_UNITS, true);
        setBorder(BorderFactory.createEmptyBorder());
        ((DefaultCaret) getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        setName("chat.transcript");
    }

    @Override public boolean getScrollableTracksViewportWidth() { return true; }
}
