package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import gnu.crypto.hash.Sha256Standalone;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.TreeMap;
import java.util.zip.Deflater;

import net.i2p.util.ByteCache;
import net.i2p.util.OrderedProperties;
import net.i2p.util.ReusableGZIPInputStream;
import net.i2p.util.ReusableGZIPOutputStream;

/**
 * Defines some simple IO routines for dealing with marshalling data structures
 *
 * @author jrandom
 */
public class DataHelper {
    private final static byte _equalBytes[] = "=".getBytes(); // in UTF-8
    private final static byte _semicolonBytes[] = ";".getBytes(); // in UTF-8

    /** Read a mapping from the stream, as defined by the I2P data structure spec,
     * and store it into a Properties object.
     *
     * A mapping is a set of key / value pairs. It starts with a 2 byte Integer (ala readLong(rawStream, 2))
     * defining how many bytes make up the mapping.  After that comes that many bytes making
     * up a set of UTF-8 encoded characters. The characters are organized as key=value;.
     * The key is a String (ala readString(rawStream)) unique as a key within the current
     * mapping that does not include the UTF-8 characters '=' or ';'.  After the key
     * comes the literal UTF-8 character '='.  After that comes a String (ala readString(rawStream))
     * for the value. Finally after that comes the literal UTF-8 character ';'. This key=value;
     * is repeated until there are no more bytes (not characters!) left as defined by the
     * first two byte integer.
     * @param rawStream stream to read the mapping from
     * @throws DataFormatException if the format is invalid
     * @throws IOException if there is a problem reading the data
     * @return mapping
     */
    public static Properties readProperties(InputStream rawStream) 
        throws DataFormatException, IOException {
        Properties props = new OrderedProperties();
        long size = readLong(rawStream, 2);
        byte data[] = new byte[(int) size];
        int read = read(rawStream, data);
        if (read != size) throw new DataFormatException("Not enough data to read the properties");
        ByteArrayInputStream in = new ByteArrayInputStream(data);
        byte eqBuf[] = new byte[_equalBytes.length];
        byte semiBuf[] = new byte[_semicolonBytes.length];
        while (in.available() > 0) {
            String key = readString(in);
            read = read(in, eqBuf);
            if ((read != eqBuf.length) || (!eq(eqBuf, _equalBytes))) {
                break;
            }
            String val = readString(in);
            read = read(in, semiBuf);
            if ((read != semiBuf.length) || (!eq(semiBuf, _semicolonBytes))) {
                break;
            }
            props.put(key, val);
        }
        return props;
    }

    /**
     * Write a mapping to the stream, as defined by the I2P data structure spec,
     * and store it into a Properties object.  See readProperties for the format.
     *
     * @param rawStream stream to write to
     * @param props properties to write out
     * @throws DataFormatException if there is not enough valid data to write out
     * @throws IOException if there is an IO error writing out the data
     */
    public static void writeProperties(OutputStream rawStream, Properties props) 
            throws DataFormatException, IOException {
        if (props != null) {
            OrderedProperties p = new OrderedProperties();
            p.putAll(props);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(32);
            for (Iterator iter = p.keySet().iterator(); iter.hasNext();) {
                String key = (String) iter.next();
                String val = p.getProperty(key);
                // now make sure they're in UTF-8
                //key = new String(key.getBytes(), "UTF-8");
                //val = new String(val.getBytes(), "UTF-8");
                writeString(baos, key);
                baos.write(_equalBytes);
                writeString(baos, val);
                baos.write(_semicolonBytes);
            }
            baos.close();
            byte propBytes[] = baos.toByteArray();
            writeLong(rawStream, 2, propBytes.length);
            rawStream.write(propBytes);
        } else {
            writeLong(rawStream, 2, 0);
        }
    }
    
