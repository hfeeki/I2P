package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.util.Log;

/**
 * Handles the actual ElGamal+AES encryption and decryption scenarios using the
 * supplied keys and data.
 */
public class ElGamalAESEngine {
    private final static Log _log = new Log(ElGamalAESEngine.class);
    private final static int MIN_ENCRYPTED_SIZE = 80; // smallest possible resulting size
    private I2PAppContext _context;

    private ElGamalAESEngine() { // nop
    }

    public ElGamalAESEngine(I2PAppContext ctx) {
        _context = ctx;
        
        _context.statManager().createFrequencyStat("crypto.elGamalAES.encryptNewSession",
                                                   "how frequently we encrypt to a new ElGamal/AES+SessionTag session?",
                                                   "Encryption", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l});
        _context.statManager().createFrequencyStat("crypto.elGamalAES.encryptExistingSession",
                                                   "how frequently we encrypt to an existing ElGamal/AES+SessionTag session?",
                                                   "Encryption", new long[] { 60 * 1000l, 60 * 60 * 1000l, 24 * 60 * 60 * 1000l});
        _context.statManager().createFrequencyStat("crypto.elGamalAES.decryptNewSession",
                                                   "how frequently we decrypt with a new ElGamal/AES+SessionTag session?",
                                                   "Encryption", new long[] { 60 * 1000l, 60 * 60 * 1000l, 24 * 60 * 60 * 1000l});
        _context.statManager().createFrequencyStat("crypto.elGamalAES.decryptExistingSession",
                                                   "how frequently we decrypt with an existing ElGamal/AES+SessionTag session?",
                                                   "Encryption", new long[] { 60 * 1000l, 60 * 60 * 1000l, 24 * 60 * 60 * 1000l});
        _context.statManager().createFrequencyStat("crypto.elGamalAES.decryptFail",
                                                   "how frequently we fail to decrypt with ElGamal/AES+SessionTag?", "Encryption",
                                                   new long[] { 60 * 60 * 1000l, 24 * 60 * 60 * 1000l});
    }

    /**
     * Decrypt the message using the given private key.  This works according to the
     * ElGamal+AES algorithm in the data structure spec.
     *
     */
    public byte[] decrypt(byte data[], PrivateKey targetPrivateKey) throws DataFormatException {
        if (data == null) {
            if (_log.shouldLog(Log.ERROR)) _log.error("Null data being decrypted?");
            return null;
        } else if (data.length < MIN_ENCRYPTED_SIZE) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Data is less than the minimum size (" + data.length + " < " + MIN_ENCRYPTED_SIZE + ")");
            return null;
        }

        byte tag[] = new byte[32];
        System.arraycopy(data, 0, tag, 0, tag.length);
        SessionTag st = new SessionTag(tag);
        SessionKey key = _context.sessionKeyManager().consumeTag(st);
        SessionKey foundKey = new SessionKey();
        foundKey.setData(null);
        SessionKey usedKey = new SessionKey();
        Set foundTags = new HashSet();
        byte decrypted[] = null;
        if (key != null) {
            if (_log.shouldLog(Log.DEBUG)) _log.debug("Key is known for tag " + st);
            usedKey.setData(key.getData());
            decrypted = decryptExistingSession(data, key, targetPrivateKey, foundTags, usedKey, foundKey);
            if (decrypted != null)
                _context.statManager().updateFrequency("crypto.elGamalAES.decryptExistingSession");
            else
                _context.statManager().updateFrequency("crypto.elGamalAES.decryptFailed");
        } else {
            if (_log.shouldLog(Log.DEBUG)) _log.debug("Key is NOT known for tag " + st);
            decrypted = decryptNewSession(data, targetPrivateKey, foundTags, usedKey, foundKey);
            if (decrypted != null)
                _context.statManager().updateFrequency("crypto.elGamalAES.decryptNewSession");
            else
                _context.statManager().updateFrequency("crypto.elGamalAES.decryptFailed");
        }

        if ((key == null) && (decrypted == null)) {
            //_log.debug("Unable to decrypt the data starting with tag [" + st + "] - did the tag expire recently?", new Exception("Decrypt failure"));
        }

        if (foundTags.size() > 0) {
            if (foundKey.getData() != null) {
                if (_log.shouldLog(Log.DEBUG)) _log.debug("Found key: " + foundKey);
                _context.sessionKeyManager().tagsReceived(foundKey, foundTags);
            } else {
                if (_log.shouldLog(Log.DEBUG)) _log.debug("Used key: " + usedKey);
                _context.sessionKeyManager().tagsReceived(usedKey, foundTags);
            }
        }
        return decrypted;
    }

    /**
     * scenario 1: 
     * Begin with 222 bytes, ElG encrypted, containing:
     *  - 32 byte SessionKey
     *  - 32 byte pre-IV for the AES
     *  - 158 bytes of random padding
     * Then encrypt with AES using that session key and the first 16 bytes of the SHA256 of the pre-IV, using
     * the decryptAESBlock method & structure.
     *
     * @param foundTags set which is filled with any sessionTags found during decryption
     * @param foundKey  session key which may be filled with a new sessionKey found during decryption
     *
     * @return null if decryption fails
     */
    byte[] decryptNewSession(byte data[], PrivateKey targetPrivateKey, Set foundTags, SessionKey usedKey,
                                    SessionKey foundKey) throws DataFormatException {
        if (data == null) {
            if (_log.shouldLog(Log.WARN)) _log.warn("Data is null, unable to decrypt new session");
            return null;
        } else if (data.length < 514) {
            if (_log.shouldLog(Log.WARN)) _log.warn("Data length is too small (" + data.length + ")");
            return null;
        }
        byte elgEncr[] = new byte[514];
        if (data.length > 514) {
            System.arraycopy(data, 0, elgEncr, 0, 514);
        } else {
            System.arraycopy(data, 0, elgEncr, 514 - data.length, data.length);
        }
        byte elgDecr[] = _context.elGamalEngine().decrypt(elgEncr, targetPrivateKey);
        if (elgDecr == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("decrypt returned null", new Exception("decrypt failed"));
            return null;
        }

        byte preIV[] = null;
        
        int offset = 0;
        byte key[] = new byte[SessionKey.KEYSIZE_BYTES];
        System.arraycopy(elgDecr, offset, key, 0, SessionKey.KEYSIZE_BYTES);
        offset += SessionKey.KEYSIZE_BYTES;
        usedKey.setData(key);
        preIV = new byte[32];
        System.arraycopy(elgDecr, offset, preIV, 0, 32);
        offset += 32;

        //_log.debug("Pre IV for decryptNewSession: " + DataHelper.toString(preIV, 32));
        //_log.debug("SessionKey for decryptNewSession: " + DataHelper.toString(key.getData(), 32));
        SHA256EntryCache.CacheEntry cache = _context.sha().cache().acquire(preIV.length);
        Hash ivHash = _context.sha().calculateHash(preIV, cache);
        byte iv[] = new byte[16];
        System.arraycopy(ivHash.getData(), 0, iv, 0, 16);
        _context.sha().cache().release(cache);

        // feed the extra bytes into the PRNG
        _context.random().harvester().feedEntropy("ElG/AES", elgDecr, offset, elgDecr.length - offset); 

        byte aesDecr[] = decryptAESBlock(data, 514, data.length-514, usedKey, iv, null, foundTags, foundKey);

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Decrypt with a NEW session successfull: # tags read = " + foundTags.size(),
                       new Exception("Decrypted by"));
        return aesDecr;
    }

    /**
     * scenario 2: 
     * The data begins with 32 byte session tag, which also serves as the preIV.
     * Then decrypt with AES using that session key and the first 16 bytes of the SHA256 of the pre-IV:
     *  - 2 byte integer specifying the # of session tags
     *  - that many 32 byte session tags
     *  - 4 byte integer specifying data.length
     *  - SHA256 of data
     *  - 1 byte flag that, if == 1, is followed by a new SessionKey
     *  - data
     *  - random bytes, padding the total size to greater than paddedSize with a mod 16 = 0
     *
     * If anything doesn't match up in decryption, it falls back to decryptNewSession
     *
     * @param foundTags set which is filled with any sessionTags found during decryption
     * @param foundKey  session key which may be filled with a new sessionKey found during decryption
     *
     */
    byte[] decryptExistingSession(byte data[], SessionKey key, PrivateKey targetPrivateKey, Set foundTags,
                                         SessionKey usedKey, SessionKey foundKey) throws DataFormatException {
        byte preIV[] = new byte[32];
        System.arraycopy(data, 0, preIV, 0, preIV.length);
        SHA256EntryCache.CacheEntry cache = _context.sha().cache().acquire(32);
        Hash ivHash = _context.sha().calculateHash(preIV, cache);
        byte iv[] = new byte[16];
        System.arraycopy(ivHash.getData(), 0, iv, 0, 16);
        _context.sha().cache().release(cache);

        usedKey.setData(key.getData());

        //_log.debug("Pre IV for decryptExistingSession: " + DataHelper.toString(preIV, 32));
        //_log.debug("SessionKey for decryptNewSession: " + DataHelper.toString(key.getData(), 32));
        byte decrypted[] = decryptAESBlock(data, 32, data.length-32, key, iv, preIV, foundTags, foundKey);
        if (decrypted == null) {
            // it begins with a valid session tag, but thats just a coincidence.
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Decrypt with a non session tag, but tags read: " + foundTags.size());
            return decryptNewSession(data, targetPrivateKey, foundTags, usedKey, foundKey);
        }
        // existing session decrypted successfully!
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Decrypt with an EXISTING session tag successfull, # tags read: " + foundTags.size(),
                       new Exception("Decrypted by"));
        return decrypted;
    }

    /**
     * Decrypt the AES data with the session key and IV.  The result should be:
     *  - 2 byte integer specifying the # of session tags
     *  - that many 32 byte session tags
     *  - 4 byte integer specifying data.length
     *  - SHA256 of data
     *  - 1 byte flag that, if == 1, is followed by a new SessionKey
     *  - data
     *  - random bytes, padding the total size to greater than paddedSize with a mod 16 = 0
     *
     * If anything doesn't match up in decryption, return null.  Otherwise, return
     * the decrypted data and update the session as necessary.  If the sentTag is not null,
     * consume it, but if it is null, record the keys, etc as part of a new session.
     *
     * @param foundTags set which is filled with any sessionTags found during decryption
     * @param foundKey  session key which may be filled with a new sessionKey found during decryption
     */
    byte[] decryptAESBlock(byte encrypted[], SessionKey key, byte iv[], 
                           byte sentTag[], Set foundTags, SessionKey foundKey) throws DataFormatException {
        return decryptAESBlock(encrypted, 0, encrypted.length, key, iv, sentTag, foundTags, foundKey);
    }
    byte[] decryptAESBlock(byte encrypted[], int offset, int encryptedLen, SessionKey key, byte iv[], 
                           byte sentTag[], Set foundTags, SessionKey foundKey) throws DataFormatException {
        //_log.debug("iv for decryption: " + DataHelper.toString(iv, 16));	
        //_log.debug("decrypting AES block.  encr.length = " + (encrypted == null? -1 : encrypted.length) + " sentTag: " + DataHelper.toString(sentTag, 32));
        byte decrypted[] = new byte[encryptedLen];
        _context.aes().decrypt(encrypted, offset, decrypted, 0, key, iv, encryptedLen);
        //Hash h = _context.sha().calculateHash(decrypted);
        //_log.debug("Hash of entire aes block after decryption: \n" + DataHelper.toString(h.getData(), 32));
        try {
            SessionKey newKey = null;
            Hash readHash = null;
            List tags = new ArrayList();

            //ByteArrayInputStream bais = new ByteArrayInputStream(decrypted);
            int cur = 0;
            long numTags = DataHelper.fromLong(decrypted, cur, 2);
            cur += 2;
            //_log.debug("# tags: " + numTags);
            if ((numTags < 0) || (numTags > 65535)) throw new Exception("Invalid number of session tags");
            if (numTags * SessionTag.BYTE_LENGTH > decrypted.length - 2) {
                throw new Exception("# tags: " + numTags + " is too many for " + (decrypted.length - 2));
            }
            for (int i = 0; i < numTags; i++) {
                byte tag[] = new byte[SessionTag.BYTE_LENGTH];
                System.arraycopy(decrypted, cur, tag, 0, SessionTag.BYTE_LENGTH); 
                cur += SessionTag.BYTE_LENGTH;
                tags.add(new SessionTag(tag));
            }
            long len = DataHelper.fromLong(decrypted, cur, 4);
            cur += 4;
            //_log.debug("len: " + len);
            if ((len < 0) || (len > decrypted.length - cur - Hash.HASH_LENGTH - 1)) 
                throw new Exception("Invalid size of payload (" + len + ", remaining " + (decrypted.length-cur) +")");
            byte hashval[] = new byte[Hash.HASH_LENGTH];
            System.arraycopy(decrypted, cur, hashval, 0, Hash.HASH_LENGTH);
            cur += Hash.HASH_LENGTH;
            readHash = new Hash();
            readHash.setData(hashval);
            byte flag = decrypted[cur++];
            if (flag == 0x01) {
                byte rekeyVal[] = new byte[SessionKey.KEYSIZE_BYTES];
                System.arraycopy(decrypted, cur, rekeyVal, 0, SessionKey.KEYSIZE_BYTES);
                cur += SessionKey.KEYSIZE_BYTES;
                newKey = new SessionKey();
                newKey.setData(rekeyVal);
            }
            byte unencrData[] = new byte[(int) len];
            System.arraycopy(decrypted, cur, unencrData, 0, (int)len);
            cur += len;
            SHA256EntryCache.CacheEntry cache = _context.sha().cache().acquire(unencrData.length);
            Hash calcHash = _context.sha().calculateHash(unencrData, cache);
            boolean eq = calcHash.equals(readHash);
            _context.sha().cache().release(cache);

            if (eq) {
                // everything matches.  w00t.
                foundTags.addAll(tags);
                if (newKey != null) foundKey.setData(newKey.getData());
                return unencrData;
            }

            throw new Exception("Hash does not match");
        } catch (Exception e) {
            if (_log.shouldLog(Log.WARN)) _log.warn("Unable to decrypt AES block", e);
            return null;
        }
    }

    /**
     * Encrypt the unencrypted data to the target.  The total size returned will be 
     * no less than the paddedSize parameter, but may be more.  This method uses the 
     * ElGamal+AES algorithm in the data structure spec.
     *
     * @param target public key to which the data should be encrypted. 
     * @param key session key to use during encryption
     * @param tagsForDelivery session tags to be associated with the key (or newKey if specified), or null
     * @param currentTag sessionTag to use, or null if it should use ElG
     * @param newKey key to be delivered to the target, with which the tagsForDelivery should be associated
     * @param paddedSize minimum size in bytes of the body after padding it (if less than the
     *          body's real size, no bytes are appended but the body is not truncated)
     */
    public byte[] encrypt(byte data[], PublicKey target, SessionKey key, Set tagsForDelivery,
                                 SessionTag currentTag, SessionKey newKey, long paddedSize) {
        if (currentTag == null) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Current tag is null, encrypting as new session", new Exception("encrypt new"));
            _context.statManager().updateFrequency("crypto.elGamalAES.encryptNewSession");
            return encryptNewSession(data, target, key, tagsForDelivery, newKey, paddedSize);
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Current tag is NOT null, encrypting as existing session", new Exception("encrypt existing"));
        _context.statManager().updateFrequency("crypto.elGamalAES.encryptExistingSession");
        return encryptExistingSession(data, target, key, tagsForDelivery, currentTag, newKey, paddedSize);
    }

    /**
     * Encrypt the data to the target using the given key and deliver the specified tags
     */
    public byte[] encrypt(byte data[], PublicKey target, SessionKey key, Set tagsForDelivery,
                                 SessionTag currentTag, long paddedSize) {
        return encrypt(data, target, key, tagsForDelivery, currentTag, null, paddedSize);
    }

    /**
     * Encrypt the data to the target using the given key and deliver the specified tags
     */
    public byte[] encrypt(byte data[], PublicKey target, SessionKey key, Set tagsForDelivery, long paddedSize) {
        return encrypt(data, target, key, tagsForDelivery, null, null, paddedSize);
    }

    /**
     * Encrypt the data to the target using the given key delivering no tags
     */
    public byte[] encrypt(byte data[], PublicKey target, SessionKey key, long paddedSize) {
        return encrypt(data, target, key, null, null, null, paddedSize);
    }

    /**
     * scenario 1: 
     * Begin with 222 bytes, ElG encrypted, containing:
     *  - 32 byte SessionKey
     *  - 32 byte pre-IV for the AES
     *  - 158 bytes of random padding
     * Then encrypt with AES using that session key and the first 16 bytes of the SHA256 of the pre-IV:
     *  - 2 byte integer specifying the # of session tags
     *  - that many 32 byte session tags
     *  - 4 byte integer specifying data.length
     *  - SHA256 of data
     *  - 1 byte flag that, if == 1, is followed by a new SessionKey
     *  - data
     *  - random bytes, padding the total size to greater than paddedSize with a mod 16 = 0
     *
     */
    byte[] encryptNewSession(byte data[], PublicKey target, SessionKey key, Set tagsForDelivery,
                                    SessionKey newKey, long paddedSize) {
        //_log.debug("Encrypting to a NEW session");
        byte elgSrcData[] = new byte[SessionKey.KEYSIZE_BYTES+32+158];
        System.arraycopy(key.getData(), 0, elgSrcData, 0, SessionKey.KEYSIZE_BYTES);
        byte preIV[] = new byte[32];
        _context.random().nextBytes(preIV);
        System.arraycopy(preIV, 0, elgSrcData, SessionKey.KEYSIZE_BYTES, 32);
        byte rnd[] = new byte[158];
        _context.random().nextBytes(rnd);
        System.arraycopy(rnd, 0, elgSrcData, SessionKey.KEYSIZE_BYTES+32, 158);

        //_log.debug("Pre IV for encryptNewSession: " + DataHelper.toString(preIV, 32));
        //_log.debug("SessionKey for encryptNewSession: " + DataHelper.toString(key.getData(), 32));
        long before = _context.clock().now();
        byte elgEncr[] = _context.elGamalEngine().encrypt(elgSrcData, target);
        long after = _context.clock().now();
        if (_log.shouldLog(Log.INFO))
            _log.info("elgEngine.encrypt of the session key took " + (after - before) + "ms");
        if (elgEncr.length < 514) {
            byte elg[] = new byte[514];
            int diff = elg.length - elgEncr.length;
            if (_log.shouldLog(Log.DEBUG)) _log.debug("Difference in size: " + diff);
            System.arraycopy(elgEncr, 0, elg, diff, elgEncr.length);
            elgEncr = elg;
        }
        //_log.debug("ElGamal encrypted length: " + elgEncr.length + " elGamal source length: " + elgSrc.toByteArray().length);
        
        // should we also feed the encrypted elG block into the harvester?

        SHA256EntryCache.CacheEntry cache = _context.sha().cache().acquire(preIV.length);
        Hash ivHash = _context.sha().calculateHash(preIV, cache);
        byte iv[] = new byte[16];
        System.arraycopy(ivHash.getData(), 0, iv, 0, 16);
        _context.sha().cache().release(cache);
        byte aesEncr[] = encryptAESBlock(data, key, iv, tagsForDelivery, newKey, paddedSize);
        //_log.debug("AES encrypted length: " + aesEncr.length);

        byte rv[] = new byte[elgEncr.length + aesEncr.length];
        System.arraycopy(elgEncr, 0, rv, 0, elgEncr.length);
        System.arraycopy(aesEncr, 0, rv, elgEncr.length, aesEncr.length);
        //_log.debug("Return length: " + rv.length);
        long finish = _context.clock().now();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("after the elgEngine.encrypt took a total of " + (finish - after) + "ms");
        return rv;
    }

    /**
     * scenario 2: 
     * Begin with 32 byte session tag, which also serves as the preIV.
     * Then encrypt with AES using that session key and the first 16 bytes of the SHA256 of the pre-IV:
     *  - 2 byte integer specifying the # of session tags
     *  - that many 32 byte session tags
     *  - 4 byte integer specifying data.length
     *  - SHA256 of data
     *  - 1 byte flag that, if == 1, is followed by a new SessionKey
     *  - data
     *  - random bytes, padding the total size to greater than paddedSize with a mod 16 = 0
     *
     */
    byte[] encryptExistingSession(byte data[], PublicKey target, SessionKey key, Set tagsForDelivery,
                                         SessionTag currentTag, SessionKey newKey, long paddedSize) {
        //_log.debug("Encrypting to an EXISTING session");
        byte rawTag[] = currentTag.getData();

        //_log.debug("Pre IV for encryptExistingSession (aka tag): " + currentTag.toString());
        //_log.debug("SessionKey for encryptNewSession: " + DataHelper.toString(key.getData(), 32));
        SHA256EntryCache.CacheEntry cache = _context.sha().cache().acquire(rawTag.length);
        Hash ivHash = _context.sha().calculateHash(rawTag, cache);
        byte iv[] = new byte[16];
        System.arraycopy(ivHash.getData(), 0, iv, 0, 16);
        _context.sha().cache().release(cache);

        byte aesEncr[] = encryptAESBlock(data, key, iv, tagsForDelivery, newKey, paddedSize, SessionTag.BYTE_LENGTH);
        // that prepended SessionTag.BYTE_LENGTH bytes at the beginning of the buffer
        System.arraycopy(rawTag, 0, aesEncr, 0, rawTag.length);
        return aesEncr;
    }

    private final static Set EMPTY_SET = new HashSet();

    /**
     * For both scenarios, this method encrypts the AES area using the given key, iv
     * and making sure the resulting data is at least as long as the paddedSize and 
     * also mod 16 bytes.  The contents of the encrypted data is:
     *  - 2 byte integer specifying the # of session tags
     *  - that many 32 byte session tags
     *  - 4 byte integer specifying data.length
     *  - SHA256 of data
     *  - 1 byte flag that, if == 1, is followed by a new SessionKey
     *  - data
     *  - random bytes, padding the total size to greater than paddedSize with a mod 16 = 0
     *
     */
    final byte[] encryptAESBlock(byte data[], SessionKey key, byte[] iv, Set tagsForDelivery, SessionKey newKey,
                                        long paddedSize) {
        return encryptAESBlock(data, key, iv, tagsForDelivery, newKey, paddedSize, 0);
    }
    final byte[] encryptAESBlock(byte data[], SessionKey key, byte[] iv, Set tagsForDelivery, SessionKey newKey,
                                        long paddedSize, int prefixBytes) {
        //_log.debug("iv for encryption: " + DataHelper.toString(iv, 16));
        //_log.debug("Encrypting AES");
        if (tagsForDelivery == null) tagsForDelivery = EMPTY_SET;
        int size = 2 // sizeof(tags)
                 + tagsForDelivery.size()
                 + SessionTag.BYTE_LENGTH*tagsForDelivery.size()
                 + 4 // payload length
                 + Hash.HASH_LENGTH
                 + (newKey == null ? 1 : 1 + SessionKey.KEYSIZE_BYTES)
                 + data.length;
        int totalSize = size + getPaddingSize(size, paddedSize);

        byte aesData[] = new byte[totalSize + prefixBytes];

        int cur = prefixBytes;
        DataHelper.toLong(aesData, cur, 2, tagsForDelivery.size());
        cur += 2;
        for (Iterator iter = tagsForDelivery.iterator(); iter.hasNext();) {
            SessionTag tag = (SessionTag) iter.next();
            System.arraycopy(tag.getData(), 0, aesData, cur, SessionTag.BYTE_LENGTH);
            cur += SessionTag.BYTE_LENGTH;
        }
        //_log.debug("# tags created, registered, and written: " + tags.size());
        DataHelper.toLong(aesData, cur, 4, data.length);
        cur += 4;
        //_log.debug("data length: " + data.length);
        SHA256EntryCache.CacheEntry cache = _context.sha().cache().acquire(data.length);
        Hash hash = _context.sha().calculateHash(data, cache);
        System.arraycopy(hash.getData(), 0, aesData, cur, Hash.HASH_LENGTH);
        cur += Hash.HASH_LENGTH;
        _context.sha().cache().release(cache);

        //_log.debug("hash of data: " + DataHelper.toString(hash.getData(), 32));
        if (newKey == null) {
            aesData[cur++] = 0x00; // don't rekey
            //_log.debug("flag written");
        } else {
            aesData[cur++] = 0x01; // rekey
            System.arraycopy(newKey.getData(), 0, aesData, cur, SessionKey.KEYSIZE_BYTES);
            cur += SessionKey.KEYSIZE_BYTES;
        }
        System.arraycopy(data, 0, aesData, cur, data.length);
        cur += data.length;

        //_log.debug("raw data written: " + len);
        byte padding[] = getPadding(_context, size, paddedSize);
        //_log.debug("padding length: " + padding.length);
        System.arraycopy(padding, 0, aesData, cur, padding.length);
        cur += padding.length;

        //Hash h = _context.sha().calculateHash(aesUnencr);
        //_log.debug("Hash of entire aes block before encryption: (len=" + aesUnencr.length + ")\n" + DataHelper.toString(h.getData(), 32));
        _context.aes().encrypt(aesData, prefixBytes, aesData, prefixBytes, key, iv, aesData.length - prefixBytes);
        //_log.debug("Encrypted length: " + aesEncr.length);
        //return aesEncr;
        return aesData;
    }

    /**
     * Return random bytes for padding the data to a mod 16 size so that it is
     * at least minPaddedSize
     *
     */
    final static byte[] getPadding(I2PAppContext context, int curSize, long minPaddedSize) {
        int size = getPaddingSize(curSize, minPaddedSize);
        byte rv[] = new byte[size];
        context.random().nextBytes(rv);
        return rv;
    }
    final static int getPaddingSize(int curSize, long minPaddedSize) {
        int diff = 0;
        if (curSize < minPaddedSize) {
            diff = (int) minPaddedSize - curSize;
        }

        int numPadding = diff;
        if (((curSize + diff) % 16) != 0) numPadding += (16 - ((curSize + diff) % 16));
        return numPadding;
    }

    public static void main(String args[]) {
        I2PAppContext ctx = new I2PAppContext();
        ElGamalAESEngine e = new ElGamalAESEngine(ctx);
        Object kp[] = ctx.keyGenerator().generatePKIKeypair();
        PublicKey pubKey = (PublicKey)kp[0];
        PrivateKey privKey = (PrivateKey)kp[1];
        SessionKey sessionKey = ctx.keyGenerator().generateSessionKey();
        for (int i = 0; i < 10; i++) {
            try {
                Set tags = new HashSet(5);
                if (i == 0) {
                    for (int j = 0; j < 5; j++)
                        tags.add(new SessionTag(true));
                }
                byte encrypted[] = e.encrypt("blah".getBytes(), pubKey, sessionKey, tags, 1024);
                byte decrypted[] = e.decrypt(encrypted, privKey);
                if ("blah".equals(new String(decrypted))) {
                    System.out.println("equal on " + i);
                } else {
                    System.out.println("NOT equal on " + i + ": " + new String(decrypted));
                    break;
                }
                ctx.sessionKeyManager().tagsDelivered(pubKey, sessionKey, tags);
            } catch (Exception ee) {
                ee.printStackTrace();
                break;
            }
        }
    }
}