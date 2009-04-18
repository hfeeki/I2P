package net.i2p.router.peermanager;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.Hash;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterInfo;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.pool.TunnelPeerSelector;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

/**
 * Keep the peer profiles organized according to the tiered model.  This does not
 * actively update anything - the reorganize() method should be called periodically
 * to recalculate thresholds and move profiles into the appropriate tiers, and addProfile()
 * should be used to add new profiles (placing them into the appropriate groupings).
 */
public class ProfileOrganizer {
    private Log _log;
    private RouterContext _context;
    /** H(routerIdentity) to PeerProfile for all peers that are fast and high capacity*/
    private Map _fastPeers;
    /** H(routerIdentity) to PeerProfile for all peers that have high capacities */
    private Map _highCapacityPeers;
    /** H(routerIdentity) to PeerProfile for all peers that well integrated into the network and not failing horribly */
    private Map _wellIntegratedPeers;
    /** H(routerIdentity) to PeerProfile for all peers that are not failing horribly */
    private Map _notFailingPeers;
    /** H(routerIdnetity), containing elements in _notFailingPeers */
    private List _notFailingPeersList;
    /** H(routerIdentity) to PeerProfile for all peers that ARE failing horribly (but that we haven't dropped reference to yet) */
    private Map _failingPeers;
    /** who are we? */
    private Hash _us;
    private ProfilePersistenceHelper _persistenceHelper;
    
    /** PeerProfile objects for all peers profiled, orderd by the ones with the highest capacity first */
    private Set _strictCapacityOrder;
    
    /** threshold speed value, seperating fast from slow */
    private double _thresholdSpeedValue;
    /** threshold reliability value, seperating reliable from unreliable */
    private double _thresholdCapacityValue;
    /** integration value, seperating well integrated from not well integrated */
    private double _thresholdIntegrationValue;
    
    private InverseCapacityComparator _comp;

    /**
     * Defines the minimum number of 'fast' peers that the organizer should select.  See
     * {@link ProfileOrganizer#getMinimumFastPeers}
     *
     */
    public static final String PROP_MINIMUM_FAST_PEERS = "profileOrganizer.minFastPeers";
    public static final int DEFAULT_MINIMUM_FAST_PEERS = 8;
    /**
     * Defines the minimum number of 'high capacity' peers that the organizer should 
     * select when using the mean - if less than this many are available, select the 
     * capacity by the median.  
     *
     */
    public static final String PROP_MINIMUM_HIGH_CAPACITY_PEERS = "profileOrganizer.minHighCapacityPeers";
    public static final int DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS = 10;
    
    /** synchronized against this lock when updating the tier that peers are located in (and when fetching them from a peer) */
    private Object _reorganizeLock = new Object();
    
    /** incredibly weak PRNG, just used for shuffling peers.  no need to waste the real PRNG on this */
    private Random _random = new Random();
    
