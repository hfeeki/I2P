package net.i2p.router.web;

import java.io.*;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.stat.RateSummaryListener;
import net.i2p.util.Log;

import org.jrobin.core.RrdDb;
import org.jrobin.core.RrdDef;
import org.jrobin.core.RrdBackendFactory;
import org.jrobin.core.RrdMemoryBackendFactory;
import org.jrobin.core.Sample;

import java.awt.Color;
import org.jrobin.graph.RrdGraph;
import org.jrobin.graph.RrdGraphDef;
import org.jrobin.graph.RrdGraphDefTemplate;
import org.jrobin.core.RrdException;

class SummaryListener implements RateSummaryListener {
    private I2PAppContext _context;
    private Log _log;
    private Rate _rate;
    private String _name;
    private String _eventName;
    private RrdDb _db;
    private Sample _sample;
    private RrdMemoryBackendFactory _factory;
    private SummaryRenderer _renderer;
    
    static final int PERIODS = 1440;
    
    static {
        try {
            RrdBackendFactory.setDefaultFactory("MEMORY");
        } catch (RrdException re) {
            re.printStackTrace();
        }
    }
    
    public SummaryListener(Rate r) {
        _context = I2PAppContext.getGlobalContext();
        _rate = r;
        _log = _context.logManager().getLog(SummaryListener.class);
    }
    
    public void add(double totalValue, long eventCount, double totalEventTime, long period) {
        long now = now();
        long when = now / 1000;
        //System.out.println("add to " + getRate().getRateStat().getName() + " on " + System.currentTimeMillis() + " / " + now + " / " + when);
        if (_db != null) {
            // add one value to the db (the average value for the period)
            try {
                _sample.setTime(when);
                double val = eventCount > 0 ? (totalValue / (double)eventCount) : 0d;
                _sample.setValue(_name, val);
                _sample.setValue(_eventName, eventCount);
                //_sample.setValue(0, val);
                //_sample.setValue(1, eventCount);
                _sample.update();
                //String names[] = _sample.getDsNames();
                //System.out.println("Add " + val + " over " + eventCount + " for " + _name
                //                   + " [" + names[0] + ", " + names[1] + "]");
            } catch (IOException ioe) {
                _log.error("Error adding", ioe);
            } catch (RrdException re) {
                _log.error("Error adding", re);
            }
        }
    }
    
    /**
     * JRobin can only deal with 20 character data source names, so we need to create a unique,
     * munged version from the user/developer-visible name.
     *
     */
    static String createName(I2PAppContext ctx, String wanted) { 
        return ctx.sha().calculateHash(DataHelper.getUTF8(wanted)).toBase64().substring(0,20);
    }
    
    public Rate getRate() { return _rate; }
    public void startListening() {
        RateStat rs = _rate.getRateStat();
        long period = _rate.getPeriod();
        String baseName = rs.getName() + "." + period;
        _name = createName(_context, baseName);
        _eventName = createName(_context, baseName + ".events");
        try {
            RrdDef def = new RrdDef(_name, now()/1000, period/1000);
            // for info on the heartbeat, xff, steps, etc, see the rrdcreate man page, aka
            // http://www.jrobin.org/support/man/rrdcreate.html
            long heartbeat = period*10/1000;
            def.addDatasource(_name, "GAUGE", heartbeat, Double.NaN, Double.NaN);
            def.addDatasource(_eventName, "GAUGE", heartbeat, 0, Double.NaN);
            double xff = 0.9;
            int steps = 1;
            int rows = PERIODS;
            def.addArchive("AVERAGE", xff, steps, rows);
            _factory = (RrdMemoryBackendFactory)RrdBackendFactory.getDefaultFactory();
            _db = new RrdDb(def, _factory);
            _sample = _db.createSample();
            _renderer = new SummaryRenderer(_context, this);
            _rate.setSummaryListener(this);
        } catch (RrdException re) {
            _log.error("Error starting", re);
        } catch (IOException ioe) {
            _log.error("Error starting", ioe);
        }
    }
    public void stopListening() {
        if (_db == null) return;
        try {
            _db.close();
        } catch (IOException ioe) {
            _log.error("Error closing", ioe);
        }
        _rate.setSummaryListener(null);
        _factory.delete(_db.getPath());
        _db = null;
    }
    public void renderPng(OutputStream out, int width, int height, boolean hideLegend, boolean hideGrid, boolean hideTitle, boolean showEvents, int periodCount, boolean showCredit) throws IOException {
        _renderer.render(out, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount, showCredit); 
    }
    public void renderPng(OutputStream out) throws IOException { _renderer.render(out); }
 
    String getName() { return _name; }
    String getEventName() { return _eventName; }
    RrdDb getData() { return _db; }
    long now() { return _context.clock().now(); }
    
