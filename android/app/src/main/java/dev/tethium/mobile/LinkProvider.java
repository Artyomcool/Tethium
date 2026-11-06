package dev.tethium.mobile;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import org.json.JSONArray;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Our other apps reach their laptop service through this provider and never see an address or a token: Tethium attaches them,
 * recovers from a revoked token and returns the answer. The caller is identified by Android, and the laptop issues the token only
 * for the package and signing key its project published. Bodies travel as files, so a call never depends on the caller's speed.
 */
public final class LinkProvider extends ContentProvider {
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS");
    private static final Set<String> NOT_SENT = Set.of("authorization", "connection", "content-length", "expect", "host", "keep-alive", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade");
    private static final int READ_MILLIS = 120_000;
    private final Handler main = new Handler(Looper.getMainLooper());
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String name,Bundle extras){
        var out=new Bundle();
        String caller=getCallingPackage();
        String verb=extras==null?null:extras.getString("method"),path=extras==null?null:extras.getString("path");
        if(!"request".equals(method)||name==null||!name.matches("[a-z0-9][a-z0-9-]{0,40}")||verb==null||!METHODS.contains(verb)||path==null||!path.matches("/[^\\s\\p{Cntrl}]*")){out.putString("error","bad_request");return out;}
        Context context=getContext();var prefs=context.getSharedPreferences("tethium",0);
        if(caller==null){out.putString("error","denied");return out;}
        if(prefs.getString("token","").isEmpty()){out.putString("error","not_paired");return out;}
        File folder=new File(context.getCacheDir(),"link");File request=null,response=null;
        try{
            if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Нет места для запроса");
            ParcelFileDescriptor body=extras.getParcelable("body");
            if(body!=null){request=File.createTempFile("request",null,folder);try(var in=new ParcelFileDescriptor.AutoCloseInputStream(body);var file=new FileOutputStream(request)){copy(in,file);}}
            response=File.createTempFile("response",null,folder);
            send(context,prefs,caller,name,verb,path,extras.getBundle("headers"),request,response,out);
            var answer=ParcelFileDescriptor.open(response,ParcelFileDescriptor.MODE_READ_ONLY);out.putParcelable("body",answer);
            // The transaction duplicates the descriptor for the caller; ours only has to outlive that.
            main.postDelayed(()->{try{answer.close();}catch(IOException ignored){}},30_000);
        }catch(Connection.ApiException denied){out.clear();out.putString("error",denied.status==401||denied.status==404?"denied":"unreachable");out.putString("message",denied.getMessage());}
        catch(Exception failure){out.clear();out.putString("error","unreachable");out.putString("message",Objects.toString(failure.getMessage(),"Нет связи с ноутбуком"));}
        finally{if(request!=null)request.delete();if(response!=null)response.delete();}
        return out;
    }
    private static void send(Context context,SharedPreferences prefs,String caller,String name,String verb,String path,Bundle headers,File request,File response,Bundle out)throws Exception{
        var signers=signers(context,caller);var connection=AppUpdates.connection(context);
        String key="serviceToken."+name+"."+caller+"."+signers.toString().hashCode();
        for(int attempt=0;;attempt++){
            String token=attempt==0?prefs.getString(key,null):null;
            if(token==null){
                token=connection.post("/service-token",Connection.json("service",name,"package",caller,"signers",signers)).getString("token");
                prefs.edit().putString(key,token).apply();
            }
            HttpURLConnection c=connection.open("/svc/"+name+path);
            try{
                c.setRequestMethod(verb);c.setReadTimeout(READ_MILLIS);
                if(headers!=null)for(String header:headers.keySet())if(!NOT_SENT.contains(header.toLowerCase(Locale.ROOT))&&headers.getString(header)!=null)c.setRequestProperty(header,headers.getString(header));
                c.setRequestProperty("Authorization","Bearer "+token);
                if(request!=null){c.setDoOutput(true);c.setFixedLengthStreamingMode(request.length());try(var in=new FileInputStream(request);var o=c.getOutputStream()){copy(in,o);}}
                int status=c.getResponseCode();
                if(status==401&&attempt==0)continue; // revoked or re-paired: one fresh token, the body is still in the file
                out.putInt("status",status);
                var names=new Bundle();
                for(var entry:c.getHeaderFields().entrySet())if(entry.getKey()!=null&&!NOT_SENT.contains(entry.getKey().toLowerCase(Locale.ROOT)))names.putString(entry.getKey(),String.join(", ",(List<String>)entry.getValue()));
                out.putBundle("headers",names);
                try(var in=status<400?c.getInputStream():c.getErrorStream();var file=new FileOutputStream(response)){if(in!=null)copy(in,file);}
                return;
            }finally{c.disconnect();}
        }
    }
    private static void copy(InputStream in,java.io.OutputStream out)throws IOException{byte[] b=new byte[65536];int n;while((n=in.read(b))>=0)out.write(b,0,n);}
    private static JSONArray signers(Context context,String caller)throws Exception{
        var manager=context.getPackageManager();var result=new JSONArray();
        var info=manager.getPackageInfo(caller,Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES);
        var values=Build.VERSION.SDK_INT>=28&&info.signingInfo!=null?info.signingInfo.getApkContentsSigners():info.signatures;
        if(values!=null)for(var signature:values){var hex=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()))hex.append(String.format(Locale.ROOT,"%02x",b&255));result.put(hex.toString());}
        return result;
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){return null;}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
