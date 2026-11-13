package dev.tethium.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.*;
import java.net.*;

/** Identifies the LAN used for pairing without location, SSID, or any Android location permission. */
final class NetworkRoute {
    private NetworkRoute(){}
    static Network active(Context context){return ((ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetwork();}
    static String fingerprint(Context context,Network requested){
        try{
            var manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);Network network=requested==null?manager.getActiveNetwork():requested;if(network==null)return "";
            var properties=manager.getLinkProperties(network);if(properties==null)return "";
            for(var address:properties.getLinkAddresses())if(address.getAddress() instanceof Inet4Address){byte[] bytes=address.getAddress().getAddress();int prefix=address.getPrefixLength(),bits=prefix;for(int i=0;i<bytes.length;i++){int keep=Math.max(0,Math.min(8,bits));bytes[i]=(byte)(bytes[i]&(keep==0?0:0xff<<(8-keep)));bits-=keep;}return InetAddress.getByAddress(bytes).getHostAddress()+"/"+prefix;}
        }catch(Exception ignored){}
        return "";
    }
    static boolean home(Context context,SharedPreferences preferences,Network requested){
        var manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network=requested==null?manager.getActiveNetwork():requested;
        if(network==null)return false;
        var capabilities=manager.getNetworkCapabilities(network);
        // Old pairings may not have a LAN fingerprint yet. A cellular default network is
        // nevertheless unambiguously outside the home LAN and must use the relay.
        String saved=preferences.getString("homeNetwork","");
        return homeFor(
            capabilities!=null&&capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            capabilities!=null&&capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            saved,fingerprint(context,network));
    }
    static boolean homeFor(boolean cellular,boolean wifi,String saved,String current){return !(cellular&&!wifi)&&(saved.isEmpty()||saved.equals(current));}
}
