package net.i2p.router.tunnel;

import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Minor extension to allow message history integration
 */
public class RouterFragmentHandler extends FragmentHandler {
    private RouterContext _routerContext;
    private Log _log;
    
    public RouterFragmentHandler(RouterContext context, DefragmentedReceiver receiver) {
        super(context, receiver);
        _routerContext = context;
        _log = context.logManager().getLog(RouterFragmentHandler.class);
    }
    
    protected void noteReception(long messageId, int fragmentId, String status) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Received fragment " + fragmentId + " for message " + messageId + ": " + status);
        _routerContext.messageHistory().receiveTunnelFragment(messageId, fragmentId, status);
    }
    protected void noteCompletion(long messageId) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Received complete message " + messageId);
        _routerContext.messageHistory().receiveTunnelFragmentComplete(messageId);
    }
    protected void noteFailure(long messageId, String status) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Dropped message " + messageId + ": " + status);
        _routerContext.messageHistory().droppedFragmentedMessage(messageId, status);
    }
}
