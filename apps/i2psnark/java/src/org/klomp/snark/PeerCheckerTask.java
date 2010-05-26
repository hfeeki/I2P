/* PeerCheckTasks - TimerTask that checks for good/bad up/downloaders.
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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.TimerTask;

import net.i2p.I2PAppContext;

/**
 * TimerTask that checks for good/bad up/downloader. Works together
 * with the PeerCoordinator to select which Peers get (un)choked.
 */
class PeerCheckerTask extends TimerTask
{
  private static final long KILOPERSECOND = 1024*(PeerCoordinator.CHECK_PERIOD/1000);

  private final PeerCoordinator coordinator;
  public I2PSnarkUtil _util;

  PeerCheckerTask(I2PSnarkUtil util, PeerCoordinator coordinator)
  {
    _util = util;
    this.coordinator = coordinator;
  }

  private static final Random random = I2PAppContext.getGlobalContext().random();

  public void run()
  {
    synchronized(coordinator.peers)
      {
        Iterator it = coordinator.peers.iterator();
        if ((!it.hasNext()) || coordinator.halted()) {
          coordinator.peerCount = 0;
          coordinator.interestedAndChoking = 0;
          coordinator.setRateHistory(0, 0);
          coordinator.uploaders = 0;
          if (coordinator.halted())
            cancel();
          return;
        }

        // Calculate total uploading and worst downloader.
        long worstdownload = Long.MAX_VALUE;
        Peer worstDownloader = null;

        int peers = 0;
        int uploaders = 0;
        int downloaders = 0;
        int removedCount = 0;

        long uploaded = 0;
        long downloaded = 0;

        // Keep track of peers we remove now,
        // we will add them back to the end of the list.
        List removed = new ArrayList();
        int uploadLimit = coordinator.allowedUploaders();
        boolean overBWLimit = coordinator.overUpBWLimit();
        while (it.hasNext())
          {
            Peer peer = (Peer)it.next();

            // Remove dying peers
            if (!peer.isConnected())
              {
                it.remove();
                coordinator.removePeerFromPieces(peer);
                coordinator.peerCount = coordinator.peers.size();
                continue;
              }

            peers++;

            if (!peer.isChoking())
              uploaders++;
            if (!peer.isChoked() && peer.isInteresting())
              downloaders++;

            long upload = peer.getUploaded();
            uploaded += upload;
            long download = peer.getDownloaded();
            downloaded += download;
	    peer.setRateHistory(upload, download);
            peer.resetCounters();

            _util.debug(peer + ":", Snark.DEBUG);
            _util.debug(" ul: " + upload*1024/KILOPERSECOND
                        + " dl: " + download*1024/KILOPERSECOND
                        + " i: " + peer.isInterested()
                        + " I: " + peer.isInteresting()
                        + " c: " + peer.isChoking()
                        + " C: " + peer.isChoked(),
                        Snark.DEBUG);

            // Choke a percentage of them rather than all so it isn't so drastic...
            // unless this torrent is over the limit all by itself.
            boolean overBWLimitChoke = upload > 0 &&
                                       ((overBWLimit && random.nextInt(5) < 2) ||
                                        (coordinator.overUpBWLimit(uploaded)));

            // If we are at our max uploaders and we have lots of other
            // interested peers try to make some room.
            // (Note use of coordinator.uploaders)
            if (((coordinator.uploaders == uploadLimit
                && coordinator.interestedAndChoking > 0)
                || coordinator.uploaders > uploadLimit
                || overBWLimitChoke)
                && !peer.isChoking())
              {
                // Check if it still wants pieces from us.
                if (!peer.isInterested())
                  {
                    _util.debug("Choke uninterested peer: " + peer,
                                Snark.INFO);
                    peer.setChoking(true);
                    uploaders--;
                    coordinator.uploaders--;
                    
                    // Put it at the back of the list
                    it.remove();
                    removed.add(peer);
                  }
                else if (overBWLimitChoke)
                  {
                    _util.debug("BW limit (" + upload + "/" + uploaded + "), choke peer: " + peer,
                                Snark.INFO);
                    peer.setChoking(true);
                    uploaders--;
                    coordinator.uploaders--;
                    removedCount++;

                    // Put it at the back of the list for fairness, even though we won't be unchoking this time
                    it.remove();
                    removed.add(peer);
                  }
                else if (peer.isInteresting() && peer.isChoked())
                  {
                    // If they are choking us make someone else a downloader
                    _util.debug("Choke choking peer: " + peer, Snark.DEBUG);
                    peer.setChoking(true);
                    uploaders--;
                    coordinator.uploaders--;
                    removedCount++;
                    
                    // Put it at the back of the list
                    it.remove();
                    removed.add(peer);
                  }
                else if (!peer.isInteresting() && !coordinator.completed())
                  {
                    // If they aren't interesting make someone else a downloader
                    _util.debug("Choke uninteresting peer: " + peer, Snark.DEBUG);
                    peer.setChoking(true);
                    uploaders--;
                    coordinator.uploaders--;
                    removedCount++;
                    
                    // Put it at the back of the list
                    it.remove();
                    removed.add(peer);
                  }
                else if (peer.isInteresting()
                         && !peer.isChoked()
                         && download == 0)
                  {
                    // We are downloading but didn't receive anything...
                    _util.debug("Choke downloader that doesn't deliver:"
                                + peer, Snark.DEBUG);
                    peer.setChoking(true);
                    uploaders--;
                    coordinator.uploaders--;
                    removedCount++;
                    
                    // Put it at the back of the list
                    it.remove();
                    removed.add(peer);
                  }
                else if (peer.isInteresting() && !peer.isChoked() &&
                         download < worstdownload)
                  {
                    // Make sure download is good if we are uploading
                    worstdownload = download;
                    worstDownloader = peer;
                  }
                else if (upload < worstdownload && coordinator.completed())
                  {
                    // Make sure upload is good if we are seeding
                    worstdownload = upload;
                    worstDownloader = peer;
                  }
              }
            peer.retransmitRequests();
            peer.keepAlive();
          }

        // Resync actual uploaders value
        // (can shift a bit by disconnecting peers)
        coordinator.uploaders = uploaders;

        // Remove the worst downloader if needed. (uploader if seeding)
        if (((uploaders == uploadLimit
            && coordinator.interestedAndChoking > 0)
            || uploaders > uploadLimit)
            && worstDownloader != null)
          {
            _util.debug("Choke worst downloader: " + worstDownloader,
                        Snark.DEBUG);

            worstDownloader.setChoking(true);
            coordinator.uploaders--;
            removedCount++;

            // Put it at the back of the list
            coordinator.peers.remove(worstDownloader);
            coordinator.peerCount = coordinator.peers.size();
            removed.add(worstDownloader);
          }
        
        // Optimistically unchoke a peer
        if ((!overBWLimit) && !coordinator.overUpBWLimit(uploaded))
            coordinator.unchokePeer();

        // Put peers back at the end of the list that we removed earlier.
        coordinator.peers.addAll(removed);
        coordinator.peerCount = coordinator.peers.size();
        coordinator.interestedAndChoking += removedCount;

	// store the rates
	coordinator.setRateHistory(uploaded, downloaded);

        // close out unused files, but we don't need to do it every time
        if (random.nextInt(4) == 0)
            coordinator.getStorage().cleanRAFs();

      }
  }
}
