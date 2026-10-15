package com.niki914.zafiro.vdisplay.server;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.system.Os;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Shell-UID (app_process) side of Zafiro's background virtual display.
 *
 * It is a TCP <b>client</b> of the Zafiro app (127.0.0.1:port, same direction that the HyperOS PoC proved works):
 * one JSON object per line in each direction. The process owns every display it creates, so every operation is
 * addressed to a display this process created; display 0 can never be a target. Input is deliberately NOT
 * handled here: a shell-UID process is denied INJECT_EVENTS on HyperOS, so Zafiro injects with
 * {@code input -d <displayId>} from its own root/Shizuku shell. When the socket closes, all displays are released
 * and the process exits.
 *
 * Start: CLASSPATH=&lt;zafiro apk&gt; app_process / com.niki914.zafiro.vdisplay.server.VirtualDisplayServer
 * port=&lt;n&gt; token=&lt;t&gt;
 */
public final class VirtualDisplayServer {

    private static final String TAG = "ZafiroVdServer";

    // DisplayManager flags (some are @hide); same set as scrcpy --new-display and the HyperOS PoC (0xffcb).
    private static final int VD_PUBLIC = 1;
    private static final int VD_PRESENTATION = 1 << 1;
    private static final int VD_OWN_CONTENT_ONLY = 1 << 3;
    private static final int VD_SUPPORTS_TOUCH = 1 << 6;
    private static final int VD_ROTATES_WITH_CONTENT = 1 << 7;
    private static final int VD_DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    private static final int VD_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 << 9;
    private static final int VD_TRUSTED = 1 << 10;
    private static final int VD_OWN_DISPLAY_GROUP = 1 << 11;
    private static final int VD_ALWAYS_UNLOCKED = 1 << 12;
    private static final int VD_TOUCH_FEEDBACK_DISABLED = 1 << 13;
    private static final int VD_OWN_FOCUS = 1 << 14;
    private static final int VD_DEVICE_DISPLAY_GROUP = 1 << 15;

    private static final int MAX_DISPLAYS = 3;
    private static final int MAX_EDGE = 4096;

    private static final class Vd {
        int id;
        VirtualDisplay display;
        ImageReader reader;
        HandlerThread thread;
        Image latest;
        int width;
        int height;
        int dpi;
    }

    private final Map<Integer, Vd> displays = new HashMap<>();
    private final Object frameLock = new Object();
    private BufferedWriter out;
    private final Object writeLock = new Object();

    private VirtualDisplayServer() {
    }

