package net.i2p.time;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Periodically query a series of NTP servers and post the offset 
 * to a given URL.  It tries the NTP servers in order, contacting them
 * using UDP port 123, and sends the current date to the URL specified
 * (specifically, URL+"&now=" + yyyyMMdd_HH:mm:ss.SSS in the UK locale).
 * It does this every 5 minutes, forever.
 *
 * Usage: <pre>
 *  Timestamper URL ntpServer1[ ntpServer2]*
 * </pre>
 */
public class Timestamper implements Runnable {
    private static Log _log = new Log(Timestamper.class);
    private static String _targetURL;
    private static String _serverList[];
    
    private int DELAY_MS = 5*60*1000;
    
    public Timestamper() {}
    
    public void startTimestamper() {
        if (_log.shouldLog(Log.INFO))
            _log.info("Starting timestamper pointing at " + _targetURL);
        synchronized (Timestamper.class) {
            String enabled = System.getProperty("timestamper.enabled");
            if (enabled != null) {
                _log.warn("Timestamper already running");
                return;
            } else {
                System.setProperty("timestamper.enabled", "true");
            }
        }
        I2PThread t = new I2PThread(this, "Timestamper");
        t.setPriority(I2PThread.MIN_PRIORITY);
        t.start();
    }
    
    public void run() {
        if (_log.shouldLog(Log.INFO))
            _log.info("Starting up timestamper");
        try {
            while (true) {
                String enabled = System.getProperty("timestamper.enabled");
                if ( (enabled == null) || (!"true".equals(enabled)) ) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Not stamping the time");
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Querying servers " + _serverList);
                    try {
                        long now = NtpClient.currentTime(_serverList);
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Stamp time");
                        stampTime(now);
                    } catch (IllegalArgumentException iae) {
                        _log.log(Log.CRIT, "Unable to reach any of the NTP servers - network disconnected?");
                    }
                }
                try { Thread.sleep(DELAY_MS); } catch (InterruptedException ie) {}
            }
        } catch (Throwable t) {
            _log.log(Log.CRIT, "Timestamper died!", t);
        }
    }
    
    /**
     * Send an HTTP request to a given URL specifying the current time 
     */
    private void stampTime(long now) {
        try {
            String toRequest = _targetURL + "&now=" + getNow(now);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Stamping [" + toRequest + "]");
            URL url = new URL(toRequest);
            Object o = url.getContent();
            // ignore the content
        } catch (MalformedURLException mue) {
            _log.error("Invalid URL", mue);
        } catch (IOException ioe) {
            _log.error("Error stamping the time", ioe);
        }
    }
    
    private SimpleDateFormat _fmt = new SimpleDateFormat("yyyyMMdd_HH:mm:ss.SSS", Locale.UK);
    private String getNow(long now) {
        synchronized (_fmt) {
            return _fmt.format(new Date(now));
        }
    }
    
    public static void main(String args[]) {
        if ( (args == null) || (args.length < 2) ) {
            usage();
            return;
            //args = new String[] { "http://dev.i2p.net:80/somePath?pass=password", "ntp1.sth.netnod.se", "ntp2.sth.netnod.se" };
        } 
        String servers[] = new String[args.length-1];
        System.arraycopy(args, 1, servers, 0, servers.length);
        _targetURL = args[0];
        _serverList = servers;
        Timestamper ts = new Timestamper();
        ts.startTimestamper();
    }
    
    private static void usage() {
        System.err.println("Usage: Timestamper URL ntpServer[ ntpServer]*");
        _log.error("Usage: Timestamper URL ntpServer[ ntpServer]*");
    }
}