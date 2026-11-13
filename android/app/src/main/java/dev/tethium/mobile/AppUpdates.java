package dev.tethium.mobile;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** One verified APK pipeline for Tethium and project applications. No arbitrary download URLs. */
final class AppUpdates {
    static final ExecutorService IO=Executors.newSingleThreadExecutor();
    static volatile boolean foreground;
    static final String SELF="dev.tethium.mobile", CHANNEL="app-updates";
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("app-updates",0);}
    static Connection connection(Context c)throws Exception{return connection(c,null);}
    static Connection connection(Context c,android.net.Network network)throws Exception{return Connection.from(c,network);}
    static void schedule(Context c){
        var scheduler=c.getSystemService(JobScheduler.class);
        if(scheduler.getPendingJob(7021)==null)scheduler.schedule(new JobInfo.Builder(7021,new ComponentName(c,UpdateJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setPeriodic(15*60_000L).setPersisted(true).build());
        c.getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"Обновления приложений",NotificationManager.IMPORTANCE_DEFAULT));
    }
    static JSONArray catalog(Context c,Connection conn)throws Exception{
        var data=conn.request("/apps",null);var apps=data.getJSONArray("apps");
        prefs(c).edit().putString("catalog",apps.toString()).putString("errors",data.optJSONArray("errors")==null?"[]":data.optJSONArray("errors").toString()).putLong("checked",System.currentTimeMillis()).apply();return apps;
    }
    static PackageInfo installed(Context c,String pkg){try{return c.getPackageManager().getPackageInfo(pkg,Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES);}catch(PackageManager.NameNotFoundException e){return null;}}
    static long version(PackageInfo p){return p==null?0:Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode;}
    static AppReleaseState.Action action(Context c,JSONObject app,PackageInfo old){String pkg=app.optString("packageName");if(pkg.equals(SELF)&&old!=null&&version(old)==app.optLong("versionCode"))return AppReleaseState.Action.CURRENT;return AppReleaseState.action(old!=null,version(old),app.optLong("versionCode"),app.optString("sha256"),prefs(c).getString("installedHash."+pkg,""),old!=null&&old.lastUpdateTime==prefs(c).getLong("installedAt."+pkg,-1));}
    private static Set<String> signatures(PackageInfo p){var result=new HashSet<String>();var values=Build.VERSION.SDK_INT>=28&&p.signingInfo!=null?p.signingInfo.getApkContentsSigners():p.signatures;if(values!=null)for(var s:values)result.add(s.toCharsString());return result;}
    static boolean automatic(Context c,String pkg){return prefs(c).getBoolean("auto."+pkg,pkg.equals(SELF));}
    static File target(Context c,JSONObject app)throws Exception{
        String hash=app.getString("sha256");if(!hash.matches("[a-f0-9]{64}"))throw new IOException("Некорректная сумма APK");
        File dir=new File(c.getCacheDir(),"updates");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Нет места для APK");return new File(dir,hash+".apk");
    }
    private static String hash(File f)throws Exception{var digest=MessageDigest.getInstance("SHA-256");try(var in=new FileInputStream(f)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}var out=new StringBuilder();for(byte b:digest.digest())out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    static File download(Context c,Connection conn,JSONObject app)throws Exception{return download(c,conn,app,false);}
    static File download(Context c,Connection conn,JSONObject app,boolean approvedRemote)throws Exception{
        File target=target(c,app);String expected=app.getString("sha256");
        if(!target.isFile()||!hash(target).equals(expected)){
            String path=app.getString("path");if(!path.equals("/apk")&&!path.matches("/apps/[a-f0-9]{64}\\.apk"))throw new IOException("Некорректный адрес APK");
            File part=new File(target+".part");var request=conn.open(path);if(approvedRemote)request.setRequestProperty("X-Tethium-Approve-Large","true");
            try{if(request.getResponseCode()!=200)throw new IOException("Не удалось скачать APK");try(var in=request.getInputStream();var out=new FileOutputStream(part)){byte[] b=new byte[65536];int n;long total=0;while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();total+=n;if(total>1_000_000_000L)throw new IOException("APK больше 1 ГБ");out.write(b,0,n);}}
                if(!hash(part).equals(expected))throw new IOException("APK изменился при загрузке; обновите каталог");verify(c,part,app);Files.move(part.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);
            }finally{request.disconnect();part.delete();}
        }
        verify(c,target,app);
        String key="ready."+app.getString("packageName"),previous=prefs(c).getString(key,"");
        prefs(c).edit().putString(key,expected).apply();
        if(!previous.equals(expected)&&previous.matches("[a-f0-9]{64}"))new File(target.getParentFile(),previous+".apk").delete();
        return target;
    }
    private static void verify(Context c,File file,JSONObject app)throws Exception{
        var archive=c.getPackageManager().getPackageArchiveInfo(file.toString(),Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES);
        String pkg=app.getString("packageName");var old=installed(c,pkg);
        if(archive==null||!pkg.equals(archive.packageName)||version(archive)!=app.getLong("versionCode")||signatures(archive).isEmpty())throw new IOException("Пакет или версия APK не совпадают с каталогом");
        if(old!=null&&(version(archive)<version(old)||!signatures(old).equals(signatures(archive))))throw new IOException("Обновление имеет другую подпись или более старую версию");
    }
    static boolean pending(Context c,String pkg){
        int id=prefs(c).getInt("install."+pkg,-1);if(id<0)return false;
        if(c.getPackageManager().getPackageInstaller().getSessionInfo(id)!=null)return true;
        prefs(c).edit().remove("install."+pkg).remove("confirm."+pkg).apply();return false;
    }
    static void install(Context c,JSONObject app,File file,boolean auto)throws Exception{
        String pkg=app.getString("packageName");verify(c,file,app);
        long current=version(installed(c,pkg)),offered=app.getLong("versionCode");
        if(current>offered||auto&&current==offered||pending(c,pkg))return;
        var installer=c.getPackageManager().getPackageInstaller();var params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(pkg);params.setSize(file.length());
        // Always ask for an unattended install; Android itself falls back to its confirmation when the rules are not met (first install, foreign installer, old targetSdk).
        if(Build.VERSION.SDK_INT>=31)params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id=installer.createSession(params);
        try(var session=installer.openSession(id)){
            try(var in=new FileInputStream(file);var out=session.openWrite("base.apk",0,file.length())){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);session.fsync(out);}
            var reply=new Intent(c,InstallReceiver.class).setAction("dev.tethium.INSTALL."+id).putExtra("package",pkg).putExtra("hash",app.getString("sha256"));
            var callback=PendingIntent.getBroadcast(c,id,reply,PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0));
            prefs(c).edit().putInt("install."+pkg,id).remove("confirm."+pkg).putString("status."+pkg,"Установка…").apply();session.commit(callback.getIntentSender());
        }catch(Exception e){installer.abandonSession(id);prefs(c).edit().remove("install."+pkg).apply();throw e;}
    }
    static void check(Context c,Connection conn,boolean background)throws Exception{
        var apps=catalog(c,conn);
        for(int i=0;i<apps.length();i++){
            var app=apps.getJSONObject(i);String pkg=app.getString("packageName");var old=installed(c,pkg);
            if(Thread.currentThread().isInterrupted())return;
            if(old==null||version(old)>=app.getLong("versionCode")||!automatic(c,pkg)||pending(c,pkg))continue;
            if(app.getString("sha256").equals(prefs(c).getString("failed."+pkg,"")))continue;
            try{
                if(conn.remote()){prefs(c).edit().putString("status."+pkg,"Доступно · скачивание по нажатию вне домашней сети").apply();if(!app.getString("sha256").equals(prefs(c).getString("notified."+pkg,""))){notify(c,pkg,"Доступно обновление «"+app.optString("title",pkg)+"»");prefs(c).edit().putString("notified."+pkg,app.getString("sha256")).apply();}continue;}
                File file=download(c,conn,app);prefs(c).edit().putString("status."+pkg,"Обновление скачано").apply();
                // Never restart the chat while the user is typing. Other installs may run in foreground.
                if(c.getPackageManager().canRequestPackageInstalls()&&(!pkg.equals(SELF)||background&&!foreground))install(c,app,file,true);
                else if(!app.getString("sha256").equals(prefs(c).getString("notified."+pkg,""))){notify(c,pkg,"Обновление «"+app.optString("title",pkg)+"» готово");prefs(c).edit().putString("notified."+pkg,app.getString("sha256")).apply();}
            }catch(InterruptedIOException e){throw e;}catch(Exception e){prefs(c).edit().putString("status."+pkg,Objects.toString(e.getMessage(),"Ошибка обновления")).apply();}
        }
    }
    static void notify(Context c,String pkg,String text){
        var open=PendingIntent.getActivity(c,0,new Intent(c,MainActivity.class).putExtra("apps",true),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        try{c.getSystemService(NotificationManager.class).notify(pkg.hashCode(),new Notification.Builder(c,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Tethium · приложения").setContentText(text).setContentIntent(open).setAutoCancel(true).build());}catch(SecurityException ignored){}
    }
    static void show(Activity a,Ui ui,Connection conn){
        if(conn==null)return;
        var content=ui.col();ui.pad(content,16,8);var controls=ui.row();var info=ui.label("Проверяем приложения…",12,Ui.MUTED);var list=ui.col();var scroll=new ScrollView(a);scroll.addView(list);
        final Runnable[] refresh={null}; final boolean[] loading={false};
        var reload=ui.button("Обновить список",()->refresh[0].run());controls.addView(reload,new LinearLayout.LayoutParams(0,ui.dp(48),1));
        controls.addView(ui.button("Разрешить установку",()->a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+a.getPackageName())))));
        content.addView(controls);content.addView(info);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));var dialog=ui.sheet("Магазин приложений",content);
        refresh[0]=()->{if(loading[0]||!dialog.isShowing())return;loading[0]=true;reload.setEnabled(false);reload.setText("Проверяем…");info.setText("Проверяем приложения…");IO.execute(()->{try{var apps=catalog(a,conn);a.runOnUiThread(()->{
            loading[0]=false;reload.setEnabled(true);reload.setText("Обновить список");if(!dialog.isShowing())return;list.removeAllViews();info.setText("Автоматически: при работе и в фоне по Wi-Fi. Ноутбук должен быть доступен.");
            for(int i=0;i<apps.length();i++){
                var app=apps.optJSONObject(i);String pkg=app.optString("packageName");var old=installed(a,pkg);var action=action(a,app,old);boolean newer=action==AppReleaseState.Action.INSTALL||action==AppReleaseState.Action.UPDATE||action==AppReleaseState.Action.REINSTALL;pending(a,pkg);
                ui.gap(list,16);list.addView(ui.title(app.optString("title",pkg)));list.addView(ui.label(app.optString("project")+" · "+app.optString("versionName")+" ("+app.optLong("versionCode")+")"+(app.optLong("size")>0?" · "+String.format(Locale.ROOT,"%.1f МБ",app.optLong("size")/1048576.0):""),12,Ui.MUTED));
                if(old!=null)list.addView(ui.label("На устройстве · "+old.versionName,12,Ui.MUTED));
                String state=prefs(a).getString("status."+pkg,"");if(action==AppReleaseState.Action.CURRENT)state="Актуальная сборка";else if(action==AppReleaseState.Action.NEWER_INSTALLED)state="На устройстве более новая версия";else if(action==AppReleaseState.Action.REINSTALL&&state.isEmpty())state="Сборка с тем же номером версии · можно переустановить";if(!state.isEmpty())list.addView(ui.label(state,12,Ui.MUTED));
                var row=ui.row();var auto=new Switch(a);auto.setText("Автообновление");auto.setTextColor(Ui.TEXT);auto.setChecked(automatic(a,pkg));auto.setOnCheckedChangeListener((v,on)->prefs(a).edit().putBoolean("auto."+pkg,on).apply());row.addView(auto,new LinearLayout.LayoutParams(0,ui.dp(48),1));
                String confirmation=prefs(a).getString("confirm."+pkg,"");
                var install=ui.button(!confirmation.isEmpty()?"Подтвердить":action==AppReleaseState.Action.INSTALL?"Установить":action==AppReleaseState.Action.REINSTALL?"Переустановить":newer?"Обновить":"Открыть",()->{
                    try{
                        if(!confirmation.isEmpty()){a.startActivity(Intent.parseUri(confirmation,0));return;}
                        if(!newer){open(a,pkg);return;}
                        if(!a.getPackageManager().canRequestPackageInstalls()){a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+a.getPackageName())));return;}
                        info.setText("Скачиваем «"+app.optString("title",pkg)+"»…");
                        ui.async("install:"+pkg,"Скачиваем…",IO,()->{File file=download(a,conn,app,true);install(a,app,file,false);return true;},done->{info.setText("Ожидаем установку · подтверждение появится здесь");refresh[0].run();},()->refresh[0].run());
                    }catch(Exception e){info.setText(Objects.toString(e.getMessage(),"Ошибка установки"));}
                });if(ui.pending("install:"+pkg)||confirmation.isEmpty()&&pending(a,pkg)){install.setEnabled(false);install.setText(ui.pending("install:"+pkg)?"Скачиваем…":"Устанавливаем…");}row.addView(install);list.addView(row);if(old!=null&&newer)list.addView(ui.button("Открыть установленную",()->open(a,pkg)));ui.line(list);
            }
            String errors=prefs(a).getString("errors","[]");if(!errors.equals("[]"))list.addView(ui.label("Некоторые сборки недоступны: "+errors,12,Ui.AMBER));
        });}catch(Exception e){a.runOnUiThread(()->{loading[0]=false;reload.setEnabled(true);reload.setText("Обновить список");if(dialog.isShowing())info.setText("Нет связи с каталогом: "+e.getMessage());});}});};
        refresh[0].run();
        // PackageInstaller's callback can arrive after the sheet is drawn; expose confirmation immediately.
        var handler=new android.os.Handler(a.getMainLooper());final String[] previous={"initial"};
        Runnable status=new Runnable(){public void run(){if(!dialog.isShowing())return;String current=new java.util.TreeMap<>(prefs(a).getAll()).entrySet().stream().filter(e->e.getKey().startsWith("status.")||e.getKey().startsWith("confirm.")||e.getKey().startsWith("install.")).map(Object::toString).collect(java.util.stream.Collectors.joining());if(!current.equals(previous[0]))refresh[0].run();previous[0]=current;handler.postDelayed(this,2000);}};handler.postDelayed(status,2000);
        if(Build.VERSION.SDK_INT>=33&&a.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)a.requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},84);
    }
    private static void open(Activity activity,String pkg){var launch=activity.getPackageManager().getLaunchIntentForPackage(pkg);if(launch==null)launch=activity.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);if(launch!=null)activity.startActivity(launch);else Toast.makeText(activity,"У приложения нет экрана запуска",Toast.LENGTH_SHORT).show();}
}
