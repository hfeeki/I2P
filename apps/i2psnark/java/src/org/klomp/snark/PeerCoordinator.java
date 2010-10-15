/* PeerCoordinator - Coordinates which peers do what (up and downloading).
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Timer;

import net.i2p.I2PAppContext;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * Coordinates what peer does what.
 */
public class PeerCoordinator implements PeerListener
{
  private final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(PeerCoordinator.class);
  final MetaInfo metainfo;
  final Storage storage;
  final Snark snark;

  // package local for access by CheckDownLoadersTask
  final static long CHECK_PERIOD = 40*1000; // 40 seconds
  final static int MAX_UPLOADERS = 6;

  // Approximation of the number of current uploaders.
  // Resynced by PeerChecker once in a while.
  int uploaders = 0;
  int interestedAndChoking = 0;

  // final static int MAX_DOWNLOADERS = MAX_CONNECTIONS;
  // int downloaders = 0;

  private long uploaded;
  private long downloaded;
  final static int RATE_DEPTH = 6; // make following arrays RATE_DEPTH long
  private long uploaded_old[] = {-1,-1,-1,-1,-1,-1};
  private long downloaded_old[] = {-1,-1,-1,-1,-1,-1};

  // synchronize on this when changing peers or downloaders
  final List<Peer> peers = new ArrayList();
  /** estimate of the peers, without requiring any synchronization */
  volatile int peerCount;

  /** Timer to handle all periodical tasks. */
  private final Timer timer = new Timer(true);

  private final byte[] id;

  // Some random wanted pieces
  private List<Piece> wantedPieces;

  private boolean halted = false;

  private final CoordinatorListener listener;
  public I2PSnarkUtil _util;
  private static final Random _random = I2PAppContext.getGlobalContext().random();
  
  public String trackerProblems = null;
  public int trackerSeenPeers = 0;

  public PeerCoordinator(I2PSnarkUtil util, byte[] id, MetaInfo metainfo, Storage storage,
                         CoordinatorListener listener, Snark torrent)
  {
    _util = util;
    this.id = id;
    this.metainfo = metainfo;
    this.storage = storage;
    this.listener = listener;
    this.snark = torrent;

    setWantedPieces();

    // Install a timer to check the uploaders.
    // Randomize the first start time so multiple tasks are spread out,
    // this will help the behavior with global limits
    timer.schedule(new PeerCheckerTask(_util, this), (CHECK_PERIOD / 2) + _random.nextInt((int) CHECK_PERIOD), CHECK_PERIOD);
  }
  
  // only called externally from Storage after the double-check fails
  public void setWantedPieces()
  {
    // Make a list of pieces
    wantedPieces = new ArrayList();
    BitField bitfield = storage.getBitField();
    int[] pri = storage.getPiecePriorities();
    for(int i = 0; i < metainfo.getPieces(); i++) {
      if (!bitfield.get(i)) {
        Piece p = new Piece(i);
        if (pri != null)
            p.setPriority(pri[i]);
        wantedPieces.add(p);
      }
    }
    Collections.shuffle(wantedPieces, _random);
  }

  public Storage getStorage() { return storage; }
  public CoordinatorListener getListener() { return listener; }

  // for web page detailed stats
  public List<Peer> peerList()
  {
    synchronized(peers)
      {
        return new ArrayList(peers);
      }
  }

  public byte[] getID()
  {
    return id;
  }

  public boolean completed()
  {
    return storage.complete();
  }

  /** might be wrong */
  public int getPeerCount() { return peerCount; }

  /** should be right */
  public int getPeers()
  {
    synchronized(peers)
      {
        int rv = peers.size();
        peerCount = rv;
        return rv;
      }
  }

  /**
   * Returns how many bytes are still needed to get the complete file.
   */
  public long getLeft()
  {
    // XXX - Only an approximation.
    return ((long) storage.needed()) * metainfo.getPieceLength(0);
  }

  /**
   * Returns the total number of uploaded bytes of all peers.
   */
  public long getUploaded()
  {
    return uploaded;
  }

  /**
   * Returns the total number of downloaded bytes of all peers.
   */
  public long getDownloaded()
  {
    return downloaded;
  }

  /**
   * Push the total uploaded/downloaded onto a RATE_DEPTH deep stack
   */
  public void setRateHistory(long up, long down)
  {
    setRate(up, uploaded_old);
    setRate(down, downloaded_old);
  }

