package net.i2p.client.streaming;

import java.util.Properties;

/**
 * Define the configuration for streaming and verifying data on the socket.
 *
 */
public class I2PSocketOptions {
    private long _connectTimeout;
    private long _readTimeout;
    private long _writeTimeout;
    private int _maxBufferSize;

    public static final int DEFAULT_BUFFER_SIZE = 1024*64;
    public static final int DEFAULT_WRITE_TIMEOUT = 60*1000;
    
    public I2PSocketOptions() {
        _connectTimeout = -1;
        _readTimeout = -1;
        _writeTimeout = DEFAULT_WRITE_TIMEOUT;
        _maxBufferSize = DEFAULT_BUFFER_SIZE;
    }
    
    public I2PSocketOptions(I2PSocketOptions opts) {
        _connectTimeout = opts.getConnectTimeout();
        _readTimeout = opts.getReadTimeout();
        _writeTimeout = opts.getWriteTimeout();
        _maxBufferSize = opts.getMaxBufferSize();
    }

    public I2PSocketOptions(Properties opts) {
        
    }
    
    /**
     * How long we will wait for the ACK from a SYN, in milliseconds.
     *
     * @return milliseconds to wait, or -1 if we will wait indefinitely
     */
    public long getConnectTimeout() {
        return _connectTimeout;
    }

    /**
     * Define how long we will wait for the ACK from a SYN, in milliseconds.
     *
     */
    public void setConnectTimeout(long ms) {
        _connectTimeout = ms;
    }
    
    /**
     * What is the longest we'll block on the input stream while waiting
     * for more data.  If this value is exceeded, the read() throws 
     * InterruptedIOException
     */
    public long getReadTimeout() {
        return _readTimeout;
    }

    /**
     * What is the longest we'll block on the input stream while waiting
     * for more data.  If this value is exceeded, the read() throws 
     * InterruptedIOException
     */
    public void setReadTimeout(long ms) {
        _readTimeout = ms;
    }
    
    /**
     * How much data will we accept that hasn't been written out yet.  After 
     * this amount has been exceeded, subsequent .write calls will block until
     * either some data is removed or the connection is closed.  If this is 
     * less than or equal to zero, there is no limit (warning: can eat ram)
     *
     * @return buffer size limit, in bytes
     */
    public int getMaxBufferSize() {
        return _maxBufferSize; 
    }
    
    /**
     * How much data will we accept that hasn't been written out yet.  After 
     * this amount has been exceeded, subsequent .write calls will block until
     * either some data is removed or the connection is closed.  If this is 
     * less than or equal to zero, there is no limit (warning: can eat ram)
     *
     */
    public void setMaxBufferSize(int numBytes) {
        _maxBufferSize = numBytes; 
    }
    
    /**
     * What is the longest we'll block on the output stream while waiting
     * for the data to flush.  If this value is exceeded, the write() throws 
     * InterruptedIOException.  If this is less than or equal to zero, there 
     * is no timeout.
     */
    public long getWriteTimeout() {
        return _writeTimeout;
    }

    /**
     * What is the longest we'll block on the output stream while waiting
     * for the data to flush.  If this value is exceeded, the write() throws 
     * InterruptedIOException.  If this is less than or equal to zero, there 
     * is no timeout.
     */
    public void setWriteTimeout(long ms) {
        _writeTimeout = ms;
    }
}
