/* TrackerInfo - Holds information returned by a tracker, mainly the peer list.
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
import java.io.InputStream;
import java.util.HashSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.klomp.snark.bencode.BDecoder;
import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.bencode.InvalidBEncodingException;

public class TrackerInfo
{
  private final String failure_reason;
  private final int interval;
  private final Set peers;
  private int complete;
  private int incomplete;

  public TrackerInfo(InputStream in, byte[] my_id, MetaInfo metainfo)
    throws IOException
  {
    this(new BDecoder(in), my_id, metainfo);
  }

  public TrackerInfo(BDecoder be, byte[] my_id, MetaInfo metainfo)
    throws IOException
  {
    this(be.bdecodeMap().getMap(), my_id, metainfo);
  }

  public TrackerInfo(Map m, byte[] my_id, MetaInfo metainfo)
    throws IOException
  {
    BEValue reason = (BEValue)m.get("failure reason");
    if (reason != null)
      {
        failure_reason = reason.getString();
        interval = -1;
        peers = null;
      }
    else
      {
        failure_reason = null;
        BEValue beInterval = (BEValue)m.get("interval");
        if (beInterval == null)
          throw new InvalidBEncodingException("No interval given");
        else
          interval = beInterval.getInt();

        BEValue bePeers = (BEValue)m.get("peers");
        if (bePeers == null)
          peers = Collections.EMPTY_SET;
        else
          peers = getPeers(bePeers.getList(), my_id, metainfo);

        BEValue bev = (BEValue)m.get("complete");
        if (bev != null) try {
          complete = bev.getInt();
          if (complete < 0)
              complete = 0;
        } catch (InvalidBEncodingException ibe) {}

        bev = (BEValue)m.get("incomplete");
        if (bev != null) try {
          incomplete = bev.getInt();
          if (incomplete < 0)
              incomplete = 0;
        } catch (InvalidBEncodingException ibe) {}
      }
  }

  public static Set getPeers(InputStream in, byte[] my_id, MetaInfo metainfo)
    throws IOException
  {
    return getPeers(new BDecoder(in), my_id, metainfo);
  }

  public static Set getPeers(BDecoder be, byte[] my_id, MetaInfo metainfo)
    throws IOException
  {
    return getPeers(be.bdecodeList().getList(), my_id, metainfo);
  }

  public static Set getPeers(List l, byte[] my_id, MetaInfo metainfo)
    throws IOException
  {
    Set peers = new HashSet(l.size());

    Iterator it = l.iterator();
    while (it.hasNext())
      {
        PeerID peerID;
        try {
            peerID = new PeerID(((BEValue)it.next()).getMap());
        } catch (InvalidBEncodingException ibe) {
            // don't let one bad entry spoil the whole list
            //Snark.debug("Discarding peer from list: " + ibe, Snark.ERROR);
            continue;
        }
        peers.add(new Peer(peerID, my_id, metainfo));
      }

    return peers;
  }

  public Set getPeers()
  {
    return peers;
  }

  public int getPeerCount()
  {
    int pc = peers == null ? 0 : peers.size();
    return Math.max(pc, complete + incomplete - 1);
  }

  public String getFailureReason()
  {
    return failure_reason;
  }

  public int getInterval()
  {
    return interval;
  }

    @Override
  public String toString()
  {
    if (failure_reason != null)
      return "TrackerInfo[FAILED: " + failure_reason + "]";
    else
      return "TrackerInfo[interval=" + interval
        + (complete > 0 ? (", complete=" + complete) : "" )
        + (incomplete > 0 ? (", incomplete=" + incomplete) : "" )
        + ", peers=" + peers + "]";
  }
}
