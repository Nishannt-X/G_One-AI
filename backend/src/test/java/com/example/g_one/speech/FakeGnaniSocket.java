package com.example.g_one.speech;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A minimal WebSocket server on a raw socket, standing in for the Gnani realtime endpoints. It records the upgrade
 * request (path and headers) and every frame the client sends, and lets a test push text frames or drop the connection.
 * Nothing leaves the machine.
 */
final class FakeGnaniSocket implements AutoCloseable {
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /** One client connection. */
    final class Conn {
        final Socket socket;
        final String path;
        final Map<String, String> headers;
        final List<byte[]> binary = new CopyOnWriteArrayList<>();
        final List<String> text = new CopyOnWriteArrayList<>();
        private final OutputStream out;

        Conn(Socket socket, String path, Map<String, String> headers) throws IOException {
            this.socket = socket;
            this.path = path;
            this.headers = headers;
            this.out = socket.getOutputStream();
        }

        synchronized void sendText(String message) {
            byte[] payload = message.getBytes(StandardCharsets.UTF_8);
            try {
                ByteArrayOutputStream frame = new ByteArrayOutputStream();
                frame.write(0x81);
                if (payload.length < 126) {
                    frame.write(payload.length);
                } else {
                    frame.write(126);
                    frame.write(payload.length >> 8);
                    frame.write(payload.length & 0xFF);
                }
                frame.write(payload);
                out.write(frame.toByteArray());
                out.flush();
            } catch (IOException ignored) {
                // The client went away; the test will notice through its own assertions.
            }
        }

        /** Drops the connection without a close frame. */
        void drop() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // already gone
            }
        }
    }

    final List<Conn> connections = new CopyOnWriteArrayList<>();
    private final ServerSocket server;
    private volatile Consumer<Conn> onConnect = c -> { };
    private volatile Consumer<String> onText = t -> { };

    FakeGnaniSocket() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(this::acceptLoop);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    /** Runs when a client has completed the upgrade. */
    void onConnect(Consumer<Conn> handler) {
        this.onConnect = handler;
    }

    /** Runs for every text frame, on the connection's thread; use {@link #connections} to reply. */
    void onText(Consumer<String> handler) {
        this.onText = handler;
    }

    Conn last() {
        return connections.get(connections.size() - 1);
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        for (Conn c : connections) c.drop();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread.ofVirtual().start(() -> serve(socket));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            String requestLine = readLine(in);
            Map<String, String> headers = new HashMap<>();
            String line;
            while (!(line = readLine(in)).isEmpty()) {
                int colon = line.indexOf(':');
                headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
            }
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((headers.get("sec-websocket-key") + GUID).getBytes(StandardCharsets.US_ASCII)));
            socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                    + "Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            Conn conn = new Conn(socket, requestLine.split(" ")[1], headers);
            connections.add(conn);
            onConnect.accept(conn);
            readFrames(in, conn);
        } catch (Exception e) {
            // a dropped or closed connection ends the loop
        }
    }

    private void readFrames(DataInputStream in, Conn conn) throws IOException {
        while (true) {
            int b0 = in.readUnsignedByte();
            int b1 = in.readUnsignedByte();
            int opcode = b0 & 0x0F;
            long length = b1 & 0x7F;
            if (length == 126) length = in.readUnsignedShort();
            else if (length == 127) length = in.readLong();
            byte[] mask = new byte[4];
            if ((b1 & 0x80) != 0) in.readFully(mask);
            byte[] payload = new byte[(int) length];
            in.readFully(payload);
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
            switch (opcode) {
                case 1 -> {
                    String t = new String(payload, StandardCharsets.UTF_8);
                    conn.text.add(t);
                    onText.accept(t);
                }
                case 2 -> conn.binary.add(payload);
                case 8 -> {
                    conn.socket.getOutputStream().write(new byte[] {(byte) 0x88, 0});
                    conn.socket.close();
                    return;
                }
                default -> { }
            }
        }
    }

    private static String readLine(DataInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return sb.toString();
    }
}
