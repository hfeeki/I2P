package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.PublicKey;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelBuildMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.tunnel.BuildMessageGenerator;
import net.i2p.util.Log;

/**
 *
 */
class BuildRequestor {
    private static final List ORDER = new ArrayList(BuildMessageGenerator.ORDER.length);
    static {
        for (int i = 0; i < BuildMessageGenerator.ORDER.length; i++)
            ORDER.add(Integer.valueOf(i));
    }
    private static final int PRIORITY = 500;
    /**
     *  At 10 seconds, we were receiving about 20% of replies after expiration
     *  Todo: make this variable on a per-request basis, to account for tunnel length,
     *  expl. vs. client, uptime, and network conditions.
     *  Put the expiration in the PTCC.
     *
     *  Also, perhaps, save the PTCC even after expiration for an extended time,
     *  so can we use a successfully built tunnel anyway.
     *
     */
    static final int REQUEST_TIMEOUT = 13*1000;
    
    private static boolean usePairedTunnels(RouterContext ctx) {
        String val = ctx.getProperty("router.usePairedTunnels");
        if ( (val == null) || (Boolean.valueOf(val).booleanValue()) )
            return true;
        else
            return false;
    }
    
    /** new style requests need to fill in the tunnel IDs before hand */
    public static void prepare(RouterContext ctx, PooledTunnelCreatorConfig cfg) {
        for (int i = 0; i < cfg.getLength(); i++) {
            if ( (!cfg.isInbound()) && (i == 0) ) {
                // outbound gateway (us) doesn't receive on a tunnel id
                if (cfg.getLength() <= 1) // zero hop, pretend to have a send id
                    cfg.getConfig(i).setSendTunnelId(DataHelper.toLong(4, ctx.random().nextLong(TunnelId.MAX_ID_VALUE)));
            } else {
                cfg.getConfig(i).setReceiveTunnelId(DataHelper.toLong(4, ctx.random().nextLong(TunnelId.MAX_ID_VALUE)));
            }
            
            if (i > 0)
                cfg.getConfig(i-1).setSendTunnelId(cfg.getConfig(i).getReceiveTunnelId());
            byte iv[] = new byte[16];
            ctx.random().nextBytes(iv);
            cfg.getConfig(i).setReplyIV(new ByteArray(iv));
            cfg.getConfig(i).setReplyKey(ctx.keyGenerator().generateSessionKey());
        }
        cfg.setReplyMessageId(ctx.random().nextLong(I2NPMessage.MAX_ID_VALUE));
    }
    public static void request(RouterContext ctx, TunnelPool pool, PooledTunnelCreatorConfig cfg, BuildExecutor exec) {
        // new style crypto fills in all the blanks, while the old style waits for replies to fill in the next hop, etc
        prepare(ctx, cfg);
        
        if (cfg.getLength() <= 1) {
            buildZeroHop(ctx, pool, cfg, exec);
            return;
        }
        
        Log log = ctx.logManager().getLog(BuildRequestor.class);
        cfg.setTunnelPool(pool);
        
        TunnelInfo pairedTunnel = null;
        if (pool.getSettings().isExploratory() || !usePairedTunnels(ctx)) {
            if (pool.getSettings().isInbound())
                pairedTunnel = ctx.tunnelManager().selectOutboundTunnel();
            else
                pairedTunnel = ctx.tunnelManager().selectInboundTunnel();
        } else {
            if (pool.getSettings().isInbound())
                pairedTunnel = ctx.tunnelManager().selectOutboundTunnel(pool.getSettings().getDestination());
            else
                pairedTunnel = ctx.tunnelManager().selectInboundTunnel(pool.getSettings().getDestination());
        }
        if (pairedTunnel == null) {   
            if (log.shouldLog(Log.WARN))
                log.warn("Couldn't find a paired tunnel for " + cfg + ", fall back on exploratory tunnels for pairing");
            if (!pool.getSettings().isExploratory() && usePairedTunnels(ctx))
                if (pool.getSettings().isInbound())
                    pairedTunnel = ctx.tunnelManager().selectOutboundTunnel();
                else
                    pairedTunnel = ctx.tunnelManager().selectInboundTunnel();
        }
        if (pairedTunnel == null) {
            if (log.shouldLog(Log.ERROR))
                log.error("Tunnel build failed, as we couldn't find a paired tunnel for " + cfg);
            exec.buildComplete(cfg, pool);
            return;
        }
        
        long beforeCreate = System.currentTimeMillis();
        TunnelBuildMessage msg = createTunnelBuildMessage(ctx, pool, cfg, pairedTunnel, exec);
        long createTime = System.currentTimeMillis()-beforeCreate;
        if (msg == null) {
            if (log.shouldLog(Log.ERROR))
                log.error("Tunnel build failed, as we couldn't create the tunnel build message for " + cfg);
            exec.buildComplete(cfg, pool);
            return;
        }
        
        //cfg.setPairedTunnel(pairedTunnel);
        
        long beforeDispatch = System.currentTimeMillis();
        if (cfg.isInbound()) {
            if (log.shouldLog(Log.INFO))
                log.info("Sending the tunnel build request " + msg.getUniqueId() + " out the tunnel " + pairedTunnel + " to " 
                          + cfg.getPeer(0).toBase64() + " for " + cfg + " waiting for the reply of "
                          + cfg.getReplyMessageId());
            // send it out a tunnel targetting the first hop
            ctx.tunnelDispatcher().dispatchOutbound(msg, pairedTunnel.getSendTunnelId(0), cfg.getPeer(0));
        } else {
            if (log.shouldLog(Log.INFO))
                log.info("Sending the tunnel build request directly to " + cfg.getPeer(1).toBase64() 
                          + " for " + cfg + " waiting for the reply of " + cfg.getReplyMessageId() 
                          + " with msgId=" + msg.getUniqueId());
            // send it directly to the first hop
            OutNetMessage outMsg = new OutNetMessage(ctx);
            // Todo: add some fuzz to the expiration to make it harder to guess how many hops?
            outMsg.setExpiration(msg.getMessageExpiration());
            outMsg.setMessage(msg);
            outMsg.setPriority(PRIORITY);
            RouterInfo peer = ctx.netDb().lookupRouterInfoLocally(cfg.getPeer(1));
            if (peer == null) {
                if (log.shouldLog(Log.ERROR))
                    log.error("Could not find the next hop to send the outbound request to: " + cfg);
                exec.buildComplete(cfg, pool);
                return;
            }
            outMsg.setTarget(peer);
            outMsg.setOnFailedSendJob(new TunnelBuildFirstHopFailJob(ctx, pool, cfg, exec));
            ctx.outNetMessagePool().add(outMsg);
        }
        if (log.shouldLog(Log.DEBUG))
            log.debug("Tunnel build message " + msg.getUniqueId() + " created in " + createTime
                      + "ms and dispatched in " + (System.currentTimeMillis()-beforeDispatch));
    }
    
