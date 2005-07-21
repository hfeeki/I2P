package net.i2p.router.web;

import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;

/**
 * Simple helper to query the appropriate router for data necessary to render
 * any emergency notices 
 */
public class NoticeHelper {
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
    
    public String getSystemNotice() {
        if (_context.router().gracefulShutdownInProgress()) {
            long remaining = _context.router().getShutdownTimeRemaining();
            if (remaining > 0)
                return "Graceful shutdown in " + DataHelper.formatDuration(remaining);
            else
                return "Graceful shutdown imminent, please be patient as state is written to disk";
        } else {
            return "";
        }
    }
}