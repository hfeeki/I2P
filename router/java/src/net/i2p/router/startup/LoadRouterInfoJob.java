package net.i2p.router.startup;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import net.i2p.data.DataFormatException;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.RouterInfo;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.KeyManager;
import net.i2p.router.Router;
import net.i2p.router.MessageHistory;
import net.i2p.util.Log;

public class LoadRouterInfoJob extends JobImpl {
    private static Log _log = new Log(LoadRouterInfoJob.class);
    private boolean _keysExist;
    private boolean _infoExists;
    private RouterInfo _us;
    
    public String getName() { return "Load Router Info"; }
    
    public void runJob() {
	loadRouterInfo();
	if (_us == null) {
	    RebuildRouterInfoJob.rebuildRouterInfo(false);
	    JobQueue.getInstance().addJob(this);
	    return;
	} else {
	    Router.getInstance().setRouterInfo(_us);
	    MessageHistory.initialize();
	    JobQueue.getInstance().addJob(new BootCommSystemJob());
	}
    }
	
    private void loadRouterInfo() {
	String routerInfoFile = Router.getInstance().getConfigSetting(Router.PROP_INFO_FILENAME);
	if (routerInfoFile == null)
	    routerInfoFile = Router.PROP_INFO_FILENAME_DEFAULT;
	RouterInfo info = null;
	boolean failedRead = false;
	
	
	String keyFilename = Router.getInstance().getConfigSetting(Router.PROP_KEYS_FILENAME);
	if (keyFilename == null)
	    keyFilename = Router.PROP_KEYS_FILENAME_DEFAULT;

	File rif = new File(routerInfoFile);
	if (rif.exists())
	    _infoExists = true;
	File rkf = new File(keyFilename);
	if (rkf.exists())
	    _keysExist = true;
	
	FileInputStream fis1 = null;
	FileInputStream fis2 = null;
	try {
	    if (_infoExists) {
		fis1 = new FileInputStream(rif);
		info = new RouterInfo();
		info.readBytes(fis1);
		_log.debug("Reading in routerInfo from " + rif.getAbsolutePath() + " and it has " + info.getAddresses().size() + " addresses");
	    }
	    
	    if (_keysExist) {
		fis2 = new FileInputStream(rkf);
		PrivateKey privkey = new PrivateKey();
		privkey.readBytes(fis2);
		SigningPrivateKey signingPrivKey = new SigningPrivateKey();
		signingPrivKey.readBytes(fis2);
		PublicKey pubkey = new PublicKey();
		pubkey.readBytes(fis2);
		SigningPublicKey signingPubKey = new SigningPublicKey();
		signingPubKey.readBytes(fis2);

		KeyManager.getInstance().setPrivateKey(privkey);
		KeyManager.getInstance().setSigningPrivateKey(signingPrivKey);
		KeyManager.getInstance().setPublicKey(pubkey); //info.getIdentity().getPublicKey());
		KeyManager.getInstance().setSigningPublicKey(signingPubKey); // info.getIdentity().getSigningPublicKey());
	    }
	    
	    _us = info;
	} catch (IOException ioe) {
	    _log.error("Error reading the router info from " + routerInfoFile + " and the keys from " + keyFilename, ioe);
	    _us = null;
	    rif.delete();
	    rkf.delete();
	    _infoExists = false;
	    _keysExist = false;
	} catch (DataFormatException dfe) {
	    _log.error("Corrupt router info or keys at " + routerInfoFile + " / " + keyFilename, dfe);
	    _us = null;
	    rif.delete();
	    rkf.delete();
	    _infoExists = false;
	    _keysExist = false;
	} finally {
	    if (fis1 != null) try { fis1.close(); } catch (IOException ioe) {}
	    if (fis2 != null) try { fis2.close(); } catch (IOException ioe) {}
	}
    }
}
