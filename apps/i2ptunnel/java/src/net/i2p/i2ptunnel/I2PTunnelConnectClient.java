/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.util.EventDispatcher;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;

/**
 * Supports the following:
 *   (where protocol is generally HTTP/1.1 but is ignored)
 *   (where host is one of:
 *      example.i2p
 *      52chars.b32.i2p
 *      516+charsbase64
 *      example.com (sent to one of the configured proxies)
 *   )
 *
 *   (port and protocol are ignored for i2p destinations)
 *   CONNECT host
 *   CONNECT host protocol
 *   CONNECT host:port
 *   CONNECT host:port protocol (this is the standard)
 *
 * Additional lines after the CONNECT line but before the blank line are ignored and stripped.
 * The CONNECT line is removed for .i2p accesses
 * but passed along for outproxy accesses.
 *
 * Ref:
 *  INTERNET-DRAFT                                              Ari Luotonen
 *  Expires: September 26, 1997          Netscape Communications Corporation
 *  <draft-luotonen-ssl-tunneling-03.txt>                     March 26, 1997
 *                     Tunneling SSL Through a WWW Proxy
 *
 * @author zzz a stripped-down I2PTunnelHTTPClient
 */
public class I2PTunnelConnectClient extends I2PTunnelClientBase implements Runnable {
    private static final Log _log = new Log(I2PTunnelConnectClient.class);

    private final List<String> _proxyList;

    private final static byte[] ERR_DESTINATION_UNKNOWN =
        ("HTTP/1.1 503 Service Unavailable\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-control: no-cache\r\n"+
         "\r\n"+
         "<html><body><H1>I2P ERROR: DESTINATION NOT FOUND</H1>"+
         "That I2P Destination was not found. "+
         "The host (or the outproxy, if you're using one) could also "+
	 "be temporarily offline.  You may want to <b>retry</b>.  "+
         "Could not find the following Destination:<BR><BR><div>")
        .getBytes();
    
    private final static byte[] ERR_NO_OUTPROXY =
        ("HTTP/1.1 503 Service Unavailable\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-control: no-cache\r\n"+
         "\r\n"+
         "<html><body><H1>I2P ERROR: No outproxy found</H1>"+
         "Your request was for a site outside of I2P, but you have no "+
         "HTTP outproxy configured.  Please configure an outproxy in I2PTunnel")
         .getBytes();
    
    private final static byte[] ERR_BAD_PROTOCOL =
        ("HTTP/1.1 405 Bad Method\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-control: no-cache\r\n"+
         "\r\n"+
         "<html><body><H1>I2P ERROR: METHOD NOT ALLOWED</H1>"+
         "The request uses a bad protocol. "+
         "The Connect Proxy supports CONNECT requests ONLY. Other methods such as GET are not allowed - Maybe you wanted the HTTP Proxy?.<BR>")
        .getBytes();
    
    private final static byte[] ERR_LOCALHOST =
        ("HTTP/1.1 403 Access Denied\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-control: no-cache\r\n"+
         "\r\n"+
         "<html><body><H1>I2P ERROR: REQUEST DENIED</H1>"+
         "Your browser is misconfigured. Do not use the proxy to access the router console or other localhost destinations.<BR>")
        .getBytes();
    
    private final static byte[] SUCCESS_RESPONSE =
        ("HTTP/1.1 200 Connection Established\r\n"+
         "Proxy-agent: I2P\r\n"+
         "\r\n")
        .getBytes();
    
    /** used to assign unique IDs to the threads / clients.  no logic or functionality */
    private static volatile long __clientId = 0;

    /**
     * @throws IllegalArgumentException if the I2PTunnel does not contain
     *                                  valid config to contact the router
     */
    public I2PTunnelConnectClient(int localPort, Logging l, boolean ownDest, 
                               String wwwProxy, EventDispatcher notifyThis, 
                               I2PTunnel tunnel) throws IllegalArgumentException {
        super(localPort, ownDest, l, notifyThis, "HTTPHandler " + (++__clientId), tunnel);

        _proxyList = new ArrayList();
        if (waitEventValue("openBaseClientResult").equals("error")) {
            notifyEvent("openConnectClientResult", "error");
            return;
        }

        if (wwwProxy != null) {
            StringTokenizer tok = new StringTokenizer(wwwProxy, ", ");
            while (tok.hasMoreTokens())
                _proxyList.add(tok.nextToken().trim());
        }

        setName(getLocalPort() + " -> ConnectClient [Outproxy list: " + wwwProxy + "]");

        startRunning();
    }