    public ProfileOrganizer(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(ProfileOrganizer.class);
        _comp = new InverseCapacityComparator();
        _fastPeers = new HashMap(16);
        _highCapacityPeers = new HashMap(16);
        _wellIntegratedPeers = new HashMap(16);
        _notFailingPeers = new HashMap(64);
        _notFailingPeersList = new ArrayList(64);
        _failingPeers = new HashMap(16);
        _strictCapacityOrder = new TreeSet(_comp);
        _thresholdSpeedValue = 0.0d;
        _thresholdCapacityValue = 0.0d;
        _thresholdIntegrationValue = 0.0d;
        _persistenceHelper = new ProfilePersistenceHelper(_context);
        
        _context.statManager().createRateStat("peer.profileSortTime", "How long the reorg takes sorting peers", "Peers", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("peer.profileCoalesceTime", "How long the reorg takes coalescing peer stats", "Peers", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("peer.profileThresholdTime", "How long the reorg takes determining the tier thresholds", "Peers", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("peer.profilePlaceTime", "How long the reorg takes placing peers in the tiers", "Peers", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("peer.profileReorgTime", "How long the reorg takes overall", "Peers", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
    }
    
    public void setUs(Hash us) { _us = us; }
    Hash getUs() { return _us; }
    
    public double getSpeedThreshold() { return _thresholdSpeedValue; }
    public double getCapacityThreshold() { return _thresholdCapacityValue; }
    public double getIntegrationThreshold() { return _thresholdIntegrationValue; }
    
    /**
     * Retrieve the profile for the given peer, if one exists (else null)
     *
     */
    public PeerProfile getProfile(Hash peer) {
        synchronized (_reorganizeLock) {
            return locked_getProfile(peer);
        }
    }
    
    /**
     * Add the new profile, returning the old value (or null if no profile existed)
     *
     */
    public PeerProfile addProfile(PeerProfile profile) throws IllegalStateException {
        if ( (profile == null) || (profile.getPeer() == null) ) return null;
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("New profile created for " + profile.getPeer().toBase64());
        
        synchronized (_reorganizeLock) {
            PeerProfile old = locked_getProfile(profile.getPeer());
            profile.coalesceStats();
            locked_placeProfile(profile);
            _strictCapacityOrder.add(profile);
            return old;
        }
    }
    
    public int countFastPeers() { synchronized (_reorganizeLock) { return _fastPeers.size(); } }
    public int countHighCapacityPeers() { synchronized (_reorganizeLock) { return _highCapacityPeers.size(); } }
    public int countWellIntegratedPeers() { synchronized (_reorganizeLock) { return _wellIntegratedPeers.size(); } }
    public int countNotFailingPeers() { synchronized (_reorganizeLock) { return _notFailingPeers.size(); } }
    public int countFailingPeers() { synchronized (_reorganizeLock) { return _failingPeers.size(); } }
    
    public int countActivePeers() {
        synchronized (_reorganizeLock) {
            int activePeers = 0;
            
            long hideBefore = _context.clock().now() - 6*60*60*1000;
            
            for (Iterator iter = _failingPeers.values().iterator(); iter.hasNext(); ) {
                PeerProfile profile = (PeerProfile)iter.next();
                if (profile.getLastSendSuccessful() >= hideBefore)
                    activePeers++;
                else if (profile.getLastHeardFrom() >= hideBefore)
                    activePeers++;
            }
            for (Iterator iter = _notFailingPeers.values().iterator(); iter.hasNext(); ) {
                PeerProfile profile = (PeerProfile)iter.next();
                if (profile.getLastSendSuccessful() >= hideBefore)
                    activePeers++;
                else if (profile.getLastHeardFrom() >= hideBefore)
                    activePeers++;
            }
            
            return activePeers;
        }
    }
    
    public boolean isFast(Hash peer) { synchronized (_reorganizeLock) { return _fastPeers.containsKey(peer); } }
    public boolean isHighCapacity(Hash peer) { synchronized (_reorganizeLock) { return _highCapacityPeers.containsKey(peer); } }
    public boolean isWellIntegrated(Hash peer) { synchronized (_reorganizeLock) { return _wellIntegratedPeers.containsKey(peer); } }
    public boolean isFailing(Hash peer) { synchronized (_reorganizeLock) { return _failingPeers.containsKey(peer); } }
        
    /** 
     * if a peer sends us more than 5 replies in a searchReply that we cannot
     * fetch, stop listening to them.
     *
     */
    private final static int MAX_BAD_REPLIES_PER_HOUR = 5;
    
    /**
     * Does the given peer send us bad replies - either invalid store messages 
     * (expired, corrupt, etc) or unreachable replies (pointing towards routers
     * that don't exist).
     *
     */
    public boolean peerSendsBadReplies(Hash peer) {
        PeerProfile profile = getProfile(peer);
        if (profile != null) {
            RateStat invalidReplyRateStat = profile.getDBHistory().getInvalidReplyRate();
            Rate invalidReplyRate = invalidReplyRateStat.getRate(30*60*1000l);
            if ( (invalidReplyRate.getCurrentTotalValue() > MAX_BAD_REPLIES_PER_HOUR) ||
                 (invalidReplyRate.getLastTotalValue() > MAX_BAD_REPLIES_PER_HOUR) ) {
                return true;
            }
        }
        return false;
    }
    
    public void exportProfile(Hash profile, OutputStream out) throws IOException {
        PeerProfile prof = getProfile(profile);
        if (prof != null)
            _persistenceHelper.writeProfile(prof, out);
    }
    
    public void renderStatusHTML(Writer out) throws IOException {
        ProfileOrganizerRenderer rend = new ProfileOrganizerRenderer(this, _context);
        rend.renderStatusHTML(out);
    }
    
    /**
     * Return a set of Hashes for peers that are both fast and reliable.  If an insufficient
     * number of peers are both fast and reliable, fall back onto high capacity peers, and if that
     * doesn't contain sufficient peers, fall back onto not failing peers, and even THAT doesn't
     * have sufficient peers, fall back onto failing peers.
     *
     * @param howMany how many peers are desired
     * @param exclude set of Hashes for routers that we don't want selected
     * @param matches set to store the return value in
     *
     */
    public void selectFastPeers(int howMany, Set exclude, Set matches) {
        selectFastPeers(howMany, exclude, matches, 0);
    }
    public void selectFastPeers(int howMany, Set exclude, Set matches, int mask) {
        synchronized (_reorganizeLock) {
            locked_selectPeers(_fastPeers, howMany, exclude, matches, mask);
        }
        if (matches.size() < howMany) {
            if (_log.shouldLog(Log.INFO))
                _log.info("selectFastPeers("+howMany+"), not enough fast (" + matches.size() + ") going on to highCap");
            selectHighCapacityPeers(howMany, exclude, matches, mask);
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("selectFastPeers("+howMany+"), found enough fast (" + matches.size() + ")");
        }
        return;
    }
    
    /**
     * Return a set of Hashes for peers that have a high capacity
     *
     */
    public void selectHighCapacityPeers(int howMany, Set exclude, Set matches) {
        selectHighCapacityPeers(howMany, exclude, matches, 0);
    }
    public void selectHighCapacityPeers(int howMany, Set exclude, Set matches, int mask) {
        synchronized (_reorganizeLock) {
            // we only use selectHighCapacityPeers when we are selecting for PURPOSE_TEST
            // or we are falling back due to _fastPeers being too small, so we can always 
            // exclude the fast peers
            /*
            if (exclude == null)
                exclude = new HashSet(_fastPeers.keySet());
            else
                exclude.addAll(_fastPeers.keySet());
             */
            locked_selectPeers(_highCapacityPeers, howMany, exclude, matches, mask);
        }
        if (matches.size() < howMany) {
            if (_log.shouldLog(Log.INFO))
                _log.info("selectHighCap("+howMany+"), not enough fast (" + matches.size() + ") going on to notFailing");
            selectNotFailingPeers(howMany, exclude, matches, mask);
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("selectHighCap("+howMany+"), found enough highCap (" + matches.size() + ")");
        }
        return;
    }
    /**
     * Return a set of Hashes for peers that are well integrated into the network.
     *
     */
    public void selectWellIntegratedPeers(int howMany, Set exclude, Set matches) {
        selectWellIntegratedPeers(howMany, exclude, matches, 0);
    }
    public void selectWellIntegratedPeers(int howMany, Set exclude, Set matches, int mask) {
        synchronized (_reorganizeLock) {
            locked_selectPeers(_wellIntegratedPeers, howMany, exclude, matches, mask);
        }
        if (matches.size() < howMany) {
            if (_log.shouldLog(Log.INFO))
                _log.info("selectWellIntegrated("+howMany+"), not enough integrated (" + matches.size() + ") going on to notFailing");
            selectNotFailingPeers(howMany, exclude, matches, mask);
        } else {            
            if (_log.shouldLog(Log.INFO))
                _log.info("selectWellIntegrated("+howMany+"), found enough well integrated (" + matches.size() + ")");
        }
        
        return;
    }
    /**
     * Return a set of Hashes for peers that are not failing, preferring ones that
     * we are already talking with
     *
     */
    public void selectNotFailingPeers(int howMany, Set exclude, Set matches) {
        selectNotFailingPeers(howMany, exclude, matches, false, 0);
    }
    public void selectNotFailingPeers(int howMany, Set exclude, Set matches, int mask) {
        selectNotFailingPeers(howMany, exclude, matches, false, mask);
    }
    public void selectNotFailingPeers(int howMany, Set exclude, Set matches, boolean onlyNotFailing) {
        selectNotFailingPeers(howMany, exclude, matches, onlyNotFailing, 0);
    }
    /**
     * Return a set of Hashes for peers that are not failing, preferring ones that
     * we are already talking with
     *
     * @param howMany how many peers to find
     * @param exclude what peers to skip (may be null)
     * @param matches set to store the matches in
     * @param onlyNotFailing if true, don't include any high capacity peers
     */
    public void selectNotFailingPeers(int howMany, Set exclude, Set matches, boolean onlyNotFailing, int mask) {
        if (matches.size() < howMany)
            selectAllNotFailingPeers(howMany, exclude, matches, onlyNotFailing, mask);
        return;
    }
    /**
     * Return a set of Hashes for peers that are both not failing and we're actively
     * talking with.
     *
     */
    /*
    private void selectActiveNotFailingPeers(int howMany, Set exclude, Set matches) {
        if (true) {
            selectAllNotFailingPeers(howMany, exclude, matches);
            return;
        }
        // pick out the not-failing peers that we're actively talking with
        if (matches.size() < howMany) {
            synchronized (_reorganizeLock) {
                for (Iterator iter = _notFailingPeers.keySet().iterator(); iter.hasNext(); ) {
                    Hash peer = (Hash)iter.next();
                    if ( (exclude != null) && exclude.contains(peer) ) continue;
                    if (matches.contains(peer)) continue;
                    PeerProfile prof = (PeerProfile)_notFailingPeers.get(peer);
                    if (prof.getIsActive())
                        matches.add(peer);
                    if (matches.size() >= howMany)
                        return;
                }
            }
        }
        // ok, still not enough, pick out the not-failing peers that we aren't talking with
        if (matches.size() < howMany)
            selectAllNotFailingPeers(howMany, exclude, matches);
        return;
    }
    */
    /**
     * Return a set of Hashes for peers that are not failing.
     *
     */
    public void selectAllNotFailingPeers(int howMany, Set exclude, Set matches, boolean onlyNotFailing) {
        selectAllNotFailingPeers(howMany, exclude, matches, onlyNotFailing, 0);
    }
    private void selectAllNotFailingPeers(int howMany, Set exclude, Set matches, boolean onlyNotFailing, int mask) {
        if (matches.size() < howMany) {
            int orig = matches.size();
            int needed = howMany - orig;
            int start = 0;
            List selected = new ArrayList(needed);
            synchronized (_reorganizeLock) {
                // we randomize the whole list when rebuilding it, but randomizing 
                // the entire list on each peer selection is a bit crazy
                start = _context.random().nextInt(_notFailingPeersList.size());
                for (int i = 0; i < _notFailingPeersList.size() && selected.size() < needed; i++) {
                    int curIndex = (i+start) % _notFailingPeersList.size();
                    Hash cur = (Hash)_notFailingPeersList.get(curIndex);
                    if (matches.contains(cur) ||
                        (exclude != null && exclude.contains(cur))) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("matched? " + matches.contains(cur) + " exclude: " + exclude + " cur=" + cur.toBase64());
                        continue;
                    } else if (onlyNotFailing && _highCapacityPeers.containsKey(cur)) {
                        // we dont want the good peers, just random ones
                        continue;
                    } else {
                        if (isSelectable(cur))
                            selected.add(cur);
                        else if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Not selectable: " + cur.toBase64());
                    }
                }
            }
            if (_log.shouldLog(Log.INFO))
                _log.info("Selecting all not failing (strict? " + onlyNotFailing + " start=" + start 
                          + ") found " + selected.size() + " new peers: " + selected + " all=" + _notFailingPeersList.size() + " strict=" + _strictCapacityOrder.size());
            matches.addAll(selected);
        }
        if (matches.size() < howMany) {
            if (_log.shouldLog(Log.INFO))
                _log.info("selectAllNotFailing("+howMany+"), not enough (" + matches.size() + ") going on to failing");
            selectFailingPeers(howMany, exclude, matches);
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("selectAllNotFailing("+howMany+"), enough (" + matches.size() + ")");
        }
        return;
    }
    /**
     * I'm not quite sure why you'd want this... (other than for failover from the better results)
     *
     */
    public void selectFailingPeers(int howMany, Set exclude, Set matches) {
        synchronized (_reorganizeLock) {
            locked_selectPeers(_failingPeers, howMany, exclude, matches);
        }
        return;
    }
    
    /**
     * Get the peers the transport layer thinks are unreachable, and
     * add in the peers with the SSU peer testing bug,
     * and peers requiring introducers.
     *
     */
    public List selectPeersLocallyUnreachable() { 
        List n;
        int count;
        synchronized (_reorganizeLock) {
            count = _notFailingPeers.size();
            n = new ArrayList(_notFailingPeers.keySet());
        }
        List l = new ArrayList(count / 4);
        for (Iterator iter = n.iterator(); iter.hasNext(); ) {
            Hash peer = (Hash)iter.next();
            if (_context.commSystem().wasUnreachable(peer))
                l.add(peer);
            else {
                // Blacklist <= 0.6.1.32 SSU-only peers, they don't know if they are unreachable,
                // and we may not know either if they contacted us first, so assume they are.
                // Also blacklist all peers requiring SSU introducers, because either
                //  a) it's slow; or
                //  b) it doesn't work very often; or
                //  c) in the event they are advertising NTCP, it probably won't work because
                //     they probably don't have a TCP hole punched in their firewall either.
                RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
                if (info != null) {
                    String v = info.getOption("router.version");
                    // this only works if there is no 0.6.1.34!
                    if (v != null && (!v.equals("0.6.1.33")) &&
                        v.startsWith("0.6.1.") && info.getTargetAddress("NTCP") == null)
                        l.add(peer);
                    else {
                        RouterAddress ra = info.getTargetAddress("SSU");
                        // Definitely don't want peers with no SSU address at all
                        if (ra == null) {
                            l.add(peer);
                            continue;
                        }
                        // This is the quick way of doing UDPAddress.getIntroducerCount() > 0
                        Properties props = ra.getOptions();
                        if (props != null && props.getProperty("ihost0") != null)
                            l.add(peer);
                    }
                }
            }
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Unreachable: " + l);
        return l;
    }

    /**
     * Get the peers that have recently rejected us for bandwidth
     * recent == last 20s
     *
     */
    public List selectPeersRecentlyRejecting() { 
        synchronized (_reorganizeLock) {
            long cutoff = _context.clock().now() - (20*1000);
            int count = _notFailingPeers.size();
            List l = new ArrayList(count / 128);
            for (Iterator iter = _notFailingPeers.values().iterator(); iter.hasNext(); ) {
                PeerProfile prof = (PeerProfile) iter.next();
                if (prof.getTunnelHistory().getLastRejectedBandwidth() > cutoff)
                    l.add(prof.getPeer());
            }
            return l;
        }
    }

    /**
     * Find the hashes for all peers we are actively profiling
     *
     */
    public Set selectAllPeers() {
        synchronized (_reorganizeLock) {
            Set allPeers = new HashSet(_failingPeers.size() + _notFailingPeers.size() + _highCapacityPeers.size() + _fastPeers.size());
            allPeers.addAll(_failingPeers.keySet());
            allPeers.addAll(_notFailingPeers.keySet());
            allPeers.addAll(_highCapacityPeers.keySet());
            allPeers.addAll(_fastPeers.keySet());
            return allPeers;
        }
    }
    
    /**
     * Place peers into the correct tier, as well as expand/contract and even drop profiles
     * according to whatever limits are in place.  Peer profiles are not coalesced during
     * this method, but the averages are recalculated.
     *
     */
    public void reorganize() { reorganize(false); }
    public void reorganize(boolean shouldCoalesce) {
        long sortTime = 0;
        int coalesceTime = 0;
        long thresholdTime = 0;
        long placeTime = 0;
        int profileCount = 0;
        
        long uptime = _context.router().getUptime();
        long expireOlderThan = -1;
        if (uptime > 60*60*1000) {
            // drop profiles that we haven't spoken with in 6 hours
            expireOlderThan = _context.clock().now() - 6*60*60*1000;
        }
            
        long start = System.currentTimeMillis();
        synchronized (_reorganizeLock) {
            Set allPeers = _strictCapacityOrder; //new HashSet(_failingPeers.size() + _notFailingPeers.size() + _highCapacityPeers.size() + _fastPeers.size());
            //allPeers.addAll(_failingPeers.values());
            //allPeers.addAll(_notFailingPeers.values());
            //allPeers.addAll(_highCapacityPeers.values());
            //allPeers.addAll(_fastPeers.values());

            Set reordered = new TreeSet(_comp);
            long sortStart = System.currentTimeMillis();
            for (Iterator iter = _strictCapacityOrder.iterator(); iter.hasNext(); ) {
                PeerProfile prof = (PeerProfile)iter.next();
                if ( (expireOlderThan > 0) && (prof.getLastSendSuccessful() <= expireOlderThan) )
                    continue; // drop, but no need to delete, since we don't periodically reread
                
                if (shouldCoalesce) {
                    long coalesceStart = System.currentTimeMillis();
                    prof.coalesceStats();
                    coalesceTime += (int)(System.currentTimeMillis()-coalesceStart);
                }
                reordered.add(prof);
                profileCount++;
            }
            sortTime = System.currentTimeMillis() - sortStart;
            _strictCapacityOrder = reordered;
            
            long thresholdStart = System.currentTimeMillis();
            locked_calculateThresholds(allPeers);
            thresholdTime = System.currentTimeMillis()-thresholdStart;
            
            _failingPeers.clear();
            _fastPeers.clear();
            _highCapacityPeers.clear();
            _notFailingPeers.clear();
            _notFailingPeersList.clear();
            _wellIntegratedPeers.clear();
            
            long placeStart = System.currentTimeMillis();
            
            for (Iterator iter = allPeers.iterator(); iter.hasNext(); ) {
                PeerProfile profile = (PeerProfile)iter.next();
                locked_placeProfile(profile);
            }
            
            locked_unfailAsNecessary();
            locked_promoteFastAsNecessary();

            Collections.shuffle(_notFailingPeersList, _context.random());
            
            placeTime = System.currentTimeMillis()-placeStart;

            if (_log.shouldLog(Log.INFO))
                _log.info("Profiles reorganized.  averages: [integration: " + _thresholdIntegrationValue 
                           + ", capacity: " + _thresholdCapacityValue + ", speed: " + _thresholdSpeedValue + "]");
            /*****
            if (_log.shouldLog(Log.DEBUG)) {
                StringBuffer buf = new StringBuffer(512);
                for (Iterator iter = _strictCapacityOrder.iterator(); iter.hasNext(); ) {
                    PeerProfile prof = (PeerProfile)iter.next();
                    buf.append('[').append(prof.toString()).append('=').append(prof.getCapacityValue()).append("] ");
                }
                _log.debug("Strictly organized (highest capacity first): " + buf.toString());
                _log.debug("fast: " + _fastPeers.values());
            }
            *****/
        }
        
        long total = System.currentTimeMillis()-start;
        _context.statManager().addRateData("peer.profileSortTime", sortTime, profileCount);
        _context.statManager().addRateData("peer.profileCoalesceTime", coalesceTime, profileCount);
        _context.statManager().addRateData("peer.profileThresholdTime", thresholdTime, profileCount);
        _context.statManager().addRateData("peer.profilePlaceTime", placeTime, profileCount);
        _context.statManager().addRateData("peer.profileReorgTime", total, profileCount);
    }
    
    /**
     * As with locked_unfailAsNecessary, I'm not sure how much I like this - if there
     * aren't enough fast peers, move some of the not-so-fast peers into the fast group.
     * This picks the not-so-fast peers based on capacity, not speed, and skips over any
     * failing peers.  Perhaps it should build a seperate strict ordering by speed?  Nah, not
     * worth the maintenance and memory overhead, at least not for now.
     *
     */
    private void locked_promoteFastAsNecessary() {
        int minFastPeers = getMinimumFastPeers();
        int numToPromote = minFastPeers - _fastPeers.size();
        if (numToPromote > 0) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Need to explicitly promote " + numToPromote + " peers to the fast group");
            for (Iterator iter = _strictCapacityOrder.iterator(); iter.hasNext(); ) {
                PeerProfile cur = (PeerProfile)iter.next();
                if ( (!_fastPeers.containsKey(cur.getPeer())) && (!cur.getIsFailing()) ) {
                    if (!isSelectable(cur.getPeer())) {
                        // skip peers we dont have in the netDb
                        // if (_log.shouldLog(Log.INFO))   
                        //     _log.info("skip unknown peer from fast promotion: " + cur.getPeer().toBase64());
                        continue;
                    }
                    if (!cur.getIsActive()) {
                        // skip inactive
                        // if (_log.shouldLog(Log.INFO))
                        //     _log.info("skip inactive peer from fast promotion: " + cur.getPeer().toBase64());
                        continue;
                    }
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Fast promoting: " + cur.getPeer().toBase64());
                    _fastPeers.put(cur.getPeer(), cur);
                    // no need to remove it from any of the other groups, since if it is 
                    // fast, it has a high capacity, and it is not failing
                    numToPromote--;
                    if (numToPromote <= 0)
                        break;
                }
            }
        }
        return;
    }
    
    /** how many not failing/active peers must we have? */
    private final static int MIN_NOT_FAILING_ACTIVE = 3;
    /**
     * I'm not sure how much I dislike the following - if there aren't enough
     * active and not-failing peers, pick the most reliable active peers and
     * override their 'failing' flag, resorting them into the not-failing buckets
     *
     */
    private void locked_unfailAsNecessary() {
        int notFailingActive = 0;
        for (Iterator iter = _notFailingPeers.keySet().iterator(); iter.hasNext(); ) {
            Hash key = (Hash)iter.next();
            PeerProfile peer = (PeerProfile)_notFailingPeers.get(key);
            if (peer.getIsActive())
                notFailingActive++;
            if (notFailingActive >= MIN_NOT_FAILING_ACTIVE) {
                // we've got enough, no need to try further
                return;
            }
        }
        
        // we dont have enough, lets unfail our best ones remaining
        int needToUnfail = MIN_NOT_FAILING_ACTIVE - notFailingActive;
        if (needToUnfail > 0) {
            int unfailed = 0;
            for (Iterator iter = _strictCapacityOrder.iterator(); iter.hasNext(); ) {
                PeerProfile best = (PeerProfile)iter.next();
                if ( (best.getIsActive()) && (best.getIsFailing()) ) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("All peers were failing, so we have overridden the failing flag for one of the most reliable active peers (" + best.getPeer().toBase64() + ")");
                    best.setIsFailing(false);
                    locked_placeProfile(best);
                    unfailed++;
                }
                if (unfailed >= needToUnfail)
                    break;
            }
        }
    }
    
    ////////
    // no more public stuff below
    ////////
    
    /**
     * Update the thresholds based on the profiles in this set.  currently
     * implements the capacity threshold based on the mean capacity of active
     * and nonfailing peers (falling back on the median if that results in too
     * few peers.  We then use the median speed from that group to define the 
     * speed threshold, and use the mean integration value from the 
     * high capacity group to define the integration threshold.
     *
     */
    private void locked_calculateThresholds(Set allPeers) {
        double totalCapacity = 0;
        double totalIntegration = 0;
        Set reordered = new TreeSet(_comp);
        for (Iterator iter = allPeers.iterator(); iter.hasNext(); ) {
            PeerProfile profile = (PeerProfile)iter.next();
            
            if (_us.equals(profile.getPeer())) continue;
            
            // only take into account active peers that aren't failing
            if (profile.getIsFailing() || (!profile.getIsActive()))
                continue;
        
            // dont bother trying to make sense of things below the baseline
            // otoh, keep them in the threshold calculation, so we can adapt
            ////if (profile.getCapacityValue() <= CapacityCalculator.GROWTH_FACTOR)
            ////    continue;
            
            totalCapacity += profile.getCapacityValue();
            totalIntegration += profile.getIntegrationValue();
            reordered.add(profile);
        }
        
        locked_calculateCapacityThreshold(totalCapacity, reordered);
        locked_calculateSpeedThreshold(reordered);
        
        if (totalIntegration > 0)
            _thresholdIntegrationValue = 1.0d * avg(totalIntegration, reordered.size());
        else    // Make nobody rather than everybody well-integrated
            _thresholdIntegrationValue = 1.0d;
    }
    
    /**
     * Update the _thresholdCapacityValue by using a few simple formulas run 
     * against the specified peers.  Ideally, we set the threshold capacity to
     * the mean, as long as that gives us enough peers and is greater than the
     * median.
     *
     * @param reordered ordered set of PeerProfile objects, ordered by capacity
     *                  (highest first) for active nonfailing peers whose 
     *                  capacity is greater than the growth factor
     */
    private void locked_calculateCapacityThreshold(double totalCapacity, Set reordered) {
        int numNotFailing = reordered.size();
        
        double meanCapacity = avg(totalCapacity, numNotFailing);
        
        int minHighCapacityPeers = getMinimumHighCapacityPeers();
        
        int numExceedingMean = 0;
        double thresholdAtMedian = 0;
        double thresholdAtMinHighCap = 0;
        double thresholdAtLowest = CapacityCalculator.GROWTH_FACTOR;
        int cur = 0;
        for (Iterator iter = reordered.iterator(); iter.hasNext(); ) {
            PeerProfile profile = (PeerProfile)iter.next();
            double val = profile.getCapacityValue();
            if (val > meanCapacity)
                numExceedingMean++;
            if (cur == reordered.size()/2)
                thresholdAtMedian = val;
            if (cur == minHighCapacityPeers - 1)
                thresholdAtMinHighCap = val;
            if (cur == reordered.size() -1)
                thresholdAtLowest = val;
            cur++;
        }
        
        if (numExceedingMean >= minHighCapacityPeers) {
            // our average is doing well (growing, not recovering from failures)
            if (_log.shouldLog(Log.INFO))
                _log.info("Our average capacity is doing well [" + meanCapacity 
                          + "], and includes " + numExceedingMean);
            _thresholdCapacityValue = meanCapacity;
        } else if (meanCapacity > thresholdAtMedian &&
                   reordered.size()/2 > minHighCapacityPeers) {
            // avg > median, get the min High Cap peers
            if (_log.shouldLog(Log.INFO))
                _log.info("Our average capacity [" + meanCapacity + "] is greater than the median,"
                          + " so threshold is that reqd to get the min high cap peers " + thresholdAtMinHighCap);
            _thresholdCapacityValue = thresholdAtMinHighCap;
        } else if (reordered.size()/2 >= minHighCapacityPeers) {
            // ok mean is skewed low, but we still have enough to use the median
            // We really don't want to be here, since the default is 5.0 and the median
            // is inevitably 5.01 or so.
            if (_log.shouldLog(Log.INFO))
                _log.info("Our average capacity [" + meanCapacity + "] is skewed under the median,"
                          + " so use the median threshold " + thresholdAtMedian);
            _thresholdCapacityValue = thresholdAtMedian;
        } else {
            // our average is doing well, but not enough peers
            if (_log.shouldLog(Log.INFO))
                _log.info("Our average capacity is doing well [" + meanCapacity 
                          + "], but there aren't enough of them " + numExceedingMean);
            _thresholdCapacityValue = Math.max(thresholdAtMinHighCap, thresholdAtLowest);
        }
        
        // the base growth factor is the value we give to new routers that we don't
        // know anything about.  dont go under that limit unless you want to expose
        // the selection to simple ident flooding attacks
        if (_thresholdCapacityValue <= CapacityCalculator.GROWTH_FACTOR)
            _thresholdCapacityValue = CapacityCalculator.GROWTH_FACTOR + 0.0001;
    }
    
    /**
     * Update the _thresholdSpeedValue by calculating the median speed of all
     * high capacity peers. 
     *
     * @param reordered ordered set of PeerProfile objects, ordered by capacity
     *                  (highest first) for active nonfailing peers
     */
    private void locked_calculateSpeedThreshold(Set reordered) {
        if (true) {
            locked_calculateSpeedThresholdMean(reordered);
            return;
        }
        Set speeds = new TreeSet();
        for (Iterator iter = reordered.iterator(); iter.hasNext(); ) {
            PeerProfile profile = (PeerProfile)iter.next();
            if (profile.getCapacityValue() >= _thresholdCapacityValue) {
                // duplicates being clobbered is fine by us
                speeds.add(new Double(0-profile.getSpeedValue()));
            } else {
                // its ordered
                break;
            }
        }

        // calc the median speed of high capacity peers
        int i = 0;
        for (Iterator iter = speeds.iterator(); iter.hasNext(); i++) {
            Double speed = (Double)iter.next();
            if (i >= (speeds.size() / 2)) {
                _thresholdSpeedValue = 0-speed.doubleValue();
                break;
            }
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Threshold value for speed: " + _thresholdSpeedValue + " out of speeds: " + speeds);
    }
    
    private void locked_calculateSpeedThresholdMean(Set reordered) {
        double total = 0;
        int count = 0;
        for (Iterator iter = reordered.iterator(); iter.hasNext(); ) {
            PeerProfile profile = (PeerProfile)iter.next();
            if (profile.getCapacityValue() >= _thresholdCapacityValue) {
                // duplicates being clobbered is fine by us
                total += profile.getSpeedValue();
                count++;
            } else {
                // its ordered
                break;
            }
        }

        if (count > 0)
            _thresholdSpeedValue = total / count;
        if (_log.shouldLog(Log.INFO))
            _log.info("Threshold value for speed: " + _thresholdSpeedValue + " out of speeds: " + count);
    }
    
    
    /** simple average, or 0 if NaN */
    private final static double avg(double total, double quantity) {
        if ( (total > 0) && (quantity > 0) )
            return total/quantity;
        else
            return 0.0d;
    }
    
    /** called after locking the reorganizeLock */
    private PeerProfile locked_getProfile(Hash peer) {
        PeerProfile cur = (PeerProfile)_notFailingPeers.get(peer);
        if (cur != null) 
            return cur;
        cur = (PeerProfile)_failingPeers.get(peer);
        return cur;
    }
    
    /**
     * Select peers from the peer mapping, excluding appropriately and increasing the
     * matches set until it has howMany elements in it.
     *
     */
    private void locked_selectPeers(Map peers, int howMany, Set toExclude, Set matches) {
        locked_selectPeers(peers, howMany, toExclude, matches, 0);
    }
    private void locked_selectPeers(Map peers, int howMany, Set toExclude, Set matches, int mask) {
        List all = new ArrayList(peers.keySet());
        if (toExclude != null)
            all.removeAll(toExclude);
        
        all.removeAll(matches);
        all.remove(_us);
        Collections.shuffle(all, _random);
        for (int i = 0; (matches.size() < howMany) && (i < all.size()); i++) {
            Hash peer = (Hash)all.get(i);
            boolean ok = isSelectable(peer);
            if (ok) {
                ok = mask <= 0 || notRestricted(peer, matches, mask);
                if ((!ok) && _log.shouldLog(Log.WARN))
                    _log.warn("IP restriction prevents " + peer + " from joining " + matches);
            }
            if (ok)
                matches.add(peer);
            else
                matches.remove(peer);
        }
    }
    
    /**
     * Does the peer's IP address NOT match the IP address of any peer already in the set,
     * on any transport, within a given mask?
     * mask is 1-4 (number of bytes to match) or 0 to disable
     * Perhaps rewrite this to just make a set of all the IP addresses rather than loop.
     */
    private boolean notRestricted(Hash peer, Set matches, int mask) {
        if (mask <= 0) return true;
        if (matches.size() <= 0) return true;
        RouterInfo pinfo = _context.netDb().lookupRouterInfoLocally(peer);
        if (pinfo == null) return false;
        Set paddr = pinfo.getAddresses();
        if (paddr == null || paddr.size() == 0)
            return false;
        List pladdr = new ArrayList(paddr);
        List lmatches = new ArrayList(matches);
        // for each match
        for (int i = 0; i < matches.size(); i++) {
            RouterInfo minfo = _context.netDb().lookupRouterInfoLocally((Hash) lmatches.get(i));
            if (minfo == null) continue;
            Set maddr = minfo.getAddresses();
            if (maddr == null || maddr.size() == 0)
                continue;
            List mladdr = new ArrayList(maddr);
            String oldphost = null;
            // for each peer address
            for (int j = 0; j < paddr.size(); j++) {
                RouterAddress pa = (RouterAddress) pladdr.get(j);
                if (pa == null) continue;
                Properties pprops = pa.getOptions();
                if (pprops == null) continue;
                String phost = pprops.getProperty("host");
                if (phost == null) continue;
                if (oldphost != null && oldphost.equals(phost)) continue;
                oldphost = phost;
                InetAddress pi;
                try {
                    pi = InetAddress.getByName(phost);
                } catch (UnknownHostException uhe) {
                    continue;
                }
                if (pi == null) continue;
                byte[] pib = pi.getAddress();
                String oldmhost = null;
                // for each match address
                for (int k = 0; k < maddr.size(); k++) {
                    RouterAddress ma = (RouterAddress) mladdr.get(k);
                    if (ma == null) continue;
                    Properties mprops = ma.getOptions();
                    if (mprops == null) continue;
                    String mhost = mprops.getProperty("host");
                    if (mhost == null) continue;
                    if (oldmhost != null && oldmhost.equals(mhost)) continue;
                    oldmhost = mhost;
                    InetAddress mi;
                    try {
                        mi = InetAddress.getByName(mhost);
                    } catch (UnknownHostException uhe) {
                        continue;
                    }
                    if (mi == null) continue;
                    byte[] mib = mi.getAddress();
                    // assume ipv4, compare 1 to 4 bytes
                    // log.info("Comparing " + pi + " with " + mi);
                    for (int m = 0; m < mask; m++) {
                        if (pib[m] != mib[m])
                            break;
                        if (m == mask-1)
                            return false; // IP match
                    }
                }
            }
        }
        return true;
    }

    public boolean isSelectable(Hash peer) {
        NetworkDatabaseFacade netDb = _context.netDb();
        // the CLI shouldn't depend upon the netDb
        if (netDb == null) return true;
        if (_context.router() == null) return true;
        if ( (_context.shitlist() != null) && (_context.shitlist().isShitlisted(peer)) ) {
            // if (_log.shouldLog(Log.DEBUG))
            //     _log.debug("Peer " + peer.toBase64() + " is shitlisted, dont select it");
            return false; // never select a shitlisted peer
        }
            
        RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
        if (null != info) {
            if (info.getIdentity().isHidden()) {
               if (_log.shouldLog(Log.WARN))
                    _log.warn("Peer " + peer.toBase64() + " is marked as hidden, disallowing its use");
                return false;
            } else {
                boolean exclude = TunnelPeerSelector.shouldExclude(_context, info);
                if (exclude) {
                    // if (_log.shouldLog(Log.WARN))
                    //     _log.warn("Peer " + peer.toBase64() + " has capabilities or other stats suggesting we avoid it");
                    return false;
                } else {
                    // if (_log.shouldLog(Log.INFO))
                    //     _log.info("Peer " + peer.toBase64() + " is locally known, allowing its use");
                    return true;
                }
            }
        } else {
            // if (_log.shouldLog(Log.WARN))
            //    _log.warn("Peer " + peer.toBase64() + " is NOT locally known, disallowing its use");
            return false;
        }
    }
    
    /**
     * called after locking the reorganizeLock, place the profile in the appropriate tier.
     * This is where we implement the (betterThanAverage ? goToTierX : goToTierY) algorithms
     *
     */
    private void locked_placeProfile(PeerProfile profile) {
        if (profile.getIsFailing()) {
            if (!shouldDrop(profile))
                _failingPeers.put(profile.getPeer(), profile);
            _fastPeers.remove(profile.getPeer());
            _highCapacityPeers.remove(profile.getPeer());
            _wellIntegratedPeers.remove(profile.getPeer());
            _notFailingPeers.remove(profile.getPeer());
            _notFailingPeersList.remove(profile.getPeer());
        } else {
            _failingPeers.remove(profile.getPeer());
            _fastPeers.remove(profile.getPeer());
            _highCapacityPeers.remove(profile.getPeer());
            _wellIntegratedPeers.remove(profile.getPeer());
            
            _notFailingPeers.put(profile.getPeer(), profile);
            _notFailingPeersList.add(profile.getPeer());
            // if not selectable for a tunnel (shitlisted for example),
            // don't allow them in the high-cap pool, what would the point of that be?
            if (_thresholdCapacityValue <= profile.getCapacityValue() &&
                isSelectable(profile.getPeer())) {
                _highCapacityPeers.put(profile.getPeer(), profile);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("High capacity: \t" + profile.getPeer().toBase64());
                if (_thresholdSpeedValue <= profile.getSpeedValue()) {
                    if (!profile.getIsActive()) {
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Skipping fast mark [!active] for " + profile.getPeer().toBase64());
                    } else {
                        _fastPeers.put(profile.getPeer(), profile);
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Fast: \t" + profile.getPeer().toBase64());
                    }
                }
                
            } else {
                // not high capacity, but not failing (yet)
            }
            // We aren't using the well-integrated list yet...
            // But by observation, the floodfill peers are often not in the
            // high-capacity group, so let's not require a peer to be high-capactiy
            // to call him well-integrated.
            // This could be used later to see if a floodfill peer is for real.
            if (_thresholdIntegrationValue <= profile.getIntegrationValue()) {
                _wellIntegratedPeers.put(profile.getPeer(), profile);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Integrated: \t" + profile.getPeer().toBase64());
            }
        }
    }
    
    /**
     * This is where we determine whether a failing peer is so poor and we're so overloaded
     * that we just want to forget they exist.  This algorithm won't need to be implemented until
     * after I2P 1.0, most likely, since we should be able to handle thousands of peers profiled
     * without ejecting any of them, but anyway, this is how we'd do it.  Most likely.
     *
     */
    private boolean shouldDrop(PeerProfile profile) { return false; }
    
    /**
     * Defines the minimum number of 'fast' peers that the organizer should select.  If
     * the profile calculators derive a threshold that does not select at least this many peers,
     * the threshold will be overridden to make sure this many peers are in the fast+reliable group.
     * This parameter should help deal with a lack of diversity in the tunnels created when some 
     * peers are particularly fast.
     *
     * @return minimum number of peers to be placed in the 'fast' group
     */
    protected int getMinimumFastPeers() {
        return _context.getProperty(PROP_MINIMUM_FAST_PEERS, DEFAULT_MINIMUM_FAST_PEERS);
    }
    
    
    /**
     * Defines the minimum number of 'fast' peers that the organizer should select.  If
     * the profile calculators derive a threshold that does not select at least this many peers,
     * the threshold will be overridden to make sure this many peers are in the fast+reliable group.
     * This parameter should help deal with a lack of diversity in the tunnels created when some 
     * peers are particularly fast.
     *
     * @return minimum number of peers to be placed in the 'fast' group
     */
    protected int getMinimumHighCapacityPeers() {
        return _context.getProperty(PROP_MINIMUM_HIGH_CAPACITY_PEERS, DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS);
    }
    
    private final static DecimalFormat _fmt = new DecimalFormat("###,##0.00", new DecimalFormatSymbols(Locale.UK));
    private final static String num(double num) { synchronized (_fmt) { return _fmt.format(num); } }
    
    /**
     * Read in all of the profiles specified and print out 
     * their calculated values.  Usage: <pre>
     *  ProfileOrganizer [filename]*
     * </pre>
     */
    public static void main(String args[]) {
        RouterContext ctx = new RouterContext(null); // new net.i2p.router.Router());
        ProfileOrganizer organizer = new ProfileOrganizer(ctx);
        organizer.setUs(Hash.FAKE_HASH);
        ProfilePersistenceHelper helper = new ProfilePersistenceHelper(ctx);
        for (int i = 0; i < args.length; i++) {
            PeerProfile profile = helper.readProfile(new java.io.File(args[i]));
            if (profile == null) {
                System.err.println("Could not load profile " + args[i]);
                continue;
            }
            organizer.addProfile(profile);
        }
        organizer.reorganize();
        DecimalFormat fmt = new DecimalFormat("0,000.0");
        fmt.setPositivePrefix("+");
        
        for (Iterator iter = organizer.selectAllPeers().iterator(); iter.hasNext(); ) {
            Hash peer = (Hash)iter.next();
            PeerProfile profile = organizer.getProfile(peer);
            if (!profile.getIsActive()) {
                System.out.println("Peer " + profile.getPeer().toBase64().substring(0,4) 
                           + " [" + (organizer.isFast(peer) ? "IF+R" : 
                                     organizer.isHighCapacity(peer) ? "IR  " :
                                     organizer.isFailing(peer) ? "IX  " : "I   ") + "]: "
                           + "\t Speed:\t" + fmt.format(profile.getSpeedValue())
                           + " Capacity:\t" + fmt.format(profile.getCapacityValue())
                           + " Integration:\t" + fmt.format(profile.getIntegrationValue())
                           + " Active?\t" + profile.getIsActive() 
                           + " Failing?\t" + profile.getIsFailing());
            } else {
                System.out.println("Peer " + profile.getPeer().toBase64().substring(0,4) 
                           + " [" + (organizer.isFast(peer) ? "F+R " : 
                                     organizer.isHighCapacity(peer) ? "R   " :
                                     organizer.isFailing(peer) ? "X   " : "    ") + "]: "
                           + "\t Speed:\t" + fmt.format(profile.getSpeedValue())
                           + " Capacity:\t" + fmt.format(profile.getCapacityValue())
                           + " Integration:\t" + fmt.format(profile.getIntegrationValue())
                           + " Active?\t" + profile.getIsActive() 
                           + " Failing?\t" + profile.getIsFailing());
            }
        }
        
        System.out.println("Thresholds:");
        System.out.println("Speed:       " + num(organizer.getSpeedThreshold()) + " (" + organizer.countFastPeers() + " fast peers)");
        System.out.println("Capacity:    " + num(organizer.getCapacityThreshold()) + " (" + organizer.countHighCapacityPeers() + " reliable peers)");
    }
    
}
