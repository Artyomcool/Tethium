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
        installCopyButton();
    }

    // A command line or a code block offers a copy button while the pointer is over it.
    private final JButton copy=new JButton(new CopyIcon(false));
    private Element hovered;
    private final Timer copied=new Timer(1200,e->copy.setIcon(new CopyIcon(false)));
    private void installCopyButton(){
        setLayout(null);copy.setFocusable(false);copy.setName("chat.copyBlock");copy.putClientProperty("JButton.buttonType","roundRect");copy.setMargin(new java.awt.Insets(3,3,3,3));
        copy.setToolTipText("Скопировать в буфер обмена");copy.setVisible(false);copied.setRepeats(false);add(copy);
        copy.addActionListener(e->{if(hovered==null)return;String text=copyText(hovered);if(text.isEmpty())return;
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text),null);copy.setIcon(new CopyIcon(true));copied.restart();});
        addMouseMotionListener(new java.awt.event.MouseMotionAdapter(){@Override public void mouseMoved(java.awt.event.MouseEvent e){hover(e.getPoint());}});
        addMouseListener(new java.awt.event.MouseAdapter(){@Override public void mouseExited(java.awt.event.MouseEvent e){if(!copy.getBounds().contains(e.getPoint()))hideCopy();}});
    }
    /** Two overlapping sheets; a check mark for a moment after copying. */
    private record CopyIcon(boolean done) implements Icon {
        public int getIconWidth(){return 14;}
        public int getIconHeight(){return 14;}
        public void paintIcon(java.awt.Component c,java.awt.Graphics graphics,int x,int y){
            var g=(java.awt.Graphics2D)graphics.create();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,java.awt.RenderingHints.VALUE_ANTIALIAS_ON);g.translate(x,y);
            g.setStroke(new java.awt.BasicStroke(1.4f,java.awt.BasicStroke.CAP_ROUND,java.awt.BasicStroke.JOIN_ROUND));
            if(done){g.setColor(Theme.ACCENT);g.drawPolyline(new int[]{2,6,12},new int[]{7,11,3},3);}
            else{g.setColor(Theme.MUTED);g.drawPolyline(new int[]{3,1,1,9,9},new int[]{9,9,1,1,3},5);g.setColor(Theme.TEXT);g.drawRoundRect(5,5,8,8,2,2);}
            g.dispose();
        }
    }
    private void hideCopy(){hovered=null;copy.setVisible(false);}
    private void hover(java.awt.Point point){
        int position=viewToModel2D(point);if(position<0||!(getDocument() instanceof HTMLDocument document)){hideCopy();return;}
        Element block=copyable(document.getParagraphElement(position));
        java.awt.Rectangle area=block==null?null:area(block);
        if(area==null||point.y<area.y||point.y>area.y+area.height){hideCopy();return;}
        hovered=block;var size=copy.getPreferredSize();var insets=getInsets();
        int x=getWidth()-insets.right-size.width-6,y=Math.max(area.y,area.y+(Math.min(area.height,28)-size.height)/2);
        copy.setBounds(x,y,size.width,size.height);if(!copy.isVisible()){copy.setIcon(new CopyIcon(false));copy.setVisible(true);}
    }
    /** A command line of the agent, or a block of code; null for ordinary text. */
    static Element copyable(Element element){
        for(var e=element;e!=null;e=e.getParentElement()){
            if("pre".equals(e.getName()))return e;
            if("p".equals(e.getName()))return "action".equals(String.valueOf(e.getAttributes().getAttribute(HTML.Attribute.CLASS)))?e:null;
        }
        return null;
    }
    private java.awt.Rectangle area(Element block){
        try{var first=modelToView2D(block.getStartOffset());var last=modelToView2D(Math.max(block.getStartOffset(),block.getEndOffset()-1));if(first==null||last==null)return null;return first.getBounds().union(last.getBounds());}catch(BadLocationException e){return null;}
    }
    /** For a command line only the command itself, without its "Команда: " label. */
    String copyText(Element block){
        try{String text=getDocument().getText(block.getStartOffset(),block.getEndOffset()-block.getStartOffset()).strip();
            return "pre".equals(block.getName())?text:text.replaceFirst("^(Команда|Файлы|Инструмент|Поиск): ","");}
        catch(BadLocationException e){return "";}
    }

    @Override public boolean getScrollableTracksViewportWidth() { return true; }
}
