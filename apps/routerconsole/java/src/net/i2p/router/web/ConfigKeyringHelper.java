package net.i2p.router.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;

import net.i2p.router.RouterContext;

public class ConfigKeyringHelper {
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

    public ConfigKeyringHelper() {}
    
    public String getSummary() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(4*1024);
        try {
            _context.keyRing().renderStatusHTML(new OutputStreamWriter(baos));
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return new String(baos.toByteArray());
    }
}
