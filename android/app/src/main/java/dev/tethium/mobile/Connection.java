package dev.tethium.mobile;

import org.json.JSONObject;
import javax.net.ssl.*;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.*;

final class Connection {
    static final class ApiException extends IOException { final int status; ApiException(int status,String text){super(text);this.status=status;} }
    final String address,pin,token;
    private final SSLSocketFactory factory;
    private final android.net.Network network;
    Connection(String address,String pin,String token)throws Exception{this(address,pin,token,null);}
    Connection(String address,String pin,String token,android.net.Network network)throws Exception{
        this.network=network;
        if(!address.matches("[a-zA-Z0-9.\\-]+:[0-9]{1,5}")||!pin.matches("[a-f0-9]{64}"))throw new IOException("Некорректная ссылка подключения");
        this.address=address;this.pin=pin;this.token=token;
        var tls=SSLContext.getInstance("TLS");
        tls.init(null,new TrustManager[]{new X509TrustManager(){
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
            public void checkClientTrusted(X509Certificate[] c,String a)throws CertificateException{throw new CertificateException();}
            public void checkServerTrusted(X509Certificate[] c,String a)throws CertificateException{
                try{if(c.length==0||!sha(c[0].getEncoded()).equals(pin))throw new CertificateException("Изменилась подпись ноутбука. Привяжите его заново.");c[0].checkValidity();}catch(CertificateException e){throw e;}catch(Exception e){throw new CertificateException(e);}
            }
        }},null);factory=tls.getSocketFactory();
    }
    HttpsURLConnection open(String path)throws Exception{
        if(!path.startsWith("/")||path.startsWith("//"))throw new IOException("Invalid path");
        var url=new URL("https://"+address+path);var c=(HttpsURLConnection)(network==null?url.openConnection():network.openConnection(url));
        c.setSSLSocketFactory(factory);c.setHostnameVerifier((h,s)->h.equals(address.substring(0,address.lastIndexOf(':'))));c.setConnectTimeout(5000);c.setReadTimeout(path.equals("/quota/reset")?70000:15000);c.setInstanceFollowRedirects(false);
        if(token!=null&&!token.isEmpty())c.setRequestProperty("Authorization","Bearer "+token);return c;
    }
    JSONObject request(String path,JSONObject body)throws Exception{
        var c=open(path);try{
            if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(var out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int status=c.getResponseCode();InputStream stream=status<400?c.getInputStream():c.getErrorStream();String text=stream==null?"":new String(read(stream),StandardCharsets.UTF_8);
            var result=new JSONObject(text);if(status!=200)throw new ApiException(status,result.optString("error","Ошибка "+status));return result;
        }finally{c.disconnect();}
    }
    static JSONObject json(Object... pairs){var j=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)j.put((String)pairs[i],pairs[i+1]);}catch(Exception e){throw new IllegalArgumentException(e);}return j;}
    static byte[] read(InputStream in)throws IOException{var out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))>=0)out.write(b,0,n);return out.toByteArray();}
    static String sha(byte[] bytes)throws Exception{var result=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();}
}
