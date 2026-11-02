package org.hotwordtest;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Offline FSMN-VAD. Returns [startMs,endMs,...] on a 16 kHz mono chunk. */
public final class FsmnVad {
    static { System.loadLibrary("fsmn_vad"); }
    private static volatile String modelPath;
    private FsmnVad() {}
    public static native int[] detectNative(String modelPath, float[] pcm, int threads);
    public static void configure(Context context) throws IOException {
        modelPath = prepareModel(context.getApplicationContext()).getAbsolutePath();
    }
    public static int[] detect(float[] pcm) {
        String path = modelPath;
        if (path == null) throw new IllegalStateException("FSMN VAD尚未初始化：请先调用configure");
        if (pcm.length > 60 * 16000) throw new IllegalArgumentException("FSMN VAD单次输入不能超过60秒");
        return detectNative(path, pcm, 4);
    }
    public static File prepareModel(Context context) throws IOException {
        File dst = new File(context.getFilesDir(), "fsmn-vad.gguf");
        if (dst.isFile() && dst.length() == 1720512L) return dst;
        File tmp = new File(context.getFilesDir(), "fsmn-vad.gguf.tmp");
        try (InputStream in = context.getAssets().open("fsmn/fsmn-vad.gguf");
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        if (!tmp.renameTo(dst)) throw new IOException("Cannot prepare FSMN VAD model");
        return dst;
    }
}
