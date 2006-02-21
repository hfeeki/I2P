package net.i2p.router.transport.udp;

import java.util.Arrays;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.I2PAppContext;
import net.i2p.router.OutNetMessage;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/**
 * Maintain the outbound fragmentation for resending
 *
 */
public class OutboundMessageState {
    private I2PAppContext _context;
    private Log _log;
    /** may be null if we are part of the establishment */
    private OutNetMessage _message;
    private long _messageId;
    /** will be null, unless we are part of the establishment */
    private PeerState _peer;
    private long _expiration;
    private ByteArray _messageBuf;
    /** fixed fragment size across the message */
    private int _fragmentSize;
    /** sends[i] is how many times the fragment has been sent, or -1 if ACKed */
    private short _fragmentSends[];
    private long _startedOn;
    private long _nextSendTime;
    private int _pushCount;
    private short _maxSends;
    private int _nextSendFragment;
    
    public static final int MAX_FRAGMENTS = 32;
    private static final ByteCache _cache = ByteCache.getInstance(64, MAX_FRAGMENTS*1024);
    
    public OutboundMessageState(I2PAppContext context) {
        _context = context;
        _log = _context.logManager().getLog(OutboundMessageState.class);
        _pushCount = 0;
        _maxSends = 0;
        _nextSendFragment = 0;
    }
    
    public boolean initialize(OutNetMessage msg) {
        if (msg == null) return false;
        try {
            initialize(msg, msg.getMessage(), null);
            return true;
        } catch (OutOfMemoryError oom) {
            throw oom;
        } catch (Exception e) {
            _log.log(Log.CRIT, "Error initializing " + msg, e);
            return false;
        }
    }
    
    public boolean initialize(I2NPMessage msg, PeerState peer) {
        if (msg == null) 
            return false;
        
        try {
            initialize(null, msg, peer);
            return true;
        } catch (OutOfMemoryError oom) {
            throw oom;
        } catch (Exception e) {
            _log.log(Log.CRIT, "Error initializing " + msg, e);
            return false;
        }
    }
    
    public boolean initialize(OutNetMessage m, I2NPMessage msg) {
        if ( (m == null) || (msg == null) ) 
            return false;
        
        try {
            initialize(m, msg, null);
            return true;
        } catch (OutOfMemoryError oom) {
            throw oom;
        } catch (Exception e) {
            _log.log(Log.CRIT, "Error initializing " + msg, e);
            return false;
        }
    }
    
    private void initialize(OutNetMessage m, I2NPMessage msg, PeerState peer) {
        _message = m;
        _peer = peer;
        if (_messageBuf != null) {
            _cache.release(_messageBuf);
            _messageBuf = null;
        }

        _messageBuf = _cache.acquire();
        int size = msg.getRawMessageSize();
        if (size > _messageBuf.getData().length)
            throw new IllegalArgumentException("Size too large!  " + size + " in " + msg);
        int len = msg.toRawByteArray(_messageBuf.getData());
        _messageBuf.setValid(len);
        _messageId = msg.getUniqueId();
        
        _startedOn = _context.clock().now();
        _nextSendTime = _startedOn;
        _expiration = _startedOn + 10*1000;
        //_expiration = msg.getExpiration();
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Raw byte array for " + _messageId + ": " + Base64.encode(_messageBuf.getData(), 0, len));
    }
    
    public void releaseResources() { 
        if (_messageBuf != null)
            _cache.release(_messageBuf);
        //_messageBuf = null;
    }
    
    public OutNetMessage getMessage() { return _message; }
    public long getMessageId() { return _messageId; }
    public PeerState getPeer() { return _peer; }
    public void setPeer(PeerState peer) { _peer = peer; }
    public boolean isExpired() {
        return _expiration < _context.clock().now(); 
    }
    public boolean isComplete() {
        short sends[] = _fragmentSends;
        if (sends == null) return false;
        for (int i = 0; i < sends.length; i++)
            if (sends[i] >= 0)
                return false;
        // nothing else pending ack
        return true;
    }
    public int getUnackedSize() {
        short fragmentSends[] = _fragmentSends;
        ByteArray messageBuf = _messageBuf;
        int rv = 0;
        if ( (messageBuf != null) && (fragmentSends != null) ) {
            int totalSize = messageBuf.getValid();
            int lastSize = totalSize % _fragmentSize;
            if (lastSize == 0)
                lastSize = _fragmentSize;
            for (int i = 0; i < fragmentSends.length; i++) {
                if (fragmentSends[i] >= (short)0) {
                    if (i + 1 == fragmentSends.length)
                        rv += lastSize;
                    else
                        rv += _fragmentSize;
                }
            }
        }
        return rv;
    }
    public boolean needsSending(int fragment) {
        
        short sends[] = _fragmentSends;
        if ( (sends == null) || (fragment >= sends.length) || (fragment < 0) )
            return false;
        return (sends[fragment] >= (short)0);
    }
    public long getLifetime() { return _context.clock().now() - _startedOn; }
    
