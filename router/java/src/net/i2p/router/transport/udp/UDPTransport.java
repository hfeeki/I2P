package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

import java.io.IOException;
import java.io.Writer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.Transport;
import net.i2p.router.transport.TransportImpl;
import net.i2p.router.transport.TransportBid;
import net.i2p.util.Log;

/**
 *
 */
public class UDPTransport extends TransportImpl implements TimedWeightedPriorityMessageQueue.FailedListener {
    private RouterContext _context;
    private Log _log;
    private UDPEndpoint _endpoint;
    /** Peer (Hash) to PeerState */
    private Map _peersByIdent;
    /** Remote host (ip+port as a string) to PeerState */
    private Map _peersByRemoteHost;
    /** Relay tag (base64 String) to PeerState */
    private Map _peersByRelayTag;
    private PacketHandler _handler;
    private EstablishmentManager _establisher;
    private MessageQueue _outboundMessages;
    private OutboundMessageFragments _fragments;
    private OutboundRefiller _refiller;
    private PacketPusher _pusher;
    private InboundMessageFragments _inboundFragments;
    private UDPFlooder _flooder;
    
    /** list of RelayPeer objects for people who will relay to us */
    private List _relayPeers;

    /** summary info to distribute */
    private RouterAddress _externalAddress;
    /** port number on which we can be reached, or -1 */
    private int _externalListenPort;
    /** IP address of externally reachable host, or null */
    private InetAddress _externalListenHost;
    /** introduction key */
    private SessionKey _introKey;
    
    /** shared fast bid for connected peers */
    private TransportBid _fastBid;
    /** shared slow bid for unconnected peers */
    private TransportBid _slowBid;

    public static final String STYLE = "SSUv1";
    public static final String PROP_INTERNAL_PORT = "i2np.udp.internalPort";

    /** define this to explicitly set an external IP address */
    public static final String PROP_EXTERNAL_HOST = "i2np.udp.host";
    /** define this to explicitly set an external port */
    public static final String PROP_EXTERNAL_PORT = "i2np.udp.port";
    
    
    /** how many relays offered to us will we use at a time? */
    public static final int PUBLIC_RELAY_COUNT = 3;
    
    /** configure the priority queue with the given split points */
    private static final int PRIORITY_LIMITS[] = new int[] { 100, 200, 300, 400, 500, 1000 };
    /** configure the priority queue with the given weighting per priority group */
    private static final int PRIORITY_WEIGHT[] = new int[] { 1, 1, 1, 1, 1, 2 };

    /** should we flood all UDP peers with the configured rate? */
    private static final boolean SHOULD_FLOOD_PEERS = false;
    
    private static final int MAX_CONSECUTIVE_FAILED = 5;
    
    public UDPTransport(RouterContext ctx) {
        super(ctx);
        _context = ctx;
        _log = ctx.logManager().getLog(UDPTransport.class);
        _peersByIdent = new HashMap(128);
        _peersByRemoteHost = new HashMap(128);
        _peersByRelayTag = new HashMap(128);
        _endpoint = null;
        
        _outboundMessages = new TimedWeightedPriorityMessageQueue(ctx, PRIORITY_LIMITS, PRIORITY_WEIGHT, this);
        _relayPeers = new ArrayList(1);

        _fastBid = new SharedBid(50);
        _slowBid = new SharedBid(1000);
        
        _fragments = new OutboundMessageFragments(_context, this);
        _inboundFragments = new InboundMessageFragments(_context, _fragments, this);
        _flooder = new UDPFlooder(_context, this);
    }
    
