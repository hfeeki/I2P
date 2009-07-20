package net.i2p.router.peermanager;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.router.PeerSelectionCriteria;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.util.Log;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;

/**
 * Manage the current state of the statistics
 *
 * All the capabilities methods appear to be almost unused -
 * TunnelPeerSelector just looks for unreachables, and that's it?
 * If so, a lot of this can go away, including the array of 26 ArrayLists,
 * and a lot of synchronization on _capabilitiesByPeer.
 *
 * We don't trust any published capabilities except for 'K' and 'U'.
 * This should be cleaned up.
 *
 * setCapabilities() and removeCapabilities() can just add/remove the profile and that's it.
 *
 */
class PeerManager {
    private Log _log;
    private RouterContext _context;
    private ProfileOrganizer _organizer;
    private ProfilePersistenceHelper _persistenceHelper;
    private List _peersByCapability[];
    private final Map _capabilitiesByPeer;
    
    public PeerManager(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(PeerManager.class);
        _persistenceHelper = new ProfilePersistenceHelper(context);
        _organizer = context.profileOrganizer();
        _organizer.setUs(context.routerHash());
        _capabilitiesByPeer = new HashMap(128);
        _peersByCapability = new List[26];
        for (int i = 0; i < _peersByCapability.length; i++)
            _peersByCapability[i] = new ArrayList(64);
        loadProfiles();
        ////_context.jobQueue().addJob(new EvaluateProfilesJob(_context));
        SimpleScheduler.getInstance().addPeriodicEvent(new Reorg(), 0, 45*1000);
        //_context.jobQueue().addJob(new PersistProfilesJob(_context, this));
    }
    
    private class Reorg implements SimpleTimer.TimedEvent {
        public void timeReached() {
            try {
                _organizer.reorganize(true);
            } catch (Throwable t) {
                _log.log(Log.CRIT, "Error evaluating profiles", t);
            }
        }
    }
    
    void storeProfiles() {
        Set peers = selectPeers();
        for (Iterator iter = peers.iterator(); iter.hasNext(); ) {
            Hash peer = (Hash)iter.next();
            storeProfile(peer);
        }
    }
    Set selectPeers() {
        return _organizer.selectAllPeers();
    }
    void storeProfile(Hash peer) {
        if (peer == null) return;
        PeerProfile prof = _organizer.getProfile(peer);
        if (prof == null) return;
        if (true)
            _persistenceHelper.writeProfile(prof);
    }
    void loadProfiles() {
        Set profiles = _persistenceHelper.readProfiles();
        for (Iterator iter = profiles.iterator(); iter.hasNext();) {
            PeerProfile prof = (PeerProfile)iter.next();
            if (prof != null) {
                _organizer.addProfile(prof);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Profile for " + prof.getPeer().toBase64() + " loaded");
            }
        }
    }
    
    /**
     * Find some peers that meet the criteria and we have the netDb info for locally
     *
     */
    List selectPeers(PeerSelectionCriteria criteria) {
        Set peers = new HashSet(criteria.getMinimumRequired());
        Set exclude = new HashSet(1);
        exclude.add(_context.routerHash());
        switch (criteria.getPurpose()) {
            case PeerSelectionCriteria.PURPOSE_TEST:
                // for now, the peers we test will be the reliable ones
                //_organizer.selectWellIntegratedPeers(criteria.getMinimumRequired(), exclude, curVals);

                // The PeerTestJob does only run every 5 minutes, but
                // this was helping drive us to connection limits, let's leave the exploration
                // to the ExploratoryPeerSelector, which will restrict to connected peers
                // when we get close to the limit. So let's stick with connected peers here.
                // Todo: what's the point of the PeerTestJob anyway?
                //_organizer.selectNotFailingPeers(criteria.getMinimumRequired(), exclude, peers);
                _organizer.selectActiveNotFailingPeers(criteria.getMinimumRequired(), exclude, peers);
                break;
            case PeerSelectionCriteria.PURPOSE_TUNNEL:
                // pull all of the fast ones, regardless of how many we 
                // want - we'll whittle them down later (40 lines from now)
                // int num = _organizer.countFastPeers();
                // if (num <= 0) 
                //    num = criteria.getMaximumRequired();
                // _organizer.selectFastPeers(num, exclude, curVals);
                _organizer.selectFastPeers(criteria.getMaximumRequired(), exclude, peers);
                break;
            case PeerSelectionCriteria.PURPOSE_SOURCE_ROUTE:
                _organizer.selectHighCapacityPeers(criteria.getMinimumRequired(), exclude, peers);
                break;
            case PeerSelectionCriteria.PURPOSE_GARLIC:
                _organizer.selectHighCapacityPeers(criteria.getMinimumRequired(), exclude, peers);
                break;
            default:
                break;
        }
        if (peers.size() <= 0) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("We ran out of peers when looking for reachable ones after finding " 
                          + peers.size() + " with "
                          + _organizer.countWellIntegratedPeers() + "/" 
                          + _organizer.countHighCapacityPeers() + "/" 
                          + _organizer.countFastPeers() + " integrated/high capacity/fast peers");
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Peers selected: " + peers);
        return new ArrayList(peers);
    }
    
