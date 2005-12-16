package org.klomp.snark.web;

import java.io.*;
import java.util.*;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServlet;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import org.klomp.snark.*;

/**
 *
 */
public class I2PSnarkServlet extends HttpServlet {
    private I2PAppContext _context;
    private Log _log;
    private SnarkManager _manager;
    private static long _nonce;
    
    public static final String PROP_CONFIG_FILE = "i2psnark.configFile";
    
    public void init(ServletConfig cfg) throws ServletException {
        super.init(cfg);
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(I2PSnarkServlet.class);
        _nonce = _context.random().nextLong();
        _manager = SnarkManager.instance();
        String configFile = _context.getProperty(PROP_CONFIG_FILE);
        if ( (configFile == null) || (configFile.trim().length() <= 0) )
            configFile = "i2psnark.config";
        _manager.loadConfig(configFile);
    }
    
    public void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.setCharacterEncoding("UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("text/html; charset=UTF-8");
        
        String nonce = req.getParameter("nonce");
        if ( (nonce != null) && (nonce.equals(String.valueOf(_nonce))) )
            processRequest(req);
        
        PrintWriter out = resp.getWriter();
        out.write(HEADER_BEGIN);
        // we want it to go to the base URI so we don't refresh with some funky action= value
        out.write("<meta http-equiv=\"refresh\" content=\"60;" + req.getRequestURI() + "\">\n");
        out.write(HEADER);
        out.write("<textarea class=\"snarkMessages\" rows=\"2\" cols=\"100\" wrap=\"off\" >");
        List msgs = _manager.getMessages();
        for (int i = msgs.size()-1; i >= 0; i--) {
            String msg = (String)msgs.get(i);
            out.write(msg + "\n");
        }
        out.write("</textarea>\n");

        out.write(TABLE_HEADER);

        List snarks = getSortedSnarks(req);
        String uri = req.getRequestURI();
        for (int i = 0; i < snarks.size(); i++) {
            Snark snark = (Snark)snarks.get(i);
            displaySnark(out, snark, uri, i);
        }
        if (snarks.size() <= 0) {
            out.write(TABLE_EMPTY);
        }
        
        out.write(TABLE_FOOTER);
        writeAddForm(out, req);
        writeConfigForm(out, req);
        out.write(FOOTER);
    }
    