    public void startup() {
        if (_fragments != null)
            _fragments.shutdown();
        if (_pusher != null)
            _pusher.shutdown();
        if (_handler != null) 
            _handler.shutdown();
        if (_endpoint != null)
            _endpoint.shutdown();
        if (_establisher != null)
            _establisher.shutdown();
        if (_refiller != null)
            _refiller.shutdown();
        if (_inboundFragments != null)
            _inboundFragments.shutdown();
        if (_flooder != null)
            _flooder.shutdown();
        
        _introKey = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
        System.arraycopy(_context.routerHash().getData(), 0, _introKey.getData(), 0, SessionKey.KEYSIZE_BYTES);
        
        rebuildExternalAddress();
        
        if (_endpoint == null) {
            int port = -1;
            if (_externalListenPort <= 0) {
                // no explicit external port, so lets try an internal one
                String portStr = _context.getProperty(PROP_INTERNAL_PORT);
                if (portStr != null) {
                    try {
                        port = Integer.parseInt(portStr);
                    } catch (NumberFormatException nfe) {
                        if (_log.shouldLog(Log.ERROR))
                            _log.error("Invalid port specified [" + portStr + "]");
                    }
                }
                if (port <= 0) {
                    port = 1024 + _context.random().nextInt(31*1024);
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Selecting a random port to bind to: " + port);
                }
            } else {
                port = _externalListenPort;
                if (_log.shouldLog(Log.INFO))
                    _log.info("Binding to the explicitly specified external port: " + port);
            }
            try {
                _endpoint = new UDPEndpoint(_context, port);
            } catch (SocketException se) {
                if (_log.shouldLog(Log.CRIT))
                    _log.log(Log.CRIT, "Unable to listen on the UDP port (" + port + ")", se);
                return;
            }
        }
        
        if (_establisher == null)
            _establisher = new EstablishmentManager(_context, this);
        
        if (_handler == null)
            _handler = new PacketHandler(_context, this, _endpoint, _establisher, _inboundFragments);
        
        if (_refiller == null)
            _refiller = new OutboundRefiller(_context, _fragments, _outboundMessages);
        
        if (_flooder == null)
            _flooder = new UDPFlooder(_context, this);
        
        _endpoint.startup();
        _establisher.startup();
        _handler.startup();
        _fragments.startup();
        _inboundFragments.startup();
        _pusher = new PacketPusher(_context, _fragments, _endpoint.getSender());
        _pusher.startup();
        _refiller.startup();
        _flooder.startup();
    }
    
    public void shutdown() {
        if (_flooder != null)
            _flooder.shutdown();
        if (_refiller != null)
            _refiller.shutdown();
        if (_handler != null)
            _handler.shutdown();
        if (_endpoint != null)
            _endpoint.shutdown();
        if (_fragments != null)
            _fragments.shutdown();
        if (_pusher != null)
            _pusher.shutdown();
        if (_establisher != null)
            _establisher.shutdown();
        if (_inboundFragments != null)
            _inboundFragments.shutdown();
    }
    
    /**
     * Introduction key that people should use to contact us
     *
     */
    public SessionKey getIntroKey() { return _introKey; }
    public int getLocalPort() { return _externalListenPort; }
    public InetAddress getLocalAddress() { return _externalListenHost; }
    public int getExternalPort() { return _externalListenPort; }
    
    /**
     * Someone we tried to contact gave us what they think our IP address is.
     * Right now, we just blindly trust them, changing our IP and port on a
     * whim.  this is not good ;)
     *
     */
    void externalAddressReceived(byte ourIP[], int ourPort) {
        if (_log.shouldLog(Log.WARN))
            _log.debug("External address received: " + Base64.encode(ourIP) + ":" + ourPort);
        
        if (explicitAddressSpecified()) 
            return;
            
        synchronized (this) {
            if ( (_externalListenHost == null) ||
                 (!eq(_externalListenHost.getAddress(), _externalListenPort, ourIP, ourPort)) ) {
                try {
                    _externalListenHost = InetAddress.getByAddress(ourIP);
                    _externalListenPort = ourPort;
                    rebuildExternalAddress();
                    replaceAddress(_externalAddress);
                } catch (UnknownHostException uhe) {
                    _externalListenHost = null;
                }
            }
        }
    }
    
    private static final boolean eq(byte laddr[], int lport, byte raddr[], int rport) {
        return (rport == lport) && DataHelper.eq(laddr, raddr);
    }
    
    /** 
     * get the state for the peer at the given remote host/port, or null 
     * if no state exists
     */
    public PeerState getPeerState(InetAddress remoteHost, int remotePort) {
        String hostInfo = PeerState.calculateRemoteHostString(remoteHost.getAddress(), remotePort);
        synchronized (_peersByRemoteHost) {
            return (PeerState)_peersByRemoteHost.get(hostInfo);
        }
    }
    
