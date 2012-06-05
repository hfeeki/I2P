package org.klomp.snark.dht;
/*
 *  From zzzot, modded and relicensed to GPLv2
 */

import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.SimpleDataStructure;

/*
 *  A Node ID, Hash, and port, and an optional Destination.
 *  This is what DHTNodes remembers. The DHT tracker just stores Hashes.
 *  getData() returns the 54 byte compact info (NID, Hash, port).
 *
 *  Things are a little tricky in KRPC since we exchange Hashes and don't
 *  always have the Destination.
 *  The conpact info is immutable. The Destination may be added later.
 *
 * @since 0.8.4
 * @author zzz
 */

class NodeInfo extends SimpleDataStructure {

    private long lastSeen;
    private NID nID;
    private Hash hash;
    private Destination dest;
    private int port;

    public static final int LENGTH = NID.HASH_LENGTH + Hash.HASH_LENGTH + 2;

    /**
     * Use this if we have the full destination
     * @throws IllegalArgumentException
     */
    public NodeInfo(NID nID, Destination dest, int port) {
        super();
        this.nID = nID;
        this.dest = dest;
        this.hash = dest.calculateHash();
        this.port = port;
        initialize();
    }

    /**
     * No Destination yet available
     * @throws IllegalArgumentException
     */
    public NodeInfo(NID nID, Hash hash, int port) {
        super();
        this.nID = nID;
        this.hash = hash;
        this.port = port;
        initialize();
    }

    /**
     * No Destination yet available
     * @param compactInfo 20 byte node ID, 32 byte destHash, 2 byte port
     * @throws IllegalArgumentException
     */
    public NodeInfo(byte[] compactInfo) {
        super(compactInfo);
        initialize(compactInfo);
    }

    /**
     * No Destination yet available
     * @param compactInfo 20 byte node ID, 32 byte destHash, 2 byte port
     * @param offset starting at this offset in compactInfo
     * @throws IllegalArgumentException
     * @throws AIOOBE
     */
    public NodeInfo(byte[] compactInfo, int offset) {
        super();
        byte[] d = new byte[LENGTH];
        System.arraycopy(compactInfo, offset, d, 0, LENGTH);
        setData(d);
        initialize(d);
    }

    /**
     * Form persistent storage string.
     * Format: NID:Hash:Destination:port
     * First 3 in base 64; Destination may be empty string
     * @throws IllegalArgumentException
     */
    public NodeInfo(String s) throws DataFormatException {
        super();
        String[] parts = s.split(":", 4);
        if (parts.length != 4)
            throw new DataFormatException("Bad format");
        byte[] nid = Base64.decode(parts[0]);
        if (nid == null)
            throw new DataFormatException("Bad NID");
        nID = new NID(nid);
        byte[] h = Base64.decode(parts[1]);
        if (h == null)
            throw new DataFormatException("Bad hash");
        //hash = new Hash(h);
        hash = Hash.create(h);
        if (parts[2].length() > 0)
            dest = new Destination(parts[2]);
        try {
            port = Integer.parseInt(parts[3]);
        } catch (NumberFormatException nfe) {
            throw new DataFormatException("Bad port", nfe);
        }
        initialize();
    }

    /**
     * Creates data structures from the compact info
     * @throws IllegalArgumentException
     */
    private void initialize(byte[] compactInfo) {
        if (compactInfo.length != LENGTH)
            throw new IllegalArgumentException("Bad compact info length");
        byte[] ndata = new byte[NID.HASH_LENGTH];
        System.arraycopy(compactInfo, 0, ndata, 0, NID.HASH_LENGTH);
        this.nID = new NID(ndata);
        //byte[] hdata = new byte[Hash.HASH_LENGTH];
        //System.arraycopy(compactInfo, NID.HASH_LENGTH, hdata, 0, Hash.HASH_LENGTH);
        //this.hash = new Hash(hdata);
        this.hash = Hash.create(compactInfo, NID.HASH_LENGTH);
        this.port = (int) DataHelper.fromLong(compactInfo, NID.HASH_LENGTH + Hash.HASH_LENGTH, 2);
        if (port <= 0 || port >= 65535)
            throw new IllegalArgumentException("Bad port");
    }

    /**
     * Creates 54-byte compact info
     * @throws IllegalArgumentException
     */
    private void initialize() {
        if (port <= 0 || port >= 65535)
            throw new IllegalArgumentException("Bad port");
        byte[] compactInfo = new byte[LENGTH];
        System.arraycopy(nID.getData(), 0, compactInfo, 0, NID.HASH_LENGTH);
        System.arraycopy(hash.getData(), 0, compactInfo, NID.HASH_LENGTH, Hash.HASH_LENGTH);
        DataHelper.toLong(compactInfo, NID.HASH_LENGTH + Hash.HASH_LENGTH, 2, port);
        setData(compactInfo);
    }

    public int length() {
        return LENGTH;
    }

    public NID getNID() {
        return this.nID;
    }

    /** @return may be null if we don't have it */
    public Destination getDestination() {
        return this.dest;
    }

    public Hash getHash() {
        return this.hash;
    }

    @Override
    public Hash calculateHash() {
        return this.hash;
    }

    /**
     * This can come in later but the hash must match.
     * @throws IllegalArgumentException if hash of dest doesn't match previous hash
     */
    public void setDestination(Destination dest) throws IllegalArgumentException {
        if (this.dest != null)
            return;
        if (!dest.calculateHash().equals(this.hash))
            throw new IllegalArgumentException("Hash mismatch, was: " + this.hash + " new: " + dest.calculateHash());
        this.dest = dest;
    }

    public int getPort() {
        return this.port;
    }

    public long lastSeen() {
        return lastSeen;
    }

    public void setLastSeen(long now) {
        lastSeen = now;
    }

    @Override
    public int hashCode() {
        return super.hashCode() ^ nID.hashCode() ^ port;
    }

    @Override
    public boolean equals(Object o) {
        try {
            NodeInfo ni = (NodeInfo) o;
            // assume dest matches, ignore it
            return this.hash.equals(ni.hash) && nID.equals(ni.nID) && port == ni.port;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "NodeInfo: " + nID + ' ' + hash + " port: " + port;
    }

    /**
     * To persistent storage string.
     * Format: NID:Hash:Destination:port
     * First 3 in base 64; Destination may be empty string
     */
    public String toPersistentString() {
        StringBuilder buf = new StringBuilder(650);
        buf.append(nID.toBase64()).append(':');
        buf.append(hash.toBase64()).append(':');
        if (dest != null)
            buf.append(dest.toBase64());
        buf.append(':').append(port);
        return buf.toString();
    }

}