    /**
     * Do what they ask, adding messages to _manager.addMessage as necessary
     */
    private void processRequest(HttpServletRequest req) {
        String action = req.getParameter("action");
        if (action == null) {
            // noop
        } else if ("Add torrent".equals(action)) {
            String newFile = req.getParameter("newFile");
            String newURL = req.getParameter("newURL");
            File f = null;
            if ( (newFile != null) && (newFile.trim().length() > 0) )
                f = new File(newFile.trim());
            if ( (f != null) && (!f.exists()) ) {
                _manager.addMessage("Torrent file " + newFile +" does not exist");
            }
            if ( (f != null) && (f.exists()) ) {
                File local = new File(_manager.getDataDir(), f.getName());
                String canonical = null;
                try {
                    canonical = local.getCanonicalPath();
                    
                    if (local.exists()) {
                        if (_manager.getTorrent(canonical) != null)
                            _manager.addMessage("Torrent already running: " + newFile);
                        else
                            _manager.addMessage("Torrent already in the queue: " + newFile);
                    } else {
                        boolean ok = FileUtil.copy(f.getAbsolutePath(), local.getAbsolutePath(), true);
                        if (ok) {
                            _manager.addMessage("Copying torrent to " + local.getAbsolutePath());
                            _manager.addTorrent(canonical);
                        } else {
                            _manager.addMessage("Unable to copy the torrent to " + local.getAbsolutePath() + " from " + f.getAbsolutePath());
                        }
                    }
                } catch (IOException ioe) {
                    _log.warn("hrm: " + local, ioe);
                }
            } else if ( (newURL != null) && (newURL.trim().length() > "http://.i2p/".length()) ) {
                _manager.addMessage("Fetching " + newURL);
                I2PThread fetch = new I2PThread(new FetchAndAdd(newURL), "Fetch and add");
                fetch.start();
            } else {
                // no file or URL specified
            }
        } else if ("Stop".equals(action)) {
            String torrent = req.getParameter("torrent");
            if (torrent != null) {
                byte infoHash[] = Base64.decode(torrent);
                if ( (infoHash != null) && (infoHash.length == 20) ) { // valid sha1
                    for (Iterator iter = _manager.listTorrentFiles().iterator(); iter.hasNext(); ) {
                        String name = (String)iter.next();
                        Snark snark = _manager.getTorrent(name);
                        if ( (snark != null) && (DataHelper.eq(infoHash, snark.meta.getInfoHash())) ) {
                            _manager.stopTorrent(name, false);
                            break;
                        }
                    }
                }
            }
        } else if ("Start".equals(action)) {
            String torrent = req.getParameter("torrent");
            if (torrent != null) {
                byte infoHash[] = Base64.decode(torrent);
                if ( (infoHash != null) && (infoHash.length == 20) ) { // valid sha1
                    for (Iterator iter = _manager.listTorrentFiles().iterator(); iter.hasNext(); ) {
                        String name = (String)iter.next();
                        Snark snark = _manager.getTorrent(name);
                        if ( (snark != null) && (DataHelper.eq(infoHash, snark.meta.getInfoHash())) ) {
                            snark.startTorrent();
                            _manager.addMessage("Starting up torrent " + name);
                            break;
                        }
                    }
                }
            }
        } else if ("Remove".equals(action)) {
            String torrent = req.getParameter("torrent");
            if (torrent != null) {
                byte infoHash[] = Base64.decode(torrent);
                if ( (infoHash != null) && (infoHash.length == 20) ) { // valid sha1
                    for (Iterator iter = _manager.listTorrentFiles().iterator(); iter.hasNext(); ) {
                        String name = (String)iter.next();
                        Snark snark = _manager.getTorrent(name);
                        if ( (snark != null) && (DataHelper.eq(infoHash, snark.meta.getInfoHash())) ) {
                            _manager.stopTorrent(name, true);
                            // should we delete the torrent file?
                            break;
                        }
                    }
                }
            }
        } else if ("Delete".equals(action)) {
            String torrent = req.getParameter("torrent");
            if (torrent != null) {
                byte infoHash[] = Base64.decode(torrent);
                if ( (infoHash != null) && (infoHash.length == 20) ) { // valid sha1
                    for (Iterator iter = _manager.listTorrentFiles().iterator(); iter.hasNext(); ) {
                        String name = (String)iter.next();
                        Snark snark = _manager.getTorrent(name);
                        if ( (snark != null) && (DataHelper.eq(infoHash, snark.meta.getInfoHash())) ) {
                            _manager.stopTorrent(name, true);
                            File f = new File(name);
                            f.delete();
                            _manager.addMessage("Torrent file deleted: " + f.getAbsolutePath());
                            List files = snark.meta.getFiles();
                            String dataFile = snark.meta.getName();
                            for (int i = 0; files != null && i < files.size(); i++) {
                                File df = new File(_manager.getDataDir(), (String)files.get(i));
                                boolean deleted = FileUtil.rmdir(df, false);
                                if (deleted)
                                    _manager.addMessage("Data dir deleted: " + df.getAbsolutePath());
                                else
                                    _manager.addMessage("Data dir could not be deleted: " + df.getAbsolutePath());
                            }
                            if (dataFile != null) {
                                f = new File(_manager.getDataDir(), dataFile);
                                boolean deleted = f.delete();
                                if (deleted)
                                    _manager.addMessage("Data file deleted: " + f.getAbsolutePath());
                                else
                                    _manager.addMessage("Data file could not be deleted: " + f.getAbsolutePath());
                            }
                            break;
                        }
                    }
                }
            }
        } else if ("Save configuration".equals(action)) {
            String dataDir = req.getParameter("dataDir");
            boolean autoStart = req.getParameter("autoStart") != null;
            String seedPct = req.getParameter("seedPct");
            String eepHost = req.getParameter("eepHost");
            String eepPort = req.getParameter("eepPort");
            String i2cpHost = req.getParameter("i2cpHost");
            String i2cpPort = req.getParameter("i2cpPort");
            String i2cpOpts = req.getParameter("i2cpOpts");
            _manager.updateConfig(dataDir, autoStart, seedPct, eepHost, eepPort, i2cpHost, i2cpPort, i2cpOpts);
        }
    }
    
