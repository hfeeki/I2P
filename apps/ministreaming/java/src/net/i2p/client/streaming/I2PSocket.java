package net.i2p.client.streaming;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.data.Destination;

/**
 * Minimalistic adapter between the socket api and I2PTunnel's way.
 * Note that this interface is a "subinterface" of the interface
 * defined in the "official" streaming api.
 */
public interface I2PSocket {
    /**
     * Return the Destination of this side of the socket.
     */
    public Destination getThisDestination();

    /**
     * Return the destination of the peer.
     */
    public Destination getPeerDestination();

    /**
     * Return an InputStream to read from the socket.
     */
    public InputStream getInputStream() throws IOException;

    /**
     * Return an OutputStream to write into the socket.
     */
    public OutputStream getOutputStream() throws IOException;

    /**
     * How long we will wait blocked on a read() operation.
     *
     * @return milliseconds to wait, or -1 if we will wait indefinitely
     */
    public long getReadTimeout();

    /**
     * Define how long we will wait blocked on a read() operation (-1 will make
     * the socket wait forever).
     *
     */
    public void setReadTimeout(long ms);

    /**
     * Closes the socket if not closed yet
     */
    public void close() throws IOException;
}
