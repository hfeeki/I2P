package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.DataHelper;
import net.i2p.I2PAppContext;

/**
 * Wrapper class for whatever logging system I2P uses.  This class should be 
 * instantiated and kept as a variable for each class it is used by, ala:
 *  <code>private final static Log _log = new Log(MyClassName.class);</code>
 *
 * If there is anything in here that doesn't make sense, turn off your computer
 * and go fly a kite.
 *
 *
 * @author jrandom
 */
public class Log {
    private Class _class;
    private String _name;
    private int _minPriority;
    private LogScope _scope;
    private LogManager _manager;

    public final static int DEBUG = 10;
    public final static int INFO = 20;
    public final static int WARN = 30;
    public final static int ERROR = 40;
    public final static int CRIT = 50;

    public final static String STR_DEBUG = "DEBUG";
    public final static String STR_INFO = "INFO";
    public final static String STR_WARN = "WARN";
    public final static String STR_ERROR = "ERROR";
    public final static String STR_CRIT = "CRIT";

    public static int getLevel(String level) {
        if (level == null) return Log.CRIT;
        level = level.toUpperCase();
        if (STR_DEBUG.startsWith(level)) return DEBUG;
        if (STR_INFO.startsWith(level)) return INFO;
        if (STR_WARN.startsWith(level)) return WARN;
        if (STR_ERROR.startsWith(level)) return ERROR;
        if (STR_CRIT.startsWith(level)) return CRIT;
        return CRIT;
    }

    public static String toLevelString(int level) {
        switch (level) {
        case DEBUG:
            return STR_DEBUG;
        case INFO:
            return STR_INFO;
        case WARN:
            return STR_WARN;
        case ERROR:
            return STR_ERROR;
        case CRIT:
            return STR_CRIT;
        }
        return (level > CRIT ? STR_CRIT : STR_DEBUG);
    }

    public Log(Class cls) {
        this(I2PAppContext.getGlobalContext().logManager(), cls, null);
        _manager.addLog(this);
    }

    public Log(String name) {
        this(I2PAppContext.getGlobalContext().logManager(), null, name);
        _manager.addLog(this);
    }

    Log(LogManager manager, Class cls) {
        this(manager, cls, null);
    }

    Log(LogManager manager, String name) {
        this(manager, null, name);
    }

    Log(LogManager manager, Class cls, String name) {
        _manager = manager;
        _class = cls;
        _name = name;
        _minPriority = DEBUG;
        _scope = new LogScope(name, cls);
        //_manager.addRecord(new LogRecord(Log.class, null, Thread.currentThread().getName(), Log.DEBUG, 
        //                                 "Log created with manager " + manager + " for class " + cls, null));
    }

    public void log(int priority, String msg) {
        if (priority >= _minPriority) {
            _manager.addRecord(new LogRecord(_class, _name, 
                                             Thread.currentThread().getName(), priority,
                                             msg, null));
        }
    }

    public void log(int priority, String msg, Throwable t) {
        if (priority >= _minPriority) {
            _manager.addRecord(new LogRecord(_class, _name, 
                                             Thread.currentThread().getName(), priority,
                                             msg, t));
        }
    }

    public void debug(String msg) {
        log(DEBUG, msg);
    }

    public void debug(String msg, Throwable t) {
        log(DEBUG, msg, t);
    }

    public void info(String msg) {
        log(INFO, msg);
    }

    public void info(String msg, Throwable t) {
        log(INFO, msg, t);
    }

    public void warn(String msg) {
        log(WARN, msg);
    }

    public void warn(String msg, Throwable t) {
        log(WARN, msg, t);
    }

    public void error(String msg) {
        log(ERROR, msg);
    }

    public void error(String msg, Throwable t) {
        log(ERROR, msg, t);
    }

    public int getMinimumPriority() {
        return _minPriority;
    }

    public void setMinimumPriority(int priority) {
        _minPriority = priority;
        //_manager.addRecord(new LogRecord(Log.class, null, Thread.currentThread().getName(), Log.DEBUG, 
        //                                 "Log with manager " + _manager + " for class " + _class 
        //                                 + " new priority " + toLevelString(priority), null));
    }

    public boolean shouldLog(int priority) {
        return priority >= _minPriority;
    }

    String getName() {
        if (_class != null)
            return _class.getName();
        else
            return _name;
    }
    
    public Object getScope() { return _scope; }
    private static final class LogScope {
        private String _scopeName;
        private Class _scopeClass;
        public LogScope(String name, Class cls) {
            _scopeName = name;
            _scopeClass = cls;
        }
        public int hashCode() {
            if (_scopeClass != null) 
                return _scopeClass.hashCode();
            else if (_scopeName != null)
                return _scopeName.hashCode();
            else
                return 42;
        }
        public boolean equals(Object obj) {
            if (obj == null) throw new NullPointerException("Null object scope?");
            if (obj instanceof LogScope) {
                LogScope s = (LogScope)obj;
                return DataHelper.eq(s._scopeName, _scopeName) &&
                       DataHelper.eq(s._scopeClass, _scopeClass);
            } else {
                return false;
            }
        }
    }
}