package net.i2p.heartbeat;

import net.i2p.util.Log;
import net.i2p.util.Clock;

import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;

import java.text.SimpleDateFormat;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Date;
import java.util.Iterator;

/**
 * Actually write out the stats for peer test
 *
 */
class PeerDataWriter {
    private final static Log _log = new Log(PeerDataWriter.class);
    
    /** 
     * persist the peer state to the location specified in the peer config
     *
     * @return true if it was persisted correctly, false on error
     */
    public boolean persist(PeerData data) {
	String filename = data.getConfig().getStatFile();
	String header = getHeader(data);
	File statFile = new File(filename);
	FileOutputStream fos = null;
	try {
	    fos = new FileOutputStream(statFile);
	    fos.write(header.getBytes());
	    fos.write("#action\tstatus\tdate and time sent   \tsendMs\treplyMs\n".getBytes());
	    for (Iterator iter = data.getDataPoints().iterator(); iter.hasNext(); ) {
		PeerData.EventDataPoint point = (PeerData.EventDataPoint)iter.next();
		String line = getEvent(point);
		fos.write(line.getBytes());
	    }
	} catch (IOException ioe) {
	    if (_log.shouldLog(Log.ERROR))
		_log.error("Error persisting the peer data for " + data.getConfig().getPeer().calculateHash().toBase64(), ioe);
	    return false;
	} finally {
	    if (fos != null) try { fos.close(); } catch (IOException ioe) {}
	}
	return true;
    }
    
    private String getHeader(PeerData data) { 
	StringBuffer buf = new StringBuffer(1024);
	buf.append("peer         \t").append(data.getConfig().getPeer().calculateHash().toBase64()).append('\n');
	buf.append("local        \t").append(data.getConfig().getUs().calculateHash().toBase64()).append('\n');
	buf.append("peerDest     \t").append(data.getConfig().getPeer().toBase64()).append('\n');
	buf.append("localDest    \t").append(data.getConfig().getUs().toBase64()).append('\n');
	buf.append("numTunnelHops\t").append(data.getConfig().getNumHops()).append('\n');
	buf.append("comment      \t").append(data.getConfig().getComment()).append('\n');
	buf.append("sendFrequency\t").append(data.getConfig().getSendFrequency()).append('\n');
	buf.append("sendSize     \t").append(data.getConfig().getSendSize()).append('\n');
	buf.append("sessionStart \t").append(getTime(data.getSessionStart())).append('\n');
	buf.append("currentTime  \t").append(getTime(Clock.getInstance().now())).append('\n');
	buf.append("numPending   \t").append(data.getPendingCount()).append('\n');
	buf.append("lifetimeSent \t").append(data.getLifetimeSent()).append('\n');
	buf.append("lifetimeRecv \t").append(data.getLifetimeReceived()).append('\n');
	int periods[] = data.getAveragePeriods();
	buf.append("#averages\tminutes\tsendMs\trecvMs\tnumLost\n");
	for (int i = 0; i < periods.length; i++) {
	    buf.append("periodAverage\t").append(periods[i]).append('\t');
	    buf.append(getNum(data.getAverageSendTime(periods[i]))).append('\t');
	    buf.append(getNum(data.getAverageReceiveTime(periods[i]))).append('\t');
	    buf.append(getNum(data.getLostMessages(periods[i]))).append('\n');
	}
	return buf.toString();
    }
    
    private String getEvent(PeerData.EventDataPoint point) {
	StringBuffer buf = new StringBuffer(128);
	buf.append("EVENT\t");
	if (point.getWasPonged())
	    buf.append("OK\t");
	else
	    buf.append("LOST\t");
	buf.append(getTime(point.getPingSent())).append('\t');
	if (point.getWasPonged()) {
	    buf.append(point.getPongSent() - point.getPingSent()).append('\t');
	    buf.append(point.getPongReceived() - point.getPongSent()).append('\t');
	}
	buf.append('\n');
	return buf.toString();
    }
    
    private static final SimpleDateFormat _fmt = new SimpleDateFormat("yyyyMMdd.HH:mm:ss.SSS", Locale.UK);
    public String getTime(long when) {
	synchronized (_fmt) {
	    return _fmt.format(new Date(when));
	}
    }
    private static final DecimalFormat _numFmt = new DecimalFormat("#0", new DecimalFormatSymbols(Locale.UK));
    public String getNum(double val) {
	synchronized (_numFmt) {
	    return _numFmt.format(val);
	}
    }
}