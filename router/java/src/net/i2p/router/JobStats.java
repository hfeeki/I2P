package net.i2p.router;

import net.i2p.data.DataHelper;

/** glorified struct to contain basic job stats */
class JobStats {
    private String _job;
    private long _numRuns;
    private long _totalTime;
    private long _maxTime;
    private long _minTime;
    private long _totalPendingTime;
    private long _maxPendingTime;
    private long _minPendingTime;

    public JobStats(String name) {
	_job = name;
	_numRuns = 0;
	_totalTime = 0;
	_maxTime = -1;
	_minTime = -1;
	_totalPendingTime = 0;
	_maxPendingTime = -1;
	_minPendingTime = -1;
    }

    public void jobRan(long runTime, long lag) {
	_numRuns++;
	_totalTime += runTime;
	if ( (_maxTime < 0) || (runTime > _maxTime) )
	    _maxTime = runTime;
	if ( (_minTime < 0) || (runTime < _minTime) )
	    _minTime = runTime;
	_totalPendingTime += lag;
	if ( (_maxPendingTime < 0) || (lag > _maxPendingTime) )
	    _maxPendingTime = lag;
	if ( (_minPendingTime < 0) || (lag < _minPendingTime) )
	    _minPendingTime = lag;
    }

    public String getName() { return _job; }
    public long getRuns() { return _numRuns; }
    public long getTotalTime() { return _totalTime; }
    public long getMaxTime() { return _maxTime; }
    public long getMinTime() { return _minTime; }
    public long getAvgTime() { if (_numRuns > 0) return _totalTime / _numRuns; else return 0; }
    public long getTotalPendingTime() { return _totalPendingTime; }
    public long getMaxPendingTime() { return _maxPendingTime; }
    public long getMinPendingTime() { return _minPendingTime; }
    public long getAvgPendingTime() { if (_numRuns > 0) return _totalPendingTime / _numRuns; else return 0; }

    public int hashCode() { return _job.hashCode(); }
    public boolean equals(Object obj) {
	if ( (obj != null) && (obj instanceof JobStats) ) {
	    JobStats stats = (JobStats)obj;
	    return DataHelper.eq(getName(), stats.getName()) &&
		   getRuns() == stats.getRuns() &&
		   getTotalTime() == stats.getTotalTime() &&
		   getMaxTime() == stats.getMaxTime() &&
		   getMinTime() == stats.getMinTime();
	} else {
	    return false;
	}
    }

    public String toString() { 
	StringBuffer buf = new StringBuffer();
	buf.append("Over ").append(getRuns()).append(" runs, job <b>").append(getName()).append("</b> took ");
	buf.append(getTotalTime()).append("ms (").append(getAvgTime()).append("ms/").append(getMaxTime()).append("ms/");
	buf.append(getMinTime()).append("ms avg/max/min) after a total lag of ");
	buf.append(getTotalPendingTime()).append("ms (").append(getAvgPendingTime()).append("ms/");
	buf.append(getMaxPendingTime()).append("ms/").append(getMinPendingTime()).append("ms avg/max/min)");
	return buf.toString();
    }
}
