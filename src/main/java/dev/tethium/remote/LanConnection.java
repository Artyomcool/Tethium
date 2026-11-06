package dev.tethium.remote;

import dev.tethium.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Plain HTTP on the home LAN; the device token obtained from the one-time pairing link is the only credential. */
public final class LanConnection {
    private final URI base;private final String token;
    public LanConnection(String address,String token){
        base=URI.create("http://"+address);if(base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||!base.getPath().isEmpty())throw new IllegalArgumentException("Некорректная ссылка подключения");this.token=token;
    }
    public HttpURLConnection open(String path)throws Exception{
        if(!path.startsWith("/")||path.startsWith("//")||path.contains("\\"))throw new IllegalArgumentException("Некорректный путь API");URI target=base.resolve(path);if(!Objects.equals(target.getAuthority(),base.getAuthority()))throw new IllegalArgumentException("Другой сервер");
        var c=(HttpURLConnection)target.toURL().openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(5000);c.setReadTimeout(70_000);if(token!=null)c.setRequestProperty("Authorization","Bearer "+token);return c;
    }
    public JsonNode json(String path,Object body)throws Exception{
        var c=open(path);try{if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");try(var out=c.getOutputStream()){out.write(Json.MAPPER.writeValueAsBytes(body));}}int status=c.getResponseCode();try(var in=status==200?c.getInputStream():c.getErrorStream()){if(in==null)throw new IOException("Ошибка подключения: "+status);byte[] bytes=in.readNBytes(32_000_001);if(bytes.length>32_000_000)throw new IOException("Слишком большой ответ");var value=Json.parse(new String(bytes,StandardCharsets.UTF_8));if(status!=200)throw new dev.tethium.host.HostClient.ApiException(status,value.path("error").asText("Ошибка: "+status));return value;}}finally{c.disconnect();}
    }
    public static Map<String,String> pairing(String text){var uri=URI.create(text.strip());if(!"tethium".equals(uri.getScheme())||!"pair".equals(uri.getHost())||uri.getRawQuery()==null)throw new IllegalArgumentException("Вставьте ссылку из окна «Телефон» на ноутбуке");var p=new HashMap<String,String>();for(String pair:uri.getRawQuery().split("&")){var kv=pair.split("=",2);if(kv.length==2)p.put(kv[0],URLDecoder.decode(kv[1],StandardCharsets.UTF_8));}return p;}
    /** The link's address names the phone channel; http= names the port this client talks to. */
    public static String httpAddress(Map<String,String> p){String address=p.get("address");if(address==null||!address.contains(":"))throw new IllegalArgumentException("В ссылке нет адреса ноутбука");String http=p.get("http");return http==null?address:address.substring(0,address.lastIndexOf(':'))+":"+http;}
}
