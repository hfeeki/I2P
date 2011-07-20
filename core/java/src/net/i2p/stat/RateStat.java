package net.i2p.stat;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/** coordinate a moving rate over various periods */
public class RateStat {
    private final static Log _log = new Log(RateStat.class);
    /** unique name of the statistic */
    private final String _statName;
    /** grouping under which the stat is kept */
    private final String _groupName;
    /** describe the stat */
    private final String _description;
    /** actual rate objects for this statistic */
    private final ConcurrentHashMap<Long, Rate> _rates;
    /** component we tell about events as they occur */
    private StatLog _statLog;

    public RateStat(String name, String description, String group, long periods[]) {
        _statName = name;
        _description = description;
        _groupName = group;
        _rates = new ConcurrentHashMap<Long, Rate>();
        for (int i = 0; i < periods.length; i++) {
            Rate rate = new Rate(periods[i]);
            rate.setRateStat(this);
            _rates.put(rate.getPeriod(),rate);;
        }
    }
    public void setStatLog(StatLog sl) { _statLog = sl; }
    
    /** 
     * update all of the rates for the various periods with the given value.  
     */
    public void addData(long value, long eventDuration) {
        if (_statLog != null) _statLog.addData(_groupName, _statName, value, eventDuration);
        for (Entry<Long, Rate> e: _rates.entrySet())
            e.getValue().addData(value, eventDuration);
    }

    /** coalesce all the stats */
    public void coalesceStats() {
        for (Entry<Long, Rate> e: _rates.entrySet()){
            e.getValue().coalesce();
        }
    }

    public String getName() {
        return _statName;
    }

    public String getGroupName() {
        return _groupName;
    }

    public String getDescription() {
        return _description;
    }

    public long[] getPeriods() {
        long rv[] = new long[_rates.size()];
        int counter = 0;
        for (Entry<Long, Rate> e: _rates.entrySet())
            rv[counter++] = e.getValue().getPeriod();
        Arrays.sort(rv);
        return rv;
    }

    public double getLifetimeAverageValue() {
        if ( (_rates == null) || (_rates.size() <= 0) ) return 0;
        return _rates.entrySet().iterator().next().getValue().getLifetimeAverageValue();
    }
    public long getLifetimeEventCount() {
        if ( (_rates == null) || (_rates.size() <= 0) ) return 0;
        return _rates.entrySet().iterator().next().getValue().getLifetimeEventCount();
    }

    /**
     * Returns rate with requested period if it exists, 
     * otherwise creates new rate with requested period, adds it to  list of rates and returns it.
     * @param period
     * @return
     */
    public Rate getRate(long period) {
        if (_rates.containsKey(period)){
        	return _rates.get(period);
        } else {
        	Rate rate = new Rate(period);
        	rate.setRateStat(this);
        	_rates.put(period, rate);
        	return rate;
        }
    }
    
    /**
     * Adds a new rate with the requested period, provided that 
     * a rate with that period does not already exist.
     * @param period
     */
    public void addRate(long period) {
    	if (!_rates.containsKey(period)){
	    	Rate rate = new Rate(period);
	    	rate.setRateStat(this);
	    	_rates.put(period, rate);
    	}
    }
    
    /**
     * If a rate with the provided period exists, remove it.
     * @param period
     */
    public void removeRate(long period) {
    	_rates.remove(period);
    }
    
    /**
     * Tests if a rate with the provided period exists within this RateStat.
     * @param period
     * @return
     */
    public boolean containsRate(long period) {
    	return _rates.containsKey(period);
    }

    @Override
    public int hashCode() {
        return _statName.hashCode();
    }

    private final static String NL = System.getProperty("line.separator");

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(4096);
        buf.append(getGroupName()).append('.').append(getName()).append(": ").append(getDescription()).append('\n');
        long periods[] = getPeriods();
        Arrays.sort(periods);
        for (int i = 0; i < periods.length; i++) {
            buf.append('\t').append(periods[i]).append(':');
            Rate curRate = getRate(periods[i]);
            buf.append(curRate.toString());
            buf.append(NL);
        }
        return buf.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if ((obj == null) || !(obj instanceof RateStat)) return false;
        RateStat rs = (RateStat) obj;
        if (DataHelper.eq(getGroupName(), rs.getGroupName()) && DataHelper.eq(getDescription(), rs.getDescription())
            && DataHelper.eq(getName(), rs.getName())) {
            for (Entry<Long, Rate> e: _rates.entrySet())
                if (!e.getValue().equals(rs.getRate(e.getValue().getPeriod()))) return false;
            return true;
        } 
        
