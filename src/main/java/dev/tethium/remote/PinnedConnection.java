package dev.tethium.remote;

import dev.tethium.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import javax.net.ssl.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.util.*;

/** Every TLS connection validates the certificate fingerprint obtained from the one-time pairing link. */
public final class PinnedConnection {
    private final URI base;private final String token;private final SSLSocketFactory factory;
    public PinnedConnection(String address,String pin,String token)throws Exception{
        base=URI.create("https://"+address);if(base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||!base.getPath().isEmpty()||!pin.matches("[a-fA-F0-9]{64}"))throw new IllegalArgumentException("Некорректная ссылка подключения");this.token=token;
        var context=SSLContext.getInstance("TLS");context.init(null,new TrustManager[]{new X509TrustManager(){
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
            public void checkClientTrusted(X509Certificate[] chain,String auth)throws CertificateException{throw new CertificateException("Client certificate unsupported");}
            public void checkServerTrusted(X509Certificate[] chain,String auth)throws CertificateException{try{if(chain.length==0||!MessageDigest.isEqual(HexFormat.of().parseHex(pin),MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded())))throw new CertificateException("Сертификат ноутбука изменился. Подключитесь заново по QR.");}catch(GeneralSecurityException e){throw new CertificateException(e);}}
        }},new SecureRandom());factory=context.getSocketFactory();
    }
    public HttpsURLConnection open(String path)throws Exception{
        if(!path.startsWith("/")||path.startsWith("//")||path.contains("\\"))throw new IllegalArgumentException("Некорректный путь API");URI target=base.resolve(path);if(!Objects.equals(target.getAuthority(),base.getAuthority()))throw new IllegalArgumentException("Другой сервер");
        var c=(HttpsURLConnection)target.toURL().openConnection();c.setSSLSocketFactory(factory);c.setHostnameVerifier((host,session)->true);c.setInstanceFollowRedirects(false);c.setConnectTimeout(5000);c.setReadTimeout(70_000);if(token!=null)c.setRequestProperty("Authorization","Bearer "+token);return c;
    }
    public JsonNode json(String path,Object body)throws Exception{
        var c=open(path);try{if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");try(var out=c.getOutputStream()){out.write(Json.MAPPER.writeValueAsBytes(body));}}int status=c.getResponseCode();try(var in=status==200?c.getInputStream():c.getErrorStream()){if(in==null)throw new IOException("Ошибка подключения: "+status);byte[] bytes=in.readNBytes(32_000_001);if(bytes.length>32_000_000)throw new IOException("Слишком большой ответ");var value=Json.parse(new String(bytes,StandardCharsets.UTF_8));if(status!=200)throw new dev.tethium.host.HostClient.ApiException(status,value.path("error").asText("Ошибка: "+status));return value;}}finally{c.disconnect();}
    }
    public static Map<String,String> pairing(String text){var uri=URI.create(text.strip());if(!"tethium".equals(uri.getScheme())||!"pair".equals(uri.getHost())||uri.getRawQuery()==null)throw new IllegalArgumentException("Вставьте ссылку из окна «Телефон» на ноутбуке");var p=new HashMap<String,String>();for(String pair:uri.getRawQuery().split("&")){var kv=pair.split("=",2);if(kv.length==2)p.put(kv[0],URLDecoder.decode(kv[1],StandardCharsets.UTF_8));}return p;}
}
