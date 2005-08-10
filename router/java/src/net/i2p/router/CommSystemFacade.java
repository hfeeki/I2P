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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages the communication subsystem between peers, including connections, 
 * listeners, transports, connection keys, etc.
 *
 */ 
public abstract class CommSystemFacade implements Service {
    public abstract void processMessage(OutNetMessage msg);
    
    public void renderStatusHTML(Writer out) throws IOException { }
    
    /** Create the set of RouterAddress structures based on the router's config */
    public Set createAddresses() { return new HashSet(); }
    
    public int countActivePeers() { return 0; }
    public List getMostRecentErrorMessages() { return Collections.EMPTY_LIST; }
    
    /**
     * Determine under what conditions we are remotely reachable.
     *
     */
    public short getReachabilityStatus() { return STATUS_OK; }
    public void recheckReachability() {}
    
    /** 
     * We are able to receive unsolicited connections
     */
    public static final short STATUS_OK = 0;
    /**
     * We are behind a symmetric NAT which will make our 'from' address look 
     * differently when we talk to multiple people
     *
     */
    public static final short STATUS_DIFFERENT = 1;
    /**
     * We are able to talk to peers that we initiate communication with, but
     * cannot receive unsolicited connections
     */
    public static final short STATUS_REJECT_UNSOLICITED = 2;
    /**
     * Our reachability is unknown
     */
    public static final short STATUS_UNKNOWN = 3;
    
}

class DummyCommSystemFacade extends CommSystemFacade {
    public void shutdown() {}
    public void startup() {}
    public void restart() {}
    public void processMessage(OutNetMessage msg) { }    
}
