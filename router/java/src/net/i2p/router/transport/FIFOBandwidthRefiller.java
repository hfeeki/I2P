package net.i2p.router.transport;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

class FIFOBandwidthRefiller implements Runnable {
    private Log _log;
    private I2PAppContext _context;
    private FIFOBandwidthLimiter _limiter;
    /** how many KBps do we want to allow? */
    private int _inboundKBytesPerSecond;
    /** how many KBps do we want to allow? */
    private int _outboundKBytesPerSecond;
    /** how frequently do we want to replenish the available queues? */
    private long _replenishFrequency;
    /** when did we last replenish the queue? */
    private long _lastRefillTime;
    /** when did we last check the config for updates? */
    private long _lastCheckConfigTime;
    /** how frequently do we check the config for updates? */
    private long _configCheckPeriodMs = 60*1000;
 
    public static final String PROP_INBOUND_BANDWIDTH = "i2np.bandwidth.inboundKBytesPerSecond";
    public static final String PROP_OUTBOUND_BANDWIDTH = "i2np.bandwidth.outboundKBytesPerSecond";
    public static final String PROP_INBOUND_BANDWIDTH_PEAK = "i2np.bandwidth.inboundBurstKBytes";
    public static final String PROP_OUTBOUND_BANDWIDTH_PEAK = "i2np.bandwidth.outboundBurstKBytes";
    //public static final String PROP_REPLENISH_FREQUENCY = "i2np.bandwidth.replenishFrequencyMs";

    // no longer allow unlimited bandwidth - the user must specify a value, and if they do not, it is 16KBps
    public static final int DEFAULT_INBOUND_BANDWIDTH = 16;
    public static final int DEFAULT_OUTBOUND_BANDWIDTH = 16;

    public static final int DEFAULT_BURST_SECONDS = 60;
    
    /** For now, until there is some tuning and safe throttling, we set the floor at 6KBps inbound */
    public static final int MIN_INBOUND_BANDWIDTH = 5;
    /** For now, until there is some tuning and safe throttling, we set the floor at 6KBps outbound */
    public static final int MIN_OUTBOUND_BANDWIDTH = 5;
    /** For now, until there is some tuning and safe throttling, we set the floor at a 10 second burst */
    public static final int MIN_INBOUND_BANDWIDTH_PEAK = 10;
    /** For now, until there is some tuning and safe throttling, we set the floor at a 10 second burst */
    public static final int MIN_OUTBOUND_BANDWIDTH_PEAK = 10;
    /** Updating the bandwidth more than once a second is silly.  once every 2 or 5 seconds is less so. */
    public static final long MIN_REPLENISH_FREQUENCY = 100;
    
    private static final long DEFAULT_REPLENISH_FREQUENCY = 100;
    
    public FIFOBandwidthRefiller(I2PAppContext context, FIFOBandwidthLimiter limiter) {
        _limiter = limiter;
        _context = context;
        _log = context.logManager().getLog(FIFOBandwidthRefiller.class);
        reinitialize();
    }
    public void run() {
        // bootstrap 'em with nothing
        _lastRefillTime = _limiter.now();
        while (true) {
            long now = _limiter.now();
            if (now >= _lastCheckConfigTime + _configCheckPeriodMs) {
                checkConfig();
                now = _limiter.now();
                _lastCheckConfigTime = now;
            }
            
            boolean updated = updateQueues(now);
            if (updated) {
                _lastRefillTime = now;
            }
            
            try { Thread.sleep(_replenishFrequency); } catch (InterruptedException ie) {}
        }
    }
    
    public void reinitialize() {
        _lastRefillTime = _limiter.now();
        checkConfig();
        _lastCheckConfigTime = _lastRefillTime;
    }
    