    public static void main(String... args) {
        int status = 0;
        try {
            Map<String, String> opts = parse(args);
            ShellEnv.prepare();
            new VirtualDisplayServer().run(opts.get("host") == null ? "127.0.0.1" : opts.get("host"),
                    Integer.parseInt(opts.get("port")), opts.get("token"));
        } catch (Throwable t) {
            Log.e(TAG, "fatal", t);
            status = 1;
        } finally {
            System.exit(status);
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (String a : args) {
            int eq = a.indexOf('=');
            if (eq > 0) {
                m.put(a.substring(0, eq), a.substring(eq + 1));
            }
        }
        if (!m.containsKey("port") || !m.containsKey("token")) {
            throw new IllegalArgumentException("port= and token= are required");
        }
        return m;
    }

    private void run(String host, int port, String token) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setTcpNoDelay(true);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));

        JSONObject hello = new JSONObject();
        hello.put("hello", true);
        hello.put("token", token);
        hello.put("uid", Os.getuid());
        hello.put("sdk", Build.VERSION.SDK_INT);
        hello.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        send(hello);
        Log.i(TAG, "connected uid=" + Os.getuid());

        try {
            String line;
            while ((line = in.readLine()) != null) {
                JSONObject req;
                try {
                    req = new JSONObject(line);
                } catch (Exception e) {
                    continue;
                }
                int id = req.optInt("id", -1);
                JSONObject resp = new JSONObject();
                resp.put("id", id);
                try {
                    handle(req, resp);
                    resp.put("ok", true);
                } catch (Throwable t) {
                    resp = new JSONObject();
                    resp.put("id", id);
                    resp.put("ok", false);
                    resp.put("error", String.valueOf(t.getMessage() != null ? t.getMessage() : t));
                    Log.w(TAG, "command failed: " + req.optString("cmd"), t);
                }
                send(resp);
                if ("stop".equals(req.optString("cmd"))) {
                    break;
                }
            }
        } finally {
            Log.i(TAG, "cleanup: releasing " + displays.size() + " display(s)");
            for (Vd vd : new ArrayList<>(displays.values())) {
                release(vd);
            }
            displays.clear();
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void send(JSONObject obj) throws IOException {
        synchronized (writeLock) {
            out.write(obj.toString());
            out.write('\n');
            out.flush();
        }
    }

    private void handle(JSONObject req, JSONObject resp) throws Exception {
        String cmd = req.getString("cmd");
        switch (cmd) {
            case "ping":
                return;
            case "stop":
                return;
            case "create":
                create(req, resp);
                return;
            case "destroy":
                destroy(req.getInt("display"));
                return;
            case "resize":
                resize(req, resp);
                return;
            case "list":
                list(resp);
                return;
            case "screenshot":
                screenshot(req, resp);
                return;
            case "launch":
                launch(req, resp);
                return;
            default:
                throw new IllegalArgumentException("unknown command: " + cmd);
        }
    }

    // ---- display lifecycle ----

    private void create(JSONObject req, JSONObject resp) throws Exception {
        if (displays.size() >= MAX_DISPLAYS) {
            throw new IllegalStateException("too many virtual displays (max " + MAX_DISPLAYS + ")");
        }
        int w = checkEdge(req.optInt("width", 1080));
        int h = checkEdge(req.optInt("height", 1920));
        int dpi = req.optInt("dpi", 320);
        if (dpi < 72 || dpi > 640) {
            throw new IllegalArgumentException("dpi out of range: " + dpi);
        }
        String name = req.optString("name", "Zafiro Background");

        Vd vd = new Vd();
        vd.width = w;
        vd.height = h;
        vd.dpi = dpi;
        attachReader(vd, w, h);

        int flags = VD_PUBLIC | VD_PRESENTATION | VD_OWN_CONTENT_ONLY | VD_SUPPORTS_TOUCH | VD_ROTATES_WITH_CONTENT
                | VD_DESTROY_CONTENT_ON_REMOVAL | VD_SHOULD_SHOW_SYSTEM_DECORATIONS | VD_TRUSTED
                | VD_OWN_DISPLAY_GROUP | VD_ALWAYS_UNLOCKED | VD_TOUCH_FEEDBACK_DISABLED;
        if (Build.VERSION.SDK_INT >= 34) {
            // own focus: the display keeps its own focused window, the physical display's focus is untouched
            flags |= VD_OWN_FOCUS | VD_DEVICE_DISPLAY_GROUP;
        }
        try {
            Constructor<DisplayManager> ctor = DisplayManager.class.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            DisplayManager dm = ctor.newInstance(ShellEnv.context());
            vd.display = dm.createVirtualDisplay(name, w, h, dpi, vd.reader.getSurface(), flags);
        } catch (Throwable t) {
            closeReader(vd);
            throw t;
        }
        if (vd.display == null) {
            closeReader(vd);
            throw new IllegalStateException("createVirtualDisplay returned null");
        }
        int id = vd.display.getDisplay().getDisplayId();
        if (id == 0) {
            release(vd);
            throw new IllegalStateException("refusing display id 0");
        }
        vd.id = id;
        displays.put(id, vd);
        resp.put("display", id);
        resp.put("width", w);
        resp.put("height", h);
        resp.put("dpi", dpi);
        resp.put("flags", flags);
        Log.i(TAG, "created display " + id + " " + w + "x" + h + "/" + dpi + " flags=0x" + Integer.toHexString(flags));
    }

    private void attachReader(Vd vd, int w, int h) {
        vd.thread = new HandlerThread("vd-frames");
        vd.thread.start();
        vd.reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        registerListener(vd);
    }

    private void registerListener(Vd vd) {
        final ImageReader reader = vd.reader;
        reader.setOnImageAvailableListener(r -> {
            Image img = null;
            try {
                img = r.acquireLatestImage();
            } catch (Throwable t) {
                Log.w(TAG, "acquireLatestImage failed: " + t);
            }
            if (img == null) {
                return;
            }
            synchronized (frameLock) {
                if (vd.latest != null) {
                    vd.latest.close();
                }
                vd.latest = img;
            }
        }, new Handler(vd.thread.getLooper()));
    }

    private void closeReader(Vd vd) {
        synchronized (frameLock) {
            if (vd.latest != null) {
                vd.latest.close();
                vd.latest = null;
            }
        }
        if (vd.reader != null) {
            vd.reader.close();
            vd.reader = null;
        }
        if (vd.thread != null) {
            vd.thread.quitSafely();
            vd.thread = null;
        }
    }

    private void release(Vd vd) {
        try {
            if (vd.display != null) {
                vd.display.release();
            }
        } catch (Throwable t) {
            Log.w(TAG, "release display failed: " + t);
        }
        closeReader(vd);
    }

    private void destroy(int displayId) {
        Vd vd = require(displayId);
        displays.remove(displayId);
        release(vd);
        Log.i(TAG, "destroyed display " + displayId);
    }

    private void resize(JSONObject req, JSONObject resp) throws Exception {
        Vd vd = require(req.getInt("display"));
        int w = checkEdge(req.getInt("width"));
        int h = checkEdge(req.getInt("height"));
        int dpi = req.optInt("dpi", vd.dpi);
        ImageReader old = vd.reader;
        HandlerThread oldThread = vd.thread;
        synchronized (frameLock) {
            if (vd.latest != null) {
                vd.latest.close();
                vd.latest = null;
            }
        }
        vd.thread = new HandlerThread("vd-frames");
        vd.thread.start();
        vd.reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        registerListener(vd);
        vd.display.setSurface(vd.reader.getSurface());
        vd.display.resize(w, h, dpi);
        old.close();
        oldThread.quitSafely();
        vd.width = w;
        vd.height = h;
        vd.dpi = dpi;
        resp.put("width", w);
        resp.put("height", h);
        resp.put("dpi", dpi);
    }

    private void list(JSONObject resp) throws Exception {
        JSONArray arr = new JSONArray();
        for (Vd vd : displays.values()) {
            JSONObject o = new JSONObject();
            o.put("display", vd.id);
            o.put("width", vd.width);
            o.put("height", vd.height);
            o.put("dpi", vd.dpi);
            arr.put(o);
        }
        resp.put("displays", arr);
    }

    private Vd require(int displayId) {
        Vd vd = displays.get(displayId);
        if (vd == null) {
            throw new IllegalArgumentException("display " + displayId + " is not managed by Zafiro");
        }
        return vd;
    }

    private static int checkEdge(int v) {
        if (v < 64 || v > MAX_EDGE) {
            throw new IllegalArgumentException("size out of range: " + v);
        }
        return v;
    }

    // ---- frames ----

    private void screenshot(JSONObject req, JSONObject resp) throws Exception {
        Vd vd = require(req.getInt("display"));
        boolean jpeg = "jpeg".equals(req.optString("format", "png"));
        int quality = req.optInt("quality", 80);
        int maxSide = req.optInt("max_side", 0);
        Bitmap bmp;
        synchronized (frameLock) {
            Image img = vd.latest;
            if (img == null) {
                throw new IllegalStateException("no frame yet: nothing has been drawn on this display");
            }
            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int w = img.getWidth();
            int h = img.getHeight();
            Bitmap padded = Bitmap.createBitmap(rowStride / pixelStride, h, Bitmap.Config.ARGB_8888);
            buf.rewind();
            padded.copyPixelsFromBuffer(buf);
            bmp = padded.getWidth() == w ? padded : Bitmap.createBitmap(padded, 0, 0, w, h);
            if (bmp != padded) {
                padded.recycle();
            }
        }
        if (maxSide > 0 && Math.max(bmp.getWidth(), bmp.getHeight()) > maxSide) {
            float s = (float) maxSide / Math.max(bmp.getWidth(), bmp.getHeight());
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(bmp.getWidth() * s)),
                    Math.max(1, Math.round(bmp.getHeight() * s)), true);
            bmp.recycle();
            bmp = scaled;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(jpeg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG, quality, bos);
        resp.put("width", bmp.getWidth());
        resp.put("height", bmp.getHeight());
        resp.put("mime", jpeg ? "image/jpeg" : "image/png");
        resp.put("data", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP));
        bmp.recycle();
    }

    // ---- launch ----

    private void launch(JSONObject req, JSONObject resp) throws Exception {
        Vd vd = require(req.getInt("display"));
        String component = req.getString("component");
        if (!component.matches("[A-Za-z0-9._$]+/[A-Za-z0-9._$]+")) {
            throw new IllegalArgumentException("invalid component: " + component);
        }
        List<String> cmd = new ArrayList<>();
        cmd.add("am");
        cmd.add("start");
        cmd.add("--display");
        cmd.add(String.valueOf(vd.id));
        cmd.add("-n");
        cmd.add(component);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = readAll(p);
        if (!p.waitFor(15, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("am start timed out");
        }
        if (p.exitValue() != 0 || output.contains("Error:") || output.contains("Exception")) {
            throw new IllegalStateException("am start failed: " + output.trim());
        }
        resp.put("output", output.trim());
    }

    private static String readAll(Process p) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String l;
        while ((l = r.readLine()) != null) {
            sb.append(l).append('\n');
        }
        return sb.toString();
    }
}
