package net.i2p.router.web;

import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.crypto.TrustedUpdate;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterVersion;
import net.i2p.util.EepGet;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * <p>Handles the request to update the router by firing off an
 * {@link net.i2p.util.EepGet} call to download the latest signed update file
 * and displaying the status to anyone who asks.
 * </p>
 * <p>After the download completes the signed update file is verified with
 * {@link net.i2p.crypto.TrustedUpdate}, and if it's authentic the payload
 * of the signed update file is unpacked and the router is restarted to complete
 * the update process.
 * </p>
 */
public class UpdateHandler {
    protected static UpdateRunner _updateRunner;
    protected RouterContext _context;
    protected Log _log;
    protected DecimalFormat _pct = new DecimalFormat("00.0%");
    
    protected static final String SIGNED_UPDATE_FILE = "i2pupdate.sud";
    protected static final String PROP_UPDATE_IN_PROGRESS = "net.i2p.router.web.UpdateHandler.updateInProgress";

    public UpdateHandler() {
        this(ContextHelper.getContext(null));
    }
    public UpdateHandler(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(UpdateHandler.class);
    }
    
    /**
     * Configure this bean to query a particular router context
     *
     * @param contextId begging few characters of the routerHash, or null to pick
     *                  the first one we come across.
     */
    public void setContextId(String contextId) {
        try {
            _context = ContextHelper.getContext(contextId);
            _log = _context.logManager().getLog(UpdateHandler.class);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
    
    
    public void setUpdateNonce(String nonce) { 
        if (nonce == null) return;
        if (nonce.equals(System.getProperty("net.i2p.router.web.UpdateHandler.nonce")) ||
            nonce.equals(System.getProperty("net.i2p.router.web.UpdateHandler.noncePrev"))) {
            update();
        }
    }

    public void update() {
        // don't block waiting for the other one to finish
        if ("true".equals(System.getProperty(PROP_UPDATE_IN_PROGRESS, "false"))) {
            _log.error("Update already running");
            return;
        }
        synchronized (UpdateHandler.class) {
            if (_updateRunner == null)
                _updateRunner = new UpdateRunner();
            if (_updateRunner.isRunning()) {
                return;
            } else {
                System.setProperty(PROP_UPDATE_IN_PROGRESS, "true");
                I2PThread update = new I2PThread(_updateRunner, "Update");
                update.start();
            }
        }
    }
    
    public String getStatus() {
        if (_updateRunner == null)
            return "";
        return _updateRunner.getStatus();
    }
    
    public class UpdateRunner implements Runnable, EepGet.StatusListener {
        protected boolean _isRunning;
        protected String _status;
        public UpdateRunner() { 
            _isRunning = false; 
            _status = "<b>Updating</b>";
        }
        public boolean isRunning() { return _isRunning; }
        public String getStatus() { return _status; }
        public void run() {
            _isRunning = true;
            update();
            System.setProperty(PROP_UPDATE_IN_PROGRESS, "false");
            _isRunning = false;
        }
        protected void update() {
            _status = "<b>Updating</b>";
            String updateURL = selectUpdateURL();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Selected update URL: " + updateURL);
            boolean shouldProxy = Boolean.valueOf(_context.getProperty(ConfigUpdateHandler.PROP_SHOULD_PROXY, ConfigUpdateHandler.DEFAULT_SHOULD_PROXY)).booleanValue();
            String proxyHost = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST, ConfigUpdateHandler.DEFAULT_PROXY_HOST);
            String port = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_PORT, ConfigUpdateHandler.DEFAULT_PROXY_PORT);
            int proxyPort = -1;
            try {
                proxyPort = Integer.parseInt(port);
            } catch (NumberFormatException nfe) {
                return;
            }
            try {
                EepGet get = null;
                if (shouldProxy)
                    get = new EepGet(_context, proxyHost, proxyPort, 20, SIGNED_UPDATE_FILE, updateURL, false);
                else
                    get = new EepGet(_context, 1, SIGNED_UPDATE_FILE, updateURL, false);
                get.addStatusListener(UpdateRunner.this);
                get.fetch();
            } catch (Throwable t) {
                _context.logManager().getLog(UpdateHandler.class).error("Error updating", t);
            }
        }
        
        public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Attempt failed on " + url, cause);
            // ignored
        }
        public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {
            StringBuffer buf = new StringBuffer(64);
            buf.append("<b>Updating</b> ");
            double pct = ((double)alreadyTransferred + (double)currentWrite) /
                         ((double)alreadyTransferred + (double)currentWrite + (double)bytesRemaining);
            synchronized (_pct) {
                buf.append(_pct.format(pct));
            }
            buf.append(":<br />\n" + (currentWrite + alreadyTransferred));
            buf.append(" transferred");
            _status = buf.toString();
        }
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {
            _status = "<b>Update downloaded</b>";
            TrustedUpdate up = new TrustedUpdate(_context);
            String err = up.migrateVerified(RouterVersion.VERSION, SIGNED_UPDATE_FILE, Router.UPDATE_FILE);
            File f = new File(SIGNED_UPDATE_FILE);
            f.delete();
            if (err == null) {
                String policy = _context.getProperty(ConfigUpdateHandler.PROP_UPDATE_POLICY);
                if ("install".equals(policy)) {
                    _log.log(Log.CRIT, "Update was VERIFIED, restarting to install it");
                    _status = "<b>Update verified</b><br />Restarting";
                    restart();
                } else {
                    _log.log(Log.CRIT, "Update was VERIFIED, will be installed at next restart");
                    _status = "<b>Update downloaded</b><br />Click Restart to Install";
                    if (up.newVersion() != null)
                        _status += " Version " + up.newVersion();
                }
            } else {
                err = err + " from " + url;
                _log.log(Log.CRIT, err);
                _status = "<b>" + err + "</b>";
            }
        }
        public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {
            // don't display bytesTransferred as it is meaningless
            _log.log(Log.CRIT, "Update from " + url + " did not download completely (" +
                               bytesRemaining + " remaining after " + currentAttempt + " tries)");

            _status = "<b>Transfer failed</b>";
        }
        public void headerReceived(String url, int attemptNum, String key, String val) {}
        public void attempting(String url) {}
    }
    
    private void restart() {
        _context.addShutdownTask(new ConfigServiceHandler.UpdateWrapperManagerTask(Router.EXIT_GRACEFUL_RESTART));
        _context.router().shutdownGracefully(Router.EXIT_GRACEFUL_RESTART);
    }

    private String selectUpdateURL() {
        String URLs = _context.getProperty(ConfigUpdateHandler.PROP_UPDATE_URL, ConfigUpdateHandler.DEFAULT_UPDATE_URL);
        StringTokenizer tok = new StringTokenizer(URLs, " ,\r\n");
        List URLList = new ArrayList();
        while (tok.hasMoreTokens())
            URLList.add(tok.nextToken().trim());
        int size = URLList.size();
        _log.log(Log.DEBUG, "Picking update source from " + size + " candidates.");
        if (size <= 0) {
            _log.log(Log.WARN, "Update list is empty - no update available");
            return null;
        }
        int index = I2PAppContext.getGlobalContext().random().nextInt(size);
        _log.log(Log.DEBUG, "Picked update source " + index + ".");
        return (String) URLList.get(index);
    }
}
