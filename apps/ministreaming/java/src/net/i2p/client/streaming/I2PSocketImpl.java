package net.i2p.client.streaming;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;

import net.i2p.I2PException;
import net.i2p.client.I2PSessionException;
import net.i2p.data.Destination;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Initial stub implementation for the socket
 *
 */
class I2PSocketImpl implements I2PSocket {
    private final static Log _log = new Log(I2PSocketImpl.class);

    public static final int MAX_PACKET_SIZE = 1024 * 32;
    public static final int PACKET_DELAY = 100;

    private I2PSocketManager manager;
    private Destination local;
    private Destination remote;
    private String localID;
    private String remoteID;
    private Object remoteIDWaiter = new Object();
    private I2PInputStream in;
    private I2POutputStream out;
    private boolean outgoing;
    private Object flagLock = new Object();

    /**
     * Whether the I2P socket has already been closed.
     */
    private boolean closed = false;

    /**
     * Whether to send out a close packet when the socket is
     * closed. (If the socket is closed because of an incoming close
     * packet, we need not send one.)
     */
    private boolean sendClose = true;

    /**
     * Whether the I2P socket has already been closed and all data
     * (from I2P to the app, dunno whether to call this incoming or
     * outgoing) has been processed.
     */
    private boolean closed2 = false;

    /**
     * @param peer who this socket is (or should be) connected to 
     * @param mgr how we talk to the network
     * @param outgoing did we initiate the connection (true) or did we receive it (false)?
     * @param localID what is our half of the socket ID?
     */
    public I2PSocketImpl(Destination peer, I2PSocketManager mgr, boolean outgoing, String localID) {
        this.outgoing = outgoing;
        manager = mgr;
        remote = peer;
        local = mgr.getSession().getMyDestination();
        in = new I2PInputStream();
        I2PInputStream pin = new I2PInputStream();
        out = new I2POutputStream(pin);
        new I2PSocketRunner(pin);
        this.localID = localID;
    }

    /**
     * Our half of the socket's unique ID
     *
     */
    public String getLocalID() {
        return localID;
    }

    /**
     * We've received the other side's half of the socket's unique ID 
     */
    public void setRemoteID(String id) {
        synchronized (remoteIDWaiter) {
            remoteID = id;
            remoteIDWaiter.notifyAll();
        }
    }

    /**
     * Retrieve the other side's half of the socket's unique ID, or null if it
     * isn't known yet
     *
     * @param wait if true, we should wait until we receive it from the peer, otherwise
     *             return what we know immediately (which may be null)
     */
    public String getRemoteID(boolean wait) {
        try {
            return getRemoteID(wait, -1);
        } catch (InterruptedIOException iie) {
            _log.error("wtf, we said we didn't want it to time out!  you smell", iie);
            return null;
        }
    }

    /**
     * Retrieve the other side's half of the socket's unique ID, or null if it isn't
     * known yet and we were instructed not to wait
     *
     * @param wait should we wait for the peer to send us their half of the ID, or 
     *             just return immediately?
     * @param maxWait if we're going to wait, after how long should we timeout and fail?
     *                (if this value is < 0, we wait indefinitely)
     * @throws InterruptedIOException when the max waiting period has been exceeded
     */
    public String getRemoteID(boolean wait, long maxWait) throws InterruptedIOException {
        long dieAfter = System.currentTimeMillis() + maxWait;
        synchronized (remoteIDWaiter) {
            if (wait) {
                try {
                    if (maxWait >= 0)
                        remoteIDWaiter.wait(maxWait);
                    else
                        remoteIDWaiter.wait();
                } catch (InterruptedException ex) {
                }

                if ((maxWait >= 0) && (System.currentTimeMillis() >= dieAfter))
                    throw new InterruptedIOException("Timed out waiting for remote ID");
             
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("TIMING: RemoteID set to " 
                               + I2PSocketManager.getReadableForm(remoteID) + " for "
                               + this.hashCode());
            }
            return remoteID;
        }
    }

    /**
     * Retrieve the other side's half of the socket's unique ID, or null if it
     * isn't known yet.  This does not wait
     *
     */
    public String getRemoteID() {
        return getRemoteID(false);
    }

    /**
     * The other side has given us some data, so inject it into our socket's 
     * inputStream
     *
     * @param data the data to inject into our local inputStream
     */
    public void queueData(byte[] data) {
        in.queueData(data);
    }

