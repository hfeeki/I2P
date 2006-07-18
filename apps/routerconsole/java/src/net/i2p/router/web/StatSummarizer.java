package net.i2p.router.web;

import java.io.*;
import java.util.*;

import net.i2p.stat.*;
import net.i2p.router.*;
import net.i2p.util.Log;

import java.awt.Color;
import org.jrobin.graph.RrdGraph;
import org.jrobin.graph.RrdGraphDef;
import org.jrobin.graph.RrdGraphDefTemplate;
import org.jrobin.core.RrdException;

/**
 *
 */
public class StatSummarizer implements Runnable {
    private RouterContext _context;
    private Log _log;
    /** list of SummaryListener instances */
    private List _listeners;
    private static StatSummarizer _instance;
    
    public StatSummarizer() {
        _context = (RouterContext)RouterContext.listContexts().get(0); // fuck it, only summarize one per jvm
        _log = _context.logManager().getLog(getClass());
        _listeners = new ArrayList(16);
        _instance = this;
    }
    
    public static StatSummarizer instance() { return _instance; }
    
    public void run() {
        String specs = "";
        while (_context.router().isAlive()) {
            specs = adjustDatabases(specs);
            try { Thread.sleep(60*1000); } catch (InterruptedException ie) {}
        }
    }
    
    /** list of SummaryListener instances */
    List getListeners() { return _listeners; }
    
    private static final String DEFAULT_DATABASES = "bw.sendRate.60000" +
                                                    ",bw.recvRate.60000" +
                                                    ",tunnel.testSuccessTime.60000" +
                                                    ",udp.outboundActiveCount.60000" +
                                                    ",udp.receivePacketSize.60000" +
                                                    ",udp.receivePacketSkew.60000" +
                                                    ",udp.sendConfirmTime.60000" +
                                                    ",udp.sendPacketSize.60000" +
                                                    ",router.activePeers.60000" +
                                                    ",router.activeSendPeers.60000" +
                                                    ",tunnel.acceptLoad.60000" +
                                                    ",tunnel.dropLoadProactive.60000" +
                                                    ",tunnel.buildExploratorySuccess.60000" +
                                                    ",tunnel.buildExploratoryReject.60000" +
                                                    ",tunnel.buildExploratoryExpire.60000" +
                                                    ",client.sendAckTime.60000" +
                                                    ",client.dispatchNoACK.60000" +
                                                    ",ntcp.sendTime.60000" +
                                                    ",ntcp.transmitTime.60000" +
                                                    ",ntcp.sendBacklogTime.60000" +
                                                    ",ntcp.receiveTime.60000" +
                                                    ",transport.sendMessageFailureLifetime.60000" +
                                                    ",transport.sendProcessingTime.60000";
    
    private String adjustDatabases(String oldSpecs) {
        String spec = _context.getProperty("stat.summaries", DEFAULT_DATABASES);
        if ( ( (spec == null) && (oldSpecs == null) ) ||
             ( (spec != null) && (oldSpecs != null) && (oldSpecs.equals(spec))) )
            return oldSpecs;
        
        List old = parseSpecs(oldSpecs);
        List newSpecs = parseSpecs(spec);
        
        // remove old ones
        for (int i = 0; i < old.size(); i++) {
            Rate r = (Rate)old.get(i);
            if (!newSpecs.contains(r))
                removeDb(r);
        }
        // add new ones
        StringBuffer buf = new StringBuffer();
        for (int i = 0; i < newSpecs.size(); i++) {
            Rate r = (Rate)newSpecs.get(i);
            if (!old.contains(r))
                addDb(r);
            buf.append(r.getRateStat().getName()).append(".").append(r.getPeriod());
            if (i + 1 < newSpecs.size())
                buf.append(',');
        }
        return buf.toString();
    }
    
    private void removeDb(Rate r) {
        for (int i = 0; i < _listeners.size(); i++) {
            SummaryListener lsnr = (SummaryListener)_listeners.get(i);
            if (lsnr.getRate().equals(r)) {
                _listeners.remove(i);
                lsnr.stopListening();
                return;
            }
        }
    }
    private void addDb(Rate r) {
        SummaryListener lsnr = new SummaryListener(r);
        _listeners.add(lsnr);
        lsnr.startListening();
        //System.out.println("Start listening for " + r.getRateStat().getName() + ": " + r.getPeriod());
    }
    public boolean renderPng(Rate rate, OutputStream out) throws IOException { 
        return renderPng(rate, out, -1, -1, false, false, false, false, -1, true); 
    }
    public boolean renderPng(Rate rate, OutputStream out, int width, int height, boolean hideLegend, boolean hideGrid, boolean hideTitle, boolean showEvents, int periodCount, boolean showCredit) throws IOException {
        for (int i = 0; i < _listeners.size(); i++) {
            SummaryListener lsnr = (SummaryListener)_listeners.get(i);
            if (lsnr.getRate().equals(rate)) {
                lsnr.renderPng(out, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount, showCredit);
                return true;
            }
        }
        return false;
    }
    public boolean renderPng(OutputStream out, String templateFilename) throws IOException {
        SummaryRenderer.render(_context, out, templateFilename);
        return true;
    }
    public boolean getXML(Rate rate, OutputStream out) throws IOException {
        for (int i = 0; i < _listeners.size(); i++) {
            SummaryListener lsnr = (SummaryListener)_listeners.get(i);
            if (lsnr.getRate().equals(rate)) {
                lsnr.getData().exportXml(out);
                out.write(("<!-- Rate: " + lsnr.getRate().getRateStat().getName() + " for period " + lsnr.getRate().getPeriod() + " -->\n").getBytes());
                out.write(("<!-- Average data soure name: " + lsnr.getName() + " event count data source name: " + lsnr.getEventName() + " -->\n").getBytes());
                return true;
            }
        }
        return false;
    }
    
