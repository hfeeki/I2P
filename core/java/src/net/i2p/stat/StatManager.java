package net.i2p.stat;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import net.i2p.util.Log;

/** 
 * Coordinate the management of various frequencies and rates within I2P components,
 * both allowing central update and retrieval, as well as distributed creation and 
 * use.  This does not provide any persistence, but the data structures exposed can be
 * read and updated to manage the complete state.
 * 
 */
public class StatManager {
    private final static Log _log = new Log(StatManager.class);
    private final static StatManager _instance = new StatManager();

    public final static StatManager getInstance() {
        return _instance;
    }
    /** stat name to FrequencyStat */
    private Map _frequencyStats;
    /** stat name to RateStat */
    private Map _rateStats;

    private StatManager() {
        _frequencyStats = Collections.synchronizedMap(new HashMap(128));
        _rateStats = Collections.synchronizedMap(new HashMap(128));
    }

    /**
     * Create a new statistic to monitor the frequency of some event.
     *
     * @param name unique name of the statistic
     * @param description simple description of the statistic
     * @param group used to group statistics together
     * @param periods array of period lengths (in milliseconds)
     */
    public void createFrequencyStat(String name, String description, String group, long periods[]) {
        _frequencyStats.put(name, new FrequencyStat(name, description, group, periods));
    }

    /**
     * Create a new statistic to monitor the average value and confidence of some action.
     *
     * @param name unique name of the statistic
     * @param description simple description of the statistic
     * @param group used to group statistics together
     * @param periods array of period lengths (in milliseconds)
     */
    public void createRateStat(String name, String description, String group, long periods[]) {
        _rateStats.put(name, new RateStat(name, description, group, periods));
    }

    /** update the given frequency statistic, taking note that an event occurred (and recalculating all frequencies) */
    public void updateFrequency(String name) {
        FrequencyStat freq = (FrequencyStat) _frequencyStats.get(name);
        if (freq != null) freq.eventOccurred();
    }

    /** update the given rate statistic, taking note that the given data point was received (and recalculating all rates) */
    public void addRateData(String name, long data, long eventDuration) {
        RateStat stat = (RateStat) _rateStats.get(name);
        if (stat != null) stat.addData(data, eventDuration);
    }

    public void coallesceStats() {
        for (Iterator iter = getFrequencyNames().iterator(); iter.hasNext();) {
            String name = (String) iter.next();
            FrequencyStat stat = getFrequency(name);
            if (stat != null) {
                stat.coallesceStats();
            }
        }
        for (Iterator iter = getRateNames().iterator(); iter.hasNext();) {
            String name = (String) iter.next();
            RateStat stat = getRate(name);
            if (stat != null) {
                stat.coallesceStats();
            }
        }
    }

    public FrequencyStat getFrequency(String name) {
        return (FrequencyStat) _frequencyStats.get(name);
    }

    public RateStat getRate(String name) {
        return (RateStat) _rateStats.get(name);
    }

    public Set getFrequencyNames() {
        return Collections.unmodifiableSet(new HashSet(_frequencyStats.keySet()));
    }

    public Set getRateNames() {
        return Collections.unmodifiableSet(new HashSet(_rateStats.keySet()));
    }

    /** is the given stat a monitored rate? */
    public boolean isRate(String statName) {
        return _rateStats.containsKey(statName);
    }

    /** is the given stat a monitored frequency? */
    public boolean isFrequency(String statName) {
        return _frequencyStats.containsKey(statName);
    }

    /** Group name (String) to a Set of stat names */
    public Map getStatsByGroup() {
        Map groups = new TreeMap();
        for (Iterator iter = _frequencyStats.values().iterator(); iter.hasNext();) {
            FrequencyStat stat = (FrequencyStat) iter.next();
            if (!groups.containsKey(stat.getGroupName())) groups.put(stat.getGroupName(), new TreeSet());
            Set names = (Set) groups.get(stat.getGroupName());
            names.add(stat.getName());
        }
        for (Iterator iter = _rateStats.values().iterator(); iter.hasNext();) {
            RateStat stat = (RateStat) iter.next();
            if (!groups.containsKey(stat.getGroupName())) groups.put(stat.getGroupName(), new TreeSet());
            Set names = (Set) groups.get(stat.getGroupName());
            names.add(stat.getName());
        }
        return groups;
    }
}