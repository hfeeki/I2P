/* I2PSOCKSTunnel is released under the terms of the GNU GPL,
 * with an additional exception.  For further details, see the
 * licensing terms in I2PTunnel.java.
 *
 * Copyright (c) 2004 by human
 */
package net.i2p.i2ptunnel.socks;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

import net.i2p.util.HexDump;
import net.i2p.util.Log;

/*
 * Class that manages SOCKS5 connections, and forwards them to
 * destination hosts or (eventually) some outproxy.
 *
 * @author human
 */
public class SOCKS5Server extends SOCKSServer {
    private static final Log _log = new Log(SOCKS5Server.class);

    private static final int SOCKS_VERSION_5 = 0x05;

    private Socket clientSock = null;

    private boolean setupCompleted = false;

    /**
     * Create a SOCKS5 server that communicates with the client using
     * the specified socket.  This method should not be invoked
     * directly: new SOCKS5Server objects should be created by using
     * SOCKSServerFactory.createSOCSKServer().  It is assumed that the
     * SOCKS VER field has been stripped from the input stream of the
     * client socket.
     *
     * @param clientSock client socket
     */
    public SOCKS5Server(Socket clientSock) {
        this.clientSock = clientSock;
    }

    public Socket getClientSocket() throws SOCKSException {
        setupServer();

        return clientSock;
    }

    protected void setupServer() throws SOCKSException {
        if (setupCompleted) { return; }

        DataInputStream in;
        DataOutputStream out;
        try {
            in = new DataInputStream(clientSock.getInputStream());
            out = new DataOutputStream(clientSock.getOutputStream());

            init(in, out);
            manageRequest(in, out);
        } catch (IOException e) {
            throw new SOCKSException("Connection error (" + e.getMessage() + ")");
        }

        setupCompleted = true;
    }

    /**
     * SOCKS5 connection initialization.  This method assumes that
     * SOCKS "VER" field has been stripped from the input stream.
     */
    private void init(DataInputStream in, DataOutputStream out) throws IOException, SOCKSException {
        int nMethods = in.readByte() & 0xff;
        boolean methodOk = false;
        int method = Method.NO_ACCEPTABLE_METHODS;

        for (int i = 0; i < nMethods; ++i) {
            method = in.readByte() & 0xff;
            if (method == Method.NO_AUTH_REQUIRED) {
                // That's fine, we do support this method
                break;
            }
        }

        boolean canContinue = false;
        switch (method) {
        case Method.NO_AUTH_REQUIRED:
            _log.debug("no authentication required");
            sendInitReply(Method.NO_AUTH_REQUIRED, out);
            return;
        default:
            _log.debug("no suitable authentication methods found (" + Integer.toHexString(method) + ")");
            sendInitReply(Method.NO_ACCEPTABLE_METHODS, out);
            throw new SOCKSException("Unsupported authentication method");
        }
    }

