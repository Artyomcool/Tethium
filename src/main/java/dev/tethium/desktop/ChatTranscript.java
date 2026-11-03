package dev.tethium.desktop;

import javax.swing.*;
import javax.swing.text.*;
import javax.swing.text.html.*;

/** HTML chat that wraps even preformatted lines and unbroken paths to the viewport. */
final class ChatTranscript extends JEditorPane {
    boolean virtualScrolling,renderingPage;
    ChatLayoutTrace layoutTrace;
    @Override public void setText(String value){long start=System.nanoTime();super.setText(value);if(layoutTrace!=null)layoutTrace.document(value==null?0:value.length(),value==null?0:value.hashCode(),System.nanoTime()-start);}
    @Override public void setBounds(int x,int y,int width,int height){boolean changed=x!=getX()||y!=getY()||width!=getWidth()||height!=getHeight();super.setBounds(x,y,width,height);if(changed&&layoutTrace!=null)layoutTrace.event("text.bounds","stack",ChatLayoutTrace.stack());}

    void imageCopyMenu(JPopupMenu textMenu,java.util.function.Consumer<String> copy){
        addMouseListener(new java.awt.event.MouseAdapter(){
            private void popup(java.awt.event.MouseEvent e){
                if(!e.isPopupTrigger())return;String source=imageSourceAt(e.getPoint());
                var menu=textMenu;
                if(source!=null){menu=new JPopupMenu();menu.add(new AbstractAction("Копировать изображение"){public void actionPerformed(java.awt.event.ActionEvent action){copy.accept(source);}});}
                menu.show(ChatTranscript.this,e.getX(),e.getY());e.consume();
            }
            public void mousePressed(java.awt.event.MouseEvent e){popup(e);}
            public void mouseReleased(java.awt.event.MouseEvent e){popup(e);}
        });
    }
    String imageSourceAt(java.awt.Point point){
        var insets=getInsets();return imageSourceAt(getUI().getRootView(this),new java.awt.Rectangle(insets.left,insets.top,getWidth()-insets.left-insets.right,getHeight()-insets.top-insets.bottom),point);
    }
    private static String imageSourceAt(View view,java.awt.Shape area,java.awt.Point point){
        if(area==null||!area.getBounds().contains(point))return null;
        if(view instanceof ImageView){var attrs=view.getElement().getAttributes();Object source=attrs.getAttribute("data-original");if(source==null)source=attrs.getAttribute(HTML.Attribute.SRC);return source==null?null:source.toString();}
        for(int i=0;i<view.getViewCount();i++){String source=imageSourceAt(view.getView(i),view.getChildAllocation(i,area),point);if(source!=null)return source;}return null;
    }
    @Override public void scrollRectToVisible(java.awt.Rectangle area){boolean allowed=!virtualScrolling||isFocusOwner()&&!renderingPage;if(layoutTrace!=null)layoutTrace.event("caret.scrollRequest","allowed",allowed,"targetY",area.y,"targetH",area.height,"stack",ChatLayoutTrace.stack());if(allowed)super.scrollRectToVisible(area);}
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
