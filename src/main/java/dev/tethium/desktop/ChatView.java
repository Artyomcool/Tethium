package dev.tethium.desktop;

import dev.tethium.host.HostClient;
import javax.swing.*;
import java.awt.*;

/**
 * Everything the chat area keeps for one session: the rendered transcript, its scroll pane and position, navigation anchors and
 * the paged history index. The window keeps a few of these for recently shown sessions, so switching back is a component swap.
 */
final class ChatView implements AutoCloseable {
    final String sessionId;
    final ChatTranscript transcript = new ChatTranscript();
    final JScrollPane history = new JScrollPane(transcript);
    final ChatNavigation navigation = new ChatNavigation(transcript, history);
    final VirtualHistory paged;
    final JPanel area = new JPanel(new BorderLayout());
    String chatSignature = "";
    boolean scrollToLatest;
    long chatRender;
    ChatView(String sessionId, HostClient host) {
        this.sessionId = sessionId; paged = new VirtualHistory(host, transcript, history, navigation);
        history.setName("chat.history"); history.setBorder(null); history.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        history.getVerticalScrollBar().setUnitIncrement(24);
        // A scrollbar that appears and vanishes as a short chat crosses the viewport height reflows every line and makes it blink.
        history.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        area.add(history); area.add(navigation.requestMap(), BorderLayout.EAST);
    }
    @Override public void close() { paged.close(); }
}