    private String getPrefix(long requestId) { return "Client[" + _clientId + "/" + requestId + "]: "; }
    
    private String selectProxy() {
        synchronized (_proxyList) {
            int size = _proxyList.size();
            if (size <= 0)
                return null;
            int index = I2PAppContext.getGlobalContext().random().nextInt(size);
            return _proxyList.get(index);
        }
    }

    private static final int DEFAULT_READ_TIMEOUT = 60*1000;
    
    /** 
     * create the default options (using the default timeout, etc)
     *
     */
    protected I2PSocketOptions getDefaultOptions() {
        Properties defaultOpts = getTunnel().getClientOptions();
        if (!defaultOpts.contains(I2PSocketOptions.PROP_READ_TIMEOUT))
            defaultOpts.setProperty(I2PSocketOptions.PROP_READ_TIMEOUT, ""+DEFAULT_READ_TIMEOUT);
        if (!defaultOpts.contains("i2p.streaming.inactivityTimeout"))
            defaultOpts.setProperty("i2p.streaming.inactivityTimeout", ""+DEFAULT_READ_TIMEOUT);
        I2PSocketOptions opts = sockMgr.buildOptions(defaultOpts);
        if (!defaultOpts.containsKey(I2PSocketOptions.PROP_CONNECT_TIMEOUT))
            opts.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
        return opts;
    }
    
    private static long __requestId = 0;
    protected void clientConnectionRun(Socket s) {
        InputStream in = null;
        OutputStream out = null;
        String targetRequest = null;
        boolean usingWWWProxy = false;
        String currentProxy = null;
        long requestId = ++__requestId;
        try {
            out = s.getOutputStream();
            in = s.getInputStream();
            String line, method = null, host = null, destination = null, restofline = null;
            StringBuffer newRequest = new StringBuffer();
            int ahelper = 0;
            while (true) {
                // Use this rather than BufferedReader because we can't have readahead,
                // since we are passing the stream on to I2PTunnelRunner
                line = DataHelper.readLine(in);
                line = line.trim();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(getPrefix(requestId) + "Line=[" + line + "]");
                
                if (method == null) { // first line CONNECT blah.i2p:80 HTTP/1.1
                    int pos = line.indexOf(" ");
                    if (pos == -1) break;    // empty first line
                    method = line.substring(0, pos);
                    String request = line.substring(pos + 1);

                    pos = request.indexOf(":");
                    if (pos == -1)
                       pos = request.indexOf(" ");
                    if (pos == -1) {
                        host = request;
                        restofline = "";
                    } else {
                        host = request.substring(0, pos);
                        restofline = request.substring(pos); // ":80 HTTP/1.1" or " HTTP/1.1"
                    }

                    if (host.toLowerCase().endsWith(".i2p")) {
                        // Destination gets the host name
                        destination = host;
                    } else if (host.indexOf(".") != -1) {
                        // The request must be forwarded to a outproxy
                        currentProxy = selectProxy();
                        if (currentProxy == null) {
                            if (_log.shouldLog(Log.WARN))
                                _log.warn(getPrefix(requestId) + "Host wants to be outproxied, but we dont have any!");
                            writeErrorMessage(ERR_NO_OUTPROXY, out);
                            s.close();
                            return;
                        }
                        destination = currentProxy;
                        usingWWWProxy = true;
                        newRequest.append("CONNECT ").append(host).append(restofline).append("\r\n\r\n"); // HTTP spec
                    } else if (host.toLowerCase().equals("localhost")) {
                        writeErrorMessage(ERR_LOCALHOST, out);
                        s.close();
                        return;
                    } else {  // full b64 address (hopefully)
                        destination = host;
                    }
                    targetRequest = host;

                    if (_log.shouldLog(Log.DEBUG)) {
                        _log.debug(getPrefix(requestId) + "METHOD:" + method + ":");
                        _log.debug(getPrefix(requestId) + "HOST  :" + host + ":");
                        _log.debug(getPrefix(requestId) + "REST  :" + restofline + ":");
                        _log.debug(getPrefix(requestId) + "DEST  :" + destination + ":");
                    }
                    
                } else if (line.length() > 0) {
                    // Additional lines - shouldn't be too many. Firefox sends:
                    // User-Agent: blabla
                    // Proxy-Connection: keep-alive
                    // Host: blabla.i2p
                    //
                    // We could send these (filtered like in HTTPClient) on to the outproxy,
                    // but for now just chomp them all.
                    line = null;
                } else {
                    // do it
                    break;
                }
            }

            if (destination == null || !"CONNECT".equalsIgnoreCase(method)) {
                writeErrorMessage(ERR_BAD_PROTOCOL, out);
                s.close();
                return;
            }
            
            Destination dest = I2PTunnel.destFromName(destination);
            if (dest == null) {
                String str;
                byte[] header;
                if (usingWWWProxy)
                    str = FileUtil.readTextFile("docs/dnfp-header.ht", 100, true);
                else
                    str = FileUtil.readTextFile("docs/dnfh-header.ht", 100, true);
                if (str != null)
                    header = str.getBytes();
                else
                    header = ERR_DESTINATION_UNKNOWN;
                writeErrorMessage(header, out, targetRequest, usingWWWProxy, destination);
                s.close();
                return;
            }

            I2PSocket i2ps = createI2PSocket(dest, getDefaultOptions());
            byte[] data = null;
            byte[] response = null;
            if (usingWWWProxy)
                data = newRequest.toString().getBytes("ISO-8859-1");
            else
                response = SUCCESS_RESPONSE;
            Runnable onTimeout = new OnTimeout(s, s.getOutputStream(), targetRequest, usingWWWProxy, currentProxy, requestId);
            I2PTunnelRunner runner = new I2PTunnelRunner(s, i2ps, sockLock, data, response, mySockets, onTimeout);
        } catch (SocketException ex) {
            _log.info(getPrefix(requestId) + "Error trying to connect", ex);
            handleConnectClientException(ex, out, targetRequest, usingWWWProxy, currentProxy, requestId);
            closeSocket(s);
        } catch (IOException ex) {
            _log.info(getPrefix(requestId) + "Error trying to connect", ex);
            handleConnectClientException(ex, out, targetRequest, usingWWWProxy, currentProxy, requestId);
            closeSocket(s);
        } catch (I2PException ex) {
            _log.info("getPrefix(requestId) + Error trying to connect", ex);
            handleConnectClientException(ex, out, targetRequest, usingWWWProxy, currentProxy, requestId);
            closeSocket(s);
        } catch (OutOfMemoryError oom) {
            IOException ex = new IOException("OOM");
            _log.info("getPrefix(requestId) + Error trying to connect", ex);
            handleConnectClientException(ex, out, targetRequest, usingWWWProxy, currentProxy, requestId);
            closeSocket(s);
        }
    }