    public boolean equals(Object obj) {
        return ((obj instanceof SummaryListener) && ((SummaryListener)obj)._rate.equals(_rate));
    }
    public int hashCode() { return _rate.hashCode(); }
}

class SummaryRenderer {
    private Log _log;
    private SummaryListener _listener;
    public SummaryRenderer(I2PAppContext ctx, SummaryListener lsnr) { 
        _log = ctx.logManager().getLog(SummaryRenderer.class);
        _listener = lsnr;
    }
    
    /**
     * Render the stats as determined by the specified JRobin xml config,
     * but note that this doesn't work on stock jvms, as it requires 
     * DOM level 3 load and store support.  Perhaps we can bundle that, or
     * specify who can get it from where, etc.
     *
     */
    public static synchronized void render(I2PAppContext ctx, OutputStream out, String filename) throws IOException {
        long end = ctx.clock().now() - 60*1000;
        long start = end - 60*1000*SummaryListener.PERIODS;
        long begin = System.currentTimeMillis();
        try {
            RrdGraphDefTemplate template = new RrdGraphDefTemplate(filename);
            RrdGraphDef def = template.getRrdGraphDef();
            def.setTimePeriod(start/1000, end/1000); // ignore the periods in the template
            RrdGraph graph = new RrdGraph(def);
            byte img[] = graph.getPNGBytes();
            out.write(img);
        } catch (RrdException re) {
            //_log.error("Error rendering " + filename, re);
            throw new IOException("Error plotting: " + re.getMessage());
        } catch (IOException ioe) {
            //_log.error("Error rendering " + filename, ioe);
            throw ioe;
        }
    }
    public void render(OutputStream out) throws IOException { render(out, -1, -1, false, false, false, false, -1, true); }
    public void render(OutputStream out, int width, int height, boolean hideLegend, boolean hideGrid, boolean hideTitle, boolean showEvents, int periodCount, boolean showCredit) throws IOException {
        long end = _listener.now() - 60*1000;
        if (periodCount <= 0) periodCount = SummaryListener.PERIODS;
        if (periodCount > SummaryListener.PERIODS)
            periodCount = SummaryListener.PERIODS;
        long start = end - _listener.getRate().getPeriod()*periodCount;
        //long begin = System.currentTimeMillis();
        try {
            RrdGraphDef def = new RrdGraphDef();
            def.setTimePeriod(start/1000, 0);
            String name = _listener.getRate().getRateStat().getName();
            if ((name.startsWith("bw.") || name.endsWith("PacketSize")) && !showEvents)
                def.setBaseValue(1024);
            String title = name;
            if (showEvents)
                title = title + " events in ";
            else
                title = title + " averaged for ";
            title = title + DataHelper.formatDuration(_listener.getRate().getPeriod());
            if (!hideTitle)
                def.setTitle(title);
            String path = _listener.getData().getPath();
            String dsNames[] = _listener.getData().getDsNames();
            String plotName = null;
            String descr = null;
            if (showEvents) {
                // include the average event count on the plot
                plotName = dsNames[1];
                descr = "Events per period";
            } else {
                // include the average value
                plotName = dsNames[0];
                descr = _listener.getRate().getRateStat().getDescription();
            }
            def.datasource(plotName, path, plotName, "AVERAGE", "MEMORY");
            def.area(plotName, Color.BLUE, descr + "@r");
            if (!hideLegend) {
                def.gprint(plotName, "AVERAGE", "avg: @2@s");
                def.gprint(plotName, "MAX", " max: @2@s");
                def.gprint(plotName, "LAST", " now: @2@s@r");
            }
            if (!showCredit)
                def.setShowSignature(false);
            /*
            // these four lines set up a graph plotting both values and events on the same chart
            // (but with the same coordinates, so the values may look pretty skewed)
                def.datasource(dsNames[0], path, dsNames[0], "AVERAGE", "MEMORY");
                def.datasource(dsNames[1], path, dsNames[1], "AVERAGE", "MEMORY");
                def.area(dsNames[0], Color.BLUE, _listener.getRate().getRateStat().getDescription());
                def.line(dsNames[1], Color.RED, "Events per period");
            */
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
            //long timeToPlot = System.currentTimeMillis() - begin;
            out.write(data);
            //File t = File.createTempFile("jrobinData", ".xml");
            //_listener.getData().dumpXml(new FileOutputStream(t));
            //System.out.println("plotted: " + (data != null ? data.length : 0) + " bytes in " + timeToPlot
            //                   ); // + ", data written to " + t.getAbsolutePath());
        } catch (RrdException re) {
            _log.error("Error rendering", re);
            throw new IOException("Error plotting: " + re.getMessage());
        } catch (IOException ioe) {
            _log.error("Error rendering", ioe);
            throw ioe;
        } catch (OutOfMemoryError oom) {
            _log.error("Error rendering", oom);
            throw new IOException("Error plotting: " + oom.getMessage());
        }
    }
}