    public static int toProperties(byte target[], int offset, Properties props) throws DataFormatException, IOException {
        if (props != null) {
            OrderedProperties p = new OrderedProperties();
            p.putAll(props);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(32);
            for (Iterator iter = p.keySet().iterator(); iter.hasNext();) {
                String key = (String) iter.next();
                String val = p.getProperty(key);
                // now make sure they're in UTF-8
                //key = new String(key.getBytes(), "UTF-8");
                //val = new String(val.getBytes(), "UTF-8");
                writeString(baos, key);
                baos.write(_equalBytes);
                writeString(baos, val);
                baos.write(_semicolonBytes);
            }
            baos.close();
            byte propBytes[] = baos.toByteArray();
            toLong(target, offset, 2, propBytes.length);
            offset += 2;
            System.arraycopy(propBytes, 0, target, offset, propBytes.length);
            offset += propBytes.length;
            return offset;
        } else {
            toLong(target, offset, 2, 0);
            return offset + 2;
        }
    }
    
    public static int fromProperties(byte source[], int offset, Properties target) throws DataFormatException, IOException {
        int size = (int)fromLong(source, offset, 2);
        offset += 2;
        ByteArrayInputStream in = new ByteArrayInputStream(source, offset, size);
        byte eqBuf[] = new byte[_equalBytes.length];
        byte semiBuf[] = new byte[_semicolonBytes.length];
        while (in.available() > 0) {
            String key = readString(in);
            int read = read(in, eqBuf);
            if ((read != eqBuf.length) || (!eq(eqBuf, _equalBytes))) {
                break;
            }
            String val = readString(in);
            read = read(in, semiBuf);
            if ((read != semiBuf.length) || (!eq(semiBuf, _semicolonBytes))) {
                break;
            }
            target.put(key, val);
        }
        return offset + size;
    }

