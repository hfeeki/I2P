package net.i2p.router.tunnel;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.data.Hash;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/**
 * Receive the inbound tunnel message, removing all of the layers
 * added by earlier hops to recover the preprocessed data sent
 * by the gateway.  This delegates the crypto to the 
 * OutboundGatewayProcessor, since the tunnel creator does the 
 * same thing in both instances.
 *
 */
public class InboundEndpointProcessor {
    private I2PAppContext _context;
    private Log _log;
    private TunnelCreatorConfig _config;
    private IVValidator _validator;    
    
    static final boolean USE_ENCRYPTION = HopProcessor.USE_ENCRYPTION;
    private static final ByteCache _cache = ByteCache.getInstance(128, HopProcessor.IV_LENGTH);
    
    public InboundEndpointProcessor(I2PAppContext ctx, TunnelCreatorConfig cfg) {
        this(ctx, cfg, DummyValidator.getInstance());
    }
    public InboundEndpointProcessor(I2PAppContext ctx, TunnelCreatorConfig cfg, IVValidator validator) {
        _context = ctx;
        _log = ctx.logManager().getLog(InboundEndpointProcessor.class);
        _config = cfg;
        _validator = validator;
    }
    
    public Hash getDestination() { return _config.getDestination(); }
    public TunnelCreatorConfig getConfig() { return _config; }
    
    /**
     * Undo all of the encryption done by the peers in the tunnel, recovering the
     * preprocessed data sent by the gateway.  
     *
     * @return true if the data was recovered (and written in place to orig), false
     *         if it was a duplicate or from the wrong peer.
     */
    public boolean retrievePreprocessedData(byte orig[], int offset, int length, Hash prev) {
        Hash last = _config.getPeer(_config.getLength()-2);
        if (!last.equals(prev)) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Invalid previous peer - attempted hostile loop?  from " + prev 
                           + ", expected " + last);
            return false;
        }
        
        ByteArray ba = _cache.acquire();
        byte iv[] = ba.getData(); //new byte[HopProcessor.IV_LENGTH];
        System.arraycopy(orig, offset, iv, 0, iv.length);
        //if (_config.getLength() > 1)
        //    _log.debug("IV at inbound endpoint before decrypt: " + Base64.encode(iv));

        boolean ok = _validator.receiveIV(iv, 0, orig, offset + HopProcessor.IV_LENGTH);
        if (!ok) {
            if (_log.shouldLog(Log.WARN)) 
                _log.warn("Invalid IV received");
            _cache.release(ba);
            return false;
        }
        
        // inbound endpoints and outbound gateways have to undo the crypto in the same way
        if (USE_ENCRYPTION)
            decrypt(_context, _config, iv, orig, offset, length);
        
        _cache.release(ba);
        return true;
    }
    
    private void decrypt(I2PAppContext ctx, TunnelCreatorConfig cfg, byte iv[], byte orig[], int offset, int length) {
        Log log = ctx.logManager().getLog(OutboundGatewayProcessor.class);
        ByteArray ba = _cache.acquire();
        byte cur[] = ba.getData(); // new byte[HopProcessor.IV_LENGTH]; // so we dont malloc
        for (int i = cfg.getLength()-2; i >= 0; i--) { // dont include the endpoint, since that is the creator
            OutboundGatewayProcessor.decrypt(ctx, iv, orig, offset, length, cur, cfg.getConfig(i));
            if (log.shouldLog(Log.DEBUG)) {
                //log.debug("IV at hop " + i + ": " + Base64.encode(orig, offset, HopProcessor.IV_LENGTH));
                //log.debug("hop " + i + ": " + Base64.encode(orig, offset + HopProcessor.IV_LENGTH, length - HopProcessor.IV_LENGTH));
            }
        }
        _cache.release(ba);
    }
    
}
