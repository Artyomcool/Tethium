package org.cef.browser;

import org.junit.jupiter.api.Test;
import java.awt.Rectangle;
import static org.junit.jupiter.api.Assertions.*;

class PopupPixelsTest {
    @Test void nativeMenuOriginUsesTheSameScaleAsItsPaintBuffer(){
        var dip=new Rectangle(40,66,240,377);
        for(double scale:new double[]{1,1.25,1.5,2}){
            var pixels=TethiumOsrBrowser.popupPixels(dip,scale);
            assertEquals(dip.x,pixels.x/scale,.5/scale+1e-9);
            assertEquals(dip.y,pixels.y/scale,.5/scale+1e-9);
            assertTrue(pixels.width>=dip.width*scale);
            assertTrue(pixels.height>=dip.height*scale);
        }
        assertEquals(new Rectangle(60,99,360,566),TethiumOsrBrowser.popupPixels(dip,1.5));
        assertEquals(new Rectangle(40,66,240,377),dip,"JCEF may mutate its input while clamping the popup");
    }

    @Test void negativeCoordinatesAndInvalidScaleDoNotMoveTheMenuToAnotherOrigin(){
        assertEquals(new Rectangle(-30,-15,150,75),TethiumOsrBrowser.popupPixels(new Rectangle(-20,-10,100,50),1.5));
        var bounds=new Rectangle(5,10,100,50);
        for(double invalid:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY})assertEquals(bounds,TethiumOsrBrowser.popupPixels(bounds,invalid));
    }
}
