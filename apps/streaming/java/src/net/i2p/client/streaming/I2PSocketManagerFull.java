package net.i2p.client.streaming;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionListener;
import net.i2p.data.Destination;
import net.i2p.util.Log;


/**
 * Centralize the coordination and multiplexing of the local client's streaming.
 * There should be one I2PSocketManager for each I2PSession, and if an application
 * is sending and receiving data through the streaming library using an
 * I2PSocketManager, it should not attempt to call I2PSession's setSessionListener
 * or receive any messages with its .receiveMessage
 *
 */
public class I2PSocketManagerFull implements I2PSocketManager {
    private I2PAppContext _context;
    private Log _log;
    private I2PSession _session;
    private I2PServerSocketFull _serverSocket;
    private ConnectionOptions _defaultOptions;
    private long _acceptTimeout;
    private String _name;
    private int _maxStreams;
    private static int __managerId = 0;
    private ConnectionManager _connectionManager;
    
    /**
     * How long to wait for the client app to accept() before sending back CLOSE?
     * This includes the time waiting in the queue.  Currently set to 5 seconds.
     */
    private static final long ACCEPT_TIMEOUT_DEFAULT = 5*1000;
    
    public I2PSocketManagerFull() {
        _context = null;
        _session = null;
    }
    public I2PSocketManagerFull(I2PAppContext context, I2PSession session, Properties opts, String name) {
        this();
        init(context, session, opts, name);
    }
    
    /** how many streams will we allow at once?  */
    public static final String PROP_MAX_STREAMS = "i2p.streaming.maxConcurrentStreams";
    
    /**
     *
     */
    public void init(I2PAppContext context, I2PSession session, Properties opts, String name) {
        _context = context;
        _session = session;
        _log = _context.logManager().getLog(I2PSocketManagerFull.class);
        
        _maxStreams = -1;
        try {
            String num = (opts != null ? opts.getProperty(PROP_MAX_STREAMS, "-1") : "-1");
            _maxStreams = Integer.parseInt(num);
        } catch (NumberFormatException nfe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Invalid max # of concurrent streams, defaulting to unlimited", nfe);
            _maxStreams = -1;
        }
        _connectionManager = new ConnectionManager(_context, _session, _maxStreams);
        _name = name + " " + (++__managerId);
        _acceptTimeout = ACCEPT_TIMEOUT_DEFAULT;
        _defaultOptions = new ConnectionOptions(opts);
        _serverSocket = new I2PServerSocketFull(this);
        
        if (_log.shouldLog(Log.INFO)) {
            _log.info("Socket manager created.  \ndefault options: " + _defaultOptions
                      + "\noriginal properties: " + opts);
        }
    }

    public I2PSocketOptions buildOptions() { return buildOptions(null); }
    public I2PSocketOptions buildOptions(Properties opts) {
        return new ConnectionOptions(opts);
    }
    
    public I2PSession getSession() {
        return _session;
    }
    
    public ConnectionManager getConnectionManager() {
        return _connectionManager;
    }

    public I2PSocket receiveSocket() throws I2PException {
        if (_session.isClosed()) throw new I2PException("Session closed");
        Connection con = _connectionManager.getConnectionHandler().accept(-1);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("receiveSocket() called: " + con);
        if (con != null) {
            I2PSocketFull sock = new I2PSocketFull(con);
            con.setSocket(sock);
            return sock;
        } else { 
            return null;
        }
    }
    
    /**
     * Ping the specified peer, returning true if they replied to the ping within 
     * the timeout specified, false otherwise.  This call blocks.
     *
     */
    public boolean ping(Destination peer, long timeoutMs) {
        return _connectionManager.ping(peer, timeoutMs);
    }

    /**
     * How long should we wait for the client to .accept() a socket before
     * sending back a NACK/Close?  
     *
     * @param ms milliseconds to wait, maximum
     */
    public void setAcceptTimeout(long ms) { _acceptTimeout = ms; }
    public long getAcceptTimeout() { return _acceptTimeout; }

    public void setDefaultOptions(I2PSocketOptions options) {
        _defaultOptions = new ConnectionOptions(options);
    }

    public I2PSocketOptions getDefaultOptions() {
        return _defaultOptions;
    }

    public I2PServerSocket getServerSocket() {
        _connectionManager.setAllowIncomingConnections(true);
        return _serverSocket;
    }

    /**
     * Create a new connected socket (block until the socket is created)
     *
     * @param peer Destination to connect to
     * @param options I2P socket options to be used for connecting
     *
     * @throws NoRouteToHostException if the peer is not found or not reachable
     * @throws I2PException if there is some other I2P-related problem
     */
    public I2PSocket connect(Destination peer, I2PSocketOptions options) 
                             throws I2PException, NoRouteToHostException {
        if (_connectionManager.getSession().isClosed()) 
            throw new I2PException("Session is closed");
        if (options == null)
            options = _defaultOptions;
        ConnectionOptions opts = null;
        if (options instanceof ConnectionOptions)
            opts = (ConnectionOptions)options;
        else
            opts = new ConnectionOptions(options);
        Connection con = _connectionManager.connect(peer, opts);
        if (con == null)
            throw new TooManyStreamsException("Too many streams (max " + _maxStreams + ")");
        I2PSocketFull socket = new I2PSocketFull(con);
        con.setSocket(socket);
        if (con.getConnectionError() != null) { 
            con.disconnect(false);
            throw new NoRouteToHostException(con.getConnectionError());
        }
        return socket;
    }

    /**
     * Create a new connected socket (block until the socket is created)
     *
     * @param peer Destination to connect to
     *
     * @throws NoRouteToHostException if the peer is not found or not reachable
     * @throws I2PException if there is some other I2P-related problem
     */
    public I2PSocket connect(Destination peer) throws I2PException, NoRouteToHostException {
        return connect(peer, _defaultOptions);
    }

    /**
     * Destroy the socket manager, freeing all the associated resources.  This
     * method will block untill all the managed sockets are closed.
     *
     */
    public void destroySocketManager() {
        _connectionManager.disconnectAllHard();
        _connectionManager.setAllowIncomingConnections(false);
        // should we destroy the _session too?
        // yes, since the old lib did (and SAM wants it to, and i dont know why not)
        if ( (_session != null) && (!_session.isClosed()) ) {
            try {
                _session.destroySession();
            } catch (I2PSessionException ise) {
                _log.warn("Unable to destroy the session", ise);
            }
        }
    }

    /**
     * Retrieve a set of currently connected I2PSockets, either initiated locally or remotely.
     *
     */
    public Set listSockets() {
        Set connections = _connectionManager.listConnections();
        Set rv = new HashSet(connections.size());
        for (Iterator iter = connections.iterator(); iter.hasNext(); ) {
            Connection con = (Connection)iter.next();
            if (con.getSocket() != null)
                rv.add(con.getSocket());
        }
        return rv;
    }

    public String getName() { return _name; }
    public void setName(String name) { _name = name; }
}