    private class FetchAndAdd implements Runnable {
        private String _url;
        public FetchAndAdd(String url) {
            _url = url;
        }
        public void run() {
            _url = _url.trim();
            File file = I2PSnarkUtil.instance().get(_url, false);
            try {
                if ( (file != null) && (file.exists()) && (file.length() > 0) ) {
                    _manager.addMessage("Torrent fetched from " + _url);
                    FileInputStream in = null;
                    try {
                        in = new FileInputStream(file);
                        MetaInfo info = new MetaInfo(in);
                        String name = info.getName();
                        name = name.replace('/', '_');
                        name = name.replace('\\', '_');
                        name = name.replace('&', '+');
                        name = name.replace('\'', '_');
                        name = name.replace('"', '_');
                        name = name.replace('`', '_');
                        name = name + ".torrent";
                        File torrentFile = new File(_manager.getDataDir(), name);
                        
                        String canonical = torrentFile.getCanonicalPath();
                        
                        if (torrentFile.exists()) {
                            if (_manager.getTorrent(canonical) != null)
                                _manager.addMessage("Torrent already running: " + name);
                            else
                                _manager.addMessage("Torrent already in the queue: " + name);
                        } else {
                            FileUtil.copy(file.getAbsolutePath(), canonical, true);
                            _manager.addTorrent(canonical);
                        }
                    } catch (IOException ioe) {
                        _manager.addMessage("Torrent at " + _url + " was not valid: " + ioe.getMessage());
                    } finally {
                        try { in.close(); } catch (IOException ioe) {}
                    }
                } else {
                    _manager.addMessage("Torrent was not retrieved from " + _url);
                }
            } finally {
                if (file != null) file.delete();
            }
        }
    }
    
    private List getSortedSnarks(HttpServletRequest req) {
        Set files = _manager.listTorrentFiles();
        TreeSet fileNames = new TreeSet(files); // sorts it alphabetically
        ArrayList rv = new ArrayList(fileNames.size());
        for (Iterator iter = fileNames.iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            Snark snark = _manager.getTorrent(name);
            if (snark != null)
                rv.add(snark);
        }
        return rv;
    }
    
