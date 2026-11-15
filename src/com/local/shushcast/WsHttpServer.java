package com.local.shushcast;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;

public final class WsHttpServer {
    private static final byte[] MANIFEST_JSON = ("{"
            + "\"name\":\"Shush Cast\","
            + "\"short_name\":\"Shush Cast\","
            + "\"start_url\":\"/\","
            + "\"display\":\"standalone\","
            + "\"background_color\":\"#0d1017\","
            + "\"theme_color\":\"#0d1017\","
            + "\"icons\":["
            + "{\"src\":\"/icon-180.png\",\"sizes\":\"180x180\",\"type\":\"image/png\"},"
            + "{\"src\":\"/icon-512.png\",\"sizes\":\"512x512\",\"type\":\"image/png\"}"
            + "]}"
    ).getBytes(StandardCharsets.UTF_8);

    private static final byte[] SW_JS = (
            "const CACHE_NAME = 'shushcast-v1';\n"
            + "const ASSETS = [\n"
            + "  '/',\n"
            + "  '/manifest.webmanifest',\n"
            + "  '/silence.wav',\n"
            + "  '/apple-touch-icon.png',\n"
            + "  '/icon-180.png',\n"
            + "  '/icon-512.png'\n"
            + "];\n\n"
            + "self.addEventListener('install', (event) => {\n"
            + "  event.waitUntil(\n"
            + "    caches.open(CACHE_NAME)\n"
            + "      .then((cache) => cache.addAll(ASSETS))\n"
            + "      .then(() => self.skipWaiting())\n"
            + "  );\n"
            + "});\n\n"
            + "self.addEventListener('activate', (event) => {\n"
            + "  event.waitUntil(\n"
            + "    caches.keys()\n"
            + "      .then((keys) => Promise.all(\n"
            + "        keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k))\n"
            + "      ))\n"
            + "      .then(() => self.clients.claim())\n"
            + "  );\n"
            + "});\n\n"
            + "self.addEventListener('fetch', (event) => {\n"
            + "  const req = event.request;\n"
            + "  if (req.method !== 'GET') return;\n"
            + "  const url = new URL(req.url);\n"
            + "  if (url.origin !== self.location.origin) return;\n"
            + "  if (url.pathname === '/ping' || url.pathname === '/ws' || url.pathname === '/sw.js') return;\n\n"
            + "  event.respondWith(\n"
            + "    caches.match(req).then((cached) => {\n"
            + "      const networkFetch = fetch(req).then((res) => {\n"
            + "        if (res && res.ok) {\n"
            + "          const copy = res.clone();\n"
            + "          caches.open(CACHE_NAME).then((cache) => cache.put(req, copy));\n"
            + "        }\n"
            + "        return res;\n"
            + "      });\n"
            + "      if (cached) {\n"
            + "        event.waitUntil(networkFetch.catch(() => {}));\n"
            + "        return cached;\n"
            + "      }\n"
            + "      return networkFetch.catch(() => {\n"
            + "        if (req.mode === 'navigate') {\n"
            + "          return caches.match('/').then((r) => r || new Response('Offline', { status: 503 }));\n"
            + "        }\n"
            + "        return new Response('', { status: 503, statusText: 'Offline' });\n"
            + "      });\n"
            + "    })\n"
            + "  );\n"
            + "});\n"
    ).getBytes(StandardCharsets.UTF_8);

    private final int port;
    private final byte[] playerHtml;
    private final byte[] icon180Png;
    private final byte[] icon512Png;
    private final List<ClientSession> clients = new CopyOnWriteArrayList<>();
    private volatile boolean running = false;
    private volatile boolean capturing = false;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private Listener listener;

    public interface Listener {
        void onClientCountChanged(int count);
    }

    public WsHttpServer(Context context, int port, Listener listener) throws IOException {
        this.port = port;
        this.listener = listener;
        this.playerHtml = readRaw(context, R.raw.player);
        this.icon180Png = renderIconPng(context, R.drawable.ic_launcher, 180);
        this.icon512Png = renderIconPng(context, R.drawable.ic_launcher, 512);
    }

