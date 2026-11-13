package your.app;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.TreeMap;

/**
 * Talks to this project's laptop service through the Tethium app on the phone: pairing, address and tokens all stay in Tethium.
 * The manifest needs: queries, provider android:authorities="dev.tethium.mobile.link". Call from a background thread.
 */
final class TethiumLink {
    private static final Uri PROVIDER = Uri.parse("content://dev.tethium.mobile.link");
    private TethiumLink() {}

    /** Tethium could not carry the request. reason: no_tethium, not_paired, denied, unreachable or bad_request. */
    static final class Unavailable extends IOException {
        final String reason;
        Unavailable(String reason, String message) { super(message == null ? reason : message); this.reason = reason; }
    }
    /** The service's own answer, whatever its status. Header names are case-insensitive. */
    static final class Response implements Closeable {
        final int status; final Map<String, String> headers; final InputStream body;
        Response(int status, Map<String, String> headers, InputStream body) { this.status = status; this.headers = headers; this.body = body; }
        byte[] bytes() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] chunk = new byte[65536]; int n;
            while ((n = body.read(chunk)) >= 0) out.write(chunk, 0, n);
            return out.toByteArray();
        }
        @Override public void close() throws IOException { body.close(); }
    }

    static Response request(Context context, String service, String method, String path, Map<String, String> headers, byte[] body) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("TethiumLink blocks until the service answers");
        Bundle extras = new Bundle();
        extras.putString("method", method); extras.putString("path", path);
        Bundle names = new Bundle();
        if (headers != null) for (Map.Entry<String, String> header : headers.entrySet()) names.putString(header.getKey(), header.getValue());
        extras.putBundle("headers", names);
        if (body != null) extras.putParcelable("body", feed(body));
        Bundle out;
        try { out = context.getContentResolver().call(PROVIDER, "request", service, extras); }
        catch (IllegalArgumentException | SecurityException missing) { throw new Unavailable("no_tethium", missing.getMessage()); }
        if (out == null) throw new Unavailable("no_tethium", null);
        if (out.getString("error") != null) throw new Unavailable(out.getString("error"), out.getString("message"));
        Map<String, String> answer = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Bundle received = out.getBundle("headers");
        if (received != null) for (String name : received.keySet()) answer.put(name, received.getString(name));
        ParcelFileDescriptor file = out.getParcelable("body");
        return new Response(out.getInt("status"), answer, new ParcelFileDescriptor.AutoCloseInputStream(file));
    }
    /** Call only after the app has shown the byte count to the user and received an explicit tap. */
    static Map<String,String> approveLargeTransfer(Map<String,String> headers) {
        Map<String,String> approved = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) approved.putAll(headers);
        approved.put("X-Tethium-Approve-Large", "true");
        return approved;
    }
    private static ParcelFileDescriptor feed(byte[] body) throws IOException {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        new Thread(() -> {
            try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) { out.write(body); }
            catch (IOException ignored) { }
        }, "tethium-link-body").start();
        return pipe[0];
    }
}
