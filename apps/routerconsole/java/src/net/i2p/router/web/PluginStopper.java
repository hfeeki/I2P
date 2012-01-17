package net.i2p.router.web;

import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 *  Stop all plugins that are installed and running
 *
 *  @since 0.7.13
 *  @author zzz
 */
public class PluginStopper extends PluginStarter {

    public PluginStopper(RouterContext ctx) {
        super(ctx);
    }

    @Override
    public void run() {
        stopPlugins(_context);
    }

    /**
     *  Stop all running plugins
     *
     *  this shouldn't throw anything
     */
    private static void stopPlugins(RouterContext ctx) {
        Log log = ctx.logManager().getLog(PluginStopper.class);
        for (String app : getPlugins()) {
            if (isPluginRunning(app, ctx)) {
                try {
                   stopPlugin(ctx, app);
                } catch (Throwable e) {
                   if (log.shouldLog(Log.WARN))
                       log.warn("Failed to stop plugin: " + app, e);
                }
            }
        }
    }
}