    private boolean updateQueues(long now) {
        long numMs = (now - _lastRefillTime);
        if (_log.shouldLog(Log.INFO))
            _log.info("Updating bandwidth after " + numMs + " (available in=" 
                       + _limiter.getAvailableInboundBytes() + ", out=" 
                       + _limiter.getAvailableOutboundBytes()+ ", rate in=" 
                       + _inboundKBytesPerSecond + ", out=" 
                       + _outboundKBytesPerSecond  +")");
        if (numMs >= MIN_REPLENISH_FREQUENCY) {
            long inboundToAdd = (1024*_inboundKBytesPerSecond * numMs)/1000;
            long outboundToAdd = (1024*_outboundKBytesPerSecond * numMs)/1000;

            if (inboundToAdd < 0) inboundToAdd = 0;
            if (outboundToAdd < 0) outboundToAdd = 0;

            if (_inboundKBytesPerSecond <= 0) {
                _limiter.setInboundUnlimited(true);
                inboundToAdd = 0;
            } else {
                _limiter.setInboundUnlimited(false);
            }
            if (_outboundKBytesPerSecond <= 0) {
                _limiter.setOutboundUnlimited(true);
                outboundToAdd = 0;
            } else {
                _limiter.setOutboundUnlimited(false);
            }
            
            _limiter.refillBandwidthQueues(inboundToAdd, outboundToAdd);
            
            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug("Adding " + inboundToAdd + " bytes to inboundAvailable");
                _log.debug("Adding " + outboundToAdd + " bytes to outboundAvailable");
            }
            return true;
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Refresh delay too fast (" + numMs + ")");
            return false;
        }
    }
    
    private void checkConfig() {
        updateInboundRate();
        updateOutboundRate();
        updateInboundPeak();
        updateOutboundPeak();

        _replenishFrequency = DEFAULT_REPLENISH_FREQUENCY;
        
        if (_inboundKBytesPerSecond <= 0) {
            _limiter.setInboundUnlimited(true);
        } else {
            _limiter.setInboundUnlimited(false);
        }
        if (_outboundKBytesPerSecond <= 0) {
            _limiter.setOutboundUnlimited(true);
        } else {
            _limiter.setOutboundUnlimited(false);
        }

    }
    
    private void updateInboundRate() {
        String inBwStr = _context.getProperty(PROP_INBOUND_BANDWIDTH);
        if ( (inBwStr != null) && 
             (inBwStr.trim().length() > 0) && 
             (!(inBwStr.equals(String.valueOf(_inboundKBytesPerSecond)))) ) {
            // bandwidth was specified *and* changed
            try {
                int in = Integer.parseInt(inBwStr);
                if ( (in <= 0) || (in > MIN_INBOUND_BANDWIDTH) ) 
                    _inboundKBytesPerSecond = in;
                else
                    _inboundKBytesPerSecond = MIN_INBOUND_BANDWIDTH;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Updating inbound rate to " + _inboundKBytesPerSecond);
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid inbound bandwidth limit [" + inBwStr 
                              + "], keeping as " + _inboundKBytesPerSecond);
            }
        } else {
            if ( (inBwStr == null) && (_log.shouldLog(Log.DEBUG)) )
                _log.debug("Inbound bandwidth limits not specified in the config via " + PROP_INBOUND_BANDWIDTH);
        }
        
        if (_inboundKBytesPerSecond <= 0)
            _inboundKBytesPerSecond = DEFAULT_INBOUND_BANDWIDTH;
    }
    private void updateOutboundRate() {
        String outBwStr = _context.getProperty(PROP_OUTBOUND_BANDWIDTH);
        
        if ( (outBwStr != null) && 
             (outBwStr.trim().length() > 0) && 
             (!(outBwStr.equals(String.valueOf(_outboundKBytesPerSecond)))) ) {
            // bandwidth was specified *and* changed
            try {
                int out = Integer.parseInt(outBwStr);
                if ( (out <= 0) || (out >= MIN_OUTBOUND_BANDWIDTH) )
                    _outboundKBytesPerSecond = out;
                else
                    _outboundKBytesPerSecond = MIN_OUTBOUND_BANDWIDTH;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Updating outbound rate to " + _outboundKBytesPerSecond);
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid outbound bandwidth limit [" + outBwStr 
                              + "], keeping as " + _outboundKBytesPerSecond);
            }
        } else {
            if ( (outBwStr == null) && (_log.shouldLog(Log.DEBUG)) )
                _log.debug("Outbound bandwidth limits not specified in the config via " + PROP_OUTBOUND_BANDWIDTH);
        }
        
        if (_outboundKBytesPerSecond <= 0)
            _outboundKBytesPerSecond = DEFAULT_OUTBOUND_BANDWIDTH;
    }
    
    private void updateInboundPeak() {
        String inBwStr = _context.getProperty(PROP_INBOUND_BANDWIDTH_PEAK);
        if ( (inBwStr != null) && 
             (inBwStr.trim().length() > 0) && 
             (!(inBwStr.equals(String.valueOf(_limiter.getMaxInboundBytes())))) ) {
            // peak bw was specified *and* changed
            try {
                int in = Integer.parseInt(inBwStr);
                if (in >= MIN_INBOUND_BANDWIDTH_PEAK) {
                    if (in < _inboundKBytesPerSecond)
                        _limiter.setMaxInboundBytes(_inboundKBytesPerSecond * 1024);
                    else 
                        _limiter.setMaxInboundBytes(in * 1024);
                } else {
                    if (MIN_INBOUND_BANDWIDTH_PEAK < _inboundKBytesPerSecond) 
                        _limiter.setMaxInboundBytes(_inboundKBytesPerSecond * 1024);
                    else
                        _limiter.setMaxInboundBytes(MIN_INBOUND_BANDWIDTH_PEAK * 1024);
                }
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid inbound bandwidth burst limit [" + inBwStr 
                              + "]");
                _limiter.setMaxInboundBytes(DEFAULT_BURST_SECONDS * _inboundKBytesPerSecond * 1024);
            }
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Inbound bandwidth burst limits not specified in the config via " 
                           + PROP_INBOUND_BANDWIDTH_PEAK);
            _limiter.setMaxInboundBytes(DEFAULT_BURST_SECONDS * _inboundKBytesPerSecond * 1024);
        }
    }
    private void updateOutboundPeak() {
        String outBwStr = _context.getProperty(PROP_OUTBOUND_BANDWIDTH_PEAK);
        if ( (outBwStr != null) && 
             (outBwStr.trim().length() > 0) && 
             (!(outBwStr.equals(String.valueOf(_limiter.getMaxOutboundBytes())))) ) {
            // peak bw was specified *and* changed
            try {
                int out = Integer.parseInt(outBwStr);
                if (out >= MIN_OUTBOUND_BANDWIDTH_PEAK) {
                    if (out < _outboundKBytesPerSecond)
                        _limiter.setMaxOutboundBytes(_outboundKBytesPerSecond * 1024);
                    else
                        _limiter.setMaxOutboundBytes(out * 1024);
                } else {
                    if (MIN_OUTBOUND_BANDWIDTH_PEAK < _outboundKBytesPerSecond)
                        _limiter.setMaxOutboundBytes(_outboundKBytesPerSecond * 1024);
                    else
                        _limiter.setMaxOutboundBytes(MIN_OUTBOUND_BANDWIDTH_PEAK * 1024);
                }
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid outbound bandwidth burst limit [" + outBwStr 
                              + "]");
                _limiter.setMaxOutboundBytes(DEFAULT_BURST_SECONDS * _outboundKBytesPerSecond * 1024);
            }
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Outbound bandwidth burst limits not specified in the config via " 
                           + PROP_OUTBOUND_BANDWIDTH_PEAK);
            _limiter.setMaxOutboundBytes(DEFAULT_BURST_SECONDS * _outboundKBytesPerSecond * 1024);
        }
    }

}