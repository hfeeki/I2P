package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.util.Log;

/**
 * Thread safe implementation of an I2P session running over TCP.  
 *
 * @author jrandom
 */
class I2PSessionImpl2 extends I2PSessionImpl {
    private Log _log;

    /** set of MessageState objects, representing all of the messages in the process of being sent */
    private Set _sendingStates;
    /** max # seconds to wait for confirmation of the message send */
    private final static long SEND_TIMEOUT = 60 * 1000; // 60 seconds to send 
    /** should we gzip each payload prior to sending it? */
    private final static boolean SHOULD_COMPRESS = true;
    private final static boolean SHOULD_DECOMPRESS = true;

    /**
     * Create a new session, reading the Destination, PrivateKey, and SigningPrivateKey
     * from the destKeyStream, and using the specified options to connect to the router
     *
     * @throws I2PSessionException if there is a problem loading the private keys or 
     */
    public I2PSessionImpl2(I2PAppContext ctx, InputStream destKeyStream, Properties options) throws I2PSessionException {
        super(ctx, destKeyStream, options);
        _log = ctx.logManager().getLog(I2PSessionImpl2.class);
        _sendingStates = new HashSet(32);

        ctx.statManager().createRateStat("i2cp.sendBestEffortTotalTime", "how long to do the full sendBestEffort call?", "i2cp", new long[] { 10*60*1000 } );
        //ctx.statManager().createRateStat("i2cp.sendBestEffortStage0", "first part of sendBestEffort?", "i2cp", new long[] { 10*60*1000 } );
        //ctx.statManager().createRateStat("i2cp.sendBestEffortStage1", "second part of sendBestEffort?", "i2cp", new long[] { 10*60*1000 } );
        //ctx.statManager().createRateStat("i2cp.sendBestEffortStage2", "third part of sendBestEffort?", "i2cp", new long[] { 10*60*1000 } );
        //ctx.statManager().createRateStat("i2cp.sendBestEffortStage3", "fourth part of sendBestEffort?", "i2cp", new long[] { 10*60*1000 } );
        //ctx.statManager().createRateStat("i2cp.sendBestEffortStage4", "fifth part of sendBestEffort?", "i2cp", new long[] { 10*60*1000 } );
        _context.statManager().createRateStat("i2cp.receiveStatusTime.0", "How long it took to get status=0 back", "i2cp", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("i2cp.receiveStatusTime.1", "How long it took to get status=1 back", "i2cp", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("i2cp.receiveStatusTime.2", "How long it took to get status=2 back", "i2cp", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("i2cp.receiveStatusTime.3", "How long it took to get status=3 back", "i2cp", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("i2cp.receiveStatusTime.4", "How long it took to get status=4 back", "i2cp", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("i2cp.receiveStatusTime.5", "How long it took to get status=5 back", "i2cp", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("i2cp.receiveStatusTime", "How long it took to get any status", "i2cp", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("i2cp.tx.msgCompressed", "compressed size transferred", "i2cp", new long[] { 60*1000, 30*60*1000 });
        _context.statManager().createRateStat("i2cp.tx.msgExpanded", "size before compression", "i2cp", new long[] { 60*1000, 30*60*1000 });
    }

    protected long getTimeout() {
        return SEND_TIMEOUT;
    }
    
    @Override
    public void destroySession(boolean sendDisconnect) {
        clearStates();
        super.destroySession(sendDisconnect);
    }

    /** Don't bother if really small.
     *  Three 66-byte messages will fit in one tunnel message.
     *  Four messages don't fit no matter how small. So below 66 it isn't worth it.
     *  See ConnectionOptions.java in the streaming lib for similar calculations.
     *  Since we still have to pass it through gzip -0 the CPU savings
     *  is trivial but it's the best we can do for now. See below.
     *  i2cp.gzip defaults to SHOULD_COMPRESS = true.
     *  Perhaps the http server (which does its own compression)
     *  and P2P apps (with generally uncompressible data) should
     *  set to false.
     */
    private static final int DONT_COMPRESS_SIZE = 66;
    private boolean shouldCompress(int size) {
         if (size <= DONT_COMPRESS_SIZE)
             return false;
         String p = getOptions().getProperty("i2cp.gzip");
         if (p != null)
             return Boolean.valueOf(p).booleanValue();
         return SHOULD_COMPRESS;
    }
    
    @Override
    public boolean sendMessage(Destination dest, byte[] payload) throws I2PSessionException {
        return sendMessage(dest, payload, 0, payload.length);
    }
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size) throws I2PSessionException {
        return sendMessage(dest, payload, offset, size, new SessionKey(), new HashSet(64));
    }
    
    @Override
    public boolean sendMessage(Destination dest, byte[] payload, SessionKey keyUsed, Set tagsSent) throws I2PSessionException {
        return sendMessage(dest, payload, 0, payload.length, keyUsed, tagsSent);
    }
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size, SessionKey keyUsed, Set tagsSent)
                   throws I2PSessionException {
        if (_log.shouldLog(Log.DEBUG)) _log.debug("sending message");
        if (isClosed()) throw new I2PSessionException("Already closed");

        // Sadly there is no way to send something completely uncompressed in a backward-compatible way,
        // so we have to still send it in a gzip format, which adds 23 bytes (2.4% for a 960-byte msg)
        // (10 byte header + 5 byte block header + 8 byte trailer)
        // In the future we can add a one-byte magic number != 0x1F to signal an uncompressed msg
        // (Gzip streams start with 0x1F 0x8B 0x08)
        // assuming we don't need the CRC-32 that comes with gzip (do we?)
        // Maybe implement this soon in receiveMessage() below so we are ready
        // in case we ever make an incompatible network change.
        // This would save 22 of the 23 bytes and a little CPU.
        boolean sc = shouldCompress(size);
        if (sc)
            payload = DataHelper.compress(payload, offset, size);
        else
            payload = DataHelper.compress(payload, offset, size, DataHelper.NO_COMPRESSION);
        //else throw new IllegalStateException("we need to update sendGuaranteed to support partial send");

        int compressed = payload.length;
        if (_log.shouldLog(Log.INFO)) {
            String d = dest.calculateHash().toBase64().substring(0,4);
            _log.info("sending message to: " + d + " compress? " + sc + " sizeIn=" + size + " sizeOut=" + compressed);
        }
        _context.statManager().addRateData("i2cp.tx.msgCompressed", compressed, 0);
        _context.statManager().addRateData("i2cp.tx.msgExpanded", size, 0);
        return sendBestEffort(dest, payload, keyUsed, tagsSent);
    }

    /**
     * pull the unencrypted AND DECOMPRESSED data 
     */
    @Override
    public byte[] receiveMessage(int msgId) throws I2PSessionException {
        byte compressed[] = super.receiveMessage(msgId);
        if (compressed == null) {
            _log.error("Error: message " + msgId + " already received!");
            return null;
        }
        // future - check magic number to see whether to decompress
        if (SHOULD_DECOMPRESS) {
            try {
                return DataHelper.decompress(compressed);
            } catch (IOException ioe) {
                throw new I2PSessionException("Error decompressing message", ioe);
            }
        }
        return compressed;
    }
    
    private static final int NUM_TAGS = 50;

    private boolean sendBestEffort(Destination dest, byte payload[], SessionKey keyUsed, Set tagsSent)
                    throws I2PSessionException {
        SessionKey key = null;
        SessionKey newKey = null;
        SessionTag tag = null;
        Set sentTags = null;
        int oldTags = 0;
        long begin = _context.clock().now();
        if (I2CPMessageProducer.END_TO_END_CRYPTO) {
            if (_log.shouldLog(Log.DEBUG)) _log.debug("begin sendBestEffort");
            key = _context.sessionKeyManager().getCurrentKey(dest.getPublicKey());
            if (_log.shouldLog(Log.DEBUG)) _log.debug("key fetched");
            if (key == null) key = _context.sessionKeyManager().createSession(dest.getPublicKey());
            tag = _context.sessionKeyManager().consumeNextAvailableTag(dest.getPublicKey(), key);
            if (_log.shouldLog(Log.DEBUG)) _log.debug("tag consumed");
            sentTags = null;
            oldTags = _context.sessionKeyManager().getAvailableTags(dest.getPublicKey(), key);
            long availTimeLeft = _context.sessionKeyManager().getAvailableTimeLeft(dest.getPublicKey(), key);
        
            if ( (tagsSent == null) || (tagsSent.size() <= 0) ) {
                if (oldTags < NUM_TAGS) {
                    sentTags = createNewTags(NUM_TAGS);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("** sendBestEffort only had " + oldTags + " with " + availTimeLeft + ", adding " + NUM_TAGS + ": " + sentTags);
                } else if (availTimeLeft < 2 * 60 * 1000) {
                    // if we have > 50 tags, but they expire in under 2 minutes, we want more
                    sentTags = createNewTags(NUM_TAGS);
                    if (_log.shouldLog(Log.DEBUG)) 
                        _log.debug(getPrefix() + "Tags expiring in " + availTimeLeft + ", adding " + NUM_TAGS + " new ones: " + sentTags);
                    //_log.error("** sendBestEffort available time left " + availTimeLeft);
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("sendBestEffort old tags: " + oldTags + " available time left: " + availTimeLeft);
                }
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("sendBestEffort is sending " + tagsSent.size() + " with " + availTimeLeft 
                               + "ms left, " + oldTags + " tags known and " 
                               + (tag == null ? "no tag" : " a valid tag"));
            }
        
            if (false) // rekey
                newKey = _context.keyGenerator().generateSessionKey();
        
            if ( (tagsSent != null) && (tagsSent.size() > 0) ) {
                if (sentTags == null)
                    sentTags = new HashSet();
                sentTags.addAll(tagsSent);
            }
        } else {
            // not using end to end crypto, so don't ever bundle any tags
        }
        
        if (_log.shouldLog(Log.DEBUG)) _log.debug("before creating nonce");
        
        long nonce = _context.random().nextInt(Integer.MAX_VALUE);
        if (_log.shouldLog(Log.DEBUG)) _log.debug("before sync state");
        MessageState state = new MessageState(_context, nonce, getPrefix());
        state.setKey(key);
        state.setTags(sentTags);
        state.setNewKey(newKey);
        state.setTo(dest);
        if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "Setting key = " + key);

        if (keyUsed != null) {
            if (I2CPMessageProducer.END_TO_END_CRYPTO) {
                if (newKey != null)
                    keyUsed.setData(newKey.getData());
                else
                    keyUsed.setData(key.getData());
            } else {
                keyUsed.setData(SessionKey.INVALID_KEY.getData());
            }
        }
        if (tagsSent != null) {
            if (sentTags != null) {
                tagsSent.addAll(sentTags);
            }
        }

        if (_log.shouldLog(Log.DEBUG)) _log.debug("before sync state");
        long beforeSendingSync = _context.clock().now();
        long inSendingSync = 0;
        synchronized (_sendingStates) {
            inSendingSync = _context.clock().now();
            _sendingStates.add(state);
        }
        long afterSendingSync = _context.clock().now();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getPrefix() + "Adding sending state " + state.getMessageId() + " / "
                       + state.getNonce() + " for best effort "
                       + " sync took " + (inSendingSync-beforeSendingSync) 
                       + " add took " + (afterSendingSync-inSendingSync));
        _producer.sendMessage(this, dest, nonce, payload, tag, key, sentTags, newKey);
        
        // since this is 'best effort', all we're waiting for is a status update 
        // saying that the router received it - in theory, that should come back
        // immediately, but in practice can take up to a second (though usually
        // much quicker).  setting this to false will short-circuit that delay
        boolean actuallyWait = false; // true;
        
        long beforeWaitFor = _context.clock().now();
        if (actuallyWait)
            state.waitFor(MessageStatusMessage.STATUS_SEND_ACCEPTED, 
                          _context.clock().now() + getTimeout());
        //long afterWaitFor = _context.clock().now();
        //long inRemovingSync = 0;
        synchronized (_sendingStates) {
            //inRemovingSync = _context.clock().now();
            _sendingStates.remove(state);
        }
        long afterRemovingSync = _context.clock().now();
        boolean found = !actuallyWait || state.received(MessageStatusMessage.STATUS_SEND_ACCEPTED);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getPrefix() + "After waitFor sending state " + state.getMessageId()
                       + " / " + state.getNonce() + " found = " + found);
        
        long timeToSend = afterRemovingSync - beforeSendingSync;
        if ( (timeToSend > 10*1000) && (_log.shouldLog(Log.WARN)) ) {
            _log.warn("wtf, took " + timeToSend + "ms to send the message?!", new Exception("baz"));
        }
        
        if ( (afterRemovingSync - begin > 500) && (_log.shouldLog(Log.WARN) ) ) {
            _log.warn("Took " + (afterRemovingSync-begin) + "ms to sendBestEffort, "
                      + (afterSendingSync-begin) + "ms to prepare, "
                      + (beforeWaitFor-afterSendingSync) + "ms to send, "
                      + (afterRemovingSync-beforeWaitFor) + "ms waiting for reply");
        }
        
        _context.statManager().addRateData("i2cp.sendBestEffortTotalTime", afterRemovingSync - begin, 0);
        //_context.statManager().addRateData("i2cp.sendBestEffortStage0", beforeSendingSync- begin, 0);
        //_context.statManager().addRateData("i2cp.sendBestEffortStage1", afterSendingSync- beforeSendingSync, 0);
        //_context.statManager().addRateData("i2cp.sendBestEffortStage2", beforeWaitFor- afterSendingSync, 0);
        //_context.statManager().addRateData("i2cp.sendBestEffortStage3", afterWaitFor- beforeWaitFor, 0);
        //_context.statManager().addRateData("i2cp.sendBestEffortStage4", afterRemovingSync- afterWaitFor, 0);
        
        if (found) {
            if (_log.shouldLog(Log.INFO))
                _log.info(getPrefix() + "Message sent after " + state.getElapsed() + "ms with "
                          + payload.length + " bytes");
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info(getPrefix() + "Message send failed after " + state.getElapsed() + "ms with "
                          + payload.length + " bytes");
            if (_log.shouldLog(Log.ERROR))
                _log.error(getPrefix() + "Never received *accepted* from the router!  dropping and reconnecting");
            disconnect();
            return false;
        }
        return found;
    }
    
    @Override
    public void receiveStatus(int msgId, long nonce, int status) {
        if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "Received status " + status + " for msgId " + msgId + " / " + nonce);
        MessageState state = null;
        long beforeSync = _context.clock().now();
        long inSync = 0;
        synchronized (_sendingStates) {
            inSync = _context.clock().now();
            for (Iterator iter = _sendingStates.iterator(); iter.hasNext();) {
                state = (MessageState) iter.next();
                if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "State " + state.getMessageId() + " / " + state.getNonce());
                if (state.getNonce() == nonce) {
                    if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "Found a matching state");
                    break;
                } else if ((state.getMessageId() != null) && (state.getMessageId().getMessageId() == msgId)) {
                    if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "Found a matching state by msgId");
                    break;
                } else {
                    if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "State does not match");
                    state = null;
                }
            }
        }
        long afterSync = _context.clock().now();

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("receiveStatus(" + msgId + ", " + nonce + ", " + status+ "): sync: " 
                       + (inSync-beforeSync) + "ms, check: " + (afterSync-inSync));
        
        if (state != null) {
            if (state.getMessageId() == null) {
                MessageId id = new MessageId();
                id.setMessageId(msgId);
                state.setMessageId(id);
            }
            state.receive(status);
            
            long lifetime = state.getElapsed();
            switch (status) {
                case 1:
                    _context.statManager().addRateData("i2cp.receiveStatusTime.1", lifetime, 0);
                    break;
                case 2:
                    _context.statManager().addRateData("i2cp.receiveStatusTime.2", lifetime, 0);
                    break;
                case 3:
                    _context.statManager().addRateData("i2cp.receiveStatusTime.3", lifetime, 0);
                    break;
                case 4:
                    _context.statManager().addRateData("i2cp.receiveStatusTime.4", lifetime, 0);
                    break;
                case 5:
                    _context.statManager().addRateData("i2cp.receiveStatusTime.5", lifetime, 0);
                    break;
            }
            
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info(getPrefix() + "No matching state for messageId " + msgId + " / " + nonce
                          + " w/ status = " + status);
        }
        _context.statManager().addRateData("i2cp.receiveStatusTime", _context.clock().now() - beforeSync, 0);
    }

    /**
     * Called whenever we want to reconnect (used only in the superclass).  We need
     * to override this to clear out the message state
     *
     */
    @Override
    protected boolean reconnect() {
        // even if we succeed in reconnecting, we want to clear the old states, 
        // since this will be a new sessionId
        clearStates();
        return super.reconnect();
    }

    private void clearStates() {
        synchronized (_sendingStates) {
            for (Iterator iter = _sendingStates.iterator(); iter.hasNext();) {
                MessageState state = (MessageState) iter.next();
                state.cancel();
            }
            if (_log.shouldLog(Log.INFO)) _log.info(getPrefix() + "Disconnecting " + _sendingStates.size() + " states");
            _sendingStates.clear();
        }
    }
}
