package net.i2p.stat;

import java.io.IOException;
import java.util.Properties;

import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 * Simple rate calculator for periodically sampled data points - determining an 
 * average value over a period, the number of events in that period, the maximum number
 * of events (using the interval between events), and lifetime data.
 *
 */
public class Rate {
    private final static Log _log = new Log(Rate.class);
    private volatile double _currentTotalValue;
    private volatile long _currentEventCount;
    private volatile long _currentTotalEventTime;
    private volatile double _lastTotalValue;
    private volatile long _lastEventCount;
    private volatile long _lastTotalEventTime;
    private volatile double _extremeTotalValue;
    private volatile long _extremeEventCount;
    private volatile long _extremeTotalEventTime;
    private volatile double _lifetimeTotalValue;
    private volatile long _lifetimeEventCount;
    private volatile long _lifetimeTotalEventTime;

    private volatile long _lastCoallesceDate;
    private long _creationDate;
    private long _period;

    /** locked during coallesce and addData */
    private Object _lock = new Object();

    /** in the current (partial) period, what is the total value acrued through all events? */
    public double getCurrentTotalValue() {
        return _currentTotalValue;
    }

    /** in the current (partial) period, how many events have occurred? */
    public long getCurrentEventCount() {
        return _currentEventCount;
    }

    /** in the current (partial) period, how much of the time has been spent doing the events? */
    public long getCurrentTotalEventTime() {
        return _currentTotalEventTime;
    }

    /** in the last full period, what was the total value acrued through all events? */
    public double getLastTotalValue() {
        return _lastTotalValue;
    }

    /** in the last full period, how many events occurred? */
    public long getLastEventCount() {
        return _lastEventCount;
    }

    /** in the last full period, how much of the time was spent doing the events? */
    public long getLastTotalEventTime() {
        return _lastTotalEventTime;
    }

    /** what was the max total value acrued in any period?  */
    public double getExtremeTotalValue() {
        return _extremeTotalValue;
    }

    /** when the max(totalValue) was achieved, how many events occurred in that period? */
    public long getExtremeEventCount() {
        return _extremeEventCount;
    }

    /** when the max(totalValue) was achieved, how much of the time was spent doing the events? */
    public long getExtremeTotalEventTime() {
        return _extremeTotalEventTime;
    }

    /** since rate creation, what was the total value acrued through all events?  */
    public double getLifetimeTotalValue() {
        return _lifetimeTotalValue;
    }

    /** since rate creation, how many events have occurred? */
    public long getLifetimeEventCount() {
        return _lifetimeEventCount;
    }

    /** since rate creation, how much of the time was spent doing the events? */
    public long getLifetimeTotalEventTime() {
        return _lifetimeTotalEventTime;
    }

    /** when was the rate last coallesced? */
    public long getLastCoallesceDate() {
        return _lastCoallesceDate;
    }

    /** when was this rate created? */
    public long getCreationDate() {
        return _creationDate;
    }

    /** how large should this rate's cycle be? */
    public long getPeriod() {
        return _period;
    }

    /**
     *
     * @param period number of milliseconds in the period this rate deals with
     * @throws IllegalArgumentException if the period is not greater than 0
     */
    public Rate(long period) throws IllegalArgumentException {
        if (period <= 0) throw new IllegalArgumentException("The period must be strictly positive");
        _currentTotalValue = 0.0d;
        _currentEventCount = 0;
        _currentTotalEventTime = 0;
        _lastTotalValue = 0.0d;
        _lastEventCount = 0;
        _lastTotalEventTime = 0;
        _extremeTotalValue = 0.0d;
        _extremeEventCount = 0;
        _extremeTotalEventTime = 0;
        _lifetimeTotalValue = 0.0d;
        _lifetimeEventCount = 0;
        _lifetimeTotalEventTime = 0;

        _creationDate = now();
        _lastCoallesceDate = _creationDate;
        _period = period;
    }