    /** 
     * get the state for the peer with the given ident, or null 
     * if no state exists
     */
    public PeerState getPeerState(Hash remotePeer) { 
        synchronized (_peersByIdent) {
            return (PeerState)_peersByIdent.get(remotePeer);
        }
    }
    
    /**
     * get the state for the peer being introduced, or null if we aren't
     * offering to introduce anyone with that tag.
     */
    public PeerState getPeerState(String relayTag) {
        synchronized (_peersByRelayTag) {
            return (PeerState)_peersByRelayTag.get(relayTag);
        }
    }
    
    /** 
     * add the peer info, returning true if it went in properly, false if
     * it was rejected (causes include peer ident already connected, or no
     * remote host info known
     *
     */
    boolean addRemotePeerState(PeerState peer) {
        if (_log.shouldLog(Log.WARN))
            _log.debug("Add remote peer state: " + peer);
        if (peer.getRemotePeer() != null) {
            synchronized (_peersByIdent) {
                PeerState oldPeer = (PeerState)_peersByIdent.put(peer.getRemotePeer(), peer);
                if ( (oldPeer != null) && (oldPeer != peer) ) {
                    // should we transfer the oldPeer's RTT/RTO/etc? nah
                    // or perhaps reject the new session?  nah, 
                    // using the new one allow easier reconnect
                }
            }
        }
        
        String remoteString = peer.getRemoteHostString();
        if (remoteString == null) return false;
        
        synchronized (_peersByRemoteHost) {
            PeerState oldPeer = (PeerState)_peersByRemoteHost.put(remoteString, peer);
            if ( (oldPeer != null) && (oldPeer != peer) ) {
                //_peersByRemoteHost.put(remoteString, oldPeer);
                //return false;
            }
        }
        
        _context.shitlist().unshitlistRouter(peer.getRemotePeer());

        if (SHOULD_FLOOD_PEERS)
            _flooder.addPeer(peer);
        
        return true;
    }
    
    private void dropPeer(PeerState peer) {
        if (_log.shouldLog(Log.WARN))
            _log.debug("Dropping remote peer: " + peer);
        if (peer.getRemotePeer() != null) {
            _context.shitlist().shitlistRouter(peer.getRemotePeer(), "dropped after too many retries");
            synchronized (_peersByIdent) {
                _peersByIdent.remove(peer.getRemotePeer());
            }
        }
        
        String remoteString = peer.getRemoteHostString();
        if (remoteString != null) {
            synchronized (_peersByRemoteHost) {
                _peersByRemoteHost.remove(remoteString);
            }
        }
        
        if (SHOULD_FLOOD_PEERS)
            _flooder.removePeer(peer);
    }
    
    int send(UDPPacket packet) { 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending packet " + packet);
        return _endpoint.send(packet); 
    }
    
    public TransportBid bid(RouterInfo toAddress, long dataSize) {
        Hash to = toAddress.getIdentity().calculateHash();
        PeerState peer = getPeerState(to);
        if (peer != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("bidding on a message to an established peer: " + peer);
            return _fastBid;
        } else {
            if (null == toAddress.getTargetAddress(STYLE))
                return null;

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("bidding on a message to an unestablished peer: " + to.toBase64());
            return _slowBid;
        }
    }
    
