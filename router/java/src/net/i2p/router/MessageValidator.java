package net.i2p.router;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeMap;

import net.i2p.util.DecayingBloomFilter;
import net.i2p.util.Log;

/**
 * Singleton to manage the logic (and historical data) to determine whether a message
 * is valid or not (meaning it isn't expired and hasn't already been received).  We'll
 * need a revamp once we start dealing with long message expirations (since it might
 * involve keeping a significant number of entries in memory), but that probably won't
 * be necessary until I2P 3.0.
 *
 */
public class MessageValidator {
    private Log _log;
    private RouterContext _context;
    private DecayingBloomFilter _filter;
    
    
    public MessageValidator(RouterContext context) {
        _log = context.logManager().getLog(MessageValidator.class);
        _filter = null;
        _context = context;
        context.statManager().createRateStat("router.duplicateMessageId", "Note that a duplicate messageId was received", "Router", 
                                             new long[] { 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l });
    }
    
    
    /**
     * Determine if this message should be accepted as valid (not expired, not a duplicate)
     *
     * @return true if the message should be accepted as valid, false otherwise
     */
    public boolean validateMessage(long messageId, long expiration) {
        long now = _context.clock().now();
        if (now - Router.CLOCK_FUDGE_FACTOR >= expiration) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Rejecting message " + messageId + " because it expired " + (now-expiration) + "ms ago");
            return false;
        } else if (now + 4*Router.CLOCK_FUDGE_FACTOR < expiration) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Rejecting message " + messageId + " because it will expire too far in the future (" + (expiration-now) + "ms)");
            return false;
        }
        
        boolean isDuplicate = noteReception(messageId, expiration);
        if (isDuplicate) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Rejecting message " + messageId + " because it is a duplicate", new Exception("Duplicate origin"));
            _context.statManager().addRateData("router.duplicateMessageId", 1, 0);
            return false;
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Accepting message " + messageId + " because it is NOT a duplicate", new Exception("Original origin"));
            return true;
        }
    }
    
    /**
     * Note that we've received the message (which has the expiration given).
     * This functionality will need to be reworked for I2P 3.0 when we take into
     * consideration messages with significant user specified delays (since we dont
     * want to keep an infinite number of messages in RAM, etc)
     *
     * @return true if we HAVE already seen this message, false if not
     */
    private boolean noteReception(long messageId, long messageExpiration) {
        boolean dup = _filter.add(messageId);
        return dup;
    }
    
    public void startup() {
        _filter = new DecayingBloomFilter(_context, (int)Router.CLOCK_FUDGE_FACTOR * 2, 8);
    }
    
    void shutdown() {
        _filter.stopDecaying();
    }
}
