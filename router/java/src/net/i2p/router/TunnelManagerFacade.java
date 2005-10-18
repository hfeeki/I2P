package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;

import java.io.IOException;
import java.io.Writer;

/**
 * Build and maintain tunnels throughout the network.
 *
 */ 
public interface TunnelManagerFacade extends Service {
    /**
     * Retrieve the information related to a particular tunnel
     *
     * @param id the tunnelId as seen at the gateway
     *
     */
    TunnelInfo getTunnelInfo(TunnelId id);
    /** pick an inbound tunnel not bound to a particular destination */
    TunnelInfo selectInboundTunnel();
    /** pick an inbound tunnel bound to the given destination */
    TunnelInfo selectInboundTunnel(Hash destination);
    /** pick an outbound tunnel not bound to a particular destination */
    TunnelInfo selectOutboundTunnel();
    /** pick an outbound tunnel bound to the given destination */
    TunnelInfo selectOutboundTunnel(Hash destination);
    
    /**
     * True if the peer currently part of a tunnel
     *
     */
    boolean isInUse(Hash peer);
    
    /** how many tunnels are we participating in? */
    public int getParticipatingCount();
    /** how many free inbound tunnels do we have available? */
    public int getFreeTunnelCount();
    /** how many outbound tunnels do we have available? */
    public int getOutboundTunnelCount();
    
    /** When does the last tunnel we are participating in expire? */
    public long getLastParticipatingExpiration();
    
    /** 
     * the client connected (or updated their settings), so make sure we have
     * the tunnels for them, and whenever necessary, ask them to authorize 
     * leases.
     *
     */
    public void buildTunnels(Destination client, ClientTunnelSettings settings);
    
    public TunnelPoolSettings getInboundSettings();
    public TunnelPoolSettings getOutboundSettings();
    public TunnelPoolSettings getInboundSettings(Hash client);
    public TunnelPoolSettings getOutboundSettings(Hash client);
    public void setInboundSettings(TunnelPoolSettings settings);
    public void setOutboundSettings(TunnelPoolSettings settings);
    public void setInboundSettings(Hash client, TunnelPoolSettings settings);
    public void setOutboundSettings(Hash client, TunnelPoolSettings settings);
}

class DummyTunnelManagerFacade implements TunnelManagerFacade {
    
    public TunnelInfo getTunnelInfo(TunnelId id) { return null; }
    public TunnelInfo selectInboundTunnel() { return null; }
    public TunnelInfo selectInboundTunnel(Hash destination) { return null; } 
    public TunnelInfo selectOutboundTunnel() { return null; }
    public TunnelInfo selectOutboundTunnel(Hash destination) { return null; }
    public boolean isInUse(Hash peer) { return false; }
    public int getParticipatingCount() { return 0; }
    public int getFreeTunnelCount() { return 0; }
    public int getOutboundTunnelCount() { return 0; }
    public long getLastParticipatingExpiration() { return -1; }
    public void buildTunnels(Destination client, ClientTunnelSettings settings) {}
    public TunnelPoolSettings getInboundSettings() { return null; }
    public TunnelPoolSettings getOutboundSettings() { return null; }
    public TunnelPoolSettings getInboundSettings(Hash client) { return null; }
    public TunnelPoolSettings getOutboundSettings(Hash client) { return null; }
    public void setInboundSettings(TunnelPoolSettings settings) {}
    public void setOutboundSettings(TunnelPoolSettings settings) {}
    public void setInboundSettings(Hash client, TunnelPoolSettings settings) {}
    public void setOutboundSettings(Hash client, TunnelPoolSettings settings) {}
    
    public void renderStatusHTML(Writer out) throws IOException {}
    public void restart() {}
    public void shutdown() {}
    public void startup() {}
}