    /**
     * Return the Destination of this side of the socket.
     */
    public Destination getThisDestination() {
        return local;
    }

    /**
     * Return the destination of the peer.
     */
    public Destination getPeerDestination() {
        return remote;
    }

    /**
     * Return an InputStream to read from the socket.
     */
    public InputStream getInputStream() throws IOException {
        if ((in == null)) throw new IOException("Not connected");
        return in;
    }

    /**
     * Return an OutputStream to write into the socket.
     */
    public OutputStream getOutputStream() throws IOException {
        if ((out == null)) throw new IOException("Not connected");
        return out;
    }

    /**
     * Closes the socket if not closed yet (from the Application
     * side).
     */
    public void close() throws IOException {
        synchronized (flagLock) {
            _log.debug("Closing connection");
            closed = true;
        }
        out.close();
        in.notifyClosed();
    }

    /**
     * Close the socket from the I2P side, e. g. by a close packet.
     */
    protected void internalClose() {
        synchronized (flagLock) {
            closed = true;
            closed2 = true;
            sendClose = false;
        }
        out.close();
        in.notifyClosed();
    }

    private byte getMask(int add) {
        if (outgoing)
            return (byte)(I2PSocketManager.DATA_IN + (byte)add);
        else
            return (byte)(I2PSocketManager.DATA_OUT + (byte)add);
    }

    /**
     * What is the longest we'll block on the input stream while waiting
     * for more data?  If this value is exceeded, the read() throws 
     * InterruptedIOException
     */
    public long getReadTimeout() {
        return in.getReadTimeout();
    }

    public void setReadTimeout(long ms) {
        in.setReadTimeout(ms);
    }

    //--------------------------------------------------
    private class I2PInputStream extends InputStream {

        private ByteCollector bc = new ByteCollector();

        private long readTimeout = -1;

        public long getReadTimeout() {
            return readTimeout;
        }
        
        public void setReadTimeout(long ms) {
            readTimeout = ms;
        }
        
        public int read() throws IOException {
            byte[] b = new byte[1];
            int res = read(b);
            if (res == 1) return b[0] & 0xff;
            if (res == -1) return -1;
            throw new RuntimeException("Incorrect read() result");
        }

        public synchronized int read(byte[] b, int off, int len) throws IOException {
            _log.debug("Read called: " + this.hashCode());
            if (len == 0) return 0;
            long dieAfter = System.currentTimeMillis() + readTimeout;
            byte[] read = bc.startToByteArray(len);
            boolean timedOut = false;

            while (read.length == 0) {
                synchronized (flagLock) {
                    if (closed) {
                        _log.debug("Closed is set, so closing stream: " + hashCode());
                        return -1;
                    }
                }
                try {
                    if (readTimeout >= 0) {
                        wait(readTimeout);
                    } else {
                        wait();
                    }
                } catch (InterruptedException ex) {}

                if ((readTimeout >= 0)
                    && (System.currentTimeMillis() >= dieAfter)) {
                    throw new InterruptedIOException("Timeout reading from I2PSocket (" + readTimeout + " msecs)");
                }

                read = bc.startToByteArray(len);
            }
            if (read.length > len) throw new RuntimeException("BUG");
            System.arraycopy(read, 0, b, off, read.length);

            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug("Read from I2PInputStream " + hashCode() + " returned " 
                           + read.length + " bytes");
            }
            //if (_log.shouldLog(Log.DEBUG)) {
            //  _log.debug("Read from I2PInputStream " + this.hashCode()
            //             + " returned "+read.length+" bytes:\n"
            //             + HexDump.dump(read));
            //}
            return read.length;
        }

        public int available() {
            return bc.getCurrentSize();
        }

        public void queueData(byte[] data) {
            queueData(data, 0, data.length);
        }