    private static byte[] renderIconPng(Context context, int resId, int size) {
        Drawable drawable = context.getDrawable(resId);
        if (drawable == null) return new byte[0];
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(canvas);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos);
        bitmap.recycle();
        return bos.toByteArray();
    }

    private static byte[] readRaw(Context context, int resId) throws IOException {
        try (InputStream is = context.getResources().openRawResource(resId);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    public void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        running = true;
        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket s = serverSocket.accept();
                    s.setTcpNoDelay(true);
                    new Thread(() -> handleConnection(s), "shush-conn").start();
                } catch (IOException e) {
                    if (running) e.printStackTrace();
                }
            }
        }, "shush-accept");
        acceptThread.start();
    }

    public void stop() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
        }
        for (ClientSession c : clients) c.close();
        clients.clear();
        notifyCount();
    }

    public int getClientCount() {
        return clients.size();
    }

    public void setCapturing(boolean capturing) {
        this.capturing = capturing;
        broadcastMeta();
    }

    public void broadcastAudio(byte[] pcm, int len) {
        if (clients.isEmpty()) return;
        byte[] frame = buildWsFrame(0x2, pcm, len);
        for (ClientSession c : clients) {
            c.enqueue(frame);
        }
    }

    private void broadcastMeta() {
        String json = "{\"sampleRate\":48000,\"channels\":2,\"clients\":" + clients.size()
                + ",\"capturing\":" + capturing + "}";
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        byte[] frame = buildWsFrame(0x1, payload, payload.length);
        for (ClientSession c : clients) {
            c.enqueue(frame);
        }
    }

    private void notifyCount() {
        if (listener != null) listener.onClientCountChanged(clients.size());
        broadcastMeta();
    }

    private void handleConnection(Socket socket) {
        try {
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            StringBuilder req = new StringBuilder();
            int prev = 0, cur;
            while ((cur = in.read()) != -1) {
                req.append((char) cur);
                int len = req.length();
                if (len >= 4 && req.charAt(len - 4) == '\r' && req.charAt(len - 3) == '\n'
                        && req.charAt(len - 2) == '\r' && req.charAt(len - 1) == '\n') {
                    break;
                }
                if (len > 16384) {
                    socket.close();
                    return;
                }
            }
            String headers = req.toString();
            if (headers.isEmpty()) {
                socket.close();
                return;
            }
            String[] lines = headers.split("\r\n");
            String firstLine = lines[0];
            String wsKey = null;
            for (int i = 1; i < lines.length; i++) {
                int idx = lines[i].indexOf(':');
                if (idx > 0) {
                    String k = lines[i].substring(0, idx).trim();
                    String v = lines[i].substring(idx + 1).trim();
                    if (k.equalsIgnoreCase("Sec-WebSocket-Key")) wsKey = v;
                }
            }

            String path = extractPath(firstLine);
            if (wsKey != null && "/ws".equals(path)) {
                String accept = computeWsAccept(wsKey);
                String resp = "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
                out.write(resp.getBytes(StandardCharsets.US_ASCII));
                out.flush();

                ClientSession session = new ClientSession(socket, in, out);
                clients.add(session);
                notifyCount();
                session.runLoop();
                clients.remove(session);
                notifyCount();
            } else if ("/ping".equals(path)) {
                String pingJson = "{\"ok\":true,\"capturing\":" + capturing
                        + ",\"clients\":" + clients.size() + "}";
                sendResponse(out, "application/json; charset=utf-8", "no-store",
                        pingJson.getBytes(StandardCharsets.UTF_8));
                socket.close();
            } else if ("/sw.js".equals(path)) {
                sendResponse(out, "application/javascript; charset=utf-8", "no-cache", SW_JS);
                socket.close();
            } else if ("/silence.wav".equals(path)) {
                sendResponse(out, "audio/wav", "public, max-age=604800", buildSilentWav());
                socket.close();
            } else if ("/apple-touch-icon.png".equals(path) || "/icon-180.png".equals(path)) {
                sendResponse(out, "image/png", "public, max-age=604800", icon180Png);
                socket.close();
            } else if ("/icon-512.png".equals(path)) {
                sendResponse(out, "image/png", "public, max-age=604800", icon512Png);
                socket.close();
            } else if ("/manifest.webmanifest".equals(path)) {
                sendResponse(out, "application/manifest+json; charset=utf-8", "public, max-age=86400", MANIFEST_JSON);
                socket.close();
            } else {
                sendResponse(out, "text/html; charset=utf-8", "public, max-age=86400", playerHtml);
                socket.close();
            }
        } catch (Exception e) {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private static String extractPath(String requestLine) {
        int sp1 = requestLine.indexOf(' ');
        if (sp1 < 0) return "/";
        int sp2 = requestLine.indexOf(' ', sp1 + 1);
        String target = sp2 > sp1 ? requestLine.substring(sp1 + 1, sp2) : requestLine.substring(sp1 + 1);
        int q = target.indexOf('?');
        return q >= 0 ? target.substring(0, q) : target;
    }

    private static void sendResponse(OutputStream out, String contentType, String cacheControl, byte[] body)
            throws IOException {
        String hdr = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Cache-Control: " + cacheControl + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(hdr.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static byte[] buildSilentWav() {
        int samples = 8000;
        int dataSize = samples * 2;
        byte[] w = new byte[44 + dataSize];
        byte[] hdr = new byte[]{
            'R','I','F','F',
            (byte)((36+dataSize)&0xff),(byte)(((36+dataSize)>>8)&0xff),(byte)(((36+dataSize)>>16)&0xff),(byte)(((36+dataSize)>>24)&0xff),
            'W','A','V','E','f','m','t',' ',
            16,0,0,0, 1,0, 1,0,
            (byte)(8000&0xff),(byte)((8000>>8)&0xff),0,0,
            (byte)(16000&0xff),(byte)((16000>>8)&0xff),0,0,
            2,0, 16,0,
            'd','a','t','a',
            (byte)(dataSize&0xff),(byte)((dataSize>>8)&0xff),(byte)(((dataSize)>>16)&0xff),(byte)(((dataSize)>>24)&0xff)
        };
        System.arraycopy(hdr, 0, w, 0, 44);
        return w;
    }

    private static String computeWsAccept(String key) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash = sha1.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                .getBytes(StandardCharsets.US_ASCII));
        return Base64.getEncoder().encodeToString(hash);
    }

    private static byte[] buildWsFrame(int opcode, byte[] data, int len) {
        int hdrLen = len <= 125 ? 2 : (len <= 65535 ? 4 : 10);
        byte[] frame = new byte[hdrLen + len];
        frame[0] = (byte) (0x80 | (opcode & 0x0F));
        if (len <= 125) {
            frame[1] = (byte) len;
        } else if (len <= 65535) {
            frame[1] = 126;
            frame[2] = (byte) ((len >> 8) & 0xFF);
            frame[3] = (byte) (len & 0xFF);
        } else {
            frame[1] = 127;
            for (int i = 0; i < 8; i++) frame[2 + i] = (byte) ((len >> (56 - i * 8)) & 0xFF);
        }
        System.arraycopy(data, 0, frame, hdrLen, len);
        return frame;
    }

    private static final class ClientSession {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(24);
        private volatile boolean alive = true;

        ClientSession(Socket socket, InputStream in, OutputStream out) {
            this.socket = socket;
            this.in = in;
            this.out = out;
        }

        void enqueue(byte[] frame) {
            if (!alive) return;
            if (!queue.offer(frame)) {
                queue.poll();
                queue.offer(frame);
            }
        }

        void runLoop() {
            Thread reader = new Thread(() -> {
                try {
                    byte[] buf = new byte[512];
                    while (alive) {
                        int b0 = in.read();
                        if (b0 == -1) break;
                        int opcode = b0 & 0x0F;
                        if (opcode == 0x8) break; // close frame
                        int b1 = in.read();
                        if (b1 == -1) break;
                        long plen = b1 & 0x7F;
                        if (plen == 126) {
                            plen = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
                        } else if (plen == 127) {
                            for (int i = 0; i < 8; i++) in.read();
                        }
                        if ((b1 & 0x80) != 0) {
                            for (int i = 0; i < 4; i++) in.read();
                        }
                        long rem = plen;
                        while (rem > 0) {
                            int r = in.read(buf, 0, (int) Math.min(buf.length, rem));
                            if (r <= 0) break;
                            rem -= r;
                        }
                        if (opcode == 0x9) { // ping -> pong
                            enqueue(buildWsFrame(0xA, new byte[0], 0));
                        }
                    }
                } catch (IOException ignored) {
                } finally {
                    close();
                }
            }, "shush-ws-in");
            reader.start();

            try {
                while (alive) {
                    byte[] frame = queue.take();
                    if (!alive || frame.length == 0) break;
                    out.write(frame);
                    out.flush();
                }
            } catch (Exception ignored) {
            } finally {
                close();
            }
        }

        void close() {
            if (!alive) return;
            alive = false;
            queue.clear();
            queue.offer(new byte[0]);
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}
