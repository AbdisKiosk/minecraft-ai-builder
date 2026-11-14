package me.abdiskiosk.minecraftaibuilder.plugin.relay;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A WebSocket client (RFC 6455) on a plain socket, because the plugin also runs on Java 8, which has none
 * ({@code java.net.http} came with Java 11). Text messages, ping, pong and close; no extensions, no proxy.
 * {@link #connect} blocks for the handshake, {@link #start} begins reading on a thread of its own, and sends
 * block until written, so callers keep them off the server thread.
 */
final class WebSocketClient {

    interface Listener {
        void onText(String text);

        /** A ping or pong arrived. */
        void onHeard();

        /** The other side closed the connection; 1005 when it gave no code. */
        void onClose(int code, String reason);

        /** The connection broke without a close frame. Not called after {@link #abort} or {@link #close}. */
        void onError(Throwable error);
    }

    static final int NORMAL_CLOSURE = 1000;
    private static final int NO_STATUS = 1005;
    private static final int TOO_BIG = 1009;
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    /** Bounds what one message from the panel can make the server hold. */
    private static final int MAX_MESSAGE_BYTES = 64 * 1024 * 1024;
    private static final int TEXT = 1, BINARY = 2, CLOSE = 8, PING = 9, PONG = 10;

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private final Object writeLock = new Object();
    /** Set by abort() and close(): what the reader meets after that is our doing, not news. */
    private volatile boolean silent;
    private boolean closeSent;
    private volatile boolean reading;
    private final CountDownLatch readerDone = new CountDownLatch(1);

    private WebSocketClient(Socket socket, DataInputStream in, OutputStream out, Listener listener) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.listener = listener;
    }

    /** Opens the connection and completes the handshake, each step within the timeout. */
    static WebSocketClient connect(URI uri, Map<String, String> headers, int timeoutMillis, Listener listener) throws IOException {
        boolean tls = "wss".equals(uri.getScheme());
        String host = uri.getHost();
        if (host == null) throw new IOException("no host in " + uri);
        int port = uri.getPort() != -1 ? uri.getPort() : tls ? 443 : 80;
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            socket.setTcpNoDelay(true);
            if (tls) {
                SSLSocket secure = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(socket, host, port, true);
                // A bare SSLSocket accepts a certificate for any host; this makes it check the name, as HTTPS does.
                SSLParameters parameters = secure.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                secure.setSSLParameters(parameters);
                secure.startHandshake();
                socket = secure;
            }
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 16 * 1024));
            OutputStream out = socket.getOutputStream();

            byte[] nonce = new byte[16];
            new SecureRandom().nextBytes(nonce);
            String key = Base64.getEncoder().encodeToString(nonce);
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
            boolean defaultPort = port == (tls ? 443 : 80);
            StringBuilder request = new StringBuilder();
            request.append("GET ").append(path).append(" HTTP/1.1\r\n");
            request.append("Host: ").append(host.contains(":") ? "[" + host + "]" : host).append(defaultPort ? "" : ":" + port).append("\r\n");
            request.append("Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n");
            request.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
            for (Map.Entry<String, String> header : headers.entrySet()) {
                String name = header.getKey(), value = header.getValue();
                if (name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                    throw new IOException("line break in the " + name.trim() + " header");
                }
                request.append(name).append(": ").append(value).append("\r\n");
            }
            request.append("\r\n");
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            String[] lines = readHeader(in).split("\r\n");
            String[] status = lines[0].split(" ", 3);
            if (status.length < 2 || !status[0].startsWith("HTTP/1.1")) throw new IOException("not a WebSocket server");
            if (!"101".equals(status[1])) throw new IOException("the panel answered HTTP " + status[1]);
            String accept = null;
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0 && lines[i].substring(0, colon).trim().equalsIgnoreCase("Sec-WebSocket-Accept")) {
                    accept = lines[i].substring(colon + 1).trim();
                }
            }
            if (!expectedAccept(key).equals(accept)) throw new IOException("not a WebSocket server");
            socket.setSoTimeout(0);
            return new WebSocketClient(socket, in, out, listener);
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // the first failure is the one to report
            }
            throw e;
        }
    }

    private static String readHeader(InputStream in) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0) throw new EOFException("connection closed during the handshake");
            header.write(b);
            if (header.size() > MAX_HEADER_BYTES) throw new IOException("handshake answer too large");
            boolean wanted = b == (matched % 2 == 0 ? '\r' : '\n');
            matched = wanted ? matched + 1 : b == '\r' ? 1 : 0;
        }
        return new String(header.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static String expectedAccept(String key) {
        try {
            byte[] sha1 = MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(sha1);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Starts delivering to the listener, from one daemon thread. */
    void start(String threadName) {
        reading = true;
        Thread reader = new Thread(() -> {
            try {
                read();
            } finally {
                readerDone.countDown();
            }
        }, threadName);
        reader.setDaemon(true);
        reader.start();
    }

    void sendText(String text) throws IOException {
        write(TEXT, text.getBytes(StandardCharsets.UTF_8));
    }

    void sendPing() throws IOException {
        write(PING, new byte[0]);
    }

    /**
     * Says goodbye, waits a second for the other side's goodbye and closes the connection. Closing at once would
     * reset it, and the other side would see a dropped connection instead of the code.
     */
    void close(int code, String reason) {
        silent = true;
        try {
            writeClose(code, reason);
            if (reading) readerDone.await(1, TimeUnit.SECONDS);
        } catch (IOException ignored) {
            // it is going away either way
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        abort();
    }

    /** Drops the connection at once; a send or read in progress fails. */
    void abort() {
        silent = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing left to release
        }
    }

    private void read() {
        try {
            ByteArrayOutputStream message = new ByteArrayOutputStream();
            boolean inMessage = false, text = false;
            while (true) {
                int first = in.readUnsignedByte();
                int second = in.readUnsignedByte();
                boolean fin = (first & 0x80) != 0;
                int opcode = first & 0x0F;
                if ((first & 0x70) != 0) throw new IOException("unexpected WebSocket extension");
                long length = second & 0x7F;
                if (length == 126) length = in.readUnsignedShort();
                else if (length == 127) length = in.readLong();
                boolean control = opcode >= 8;
                if (length < 0 || (control && (length > 125 || !fin))) throw new IOException("malformed WebSocket frame");
                if (length > MAX_MESSAGE_BYTES - message.size()) {
                    writeClose(TOO_BIG, "message too large");
                    throw new IOException("the panel sent a message over " + MAX_MESSAGE_BYTES / (1024 * 1024) + " MB");
                }
                byte[] mask = null;
                if ((second & 0x80) != 0) {
                    mask = new byte[4];
                    in.readFully(mask);
                }
                byte[] payload = new byte[(int) length];
                in.readFully(payload);
                if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];

                switch (opcode) {
                    case 0:
                    case TEXT:
                    case BINARY:
                        if ((opcode == 0) != inMessage) throw new IOException("malformed WebSocket message");
                        if (opcode != 0) text = opcode == TEXT;
                        message.write(payload, 0, payload.length);
                        inMessage = !fin;
                        if (fin) {
                            // Binary messages are not part of the relay protocol; dropped like the JDK's default listener did.
                            if (text) listener.onText(new String(message.toByteArray(), StandardCharsets.UTF_8));
                            message = new ByteArrayOutputStream();
                        }
                        break;
                    case PING:
                        write(PONG, payload);
                        listener.onHeard();
                        break;
                    case PONG:
                        listener.onHeard();
                        break;
                    case CLOSE:
                        int code = payload.length >= 2 ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF) : NO_STATUS;
                        String reason = payload.length > 2 ? new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8) : "";
                        boolean ours = silent;
                        try {
                            writeClose(payload.length >= 2 ? code : NORMAL_CLOSURE, "");
                        } catch (IOException ignored) {
                            // the close itself is what matters
                        }
                        abort();
                        if (!ours) listener.onClose(code, reason);
                        return;
                    default:
                        throw new IOException("unknown WebSocket frame type " + opcode);
                }
            }
        } catch (IOException | RuntimeException e) {
            boolean ours = silent;
            abort();
            if (!ours) listener.onError(e);
        }
    }

    private void writeClose(int code, String reason) throws IOException {
        byte[] why = reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + Math.min(why.length, 123)];
        payload[0] = (byte) (code >> 8);
        payload[1] = (byte) code;
        System.arraycopy(why, 0, payload, 2, payload.length - 2);
        synchronized (writeLock) {
            if (closeSent) return;
            closeSent = true;
            frame(CLOSE, payload);
        }
    }

    private void write(int opcode, byte[] payload) throws IOException {
        synchronized (writeLock) {
            if (closeSent) throw new IOException("connection closing");
            frame(opcode, payload);
        }
    }

    /** One unfragmented, masked frame (a client must mask); the caller holds writeLock. */
    private void frame(int opcode, byte[] payload) throws IOException {
        int n = payload.length;
        int header = n < 126 ? 2 : n < 65536 ? 4 : 10;
        byte[] frame = new byte[header + 4 + n];
        frame[0] = (byte) (0x80 | opcode);
        if (n < 126) {
            frame[1] = (byte) (0x80 | n);
        } else if (n < 65536) {
            frame[1] = (byte) (0x80 | 126);
            frame[2] = (byte) (n >> 8);
            frame[3] = (byte) n;
        } else {
            frame[1] = (byte) (0x80 | 127);
            for (int i = 0; i < 8; i++) frame[2 + i] = (byte) ((long) n >> (8 * (7 - i)));
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        System.arraycopy(mask, 0, frame, header, 4);
        for (int i = 0; i < n; i++) frame[header + 4 + i] = (byte) (payload[i] ^ mask[i & 3]);
        out.write(frame);
        out.flush();
    }
}
