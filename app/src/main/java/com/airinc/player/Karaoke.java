package com.airinc.player;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Vocal remover. Decodes a song, removes everything that sits in the CENTRE of the stereo image
 * between ~140 Hz and ~8.5 kHz (lead vocals, but little else), and keeps
 * the centre bass / kick (< 140 Hz), the cymbals / air (> 8.5 kHz) and all hard-panned instruments.
 * Result is a 16-bit stereo WAV that MediaPlayer plays like any other file (seek, speed, EQ keep working).
 */
public class Karaoke {
    public interface Progress { void on(int pct); }

    static File dir(Context c) { File d = new File(c.getCacheDir(), "karaoke"); d.mkdirs(); return d; }
    public static File fileFor(Context c, long id) { return new File(dir(c), id + ".wav"); }
    public static boolean ready(Context c, long id) { File f = fileFor(c, id); return f.exists() && f.length() > 4096; }

    /** 2nd-order Butterworth biquad (transposed direct form II). */
    static class Bq {
        double b0, b1, b2, a1, a2, z1, z2;
        Bq(boolean hp, double fc, double sr) {
            double w = 2 * Math.PI * fc / sr, cs = Math.cos(w), al = Math.sin(w) / (2 * 0.70710678), a0 = 1 + al;
            if (hp) { b0 = (1 + cs) / 2 / a0; b1 = -(1 + cs) / a0; b2 = b0; }
            else { b0 = (1 - cs) / 2 / a0; b1 = (1 - cs) / a0; b2 = b0; }
            a1 = -2 * cs / a0; a2 = (1 - al) / a0;
        }
        double p(double x) { double y = b0 * x + z1; z1 = b1 * x - a1 * y + z2; z2 = b2 * x - a2 * y; return y; }
    }

    static void header(OutputStream o, int sr, int ch, long dataBytes) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt((int) (36 + dataBytes)).put("WAVE".getBytes()).put("fmt ".getBytes())
            .putInt(16).putShort((short) 1).putShort((short) ch).putInt(sr).putInt(sr * ch * 2).putShort((short) (ch * 2)).putShort((short) 16)
            .put("data".getBytes()).putInt((int) dataBytes);
        o.write(b.array());
    }

    /** blocking. returns true when the instrumental was written. */
    public static boolean process(Context c, long id, AtomicBoolean cancel, Progress cb) throws Exception {
        // keep the cache small: delete all but the 2 newest other files
        File[] old = dir(c).listFiles();
        if (old != null) {
            Arrays.sort(old, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = 2; i < old.length; i++) old[i].delete();
        }
        File out = fileFor(c, id), tmp = new File(dir(c), id + ".part");
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        try (RandomAccessFile raf = new RandomAccessFile(tmp, "rw")) {
            raf.setLength(0);
            ex.setDataSource(c, Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(id)), null);
            int ti = -1; MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) { ti = i; fmt = f; break; }
            }
            if (ti < 0) return false;
            ex.selectTrack(ti);
            long durUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            dec.configure(fmt, null, null, 0);
            dec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false, headerDone = false;
            int sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT), enc = 2;
            Bq[] f = null;
            long bytes = 0; int lastPct = -1;
            final double beta = 0.97;
            byte[] ob = new byte[1 << 16];
            BufferedOutputStream bo = new BufferedOutputStream(new FileOutputStream(raf.getFD()), 1 << 16);
            header(bo, sr, 2, 0); headerDone = true;   // patched at the end (sr/ch re-checked on format change)

            while (!outDone) {
                if (cancel.get()) { bo.flush(); tmp.delete(); return false; }
                if (!inDone) {
                    int ii = dec.dequeueInputBuffer(8000);
                    if (ii >= 0) {
                        ByteBuffer b = dec.getInputBuffer(ii);
                        int n = ex.readSampleData(b, 0);
                        if (n < 0) { dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                        else { dec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                    }
                }
                int oi = dec.dequeueOutputBuffer(info, 8000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    enc = of.containsKey("pcm-encoding") ? of.getInteger("pcm-encoding") : 2;
                    if (ch != 2) return false;            // mono / surround: nothing to separate
                    f = new Bq[]{new Bq(true, 140, sr), new Bq(true, 140, sr), new Bq(false, 8500, sr), new Bq(false, 8500, sr)};
                } else if (oi >= 0) {
                    if (info.size > 0 && f != null) {
                        ByteBuffer b = dec.getOutputBuffer(oi);
                        b.position(info.offset); b.limit(info.offset + info.size); b.order(ByteOrder.LITTLE_ENDIAN);
                        boolean fl = enc == 4;
                        int frames = info.size / (fl ? 8 : 4), p = 0;
                        for (int k = 0; k < frames; k++) {
                            double l, r;
                            if (fl) { l = b.getFloat() * 32768.0; r = b.getFloat() * 32768.0; }
                            else { l = b.getShort(); r = b.getShort(); }
                            double m = (l + r) * 0.5;
                            double mb = f[3].p(f[2].p(f[1].p(f[0].p(m))));
                            double lo = l - beta * mb, ro = r - beta * mb;
                            int a = (int) Math.max(-32768, Math.min(32767, lo)), d = (int) Math.max(-32768, Math.min(32767, ro));
                            ob[p++] = (byte) a; ob[p++] = (byte) (a >> 8); ob[p++] = (byte) d; ob[p++] = (byte) (d >> 8);
                            if (p >= ob.length - 4) { bo.write(ob, 0, p); bytes += p; p = 0; }
                        }
                        if (p > 0) { bo.write(ob, 0, p); bytes += p; }
                        if (durUs > 0) { int pct = (int) Math.min(99, info.presentationTimeUs * 100 / durUs); if (pct != lastPct && pct % 2 == 0) { lastPct = pct; cb.on(pct); } }
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
            }
            bo.flush();
            if (f == null || bytes < 1000) { tmp.delete(); return false; }
            raf.seek(0);
            ByteArrayOutputStream hb = new ByteArrayOutputStream(); header(hb, sr, 2, bytes);
            raf.write(hb.toByteArray());
        } catch (Throwable t) { tmp.delete(); return false; }
        finally { try { if (dec != null) { dec.stop(); dec.release(); } } catch (Throwable t) { } try { ex.release(); } catch (Throwable t) { } }
        out.delete();
        return tmp.renameTo(out);
    }
}