    /**
     * Create a new rate and load its state from the properties, taking data 
     * from the data points underneath the given prefix.  <p />
     * (e.g. prefix = "profile.dbIntroduction.60m", this will load the associated data points such
     * as "profile.dbIntroduction.60m.lifetimeEventCount").  The data can be exported
     * through store(outputStream, "profile.dbIntroduction.60m").
     *
     * @param prefix prefix to the property entries (should NOT end with a period)
     * @param treatAsCurrent if true, we'll treat the loaded data as if no time has
     *                       elapsed since it was written out, but if it is false, we'll
     *                       treat the data with as much freshness (or staleness) as appropriate.
     * @throws IllegalArgumentException if the data was formatted incorrectly
     */
    public Rate(Properties props, String prefix, boolean treatAsCurrent) throws IllegalArgumentException {
        this(1);
        load(props, prefix, treatAsCurrent);
    }

    /** accrue the data in the current period as an instantaneous event */
    public void addData(long value) {
        addData(value, 0);
    }

    /**
     * Accrue the data in the current period as if the event took the specified amount of time
     *
     * @param value value to accrue in the current period
     * @param eventDuration how long it took to accrue this data (set to 0 if it was instantaneous)
     */
    public void addData(long value, long eventDuration) {
        synchronized (_lock) {
            _currentTotalValue += value;
            _currentEventCount++;
            _currentTotalEventTime += eventDuration;

            _lifetimeTotalValue += value;
            _lifetimeEventCount++;
            _lifetimeTotalEventTime += eventDuration;
        }
    }

    public void coallesce() {
        synchronized (_lock) {
            long now = now();
            long measuredPeriod = now - _lastCoallesceDate;
            if (measuredPeriod < _period) {
                // no need to coallesce
                return;
            } else {
                // ok ok, lets coallesce

                // how much were we off by?  (so that we can sample down the measured values)
                double periodFactor = measuredPeriod / _period;
                _lastTotalValue = (_currentTotalValue == 0 ? 0.0d : _currentTotalValue / periodFactor);
                _lastEventCount = (_currentEventCount == 0 ? 0l : (long) (_currentEventCount / periodFactor));
                _lastTotalEventTime = (_currentTotalEventTime == 0 ? 0l
                                                                  : (long) (_currentTotalEventTime / periodFactor));
                _lastCoallesceDate = now;

                if (_lastTotalValue > _extremeTotalValue) {
                    _extremeTotalValue = _lastTotalValue;
                    _extremeEventCount = _lastEventCount;
                    _extremeTotalEventTime = _lastTotalEventTime;
                }

                _currentTotalValue = 0.0d;
                _currentEventCount = 0;
                _currentTotalEventTime = 0;
            }
        }
    }

    /** what was the average value across the events in the last period? */
    public double getAverageValue() {
        if ((_lastTotalValue != 0) && (_lastEventCount > 0))
            return _lastTotalValue / _lastEventCount;
        else
            return 0.0d;
    }

    /** what was the average value across the events in the most active period? */
    public double getExtremeAverageValue() {
        if ((_extremeTotalValue != 0) && (_extremeEventCount > 0))
            return _extremeTotalValue / _extremeEventCount;
        else
            return 0.0d;
    }

    /** what was the average value across the events since the stat was created? */
    public double getLifetimeAverageValue() {
        if ((_lifetimeTotalValue != 0) && (_lifetimeEventCount > 0))
            return _lifetimeTotalValue / _lifetimeEventCount;
        else
            return 0.0d;
    }

    /** 
     * During the last period, how much of the time was spent actually processing events in proportion 
     * to how many events could have occurred if there were no intervals?
     *
     * @return percentage, or 0 if event times aren't used
     */
    public double getLastEventSaturation() {
        if ((_lastEventCount > 0) && (_lastTotalEventTime > 0)) {
            double eventTime = (double) _lastTotalEventTime / (double) _lastEventCount;
            double maxEvents = (double) _period / eventTime;
            double saturation = _lastEventCount / maxEvents;
            return saturation;
        } else {
            return 0.0d;
        }
    }