  private static void setRate(long val, long array[])
  {
    synchronized(array) {
      for (int i = RATE_DEPTH-1; i > 0; i--)
        array[i] = array[i-1];
      array[0] = val;
    }
  }

  /**
   * Returns the 4-minute-average rate in Bps
   */
  public long getDownloadRate()
  {
    return getRate(downloaded_old);
  }

  public long getUploadRate()
  {
    return getRate(uploaded_old);
  }

  public long getCurrentUploadRate()
  {
    // no need to synchronize, only one value
    long r = uploaded_old[0];
    if (r <= 0)
        return 0;
    return (r * 1000) / CHECK_PERIOD;
  }

  private long getRate(long array[])
  {
    long rate = 0;
    int i = 0;
    synchronized(array) {
      for ( ; i < RATE_DEPTH; i++) {
        if (array[i] < 0)
            break;
        rate += array[i];
      }
    }
    if (i == 0)
        return 0;
    return rate / (i * CHECK_PERIOD / 1000);
  }

  public MetaInfo getMetaInfo()
  {
    return metainfo;
  }

  public boolean needPeers()
  {
    synchronized(peers)
      {
        return !halted && peers.size() < getMaxConnections();
      }
  }
  
  /**
   *  Reduce max if huge pieces to keep from ooming when leeching
   *  @return 512K: 16; 1M: 11; 2M: 6
   */
  private int getMaxConnections() {
    int size = metainfo.getPieceLength(0);
    int max = _util.getMaxConnections();
    if (size <= 512*1024 || completed())
      return max;
    if (size <= 1024*1024)
      return (max + max + 2) / 3;
    return (max + 2) / 3;
  }

  public boolean halted() { return halted; }

  public void halt()
  {
    halted = true;
    List<Peer> removed = new ArrayList();
    synchronized(peers)
      {
        // Stop peer checker task.
        timer.cancel();

        // Stop peers.
        removed.addAll(peers);
        peers.clear();
        peerCount = 0;
      }

    while (!removed.isEmpty()) {
        Peer peer = removed.remove(0);
        peer.disconnect();
        removePeerFromPieces(peer);
    }
    // delete any saved orphan partial piece
    savedRequest = null;
  }

  public void connected(Peer peer)
  { 
    if (halted)
      {
        peer.disconnect(false);
        return;
      }

    Peer toDisconnect = null;
    synchronized(peers)
      {
        Peer old = peerIDInList(peer.getPeerID(), peers);
        if ( (old != null) && (old.getInactiveTime() > 8*60*1000) ) {
            // idle for 8 minutes, kill the old con (32KB/8min = 68B/sec minimum for one block)
            if (_log.shouldLog(Log.WARN))
              _log.warn("Remomving old peer: " + peer + ": " + old + ", inactive for " + old.getInactiveTime());
            peers.remove(old);
            toDisconnect = old;
            old = null;
        }
        if (old != null)
          {
            if (_log.shouldLog(Log.WARN))
              _log.warn("Already connected to: " + peer + ": " + old + ", inactive for " + old.getInactiveTime());
            // toDisconnect = peer to get out of synchronized(peers)
            peer.disconnect(false); // Don't deregister this connection/peer.
          }
        // This is already checked in addPeer() but we could have gone over the limit since then
        else if (peers.size() >= getMaxConnections())
          {
            if (_log.shouldLog(Log.WARN))
              _log.warn("Already at MAX_CONNECTIONS in connected() with peer: " + peer);
            // toDisconnect = peer to get out of synchronized(peers)
            peer.disconnect(false);
          }
        else
          {
            if (_log.shouldLog(Log.INFO))
              _log.info("New connection to peer: " + peer + " for " + metainfo.getName());

            // Add it to the beginning of the list.
            // And try to optimistically make it a uploader.
            peers.add(0, peer);
            peerCount = peers.size();
            unchokePeer();

            if (listener != null)
              listener.peerChange(this, peer);
          }
      }
    if (toDisconnect != null) {
        toDisconnect.disconnect(false);
        removePeerFromPieces(toDisconnect);
    }
  }