    /**
     * Ack all the fragments in the ack list.  As a side effect, if there are
     * still unacked fragments, the 'next send' time will be updated under the
     * assumption that that all of the packets within a volley would reach the
     * peer within that ack frequency (2-400ms).
     *
     * @return true if the message was completely ACKed
     */
    public boolean acked(ACKBitfield bitfield) {
        // stupid brute force, but the cardinality should be trivial
        short sends[] = _fragmentSends;
        if (sends != null)
            for (int i = 0; i < bitfield.fragmentCount() && i < sends.length; i++)
                if (bitfield.received(i))
                    sends[i] = (short)-1;
        
        boolean rv = isComplete();
        if (!rv && false) { // don't do the fast retransmit... lets give it time to get ACKed
            long nextTime = _context.clock().now() + Math.max(_peer.getRTT(), ACKSender.ACK_FREQUENCY);
            //_nextSendTime = Math.max(now, _startedOn+PeerState.MIN_RTO);
            if (_nextSendTime <= 0)
                _nextSendTime = nextTime;
            else
                _nextSendTime = Math.min(_nextSendTime, nextTime);
            
            //if (now + 100 > _nextSendTime)
            //    _nextSendTime = now + 100;
            //_nextSendTime = now;
        }
        return rv;
    }
    
    public long getNextSendTime() { return _nextSendTime; }
    public void setNextSendTime(long when) { _nextSendTime = when; }
    public int getMaxSends() { return _maxSends; }
    public int getPushCount() { return _pushCount; }
    /** note that we have pushed the message fragments */
    public void push() { 
        _pushCount++; 
        if (_pushCount > _maxSends)
            _maxSends = (short)_pushCount;
        if (_fragmentSends != null)
            for (int i = 0; i < _fragmentSends.length; i++)
                if (_fragmentSends[i] >= (short)0)
                    _fragmentSends[i] = (short)(1 + _fragmentSends[i]);
        
    }
    public boolean isFragmented() { return _fragmentSends != null; }
    /**
     * Prepare the message for fragmented delivery, using no more than
     * fragmentSize bytes per fragment.
     *
     */
    public void fragment(int fragmentSize) {
        int totalSize = _messageBuf.getValid();
        int numFragments = totalSize / fragmentSize;
        if (numFragments * fragmentSize < totalSize)
            numFragments++;
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Fragmenting a " + totalSize + " message into " + numFragments + " fragments");
        
        //_fragmentEnd = new int[numFragments];
        _fragmentSends = new short[numFragments];
        //Arrays.fill(_fragmentEnd, -1);
        Arrays.fill(_fragmentSends, (short)0);
        
        _fragmentSize = fragmentSize;
    }
    /** how many fragments in the message */
    public int getFragmentCount() { 
        if (_fragmentSends == null) 
            return -1;
        else
            return _fragmentSends.length; 
    }
    public int getFragmentSize() { return _fragmentSize; }
    /** should we continue sending this fragment? */
    public boolean shouldSend(int fragmentNum) { return _fragmentSends[fragmentNum] >= (short)0; }
    public int fragmentSize(int fragmentNum) {
        if (_messageBuf == null) return -1;
        if (fragmentNum + 1 == _fragmentSends.length) {
            int valid = _messageBuf.getValid();
            if (valid <= _fragmentSize)
                return valid;
            else
                return valid % _fragmentSize;
        } else {
            return _fragmentSize;
        }
    }

    /**
     * Write a part of the the message onto the specified buffer.
     *
     * @param out target to write
     * @param outOffset into outOffset to begin writing
     * @param fragmentNum fragment to write (0 indexed)
     * @return bytesWritten
     */
    public int writeFragment(byte out[], int outOffset, int fragmentNum) {
        int start = _fragmentSize * fragmentNum;
        int end = start + fragmentSize(fragmentNum);
        if (_messageBuf == null) return -1;
        int toSend = end - start;
        byte buf[] = _messageBuf.getData();
        if ( (buf != null) && (start + toSend < buf.length) && (out != null) && (outOffset + toSend < out.length) ) {
            System.arraycopy(_messageBuf.getData(), start, out, outOffset, toSend);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Raw fragment[" + fragmentNum + "] for " + _messageId 
                           + "[" + start + "-" + (start+toSend) + "/" + _messageBuf.getValid() + "/" + _fragmentSize + "]: " 
                           + Base64.encode(out, outOffset, toSend));
            return toSend;
        } else {
            return -1;
        }
    }
    
    public String toString() {
        short sends[] = _fragmentSends;
        ByteArray messageBuf = _messageBuf;
        StringBuffer buf = new StringBuffer(64);
        buf.append("Message ").append(_messageId);
        if (sends != null)
            buf.append(" with ").append(sends.length).append(" fragments");
        if (messageBuf != null)
            buf.append(" of size ").append(messageBuf.getValid());
        buf.append(" volleys: ").append(_maxSends);
        buf.append(" lifetime: ").append(getLifetime());
        if (sends != null) {
            buf.append(" pending fragments: ");
            for (int i = 0; i < sends.length; i++)
                if (sends[i] >= 0)
                    buf.append(i).append(' ');
        }
        return buf.toString();
    }
}