    /** 
     * During the extreme period, how much of the time was spent actually processing events
     * in proportion to how many events could have occurred if there were no intervals? 
     *
     * @return percentage, or 0 if the statistic doesn't use event times
     */
    public double getExtremeEventSaturation() {
        if ((_extremeEventCount > 0) && (_extremeTotalEventTime > 0)) {
            double eventTime = (double) _extremeTotalEventTime / (double) _extremeEventCount;
            double maxEvents = (double) _period / eventTime;
            return _extremeEventCount / maxEvents;
        } else {
            return 0.0d;
        }
    }

    /** 
     * During the lifetime of this stat, how much of the time was spent actually processing events in proportion 
     * to how many events could have occurred if there were no intervals? 
     *
     * @return percentage, or 0 if event times aren't used
     */
    public double getLifetimeEventSaturation() {
        if ((_lastEventCount > 0) && (_lifetimeTotalEventTime > 0)) {
            double eventTime = (double) _lifetimeTotalEventTime / (double) _lifetimeEventCount;
            double maxEvents = (double) _period / eventTime;
            double numPeriods = getLifetimePeriods();
            double avgEventsPerPeriod = _lifetimeEventCount / numPeriods;
            return avgEventsPerPeriod / maxEvents;
        } else {
            return 0.0d;
        }
    }

    /** how many periods have we already completed? */
    public long getLifetimePeriods() {
        long lifetime = now() - _creationDate;
        double periods = lifetime / (double) _period;
        return (long) Math.floor(periods);
    }

    /** 
     * using the last period's rate, what is the total value that could have been sent 
     * if events were constant?
     *
     * @return max total value, or 0 if event times aren't used
     */
    public double getLastSaturationLimit() {
        if ((_lastTotalValue != 0) && (_lastEventCount > 0) && (_lastTotalEventTime > 0)) {
            double saturation = getLastEventSaturation();
            if (saturation != 0.0d)
                return _lastTotalValue / saturation;
            else
                return 0.0d;
        } else {
            return 0.0d;
        }
    }

    /** 
     * using the extreme period's rate, what is the total value that could have been 
     * sent if events were constant?
     *
     * @return event total at saturation, or 0 if no event times are measured
     */
    public double getExtremeSaturationLimit() {
        if ((_extremeTotalValue != 0) && (_extremeEventCount > 0) && (_extremeTotalEventTime > 0)) {
            double saturation = getExtremeEventSaturation();
            if (saturation != 0.0d)
                return _extremeTotalValue / saturation;
            else
                return 0.0d;
        } else {
            return 0.0d;
        }
    }

    /**
     * How large was the last period's value as compared to the largest period ever?
     *
     */
    public double getPercentageOfExtremeValue() {
        if ((_lastTotalValue != 0) && (_extremeTotalValue != 0))
            return _lastTotalValue / _extremeTotalValue;
        else
            return 0.0d;
    }

    /**
     * How large was the last period's value as compared to the lifetime average value?
     *
     */
    public double getPercentageOfLifetimeValue() {
        if ((_lastTotalValue != 0) && (_lifetimeTotalValue != 0)) {
            double lifetimePeriodValue = _period * (_lifetimeTotalValue / (now() - _creationDate));
            return _lastTotalValue / lifetimePeriodValue;
        } else {
            return 0.0d;
        }
    }

