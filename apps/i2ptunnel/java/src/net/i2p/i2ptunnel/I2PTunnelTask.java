/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.util.Set;

import net.i2p.client.I2PSession;
import net.i2p.util.EventDispatcher;
import net.i2p.util.EventDispatcherImpl;

/**
 * Either a Server or a Client.
 */

public abstract class I2PTunnelTask implements EventDispatcher {

    private final EventDispatcherImpl _event = new EventDispatcherImpl();

    private int id;
    private String name;
    protected boolean open;
    private I2PTunnel tunnel;

    //protected I2PTunnelTask(String name) {
    //	I2PTunnelTask(name, (EventDispatcher)null);
    //}

    protected I2PTunnelTask(String name, EventDispatcher notifyThis) {
        attachEventDispatcher(notifyThis);
        this.name = name;
        this.id = -1;
    }

    /** for apps that use multiple I2PTunnel instances */
    public void setTunnel(I2PTunnel pTunnel) {
        tunnel = pTunnel;
    }

    public int getId() {
        return this.id;
    }

    public boolean isOpen() {
        return open;
    }

    public void setId(int id) {
        this.id = id;
    }

    protected void setName(String name) {
        this.name = name;
    }

    protected void routerDisconnected() {
        tunnel.routerDisconnected();
    }

    public abstract boolean close(boolean forced);

    public void disconnected(I2PSession session) {
        routerDisconnected();
    }

    public void errorOccurred(I2PSession session, String message, Throwable error) {
    }

    public void reportAbuse(I2PSession session, int severity) {
    }

    public String toString() {
        return name;
    }

    /* Required by the EventDispatcher interface */
    public EventDispatcher getEventDispatcher() {
        return _event;
    }

    public void attachEventDispatcher(EventDispatcher e) {
        _event.attachEventDispatcher(e.getEventDispatcher());
    }

    public void detachEventDispatcher(EventDispatcher e) {
        _event.detachEventDispatcher(e.getEventDispatcher());
    }

    public void notifyEvent(String e, Object a) {
        _event.notifyEvent(e, a);
    }

    public Object getEventValue(String n) {
        return _event.getEventValue(n);
    }

    public Set getEvents() {
        return _event.getEvents();
    }

    public void ignoreEvents() {
        _event.ignoreEvents();
    }

    public void unIgnoreEvents() {
        _event.unIgnoreEvents();
    }

    public Object waitEventValue(String n) {
        return _event.waitEventValue(n);
    }
}