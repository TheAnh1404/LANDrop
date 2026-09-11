package com.landrop.peer;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Listens for incoming peer TCP connections and produces {@link PeerConnection} instances.
 */
public class PeerListener implements AutoCloseable {

    private final ServerSocket serverSocket;

    /**
     * Creates a PeerListener bound to the specified port.
     * Use port 0 for system-assigned dynamic port.
     *
     * @param port the TCP port to listen on
     * @throws IOException if binding fails
     */
    public PeerListener(int port) throws IOException {
        this.serverSocket = new ServerSocket(port);
    }

    /**
     * Waits for and accepts the next incoming peer connection.
     *
     * @return a new PeerConnection wrapping the accepted socket
     * @throws IOException if an I/O error occurs while waiting for a connection
     */
    public PeerConnection acceptConnection() throws IOException {
        Socket socket = serverSocket.accept();
        return new PeerConnection(socket);
    }

    /**
     * Returns the port on which this listener is currently bound.
     *
     * @return the local port
     */
    public int getPort() {
        return serverSocket.getLocalPort();
    }

    /**
     * Checks if the listener server socket is closed.
     *
     * @return true if closed, false otherwise
     */
    public boolean isClosed() {
        return serverSocket.isClosed();
    }

    @Override
    public void close() throws IOException {
        if (!serverSocket.isClosed()) {
            serverSocket.close();
        }
    }
}
