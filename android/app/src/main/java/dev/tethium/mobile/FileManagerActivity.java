package dev.tethium.mobile;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Paired laptop folders; Android's document picker supplies local files and destinations. */
public final class FileManagerActivity extends Activity {
    private static final int PICK=701,SAVE=702,CHUNK=1024*1024;
    private Ui ui;private Connection connection;private Spinner roots;private TextView location,status;private LinearLayout rows,tools;private ProgressBar progress;private Button cancel;
    private final ExecutorService io=Executors.newSingleThreadExecutor();private AtomicBoolean cancelled=new AtomicBoolean();private boolean busy,applying;private String root="",path="";
    private JSONArray rootValues=new JSONArray(),entries=new JSONArray();private JSONObject pendingDownload;private long lastProgress;
    private final ArrayDeque<Runnable> pendingActions=new ArrayDeque<>();
    private final ExecutorService browsing=Executors.newSingleThreadExecutor();private volatile long navigationVersion;private boolean navigating;private long backExitUntil;private Runnable unregisterBack=()->{};
    private record Prepared(File file,String name,String sha,long size){}
    private record Upload(Prepared source,JSONObject existing){}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);ui=new Ui(this);getWindow().setStatusBarColor(Ui.BG);getWindow().setNavigationBarColor(Ui.BG);
        setTaskDescription(new ActivityManager.TaskDescription("Tethium · Файлы",null,Ui.BG));
        if(android.os.Build.VERSION.SDK_INT>=33){android.window.OnBackInvokedCallback callback=this::handleBack;getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback);unregisterBack=()->getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(callback);}
        if(saved!=null){root=saved.getString("root","");path=saved.getString("path","");try{String info=saved.getString("download");if(info!=null)pendingDownload=new JSONObject(info);}catch(JSONException ignored){}}
        try{var prefs=getSharedPreferences("tethium",MODE_PRIVATE);connection=new Connection(prefs.getString("address",""),prefs.getString("token",""),prefs.getInt("httpPort",8766));}catch(Exception e){finish();return;}
        var page=ui.col();page.setBackgroundColor(Ui.BG);var title=ui.row();title.addView(ui.icon("folder","Файлы ноутбука",()->{}),new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));title.addView(ui.title("Файлы · ноутбук"),new LinearLayout.LayoutParams(0,-2,1));title.addView(ui.icon("close","Закрыть файлы",this::requestClose),new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));page.addView(title);
        roots=new Spinner(this);page.addView(ui.field(roots));roots.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int position,long id){if(applying||rootValues.optJSONObject(position)==null)return;String next=rootValues.optJSONObject(position).optString("id");if(next.equals(root))return;root=next;path="";backExitUntil=0;load();}public void onNothingSelected(AdapterView<?> p){}});
        location=ui.label("/",13,Ui.MUTED);ui.pad(location,20,6);page.addView(location);
        tools=ui.row();tools.addView(ui.button("↑",this::up));tools.addView(ui.icon("refresh","Обновить",this::load),new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));tools.addView(ui.primary("Отправить…",this::pick));tools.addView(ui.icon("plus","Новая папка",this::mkdir),new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));var toolScroll=new HorizontalScrollView(this);toolScroll.setHorizontalScrollBarEnabled(false);toolScroll.addView(tools);page.addView(toolScroll);
        var scroll=new ScrollView(this);rows=ui.col();ui.pad(rows,12,8);scroll.addView(rows);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        var footer=ui.col();ui.pad(footer,16,8);status=ui.label("",12,Ui.MUTED);footer.addView(status);progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);footer.addView(progress,new LinearLayout.LayoutParams(-1,ui.dp(8)));cancel=ui.button("Остановить передачу",()->{cancelled.set(true);cancel.setEnabled(false);status.setText("Останавливаем…");});cancel.setVisibility(View.GONE);footer.addView(cancel);page.addView(footer);setContentView(page);ui.inset(page);loadRoots();
    }
    private void loadRoots(){work("Читаем папки…",()->connection.post("/file-manager/roots",null),result->{
        rootValues=result.optJSONArray("roots");if(rootValues==null)rootValues=new JSONArray();var names=new ArrayList<String>();int selected=0;for(int i=0;i<rootValues.length();i++){var r=rootValues.optJSONObject(i);names.add(r.optString("title"));if(r.optString("id").equals(root))selected=i;}
        applying=true;roots.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));roots.setSelection(selected);applying=false;
        if(rootValues.length()>0){String id=rootValues.optJSONObject(selected).optString("id");if(!id.equals(root))path="";root=id;load();}
    });}
    private static String listing(String root,String path,int offset){return "/file-manager/list?root="+encode(root)+"&path="+encode(path)+"&offset="+offset;}
    private void load(){if(root.isEmpty()||isDestroyed()||isFinishing())return;long version=++navigationVersion;String selectedRoot=root,selectedPath=path;navigating=true;entries=new JSONArray();render();if(!busy){status.setText("Читаем файлы…");progress.setIndeterminate(true);}enabled(!busy);
        browsing.execute(()->{if(version!=navigationVersion)return;var all=new JSONArray();Exception error=null;try{int offset=0;do{var page=connection.post(listing(selectedRoot,selectedPath,offset),null);var items=page.optJSONArray("entries");if(items!=null)for(int i=0;i<items.length();i++)all.put(items.get(i));offset=page.optInt("next",-1);}while(offset>=0&&version==navigationVersion&&!Thread.currentThread().isInterrupted());}catch(Exception e){error=e;}Exception failure=error;runOnUiThread(()->{if(isDestroyed()||isFinishing()||version!=navigationVersion)return;navigating=false;entries=all;render();enabled(!busy);if(!busy){progress.setIndeterminate(false);if(failure!=null)status.setText(Objects.toString(failure.getMessage(),"Не удалось прочитать папку"));}});});
    }
    private Map<String,String> rootPaths(){var result=new LinkedHashMap<String,String>();for(int i=0;i<rootValues.length();i++){var r=rootValues.optJSONObject(i);if(r!=null)result.put(r.optString("id"),r.optString("path"));}return result;}
    private void up(){var parent=FileNavigation.parent(root,path,rootPaths());if(parent==null)return;root=parent.root();path=parent.path();backExitUntil=0;applying=true;for(int i=0;i<rootValues.length();i++)if(rootValues.optJSONObject(i).optString("id").equals(root))roots.setSelection(i);applying=false;load();}
    private void render(){rows.removeAllViews();String base=FileNavigation.normalize(rootPaths().getOrDefault(root,""));location.setText(path.isEmpty()?base:(base.endsWith("/")?base:base+"/")+path);for(int i=0;i<entries.length();i++){
        var entry=entries.optJSONObject(i);var row=ui.row();row.setBackground(ui.ripple(0,12));boolean dir=entry.optBoolean("directory"),accessible=entry.optBoolean("accessible");
        var icon=new ImageView(this);icon.setImageDrawable(new Ui.Glyph(dir?"folder":"image",accessible?Ui.ACCENT:Ui.MUTED));var ip=new LinearLayout.LayoutParams(ui.dp(26),ui.dp(26));ip.setMargins(ui.dp(10),0,ui.dp(12),0);row.addView(icon,ip);
        var text=ui.col();ui.pad(text,0,12);var name=ui.label(entry.optString("name"),15,Ui.TEXT);name.setMaxLines(2);text.addView(name);ui.gap(text,4);String date=new java.text.SimpleDateFormat("dd.MM.yy HH:mm",Locale.ROOT).format(new Date(entry.optLong("modified")));text.addView(ui.label((dir?"Папка":size(entry.optLong("size")))+" · "+date+(accessible?"":" · ссылка недоступна"),12,Ui.MUTED));row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        row.addView(ui.icon("more","Действия с файлом",()->actions(entry)),new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));ui.click(row,()->{if(accessible){if(dir){path=join(path,entry.optString("name"));backExitUntil=0;load();}else if(!busy)actions(entry);}});rows.addView(row);ui.line(rows);
    }if(!busy)status.setText(entries.length()+" элементов · папки рабочего ноутбука");}
    private void actions(JSONObject entry){if(busy||!entry.optBoolean("accessible"))return;var menu=new ActionMenu(ui,entry.optString("name"),entry.optBoolean("directory")?"Папка на ноутбуке":size(entry.optLong("size")));
        menu.action("down",entry.optBoolean("directory")?"Сохранить папку ZIP…":"Сохранить на телефон…",null,()->prepareDownload(entry));
        menu.action("edit","Переименовать",null,()->rename(entry)).action("close","Удалить…","Непустые папки не удаляются",()->new AlertDialog.Builder(this).setTitle("Удалить с ноутбука?").setMessage(entry.optString("name")).setNegativeButton("Отмена",null).setPositiveButton("Удалить",(d,w)->mutate("delete",entryBody(entry).putOptSafe("confirmed",true))).show()).show();
    }
    // JSONObject's checked exceptions stay out of click handlers.
    private static final class Body extends JSONObject {Body putOptSafe(String key,Object value){try{put(key,value);}catch(JSONException e){throw new IllegalStateException(e);}return this;}}
    private Body entryBody(JSONObject entry){return new Body().putOptSafe("root",root).putOptSafe("path",join(path,entry.optString("name"))).putOptSafe("revision",entry.optString("revision"));}
    private void mutate(String action,JSONObject body){work("Сохраняем…",()->connection.post("/file-manager/"+action,body),r->load());}
    private void mkdir(){var input=ui.edit("Название папки");new AlertDialog.Builder(this).setTitle("Новая папка").setView(ui.field(input)).setNegativeButton("Отмена",null).setPositiveButton("Создать",(d,w)->mutate("mkdir",Connection.json("root",root,"path",path,"name",input.getText().toString()))).show();}
    private void rename(JSONObject entry){var input=ui.edit("Новое имя");input.setText(entry.optString("name"));new AlertDialog.Builder(this).setTitle("Переименовать").setView(ui.field(input)).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",(d,w)->mutate("rename",entryBody(entry).putOptSafe("name",input.getText().toString()))).show();}
    private void pick(){if(busy||navigating||root.isEmpty())return;var intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);startActivityForResult(intent,PICK);}
    private void prepareDownload(JSONObject entry){var body=entryBody(entry);work("Проверяем файл…",()->connection.post("/file-manager/download",body),info->{pendingDownload=info;startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,info.optString("name")),SAVE);});}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null)return;
        if(request==PICK){var selected=new ArrayDeque<Uri>();if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++)selected.add(data.getClipData().getItemAt(i).getUri());}else if(data.getData()!=null)selected.add(data.getData());String destinationRoot=root,destinationPath=path;schedule(()->uploadNext(selected,destinationRoot,destinationPath));}
        if(request==SAVE&&pendingDownload!=null&&data.getData()!=null){var info=pendingDownload;pendingDownload=null;Uri destination=data.getData();schedule(()->work("Скачиваем…",()->{download(info,destination);return "Сохранено: "+info.optString("name");},message->status.setText(message)));}
    }
    private void schedule(Runnable action){if(busy)pendingActions.addLast(action);else action.run();}
    private void uploadNext(ArrayDeque<Uri> uris,String targetRoot,String parent){if(uris.isEmpty())return;Uri uri=uris.removeFirst();work("Готовим файл телефона…",()->{var source=prepare(uri);JSONObject existing=null;int offset=0;do{var page=connection.post(listing(targetRoot,parent,offset),null);var items=page.optJSONArray("entries");if(items!=null)for(int i=0;i<items.length();i++){var entry=items.optJSONObject(i);if(source.name.equals(entry.optString("name")))existing=entry;}offset=page.optInt("next",-1);}while(existing==null&&offset>=0);return new Upload(source,existing);},result->{var prepared=result.source;var existing=result.existing;
        if(existing!=null&&existing.optBoolean("directory")){status.setText("Папка с таким именем уже существует");prepared.file.delete();uploadNext(uris,targetRoot,parent);return;}
        String revision=existing==null?"":existing.optString("revision");Runnable send=()->work("Отправляем "+prepared.name+"…",()->{upload(prepared,targetRoot,join(parent,prepared.name),revision);prepared.file.delete();return "Отправлено: "+prepared.name;},message->{status.setText(message);if(uris.isEmpty())load();else uploadNext(uris,targetRoot,parent);});
        if(existing==null)send.run();else new AlertDialog.Builder(this).setTitle("Заменить файл на ноутбуке?").setMessage(prepared.name).setNegativeButton("Пропустить",(d,w)->{prepared.file.delete();uploadNext(uris,targetRoot,parent);}).setPositiveButton("Заменить",(d,w)->send.run()).setOnCancelListener(d->{prepared.file.delete();uploadNext(uris,targetRoot,parent);}).show();
    });}
    private Prepared prepare(Uri uri)throws Exception{
        String name="файл";try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())name=cursor.getString(0);}if(name==null||name.isBlank()||name.contains("/")||name.contains("\\"))throw new IOException("Некорректное имя файла");
        File cache=new File(getCacheDir(),"file-transfers");cache.mkdirs();File temporary=File.createTempFile("upload-",".part",cache);var hash=MessageDigest.getInstance("SHA-256");long count=0;
        try(var input=getContentResolver().openInputStream(uri);var output=new FileOutputStream(temporary)){if(input==null)throw new IOException("Не удалось открыть файл");byte[] b=new byte[65536];int n;while((n=input.read(b))!=-1){check();output.write(b,0,n);hash.update(b,0,n);count+=n;update("Готовим "+name,count,0);}}
        catch(Exception e){temporary.delete();throw e;}
        String sha=hex(hash.digest());File file=new File(cache,"upload-"+sha);Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);return new Prepared(file,name,sha,count);
    }
    private void upload(Prepared source,String root,String path,String revision)throws Exception{
        var body=Connection.json("root",root,"path",path,"size",source.size,"sha256",source.sha,"replaceRevision",revision);String id=UUID.nameUUIDFromBytes(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();body.put("id",id);
        var begin=connection.post("/file-manager/upload",body);long offset=begin.optLong("offset");update(source.name,offset,source.size);
        if(!begin.optBoolean("complete"))try(var input=new RandomAccessFile(source.file,"r")){byte[] b=new byte[CHUNK];while(offset<source.size){check();input.seek(offset);int n=input.read(b,0,(int)Math.min(CHUNK,source.size-offset));if(n<=0)throw new IOException("Исходный файл изменился");long position=offset;int count=n;
            offset=retry(()->{var c=connection.open("/file-manager/upload/"+id+"/chunk?offset="+position);try{c.setRequestMethod("POST");c.setDoOutput(true);c.setFixedLengthStreamingMode(count);try(var out=c.getOutputStream()){out.write(b,0,count);}return response(c).optLong("offset");}finally{c.disconnect();}});if(offset<=position||offset>source.size)throw new IOException("Некорректное смещение");update(source.name,offset,source.size);
        }}check();connection.post("/file-manager/upload/"+id+"/finish",new JSONObject());
    }
    private void download(JSONObject info,Uri destination)throws Exception{
        String hash=info.optString("sha256");if(!hash.matches("[a-f0-9]{64}"))throw new IOException("Некорректный файл");long size=info.optLong("size");File directory=new File(getCacheDir(),"file-transfers");directory.mkdirs();File partial=new File(directory,"download-"+hash);long offset=partial.exists()?partial.length():0;if(offset>size)throw new IOException("Некорректная незавершённая загрузка");
        try(var output=new RandomAccessFile(partial,"rw")){while(offset<size){check();long position=offset;int count=(int)Math.min(CHUNK,size-offset);byte[] b=retry(()->{var c=connection.open("/file-manager/download/"+info.optString("id")+"?offset="+position+"&length="+count);try{if(c.getResponseCode()!=200){response(c);throw new IOException("Ошибка загрузки");}try(var in=c.getInputStream()){byte[] bytes=readChunk(in,count);if(bytes.length!=count)throw new IOException("Неполная порция файла");return bytes;}}finally{c.disconnect();}});output.seek(offset);output.write(b);offset+=b.length;update(info.optString("name"),offset,size);}output.getFD().sync();}
        check();var digest=MessageDigest.getInstance("SHA-256");try(var in=new FileInputStream(partial)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){check();digest.update(b,0,n);}}if(!hex(digest.digest()).equals(hash)){partial.delete();throw new IOException("Контрольная сумма не совпала. Повторите скачивание.");}
        try(var in=new FileInputStream(partial);var out=getContentResolver().openOutputStream(destination,"wt")){if(out==null)throw new IOException("Не удалось сохранить файл");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){check();out.write(b,0,n);}}partial.delete();
        try{connection.post("/file-manager/download/"+info.optString("id")+"/release",new JSONObject());}catch(Exception ignored){}
    }
    private static byte[] readChunk(InputStream in,int length)throws IOException{var out=new ByteArrayOutputStream(length);byte[] b=new byte[65536];int left=length+1;while(left>0){int n=in.read(b,0,Math.min(left,b.length));if(n<0)break;out.write(b,0,n);left-=n;}return out.toByteArray();}
    private static JSONObject response(java.net.HttpURLConnection c)throws Exception{int code=c.getResponseCode();var stream=code==200?c.getInputStream():c.getErrorStream();String text=stream==null?"{}":new String(readChunk(stream,65536),java.nio.charset.StandardCharsets.UTF_8);var json=new JSONObject(text);if(code!=200)throw new Connection.ApiException(code,json.optString("error","Ошибка передачи: "+code));return json;}
    private interface Attempt<T>{T run()throws Exception;}
    private <T>T retry(Attempt<T> attempt)throws Exception{for(int n=0;;n++){check();try{return attempt.run();}catch(Connection.ApiException e){throw e;}catch(IOException e){if(n>=2)throw e;Thread.sleep(250L*(n+1));}}}
    private void check()throws InterruptedIOException{if(cancelled.get()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Передача остановлена. Повторная отправка продолжит её.");}
    private <T>void work(String label,Callable<T> operation,java.util.function.Consumer<T> success){if(busy)return;busy=true;cancelled=new AtomicBoolean();status.setText(label);progress.setIndeterminate(true);enabled(false);cancel.setEnabled(true);cancel.setVisibility(View.VISIBLE);
        io.execute(()->{T value=null;Exception error=null;try{value=operation.call();}catch(Exception e){error=e;}T result=value;Exception failure=error;runOnUiThread(()->{if(isDestroyed()||isFinishing())return;busy=false;enabled(true);cancel.setVisibility(View.GONE);progress.setIndeterminate(false);if(failure==null){progress.setProgress(1000);success.accept(result);}else{status.setText(Objects.toString(failure.getMessage(),"Ошибка передачи"));if(!cancelled.get())new AlertDialog.Builder(this).setTitle("Файлы").setMessage(status.getText()).setPositiveButton("OK",null).show();}if(!busy&&!pendingActions.isEmpty())pendingActions.removeFirst().run();});});
    }
    private void enabled(boolean enabled){roots.setEnabled(true);for(int i=0;i<tools.getChildCount();i++)tools.getChildAt(i).setEnabled(i<2||enabled&&!navigating);rows.setAlpha(1);}
    private void update(String name,long value,long total){long now=System.nanoTime();if(value!=total&&now-lastProgress<100_000_000)return;lastProgress=now;runOnUiThread(()->{if(isDestroyed())return;status.setText(name+" · "+size(value)+(total>0?" / "+size(total):""));progress.setIndeterminate(total==0);if(total>0)progress.setProgress((int)(1000.0*value/total));});}
    private void handleBack(){if(FileNavigation.parent(root,path,rootPaths())!=null){up();return;}long now=android.os.SystemClock.elapsedRealtime();if(now<=backExitUntil){requestClose();return;}backExitUntil=now+2000;Toast.makeText(this,"Нажмите «Назад» ещё раз, чтобы закрыть файлы",Toast.LENGTH_SHORT).show();}
    @Override public void onBackPressed(){handleBack();}
    private void closeFiles(){if(isTaskRoot())finishAndRemoveTask();else finish();}
    private void requestClose(){if(busy){new AlertDialog.Builder(this).setTitle("Остановить передачу и закрыть файлы?").setMessage("Её можно продолжить повторной отправкой файла.").setNegativeButton("Продолжать",null).setPositiveButton("Закрыть",(d,w)->{cancelled.set(true);closeFiles();}).show();}else closeFiles();}
    @Override protected void onResume(){super.onResume();backExitUntil=0;}
    @Override protected void onSaveInstanceState(Bundle state){state.putString("root",root);state.putString("path",path);if(pendingDownload!=null)state.putString("download",pendingDownload.toString());super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){unregisterBack.run();navigationVersion++;cancelled.set(true);io.shutdownNow();browsing.shutdownNow();if(connection!=null)connection.close();super.onDestroy();}
    private static String join(String parent,String name){return parent.isEmpty()?name:parent+"/"+name;}
    private static String encode(String s){try{return java.net.URLEncoder.encode(s,"UTF-8");}catch(Exception e){throw new IllegalStateException(e);}}
    private static String hex(byte[] bytes){var result=new StringBuilder();for(byte b:bytes)result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();}
    private static String size(long n){return n<1024?n+" Б":n<1048576?String.format(Locale.ROOT,"%.1f КБ",n/1024.0):n<1073741824?String.format(Locale.ROOT,"%.1f МБ",n/1048576.0):String.format(Locale.ROOT,"%.2f ГБ",n/1073741824.0);}
}
