/* PeerConnectionOut - Keeps a queue of outgoing messages and delivers them.
   Copyright (C) 2003 Mark J. Wielaard

   This file is part of Snark.
   
   This program is free software; you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation; either version 2, or (at your option)
   any later version.
 
   This program is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.
 
   You should have received a copy of the GNU General Public License
   along with this program; if not, write to the Free Software Foundation,
   Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
*/

package org.klomp.snark;

import java.io.*;
import java.net.*;
import java.util.*;

import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

class PeerConnectionOut implements Runnable
{
  private Log _log = new Log(PeerConnectionOut.class);
  private final Peer peer;
  private final DataOutputStream dout;

  private Thread thread;
  private boolean quit;

  // Contains Messages.
  private List sendQueue = new ArrayList();
  
  private static long __id = 0;
  private long _id;
  
  long lastSent;

  public PeerConnectionOut(Peer peer, DataOutputStream dout)
  {
    this.peer = peer;
    this.dout = dout;
    _id = ++__id;

    lastSent = System.currentTimeMillis();
    quit = false;
  }
  
  public void startup() {
    thread = new I2PThread(this, "Snark sender " + _id + ": " + peer);
    thread.start();
  }

  /**
   * Continuesly monitors for more outgoing messages that have to be send.
   * Stops if quit is true of an IOException occurs.
   */
  public void run()
  {
    try
      {
        while (!quit && peer.isConnected())
          {
            Message m = null;
            PeerState state = null;
            boolean shouldFlush;
            synchronized(sendQueue)
              {
                shouldFlush = !quit && peer.isConnected() && sendQueue.isEmpty();
              }
            if (shouldFlush)
                // Make sure everything will reach the other side.
                // flush while not holding lock, could take a long time
                dout.flush();

            synchronized(sendQueue)
              {
                while (!quit && peer.isConnected() && sendQueue.isEmpty())
                  {
                    try
                      {
                        // Make sure everything will reach the other side.
                        // don't flush while holding lock, could take a long time
                        // dout.flush();
                        
                        // Wait till more data arrives.
                        sendQueue.wait(60*1000);
                      }
                    catch (InterruptedException ie)
                      {
                        /* ignored */
                      }
                  }
                state = peer.state;
                if (!quit && state != null && peer.isConnected())
                  {
                    // Piece messages are big. So if there are other
                    // (control) messages make sure they are send first.
                    // Also remove request messages from the queue if
                    // we are currently being choked to prevent them from
                    // being send even if we get unchoked a little later.
                    // (Since we will resent them anyway in that case.)
                    // And remove piece messages if we are choking.
                    
                    // this should get fixed for starvation
                    Iterator it = sendQueue.iterator();
                    while (m == null && it.hasNext())
                      {
                        Message nm = (Message)it.next();
                        if (nm.type == Message.PIECE)
                          {
                            if (state.choking) {
                              it.remove();
                              SimpleTimer.getInstance().removeEvent(nm.expireEvent);
                            }
                            nm = null;
                          }
                        else if (nm.type == Message.REQUEST && state.choked)
                          {
                            it.remove();
                            SimpleTimer.getInstance().removeEvent(nm.expireEvent);
                            nm = null;
                          }
                          
                        if (m == null && nm != null)
                          {
                            m = nm;
                            SimpleTimer.getInstance().removeEvent(nm.expireEvent);
                            it.remove();
                          }
                      }
                    if (m == null && sendQueue.size() > 0) {
                      m = (Message)sendQueue.remove(0);
                      SimpleTimer.getInstance().removeEvent(m.expireEvent);
                    }
                  }
              }
            if (m != null)
              {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Send " + peer + ": " + m + " on " + peer.metainfo.getName());
                m.sendMessage(dout);
                lastSent = System.currentTimeMillis();

                // Remove all piece messages after sending a choke message.
                if (m.type == Message.CHOKE)
                  removeMessage(Message.PIECE);

                // XXX - Should also register overhead...
                if (m.type == Message.PIECE)
                  state.uploaded(m.len);

                m = null;
              }
          }
      }
    catch (IOException ioe)
      {
        // Ignore, probably other side closed connection.
        if (_log.shouldLog(Log.INFO))
            _log.info("IOError sending to " + peer, ioe);
      }
    catch (Throwable t)
      {
        _log.error("Error sending to " + peer, t);
        if (t instanceof OutOfMemoryError)
            throw (OutOfMemoryError)t;
      }
    finally
      {
        quit = true;
        peer.disconnect();
      }
  }

  public void disconnect()
  {
    synchronized(sendQueue)
      {
        //if (quit == true)
        //  return;
        
        quit = true;
        if (thread != null)
            thread.interrupt();
        
        sendQueue.clear();
        sendQueue.notify();
      }
    if (dout != null) {
        try {
            dout.close();
        } catch (IOException ioe) {
            _log.warn("Error closing the stream to " + peer, ioe);
        }
    }
  }

  /**
   * Adds a message to the sendQueue and notifies the method waiting
   * on the sendQueue to change.
   * If a PIECE message only, add a timeout.
   */
  private void addMessage(Message m)
  {
    if (m.type == Message.PIECE)
      SimpleTimer.getInstance().addEvent(new RemoveTooSlow(m), SEND_TIMEOUT);
    synchronized(sendQueue)
      {
        sendQueue.add(m);
        sendQueue.notifyAll();
      }
  }
  
  /** remove messages not sent in 3m */
  private static final int SEND_TIMEOUT = 3*60*1000;
  private class RemoveTooSlow implements SimpleTimer.TimedEvent {
      private Message _m;
      public RemoveTooSlow(Message m) {
          _m = m;
          m.expireEvent = RemoveTooSlow.this;
      }
      
