package net.i2p.router.peermanager;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

class ProfilePersistenceHelper {
    private Log _log;
    private RouterContext _context;
    
    public final static String PROP_PEER_PROFILE_DIR = "router.profileDir";
    public final static String DEFAULT_PEER_PROFILE_DIR = "peerProfiles";
    private final static String NL = System.getProperty("line.separator");
    
    private File _profileDir = null;
    private Hash _us;
    
    public ProfilePersistenceHelper(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(ProfilePersistenceHelper.class);
        File profileDir = getProfileDir();
        _us = null;
        if (!profileDir.exists()) {
            profileDir.mkdirs();
            _log.info("Profile directory " + profileDir.getAbsolutePath() + " created");
        }
    }
    
    public void setUs(Hash routerIdentHash) { _us = routerIdentHash; }
    
    /** write out the data from the profile to the stream */
    public void writeProfile(PeerProfile profile) {
        File f = pickFile(profile);
        long before = _context.clock().now();
        OutputStream fos = null;
        try {
            fos = new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(f)));
            writeProfile(profile, fos);
        } catch (IOException ioe) {
            _log.error("Error writing profile to " + f);
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
        }
        long delay = _context.clock().now() - before;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Writing the profile to " + f.getName() + " took " + delay + "ms");
    }
    /** write out the data from the profile to the stream */
    public void writeProfile(PeerProfile profile, OutputStream out) throws IOException {
        String groups = null;
        if (_context.profileOrganizer().isFailing(profile.getPeer())) {
            groups = "failing";
        } else if (!_context.profileOrganizer().isHighCapacity(profile.getPeer())) {
            groups = "not failing";
        } else {
            if (_context.profileOrganizer().isFast(profile.getPeer()))
                groups = "fast and high capacity";
            else
                groups = "high capacity";
            
            if (_context.profileOrganizer().isWellIntegrated(profile.getPeer()))
                groups = groups + ", well integrated";
        }
        
        StringBuffer buf = new StringBuffer(512);
        buf.append("########################################################################").append(NL);
        buf.append("# profile for ").append(profile.getPeer().toBase64()).append(NL);
        if (_us != null)
            buf.append("# as calculated by ").append(_us.toBase64()).append(NL);
        buf.append("#").append(NL);
        buf.append("# reliability: ").append(profile.getReliabilityValue()).append(NL);
        buf.append("# capacity: ").append(profile.getCapacityValue()).append(NL);
        buf.append("# integration: ").append(profile.getIntegrationValue()).append(NL);
        buf.append("# speedValue: ").append(profile.getSpeedValue()).append(NL);
        buf.append("#").append(NL);
        buf.append("# Groups: ").append(groups).append(NL);
        buf.append("########################################################################").append(NL);
        buf.append("##").append(NL);
        buf.append("# Reliability bonus: used to affect the reliability score after all other calculations are done").append(NL);
        buf.append("reliabilityBonus=").append(profile.getReliabilityBonus()).append(NL);
        buf.append("# Integration bonus: used to affect the integration score after all other calculations are done").append(NL);
        buf.append("integrationBonus=").append(profile.getIntegrationBonus()).append(NL);
        buf.append("# Speed bonus: used to affect the speed score after all other calculations are done").append(NL);
        buf.append("speedBonus=").append(profile.getSpeedBonus()).append(NL);
        buf.append(NL).append(NL);
        buf.append("# Last heard about: when did we last get a reference to this peer?  (milliseconds since the epoch)").append(NL);
        buf.append("lastHeardAbout=").append(profile.getLastHeardAbout()).append(NL);
        buf.append("# First heard about: when did we first get a reference to this peer?  (milliseconds since the epoch)").append(NL);
        buf.append("firstHeardAbout=").append(profile.getFirstHeardAbout()).append(NL);
        buf.append("# Last sent to successfully: when did we last send the peer a message successfully?  (milliseconds from the epoch)").append(NL);
        buf.append("lastSentToSuccessfully=").append(profile.getLastSendSuccessful()).append(NL);
        buf.append("# Last failed send: when did we last fail to send a message to the peer?  (milliseconds from the epoch)").append(NL);
        buf.append("lastFailedSend=").append(profile.getLastSendFailed()).append(NL);
        buf.append("# Last heard from: when did we last get a message from the peer?  (milliseconds from the epoch)").append(NL);
        buf.append("lastHeardFrom=").append(profile.getLastHeardFrom()).append(NL);
        buf.append(NL);
        
        out.write(buf.toString().getBytes());
        
        profile.getTunnelHistory().store(out);
        profile.getDBHistory().store(out);
        
        if (profile.getIsExpanded()) {
            // only write out expanded data if, uh, we've got it
            profile.getCommError().store(out, "commError");
            profile.getDbIntroduction().store(out, "dbIntroduction");
            profile.getDbResponseTime().store(out, "dbResponseTime");
            profile.getReceiveSize().store(out, "receiveSize");
            profile.getSendFailureSize().store(out, "sendFailureSize");
            profile.getSendSuccessSize().store(out, "sendSuccessSize");
            profile.getTunnelCreateResponseTime().store(out, "tunnelCreateResponseTime");
            profile.getTunnelTestResponseTime().store(out, "tunnelTestResponseTime");
        }
    }
    
    public Set readProfiles() {
        long start = _context.clock().now();
        Set files = selectFiles();
        Set profiles = new HashSet(files.size());
        for (Iterator iter = files.iterator(); iter.hasNext();) {
            File f = (File)iter.next();
            PeerProfile profile = readProfile(f);
            if (profile != null)
                profiles.add(profile);
        }
        long duration = _context.clock().now() - start;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Loading " + profiles.size() + " took " + duration + "ms");
        return profiles;
    }
    
    private Set selectFiles() {
        File files[] = getProfileDir().listFiles(new FilenameFilter() {
            public boolean accept(File dir, String filename) {
                return (filename.startsWith("profile-") && filename.endsWith(".dat"));
            }
        });
        Set rv = new HashSet(files.length);
        for (int i = 0; i < files.length; i++)
            rv.add(files[i]);
        return rv;
    }
    public PeerProfile readProfile(File file) {
        Hash peer = getHash(file.getName());
        try {
            if (peer == null) {
                _log.error("The file " + file.getName() + " is not a valid hash");
                return null;
            }
            PeerProfile profile = new PeerProfile(_context, peer);
            Properties props = new Properties();
            
            loadProps(props, file);
            
            profile.setReliabilityBonus(getLong(props, "reliabilityBonus"));
            profile.setIntegrationBonus(getLong(props, "integrationBonus"));
            profile.setSpeedBonus(getLong(props, "speedBonus"));
            
            profile.setLastHeardAbout(getLong(props, "lastHeardAbout"));
            profile.setFirstHeardAbout(getLong(props, "firstHeardAbout"));
            profile.setLastSendSuccessful(getLong(props, "lastSentToSuccessfully"));
            profile.setLastSendFailed(getLong(props, "lastFailedSend"));
            profile.setLastHeardFrom(getLong(props, "lastHeardFrom"));
            
            profile.getTunnelHistory().load(props);
            profile.getDBHistory().load(props);
            
            profile.getCommError().load(props, "commError", true);
            profile.getDbIntroduction().load(props, "dbIntroduction", true);
            profile.getDbResponseTime().load(props, "dbResponseTime", true);
            profile.getReceiveSize().load(props, "receiveSize", true);
            profile.getSendFailureSize().load(props, "sendFailureSize", true);
            profile.getSendSuccessSize().load(props, "sendSuccessSize", true);
            profile.getTunnelCreateResponseTime().load(props, "tunnelCreateResponseTime", true);
            profile.getTunnelTestResponseTime().load(props, "tunnelTestResponseTime", true);
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Loaded the profile for " + peer.toBase64() + " from " + file.getName());
            
            return profile;
        } catch (IllegalArgumentException iae) {
            _log.error("Error loading profile from " +file.getName(), iae);
            file.delete();
            return null;
        }
    }
    
    private final static long getLong(Properties props, String key) {
        String val = props.getProperty(key);
        if (val != null) {
            try {
                return Long.parseLong(val);
            } catch (NumberFormatException nfe) {
                return 0;
            }
        }
        return 0;
    }
    
    private void loadProps(Properties props, File file) {
        try {
            FileInputStream fin = new FileInputStream(file);
            int c = fin.read(); 
            fin.close();
            fin = new FileInputStream(file); // ghetto mark+reset
            if (c == '#') {
                // uncompressed
                if (_log.shouldLog(Log.INFO))
                    _log.info("Loading uncompressed profile data from " + file.getName());
                DataHelper.loadProps(props, fin);
            } else {
                // compressed (or corrupt...)
                if (_log.shouldLog(Log.INFO))
                    _log.info("Loading compressed profile data from " + file.getName());
                DataHelper.loadProps(props, new GZIPInputStream(fin));
            }
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error loading properties from " + file.getName(), ioe);
        }
    }
    
    private Hash getHash(String name) {
        String key = name.substring("profile-".length());
        key = key.substring(0, key.length() - ".dat".length());
        Hash h = new Hash();
        try {
            h.fromBase64(key);
            return h;
        } catch (DataFormatException dfe) {
            _log.warn("Invalid base64 [" + key + "]", dfe);
            return null;
        }
    }
    
    private File pickFile(PeerProfile profile) {
        return new File(getProfileDir(), "profile-" + profile.getPeer().toBase64() + ".dat");
    }
    
    private File getProfileDir() {
        if (_profileDir == null) {
            String dir = null;
            if (_context.router() == null) {
                dir = _context.getProperty(PROP_PEER_PROFILE_DIR, DEFAULT_PEER_PROFILE_DIR);
            } else {
                dir = _context.router().getConfigSetting(PROP_PEER_PROFILE_DIR);
                if (dir == null) {
                    _log.info("No peer profile dir specified [" + PROP_PEER_PROFILE_DIR 
                              + "], using [" + DEFAULT_PEER_PROFILE_DIR + "]");
                    dir = DEFAULT_PEER_PROFILE_DIR;
                }
            }
            _profileDir = new File(dir);
        }
        return _profileDir;
    }
    
    /** generate 1000 profiles */
    public static void main(String args[]) {
        System.out.println("Generating 1000 profiles");
        File dir = new File("profiles");
        dir.mkdirs();
        byte data[] = new byte[32];
        java.util.Random rnd = new java.util.Random();
        for (int i = 0; i < 1000; i++) {
            rnd.nextBytes(data);
            Hash peer = new Hash(data);
            try {
                File f = new File(dir, "profile-" + peer.toBase64() + ".dat");
                f.createNewFile();
                System.out.println("Created " + peer.toBase64());
            } catch (IOException ioe) {}
        }
        System.out.println("1000 peers created in " + dir.getAbsolutePath());
    }
}
