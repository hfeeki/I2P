package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Date;

/**
 * Render a log record according to the log manager's settings
 *
 */
class LogRecordFormatter {
    private final static String NL = System.getProperty("line.separator");
    // arbitrary max length for the classname property (this makes is it lines up nicely)
    private final static int MAX_WHERE_LENGTH = 30;
    // if we're going to have one for where... be consistent
    private final static int MAX_THREAD_LENGTH = 12;
    private final static int MAX_PRIORITY_LENGTH = 5;

    public static String formatRecord(LogRecord rec) {
        StringBuffer buf = new StringBuffer();
        char format[] = LogManager.getInstance()._getFormat();
        for (int i = 0; i < format.length; ++i) {
            switch ((int) format[i]) {
            case (int) LogManager.DATE:
                buf.append(getWhen(rec));
                break;
            case (int) LogManager.CLASS:
                buf.append(getWhere(rec));
                break;
            case (int) LogManager.THREAD:
                buf.append(getThread(rec));
                break;
            case (int) LogManager.PRIORITY:
                buf.append(getPriority(rec));
                break;
            case (int) LogManager.MESSAGE:
                buf.append(getWhat(rec));
                break;
            default:
                buf.append(format[i]);
                break;
            }
        }
        buf.append(NL);
        if (rec.getThrowable() != null) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
            PrintWriter pw = new PrintWriter(baos, true);
            rec.getThrowable().printStackTrace(pw);
            try {
                pw.flush();
                baos.flush();
            } catch (IOException ioe) {
            }
            byte tb[] = baos.toByteArray();
            buf.append(new String(tb));
        }
        return buf.toString();
    }

    private static String getThread(LogRecord logRecord) {
        return toString(logRecord.getThreadName(), MAX_THREAD_LENGTH);
    }

    private static String getWhen(LogRecord logRecord) {
        return LogManager.getInstance()._getDateFormat().format(new Date(logRecord.getDate()));
    }

    private static String getPriority(LogRecord rec) {
        return toString(Log.toLevelString(rec.getPriority()), MAX_PRIORITY_LENGTH);
    }

    private static String getWhat(LogRecord rec) {
        return rec.getMessage();
    }

    private static String getWhere(LogRecord rec) {
        String src = (rec.getSource() != null ? rec.getSource().getName() : rec.getSourceName());
        if (src == null) src = "<none>";
        return toString(src, MAX_WHERE_LENGTH);
    }

    private static String toString(String str, int size) {
        StringBuffer buf = new StringBuffer();
        if (str == null) str = "";
        if (str.length() > size) str = str.substring(str.length() - size);
        buf.append(str);
        while (buf.length() < size)
            buf.append(' ');
        return buf.toString();
    }
}