      public void timeReached() {
          boolean removed = false;
          synchronized (sendQueue) {
              removed = sendQueue.remove(_m);
              sendQueue.notifyAll();
          }
          if (removed)
              _log.info("Took too long to send " + _m + " to " + peer);
      }
  }

  /**
   * Removes a particular message type from the queue.
   *
   * @param type the Message type to remove.
   * @returns true when a message of the given type was removed, false
   * otherwise.
   */
  private boolean removeMessage(int type)
  {
    boolean removed = false;
    synchronized(sendQueue)
      {
        Iterator it = sendQueue.iterator();
        while (it.hasNext())
          {
            Message m = (Message)it.next();
            if (m.type == type)
              {
                it.remove();
                removed = true;
              }
          }
        sendQueue.notifyAll();
      }
    return removed;
  }

  void sendAlive()
  {
    Message m = new Message();
    m.type = Message.KEEP_ALIVE;
//  addMessage(m);
    synchronized(sendQueue)
      {
        if(sendQueue.isEmpty())
          sendQueue.add(m);
        sendQueue.notifyAll();
      }
  }

  void sendChoke(boolean choke)
  {
    // We cancel the (un)choke but keep PIECE messages.
    // PIECE messages are purged if a choke is actually send.
    synchronized(sendQueue)
      {
        int inverseType  = choke ? Message.UNCHOKE
                                 : Message.CHOKE;
        if (!removeMessage(inverseType))
          {
            Message m = new Message();
            if (choke)
              m.type = Message.CHOKE;
            else
              m.type = Message.UNCHOKE;
            addMessage(m);
          }
      }
  }

  void sendInterest(boolean interest)
  {
    synchronized(sendQueue)
      {
        int inverseType  = interest ? Message.UNINTERESTED
                                    : Message.INTERESTED;
        if (!removeMessage(inverseType))
          {
            Message m = new Message();
            if (interest)
              m.type = Message.INTERESTED;
            else
              m.type = Message.UNINTERESTED;
            addMessage(m);
          }
      }
  }

  void sendHave(int piece)
  {
    Message m = new Message();
    m.type = Message.HAVE;
    m.piece = piece;
    addMessage(m);
  }

  void sendBitfield(BitField bitfield)
  {
    Message m = new Message();
    m.type = Message.BITFIELD;
    m.data = bitfield.getFieldBytes();
    m.off = 0;
    m.len = m.data.length;
    addMessage(m);
  }

  /** reransmit requests not received in 7m */
  private static final int REQ_TIMEOUT = (2 * SEND_TIMEOUT) + (60 * 1000);
  void retransmitRequests(List requests)
  {
    long now = System.currentTimeMillis();
    Iterator it = requests.iterator();
    while (it.hasNext())
      {
        Request req = (Request)it.next();
        if(now > req.sendTime + REQ_TIMEOUT) {
          if (_log.shouldLog(Log.DEBUG))
              _log.debug("Retransmit request " + req + " to peer " + peer);
          sendRequest(req);
        }
      }
  }

  void sendRequests(List requests)
  {
    Iterator it = requests.iterator();
    while (it.hasNext())
      {
        Request req = (Request)it.next();
        sendRequest(req);
      }
  }

  void sendRequest(Request req)
  {
    // Check for duplicate requests to deal with fibrillating i2p-bt
    // (multiple choke/unchokes received cause duplicate requests in the queue)
    synchronized(sendQueue)
      {
        Iterator it = sendQueue.iterator();
        while (it.hasNext())
          {
            Message m = (Message)it.next();
            if (m.type == Message.REQUEST && m.piece == req.piece &&
                m.begin == req.off && m.length == req.len)
              {
                if (_log.shouldLog(Log.DEBUG))
                  _log.debug("Discarding duplicate request " + req + " to peer " + peer);
                return;
              }
          }
      }
    Message m = new Message();
    m.type = Message.REQUEST;
    m.piece = req.piece;
    m.begin = req.off;
    m.length = req.len;
    addMessage(m);
    req.sendTime = System.currentTimeMillis();
  }

  void sendPiece(int piece, int begin, int length, byte[] bytes)
  {
    Message m = new Message();
    m.type = Message.PIECE;
    m.piece = piece;
    m.begin = begin;
    m.length = length;
    m.data = bytes;
    m.off = 0;
    m.len = length;
    addMessage(m);
  }

  void sendCancel(Request req)
  {
    // See if it is still in our send queue
    synchronized(sendQueue)
      {
        Iterator it = sendQueue.iterator();
        while (it.hasNext())
          {
            Message m = (Message)it.next();
            if (m.type == Message.REQUEST
                && m.piece == req.piece
                && m.begin == req.off
                && m.length == req.len)
              it.remove();
          }
      }

    // Always send, just to be sure it it is really canceled.
    Message m = new Message();
    m.type = Message.CANCEL;
    m.piece = req.piece;
    m.begin = req.off;
    m.length = req.len;
    addMessage(m);
  }

  // Called by the PeerState when the other side doesn't want this
  // request to be handled anymore. Removes any pending Piece Message
  // from out send queue.
  void cancelRequest(int piece, int begin, int length)
  {
    synchronized (sendQueue)
      {
        Iterator it = sendQueue.iterator();
        while (it.hasNext())
          {
            Message m = (Message)it.next();
            if (m.type == Message.PIECE
                && m.piece == piece
                && m.begin == begin
                && m.length == length)
              it.remove();
          }
      }
  }
}