  // caller must synchronize on peers
  private static Peer peerIDInList(PeerID pid, List peers)
  {
    Iterator<Peer> it = peers.iterator();
    while (it.hasNext()) {
      Peer cur = it.next();
      if (pid.sameID(cur.getPeerID()))
        return cur;
    }
    return null;
  }

// returns true if actual attempt to add peer occurs
  public boolean addPeer(final Peer peer)
  {
    if (halted)
      {
        peer.disconnect(false);
        return false;
      }

    boolean need_more;
    int peersize = 0;
    synchronized(peers)
      {
        peersize = peers.size();
        // This isn't a strict limit, as we may have several pending connections;
        // thus there is an additional check in connected()
        need_more = (!peer.isConnected()) && peersize < getMaxConnections();
        // Check if we already have this peer before we build the connection
        Peer old = peerIDInList(peer.getPeerID(), peers);
        need_more = need_more && ((old == null) || (old.getInactiveTime() > 8*60*1000));
      }

    if (need_more)
      {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Adding a peer " + peer.getPeerID().toString() + " for " + metainfo.getName(), new Exception("add/run"));

        // Run the peer with us as listener and the current bitfield.
        final PeerListener listener = this;
        final BitField bitfield = storage.getBitField();
        Runnable r = new Runnable()
          {
            public void run()
            {
              peer.runConnection(_util, listener, bitfield);
            }
          };
        String threadName = peer.toString();
        new I2PAppThread(r, threadName).start();
        return true;
      }
    if (_log.shouldLog(Log.DEBUG)) {
      if (peer.isConnected())
        _log.info("Add peer already connected: " + peer);
      else
        _log.info("Connections: " + peersize + "/" + getMaxConnections()
                  + " not accepting extra peer: " + peer);
    }
    return false;
  }


  // (Optimistically) unchoke. Should be called with peers synchronized
  void unchokePeer()
  {
    // linked list will contain all interested peers that we choke.
    // At the start are the peers that have us unchoked at the end the
    // other peer that are interested, but are choking us.
    List<Peer> interested = new LinkedList();
    synchronized (peers) {
        int count = 0;
        int unchokedCount = 0;
        int maxUploaders = allowedUploaders();
        Iterator<Peer> it = peers.iterator();
        while (it.hasNext())
          {
            Peer peer = it.next();
            if (peer.isChoking() && peer.isInterested())
              {
                count++;
                if (uploaders < maxUploaders)
                  {
                    if (peer.isInteresting() && !peer.isChoked())
                      interested.add(unchokedCount++, peer);
                    else
                      interested.add(peer);
                  }
              }
          }

        while (uploaders < maxUploaders && !interested.isEmpty())
          {
            Peer peer = interested.remove(0);
            if (_log.shouldLog(Log.DEBUG))
              _log.debug("Unchoke: " + peer);
            peer.setChoking(false);
            uploaders++;
            count--;
            // Put peer back at the end of the list.
            peers.remove(peer);
            peers.add(peer);
            peerCount = peers.size();
          }
        interestedAndChoking = count;
    }
  }

  public byte[] getBitMap()
  {
    return storage.getBitField().getFieldBytes();
  }

  /**
   * Returns true if we don't have the given piece yet.
   */
  public boolean gotHave(Peer peer, int piece)
  {
    if (listener != null)
      listener.peerChange(this, peer);

    synchronized(wantedPieces)
      {
        return wantedPieces.contains(new Piece(piece));
      }
  }

  /**
   * Returns true if the given bitfield contains at least one piece we
   * are interested in.
   */
  public boolean gotBitField(Peer peer, BitField bitfield)
  {
    if (listener != null)
      listener.peerChange(this, peer);

    synchronized(wantedPieces)
      {
        Iterator<Piece> it = wantedPieces.iterator();
        while (it.hasNext())
          {
            Piece p = it.next();
            int i = p.getId();
            if (bitfield.get(i)) {
              p.addPeer(peer);
              return true;
            }
          }
      }
    return false;
  }

  /**
   *  This should be somewhat less than the max conns per torrent,
   *  but not too much less, so a torrent doesn't get stuck near the end.
   *  @since 0.7.14
   */
  private static final int END_GAME_THRESHOLD = 8;

