package net.i2p.router.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;

import net.i2p.util.Log;

import net.i2p.router.RouterContext;

public class ConfigLoggingHelper {
    private RouterContext _context;
    /**
     * Configure this bean to query a particular router context
     *
     * @param contextId begging few characters of the routerHash, or null to pick
     *                  the first one we come across.
     */
    public void setContextId(String contextId) {
        try {
            _context = ContextHelper.getContext(contextId);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
    
    public ConfigLoggingHelper() {}
    
    public String getLogFilePattern() {
        return _context.logManager().getBaseLogfilename();
    }
    public String getRecordPattern() {
        return new String(_context.logManager().getFormat());
    }
    public String getDatePattern() {
        return _context.logManager().getDateFormatPattern();
    }
    public String getMaxFileSize() {
        int bytes = _context.logManager().getFileSize();
        if (bytes == 0) return "1m";
        if (bytes > 1024*1024*1024)
            return (bytes/(1024*1024*1024)) + "g";
        else if (bytes > 1024*1024)
            return (bytes/(1024*1024)) + "m";
        else
            return (bytes/(1024)) + "k";
    }
    public String getLogLevelTable() {
        StringBuffer buf = new StringBuffer(32*1024);
        Properties limits = _context.logManager().getLimits();
        TreeSet sortedLogs = new TreeSet();
        for (Iterator iter = limits.keySet().iterator(); iter.hasNext(); ) {
            String prefix = (String)iter.next();
            sortedLogs.add(prefix);
        }
        
        buf.append("<textarea name=\"levels\" rows=\"20\" cols=\"70\">");
        for (Iterator iter = sortedLogs.iterator(); iter.hasNext(); ) {
            String prefix = (String)iter.next();
            String level = limits.getProperty(prefix);
            buf.append(prefix).append('=').append(level).append('\n');
        }
        buf.append("</textarea><br />\n");
        buf.append("<i>Valid levels are DEBUG, INFO, WARN, ERROR, CRIT</i>\n");
        return buf.toString();
    }
    public String getDefaultLogLevelBox() {
        String cur = _context.logManager().getDefaultLimit();
        StringBuffer buf = new StringBuffer(128);
        buf.append("<select name=\"defaultloglevel\">\n");
        
        buf.append("<option value=\"DEBUG\" ");
        if ("DEFAULT".equals(cur)) buf.append(" selected=\"true\" ");
        buf.append(">DEBUG</option>\n");
        
        buf.append("<option value=\"INFO\" ");
        if ("INFO".equals(cur)) buf.append(" selected=\"true\" ");
        buf.append(">INFO</option>\n");
        
        buf.append("<option value=\"WARN\" ");
        if ("WARN".equals(cur)) buf.append(" selected=\"true\" ");
        buf.append(">WARN</option>\n");
        
        buf.append("<option value=\"ERROR\" ");
        if ("WARN".equals(cur)) buf.append(" selected=\"true\" ");
        buf.append(">ERROR</option>\n");
        
        buf.append("<option value=\"CRIT\" ");
        if ("CRIT".equals(cur)) buf.append(" selected=\"true\" ");
        buf.append(">CRIT</option>\n");
        
        buf.append("</select>\n");
        return buf.toString();
    }
}