    private static TunnelBuildMessage createTunnelBuildMessage(RouterContext ctx, TunnelPool pool, PooledTunnelCreatorConfig cfg, TunnelInfo pairedTunnel, BuildExecutor exec) {
        Log log = ctx.logManager().getLog(BuildRequestor.class);
        long replyTunnel = 0;
        Hash replyRouter = null;
        if (cfg.isInbound()) {
            replyTunnel = 0;
            replyRouter = ctx.routerHash();
        } else {
            replyTunnel = pairedTunnel.getReceiveTunnelId(0).getTunnelId();
            replyRouter = pairedTunnel.getPeer(0);
        }

        // populate and encrypt the message
        BuildMessageGenerator gen = new BuildMessageGenerator();
        TunnelBuildMessage msg = new TunnelBuildMessage(ctx);

        long replyMessageId = ctx.random().nextLong(I2NPMessage.MAX_ID_VALUE);
        cfg.setReplyMessageId(replyMessageId);
        
        List order = new ArrayList(ORDER);
        Collections.shuffle(order, ctx.random()); // randomized placement within the message
        cfg.setReplyOrder(order);
        
        if (log.shouldLog(Log.DEBUG))
            log.debug("Build order: " + order + " for " + cfg);
        
        for (int i = 0; i < BuildMessageGenerator.ORDER.length; i++) {
            int hop = ((Integer)order.get(i)).intValue();
            PublicKey key = null;
    
            if (BuildMessageGenerator.isBlank(cfg, hop)) {
                // erm, blank
            } else {
                Hash peer = cfg.getPeer(hop);
                RouterInfo peerInfo = ctx.netDb().lookupRouterInfoLocally(peer);
                if (peerInfo == null) {
                    if (log.shouldLog(Log.ERROR))
                        log.error("Peer selected for hop " + i + "/" + hop + " was not found locally: " 
                                  + peer.toBase64() + " for " + cfg);
                    return null;
                } else {
                    key = peerInfo.getIdentity().getPublicKey();
                }
            }
            if (log.shouldLog(Log.DEBUG))
                log.debug(cfg.getReplyMessageId() + ": record " + i + "/" + hop + " has key " + key);
            gen.createRecord(i, hop, msg, cfg, replyRouter, replyTunnel, ctx, key);
        }
        gen.layeredEncrypt(ctx, msg, cfg, order);
        
        return msg;
    }
    
    private static void buildZeroHop(RouterContext ctx, TunnelPool pool, PooledTunnelCreatorConfig cfg, BuildExecutor exec) {
        Log log = ctx.logManager().getLog(BuildRequestor.class);
        if (log.shouldLog(Log.DEBUG))
            log.debug("Build zero hop tunnel " + cfg);            

        exec.buildComplete(cfg, pool);
        if (cfg.isInbound())
            ctx.tunnelDispatcher().joinInbound(cfg);
        else
            ctx.tunnelDispatcher().joinOutbound(cfg);
        pool.addTunnel(cfg);
        exec.buildSuccessful(cfg);
        ExpireJob expireJob = new ExpireJob(ctx, cfg, pool);
        cfg.setExpireJob(expireJob);
        ctx.jobQueue().addJob(expireJob);
        // can it get much easier?
    }

    /**
     *  Do two important things if we can't get the build msg to the
     *  first hop on an outbound tunnel -
     *  - Call buildComplete() so we can get started on the next build
     *    without waiting for the full expire time
     *  - Blame the first hop in the profile
     *  Most likely to happen on an exploratory tunnel, obviously.
     *  Can't do this for inbound tunnels since the msg goes out an expl. tunnel.
     */
    private static class TunnelBuildFirstHopFailJob extends JobImpl {
        TunnelPool _pool;
        PooledTunnelCreatorConfig _cfg;
        BuildExecutor _exec;
        private TunnelBuildFirstHopFailJob(RouterContext ctx, TunnelPool pool, PooledTunnelCreatorConfig cfg, BuildExecutor exec) {
            super(ctx);
            _cfg = cfg;
            _exec = exec;
            _pool = pool;
        }
        public String getName() { return "Timeout contacting first peer for OB tunnel"; }
        public void runJob() {
            _exec.buildComplete(_cfg, _pool);
            getContext().profileManager().tunnelTimedOut(_cfg.getPeer(1));
            getContext().statManager().addRateData("tunnel.buildFailFirstHop", 1, 0);
            // static, no _log
            //System.err.println("Cant contact first hop for " + _cfg);
        }
    }
}