    public void setCapabilities(Hash peer, String caps) { 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Setting capabilities for " + peer.toBase64() + " to " + caps);
        if (caps != null) caps = caps.toLowerCase();
        synchronized (_capabilitiesByPeer) {
            String oldCaps = null;
            if (caps != null)
                oldCaps = (String)_capabilitiesByPeer.put(peer, caps);
            else
                oldCaps = (String)_capabilitiesByPeer.remove(peer);
            
            if (oldCaps != null) {
                for (int i = 0; i < oldCaps.length(); i++) {
                    char c = oldCaps.charAt(i);
                    if ( (caps == null) || (caps.indexOf(c) < 0) ) {
                        List peers = locked_getPeers(c);
                        if (peers != null)
                            peers.remove(peer);
                    }
                }
            }
            if (caps != null) {
                for (int i = 0; i < caps.length(); i++) {
                    char c = caps.charAt(i);
                    if ( (oldCaps != null) && (oldCaps.indexOf(c) >= 0) )
                        continue;
                    List peers = locked_getPeers(c);
                    if ( (peers != null) && (!peers.contains(peer)) )
                        peers.add(peer);
                }
            }
        }
    }
    
    private List locked_getPeers(char c) {
        c = Character.toLowerCase(c);
        int i = c - 'a';
        if ( (i < 0) || (i >= _peersByCapability.length) ) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Invalid capability " + c + " (" + i + ")");
            return null;
        }
        return _peersByCapability[i];
    }
    
    public void removeCapabilities(Hash peer) { 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Removing capabilities from " + peer.toBase64());
        synchronized (_capabilitiesByPeer) {
            String oldCaps = (String)_capabilitiesByPeer.remove(peer);
            if (oldCaps != null) {
                for (int i = 0; i < oldCaps.length(); i++) {
                    char c = oldCaps.charAt(i);
                    List peers = locked_getPeers(c);
                    if (peers != null)
                        peers.remove(peer);
                }
            }
        }
    }
    public Hash selectRandomByCapability(char capability) { 
        int index = _context.random().nextInt(Integer.MAX_VALUE);
        synchronized (_capabilitiesByPeer) {
            List peers = locked_getPeers(capability);
            if ( (peers != null) && (peers.size() > 0) ) {
                index = index % peers.size();
                return (Hash)peers.get(index);
            }
        }
        return null;
    }
    public List getPeersByCapability(char capability) { 
        if (false) {
            synchronized (_capabilitiesByPeer) {
                List peers = locked_getPeers(capability);
                if (peers != null)
                    return new ArrayList(peers);
            }
            return null;
        } else {
            FloodfillNetworkDatabaseFacade f = (FloodfillNetworkDatabaseFacade)_context.netDb();
            List routerInfos = f.getKnownRouterData();
            List rv = new ArrayList();
            for (Iterator iter = routerInfos.iterator(); iter.hasNext(); ) {
                RouterInfo ri = (RouterInfo)iter.next();
                String caps = ri.getCapabilities();
                if (caps.indexOf(capability) >= 0)
                    rv.add(ri.getIdentity().calculateHash());
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Peers with capacity " + capability + ": " + rv.size());
            return rv;
        }
    }

    public void renderStatusHTML(Writer out) throws IOException { 
        _organizer.renderStatusHTML(out); 
    }
}
