package dev.tethium.mobile;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Read-only grant for the one verified update file; no caller-controlled filesystem path. */
public final class UpdateProvider extends ContentProvider {
    public boolean onCreate(){return true;}
    private File file(Uri u)throws FileNotFoundException{if(!"/tethium.apk".equals(u.getPath()))throw new FileNotFoundException();return new File(getContext().getCacheDir(),"tethium.apk");}
    public ParcelFileDescriptor openFile(Uri u,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException();return ParcelFileDescriptor.open(file(u),ParcelFileDescriptor.MODE_READ_ONLY);}
    public String getType(Uri u){return "application/vnd.android.package-archive";}
    public Cursor query(Uri u,String[] p,String s,String[] a,String o){try{var c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});c.addRow(new Object[]{"Tethium.apk",file(u).length()});return c;}catch(Exception e){return null;}}
    public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
