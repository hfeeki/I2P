package net.i2p.router.web;

import net.i2p.router.ClientTunnelSettings;

/**
 * Handler to deal with form submissions from the service config form and act
 * upon the values.
 *
 */
public class ConfigServiceHandler extends FormHandler {
    public void ConfigNetHandler() {}
    
    protected void processForm() {
        if (_action == null) return;
        
        if ("Shutdown gracefully".equals(_action)) {
            _context.router().shutdownGracefully();
            addFormNotice("Graceful shutdown initiated");
        } else if ("Shutdown immediately".equals(_action)) {
            _context.router().shutdown();
            addFormNotice("Shutdown immediately!  boom bye bye bad bwoy");
        } else if ("Cancel graceful shutdown".equals(_action)) {
            _context.router().cancelGracefulShutdown();
            addFormNotice("Graceful shutdown cancelled");
        } else {
            addFormNotice("Blah blah blah.  whatever.  I'm not going to " + _action);
        }
    }
}
