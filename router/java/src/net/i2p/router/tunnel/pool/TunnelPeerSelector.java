package net.i2p.router.tunnel.pool;

import java.util.*;
import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.util.Log;

/**
 * Coordinate the selection of peers to go into a tunnel for one particular 
 * pool.
 */
abstract class TunnelPeerSelector {
    /**
     * Which peers should go into the next tunnel for the given settings?  
     * 
     * @return ordered list of Hash objects (one per peer) specifying what order
     *         they should appear in a tunnel (endpoint first).  This includes
     *         the local router in the list.  If there are no tunnels or peers
     *         to build through, and the settings reject 0 hop tunnels, this will
     *         return null.
     */
    public abstract List selectPeers(RouterContext ctx, TunnelPoolSettings settings);
    
    protected int getLength(RouterContext ctx, TunnelPoolSettings settings) {
        int length = settings.getLength();
        int override = settings.getLengthOverride();
        if (override != 0)
            length = override;
        else if (settings.getLengthVariance() != 0) {
            int skew = settings.getLengthVariance();
            if (skew > 0)
                length += ctx.random().nextInt(skew+1);
            else {
                skew = 1 - skew;
                int off = ctx.random().nextInt(skew);
                if (ctx.random().nextBoolean())
                    length += off;
                else
                    length -= off;
            }
            if (length < 0)
                length = 0;
        }
        if ( (ctx.tunnelManager().getOutboundTunnelCount() <= 0) || 
             (ctx.tunnelManager().getFreeTunnelCount() <= 0) ) {
            Log log = ctx.logManager().getLog(TunnelPeerSelector.class);
            // no tunnels to build tunnels with
            if (settings.getAllowZeroHop()) {
                if (log.shouldLog(Log.INFO))
                    log.info("no outbound tunnels or free inbound tunnels, but we do allow zeroHop: " + settings);
                return 0;
            } else {
                if (log.shouldLog(Log.WARN))
                    log.warn("no outbound tunnels or free inbound tunnels, and we dont allow zeroHop: " + settings);
                return -1;
            }
        }
        return length;
    }
    
    protected boolean shouldSelectExplicit(TunnelPoolSettings settings) {
        if (settings.isExploratory()) return false;
        Properties opts = settings.getUnknownOptions();
        if (opts != null) {
            String peers = opts.getProperty("explicitPeers");
            if (peers == null)
                peers = I2PAppContext.getGlobalContext().getProperty("explicitPeers");
            if (peers != null)
                return true;
        }
        return false;
    }
    
    protected List selectExplicit(RouterContext ctx, TunnelPoolSettings settings, int length) {
        String peers = null;
        Properties opts = settings.getUnknownOptions();
        if (opts != null)
            peers = opts.getProperty("explicitPeers");
        
        if (peers == null)
            peers = I2PAppContext.getGlobalContext().getProperty("explicitPeers");
        
        Log log = ctx.logManager().getLog(ClientPeerSelector.class);
        List rv = new ArrayList();
        StringTokenizer tok = new StringTokenizer(peers, ",");
        Hash h = new Hash();
        while (tok.hasMoreTokens()) {
            String peerStr = tok.nextToken();
            Hash peer = new Hash();
            try {
                peer.fromBase64(peerStr);
                
                if (ctx.profileOrganizer().isSelectable(peer)) {
                    rv.add(peer);
                } else {
                    if (log.shouldLog(Log.DEBUG))
                        log.debug("Explicit peer is not selectable: " + peerStr);
                }
            } catch (DataFormatException dfe) {
                if (log.shouldLog(Log.ERROR))
                    log.error("Explicit peer is improperly formatted (" + peerStr + ")", dfe);
            }
        }
        
        int sz = rv.size();
        Collections.shuffle(rv, ctx.random());
        
        while (rv.size() > length)
            rv.remove(0);
        
        if (log.shouldLog(Log.INFO)) {
            StringBuffer buf = new StringBuffer();
            if (settings.getDestinationNickname() != null)
                buf.append("peers for ").append(settings.getDestinationNickname());
            else if (settings.getDestination() != null)
                buf.append("peers for ").append(settings.getDestination().toBase64());
            else
                buf.append("peers for exploratory ");
            if (settings.isInbound())
                buf.append(" inbound");
            else
                buf.append(" outbound");
            buf.append(" peers: ").append(rv);
            buf.append(", out of ").append(sz).append(" (not including self)");
            log.info(buf.toString());
        }
        
        if (settings.isInbound())
            rv.add(0, ctx.routerHash());
        else
            rv.add(ctx.routerHash());
        
        return rv;
    }
    
    /** 
     * Pick peers that we want to avoid
     */
    public Set getExclude(RouterContext ctx, boolean isInbound, boolean isExploratory) {
        // we may want to update this to skip 'hidden' or 'unreachable' peers, but that
        // isn't safe, since they may publish one set of routerInfo to us and another to
        // other peers.  the defaults for filterUnreachable has always been to return false,
        // but might as well make it explicit with a "false &&"
        
        if (false && filterUnreachable(ctx, isInbound, isExploratory)) {
            List caps = ctx.peerManager().getPeersByCapability(Router.CAPABILITY_UNREACHABLE);
            if (caps == null) return new HashSet(0);
            HashSet rv = new HashSet(caps);
            return rv;
        } else {
            return new HashSet(1);
        }
    }
    
    private static final String PROP_OUTBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE = "router.outboundExploratoryExcludeUnreachable";
    private static final String PROP_OUTBOUND_CLIENT_EXCLUDE_UNREACHABLE = "router.outboundClientExcludeUnreachable";
    private static final String PROP_INBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE = "router.inboundExploratoryExcludeUnreachable";
    private static final String PROP_INBOUND_CLIENT_EXCLUDE_UNREACHABLE = "router.inboundClientExcludeUnreachable";
    private static final boolean DEFAULT_OUTBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE = false;
    private static final boolean DEFAULT_OUTBOUND_CLIENT_EXCLUDE_UNREACHABLE = false;
    private static final boolean DEFAULT_INBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE = false;
    private static final boolean DEFAULT_INBOUND_CLIENT_EXCLUDE_UNREACHABLE = false;
    
    protected boolean filterUnreachable(RouterContext ctx, boolean isInbound, boolean isExploratory) {
        boolean def = false;
        String val = null;
        
        if (isExploratory)
            if (isInbound)
                val = ctx.getProperty(PROP_INBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE);
            else
                val = ctx.getProperty(PROP_OUTBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE);
        else
            if (isInbound)
                val = ctx.getProperty(PROP_INBOUND_CLIENT_EXCLUDE_UNREACHABLE);
            else 
                val = ctx.getProperty(PROP_OUTBOUND_CLIENT_EXCLUDE_UNREACHABLE);
        
        boolean rv = (val != null ? Boolean.valueOf(val).booleanValue() : def);
        //System.err.println("Filter unreachable? " + rv + " (inbound? " + isInbound + ", exploratory? " + isExploratory);
        return rv;
    }
}
