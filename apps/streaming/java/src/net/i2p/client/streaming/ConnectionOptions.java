package net.i2p.client.streaming;

import java.util.Properties;

/**
 * Define the current options for the con (and allow custom tweaking midstream)
 *
 */
public class ConnectionOptions extends I2PSocketOptions {
    private int _connectDelay;
    private boolean _fullySigned;
    private int _windowSize;
    private int _receiveWindow;
    private int _profile;
    private int _rtt;
    private int _resendDelay;
    private int _sendAckDelay;
    private int _maxMessageSize;
    private int _choke;
    private int _maxResends;
    private int _inactivityTimeout;
    private int _inactivityAction;
    private int _inboundBufferSize;

    public static final int PROFILE_BULK = 1;
    public static final int PROFILE_INTERACTIVE = 2;
    
    /** on inactivity timeout, do nothing */
    public static final int INACTIVITY_ACTION_NOOP = 0;
    /** on inactivity timeout, close the connection */
    public static final int INACTIVITY_ACTION_DISCONNECT = 1;
    /** on inactivity timeout, send a payload message */
    public static final int INACTIVITY_ACTION_SEND = 2;
    
    public ConnectionOptions() {
        super();
        init(null);
    }
    
    public ConnectionOptions(I2PSocketOptions opts) {
        super(opts);
        init(null);
    }
    
    public ConnectionOptions(ConnectionOptions opts) {
        super(opts);
        init(opts);
    }
    
    private void init(ConnectionOptions opts) {
        if (opts != null) {
            setConnectDelay(opts.getConnectDelay());
            setProfile(opts.getProfile());
            setRTT(opts.getRTT());
            setRequireFullySigned(opts.getRequireFullySigned());
            setWindowSize(opts.getWindowSize());
            setResendDelay(opts.getResendDelay());
            setMaxMessageSize(opts.getMaxMessageSize());
            setChoke(opts.getChoke());
            setMaxResends(opts.getMaxResends());
            setInactivityTimeout(opts.getInactivityTimeout());
            setInactivityAction(opts.getInactivityAction());
            setInboundBufferSize(opts.getInboundBufferSize());
        } else {
            setConnectDelay(2*1000);
            setProfile(PROFILE_BULK);
            setMaxMessageSize(Packet.MAX_PAYLOAD_SIZE);
            setRTT(30*1000);
            setReceiveWindow(1);
            setResendDelay(5*1000);
            setSendAckDelay(2*1000);
            setWindowSize(1);
            setMaxResends(5);
            setWriteTimeout(-1);
            setInactivityTimeout(5*60*1000);
            setInactivityAction(INACTIVITY_ACTION_SEND);
            setInboundBufferSize((Packet.MAX_PAYLOAD_SIZE + 2) * Connection.MAX_WINDOW_SIZE);
        }
    }
    
    public ConnectionOptions(Properties opts) {
        super(opts);
        // load the options;
    }
    
    /** 
     * how long will we wait after instantiating a new con 
     * before actually attempting to connect.  If this is
     * set to 0, connect ASAP.  If it is greater than 0, wait
     * until the output stream is flushed, the buffer fills, 
     * or that many milliseconds pass.
     *
     */
    public int getConnectDelay() { return _connectDelay; }
    public void setConnectDelay(int delayMs) { _connectDelay = delayMs; }
    
    /**
     * Do we want all packets in both directions to be signed,
     * or can we deal with signatures on the SYN and FIN packets
     * only?
     *
     */
    public boolean getRequireFullySigned() { return _fullySigned; }
    public void setRequireFullySigned(boolean sign) { _fullySigned = sign; }
    
    /** 
     * How many messages will we send before waiting for an ACK?
     *
     */
    public int getWindowSize() { return _windowSize; }
    public void setWindowSize(int numMsgs) { 
        if (numMsgs > Connection.MAX_WINDOW_SIZE)
            numMsgs = Connection.MAX_WINDOW_SIZE;
        _windowSize = numMsgs; 
    }
    
    /** after how many consecutive messages should we ack? */
    public int getReceiveWindow() { return _receiveWindow; } 
    public void setReceiveWindow(int numMsgs) { _receiveWindow = numMsgs; }
    
    /**
     * What to set the round trip time estimate to (in milliseconds)
     */
    public int getRTT() { return _rtt; }
    public void setRTT(int ms) { 
        _rtt = ms; 
        if (_rtt > 60*1000)
            _rtt = 60*1000;
    }
    
    /** rtt = rtt*RTT_DAMPENING + (1-RTT_DAMPENING)*currentPacketRTT */
    private static final double RTT_DAMPENING = 0.9;
    
    public void updateRTT(int measuredValue) {
        setRTT((int)(RTT_DAMPENING*_rtt + (1-RTT_DAMPENING)*measuredValue));
    }
    
    /** How long after sending a packet will we wait before resending? */
    public int getResendDelay() { return _resendDelay; }
    public void setResendDelay(int ms) { _resendDelay = ms; }
    
    /** 
     * if there are packets we haven't ACKed yet and we don't 
     * receive _receiveWindow messages before 
     * (_lastSendTime+_sendAckDelay), send an ACK of what
     * we have received so far.
     *
     */
    public int getSendAckDelay() { return _sendAckDelay; }
    public void setSendAckDelay(int delayMs) { _sendAckDelay = delayMs; }
    
    /** What is the largest message we want to send or receive? */
    public int getMaxMessageSize() { return _maxMessageSize; }
    public void setMaxMessageSize(int bytes) { _maxMessageSize = bytes; }
    
    /** 
     * how long we want to wait before any data is transferred on the
     * connection in either direction
     *
     */
    public int getChoke() { return _choke; }
    public void setChoke(int ms) { _choke = ms; }

    /**
     * What profile do we want to use for this connection?
     *
     */
    public int getProfile() { return _profile; }
    public void setProfile(int profile) { 
        if (profile != PROFILE_BULK) 
            throw new IllegalArgumentException("Only bulk is supported so far");
        _profile = profile; 
    }
    
    /**
     * How many times will we try to send a message before giving up?
     *
     */
    public int getMaxResends() { return _maxResends; }
    public void setMaxResends(int numSends) { _maxResends = numSends; }
    
    /**
     * What period of inactivity qualifies as "too long"?
     *
     */
    public int getInactivityTimeout() { return _inactivityTimeout; }
    public void setInactivityTimeout(int timeout) { _inactivityTimeout = timeout; }
    
    public int getInactivityAction() { return _inactivityAction; }
    public void setInactivityAction(int action) { _inactivityAction = action; }
    
    /** 
     * how much data are we willing to accept in our buffer?
     *
     */
    public int getInboundBufferSize() { return _inboundBufferSize; }
    public void setInboundBufferSize(int bytes) { _inboundBufferSize = bytes; }
}