    public boolean renderRatePng(OutputStream out, int width, int height, boolean hideLegend, boolean hideGrid, boolean hideTitle, boolean showEvents, int periodCount, boolean showCredit) throws IOException {
        long end = _context.clock().now();
        if (periodCount <= 0) periodCount = SummaryListener.PERIODS;
        if (periodCount > SummaryListener.PERIODS)
            periodCount = SummaryListener.PERIODS;
        long period = 60*1000;
        long start = end - period*periodCount;
        long begin = System.currentTimeMillis();
        try {
            RrdGraphDef def = new RrdGraphDef();
            def.setTimePeriod(start/1000, end/1000);
            String title = "Bandwidth usage";
            if (!hideTitle)
                def.setTitle(title);
            String sendName = SummaryListener.createName(_context, "bw.sendRate.60000");
            String recvName = SummaryListener.createName(_context, "bw.recvRate.60000");
            def.datasource(sendName, sendName, sendName, "AVERAGE", "MEMORY");
            def.datasource(recvName, recvName, recvName, "AVERAGE", "MEMORY");
            def.area(sendName, Color.BLUE, "Outbound bytes/second");
            //def.line(sendName, Color.BLUE, "Outbound bytes/second", 3);
            //def.line(recvName, Color.RED, "Inbound bytes/second@r", 3);
            def.area(recvName, Color.RED, "Inbound bytes/second@r");
            if (!hideLegend) {
                def.gprint(sendName, "AVERAGE", "outbound average: @2@sbytes/second");
                def.gprint(sendName, "MAX", " max: @2@sbytes/second@r");
                def.gprint(recvName, "AVERAGE", "inbound average: @2bytes/second@s");
                def.gprint(recvName, "MAX", " max: @2@sbytes/second@r");
            }
            if (!showCredit)
                def.setShowSignature(false);
            if (hideLegend) 
                def.setShowLegend(false);
            if (hideGrid) {
                def.setGridX(false);
                def.setGridY(false);
            }
            //System.out.println("rendering: path=" + path + " dsNames[0]=" + dsNames[0] + " dsNames[1]=" + dsNames[1] + " lsnr.getName=" + _listener.getName());
            def.setAntiAliasing(false);
            //System.out.println("Rendering: \n" + def.exportXmlTemplate());
            //System.out.println("*****************\nData: \n" + _listener.getData().dump());
            RrdGraph graph = new RrdGraph(def);
            //System.out.println("Graph created");
            byte data[] = null;
            if ( (width <= 0) || (height <= 0) )
                data = graph.getPNGBytes();
            else
                data = graph.getPNGBytes(width, height);
            long timeToPlot = System.currentTimeMillis() - begin;
            out.write(data);
            //File t = File.createTempFile("jrobinData", ".xml");
            //_listener.getData().dumpXml(new FileOutputStream(t));
            //System.out.println("plotted: " + (data != null ? data.length : 0) + " bytes in " + timeToPlot
            //                   ); // + ", data written to " + t.getAbsolutePath());
            return true;
        } catch (RrdException re) {
            _log.error("Error rendering", re);
            throw new IOException("Error plotting: " + re.getMessage());
        } catch (IOException ioe) {
            _log.error("Error rendering", ioe);
            throw ioe;
        }
    }
    
    /**
     * @param specs statName.period,statName.period,statName.period
     * @return list of Rate objects
     */
    private List parseSpecs(String specs) {
        StringTokenizer tok = new StringTokenizer(specs, ",");
        List rv = new ArrayList();
        while (tok.hasMoreTokens()) {
            String spec = tok.nextToken();
            int split = spec.lastIndexOf('.');
            if ( (split <= 0) || (split + 1 >= spec.length()) )
                continue;
            String name = spec.substring(0, split);
            String per = spec.substring(split+1);
            long period = -1;
            try { 
                period = Long.parseLong(per); 
                RateStat rs = _context.statManager().getRate(name);
                if (rs != null) {
                    Rate r = rs.getRate(period);
                    if (r != null)
                        rv.add(r);
                }
            } catch (NumberFormatException nfe) {}
        }
        return rv;
    }
}