    public String getStyle() { return STYLE; }
    public void send(OutNetMessage msg) { 
        if (msg == null) return;
        if (msg.getTarget() == null) return;
        if (msg.getTarget().getIdentity() == null) return;
        
        Hash to = msg.getTarget().getIdentity().calculateHash();
        if (getPeerState(to) != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sending outbound message to an established peer: " + to.toBase64());
            _outboundMessages.add(msg);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sending outbound message to an unestablished peer: " + to.toBase64());
            _establisher.establish(msg);
        }
    }
    void send(I2NPMessage msg, PeerState peer) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Injecting a data message to a new peer: " + peer);
        OutboundMessageState state = new OutboundMessageState(_context);
        state.initialize(msg, peer);
        _fragments.add(state);
    }

    public OutNetMessage getNextMessage() { return getNextMessage(-1); }
    /**
     * Get the next message, blocking until one is found or the expiration
     * reached.
     *
     * @param blockUntil expiration, or -1 if indefinite
     */
    public OutNetMessage getNextMessage(long blockUntil) {
        return _outboundMessages.getNext(blockUntil);
    }

    
    // we don't need the following, since we have our own queueing
    protected void outboundMessageReady() { throw new UnsupportedOperationException("Not used for UDP"); }
    
    public RouterAddress startListening() {
        startup();
        return _externalAddress;
    }
    
    public void stopListening() {
        shutdown();
    }
    
    void setExternalListenPort(int port) { _externalListenPort = port; }
    void setExternalListenHost(InetAddress addr) { _externalListenHost = addr; }
    void setExternalListenHost(byte addr[]) throws UnknownHostException { 
        _externalListenHost = InetAddress.getByAddress(addr); 
    }
    void addRelayPeer(String host, int port, byte tag[], SessionKey relayIntroKey) {
        if ( (_externalListenPort > 0) && (_externalListenHost != null) ) 
            return; // no need for relay peers, as we are reachable
        
        RelayPeer peer = new RelayPeer(host, port, tag, relayIntroKey);
        synchronized (_relayPeers) {
            _relayPeers.add(peer);
        }
    }

    private boolean explicitAddressSpecified() {
        return (_context.getProperty(PROP_EXTERNAL_HOST) != null);
    }
    
    void rebuildExternalAddress() {
        if (explicitAddressSpecified()) {
            try {
                String host = _context.getProperty(PROP_EXTERNAL_HOST);
                String port = _context.getProperty(PROP_EXTERNAL_PORT);
                _externalListenHost = InetAddress.getByName(host);
                _externalListenPort = Integer.parseInt(port);
            } catch (UnknownHostException uhe) {
                _externalListenHost = null;
            } catch (NumberFormatException nfe) {
                _externalListenPort = -1;
            }
        }
            
        Properties options = new Properties();
        if ( (_externalListenPort > 0) && (_externalListenHost != null) ) {
            options.setProperty(UDPAddress.PROP_PORT, String.valueOf(_externalListenPort));
            options.setProperty(UDPAddress.PROP_HOST, _externalListenHost.getHostAddress());
        } else {
            // grab 3 relays randomly
            synchronized (_relayPeers) {
                Collections.shuffle(_relayPeers);
                int numPeers = PUBLIC_RELAY_COUNT;
                if (numPeers > _relayPeers.size())
                    numPeers = _relayPeers.size();
                for (int i = 0; i < numPeers; i++) {
                    RelayPeer peer = (RelayPeer)_relayPeers.get(i);
                    options.setProperty("relay." + i + ".host", peer.getHost());
                    options.setProperty("relay." + i + ".port", String.valueOf(peer.getPort()));
                    options.setProperty("relay." + i + ".tag", Base64.encode(peer.getTag()));
                    options.setProperty("relay." + i + ".key", peer.getIntroKey().toBase64());
                }
            }
            if (options.size() <= 0)
                return;
        }
        options.setProperty(UDPAddress.PROP_INTRO_KEY, _introKey.toBase64());
        
        RouterAddress addr = new RouterAddress();
        addr.setCost(5);
        addr.setExpiration(null);
        addr.setTransportStyle(STYLE);
        addr.setOptions(options);
        
        _externalAddress = addr;
        replaceAddress(addr);
    }
    
    public void failed(OutboundMessageState msg) {
        if (msg == null) return;
        int consecutive = 0;
        if ( (msg.getPeer() != null) && 
             ( (msg.getMaxSends() >= OutboundMessageFragments.MAX_VOLLEYS) ||
               (msg.isExpired())) ) {
            consecutive = msg.getPeer().incrementConsecutiveFailedSends();
            if (_log.shouldLog(Log.WARN))
                _log.warn("Consecutive failure #" + consecutive + " sending to " + msg.getPeer());
            if (consecutive > MAX_CONSECUTIVE_FAILED)
                dropPeer(msg.getPeer());
        }
        failed(msg.getMessage());
    }
    
    public void failed(OutNetMessage msg) {
        if (msg == null) return;
        if (_log.shouldLog(Log.WARN))
            _log.warn("Sending message failed: " + msg, new Exception("failed from"));
        super.afterSend(msg, false);
    }
    public void succeeded(OutNetMessage msg) {
        if (msg == null) return;
        if (_log.shouldLog(Log.INFO))
            _log.info("Sending message succeeded: " + msg);
        super.afterSend(msg, true);
    }

    public int countActivePeers() {
        long now = _context.clock().now();
        int active = 0;
        int inactive = 0;
        synchronized (_peersByIdent) {
            for (Iterator iter = _peersByIdent.values().iterator(); iter.hasNext(); ) {
                PeerState peer = (PeerState)iter.next();
                if (now-peer.getLastReceiveTime() > 5*60*1000)
                    inactive++;
                else
                    active++;
            }
        }
        return active;
    }
    
    public void renderStatusHTML(Writer out) throws IOException {
        List peers = null;
        synchronized (_peersByIdent) {
            peers = new ArrayList(_peersByIdent.values());
        }
        
        StringBuffer buf = new StringBuffer(512);
        buf.append("<b>UDP connections: ").append(peers.size()).append("</b><br />\n");
        buf.append("<table border=\"1\">\n");
        buf.append(" <tr><td><b>peer</b></td><td><b>activity (in/out)</b></td>\n");
        buf.append("     <td><b>uptime</b></td><td><b>skew</b></td>\n");
        buf.append("     <td><b>cwnd</b></td><td><b>ssthresh</b></td>\n");
        buf.append("     <td><b>rtt</b></td><td><b>dev</b></td><td><b>rto</b></td>\n");
        buf.append("     <td><b>send</b></td><td><b>recv</b></td>\n");
        buf.append(" </tr>\n");
        out.write(buf.toString());
        buf.setLength(0);
        long now = _context.clock().now();
        for (int i = 0; i < peers.size(); i++) {
            PeerState peer = (PeerState)peers.get(i);
            if (now-peer.getLastReceiveTime() > 60*60*1000)
                continue; // don't include old peers
            
            buf.append("<tr>");
            
            buf.append("<td nowrap>");
            buf.append("<a href=\"#");
            buf.append(peer.getRemotePeer().toBase64().substring(0,6));
            buf.append("\">");
            byte ip[] = peer.getRemoteIP();
            for (int j = 0; j < ip.length; j++) {
                if (ip[j] < 0)
                    buf.append(ip[j] + 255);
                else
                    buf.append(ip[j]);
                if (j + 1 < ip.length)
                    buf.append('.');
            }
            buf.append(':').append(peer.getRemotePort());
            buf.append("</a>");
            if (peer.getConsecutiveFailedSends() > 0)
                buf.append(" [").append(peer.getConsecutiveFailedSends()).append(" failures]");
            buf.append("</td>");
            
            buf.append("<td>");
            buf.append(DataHelper.formatDuration(now-peer.getLastReceiveTime()));
            buf.append("/");
            buf.append(DataHelper.formatDuration(now-peer.getLastSendTime()));
            buf.append("</td>");

            buf.append("<td>");
            buf.append(DataHelper.formatDuration(now-peer.getKeyEstablishedTime()));
            buf.append("</td>");
            
            buf.append("<td>");
            buf.append(peer.getClockSkew()/1000);
            buf.append("s</td>");

            buf.append("<td>");
            buf.append(peer.getSendWindowBytes()/1024);
            buf.append("K</td>");

            buf.append("<td>");
            buf.append(peer.getSlowStartThreshold()/1024);
            buf.append("K</td>");

            buf.append("<td>");
            buf.append(peer.getRTT());
            buf.append("</td>");
            
            buf.append("<td>");
            buf.append(peer.getRTTDeviation());
            buf.append("</td>");

            buf.append("<td>");
            buf.append(peer.getRTO());
            buf.append("</td>");
            
            buf.append("<td>");
            buf.append(peer.getMessagesSent());
            buf.append("</td>");
            
            buf.append("<td>");
            buf.append(peer.getMessagesReceived());
            buf.append("</td>");

            buf.append("</tr>");
            out.write(buf.toString());
            buf.setLength(0);
        }
        
        out.write("</table>\n");
    }

    /**
     * Cache the bid to reduce object churn
     */
    private class SharedBid extends TransportBid {
        private int _ms;
        public SharedBid(int ms) { _ms = ms; }
        public int getLatency() { return _ms; }
        public Transport getTransport() { return UDPTransport.this; }
        public String toString() { return "UDP bid @ " + _ms; }
    }
}