    private static final int MAX_DISPLAYED_FILENAME_LENGTH = 60;
    private void displaySnark(PrintWriter out, Snark snark, String uri, int row) throws IOException {
        String filename = snark.torrent;
        File f = new File(filename);
        filename = f.getName(); // the torrent may be the canonical name, so lets just grab the local name
        if (filename.length() > MAX_DISPLAYED_FILENAME_LENGTH)
            filename = filename.substring(0, MAX_DISPLAYED_FILENAME_LENGTH) + "...";
        long total = snark.meta.getTotalLength();
        long remaining = snark.storage.needed() * snark.meta.getPieceLength(0); 
        if (remaining > total)
            remaining = total;
        int totalBps = 4096; // should probably grab this from the snark...
        long remainingSeconds = remaining / totalBps;
        long uploaded = snark.coordinator.getUploaded();
        
        boolean isRunning = !snark.stopped;
        boolean isValid = snark.meta != null;
        
        String err = snark.coordinator.trackerProblems;
        int curPeers = snark.coordinator.getPeerCount();
        int knownPeers = snark.coordinator.trackerSeenPeers;
        
        String statusString = "Unknown";
        if (err != null) {
            if (isRunning)
                statusString = "TrackerErr (" + curPeers + "/" + knownPeers + " peers)";
            else
                statusString = "TrackerErr (" + err + ")";
        } else if (remaining <= 0) {
            if (isRunning)
                statusString = "Seeding (" + curPeers + "/" + knownPeers + " peers)";
            else
                statusString = "Complete";
        } else {
            if (isRunning)
                statusString = "OK (" + curPeers + "/" + knownPeers + " peers)";
            else
                statusString = "Stopped";
        }
        
        String rowClass = (row % 2 == 0 ? "snarkTorrentEven" : "snarkTorrentOdd");
        out.write("<tr class=\"" + rowClass + "\">");
        out.write("<td valign=\"top\" align=\"left\" class=\"snarkTorrentStatus " + rowClass + "\">");
        out.write(statusString + "</td>\n\t");
        out.write("<td valign=\"top\" align=\"left\" class=\"snarkTorrentName " + rowClass + "\">");
        out.write(filename + "</td>\n\t");
        out.write("<td valign=\"top\" align=\"left\" class=\"snarkTorrentDownloaded " + rowClass + "\">");
        if (remaining > 0) {
            out.write(formatSize(total-remaining) + "/" + formatSize(total)); // 18MB/3GB
            // lets hold off on the ETA until we have rates sorted...
            //out.write(" (eta " + DataHelper.formatDuration(remainingSeconds*1000) + ")"); // (eta 6h)
        } else {
            out.write(formatSize(total)); // 3GB
        }
        out.write("</td>\n\t");
        out.write("<td valign=\"top\" align=\"left\" class=\"snarkTorrentUploaded " + rowClass 
                  + "\">" + formatSize(uploaded) + "</td>\n\t");
        //out.write("<td valign=\"top\" align=\"left\" class=\"snarkTorrentRate\">");
        //out.write("n/a"); //2KBps/12KBps/4KBps
        //out.write("</td>\n\t");
        out.write("<td valign=\"top\" align=\"left\" class=\"snarkTorrentAction " + rowClass + "\">");
        if (isRunning) {
            out.write("<a href=\"" + uri + "?action=Stop&nonce=" + _nonce 
                      + "&torrent=" + Base64.encode(snark.meta.getInfoHash())
                      + "\" title=\"Stop the torrent\">Stop</a>");
        } else {
            if (isValid)
                out.write("<a href=\"" + uri + "?action=Start&nonce=" + _nonce 
                          + "&torrent=" + Base64.encode(snark.meta.getInfoHash())
                          + "\" title=\"Start the torrent\">Start</a> ");
            out.write("<a href=\"" + uri + "?action=Remove&nonce=" + _nonce 
                      + "&torrent=" + Base64.encode(snark.meta.getInfoHash())
                      + "\" title=\"Remove the torrent from the active list, deleting the .torrent file\">Remove</a><br />");
            out.write("<a href=\"" + uri + "?action=Delete&nonce=" + _nonce 
                      + "&torrent=" + Base64.encode(snark.meta.getInfoHash())
                      + "\" title=\"Delete the .torrent file and the associated data file(s)\">Delete</a> ");
        }
        out.write("</td>\n</tr>\n");
    }
    
    private void writeAddForm(PrintWriter out, HttpServletRequest req) throws IOException {
        String uri = req.getRequestURI();
        String newURL = req.getParameter("newURL");
        if ( (newURL == null) || (newURL.trim().length() <= 0) ) newURL = "http://";
        String newFile = req.getParameter("newFile");
        if ( (newFile == null) || (newFile.trim().length() <= 0) ) newFile = "";
        
        out.write("<span class=\"snarkNewTorrent\">\n");
        // *not* enctype="multipart/form-data", so that the input type=file sends the filename, not the file
        out.write("<form action=\"" + uri + "\" method=\"POST\">\n");
        out.write("<input type=\"hidden\" name=\"nonce\" value=\"" + _nonce + "\" />\n");
        out.write("From URL&nbsp;: <input type=\"text\" name=\"newURL\" size=\"50\" value=\"" + newURL + "\" /> \n");
        // not supporting from file at the moment, since the file name passed isn't always absolute (so it may not resolve)
        //out.write("From file: <input type=\"file\" name=\"newFile\" size=\"50\" value=\"" + newFile + "\" /><br />\n");
        out.write("<input type=\"submit\" value=\"Add torrent\" name=\"action\" /><br />\n");
        out.write("Alternately, you can copy .torrent files to " + _manager.getDataDir().getAbsolutePath() + "<br />\n");
        out.write("</form>\n</span>\n");
    }
    
