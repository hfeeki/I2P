package net.i2p.router.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import net.i2p.util.Log;
import net.i2p.stat.StatManager;

/**
 * Handler to deal with form submissions from the stats config form and act
 * upon the values.
 *
 */
public class ConfigStatsHandler extends FormHandler {
    private String _filename;
    private List _stats;
    private boolean _explicitFilter;
    private String _explicitFilterValue;
    
    public ConfigStatsHandler() {
        super();
        _stats = new ArrayList();
        _explicitFilter = false;
    }
    
    protected void processForm() {
        saveChanges();
    }
    
    public void setFilename(String filename) {
        _filename = (filename != null ? filename.trim() : null);
    }

    public void setStatList(String stats[]) {
        if (stats != null) {
            for (int i = 0; i < stats.length; i++) {
                String cur = stats[i].trim();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Stat: [" + cur + "]");
                if ( (cur.length() > 0) && (!_stats.contains(cur)) )
                    _stats.add(cur);
            }
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Updated stats: " + _stats);
    }

    public void setExplicitFilter(String foo) { _explicitFilter = true; }
    public void setExplicitFilterValue(String filter) { _explicitFilterValue = filter; }
    
    /**
     * The user made changes to the config and wants to save them, so
     * lets go ahead and do so.
     *
     */
    private void saveChanges() {
        if (_filename == null)
            _filename = StatManager.DEFAULT_STAT_FILE;
        _context.router().setConfigSetting(StatManager.PROP_STAT_FILE, _filename);
        
        if (_explicitFilter) {
            _stats.clear();
            if (_explicitFilterValue == null)
                _explicitFilterValue = "";
            
            if (_explicitFilterValue.indexOf(',') != -1) {
                StringTokenizer tok = new StringTokenizer(_explicitFilterValue, ",");
                while (tok.hasMoreTokens()) {
                    String cur = tok.nextToken().trim();
                    if ( (cur.length() > 0) && (!_stats.contains(cur)) )
                        _stats.add(cur);
                }
            } else {
                String stat = _explicitFilterValue.trim();
                if ( (stat.length() > 0) && (!_stats.contains(stat)) )
                    _stats.add(stat);
            }
        }
        
        StringBuffer stats = new StringBuffer();
        for (int i = 0; i < _stats.size(); i++) {
            stats.append((String)_stats.get(i));
            if (i + 1 < _stats.size())
                stats.append(',');
        }
            
        _context.router().setConfigSetting(StatManager.PROP_STAT_FILTER, stats.toString());
        boolean ok = _context.router().saveConfig();
        if (ok) 
            addFormNotice("Stat filter and location updated successfully to: " + stats.toString());
        else
            addFormError("Failed to update the stat filter and location");
    }
    
}