  /**
   * Returns one of pieces in the given BitField that is still wanted or
   * -1 if none of the given pieces are wanted.
   */
  public int wantPiece(Peer peer, BitField havePieces)
  {
    if (halted) {
      if (_log.shouldLog(Log.WARN))
          _log.warn("We don't want anything from the peer, as we are halted!  peer=" + peer);
      return -1;
    }

    synchronized(wantedPieces)
      {
        Piece piece = null;
        Collections.sort(wantedPieces); // Sort in order of rarest first.
        List<Piece> requested = new ArrayList(); 
        Iterator<Piece> it = wantedPieces.iterator();
        while (piece == null && it.hasNext())
          {
            Piece p = it.next();
            // sorted by priority, so when we hit a disabled piece we are done
            if (p.isDisabled())
                break;
            if (havePieces.get(p.getId()) && !p.isRequested())
              {
                piece = p;
              }
            else if (p.isRequested()) 
            {
                requested.add(p);
            }
          }
        
        //Only request a piece we've requested before if there's no other choice.
        if (piece == null) {
            // AND if there are almost no wanted pieces left (real end game).
            // If we do end game all the time, we generate lots of extra traffic
            // when the seeder is super-slow and all the peers are "caught up"
            if (wantedPieces.size() > END_GAME_THRESHOLD)
                return -1;  // nothing to request and not in end game
            // let's not all get on the same piece
            Collections.shuffle(requested, _random);
            Iterator<Piece> it2 = requested.iterator();
            while (piece == null && it2.hasNext())
              {
                Piece p = it2.next();
                if (havePieces.get(p.getId()))
                  {
                    piece = p;
                  }
              }
            if (piece == null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("nothing to even rerequest from " + peer + ": requested = " + requested);
                //  _log.warn("nothing to even rerequest from " + peer + ": requested = " + requested 
                //            + " wanted = " + wantedPieces + " peerHas = " + havePieces);
                return -1; //If we still can't find a piece we want, so be it.
            } else {
                // Should be a lot smarter here - limit # of parallel attempts and
                // share blocks rather than starting from 0 with each peer.
                // This is where the flaws of the snark data model are really exposed.
                // Could also randomize within the duplicate set rather than strict rarest-first
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("parallel request (end game?) for " + peer + ": piece = " + piece);
            }
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Now requesting: piece " + piece + " priority " + piece.getPriority());
        piece.setRequested(true);
        return piece.getId();
      }
  }

  /**
   *  Maps file priorities to piece priorities.
   *  Call after updating file priorities Storage.setPriority()
   *  @since 0.8.1
   */
  public void updatePiecePriorities() {
      int[] pri = storage.getPiecePriorities();
      if (pri == null)
          return;
      synchronized(wantedPieces) {
          // Add incomplete and previously unwanted pieces to the list
          // Temp to avoid O(n**2)
          BitField want = new BitField(pri.length);
          for (Piece p : wantedPieces) {
              want.set(p.getId());
          }
          BitField bitfield = storage.getBitField();
          for (int i = 0; i < pri.length; i++) {
              if (pri[i] >= 0 && !bitfield.get(i)) {
                  if (!want.get(i)) {
                      Piece piece = new Piece(i);
                      wantedPieces.add(piece);
                      // As connections are already up, new Pieces will
                      // not have their PeerID list populated, so do that.
                      synchronized(peers) {
                          for (Peer p : peers) {
                              PeerState s = p.state;
                              if (s != null) {
                                  BitField bf = s.bitfield;
                                  if (bf != null && bf.get(i))
                                      piece.addPeer(p);
                              }
                          }
                      }
                  }
              }
          }
          // now set the new priorities and remove newly unwanted pieces
          for (Iterator<Piece> iter = wantedPieces.iterator(); iter.hasNext(); ) {
               Piece p = iter.next();
               int id = pri[p.getId()];
               if (id >= 0)
                   p.setPriority(pri[p.getId()]);
               else
                   iter.remove();
          }
          // if we added pieces, they will be in-order unless we shuffle
          Collections.shuffle(wantedPieces, _random);
      }
  }

  /**
   * Returns a byte array containing the requested piece or null of
   * the piece is unknown.
   */
  public byte[] gotRequest(Peer peer, int piece, int off, int len)
  {
    if (halted)
      return null;

    try
      {
        return storage.getPiece(piece, off, len);
      }
    catch (IOException ioe)
      {
        snark.stopTorrent();
        _log.error("Error reading the storage for " + metainfo.getName(), ioe);
        throw new RuntimeException("B0rked");
      }
  }

  /**
   * Called when a peer has uploaded some bytes of a piece.
   */
  public void uploaded(Peer peer, int size)
  {
    uploaded += size;

    if (listener != null)
      listener.peerChange(this, peer);
  }

  /**
   * Called when a peer has downloaded some bytes of a piece.
   */
  public void downloaded(Peer peer, int size)
  {
    downloaded += size;

    if (listener != null)
      listener.peerChange(this, peer);
  }

  /**
   * Returns false if the piece is no good (according to the hash).
   * In that case the peer that supplied the piece should probably be
   * blacklisted.
   */
  public boolean gotPiece(Peer peer, int piece, byte[] bs)
  {
    if (halted) {
      _log.info("Got while-halted piece " + piece + "/" + metainfo.getPieces() +" from " + peer + " for " + metainfo.getName());
      return true; // We don't actually care anymore.
    }
    
    synchronized(wantedPieces)
      {
        Piece p = new Piece(piece);
        if (!wantedPieces.contains(p))
          {
            _log.info("Got unwanted piece " + piece + "/" + metainfo.getPieces() +" from " + peer + " for " + metainfo.getName());
            
            // No need to announce have piece to peers.
            // Assume we got a good piece, we don't really care anymore.
            // Well, this could be caused by a change in priorities, so
            // only return true if we already have it, otherwise might as well keep it.
            if (storage.getBitField().get(piece))
                return true;
          }
        
        try
          {
            if (storage.putPiece(piece, bs))
              {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Got valid piece " + piece + "/" + metainfo.getPieces() +" from " + peer + " for " + metainfo.getName());
              }
            else
              {
                // Oops. We didn't actually download this then... :(
                downloaded -= metainfo.getPieceLength(piece);
                _log.warn("Got BAD piece " + piece + "/" + metainfo.getPieces() + " from " + peer + " for " + metainfo.getName());
                return false; // No need to announce BAD piece to peers.
              }
          }
        catch (IOException ioe)
          {
            snark.stopTorrent();
            _log.error("Error writing storage for " + metainfo.getName(), ioe);
            throw new RuntimeException("B0rked");
          }
        wantedPieces.remove(p);
      }

    // Announce to the world we have it!
    // Disconnect from other seeders when we get the last piece
    synchronized(peers)
      {
        List<Peer> toDisconnect = new ArrayList(); 
        Iterator<Peer> it = peers.iterator();
        while (it.hasNext())
          {
            Peer p = it.next();
            if (p.isConnected())
              {
                  if (completed() && p.isCompleted())
                      toDisconnect.add(p);
                  else
                      p.have(piece);
              }
          }
        it = toDisconnect.iterator();
        while (it.hasNext())
          {
            Peer p = it.next();
            p.disconnect(true);
          }
      }
    
    return true;
  }

  public void gotChoke(Peer peer, boolean choke)
  {
    if (_log.shouldLog(Log.INFO))
      _log.info("Got choke(" + choke + "): " + peer);

    if (listener != null)
      listener.peerChange(this, peer);
  }

  public void gotInterest(Peer peer, boolean interest)
  {
    if (interest)
      {
        synchronized(peers)
          {
            if (uploaders < allowedUploaders())
              {
                if(peer.isChoking())
                  {
                    uploaders++;
                    peer.setChoking(false);
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Unchoke: " + peer);
                  }
              }
          }
      }

    if (listener != null)
      listener.peerChange(this, peer);
  }

  public void disconnected(Peer peer)
  {
    if (_log.shouldLog(Log.INFO))
        _log.info("Disconnected " + peer, new Exception("Disconnected by"));
    
    synchronized(peers)
      {
        // Make sure it is no longer in our lists
        if (peers.remove(peer))
          {
            // Unchoke some random other peer
            unchokePeer();
            removePeerFromPieces(peer);
          }
        peerCount = peers.size();
      }

    if (listener != null)
      listener.peerChange(this, peer);
  }
  
  /** Called when a peer is removed, to prevent it from being used in 
   * rarest-first calculations.
   */
  public void removePeerFromPieces(Peer peer) {
      synchronized(wantedPieces) {
          for(Iterator<Piece> iter = wantedPieces.iterator(); iter.hasNext(); ) {
              Piece piece = iter.next();
              piece.removePeer(peer);
          }
      } 
  }


  /** Simple method to save a partial piece on peer disconnection
   *  and hopefully restart it later.
   *  Only one partial piece is saved at a time.
   *  Replace it if a new one is bigger or the old one is too old.
   *  Storage method is private so we can expand to save multiple partials
   *  if we wish.
   */
  private Request savedRequest = null;
  private long savedRequestTime = 0;
  public void savePeerPartial(PeerState state)
  {
    if (halted)
      return;
    Request req = state.getPartialRequest();
    if (req == null)
      return;
    if (savedRequest == null ||
        req.off > savedRequest.off ||
        System.currentTimeMillis() > savedRequestTime + (15 * 60 * 1000)) {
      if (savedRequest == null || (req.piece != savedRequest.piece && req.off != savedRequest.off)) {
        if (_log.shouldLog(Log.DEBUG)) {
          _log.debug(" Saving orphaned partial piece " + req);
          if (savedRequest != null)
            _log.debug(" (Discarding previously saved orphan) " + savedRequest);
        }
      }
      savedRequest = req;
      savedRequestTime = System.currentTimeMillis();
    } else {
      if (req.piece != savedRequest.piece)
        if (_log.shouldLog(Log.DEBUG))
          _log.debug(" Discarding orphaned partial piece " + req);
    }
  }

  /** Return partial piece if it's still wanted and peer has it.
   */
  public Request getPeerPartial(BitField havePieces) {
    if (savedRequest == null)
      return null;
    if (! havePieces.get(savedRequest.piece)) {
      if (_log.shouldLog(Log.DEBUG))
        _log.debug("Peer doesn't have orphaned piece " + savedRequest);
      return null;
    }
    synchronized(wantedPieces)
      {
        for(Iterator<Piece> iter = wantedPieces.iterator(); iter.hasNext(); ) {
          Piece piece = iter.next();
          if (piece.getId() == savedRequest.piece) {
            Request req = savedRequest;
            piece.setRequested(true);
            if (_log.shouldLog(Log.DEBUG))
              _log.debug("Restoring orphaned partial piece " + req);
            savedRequest = null;
            return req;
          }
        }
      }
    if (_log.shouldLog(Log.DEBUG))
      _log.debug("We no longer want orphaned piece " + savedRequest);
    savedRequest = null;
    return null;
  }

  /** Clear the requested flag for a piece if the peer
   ** is the only one requesting it
   */
  private void markUnrequestedIfOnlyOne(Peer peer, int piece)
  {
    // see if anybody else is requesting
    synchronized (peers)
      {
        Iterator<Peer> it = peers.iterator();
        while (it.hasNext()) {
          Peer p = it.next();
          if (p.equals(peer))
            continue;
          if (p.state == null)
            continue;
          int[] arr = p.state.getRequestedPieces();
          for (int i = 0; arr[i] >= 0; i++)
            if(arr[i] == piece) {
              if (_log.shouldLog(Log.DEBUG))
                _log.debug("Another peer is requesting piece " + piece);
              return;
            }
        }
      }

    // nobody is, so mark unrequested
    synchronized(wantedPieces)
      {
        Iterator<Piece> it = wantedPieces.iterator();
        while (it.hasNext()) {
          Piece p = it.next();
          if (p.getId() == piece) {
            p.setRequested(false);
            if (_log.shouldLog(Log.DEBUG))
              _log.debug("Removing from request list piece " + piece);
            return;
          }
        }
      }
  }

  /** Mark a peer's requested pieces unrequested when it is disconnected
   ** Once for each piece
   ** This is enough trouble, maybe would be easier just to regenerate
   ** the requested list from scratch instead.
   */
  public void markUnrequested(Peer peer)
  {
    if (halted || peer.state == null)
      return;
    int[] arr = peer.state.getRequestedPieces();
    for (int i = 0; arr[i] >= 0; i++)
      markUnrequestedIfOnlyOne(peer, arr[i]);
  }

  /** Return number of allowed uploaders for this torrent.
   ** Check with Snark to see if we are over the total upload limit.
   */
  public int allowedUploaders()
  {
    if (listener != null && listener.overUploadLimit(uploaders)) {
        // if (_log.shouldLog(Log.DEBUG))
        //   _log.debug("Over limit, uploaders was: " + uploaders);
        return uploaders - 1;
    } else if (uploaders < MAX_UPLOADERS)
        return uploaders + 1;
    else
        return MAX_UPLOADERS;
  }

  public boolean overUpBWLimit()
  {
    if (listener != null)
        return listener.overUpBWLimit();
    return false;
  }

  public boolean overUpBWLimit(long total)
  {
    if (listener != null)
        return listener.overUpBWLimit(total * 1000 / CHECK_PERIOD);
    return false;
  }
}