    private static class OnTimeout implements Runnable {
        private Socket _socket;
        private OutputStream _out;
        private String _target;
        private boolean _usingProxy;
        private String _wwwProxy;
        private long _requestId;
        public OnTimeout(Socket s, OutputStream out, String target, boolean usingProxy, String wwwProxy, long id) {
            _socket = s;
            _out = out;
            _target = target;
            _usingProxy = usingProxy;
            _wwwProxy = wwwProxy;
            _requestId = id;
        }
        public void run() {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Timeout occured requesting " + _target);
            handleConnectClientException(new RuntimeException("Timeout"), _out, 
                                      _target, _usingProxy, _wwwProxy, _requestId);
            closeSocket(_socket);
        }
    }
    
    private static void writeErrorMessage(byte[] errMessage, OutputStream out) throws IOException {
        if (out == null)
            return;
        out.write(errMessage);
        out.write("\n</body></html>\n".getBytes());
        out.flush();
    }

    private static void writeErrorMessage(byte[] errMessage, OutputStream out, String targetRequest,
                                          boolean usingWWWProxy, String wwwProxy) throws IOException {
        if (out != null) {
            out.write(errMessage);
            if (targetRequest != null) {
                out.write(targetRequest.getBytes());
                if (usingWWWProxy)
                    out.write(("<br>WWW proxy: " + wwwProxy).getBytes());
            }
            out.write("</div>".getBytes());
            out.write("\n</body></html>\n".getBytes());
            out.flush();
        }
    }

    private static void handleConnectClientException(Exception ex, OutputStream out, String targetRequest,
                                                  boolean usingWWWProxy, String wwwProxy, long requestId) {
        if (out == null)
            return;
        try {
            String str;
            byte[] header;
            if (usingWWWProxy)
                str = FileUtil.readTextFile("docs/dnfp-header.ht", 100, true);
            else
                str = FileUtil.readTextFile("docs/dnf-header.ht", 100, true);
            if (str != null)
                header = str.getBytes();
            else
                header = ERR_DESTINATION_UNKNOWN;
            writeErrorMessage(header, out, targetRequest, usingWWWProxy, wwwProxy);
        } catch (IOException ioe) {}
    }
}
