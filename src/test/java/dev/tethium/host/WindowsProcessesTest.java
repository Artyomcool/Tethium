package dev.tethium.host;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WindowsProcessesTest {
    @Test void seesThisProcessTheWayTheJdkDoes() {
        assumeTrue(WindowsProcesses.supported());
        var windows = new WindowsProcesses();
        var self = ProcessHandle.current();
        var rows = windows.snapshot();
        var row = rows.stream().filter(r -> r.pid() == self.pid()).findFirst().orElseThrow();
        assertEquals(self.parent().orElseThrow().pid(), row.parent());
        assertEquals(self.info().startInstant().orElseThrow().toEpochMilli(), row.started(), "start times must match or roots stop resolving");
        assertTrue(row.nanos() > 0); assertTrue(row.memory() > 0);
        assertEquals("java.exe", row.image().toLowerCase(java.util.Locale.ROOT));
        assertTrue(rows.size() > 10, "other processes are visible too: " + rows.size());
        assertTrue(rows.stream().noneMatch(r -> r.pid() == 0));
        assertTrue(windows.commandLine(self.pid()).contains("java"));
        assertNull(windows.commandLine(4_000_000), "a process that is gone gives no command line");
    }
    @Test void seesLoopbackConnectionsWithTheirOwner() throws Exception {
        assumeTrue(WindowsProcesses.supported());
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()); var client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort()); var accepted = server.accept()) {
            String port = ":" + server.getLocalPort(), other = ":" + client.getLocalPort();
            var mine = new WindowsProcesses().loopback().stream().filter(s -> s.pid() == ProcessHandle.current().pid()).toList();
            assertTrue(mine.stream().anyMatch(s -> s.local().endsWith(port) && s.remote().endsWith(other)), mine.toString());
            assertTrue(mine.stream().anyMatch(s -> s.local().endsWith(other) && s.remote().endsWith(port)), mine.toString());
        }
    }
}
