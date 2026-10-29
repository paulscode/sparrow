package com.sparrowwallet.sparrow.net;

import com.google.common.net.HostAndPort;
import com.sparrowwallet.sparrow.SparrowWallet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two ways a server that is not Fulcrum or electrs was reported to break this client (Shrike #67), both of which read
 * as a server that hangs or a generic failure. Each is now an error saying what the server did.
 */
public class TcpTransportProtocolTest {
    @TempDir
    private static Path tempHome;

    @BeforeAll
    public static void setUp() {
        System.setProperty(SparrowWallet.APP_HOME_PROPERTY, tempHome.toString());
    }

    @AfterAll
    public static void tearDown() {
        System.clearProperty(SparrowWallet.APP_HOME_PROPERTY);
    }

    /** A server that answers only the first object of a batch was waited out as silent, and retried as a hang. */
    @Test
    public void aBatchAnsweredInPartSaysSo() throws Exception {
        IOException e = exchange("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server.ping\",\"params\":[]},{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"server.ping\",\"params\":[]}]",
                request -> "[{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}]");
        assertTrue(e.getMessage().contains("answered 1 of the 2 requests"), e.getMessage());
    }

    /** A reply without the version field was refused by the client and retried as though it were transient. */
    @Test
    public void aReplyWithoutTheVersionFieldSaysSo() throws Exception {
        IOException e = exchange("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server.ping\",\"params\":[]}",
                request -> "{\"id\":1,\"result\":null}");
        assertTrue(e.getMessage().contains("without the \"jsonrpc\": \"2.0\" field"), e.getMessage());
    }

    @Test
    public void aCompliantReplyPassesThrough() throws Exception {
        try(ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            startServer(serverSocket, request -> "[{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null},{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":null}]");
            TcpTransport transport = connect(serverSocket);
            try {
                String reply = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> transport.pass(
                        "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server.ping\",\"params\":[]},{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"server.ping\",\"params\":[]}]"));
                assertTrue(reply.contains("\"id\":2"), reply);
            } finally {
                transport.close();
            }
        }
    }

    private IOException exchange(String request, UnaryOperator<String> reply) throws Exception {
        try(ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            startServer(serverSocket, reply);
            TcpTransport transport = connect(serverSocket);
            try {
                return assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(IOException.class, () -> transport.pass(request)),
                        "must fail at once with a reason, not be waited out");
            } finally {
                transport.close();
            }
        }
    }

    private TcpTransport connect(ServerSocket serverSocket) throws ServerException {
        TcpTransport transport = new TcpTransport(HostAndPort.fromParts("127.0.0.1", serverSocket.getLocalPort()));
        Arrays.fill(transport.readTimeouts, 30);
        transport.connect();
        Thread reader = new Thread(() -> {
            try {
                transport.readInputLoop();
            } catch(ServerException e) {
                //Expected once the transport is closed
            }
        });
        reader.setDaemon(true);
        reader.start();
        return transport;
    }

    private void startServer(ServerSocket serverSocket, UnaryOperator<String> reply) {
        Thread server = new Thread(() -> {
            try(Socket accepted = serverSocket.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(accepted.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(new OutputStreamWriter(accepted.getOutputStream(), StandardCharsets.UTF_8));
                String line;
                while((line = in.readLine()) != null) {
                    out.println(reply.apply(line));
                    out.flush();
                }
            } catch(Exception e) {
                //Expected once the test tears down the server and transport
            }
        });
        server.setDaemon(true);
        server.start();
    }
}
