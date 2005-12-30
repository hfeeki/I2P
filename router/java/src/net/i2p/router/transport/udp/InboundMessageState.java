package net.i2p.router.transport.udp;

import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/**
 * Hold the raw data fragments of an inbound message
 *
 */
public class InboundMessageState {
    private RouterContext _context;
    private Log _log;
    private long _messageId;
    private Hash _from;
    /** 
     * indexed array of fragments for the message, where not yet
     * received fragments are null.
     */
    private ByteArray _fragments[];
    /**
     * what is the last fragment in the message (or -1 if not yet known)
     */
    private int _lastFragment;
    private long _receiveBegin;
    private int _completeSize;
    
    /** expire after 10s */
    private static final long MAX_RECEIVE_TIME = 10*1000;
    private static final int MAX_FRAGMENTS = 64;
    
    private static final ByteCache _fragmentCache = ByteCache.getInstance(64, 2048);
    
    public InboundMessageState(RouterContext ctx, long messageId, Hash from) {
        _context = ctx;
        _log = ctx.logManager().getLog(InboundMessageState.class);
        _messageId = messageId;
        _from = from;
        _fragments = new ByteArray[MAX_FRAGMENTS];
        _lastFragment = -1;
        _completeSize = -1;
        _receiveBegin = ctx.clock().now();
    }
    
    /**
     * Read in the data from the fragment.
     *
     * @return true if the data was ok, false if it was corrupt
     */
    public boolean receiveFragment(UDPPacketReader.DataReader data, int dataFragment) {
        int fragmentNum = data.readMessageFragmentNum(dataFragment);
        if ( (fragmentNum < 0) || (fragmentNum > _fragments.length)) {
            _log.warn("Invalid fragment " + fragmentNum + "/" + _fragments.length);
            return false;
        }
        if (_fragments[fragmentNum] == null) {
            // new fragment, read it
            ByteArray message = _fragmentCache.acquire();
            try {
                data.readMessageFragment(dataFragment, message.getData(), 0);
                int size = data.readMessageFragmentSize(dataFragment);
                message.setValid(size);
                _fragments[fragmentNum] = message;
                boolean isLast = data.readMessageIsLast(dataFragment);
                if (isLast)
                    _lastFragment = fragmentNum;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("New fragment " + fragmentNum + " for message " + _messageId 
                               + ", size=" + size
                               + ", isLast=" + isLast
                               + ", data=" + Base64.encode(message.getData(), 0, size));
            } catch (ArrayIndexOutOfBoundsException aioobe) {
                _log.warn("Corrupt SSU fragment " + fragmentNum, aioobe);
                return false;
            }
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Received fragment " + fragmentNum + " for message " + _messageId 
                           + " again, old size=" + _fragments[fragmentNum].getValid() 
                           + " and new size=" + data.readMessageFragmentSize(dataFragment));
        }
        return true;
    }
    
    public boolean isComplete() {
        if (_lastFragment < 0) return false;
        for (int i = 0; i <= _lastFragment; i++)
            if (_fragments[i] == null)
                return false;
        return true;
    }
    public boolean isExpired() { 
        return _context.clock().now() > _receiveBegin + MAX_RECEIVE_TIME;
    }
    public long getLifetime() {
        return _context.clock().now() - _receiveBegin;
    }
    public Hash getFrom() { return _from; }
    public long getMessageId() { return _messageId; }
    public int getCompleteSize() {
        if (_completeSize < 0) {
            int size = 0;
            for (int i = 0; i <= _lastFragment; i++)
                size += _fragments[i].getValid();
            _completeSize = size;
        }
        return _completeSize;
    }
    public ACKBitfield createACKBitfield() {
        return new PartialBitfield(_messageId, _fragments);
    }
    
    private static final class PartialBitfield implements ACKBitfield {
        private long _bitfieldMessageId;
        private boolean _fragmentsReceived[];
        
        public PartialBitfield(long messageId, Object data[]) {
            _bitfieldMessageId = messageId;
            for (int i = data.length - 1; i >= 0; i--) {
                if (data[i] != null) {
                    if (_fragmentsReceived == null)
                        _fragmentsReceived = new boolean[i+1];
                    _fragmentsReceived[i] = true;
                }
            }
            if (_fragmentsReceived == null)
                _fragmentsReceived = new boolean[0];
        }
        public int fragmentCount() { return _fragmentsReceived.length; }
        public long getMessageId() { return _bitfieldMessageId; }
        public boolean received(int fragmentNum) { 
            if ( (fragmentNum < 0) || (fragmentNum >= _fragmentsReceived.length) )
                return false;
            return _fragmentsReceived[fragmentNum];
        }
        public boolean receivedComplete() { return false; }
        
        public String toString() { 
            StringBuffer buf = new StringBuffer(64);
            buf.append("Partial ACK of ");
            buf.append(_bitfieldMessageId);
            buf.append(" with ACKs for: ");
            for (int i = 0; i < _fragmentsReceived.length; i++)
                if (_fragmentsReceived[i])
                    buf.append(i).append(" ");
            return buf.toString();
        }
    }
    
    public void releaseResources() {
        if (_fragments != null)
            for (int i = 0; i < _fragments.length; i++)
                _fragmentCache.release(_fragments[i]);
        //_fragments = null;
    }
    
    public ByteArray[] getFragments() {
        return _fragments;
    }
    public int getFragmentCount() { return _lastFragment+1; }
    
    public String toString() {
        StringBuffer buf = new StringBuffer(32);
        buf.append("Message: ").append(_messageId);
        //if (isComplete()) {
        //    buf.append(" completely received with ");
        //    buf.append(getCompleteSize()).append(" bytes");
        //}
        buf.append(" lifetime: ").append(getLifetime());
        return buf.toString();
    }
}
