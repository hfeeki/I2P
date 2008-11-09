package net.i2p.router.networkdb.kademlia;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import net.i2p.data.DataHelper;
import net.i2p.data.DataStructure;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.router.Job;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.networkdb.DatabaseLookupMessageHandler;
import net.i2p.router.networkdb.DatabaseStoreMessageHandler;
import net.i2p.router.networkdb.PublishLocalRouterInfoJob;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.util.Log;

/**
 * Kademlia based version of the network database
 *
 */
public class KademliaNetworkDatabaseFacade extends NetworkDatabaseFacade {
    protected Log _log;
    private KBucketSet _kb; // peer hashes sorted into kbuckets, but within kbuckets, unsorted
    private DataStore _ds; // hash to DataStructure mapping, persisted when necessary
    /** where the data store is pushing the data */
    private String _dbDir;
    private Set _explicitSendKeys; // set of Hash objects that should be published ASAP
    private Set _passiveSendKeys; // set of Hash objects that should be published when there's time
    private Set _exploreKeys; // set of Hash objects that we should search on (to fill up a bucket, not to get data)
    private Map _lastSent; // Hash to Long (date last sent, or <= 0 for never)
    private boolean _initialized;
    /** Clock independent time of when we started up */
    private long _started;
    private StartExplorersJob _exploreJob;
    private HarvesterJob _harvestJob;
    /** when was the last time an exploration found something new? */
    private long _lastExploreNew;
    protected PeerSelector _peerSelector;
    protected RouterContext _context;
    /** 
     * Map of Hash to RepublishLeaseSetJob for leases we'realready managing.
     * This is added to when we create a new RepublishLeaseSetJob, and the values are 
     * removed when the job decides to stop running.
     *
     */
    private Map _publishingLeaseSets;   
    
    /** 
     * Hash of the key currently being searched for, pointing the SearchJob that
     * is currently operating.  Subsequent requests for that same key are simply
     * added on to the list of jobs fired on success/failure
     *
     */
    private Map _activeRequests;
    