    public static byte[] toProperties(Properties opts) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(2);
            writeProperties(baos, opts);
            return baos.toByteArray();
        } catch (DataFormatException dfe) {
            throw new RuntimeException("Format error writing to memory?! " + dfe.getMessage());
        } catch (IOException ioe) {
            throw new RuntimeException("IO error writing to memory?! " + ioe.getMessage());
        }
    }
    
    /**
     * Pretty print the mapping
     *
     */
    public static String toString(Properties options) {
        StringBuffer buf = new StringBuffer();
        if (options != null) {
            for (Iterator iter = options.keySet().iterator(); iter.hasNext();) {
                String key = (String) iter.next();
                String val = options.getProperty(key);
                buf.append("[").append(key).append("] = [").append(val).append("]");
            }
        } else {
            buf.append("(null properties map)");
        }
        return buf.toString();
    }

    /**
     * A more efficient Properties.load
     *
     */
    public static void loadProps(Properties props, File file) throws IOException {
        loadProps(props, file, false);
    }
    public static void loadProps(Properties props, File file, boolean forceLowerCase) throws IOException {
        loadProps(props, new FileInputStream(file), forceLowerCase);
    }
    public static void loadProps(Properties props, InputStream inStr) throws IOException {
        loadProps(props, inStr, false);
    }
    public static void loadProps(Properties props, InputStream inStr, boolean forceLowerCase) throws IOException {
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(inStr, "UTF-8"), 16*1024);
            String line = null;
            while ( (line = in.readLine()) != null) {
                if (line.trim().length() <= 0) continue;
                if (line.charAt(0) == '#') continue;
                if (line.charAt(0) == ';') continue;
                if (line.indexOf('#') > 0)  // trim off any end of line comment
                    line = line.substring(0, line.indexOf('#')).trim();
                int split = line.indexOf('=');
                if (split <= 0) continue;
                String key = line.substring(0, split);
                String val = line.substring(split+1);
                // Unescape line breaks after loading.
                // Remember: "\" needs escaping both for regex and string.
                val = val.replaceAll("\\\\r","\r");
                val = val.replaceAll("\\\\n","\n");
                if ( (key.length() > 0) && (val.length() > 0) )
                    if (forceLowerCase)
                        props.setProperty(key.toLowerCase(), val);
                    else
                        props.setProperty(key, val);
            }
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
    }
    
    public static void storeProps(Properties props, File file) throws IOException {
        PrintWriter out = null;
        try {
            out = new PrintWriter(new BufferedWriter(new FileWriter(file)));
            for (Iterator iter = props.keySet().iterator(); iter.hasNext(); ) {
                String name = (String)iter.next();
                String val = props.getProperty(name);
                out.println(name + "=" + val);
            }
            out.flush();
            out.close();
        } finally {
            if (out != null) out.close();
        }
    }
    
    /**
     * Pretty print the collection
     *
     */
    public static String toString(Collection col) {
        StringBuffer buf = new StringBuffer();
        if (col != null) {
            for (Iterator iter = col.iterator(); iter.hasNext();) {
                Object o = iter.next();
                buf.append("[").append(o).append("]");
                if (iter.hasNext()) buf.append(", ");
            }
        } else {
            buf.append("null");
        }
        return buf.toString();
    }

    public static String toString(byte buf[]) {
        if (buf == null) return "";

        return toString(buf, buf.length);
    }

    private static final byte[] EMPTY_BUFFER = "".getBytes();
    
    public static String toString(byte buf[], int len) {
        if (buf == null) buf = EMPTY_BUFFER;
        StringBuffer out = new StringBuffer();
        if (len > buf.length) {
            for (int i = 0; i < len - buf.length; i++)
                out.append("00");
        }
        for (int i = 0; i < buf.length && i < len; i++) {
            StringBuffer temp = new StringBuffer(Integer.toHexString(buf[i]));
            while (temp.length() < 2) {
                temp.insert(0, '0');
            }
            temp = new StringBuffer(temp.substring(temp.length() - 2));
            out.append(temp.toString());
        }
        return out.toString();
    }

    public static String toDecimalString(byte buf[], int len) {
        if (buf == null) buf = EMPTY_BUFFER;
        BigInteger val = new BigInteger(1, buf);
        return val.toString(10);
    }

    public final static String toHexString(byte data[]) {
        if ((data == null) || (data.length <= 0)) return "00";
        BigInteger bi = new BigInteger(1, data);
        return bi.toString(16);
    }

    public final static byte[] fromHexString(String val) {
        BigInteger bv = new BigInteger(val, 16);
        return bv.toByteArray();
    }

    /** Read the stream for an integer as defined by the I2P data structure specification.
     * Integers are a fixed number of bytes (numBytes), stored as unsigned integers in network byte order.
     * @param rawStream stream to read from
     * @param numBytes number of bytes to read and format into a number
     * @throws DataFormatException if the stream doesn't contain a validly formatted number of that many bytes
     * @throws IOException if there is an IO error reading the number
     * @return number
     */
    public static long readLong(InputStream rawStream, int numBytes) 
        throws DataFormatException, IOException {
        if (numBytes > 8)
            throw new DataFormatException("readLong doesn't currently support reading numbers > 8 bytes [as thats bigger than java's long]");

        long rv = 0;
        for (int i = 0; i < numBytes; i++) {
            long cur = rawStream.read() & 0xFF;
            if (cur == -1) throw new DataFormatException("Not enough bytes for the field");
            // we loop until we find a nonzero byte (or we reach the end)
            if (cur != 0) {
                // ok, data found, now iterate through it to fill the rv
                long remaining = numBytes - i;
                for (int j = 0; j < remaining; j++) {
                    long shiftAmount = 8 * (remaining-j-1);
                    cur = cur << shiftAmount;
                    rv += cur;
                    if (j + 1 < remaining) {
                        cur = rawStream.read() & 0xFF;
                        if (cur == -1)
                            throw new DataFormatException("Not enough bytes for the field");
                    }
                }
                break;
            }
        }
        
        return rv;
    }
    
    /** Write an integer as defined by the I2P data structure specification to the stream.
     * Integers are a fixed number of bytes (numBytes), stored as unsigned integers in network byte order.
     * @param value value to write out
     * @param rawStream stream to write to
     * @param numBytes number of bytes to write the number into (padding as necessary)
     * @throws DataFormatException if the stream doesn't contain a validly formatted number of that many bytes
     * @throws IOException if there is an IO error writing to the stream
     */
    public static void writeLong(OutputStream rawStream, int numBytes, long value) 
        throws DataFormatException, IOException {
        if (value < 0) throw new DataFormatException("Value is negative (" + value + ")");
        for (int i = numBytes - 1; i >= 0; i--) {
            byte cur = (byte)( (value >>> (i*8) ) & 0xFF);
            rawStream.write(cur);
        }
    }
    
    public static byte[] toLong(int numBytes, long value) throws IllegalArgumentException {
        if (value < 0) throw new IllegalArgumentException("Negative value not allowed");
        byte val[] = new byte[numBytes];
        toLong(val, 0, numBytes, value);
        return val;
    }
    
    public static void toLong(byte target[], int offset, int numBytes, long value) throws IllegalArgumentException {
        if (numBytes <= 0) throw new IllegalArgumentException("Invalid number of bytes");
        if (value < 0) throw new IllegalArgumentException("Negative value not allowed");
        for (int i = 0; i < numBytes; i++)
            target[offset+numBytes-i-1] = (byte)(value >>> (i*8));
    }
    
    public static long fromLong(byte src[], int offset, int numBytes) {
        if ( (src == null) || (src.length == 0) )
            return 0;
        
        long rv = 0;
        for (int i = 0; i < numBytes; i++) {
            long cur = src[offset+i] & 0xFF;
            if (cur < 0) cur = cur+256;
            cur = (cur << (8*(numBytes-i-1)));
            rv += cur;
        }
        if (rv < 0)
            throw new IllegalArgumentException("wtf, fromLong got a negative? " + rv + ": offset="+ offset +" numBytes="+numBytes);
        return rv;
    }
    
    /** Read in a date from the stream as specified by the I2P data structure spec.
     * A date is an 8 byte unsigned integer in network byte order specifying the number of
     * milliseconds since midnight on January 1, 1970 in the GMT timezone. If the number is
     * 0, the date is undefined or null. (yes, this means you can't represent midnight on 1/1/1970)
     * @param in stream to read from
     * @throws DataFormatException if the stream doesn't contain a validly formatted date
     * @throws IOException if there is an IO error reading the date
     * @return date read, or null
     */
    public static Date readDate(InputStream in) throws DataFormatException, IOException {
        long date = readLong(in, DATE_LENGTH);
        if (date == 0L) return null;

        return new Date(date);
    }
    
    /** Write out a date to the stream as specified by the I2P data structure spec.
     * @param out stream to write to
     * @param date date to write (can be null)
     * @throws DataFormatException if the date is not valid
     * @throws IOException if there is an IO error writing the date
     */
    public static void writeDate(OutputStream out, Date date) 
        throws DataFormatException, IOException {
        if (date == null)
            writeLong(out, DATE_LENGTH, 0L);
        else
            writeLong(out, DATE_LENGTH, date.getTime());
    }
    public static byte[] toDate(Date date) throws IllegalArgumentException {
        if (date == null)
            return toLong(DATE_LENGTH, 0L);
        else
            return toLong(DATE_LENGTH, date.getTime());
    }
    public static void toDate(byte target[], int offset, long when) throws IllegalArgumentException {
        toLong(target, offset, DATE_LENGTH, when);
    }
    public static Date fromDate(byte src[], int offset) throws DataFormatException {
        if ( (src == null) || (offset + DATE_LENGTH > src.length) )
            throw new DataFormatException("Not enough data to read a date");
        try {
            long when = fromLong(src, offset, DATE_LENGTH);
            if (when <= 0) 
                return null;
            else
                return new Date(when);
        } catch (IllegalArgumentException iae) {
            throw new DataFormatException(iae.getMessage());
        }
    }
    
    public static final int DATE_LENGTH = 8;

    /** Read in a string from the stream as specified by the I2P data structure spec.
     * A string is 1 or more bytes where the first byte is the number of bytes (not characters!)
     * in the string and the remaining 0-255 bytes are the non-null terminated UTF-8 encoded character array.
     * @param in stream to read from
     * @throws DataFormatException if the stream doesn't contain a validly formatted string
     * @throws IOException if there is an IO error reading the string
     * @return UTF-8 string
     */
    public static String readString(InputStream in) throws DataFormatException, IOException {
        int size = (int) readLong(in, 1);
        byte raw[] = new byte[size];
        int read = read(in, raw);
        if (read != size) throw new DataFormatException("Not enough bytes to read the string");
        return new String(raw);
    }

    /** Write out a string to the stream as specified by the I2P data structure spec.  Note that the max
     * size for a string allowed by the spec is 255 bytes.
     *
     * @param out stream to write string
     * @param string string to write out: null strings are perfectly valid, but strings of excess length will
     *               cause a DataFormatException to be thrown
     * @throws DataFormatException if the string is not valid
     * @throws IOException if there is an IO error writing the string
     */
    public static void writeString(OutputStream out, String string) 
        throws DataFormatException, IOException {
        if (string == null) {
            writeLong(out, 1, 0);
        } else {
            int len = string.length();
            if (len > 255)
                throw new DataFormatException("The I2P data spec limits strings to 255 bytes or less, but this is "
                                              + string.length() + " [" + string + "]");
            writeLong(out, 1, len);
            for (int i = 0; i < len; i++)
                out.write((byte)(string.charAt(i) & 0xFF));
        }
    }

    /** Read in a boolean as specified by the I2P data structure spec.
     * A boolean is 1 byte that is either 0 (false), 1 (true), or 2 (null)
     * @param in stream to read from
     * @throws DataFormatException if the boolean is not valid
     * @throws IOException if there is an IO error reading the boolean
     * @return boolean value, or null
     */
    public static Boolean readBoolean(InputStream in) throws DataFormatException, IOException {
        int val = (int) readLong(in, 1);
        switch (val) {
        case 0:
            return Boolean.FALSE;
        case 1:
            return Boolean.TRUE;
        case 2:
            return null;
        default:
            throw new DataFormatException("Uhhh.. readBoolean read a value that isn't a known ternary val (0,1,2): "
                                          + val);
        }
    }

    /** Write out a boolean as specified by the I2P data structure spec.
     * A boolean is 1 byte that is either 0 (false), 1 (true), or 2 (null)
     * @param out stream to write to
     * @param bool boolean value, or null
     * @throws DataFormatException if the boolean is not valid
     * @throws IOException if there is an IO error writing the boolean
     */
    public static void writeBoolean(OutputStream out, Boolean bool) 
        throws DataFormatException, IOException {
        if (bool == null)
            writeLong(out, 1, BOOLEAN_UNKNOWN);
        else if (Boolean.TRUE.equals(bool))
            writeLong(out, 1, BOOLEAN_TRUE);
        else
            writeLong(out, 1, BOOLEAN_FALSE);
    }
    
    public static Boolean fromBoolean(byte data[], int offset) {
        if (data[offset] == BOOLEAN_TRUE)
            return Boolean.TRUE;
        else if (data[offset] == BOOLEAN_FALSE)
            return Boolean.FALSE;
        else
            return null;
    }
    
    public static void toBoolean(byte data[], int offset, boolean value) {
        data[offset] = (value ? BOOLEAN_TRUE : BOOLEAN_FALSE);
    }
    public static void toBoolean(byte data[], int offset, Boolean value) {
        if (value == null)
            data[offset] = BOOLEAN_UNKNOWN;
        else
            data[offset] = (value.booleanValue() ? BOOLEAN_TRUE : BOOLEAN_FALSE);
    }
    
    public static final byte BOOLEAN_TRUE = 0x1;
    public static final byte BOOLEAN_FALSE = 0x0;
    public static final byte BOOLEAN_UNKNOWN = 0x2;
    public static final int BOOLEAN_LENGTH = 1;

    //
    // The following comparator helpers make it simpler to write consistently comparing
    // functions for objects based on their value, not JVM memory address
    //

    /**
     * Helper util to compare two objects, including null handling.
     * <p />
     *
     * This treats (null == null) as true, and (null == (!null)) as false.
     */
    public final static boolean eq(Object lhs, Object rhs) {
        try {
            boolean eq = (((lhs == null) && (rhs == null)) || ((lhs != null) && (lhs.equals(rhs))));
            return eq;
        } catch (ClassCastException cce) {
            return false;
        }
    }

    /**
     * Run a deep comparison across the two collections.  
     * <p />
     *
     * This treats (null == null) as true, (null == (!null)) as false, and then 
     * comparing each element via eq(object, object). <p />
     *
     * If the size of the collections are not equal, the comparison returns false.
     * The collection order should be consistent, as this simply iterates across both and compares
     * based on the value of each at each step along the way.
     *
     */
    public final static boolean eq(Collection lhs, Collection rhs) {
        if ((lhs == null) && (rhs == null)) return true;
        if ((lhs == null) || (rhs == null)) return false;
        if (lhs.size() != rhs.size()) return false;
        Iterator liter = lhs.iterator();
        Iterator riter = rhs.iterator();
        while ((liter.hasNext()) && (riter.hasNext()))
            if (!(eq(liter.next(), riter.next()))) return false;
        return true;
    }

    /**
     * Run a comparison on the byte arrays, byte by byte.  <p />
     *
     * This treats (null == null) as true, (null == (!null)) as false, 
     * and unequal length arrays as false.
     *
     */
    public final static boolean eq(byte lhs[], byte rhs[]) {
        boolean eq = (((lhs == null) && (rhs == null)) || ((lhs != null) && (rhs != null) && (Arrays.equals(lhs, rhs))));
        return eq;
    }

    /**
     * Compare two integers, really just for consistency.
     */
    public final static boolean eq(int lhs, int rhs) {
        return lhs == rhs;
    }

    /**
     * Compare two longs, really just for consistency.
     */
    public final static boolean eq(long lhs, long rhs) {
        return lhs == rhs;
    }

    /**
     * Compare two bytes, really just for consistency.
     */
    public final static boolean eq(byte lhs, byte rhs) {
        return lhs == rhs;
    }

    public final static boolean eq(byte lhs[], int offsetLeft, byte rhs[], int offsetRight, int length) {
        if ( (lhs == null) || (rhs == null) ) return false;
        if (length <= 0) return true;
        for (int i = 0; i < length; i++) {
            if (lhs[offsetLeft + i] != rhs[offsetRight + i]) 
                return false;
        }
        return true;
    }
    
    public final static int compareTo(byte lhs[], byte rhs[]) {
        if ((rhs == null) && (lhs == null)) return 0;
        if (lhs == null) return -1;
        if (rhs == null) return 1;
        if (rhs.length < lhs.length) return 1;
        if (rhs.length > lhs.length) return -1;
        for (int i = 0; i < rhs.length; i++) {
            if (rhs[i] > lhs[i])
                return -1;
            else if (rhs[i] < lhs[i]) return 1;
        }
        return 0;
    }

    public final static byte[] xor(byte lhs[], byte rhs[]) {
        if ((lhs == null) || (rhs == null) || (lhs.length != rhs.length)) return null;
        byte diff[] = new byte[lhs.length];
        xor(lhs, 0, rhs, 0, diff, 0, lhs.length);
        return diff;
    }

    /**
     * xor the lhs with the rhs, storing the result in out.  
     *
     * @param lhs one of the source arrays
     * @param startLeft starting index in the lhs array to begin the xor
     * @param rhs the other source array
     * @param startRight starting index in the rhs array to begin the xor
     * @param out output array
     * @param startOut starting index in the out array to store the result
     * @param len how many bytes into the various arrays to xor
     */
    public final static void xor(byte lhs[], int startLeft, byte rhs[], int startRight, byte out[], int startOut, int len) {
        if ( (lhs == null) || (rhs == null) || (out == null) )
            throw new NullPointerException("Invalid params to xor (" + lhs + ", " + rhs + ", " + out + ")");
        if (lhs.length < startLeft + len)
            throw new IllegalArgumentException("Left hand side is too short");
        if (rhs.length < startRight + len)
            throw new IllegalArgumentException("Right hand side is too short");
        if (out.length < startOut + len)
            throw new IllegalArgumentException("Result is too short");
        
        for (int i = 0; i < len; i++)
            out[startOut + i] = (byte) (lhs[startLeft + i] ^ rhs[startRight + i]);
    }

    //
    // The following hashcode helpers make it simpler to write consistently hashing
    // functions for objects based on their value, not JVM memory address
    //

    /**
     * Calculate the hashcode of the object, using 0 for null
     * 
     */
    public static int hashCode(Object obj) {
        if (obj == null) return 0;

        return obj.hashCode();
    }

    /**
     * Calculate the hashcode of the date, using 0 for null
     * 
     */
    public static int hashCode(Date obj) {
        if (obj == null) return 0;

        return (int) obj.getTime();
    }

    /**
     * Calculate the hashcode of the byte array, using 0 for null
     * 
     */
    public static int hashCode(byte b[]) {
        int rv = 0;
        if (b != null) {
            for (int i = 0; i < b.length && i < 32; i++)
                rv += (b[i] << i);
        }
        return rv;
    }

    /**
     * Calculate the hashcode of the collection, using 0 for null
     * 
     */
    public static int hashCode(Collection col) {
        if (col == null) return 0;
        int c = 0;
        for (Iterator iter = col.iterator(); iter.hasNext();)
            c = 7 * c + hashCode(iter.next());
        return c;
    }

    public static int read(InputStream in, byte target[]) throws IOException {
        return read(in, target, 0, target.length);
    }
    public static int read(InputStream in, byte target[], int offset, int length) throws IOException {
        int cur = offset;
        while (cur < length) {
            int numRead = in.read(target, cur, length - cur);
            if (numRead == -1) {
                if (cur == offset) return -1; // throw new EOFException("EOF Encountered during reading");
                return cur;
            }
            cur += numRead;
        }
        return cur;
    }
    
    
    /**
     * Read a newline delimited line from the stream, returning the line (without
     * the newline), or null if EOF reached before the newline was found
     */
    public static String readLine(InputStream in) throws IOException { return readLine(in, (Sha256Standalone)null); }
    /** update the hash along the way */
    public static String readLine(InputStream in, Sha256Standalone hash) throws IOException {
        StringBuffer buf = new StringBuffer(128);
        boolean ok = readLine(in, buf, hash);
        if (ok)
            return buf.toString();
        else
            return null;
    }
    /**
     * Read in a line, placing it into the buffer (excluding the newline).
     *
     * @return true if the line was read, false if eof was reached before a 
     *              newline was found
     */
    public static boolean readLine(InputStream in, StringBuffer buf) throws IOException {
        return readLine(in, buf, null);
    }
    /** update the hash along the way */
    public static boolean readLine(InputStream in, StringBuffer buf, Sha256Standalone hash) throws IOException {
        int c = -1;
        while ( (c = in.read()) != -1) {
            if (hash != null) hash.update((byte)c);
            if (c == '\n')
                break;
            buf.append((char)c);
        }
        if (c == -1) 
            return false;
        else
            return true;
    }
    
    public static void write(OutputStream out, byte data[], Sha256Standalone hash) throws IOException {
        hash.update(data);
        out.write(data);
    }

    public static List sortStructures(Collection dataStructures) {
        if (dataStructures == null) return new ArrayList();
        ArrayList rv = new ArrayList(dataStructures.size());
        TreeMap tm = new TreeMap();
        for (Iterator iter = dataStructures.iterator(); iter.hasNext();) {
            DataStructure struct = (DataStructure) iter.next();
            tm.put(struct.calculateHash().toString(), struct);
        }
        for (Iterator iter = tm.values().iterator(); iter.hasNext();) {
            rv.add(iter.next());
        }
        return rv;
    }

    public static String formatDuration(long ms) {
        if (ms < 5 * 1000) {
            return ms + "ms";
        } else if (ms < 5 * 60 * 1000) {
            return (ms / 1000) + "s";
        } else if (ms < 120 * 60 * 1000) {
            return (ms / (60 * 1000)) + "m";
        } else if (ms < 3 * 24 * 60 * 60 * 1000) {
            return (ms / (60 * 60 * 1000)) + "h";
        } else if (ms > 1000l * 24l * 60l * 60l * 1000l) {
            return "n/a";
        } else {
            return (ms / (24 * 60 * 60 * 1000)) + "d";
        }
    }
    
    /**
     * Strip out any HTML (simply removing any less than / greater than symbols)
     */
    public static String stripHTML(String orig) {
        if (orig == null) return "";
        String t1 = orig.replace('<', ' ');
        String rv = t1.replace('>', ' ');
        return rv;
    }

    private static final int MAX_UNCOMPRESSED = 40*1024;
    public static final int MAX_COMPRESSION = Deflater.BEST_COMPRESSION;
    public static final int NO_COMPRESSION = Deflater.NO_COMPRESSION;
    /** compress the data and return a new GZIP compressed array */
    public static byte[] compress(byte orig[]) {
        return compress(orig, 0, orig.length);
    }
    public static byte[] compress(byte orig[], int offset, int size) {
        return compress(orig, offset, size, MAX_COMPRESSION);
    }
    public static byte[] compress(byte orig[], int offset, int size, int level) {
        if ((orig == null) || (orig.length <= 0)) return orig;
        if (size >= MAX_UNCOMPRESSED) 
            throw new IllegalArgumentException("tell jrandom size=" + size);
        ReusableGZIPOutputStream out = ReusableGZIPOutputStream.acquire();
        out.setLevel(level);
        try {
            out.write(orig, offset, size);
            out.finish();
            out.flush();
            byte rv[] = out.getData();
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Compression of " + orig.length + " into " + rv.length + " (or " + 100.0d
            //               * (((double) orig.length) / ((double) rv.length)) + "% savings)");
            return rv;
        } catch (IOException ioe) {
            //_log.error("Error compressing?!", ioe);
            return null;
        } finally {
            ReusableGZIPOutputStream.release(out);
        }
        
    }
    
    /** decompress the GZIP compressed data (returning null on error) */
    public static byte[] decompress(byte orig[]) throws IOException {
        return (orig != null ? decompress(orig, 0, orig.length) : null);
    }
    public static byte[] decompress(byte orig[], int offset, int length) throws IOException {
        if ((orig == null) || (orig.length <= 0)) return orig;
        
        ReusableGZIPInputStream in = ReusableGZIPInputStream.acquire();
        in.initialize(new ByteArrayInputStream(orig, offset, length));
        
        ByteCache cache = ByteCache.getInstance(8, MAX_UNCOMPRESSED);
        ByteArray outBuf = cache.acquire();
        int written = 0;
        while (true) {
            int read = in.read(outBuf.getData(), written, MAX_UNCOMPRESSED-written);
            if (read == -1)
                break;
            written += read;
        }
        byte rv[] = new byte[written];
        System.arraycopy(outBuf.getData(), 0, rv, 0, written);
        cache.release(outBuf);
        ReusableGZIPInputStream.release(in);
        return rv;
    }

    public static byte[] getUTF8(String orig) {
        if (orig == null) return null;
        try {
            return orig.getBytes("UTF-8");
        } catch (UnsupportedEncodingException uee) {
            throw new RuntimeException("no utf8!?");
        }
    }
    public static byte[] getUTF8(StringBuffer orig) {
        if (orig == null) return null;
        return getUTF8(orig.toString());
    }
    public static String getUTF8(byte orig[]) {
        if (orig == null) return null;
        try {
            return new String(orig, "UTF-8");
        } catch (UnsupportedEncodingException uee) {
            throw new RuntimeException("no utf8!?");
        }
    }
    public static String getUTF8(byte orig[], int offset, int len) {
        if (orig == null) return null;
        try {
            return new String(orig, offset, len, "UTF-8");
        } catch (UnsupportedEncodingException uee) {
            throw new RuntimeException("No utf8!?");
        }
    }
    
    
}