        return false;
    }

    public void store(OutputStream out, String prefix) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        buf.append(NL);
        buf.append("################################################################################").append(NL);
        buf.append("# Rate: ").append(_groupName).append(": ").append(_statName).append(NL);
        buf.append("# ").append(_description).append(NL);
        buf.append("# ").append(NL).append(NL);
        out.write(buf.toString().getBytes());
        buf.setLength(0);
        for (Entry<Long, Rate> e: _rates.entrySet()){
            buf.append("#######").append(NL);
            buf.append("# Period : ").append(DataHelper.formatDuration(e.getValue().getPeriod())).append(" for rate ")
                .append(_groupName).append(" - ").append(_statName).append(NL);
            buf.append(NL);
            String curPrefix = prefix + "." + DataHelper.formatDuration(e.getValue().getPeriod());
            e.getValue().store(curPrefix, buf);
            out.write(buf.toString().getBytes());
            buf.setLength(0);
        }
    }

    /**
     * Load this rate stat from the properties, populating all of the rates contained 
     * underneath it.  The comes from the given prefix (e.g. if we are given the prefix
     * "profile.dbIntroduction", a series of rates may be found underneath 
     * "profile.dbIntroduction.60s", "profile.dbIntroduction.60m", and "profile.dbIntroduction.24h").
     * This RateStat must already be created, with the specified rate entries constructued - this
     * merely loads them with data.
     *
     * @param prefix prefix to the property entries (should NOT end with a period)
     * @param treatAsCurrent if true, we'll treat the loaded data as if no time has
     *                       elapsed since it was written out, but if it is false, we'll
     *                       treat the data with as much freshness (or staleness) as appropriate.
     * @throws IllegalArgumentException if the data was formatted incorrectly
     */
    public void load(Properties props, String prefix, boolean treatAsCurrent) throws IllegalArgumentException {
        for (Entry<Long, Rate> e: _rates.entrySet()) {
            long period = e.getValue().getPeriod();
            String curPrefix = prefix + "." + DataHelper.formatDuration(period);
            try {
                e.getValue().load(props, curPrefix, treatAsCurrent);
            } catch (IllegalArgumentException iae) {
                Rate rate = new Rate(period);
                rate.setRateStat(this);
                _rates.put(rate.getPeriod(), rate);
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Rate for " + prefix + " is corrupt, reinitializing that period");
            }
        }
    }

/*********
*/
    public static void main(String args[]) {
        RateStat rs = new RateStat("moo", "moo moo moo", "cow trueisms", new long[] { 60 * 1000, 60 * 60 * 1000,
                                                                                     24 * 60 * 60 * 1000});
        
        rs.getRate(5500L);
        System.out.println("Adding data..");
        for (int i = 0; i < 500; i++) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ie) { // nop
            }
            rs.addData(i * 100, 20);
        }
        
        Rate rate5500 = rs.getRate(5500L);
        System.out.println("Nbr of events: " + rate5500.getCurrentEventCount());
        System.out.println("Average :" + rate5500.getAverageValue());
        
        System.out.println("Coalescing stats..");
        rs.coalesceStats();
        System.out.println("Average :" + rate5500.getAverageValue());
        System.out.println("Coalescing this rate..");
        rate5500.coalesce();
        System.out.println("Average :" + rate5500.getAverageValue());
        System.out.println("Lifetime average :" + rate5500.getLifetimeAverageValue());

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(2048);
        try {
            rs.store(baos, "rateStat.test");
            byte data[] = baos.toByteArray();
            _log.error("Stored rateStat: size = " + data.length + "\n" + new String(data));

            Properties props = new Properties();
            props.load(new java.io.ByteArrayInputStream(data));

            //_log.error("Properties loaded: \n" + props);

            RateStat loadedRs = new RateStat("moo", "moo moo moo", "cow trueisms", new long[] { 60 * 1000,
                                                                                               60 * 60 * 1000,
                                                                                               24 * 60 * 60 * 1000});
            loadedRs.load(props, "rateStat.test", true);

            _log.error("Comparison after store/load: " + rs.equals(loadedRs));
        } catch (Throwable t) {
            _log.error("b0rk", t);
        }
        try {
            Thread.sleep(5000);
        } catch (InterruptedException ie) { // nop
        }
    }
 /**
*********/
}