    /**
     * The search for the given key is no longer active
     *
     */
    void searchComplete(Hash key) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("search Complete: " + key);
        SearchJob removed = null;
        synchronized (_activeRequests) {
            removed = (SearchJob)_activeRequests.remove(key);
        }
    }
    
    /**
     * for the 10 minutes after startup, don't fail db entries so that if we were
     * offline for a while, we'll have a chance of finding some live peers with the
     * previous references
     */
    protected final static long DONT_FAIL_PERIOD = 10*60*1000;
    
    /** don't probe or broadcast data, just respond and search when explicitly needed */
    private boolean _quiet = false;
    
    public static final String PROP_ENFORCE_NETID = "router.networkDatabase.enforceNetId";
    private static final boolean DEFAULT_ENFORCE_NETID = false;
    private boolean _enforceNetId = DEFAULT_ENFORCE_NETID;
    
    public final static String PROP_DB_DIR = "router.networkDatabase.dbDir";
    public final static String DEFAULT_DB_DIR = "netDb";
    
    /** if we have less than this many routers left, don't drop any more,
     *  even if they're failing or doing bad shit.
     */
    protected final static int MIN_REMAINING_ROUTERS = 25;
    
    /** 
     * dont accept any dbDtore of a router over 24 hours old (unless we dont 
     * know anyone or just started up) 
     */
    private final static long ROUTER_INFO_EXPIRATION = 3*24*60*60*1000l;
    
    private final static long EXPLORE_JOB_DELAY = 10*60*1000l;

    public KademliaNetworkDatabaseFacade(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(getClass());
        _initialized = false;
        _peerSelector = createPeerSelector();
        _publishingLeaseSets = new HashMap(8);
        _lastExploreNew = 0;
        _activeRequests = new HashMap(8);
        _enforceNetId = DEFAULT_ENFORCE_NETID;
        context.statManager().createRateStat("netDb.lookupLeaseSetDeferred", "how many lookups are deferred for a single leaseSet lookup?", "NetworkDatabase", new long[] { 60*1000, 5*60*1000 });
        context.statManager().createRateStat("netDb.exploreKeySet", "how many keys are queued for exploration?", "NetworkDatabase", new long[] { 10*60*1000 });
    }
    
    protected PeerSelector createPeerSelector() { return new PeerSelector(_context); }
    public PeerSelector getPeerSelector() { return _peerSelector; }
    
    KBucketSet getKBuckets() { return _kb; }
    DataStore getDataStore() { return _ds; }
    
    long getLastExploreNewDate() { return _lastExploreNew; }
    void setLastExploreNewDate(long when) { 
        _lastExploreNew = when; 
        if (_exploreJob != null)
            _exploreJob.updateExploreSchedule();
    }
    
    public Set getExplicitSendKeys() {
        if (!_initialized) return null;
        synchronized (_explicitSendKeys) {
            return new HashSet(_explicitSendKeys);
        }
    }
    public Set getPassivelySendKeys() {
        if (!_initialized) return null;
        synchronized (_passiveSendKeys) {
            return new HashSet(_passiveSendKeys);
        }
    }
    public void removeFromExplicitSend(Set toRemove) {
        if (!_initialized) return;
        synchronized (_explicitSendKeys) {
            _explicitSendKeys.removeAll(toRemove);
        }
    }
    public void removeFromPassiveSend(Set toRemove) {
        if (!_initialized) return;
        synchronized (_passiveSendKeys) {
            _passiveSendKeys.removeAll(toRemove);
        }
    }
    public void queueForPublishing(Set toSend) {
        if (!_initialized) return;
        synchronized (_passiveSendKeys) {
            _passiveSendKeys.addAll(toSend);
        }
    }
    
    public Long getLastSent(Hash key) {
        if (!_initialized) return null;
        synchronized (_lastSent) {
            if (!_lastSent.containsKey(key))
                _lastSent.put(key, new Long(0));
            return (Long)_lastSent.get(key);
        }
    }
    
    public void noteKeySent(Hash key) {
        if (!_initialized) return;
        synchronized (_lastSent) {
            _lastSent.put(key, new Long(_context.clock().now()));
        }
    }
    
    public Set getExploreKeys() {
        if (!_initialized) return null;
        synchronized (_exploreKeys) {
            return new HashSet(_exploreKeys);
        }
    }
    
    public void removeFromExploreKeys(Set toRemove) {
        if (!_initialized) return;
        synchronized (_exploreKeys) {
            _exploreKeys.removeAll(toRemove);
            _context.statManager().addRateData("netDb.exploreKeySet", _exploreKeys.size(), 0);
        }
    }
    public void queueForExploration(Set keys) {
        if (!_initialized) return;
        synchronized (_exploreKeys) {
            _exploreKeys.addAll(keys);
            _context.statManager().addRateData("netDb.exploreKeySet", _exploreKeys.size(), 0);
        }
    }
    
    public void shutdown() {
        _initialized = false;
        _kb = null;
        _ds = null;
        _explicitSendKeys = null;
        _passiveSendKeys = null;
        _exploreKeys = null;
        _lastSent = null;
    }
    
    public void restart() {
        _dbDir = _context.router().getConfigSetting(PROP_DB_DIR);
        if (_dbDir == null) {
            _log.info("No DB dir specified [" + PROP_DB_DIR + "], using [" + DEFAULT_DB_DIR + "]");
            _dbDir = DEFAULT_DB_DIR;
        }
        String enforce = _context.getProperty(PROP_ENFORCE_NETID);
        if (enforce != null) 
            _enforceNetId = Boolean.valueOf(enforce).booleanValue();
        else
            _enforceNetId = DEFAULT_ENFORCE_NETID;
        _ds.restart();
        synchronized (_explicitSendKeys) { _explicitSendKeys.clear(); }
        synchronized (_exploreKeys) { _exploreKeys.clear(); }
        synchronized (_passiveSendKeys) { _passiveSendKeys.clear(); }

        _initialized = true;
        
        RouterInfo ri = _context.router().getRouterInfo();
        publish(ri);
    }
    
    String getDbDir() { return _dbDir; }
    
    public void startup() {
        _log.info("Starting up the kademlia network database");
        RouterInfo ri = _context.router().getRouterInfo();
        String dbDir = _context.router().getConfigSetting(PROP_DB_DIR);
        if (dbDir == null) {
            _log.info("No DB dir specified [" + PROP_DB_DIR + "], using [" + DEFAULT_DB_DIR + "]");
            dbDir = DEFAULT_DB_DIR;
        }
        String enforce = _context.getProperty(PROP_ENFORCE_NETID);
        if (enforce != null) 
            _enforceNetId = Boolean.valueOf(enforce).booleanValue();
        else
            _enforceNetId = DEFAULT_ENFORCE_NETID;
        
        _kb = new KBucketSet(_context, ri.getIdentity().getHash());
        _ds = new PersistentDataStore(_context, dbDir, this);
        //_ds = new TransientDataStore();
        _explicitSendKeys = new HashSet(64);
        _passiveSendKeys = new HashSet(64);
        _exploreKeys = new HashSet(64);
        _lastSent = new HashMap(1024);
        _dbDir = dbDir;
        
        createHandlers();
        
        _initialized = true;
        _started = System.currentTimeMillis();
        
        // read the queues and publish appropriately
        if (false)
            _context.jobQueue().addJob(new DataPublisherJob(_context, this));
        // expire old leases
        _context.jobQueue().addJob(new ExpireLeasesJob(_context, this));
        
        // the ExpireRoutersJob never fired since the tunnel pool manager lied
        // and said all peers are in use (for no good reason), but this expire
        // thing was a bit overzealous anyway, since the kbuckets are only
        // relevent when the network is huuuuuuuuge.
        //// expire some routers in overly full kbuckets
        ////_context.jobQueue().addJob(new ExpireRoutersJob(_context, this));
        
        if (!_quiet) {
            // fill the passive queue periodically
            _context.jobQueue().addJob(new DataRepublishingSelectorJob(_context, this));
            // fill the search queue with random keys in buckets that are too small
            _context.jobQueue().addJob(new ExploreKeySelectorJob(_context, this));
            if (_exploreJob == null)
                _exploreJob = new StartExplorersJob(_context, this);
            // fire off a group of searches from the explore pool
            // Don't start it right away, so we don't send searches for random keys
            // out our 0-hop exploratory tunnels (generating direct connections to
            // one or more floodfill peers within seconds of startup).
            // We're trying to minimize the ff connections to lessen the load on the 
            // floodfills, and in any case let's try to build some real expl. tunnels first.
            // No rush, it only runs every 30m.
            _exploreJob.getTiming().setStartAfter(_context.clock().now() + EXPLORE_JOB_DELAY);
            _context.jobQueue().addJob(_exploreJob);
            // if configured to do so, periodically try to get newer routerInfo stats
            if (_harvestJob == null && "true".equals(_context.getProperty(HarvesterJob.PROP_ENABLED)))
                _harvestJob = new HarvesterJob(_context, this);
            _context.jobQueue().addJob(_harvestJob);
        } else {
            _log.warn("Operating in quiet mode - not exploring or pushing data proactively, simply reactively");
            _log.warn("This should NOT be used in production");
        }
        // periodically update and resign the router's 'published date', which basically
        // serves as a version
        _context.jobQueue().addJob(new PublishLocalRouterInfoJob(_context));
        try {
            publish(ri);
        } catch (IllegalArgumentException iae) {
            _context.router().rebuildRouterInfo(true);
            //_log.log(Log.CRIT, "Our local router info is b0rked, clearing from scratch", iae);
            //_context.router().rebuildNewIdentity();
        }
    }
    
    protected void createHandlers() {
        _context.inNetMessagePool().registerHandlerJobBuilder(DatabaseLookupMessage.MESSAGE_TYPE, new DatabaseLookupMessageHandler(_context));
        _context.inNetMessagePool().registerHandlerJobBuilder(DatabaseStoreMessage.MESSAGE_TYPE, new DatabaseStoreMessageHandler(_context));
    }
    
    /**
     * Get the routers closest to that key in response to a remote lookup
     */
    public Set findNearestRouters(Hash key, int maxNumRouters, Set peersToIgnore) {
        if (!_initialized) return null;
        return getRouters(_peerSelector.selectNearest(key, maxNumRouters, peersToIgnore, _kb));
    }
    
    private Set getRouters(Collection hashes) {
        if (!_initialized) return null;
        Set rv = new HashSet(hashes.size());
        for (Iterator iter = hashes.iterator(); iter.hasNext(); ) {
            Hash rhash = (Hash)iter.next();
            DataStructure ds = _ds.get(rhash);
            if (ds == null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Selected hash " + rhash.toBase64() + " is not stored locally");
            } else if ( !(ds instanceof RouterInfo) ) {
                // leaseSet
            } else {
                rv.add(ds);
            }
        }
        return rv;
    }
    
    /** get the hashes for all known routers */
    Set getAllRouters() {
        if (!_initialized) return new HashSet(0);
        Set keys = _ds.getKeys();
        Set rv = new HashSet(keys.size());
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("getAllRouters(): # keys in the datastore: " + keys.size());
        for (Iterator iter = keys.iterator(); iter.hasNext(); ) {
            Hash key = (Hash)iter.next();
            
            DataStructure ds = _ds.get(key);
            if (ds == null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Selected hash " + key.toBase64() + " is not stored locally");
            } else if ( !(ds instanceof RouterInfo) ) {
                // leaseSet 
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("getAllRouters(): key is router: " + key.toBase64());
                rv.add(key);
            }
        }
        return rv;
    }
    
    public int getKnownRouters() { 
        if (_kb == null) return 0;
        CountRouters count = new CountRouters();
        _kb.getAll(count);
        return count.size();
    }
    
    private class CountRouters implements SelectionCollector {
        private int _count;
        public int size() { return _count; }
        public void add(Hash entry) {
            if (_ds == null) return;
            Object o = _ds.get(entry);
            if (o instanceof RouterInfo)
                _count++;
        }
    }
    
    public int getKnownLeaseSets() {  
        if (_ds == null) return 0;
        return _ds.countLeaseSets();
    }
    
    private class CountLeaseSets implements SelectionCollector {
        private int _count;
        public int size() { return _count; }
        public void add(Hash entry) {
            if (_ds == null) return;
            Object o = _ds.get(entry);
            if (o instanceof LeaseSet)
                _count++;
        }
    }
    
    public void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs) {
        if (!_initialized) return;
        LeaseSet ls = lookupLeaseSetLocally(key);
        if (ls != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("leaseSet found locally, firing " + onFindJob);
            if (onFindJob != null)
                _context.jobQueue().addJob(onFindJob);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("leaseSet not found locally, running search");
            search(key, onFindJob, onFailedLookupJob, timeoutMs, true);
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("after lookupLeaseSet");
    }
    
    public LeaseSet lookupLeaseSetLocally(Hash key) {
        if (!_initialized) return null;
        if (_ds.isKnown(key)) {
            DataStructure ds = _ds.get(key);
            if (ds instanceof LeaseSet) {
                LeaseSet ls = (LeaseSet)ds;
                if (ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
                    return ls;
                } else {
                    fail(key);
                    // this was an interesting key, so either refetch it or simply explore with it
                    synchronized (_exploreKeys) {
                        _exploreKeys.add(key);
                    }
                    return null;
                }
            } else {
                //_log.debug("Looking for a lease set [" + key + "] but it ISN'T a leaseSet! " + ds, new Exception("Who thought that router was a lease?"));
                return null;
            }
        } else {
            return null;
        }
    }
    
    public void lookupRouterInfo(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs) {
        if (!_initialized) return;
        RouterInfo ri = lookupRouterInfoLocally(key);
        if (ri != null) {
            if (onFindJob != null)
                _context.jobQueue().addJob(onFindJob);
        } else {
            search(key, onFindJob, onFailedLookupJob, timeoutMs, false);
        }
    }
    
    public RouterInfo lookupRouterInfoLocally(Hash key) {
        if (!_initialized) return null;
        DataStructure ds = _ds.get(key);
        if (ds != null) {
            if (ds instanceof RouterInfo) {
                // more aggressive than perhaps is necessary, but makes sure we
                // drop old references that we had accepted on startup (since 
                // startup allows some lax rules).
                boolean valid = true;
                try {
                    valid = (null == validate(key, (RouterInfo)ds));
                } catch (IllegalArgumentException iae) {
                    valid = false;
                }
                if (!valid) {
                    fail(key);
                    return null;
                }
                return (RouterInfo)ds;
            } else {
                //_log.debug("Looking for a router [" + key + "] but it ISN'T a RouterInfo! " + ds, new Exception("Who thought that lease was a router?"));
                return null;
            }
        } else {
            return null;
        }
    }
    
    public void publish(LeaseSet localLeaseSet) {
        if (!_initialized) return;
        Hash h = localLeaseSet.getDestination().calculateHash();
        try {
            store(h, localLeaseSet);
        } catch (IllegalArgumentException iae) {
            _log.error("wtf, locally published leaseSet is not valid?", iae);
            return;
        }
        if (!_context.clientManager().shouldPublishLeaseSet(h))
            return;
        
        synchronized (_explicitSendKeys) {
            _explicitSendKeys.add(h);
        }
        RepublishLeaseSetJob j = null;
        synchronized (_publishingLeaseSets) {
            j = (RepublishLeaseSetJob)_publishingLeaseSets.get(h);
            if (j == null) {
                j = new RepublishLeaseSetJob(_context, this, h);
                _publishingLeaseSets.put(h, j);
            }
        }
        j.getTiming().setStartAfter(_context.clock().now());
        _context.jobQueue().addJob(j);
    }
    
    void stopPublishing(Hash target) {
        synchronized (_publishingLeaseSets) {
            _publishingLeaseSets.remove(target);
        }
    }
    
    /**
     * @throws IllegalArgumentException if the local router info is invalid
     */
    public void publish(RouterInfo localRouterInfo) throws IllegalArgumentException {
        if (!_initialized) return;
        // This isn't really used for anything
        // writeMyInfo(localRouterInfo);
        if (_context.router().isHidden()) return; // DE-nied!
        Hash h = localRouterInfo.getIdentity().getHash();
        store(h, localRouterInfo);
        synchronized (_explicitSendKeys) {
            _explicitSendKeys.add(h);
        }
    }

    /**
     * Persist the local router's info (as updated) into netDb/my.info, since
     * ./router.info isn't always updated.  This also allows external applications
     * to easily pick out which router a netDb directory is rooted off, which is handy
     * for getting the freshest data.
     *
     */
/***
    private final void writeMyInfo(RouterInfo info) {
        FileOutputStream fos = null;
        try {
            File dbDir = new File(_dbDir);
            if (!dbDir.exists())
                dbDir.mkdirs();
            fos = new FileOutputStream(new File(dbDir, "my.info"));
            info.writeBytes(fos);
            fos.close();
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Unable to persist my.info?!", ioe);
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error persisting my.info - our structure isn't valid?!", dfe);
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
        }
    }
***/
    
    /**
     * Don't let leaseSets go 20 minutes into the future 
     */
    static final long MAX_LEASE_FUTURE = 20*60*1000;
    
    /**
     * Determine whether this leaseSet will be accepted as valid and current
     * given what we know now.
     *
     * @return reason why the entry is not valid, or null if it is valid
     */
    String validate(Hash key, LeaseSet leaseSet) {
        if (!key.equals(leaseSet.getDestination().calculateHash())) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Invalid store attempt! key does not match leaseSet.destination!  key = "
                          + key + ", leaseSet = " + leaseSet);
            return "Key does not match leaseSet.destination - " + key.toBase64();
        } else if (!leaseSet.verifySignature()) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Invalid leaseSet signature!  leaseSet = " + leaseSet);
            return "Invalid leaseSet signature on " + leaseSet.getDestination().calculateHash().toBase64();
        } else if (leaseSet.getEarliestLeaseDate() <= _context.clock().now() - 2*Router.CLOCK_FUDGE_FACTOR) {
            long age = _context.clock().now() - leaseSet.getEarliestLeaseDate();
            if (_log.shouldLog(Log.WARN))
                _log.warn("Old leaseSet!  not storing it: " 
                          + leaseSet.getDestination().calculateHash().toBase64() 
                          + " expires on " + new Date(leaseSet.getEarliestLeaseDate()), new Exception("Rejecting store"));
            return "Expired leaseSet for " + leaseSet.getDestination().calculateHash().toBase64() 
                   + " expired " + DataHelper.formatDuration(age) + " ago";
        } else if (leaseSet.getEarliestLeaseDate() > _context.clock().now() + Router.CLOCK_FUDGE_FACTOR + MAX_LEASE_FUTURE) {
            long age = leaseSet.getEarliestLeaseDate() - _context.clock().now();
            if (_log.shouldLog(Log.ERROR))
                _log.error("LeaseSet to expire too far in the future: " 
                          + leaseSet.getDestination().calculateHash().toBase64() 
                          + " expires on " + new Date(leaseSet.getEarliestLeaseDate()), new Exception("Rejecting store"));
            return "Expired leaseSet for " + leaseSet.getDestination().calculateHash().toBase64() 
                   + " expiring in " + DataHelper.formatDuration(age);
        }
        return null;
    }
    
    /**
     * Store the leaseSet
     *
     * @throws IllegalArgumentException if the leaseSet is not valid
     */
    public LeaseSet store(Hash key, LeaseSet leaseSet) throws IllegalArgumentException {
        if (!_initialized) return null;
        
        LeaseSet rv = (LeaseSet)_ds.get(key);
        
        if ( (rv != null) && (rv.equals(leaseSet)) ) {
            // if it hasn't changed, no need to do anything
            return rv;
        }
        
        String err = validate(key, leaseSet);
        if (err != null)
            throw new IllegalArgumentException("Invalid store attempt - " + err);
        
        _ds.put(key, leaseSet);
        synchronized (_lastSent) {
            if (!_lastSent.containsKey(key))
                _lastSent.put(key, new Long(0));
        }
        
        // Iterate through the old failure / success count, copying over the old
        // values (if any tunnels overlap between leaseSets).  no need to be
        // ueberthreadsafe fascists here, since these values are just heuristics
        if (rv != null) {
            for (int i = 0; i < rv.getLeaseCount(); i++) {
                Lease old = rv.getLease(i);
                for (int j = 0; j < leaseSet.getLeaseCount(); j++) {
                    Lease cur = leaseSet.getLease(j);
                    if (cur.getTunnelId().getTunnelId() == old.getTunnelId().getTunnelId()) {
                        cur.setNumFailure(old.getNumFailure());
                        cur.setNumSuccess(old.getNumSuccess());
                        break;
                    }
                }
            }
        }
        
        return rv;
    }
    
    /**
     * Determine whether this routerInfo will be accepted as valid and current
     * given what we know now.
     *
     */
    String validate(Hash key, RouterInfo routerInfo) throws IllegalArgumentException {
        long now = _context.clock().now();
        
        if (!key.equals(routerInfo.getIdentity().getHash())) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Invalid store attempt! key does not match routerInfo.identity!  key = " + key + ", router = " + routerInfo);
            return "Key does not match routerInfo.identity - " + key.toBase64();
        } else if (!routerInfo.isValid()) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Invalid routerInfo signature!  forged router structure!  router = " + routerInfo);
            return "Invalid routerInfo signature on " + key.toBase64();
        } else if (!routerInfo.isCurrent(ROUTER_INFO_EXPIRATION) && (_context.router().getUptime() > 60*60*1000) ) {
            if (routerInfo.getNetworkId() != Router.NETWORK_ID) {
                _context.shitlist().shitlistRouter(key, "Peer is not in our network");
                return "Peer is not in our network (" + routerInfo.getNetworkId() + ", wants " 
                       + Router.NETWORK_ID + "): " + routerInfo.calculateHash().toBase64();
            }
            long age = _context.clock().now() - routerInfo.getPublished();
            int existing = _kb.size();
            if (existing >= MIN_REMAINING_ROUTERS) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Not storing expired router for " + key.toBase64(), new Exception("Rejecting store"));
                return "Peer " + key.toBase64() + " expired " + DataHelper.formatDuration(age) + " ago";
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Even though the peer is old, we have only " + existing
                    + " peers left (curPeer: " + key.toBase64() + " published on "
                    + new Date(routerInfo.getPublished()));
            }
        } else if (routerInfo.getPublished() > now + 2*Router.CLOCK_FUDGE_FACTOR) {
            long age = routerInfo.getPublished() - _context.clock().now();
            if (_log.shouldLog(Log.INFO))
                _log.info("Peer " + key.toBase64() + " published their routerInfo in the future?! [" 
                          + new Date(routerInfo.getPublished()) + "]", new Exception("Rejecting store"));
            return "Peer " + key.toBase64() + " published " + DataHelper.formatDuration(age) + " in the future?!";
        } else if (_enforceNetId && (routerInfo.getNetworkId() != Router.NETWORK_ID) ){
            String rv = "Peer " + key.toBase64() + " is from another network, not accepting it (id=" 
                        + routerInfo.getNetworkId() + ", want " + Router.NETWORK_ID + ")";
            return rv;
        } else if ( (_context.router().getUptime() > 60*60*1000) && (routerInfo.getPublished() < now - 2*24*60*60*1000l) ) {
            long age = _context.clock().now() - routerInfo.getPublished();
            return "Peer " + key.toBase64() + " published " + DataHelper.formatDuration(age) + " ago";
        }
        return null;
    }
    
    /**
     * store the routerInfo
     *
     * @throws IllegalArgumentException if the routerInfo is not valid
     */
    public RouterInfo store(Hash key, RouterInfo routerInfo) throws IllegalArgumentException {
        if (!_initialized) return null;
        
        RouterInfo rv = (RouterInfo)_ds.get(key);
        
        if ( (rv != null) && (rv.equals(routerInfo)) ) {
            // no need to validate
            return rv;
        }
        
        String err = validate(key, routerInfo);
        if (err != null)
            throw new IllegalArgumentException("Invalid store attempt - " + err);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("RouterInfo " + key.toBase64() + " is stored with "
                       + routerInfo.getOptions().size() + " options on "
                       + new Date(routerInfo.getPublished()));
    
        _context.peerManager().setCapabilities(key, routerInfo.getCapabilities());
        _ds.put(key, routerInfo);
        synchronized (_lastSent) {
            if (!_lastSent.containsKey(key))
                _lastSent.put(key, new Long(0));
        }
        if (rv == null)
            _kb.add(key);
        return rv;
    }
    
    public void fail(Hash dbEntry) {
        if (!_initialized) return;
        boolean isRouterInfo = false;
        Object o = _ds.get(dbEntry);
        if (o instanceof RouterInfo)
            isRouterInfo = true;
        
        if (isRouterInfo) {
            lookupBeforeDropping(dbEntry, (RouterInfo)o);
            return;
        } else {
            // we always drop leaseSets that are failed [timed out],
            // regardless of how many routers we have.  this is called on a lease if
            // it has expired *or* its tunnels are failing and we want to see if there
            // are any updates
            if (_log.shouldLog(Log.INFO))
                _log.info("Dropping a lease: " + dbEntry);
        }
        
        if (o == null) {
            _kb.remove(dbEntry);
            _context.peerManager().removeCapabilities(dbEntry);
            // if we dont know the key, lets make sure it isn't a now-dead peer
        }
        
        if (isRouterInfo)
            _ds.remove(dbEntry);
        else
            _ds.removeLease(dbEntry);
        synchronized (_lastSent) {
            _lastSent.remove(dbEntry);
        }
        synchronized (_explicitSendKeys) {
            _explicitSendKeys.remove(dbEntry);
        }
        synchronized (_passiveSendKeys) {
            _passiveSendKeys.remove(dbEntry);
        }
    }
    
    /** don't use directly - see F.N.D.F. override */
    protected void lookupBeforeDropping(Hash peer, RouterInfo info) {
        //bah, humbug.
        dropAfterLookupFailed(peer, info);
    }
    protected void dropAfterLookupFailed(Hash peer, RouterInfo info) {
        _context.peerManager().removeCapabilities(peer);
        boolean removed = _kb.remove(peer);
        if (removed) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Removed kbucket entry for " + peer);
        }
        
        _ds.remove(peer);
        synchronized (_lastSent) {
            _lastSent.remove(peer);
        }
        synchronized (_explicitSendKeys) {
            _explicitSendKeys.remove(peer);
        }
        synchronized (_passiveSendKeys) {
            _passiveSendKeys.remove(peer);
        }
    }
    
    public void unpublish(LeaseSet localLeaseSet) {
        if (!_initialized) return;
        Hash h = localLeaseSet.getDestination().calculateHash();
        DataStructure data = _ds.remove(h);
        synchronized (_lastSent) {
            _lastSent.remove(h);
        }
        synchronized (_explicitSendKeys) {
            _explicitSendKeys.remove(h);
        }
        synchronized (_passiveSendKeys) {
            _passiveSendKeys.remove(h);
        }
        
        if (data == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unpublished a lease we don't know...: " + localLeaseSet);
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("Unpublished a lease: " + h);
        }
        // now update it if we can to remove any leases
    }
    
    /**
     * Begin a kademlia style search for the key specified, which can take up to timeoutMs and
     * will fire the appropriate jobs on success or timeout (or if the kademlia search completes
     * without any match)
     *
     */
    SearchJob search(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, boolean isLease) {
        if (!_initialized) return null;
        boolean isNew = true;
        SearchJob searchJob = null;
        synchronized (_activeRequests) {
            searchJob = (SearchJob)_activeRequests.get(key);
            if (searchJob == null) {
                searchJob = new SearchJob(_context, this, key, onFindJob, onFailedLookupJob, 
                                         timeoutMs, true, isLease);
                _activeRequests.put(key, searchJob);
            } else {
                isNew = false;
            }
        }
        if (isNew) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("this is the first search for that key, fire off the SearchJob");
            _context.jobQueue().addJob(searchJob);
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("Deferring search for " + key.toBase64() + " with " + onFindJob);
            int deferred = searchJob.addDeferred(onFindJob, onFailedLookupJob, timeoutMs, isLease);
            _context.statManager().addRateData("netDb.lookupLeaseSetDeferred", deferred, searchJob.getExpiration()-_context.clock().now());
        }
        return searchJob;
    }
    
    private Set getLeases() {
        if (!_initialized) return null;
        Set leases = new HashSet();
        Set keys = getDataStore().getKeys();
        for (Iterator iter = keys.iterator(); iter.hasNext(); ) {
            Hash key = (Hash)iter.next();
            Object o = getDataStore().get(key);
            if (o instanceof LeaseSet)
                leases.add(o);
        }
        return leases;
    }
    private Set getRouters() {
        if (!_initialized) return null;
        Set routers = new HashSet();
        Set keys = getDataStore().getKeys();
        for (Iterator iter = keys.iterator(); iter.hasNext(); ) {
            Hash key = (Hash)iter.next();
            Object o = getDataStore().get(key);
            if (o instanceof RouterInfo)
                routers.add(o);
        }
        return routers;
    }

    /** smallest allowed period */
    private static final int MIN_PER_PEER_TIMEOUT = 5*1000;
    private static final int MAX_PER_PEER_TIMEOUT = 10*1000;
    
    public int getPeerTimeout(Hash peer) {
        PeerProfile prof = _context.profileOrganizer().getProfile(peer);
        double responseTime = MAX_PER_PEER_TIMEOUT;
        if (prof != null)
            responseTime = prof.getDbResponseTime().getLifetimeAverageValue();
        if (responseTime < MIN_PER_PEER_TIMEOUT)
            responseTime = MIN_PER_PEER_TIMEOUT;
        else if (responseTime > MAX_PER_PEER_TIMEOUT)
            responseTime = MAX_PER_PEER_TIMEOUT;
        return 4 * (int)responseTime;  // give it up to 4x the average response time
    }

    public void sendStore(Hash key, DataStructure ds, Job onSuccess, Job onFailure, long sendTimeout, Set toIgnore) {
        if ( (ds == null) || (key == null) ) {
            if (onFailure != null) 
                _context.jobQueue().addJob(onFailure);
            return;
        }
        _context.jobQueue().addJob(new StoreJob(_context, this, key, ds, onSuccess, onFailure, sendTimeout, toIgnore));
    }
    
    class LeaseSetComparator implements Comparator {
         public int compare(Object l, Object r) {
             Destination dl = ((LeaseSet)l).getDestination();
             Destination dr = ((LeaseSet)r).getDestination();
             boolean locall = _context.clientManager().isLocal(dl);
             boolean localr = _context.clientManager().isLocal(dr);
             if (locall && !localr) return -1;
             if (localr && !locall) return 1;
             return dl.calculateHash().toBase64().compareTo(dr.calculateHash().toBase64());
        }
    }

    class RouterInfoComparator implements Comparator {
         public int compare(Object l, Object r) {
             return ((RouterInfo)l).getIdentity().getHash().toBase64().compareTo(((RouterInfo)r).getIdentity().getHash().toBase64());
        }
    }

    public void renderRouterInfoHTML(Writer out, String routerPrefix) throws IOException {
        StringBuffer buf = new StringBuffer(4*1024);
        buf.append("<h2>Network Database RouterInfo Lookup</h2>\n");
        if (".".equals(routerPrefix)) {
            renderRouterInfo(buf, _context.router().getRouterInfo(), true);
        } else {
            boolean notFound = true;
            Set routers = getRouters();
            for (Iterator iter = routers.iterator(); iter.hasNext(); ) {
                RouterInfo ri = (RouterInfo)iter.next();
                Hash key = ri.getIdentity().getHash();
                if (key.toBase64().startsWith(routerPrefix)) {
                    renderRouterInfo(buf, ri, false);
                    notFound = false;
                }
            }
            if (notFound)
                buf.append("Router ").append(routerPrefix).append(" not found in network database");
        }
        out.write(buf.toString());
        out.flush();
    }

    public void renderStatusHTML(Writer out) throws IOException {
        StringBuffer buf = new StringBuffer(getKnownRouters() * 2048);
        buf.append("<h2>Network Database Contents</h2>\n");
        if (!_initialized) {
            buf.append("<i>Not initialized</i>\n");
            out.write(buf.toString());
            out.flush();
            return;
        }
        Set leases = new TreeSet(new LeaseSetComparator());
        leases.addAll(getLeases());
        buf.append("<h3>Leases</h3>\n");
        out.write(buf.toString());
        buf.setLength(0);
        long now = _context.clock().now();
        for (Iterator iter = leases.iterator(); iter.hasNext(); ) {
            LeaseSet ls = (LeaseSet)iter.next();
            Destination dest = ls.getDestination();
            Hash key = dest.calculateHash();
            buf.append("<b>LeaseSet: ").append(key.toBase64());
            if (_context.clientManager().isLocal(dest)) {
                buf.append(" (<a href=\"tunnels.jsp#" + key.toBase64().substring(0,4) + "\">Local</a> ");
                if (! _context.clientManager().shouldPublishLeaseSet(key))
                    buf.append("Unpublished ");
                buf.append("Destination ");
                TunnelPoolSettings in = _context.tunnelManager().getInboundSettings(key);
                if (in != null && in.getDestinationNickname() != null)
                    buf.append(in.getDestinationNickname());
                else
                    buf.append(dest.toBase64().substring(0, 6));
            } else {
                buf.append(" (Destination ");
                String host = _context.namingService().reverseLookup(dest);
                if (host != null)
                    buf.append(host);
                else
                    buf.append(dest.toBase64().substring(0, 6));
            }
            buf.append(")</b><br />\n");
            long exp = ls.getEarliestLeaseDate()-now;
            if (exp > 0)
                buf.append("Earliest expiration date in: <i>").append(DataHelper.formatDuration(exp)).append("</i><br />\n");
            else
                buf.append("Earliest expiration date was: <i>").append(DataHelper.formatDuration(0-exp)).append(" ago</i><br />\n");
            for (int i = 0; i < ls.getLeaseCount(); i++) {
                buf.append("Lease ").append(i).append(": gateway <i>");
                buf.append(ls.getLease(i).getGateway().toBase64().substring(0,6));
                buf.append("</i> tunnelId <i>").append(ls.getLease(i).getTunnelId().getTunnelId()).append("</i><br />\n");
            }
            buf.append("<hr />\n");
            out.write(buf.toString());
            buf.setLength(0);
        }
        
        Hash us = _context.routerHash();
        out.write("<h3>Routers</h3>\n");
        
        RouterInfo ourInfo = _context.router().getRouterInfo();
        renderRouterInfo(buf, ourInfo, true);
        out.write(buf.toString());
        buf.setLength(0);
        
        /* coreVersion to Map of routerVersion to Integer */
        Map versions = new TreeMap();
        
        Set routers = new TreeSet(new RouterInfoComparator());
        routers.addAll(getRouters());
        for (Iterator iter = routers.iterator(); iter.hasNext(); ) {
            RouterInfo ri = (RouterInfo)iter.next();
            Hash key = ri.getIdentity().getHash();
            boolean isUs = key.equals(us);
            if (!isUs) {
                renderRouterInfo(buf, ri, false);
                out.write(buf.toString());
                buf.setLength(0);
                String coreVersion = ri.getOption("coreVersion");
                String routerVersion = ri.getOption("router.version");
                if ( (coreVersion != null) && (routerVersion != null) ) {
                    Map routerVersions = (Map)versions.get(coreVersion);
                    if (routerVersions == null) {
                        routerVersions = new TreeMap();
                        versions.put(coreVersion, routerVersions);
                    }
                    Integer val = (Integer)routerVersions.get(routerVersion);
                    if (val == null)
                        routerVersions.put(routerVersion, Integer.valueOf(1));
                    else
                        routerVersions.put(routerVersion, Integer.valueOf(val.intValue() + 1));
                }
            }
        }
            
        if (versions.size() > 0) {
            buf.append("<table border=\"1\">\n");
            buf.append("<tr><td><b>Core version</b></td><td><b>Router version</b></td><td><b>Number</b></td></tr>\n");
            for (Iterator iter = versions.entrySet().iterator(); iter.hasNext(); ) {
                Map.Entry entry = (Map.Entry)iter.next();
                String coreVersion = (String)entry.getKey();
                Map routerVersions = (Map)entry.getValue();
                for (Iterator routerIter = routerVersions.keySet().iterator(); routerIter.hasNext(); ) {
                    String routerVersion = (String)routerIter.next();
                    Integer num = (Integer)routerVersions.get(routerVersion);
                    buf.append("<tr><td>").append(DataHelper.stripHTML(coreVersion));
                    buf.append("</td><td>").append(DataHelper.stripHTML(routerVersion));
                    buf.append("</td><td>").append(num.intValue()).append("</td></tr>\n");
                }
            }
            buf.append("</table>\n");
        }
        out.write(buf.toString());
        out.flush();
    }
    
    private void renderRouterInfo(StringBuffer buf, RouterInfo info, boolean isUs) {
        String hash = info.getIdentity().getHash().toBase64();
        buf.append("<a name=\"").append(hash.substring(0, 6)).append("\" />");
        if (isUs) {
            buf.append("<a name=\"our-info\" /a><b>Our info: ").append(hash).append("</b><br />\n");
        } else {
            buf.append("<b>Peer info for:</b> ").append(hash).append("<br />\n");
        }
        
        long age = _context.clock().now() - info.getPublished();
        if (isUs && _context.router().isHidden())
            buf.append("Hidden, Updated: <i>").append(DataHelper.formatDuration(age)).append(" ago</i><br />\n");
        else if (age > 0)
            buf.append("Published: <i>").append(DataHelper.formatDuration(age)).append(" ago</i><br />\n");
        else
            buf.append("Published: <i>in ").append(DataHelper.formatDuration(0-age)).append("???</i><br />\n");
        buf.append("Address(es): <i>");
        for (Iterator iter = info.getAddresses().iterator(); iter.hasNext(); ) {
            RouterAddress addr = (RouterAddress)iter.next();
            buf.append(addr.getTransportStyle()).append(": ");
            for (Iterator optIter = addr.getOptions().keySet().iterator(); optIter.hasNext(); ) {
                String name = (String)optIter.next();
                String val = addr.getOptions().getProperty(name);
                buf.append('[').append(DataHelper.stripHTML(name)).append('=').append(DataHelper.stripHTML(val)).append("] ");
            }
        }
        buf.append("</i><br />\n");
        buf.append("Stats: <br /><i><code>\n");
        for (Iterator iter = info.getOptions().keySet().iterator(); iter.hasNext(); ) {
            String key = (String)iter.next();
            String val = info.getOption(key);
            buf.append(DataHelper.stripHTML(key)).append(" = ").append(DataHelper.stripHTML(val)).append("<br />\n");
        }
        buf.append("</code></i><hr />\n");
    }
    
}
