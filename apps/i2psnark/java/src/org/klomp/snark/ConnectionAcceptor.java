/* ConnectionAcceptor - Accepts connections and routes them to sub-acceptors.
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

import net.i2p.I2PException;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Accepts connections on a TCP port and routes them to sub-acceptors.
 */
public class ConnectionAcceptor implements Runnable
{
  private static final ConnectionAcceptor _instance = new ConnectionAcceptor();
  public static final ConnectionAcceptor instance() { return _instance; }
  private Log _log = new Log(ConnectionAcceptor.class);
  private I2PServerSocket serverSocket;
  private PeerAcceptor peeracceptor;
  private Thread thread;

  private boolean stop;
  private boolean socketChanged;

  private ConnectionAcceptor() {}
  
  public synchronized void startAccepting(PeerCoordinatorSet set, I2PServerSocket socket) {
    if (serverSocket != socket) {
      if ( (peeracceptor == null) || (peeracceptor.coordinators != set) )
        peeracceptor = new PeerAcceptor(set);
      serverSocket = socket;
      stop = false;
      socketChanged = true;
      if (thread == null) {
          thread = new I2PThread(this, "I2PSnark acceptor");
          thread.setDaemon(true);
          thread.start();
      }
    }
  }
  
  public ConnectionAcceptor(I2PServerSocket serverSocket,
                            PeerAcceptor peeracceptor)
  {
    this.serverSocket = serverSocket;
    this.peeracceptor = peeracceptor;
    
    socketChanged = false;
    stop = false;
    thread = new I2PThread(this, "I2PSnark acceptor");
    thread.setDaemon(true);
    thread.start();
  }

  public void halt()
  {
    if (true) throw new RuntimeException("wtf");
    stop = true;

    I2PServerSocket ss = serverSocket;
    if (ss != null)
      try
        {
          ss.close();
        }
      catch(I2PException ioe) { }

    Thread t = thread;
    if (t != null)
      t.interrupt();
  }
  
  public void restart() {
      serverSocket = I2PSnarkUtil.instance().getServerSocket();
      socketChanged = true;
      Thread t = thread;
      if (t != null)
          t.interrupt();
  }

  public int getPort()
  {
    return 6881; // serverSocket.getLocalPort();
  }

  public void run()
  {
    while(!stop)
      {
        if (socketChanged) {
            // ok, already updated
            socketChanged = false;
        }
        if (serverSocket == null) {
            Snark.debug("Server socket went away.. boo hiss", Snark.ERROR);
            stop = true;
            return;
        }
        try
          {
            I2PSocket socket = serverSocket.accept();
            if (socket == null) {
                if (socketChanged) {
                    continue;
                } else {
                    I2PServerSocket ss = I2PSnarkUtil.instance().getServerSocket();
                    if (ss != serverSocket) {
                        serverSocket = ss;
                    } else {
                        Snark.debug("Null socket accepted, but socket wasn't changed?", Snark.ERROR);
                        try { Thread.sleep(10*1000); } catch (InterruptedException ie) {}
                    }
                }
            } else {
                Thread t = new I2PThread(new Handler(socket), "Connection-" + socket);
                t.start();
            }
          }
        catch (I2PException ioe)
          {
            if (!socketChanged) {
                Snark.debug("Error while accepting: " + ioe, Snark.ERROR);
                stop = true;
            }
          }
        catch (IOException ioe)
          {
            Snark.debug("Error while accepting: " + ioe, Snark.ERROR);
            stop = true;
          }
      }

    try
      {
        serverSocket.close();
      }
    catch (I2PException ignored) { }
    
    throw new RuntimeException("wtf");
  }
  
  private class Handler implements Runnable {
      private I2PSocket _socket;
      public Handler(I2PSocket socket) {
          _socket = socket;
      }
      public void run() {
          try {
              InputStream in = _socket.getInputStream();
              OutputStream out = _socket.getOutputStream();

              if (true) {
                  in = new BufferedInputStream(in);
                  //out = new BufferedOutputStream(out);
              }
              if (_log.shouldLog(Log.DEBUG))
                  _log.debug("Handling socket from " + _socket.getPeerDestination().calculateHash().toBase64());
              peeracceptor.connection(_socket, in, out);
          } catch (IOException ioe) {
              if (_log.shouldLog(Log.DEBUG))
                  _log.debug("Error handling connection from " + _socket.getPeerDestination().calculateHash().toBase64(), ioe);
              try { _socket.close(); } catch (IOException ignored) { }
          }
      }
  }
}
