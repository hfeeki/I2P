package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Writer;

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;

/**
 * Build and maintain tunnels throughout the network.
 *
 */ 
class DummyTunnelManagerFacade implements TunnelManagerFacade {
    
    public TunnelInfo getTunnelInfo(TunnelId id) { return null; }
    public TunnelInfo selectInboundTunnel() { return null; }
    public TunnelInfo selectInboundTunnel(Hash destination) { return null; } 
    public TunnelInfo selectOutboundTunnel() { return null; }
    public TunnelInfo selectOutboundTunnel(Hash destination) { return null; }
    public boolean isInUse(Hash peer) { return false; }
    public boolean isValidTunnel(Hash client, TunnelInfo tunnel) { return false; }
    public int getParticipatingCount() { return 0; }
    public int getFreeTunnelCount() { return 0; }
    public int getOutboundTunnelCount() { return 0; }
    public int getInboundClientTunnelCount() { return 0; }
    public int getOutboundClientTunnelCount() { return 0; }
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
    public int getInboundBuildQueueSize() { return 0; }
    
    public void renderStatusHTML(Writer out) throws IOException {}
    public void restart() {}
    public void shutdown() {}
    public void startup() {}
}