    public void store(String prefix, StringBuffer buf) throws IOException {
        PersistenceHelper.add(buf, prefix, ".period", "Number of milliseconds in the period", _period);
        PersistenceHelper.add(buf, prefix, ".creationDate",
                              "When was this rate created?  (milliseconds since the epoch, GMT)", _creationDate);
        PersistenceHelper.add(buf, prefix, ".lastCoallesceDate",
                              "When did we last coallesce this rate?  (milliseconds since the epoch, GMT)",
                              _lastCoallesceDate);
        PersistenceHelper.add(buf, prefix, ".currentDate",
                              "When did this data get written?  (milliseconds since the epoch, GMT)", now());
        PersistenceHelper.add(buf, prefix, ".currentTotalValue",
                              "Total value of data points in the current (uncoallesced) period", _currentTotalValue);
        PersistenceHelper
                         .add(buf, prefix, ".currentEventCount",
                              "How many events have occurred in the current (uncoallesced) period?", _currentEventCount);
        PersistenceHelper.add(buf, prefix, ".currentTotalEventTime",
                              "How many milliseconds have the events in the current (uncoallesced) period consumed?",
                              _currentTotalEventTime);
        PersistenceHelper.add(buf, prefix, ".lastTotalValue",
                              "Total value of data points in the most recent (coallesced) period", _lastTotalValue);
        PersistenceHelper.add(buf, prefix, ".lastEventCount",
                              "How many events have occurred in the most recent (coallesced) period?", _lastEventCount);
        PersistenceHelper.add(buf, prefix, ".lastTotalEventTime",
                              "How many milliseconds have the events in the most recent (coallesced) period consumed?",
                              _lastTotalEventTime);
        PersistenceHelper.add(buf, prefix, ".extremeTotalValue",
                              "Total value of data points in the most extreme period", _extremeTotalValue);
        PersistenceHelper.add(buf, prefix, ".extremeEventCount",
                              "How many events have occurred in the most extreme period?", _extremeEventCount);
        PersistenceHelper.add(buf, prefix, ".extremeTotalEventTime",
                              "How many milliseconds have the events in the most extreme period consumed?",
                              _extremeTotalEventTime);
        PersistenceHelper.add(buf, prefix, ".lifetimeTotalValue",
                              "Total value of data points since this stat was created", _lifetimeTotalValue);
        PersistenceHelper.add(buf, prefix, ".lifetimeEventCount",
                              "How many events have occurred since this stat was created?", _lifetimeEventCount);
        PersistenceHelper.add(buf, prefix, ".lifetimeTotalEventTime",
                              "How many milliseconds have the events since this stat was created consumed?",
                              _lifetimeTotalEventTime);
    }

    /**
     * Load this rate from the properties, taking data from the data points 
     * underneath the given prefix.
     *
     * @param prefix prefix to the property entries (should NOT end with a period)
     * @param treatAsCurrent if true, we'll treat the loaded data as if no time has
     *                       elapsed since it was written out, but if it is false, we'll
     *                       treat the data with as much freshness (or staleness) as appropriate.
     * @throws IllegalArgumentException if the data was formatted incorrectly
     */
    public void load(Properties props, String prefix, boolean treatAsCurrent) throws IllegalArgumentException {
        _period = PersistenceHelper.getLong(props, prefix, ".period");
        _creationDate = PersistenceHelper.getLong(props, prefix, ".creationDate");
        _lastCoallesceDate = PersistenceHelper.getLong(props, prefix, ".lastCoallesceDate");
        _currentTotalValue = PersistenceHelper.getDouble(props, prefix, ".currentTotalValue");
        _currentEventCount = PersistenceHelper.getLong(props, prefix, ".currentEventCount");
        _currentTotalEventTime = PersistenceHelper.getLong(props, prefix, ".currentTotalEventTime");
        _lastTotalValue = PersistenceHelper.getDouble(props, prefix, ".lastTotalValue");
        _lastEventCount = PersistenceHelper.getLong(props, prefix, ".lastEventCount");
        _lastTotalEventTime = PersistenceHelper.getLong(props, prefix, ".lastTotalEventTime");
        _extremeTotalValue = PersistenceHelper.getDouble(props, prefix, ".extremeTotalValue");
        _extremeEventCount = PersistenceHelper.getLong(props, prefix, ".extremeEventCount");
        _extremeTotalEventTime = PersistenceHelper.getLong(props, prefix, ".extremeTotalEventTime");
        _lifetimeTotalValue = PersistenceHelper.getDouble(props, prefix, ".lifetimeTotalValue");
        _lifetimeEventCount = PersistenceHelper.getLong(props, prefix, ".lifetimeEventCount");
        _lifetimeTotalEventTime = PersistenceHelper.getLong(props, prefix, ".lifetimeTotalEventTime");

        if (treatAsCurrent) _lastCoallesceDate = now();

        if (_period <= 0) throw new IllegalArgumentException("Period for " + prefix + " is invalid");
        coallesce();
    }