    private void writeConfigForm(PrintWriter out, HttpServletRequest req) throws IOException {
        String uri = req.getRequestURI();
        String dataDir = _manager.getDataDir().getAbsolutePath();
        boolean autoStart = _manager.shouldAutoStart();
        int seedPct = 0;
       
        out.write("<span class=\"snarkConfig\">\n");
        out.write("<form action=\"" + uri + "\" method=\"POST\">\n");
        out.write("<input type=\"hidden\" name=\"nonce\" value=\"" + _nonce + "\" />\n");
        out.write("<hr /><span class=\"snarkConfigTitle\">Configuration:</span><br />\n");
        out.write("Data directory: <input type=\"text\" size=\"40\" name=\"dataDir\" value=\"" + dataDir + "\" ");
        out.write("title=\"Directory to store torrents and data\" disabled=\"true\" /><br />\n");
        out.write("Auto start: <input type=\"checkbox\" name=\"autoStart\" value=\"true\" " 
                  + (autoStart ? "checked " : "") 
                  + "title=\"If true, automatically start torrents that are added\" disabled=\"true\" />");
        //Auto add: <input type="checkbox" name="autoAdd" value="true" title="If true, automatically add torrents that are found in the data directory" />
        //Auto stop: <input type="checkbox" name="autoStop" value="true" title="If true, automatically stop torrents that are removed from the data directory" />
        //out.write("<br />\n");
        out.write("Seed percentage: <select name=\"seedPct\" disabled=\"true\" >\n\t");
        if (seedPct <= 0)
            out.write("<option value=\"0\" selected=\"true\">Unlimited</option>\n\t");
        else
            out.write("<option value=\"0\">Unlimited</option>\n\t");
        if (seedPct == 100)
            out.write("<option value=\"100\" selected=\"true\">100%</option>\n\t");
        else
            out.write("<option value=\"100\">100%</option>\n\t");
        if (seedPct == 150)
            out.write("<option value=\"150\" selected=\"true\">150%</option>\n\t");
        else
            out.write("<option value=\"150\">150%</option>\n\t");
        out.write("</select><br />\n");
        
        out.write("<hr />\n");
        out.write("EepProxy host: <input type=\"text\" name=\"eepHost\" value=\""
                  + I2PSnarkUtil.instance().getEepProxyHost() + "\" size=\"15\" /> ");
        out.write("EepProxy port: <input type=\"text\" name=\"eepPort\" value=\""
                  + I2PSnarkUtil.instance().getEepProxyPort() + "\" size=\"5\" /> <br />\n");
        out.write("I2CP host: <input type=\"text\" name=\"i2cpHost\" value=\"" 
                  + I2PSnarkUtil.instance().getI2CPHost() + "\" size=\"15\" /> ");
        out.write("I2CP port: <input type=\"text\" name=\"i2cpPort\" value=\"" +
                  + I2PSnarkUtil.instance().getI2CPPort() + "\" size=\"5\" /> <br />\n");
        StringBuffer opts = new StringBuffer(64);
        Map options = new TreeMap(I2PSnarkUtil.instance().getI2CPOptions());
        for (Iterator iter = options.keySet().iterator(); iter.hasNext(); ) {
            String key = (String)iter.next();
            String val = (String)options.get(key);
            opts.append(key).append('=').append(val).append(' ');
        }
        out.write("I2CP options: <input type=\"text\" name=\"i2cpOpts\" size=\"80\" value=\""
                  + opts.toString() + "\" /><br />\n");
        out.write("<input type=\"submit\" value=\"Save configuration\" name=\"action\" />\n");
        out.write("</form>\n</span>\n");
    }
    