        public synchronized void queueData(byte[] data, int off, int len) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Insert " + len + " bytes into queue: " + hashCode());
            bc.append(data, off, len);
            notifyAll();
        }

        public synchronized void notifyClosed() {
            I2PInputStream.this.notifyAll();
        }
        
        public void close() throws IOException {
            super.close();
            notifyClosed();
        }

    }

    private class I2POutputStream extends OutputStream {

        public I2PInputStream sendTo;

        public I2POutputStream(I2PInputStream sendTo) {
            this.sendTo = sendTo;
        }

        public void write(int b) throws IOException {
            write(new byte[] { (byte) b});
        }

        public void write(byte[] b, int off, int len) throws IOException {
            sendTo.queueData(b, off, len);
        }

        public void close() {
            sendTo.notifyClosed();
        }
    }

    private static volatile long __runnerId = 0;
    private class I2PSocketRunner extends I2PThread {

        public InputStream in;

        public I2PSocketRunner(InputStream in) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Runner's input stream is: " + in.hashCode());
            this.in = in;
            String peer = I2PSocketImpl.this.remote.calculateHash().toBase64();
            setName("SocketRunner " + (++__runnerId) + " " + peer.substring(0, 4));
            start();
        }
        
        /**
         * Pump some more data
         *
         * @return true if we should keep on handling, false otherwise
         */
        private boolean handleNextPacket(ByteCollector bc, byte buffer[]) 
                                         throws IOException, I2PSessionException {
            int len = in.read(buffer);
            int bcsize = bc.getCurrentSize();
            if (len != -1) {
                bc.append(buffer, len);
            } else if (bcsize == 0) {
                // nothing left in the buffer, but the read(..) didn't EOF (-1)
                // this used to be 'break' (aka return false), though that seems
                // odd to me - shouldn't it keep reading packets until EOF?  
                // but perhaps there's something funky in the stream's operation,
                // or some other dependency within the rest of the ministreaming
                // lib, so for the moment, return false.  --jr
                // --
                // hehe, look at the else. This case is only used iff len == -1
                // and bcsize == 0 (i.e. there is an EOF)  --mihi
                return false;
            }
            if ((bcsize < MAX_PACKET_SIZE) && (in.available() == 0)) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Runner Point d: " + hashCode());

                try {
                    Thread.sleep(PACKET_DELAY);
                } catch (InterruptedException e) {
                    _log.warn("wtf", e);
                }
            }
            if ((bcsize >= MAX_PACKET_SIZE) || (in.available() == 0)) {
                byte[] data = bc.startToByteArray(MAX_PACKET_SIZE);
                if (data.length > 0) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Message size is: " + data.length);
                    boolean sent = sendBlock(data);
                    if (!sent) {
                        _log.error("Error sending message to peer.  Killing socket runner");
                        return false;
                    }
                }
            }
            return true;
        }

        public void run() {
            byte[] buffer = new byte[MAX_PACKET_SIZE];
            ByteCollector bc = new ByteCollector();
            boolean keepHandling = true;
            int packetsHandled = 0;
            try {
                //              try {
                while (keepHandling) {
                    keepHandling = handleNextPacket(bc, buffer);
                    packetsHandled++;
                }
                if ((bc.getCurrentSize() > 0) && (packetsHandled > 1)) {
                    _log.error("A SCARY MONSTER HAS EATEN SOME DATA! " + "(input stream: " 
                               + in.hashCode() + "; "
                               + "queue size: " + bc.getCurrentSize() + ")");
                }
                synchronized (flagLock) {
                    closed2 = true;
                }
                boolean sc;
                synchronized (flagLock) {
                    sc = sendClose;
                } // FIXME: Race here?
                if (sc) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Sending close packet: " + outgoing);
                    byte[] packet = I2PSocketManager.makePacket(getMask(0x02), remoteID, new byte[0]);
                    boolean sent = manager.getSession().sendMessage(remote, packet);
                    if (!sent) {
                        _log.error("Error sending close packet to peer");
                    }
                }
                manager.removeSocket(I2PSocketImpl.this);
            } catch (InterruptedIOException ex) {
                _log.error("BUG! read() operations should not timeout!", ex);
            } catch (IOException ex) {
                // WHOEVER removes this event on inconsistent
                // state before fixing the inconsistent state (a
                // reference on the socket in the socket manager
                // etc.) will get hanged by me personally -- mihi
                _log.error("Error running - **INCONSISTENT STATE!!!**", ex);
            } catch (I2PException ex) {
                _log.error("Error running - **INCONSISTENT STATE!!!**", ex);
            }
        }

        private boolean sendBlock(byte data[]) throws I2PSessionException {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("TIMING: Block to send for " + I2PSocketImpl.this.hashCode());
            if (remoteID == null) {
                _log.error("NULL REMOTEID");
                return false;
            }
            byte[] packet = I2PSocketManager.makePacket(getMask(0x00), remoteID, data);
            boolean sent;
            synchronized (flagLock) {
                if (closed2) return false;
            }
            sent = manager.getSession().sendMessage(remote, packet);
            return sent;
        }
    }
}
