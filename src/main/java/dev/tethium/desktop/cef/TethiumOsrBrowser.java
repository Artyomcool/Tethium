package org.cef.browser;

import org.cef.CefClient;
import org.cef.handler.CefScreenInfo;
import java.awt.Rectangle;

/**
 * CEF popup bounds are DIP coordinates, while CefRenderer composites OnPaint buffers in device pixels.
 * JCEF 17e805a passes the DIP rectangle straight to its renderer, displacing native select menus on HiDPI screens.
 * This class lives in org.cef.browser because JCEF's CefBrowserOsr has package visibility.
 */
public class TethiumOsrBrowser extends CefBrowserOsr {
    public TethiumOsrBrowser(CefClient client,String url){super(client,url,false,null,null);}

    @Override public void onPopupSize(CefBrowser browser,Rectangle bounds){
        var screen=new CefScreenInfo();getScreenInfo(browser,screen);
        super.onPopupSize(browser,popupPixels(bounds,screen.device_scale_factor));
    }

    static Rectangle popupPixels(Rectangle dip,double scale){
        if(!Double.isFinite(scale)||scale<=0)scale=1;
        return new Rectangle((int)Math.round(dip.x*scale),(int)Math.round(dip.y*scale),
                (int)Math.ceil(dip.width*scale),(int)Math.ceil(dip.height*scale));
    }
}