    public boolean equals(Object obj) {
        if ((obj == null) || (obj.getClass() != Rate.class)) return false;
        Rate r = (Rate) obj;
        return _period == r.getPeriod() && _creationDate == r.getCreationDate() &&
        //_lastCoallesceDate == r.getLastCoallesceDate() &&
               _currentTotalValue == r.getCurrentTotalValue() && _currentEventCount == r.getCurrentEventCount()
               && _currentTotalEventTime == r.getCurrentTotalEventTime() && _lastTotalValue == r.getLastTotalValue()
               && _lastEventCount == r.getLastEventCount() && _lastTotalEventTime == r.getLastTotalEventTime()
               && _extremeTotalValue == r.getExtremeTotalValue() && _extremeEventCount == r.getExtremeEventCount()
               && _extremeTotalEventTime == r.getExtremeTotalEventTime()
               && _lifetimeTotalValue == r.getLifetimeTotalValue() && _lifetimeEventCount == r.getLifetimeEventCount()
               && _lifetimeTotalEventTime == r.getLifetimeTotalEventTime();
    }

    public String toString() {
        StringBuffer buf = new StringBuffer(2048);
        buf.append("\n\t total value: ").append(getLastTotalValue());
        buf.append("\n\t highest total value: ").append(getExtremeTotalValue());
        buf.append("\n\t lifetime total value: ").append(getLifetimeTotalValue());
        buf.append("\n\t # periods: ").append(getLifetimePeriods());
        buf.append("\n\t average value: ").append(getAverageValue());
        buf.append("\n\t highest average value: ").append(getExtremeAverageValue());
        buf.append("\n\t lifetime average value: ").append(getLifetimeAverageValue());
        buf.append("\n\t % of lifetime rate: ").append(100.0d * getPercentageOfLifetimeValue());
        buf.append("\n\t % of highest rate: ").append(100.0d * getPercentageOfExtremeValue());
        buf.append("\n\t # events: ").append(getLastEventCount());
        buf.append("\n\t lifetime events: ").append(getLifetimeEventCount());
        if (getLifetimeTotalEventTime() > 0) {
            // we have some actual event durations
            buf.append("\n\t % of time spent processing events: ").append(100.0d * getLastEventSaturation());
            buf.append("\n\t total value if we were always processing events: ").append(getLastSaturationLimit());
            buf.append("\n\t max % of time spent processing events: ").append(100.0d * getExtremeEventSaturation());
            buf.append("\n\t max total value if we were always processing events: ")
               .append(getExtremeSaturationLimit());
        }
        return buf.toString();
    }

    private final static long now() {
        return Clock.getInstance().now();
    }

    public static void main(String args[]) {
        Rate rate = new Rate(1000);
        for (int i = 0; i < 50; i++) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ie) {
            }
            rate.addData(i * 100, 20);
        }
        rate.coallesce();
        StringBuffer buf = new StringBuffer(1024);
        try {
            rate.store("rate.test", buf);
            byte data[] = buf.toString().getBytes();
            _log.error("Stored rate: size = " + data.length + "\n" + buf.toString());

            Properties props = new Properties();
            props.load(new java.io.ByteArrayInputStream(data));

            //_log.error("Properties loaded: \n" + props);

            Rate r = new Rate(props, "rate.test", true);

            _log.error("Comparison after store/load: " + r.equals(rate));
        } catch (Throwable t) {
            _log.error("b0rk", t);
        }
        try {
            Thread.sleep(5000);
        } catch (InterruptedException ie) {
        }
    }
}