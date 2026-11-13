package dev.tethium.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class NetworkRouteTest {
    @Test public void cellularIsNeverMistakenForAnOldPairingWithoutFingerprint(){
        assertFalse(NetworkRoute.homeFor(true,false,"","10.112.207.180/32"));
        assertFalse(NetworkRoute.homeFor(true,false,"192.168.178.0/24","10.112.207.180/32"));
    }
    @Test public void matchingWifiRemainsDirect(){
        assertTrue(NetworkRoute.homeFor(false,true,"192.168.178.0/24","192.168.178.0/24"));
        assertTrue(NetworkRoute.homeFor(false,true,"","192.168.178.0/24"));
        assertFalse(NetworkRoute.homeFor(false,true,"192.168.178.0/24","192.168.1.0/24"));
    }
}