    /**
     * SOCKS5 request management.  This method assumes that all the
     * stuff preceding or enveloping the actual request (e.g. protocol
     * initialization, integrity/confidentiality encapsulations, etc)
     * has been stripped out of the input/output streams.
     */
    private void manageRequest(DataInputStream in, DataOutputStream out) throws IOException, SOCKSException {
        int socksVer = in.readByte() & 0xff;
        if (socksVer != SOCKS_VERSION_5) {
            _log.debug("error in SOCKS5 request (protocol != 5? wtf?)");
            throw new SOCKSException("Invalid protocol version in request");
        }

        int command = in.readByte() & 0xff;
        switch (command) {
        case Command.CONNECT:
            break;
        case Command.BIND:
            _log.debug("BIND command is not supported!");
            sendRequestReply(Reply.COMMAND_NOT_SUPPORTED, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
            throw new SOCKSException("BIND command not supported");
        case Command.UDP_ASSOCIATE:
            _log.debug("UDP ASSOCIATE command is not supported!");
            sendRequestReply(Reply.COMMAND_NOT_SUPPORTED, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
            throw new SOCKSException("UDP ASSOCIATE command not supported");
        default:
            _log.debug("unknown command in request (" + Integer.toHexString(command) + ")");
            throw new SOCKSException("Invalid command in request");
        }

        {
            // Reserved byte, should be 0x00
            byte rsv = in.readByte();
        }

        int addressType = in.readByte() & 0xff;
        switch (addressType) {
        case AddressType.IPV4:
            connHostName = new String("");
            for (int i = 0; i < 4; ++i) {
                int octet = in.readByte() & 0xff;
                connHostName += Integer.toString(octet);
                if (i != 3) {
                    connHostName += ".";
                }
            }
            _log.warn("IPV4 address type in request: " + connHostName + ". Is your client secure?");
            break;
        case AddressType.DOMAINNAME:
            {
                int addrLen = in.readByte() & 0xff;
                if (addrLen == 0) {
                    _log.debug("0-sized address length? wtf?");
                    throw new SOCKSException("Illegal DOMAINNAME length");
                }
                byte addr[] = new byte[addrLen];
                in.readFully(addr);
                connHostName = new String(addr);
            }
            _log.debug("DOMAINNAME address type in request: " + connHostName);
            break;
        case AddressType.IPV6:
            _log.warn("IP V6 address type in request! Is your client secure?" + " (IPv6 is not supported, anyway :-)");
            sendRequestReply(Reply.ADDRESS_TYPE_NOT_SUPPORTED, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
            throw new SOCKSException("IPV6 addresses not supported");
        default:
            _log.debug("unknown address type in request (" + Integer.toHexString(command) + ")");
            throw new SOCKSException("Invalid addresses type in request");
        }

        connPort = in.readUnsignedShort();
        if (connPort == 0) {
            _log.debug("trying to connect to TCP port 0?  Dropping!");
            throw new SOCKSException("Invalid port number in request");
        }
    }

    protected void confirmConnection() throws SOCKSException {
        DataInputStream in;
        DataOutputStream out;
        try {
            out = new DataOutputStream(clientSock.getOutputStream());

            sendRequestReply(Reply.SUCCEEDED, AddressType.IPV4, InetAddress.getByName("127.0.0.1"), null, 1, out);
        } catch (IOException e) {
            throw new SOCKSException("Connection error (" + e.getMessage() + ")");
        }
    }

    /**
     * Send the specified reply during SOCKS5 initialization
     */
    private void sendInitReply(int replyCode, DataOutputStream out) throws IOException {
        ByteArrayOutputStream reps = new ByteArrayOutputStream();

        reps.write(SOCKS_VERSION_5);
        reps.write(replyCode);

        byte[] reply = reps.toByteArray();

        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug("Sending init reply:\n" + HexDump.dump(reply));
        }

        out.write(reply);
    }

    /**
     * Send the specified reply to a request of the client.  Either
     * one of inetAddr or domainName can be null, depending on
     * addressType.
     */
    private void sendRequestReply(int replyCode, int addressType, InetAddress inetAddr, String domainName,
                                  int bindPort, DataOutputStream out) throws IOException {
        ByteArrayOutputStream reps = new ByteArrayOutputStream();
        DataOutputStream dreps = new DataOutputStream(reps);

        dreps.write(SOCKS_VERSION_5);
        dreps.write(replyCode);

        // Reserved byte, should be 0x00
        dreps.write(0x00);

        dreps.write(addressType);

        switch (addressType) {
        case AddressType.IPV4:
            dreps.write(inetAddr.getAddress());
            break;
        case AddressType.DOMAINNAME:
            dreps.writeByte(domainName.length());
            dreps.writeBytes(domainName);
            break;
        default:
            _log.error("unknown address type passed to sendReply() (" + Integer.toHexString(addressType) + ")! wtf?");
            return;
        }

        dreps.writeShort(bindPort);

        byte[] reply = reps.toByteArray();

        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug("Sending request reply:\n" + HexDump.dump(reply));
        }

        out.write(reply);
    }

    /*
     * Some namespaces to enclose SOCKS protocol codes
     */
    private class Method {
        private static final int NO_AUTH_REQUIRED = 0x00;
        private static final int NO_ACCEPTABLE_METHODS = 0xff;
    }

    private class AddressType {
        private static final int IPV4 = 0x01;
        private static final int DOMAINNAME = 0x03;
        private static final int IPV6 = 0x04;
    }

    private class Command {
        private static final int CONNECT = 0x01;
        private static final int BIND = 0x02;
        private static final int UDP_ASSOCIATE = 0x03;
    }

    private class Reply {
        private static final int SUCCEEDED = 0x00;
        private static final int GENERAL_SOCKS_SERVER_FAILURE = 0x01;
        private static final int CONNECTION_NOT_ALLOWED_BY_RULESET = 0x02;
        private static final int NETWORK_UNREACHABLE = 0x03;
        private static final int HOST_UNREACHABLE = 0x04;
        private static final int CONNECTION_REFUSED = 0x05;
        private static final int TTL_EXPIRED = 0x06;
        private static final int COMMAND_NOT_SUPPORTED = 0x07;
        private static final int ADDRESS_TYPE_NOT_SUPPORTED = 0x08;
    }
}