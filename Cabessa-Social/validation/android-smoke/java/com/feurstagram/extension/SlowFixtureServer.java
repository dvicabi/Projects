package com.feurstagram.extension;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Test-only delayed loopback response exercises preparation completing after occlusion. */
final class SlowFixtureServer implements Closeable {
    private final ServerSocket server;
    private final byte[] fixture;
    private final long delayMillis;
    private volatile boolean closed;
    volatile boolean requested;

    SlowFixtureServer(byte[] bytes, long delayMillis) throws Exception {
        fixture = bytes;
        this.delayMillis = delayMillis;
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        Thread accept = new Thread(() -> {
            while (!closed) {
                try {
                    Socket socket = server.accept();
                    Thread worker = new Thread(() -> respond(socket), "smoke-media-response");
                    worker.setDaemon(true);
                    worker.start();
                } catch (Exception ignored) { if (!closed) closed = true; }
            }
        }, "smoke-media-server");
        accept.setDaemon(true);
        accept.start();
    }

    String url() { return "http://127.0.0.1:" + server.getLocalPort() + "/fixture.mp4"; }

    private void respond(Socket socket) {
        try (Socket connection = socket) {
            connection.setSoTimeout(8000);
            BufferedReader input = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
            String request = input.readLine();
            if (request == null) return;
            int offset = 0;
            boolean range = false;
            String line;
            while ((line = input.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("range: bytes=")) {
                    range = true;
                    offset = Integer.parseInt(line.substring(line.indexOf('=') + 1).split("-", 2)[0]);
                }
            }
            requested = true;
            Thread.sleep(delayMillis);
            if (closed) return;
            offset = Math.min(Math.max(0, offset), fixture.length - 1);
            String headers = "HTTP/1.1 " + (range ? "206 Partial Content" : "200 OK") + "\r\n"
                    + "Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\n"
                    + "Content-Length: " + (fixture.length - offset) + "\r\n"
                    + (range ? "Content-Range: bytes " + offset + "-" + (fixture.length - 1) + "/" + fixture.length + "\r\n" : "")
                    + "Connection: close\r\n\r\n";
            OutputStream output = connection.getOutputStream();
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            if (!request.startsWith("HEAD ")) output.write(fixture, offset, fixture.length - offset);
            output.flush();
        } catch (Exception ignored) {
            // Releasing the actual MediaPlayer may close an in-flight request.
        }
    }

    @Override public void close() throws java.io.IOException { closed = true; server.close(); }
}
