package net.i2p.router;

import net.i2p.util.Log;
import net.i2p.util.Clock;
import net.i2p.util.HTTPSendData;
import net.i2p.util.I2PThread;

import net.i2p.router.transport.BandwidthLimiter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Job that, if its allowed to, will submit the data gathered by the MessageHistory
 * component to some URL so that the network can be debugged more easily.  By default
 * it does not submit any data or touch the message history file, but if the router
 * has the line "router.submitHistory=true", it will send the file that the 
 * MessageHistory component is configured to write to once an hour, post it to
 * http://i2p.net/cgi-bin/submitMessageHistory, and then delete that file
 * locally.  This should only be used if the MessageHistory component is configured to
 * gather data (via "router.keepHistory=true").
 *
 */
public class SubmitMessageHistoryJob extends JobImpl {
    private final static Log _log = new Log(SubmitMessageHistoryJob.class);
    
    /** default submitting data every hour */
    private final static long DEFAULT_REQUEUE_DELAY = 60*60*1000; 
    /** 
     * router config param for whether we want to autosubmit (and delete) the
     * history data managed by MessageHistory 
     */ 
    public final static String PARAM_SUBMIT_DATA = "router.submitHistory";
    /** default value for whether we autosubmit the data */
    public final static boolean DEFAULT_SUBMIT_DATA = true;
    /** where the data should be submitted to (via HTTP POST) */
    public final static String PARAM_SUBMIT_URL = "router.submitHistoryURL";
    /** default location */
    public final static String DEFAULT_SUBMIT_URL = "http://i2p.net/cgi-bin/submitMessageHistory";
    
    public void runJob() {
	if (shouldSubmit()) {
	    submit();
	} else {
	    _log.debug("Not submitting data");
	    // if we didn't submit we can just requeue
	    requeue(getRequeueDelay());
	}
    }
    
    /**
     * We don't want this to be run within the jobqueue itself, so fire off a new thread
     * to do the actual submission, enqueueing a new submit job when its done
     */
    private void submit() {
	I2PThread t = new I2PThread(new Runnable() {
	    public void run() { 
		_log.debug("Submitting data");
		MessageHistory.getInstance().setPauseFlushes(true);
		String filename = MessageHistory.getInstance().getFilename();
		send(filename);
		MessageHistory.getInstance().setPauseFlushes(false);
		Job job = new SubmitMessageHistoryJob();
		job.getTiming().setStartAfter(Clock.getInstance().now() + getRequeueDelay());
		JobQueue.getInstance().addJob(job);
	    }
	});
	t.setName("SubmitData");
	t.setPriority(I2PThread.MIN_PRIORITY);
	t.setDaemon(true);
	t.start();
    }
    
    private void send(String filename) {
	String url = getURL();
	try {
	    File dataFile = new File(filename);
	    if (!dataFile.exists() || !dataFile.canRead()) {
		_log.warn("Unable to read the message data file [" + dataFile.getAbsolutePath() + "]");
		return;
	    }
	    long size = dataFile.length();
	    int expectedSend = 512; // 512 for HTTP overhead
	    if (size > 0)
		expectedSend += (int)size/10; // compression
	    FileInputStream fin = new FileInputStream(dataFile);
	    BandwidthLimiter.getInstance().delayOutbound(null, expectedSend); 
	    boolean sent = HTTPSendData.postData(url, size, fin);
	    fin.close();
	    boolean deleted = dataFile.delete();
	    _log.debug("Submitted " + size + " bytes? " + sent + " and deleted? " + deleted);
	} catch (IOException ioe) {
	    _log.error("Error sending the data", ioe);
	}
    }
    
    private String getURL() {
	String str = Router.getInstance().getConfigSetting(PARAM_SUBMIT_URL);
	if ( (str == null) || (str.trim().length() <= 0) )
	    return DEFAULT_SUBMIT_URL;
	else
	    return str.trim();
    }
    
    private boolean shouldSubmit() { 
	String str = Router.getInstance().getConfigSetting(PARAM_SUBMIT_DATA);
	if (str == null) {
	    _log.debug("History submit config not specified [" + PARAM_SUBMIT_DATA + "], default = " + DEFAULT_SUBMIT_DATA);
	    return DEFAULT_SUBMIT_DATA;
	} else {
	    _log.debug("History submit config specified [" + str + "]");
	}
	return Boolean.TRUE.toString().equals(str);
    }
    private long getRequeueDelay() { return DEFAULT_REQUEUE_DELAY; }
    public String getName() { return "Submit Message History"; }
}