    private String formatSize(long bytes) {
        if (bytes < 5*1024)
            return bytes + "B";
        else if (bytes < 5*1024*1024)
            return (bytes/1024) + "KB";
        else if (bytes < 5*1024*1024*1024)
            return (bytes/(1024*1024)) + "MB";
        else
            return (bytes/(1024*1024*1024)) + "GB";
    }
    
    private static final String HEADER_BEGIN = "<html>\n" +
                                               "<head>\n" +
                                               "<title>I2PSnark - anonymous bittorrent</title>\n";
                                         
    private static final String HEADER = "<style>\n" +
                                         "body {\n" +
                                         "	background-color: #C7CFB4;\n" +
                                         "}\n" +
                                         ".snarkTitle {\n" +
                                         "	text-align: left;\n" +
                                         "	float: left;\n" +
                                         "	margin: 0px 0px 5px 5px;\n" +
                                         "	display: inline;\n" +
                                         "	font-size: 16pt;\n" +
                                         "	font-weight: bold;\n" +
                                         "}\n" +
                                         ".snarkMessages {\n" +
                                         "	border: none;\n" +
                                         "                  background-color: #CECFC6;\n" +
                                         "                  font-family: monospace;\n" +
                                         "                  font-size: 10pt;\n" +
                                         "                  font-weight: 100;\n" +
                                         "}\n" +
                                         "table {\n" +
                                         "	margin: 0px 0px 0px 0px;\n" +
                                         "	border: 0px;\n" +
                                         "	padding: 0px;\n" +
                                         "	border-width: 0px;\n" +
                                         "	border-spacing: 0px;\n" +
                                         "}\n" +
                                         "th {\n" +
                                         "	background-color: #C7D5D5;\n" +
                                         "	margin: 0px 0px 0px 0px;\n" +
                                         "}\n" +
                                         ".snarkTorrentEven {\n" +
                                         "	background-color: #E7E7E7;\n" +
                                         "}\n" +
                                         ".snarkTorrentOdd {\n" +
                                         "	background-color: #DDDDCC;\n" +
                                         "}\n" +
                                         ".snarkNewTorrent {\n" +
                                         "	font-size: 12pt;\n" +
                                         "	font-family: monospace;\n" +
                                         "	background-color: #ADAE9;\n" +
                                         "}\n" +
                                         ".snarkConfigTitle {\n" +
                                         "	font-size: 16pt;\n" +
                                         "                  font-weight: bold;\n" +
                                         "}\n" +
                                         "</style>\n" +
                                         "</head>\n" +
                                         "<body>\n" +
                                         "<p class=\"snarkTitle\">I2PSnark&nbsp;</p>\n";


    private static final String TABLE_HEADER = "<table border=\"0\" class=\"snarkTorrents\" width=\"100%\">\n" +
                                               "<thead>\n" +
                                               "<tr><th align=\"left\" valign=\"top\">Status</th>\n" +
                                               "    <th align=\"left\" valign=\"top\">Torrent</th>\n" +
                                               "    <th align=\"left\" valign=\"top\">Downloaded</th>\n" +
                                               "    <th align=\"left\" valign=\"top\">Uploaded</th>\n" +
                                               //"    <th align=\"left\" valign=\"top\">Rate</th>\n" +
                                               "    <th>&nbsp;</th></tr>\n" +
                                               "</thead>\n";
    
   private static final String TABLE_EMPTY  = "<tr class=\"snarkTorrentEven\">" +
                                              "<td class=\"snarkTorrentEven\" align=\"left\"" +
                                              "    valign=\"top\" colspan=\"5\">No torrents</td></tr>\n";

    private static final String TABLE_FOOTER = "</table>\n";
    
    private static final String FOOTER = "</body></html>";
}