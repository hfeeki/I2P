package net.i2p.i2ptunnel.irc;

import net.i2p.data.DataHelper;


/**
 * Static methods to filter individual lines.
 * Moved from I2PTunnelIRCClient.java
 *
 * @since 0.8.9
 */
abstract class IRCFilter {

    private static final boolean ALLOW_ALL_DCC_IN = false;
    private static final boolean ALLOW_ALL_DCC_OUT = false;
    /** does not override DCC handling */
    private static final boolean ALLOW_ALL_CTCP_IN = false;
    /** does not override DCC handling */
    private static final boolean ALLOW_ALL_CTCP_OUT = false;

    /*************************************************************************
     *
     *  Modify or filter a single inbound line.
     *
     *  @param helper may be null
     *  @return the original or modified line, or null if it should be dropped.
     */
    public static String inboundFilter(String s, StringBuffer expectedPong, DCCHelper helper) {
        
        String field[]=s.split(" ",4);
        String command;
        int idx=0;
        final String[] allowedCommands =
        {
                // "NOTICE", // can contain CTCP
                //"PING",
                //"PONG",
                "MODE",
                "JOIN",
                "NICK",
                "QUIT",
                "PART",
                "WALLOPS",
                "ERROR",
                "KICK",
                "H", // "hide operator status" (after kicking an op)
                "TOPIC",
                // http://tools.ietf.org/html/draft-mitchell-irc-capabilities-01
                "CAP"
        };
        
        if(field[0].charAt(0)==':')
            idx++;

        try { command = field[idx++]; }
         catch (IndexOutOfBoundsException ioobe) // wtf, server sent borked command?
        {
           //_log.warn("Dropping defective message: index out of bounds while extracting command.");
           return null;
        }

        idx++; //skip victim

        // Allow numerical responses
        try {
            new Integer(command);
            return s;
        } catch(NumberFormatException nfe){}

        
	if ("PING".equalsIgnoreCase(command))
            return "PING 127.0.0.1"; // no way to know what the ircd to i2ptunnel server con is, so localhost works
	if ("PONG".equalsIgnoreCase(command)) {
            // Turn the received ":irc.freshcoffee.i2p PONG irc.freshcoffee.i2p :127.0.0.1"
            // into ":127.0.0.1 PONG 127.0.0.1 " so that the caller can append the client's extra parameter
            // though, does 127.0.0.1 work for irc clients connecting remotely?  and for all of them?  sure would
            // be great if irc clients actually followed the RFCs here, but i guess thats too much to ask.
            // If we haven't PINGed them, or the PING we sent isn't something we know how to filter, this 
            // is blank.
            //
            // String pong = expectedPong.length() > 0 ? expectedPong.toString() : null;
            // If we aren't going to rewrite it, pass it through
            String pong = expectedPong.length() > 0 ? expectedPong.toString() : s;
            expectedPong.setLength(0);
            return pong;
        }
        
        // Allow all allowedCommands
        for(int i=0;i<allowedCommands.length;i++) {
            if(allowedCommands[i].equalsIgnoreCase(command))
                return s;
        }
        
        // Allow PRIVMSG, but block CTCP.
        if("PRIVMSG".equalsIgnoreCase(command) || "NOTICE".equalsIgnoreCase(command))
        {
            String msg;
            msg = field[idx++];
        
            if(msg.indexOf(0x01) >= 0) // CTCP marker ^A can be anywhere, not just immediately after the ':'
            {
                // CTCP

                // don't even try to parse multiple CTCP in the same message
                int count = 0;
                for (int i = 0; i < msg.length(); i++) {
                    if (msg.charAt(i) == 0x01)
                        count++;
                }
                if (count != 2)
                    return null;

                msg=msg.substring(2);
                if(msg.startsWith("ACTION ")) {
                    // /me says hello
                    return s;
                }
                if (msg.startsWith("DCC ")) {
                    StringBuilder buf = new StringBuilder(128);
                    for (int i = 0; i <= idx - 2; i++) {
                        buf.append(field[i]).append(' ');
                    }
                    buf.append(":\001DCC ");
                    return filterDCCIn(buf.toString(), msg.substring(4), helper);
                }
                if (ALLOW_ALL_CTCP_IN)
                    return s;
                return null; // Block all other ctcp
            }
            return s;
        }
        
        // Block the rest
        return null;
    }
    
    /*************************************************************************
     *
     *  Modify or filter a single outbound line.
     *
     *  @param helper may be null
     *  @return the original or modified line, or null if it should be dropped.
     */
    public static String outboundFilter(String s, StringBuffer expectedPong, DCCHelper helper) {
        
        String field[]=s.split(" ",3);
        String command;
        final String[] allowedCommands =
        {
                // "NOTICE", // can contain CTCP
                "MODE",
                "JOIN",
                "NICK",
                "WHO",
                "WHOIS",
                "LIST",
                "NAMES",
                "NICK",
                // "QUIT", // replace with a filtered QUIT to hide client quit messages
                "SILENCE",
                "MAP", // seems safe enough, the ircd should protect themselves though
                // "PART", // replace with filtered PART to hide client part messages
                "OPER",
                // "PONG", // replaced with a filtered PING/PONG since some clients send the server IP (thanks aardvax!) 
                // "PING", 
                "KICK",
                "HELPME",
                "RULES",
                "TOPIC",
                "ISON",    // jIRCii uses this for a ping (response is 303)
                "INVITE",
                "AWAY",    // should be harmless
                // http://tools.ietf.org/html/draft-mitchell-irc-capabilities-01
                "CAP"
        };

        if(field[0].length()==0)
	    return null; // W T F?
	
        
        if(field[0].charAt(0)==':')
            return null; // wtf
        
        command = field[0].toUpperCase();

	if ("PING".equals(command)) {
            // Most clients just send a PING and are happy with any old PONG.  Others,
            // like BitchX, actually expect certain behavior.  It sends two different pings:
            // "PING :irc.freshcoffee.i2p" and "PING 1234567890 127.0.0.1" (where the IP is the proxy)
            // the PONG to the former seems to be "PONG 127.0.0.1", while the PONG to the later is
            // ":irc.freshcoffee.i2p PONG irc.freshcoffe.i2p :1234567890".
            // We don't want to send them our proxy's IP address, so we need to rewrite the PING
            // sent to the server, but when we get a PONG back, use what we expected, rather than
            // what they sent.
            //
            // Yuck.

            String rv = null;
            expectedPong.setLength(0);
            if (field.length == 1) { // PING
                rv = "PING";
                // If we aren't rewriting the PING don't rewrite the PONG
                // expectedPong.append("PONG 127.0.0.1");
            } else if (field.length == 2) { // PING nonce
                rv = "PING " + field[1];
                // If we aren't rewriting the PING don't rewrite the PONG
                // expectedPong.append("PONG ").append(field[1]);
            } else if (field.length == 3) { // PING nonce serverLocation
                rv = "PING " + field[1];
                expectedPong.append("PONG ").append(field[2]).append(" :").append(field[1]); // PONG serverLocation nonce
            } else {
                //if (_log.shouldLog(Log.ERROR))
                //    _log.error("IRC client sent a PING we don't understand, filtering it (\"" + s + "\")");
                rv = null;
            }
            
            //if (_log.shouldLog(Log.WARN))
            //    _log.warn("sending ping [" + rv + "], waiting for [" + expectedPong + "] orig was [" + s  + "]");
            
            return rv;
        }
	if ("PONG".equals(command))
            return "PONG 127.0.0.1"; // no way to know what the ircd to i2ptunnel server con is, so localhost works

        // Allow all allowedCommands
        for(int i=0;i<allowedCommands.length;i++)
        {
            if(allowedCommands[i].equals(command))
                return s;
        }
        
        // mIRC sends "NOTICE user :DCC Send file (IP)"
        // in addition to the CTCP version
        if("NOTICE".equals(command))
        {
            String msg = field[2];
            if(msg.startsWith(":DCC "))
                return filterDCCOut(field[0] + ' ' + field[1] + " :DCC ", msg.substring(5), helper);
            // fall through
        }
        
        // Allow PRIVMSG, but block CTCP (except ACTION).
        if("PRIVMSG".equals(command) || "NOTICE".equals(command))
        {
            String msg;
            msg = field[2];
        
            if(msg.indexOf(0x01) >= 0) // CTCP marker ^A can be anywhere, not just immediately after the ':'
            {
                    // CTCP

                // don't even try to parse multiple CTCP in the same message
                int count = 0;
                for (int i = 0; i < msg.length(); i++) {
                    if (msg.charAt(i) == 0x01)
                        count++;
                }
                if (count != 2)
                    return null;

                msg=msg.substring(2);
                if(msg.startsWith("ACTION ")) {
                    // /me says hello
                    return s;
                }
                if (msg.startsWith("DCC "))
                    return filterDCCOut(field[0] + ' ' + field[1] + " :\001DCC ", msg.substring(4), helper);
                if (ALLOW_ALL_CTCP_OUT)
                    return s;
                return null; // Block all other ctcp
            }
            return s;
        }
        
        if("USER".equals(command)) {
            int idx = field[2].lastIndexOf(":");
            if(idx<0)
                return "USER user hostname localhost :realname";
            String realname = field[2].substring(idx+1);
            String ret = "USER "+field[1]+" hostname localhost :"+realname;
            return ret;
        }

        if ("PART".equals(command)) {
            // hide client message
            return "PART " + field[1] + " :leaving";
        }
        
        if ("QUIT".equals(command)) {
            return "QUIT :leaving";
        }
        
        // Block the rest
        return null;
    }

    /**
     *  @param pfx the message through the "DCC " part
     *  @param msg the message after the "DCC " part
     *  @param helper may be null
     *  @return the sanitized message or null to block
     *  @since 0.8.9
     */
    private static String filterDCCIn(String pfx, String msg, DCCHelper helper) {
        // strip trailing ctcp (other one is in pfx)
        int ctcp = msg.indexOf(0x01);
        if (ctcp > 0)
            msg = msg.substring(0, ctcp);
        String[] args = msg.split(" ", 5);
        if (args.length <= 0)
            return null;
        String type = args[0];
        // no IP in these but port needs to be fixed still
        //if (type == "RESUME" || type == "ACCEPT")
        //    return msg;
        if (!(type.equals("CHAT") || type.equals("SEND"))) {
            if (ALLOW_ALL_DCC_IN) {
                if (ctcp > 0)
                    return pfx + msg + (char) 0x01;
                return pfx + msg;
            }
            return null;
        }
        if (helper == null || !helper.isEnabled())
            return null;
        if (args.length < 4)
            return null;
        String arg = args[1];
        String b32 = args[2];
        int cPort;
        try {
            String cp = args[3];
            cPort = Integer.parseInt(cp);
        } catch (NumberFormatException nfe) {
            return null;
        }

        int port = helper.newIncoming(b32, cPort, type);
        if (port < 0)
            return null;
        StringBuilder buf = new StringBuilder(256);
        // fixme what is our address?
        byte[] myIP = { 127, 0, 0, 1 };
        buf.append(pfx)
           .append(type).append(' ').append(arg).append(' ')
           .append(DataHelper.fromLong(myIP, 0, myIP.length)).append(' ')
           .append(port);
        if (args.length > 4)
            buf.append(' ').append(args[4]);
        if (pfx.indexOf(0x01) >= 0)
            buf.append((char) 0x01);
        return buf.toString();
    }

    /**
     *  @param pfx the message through the "DCC " part
     *  @param msg the message after the "DCC " part
     *  @param helper may be null
     *  @return the sanitized message or null to block
     *  @since 0.8.9
     */
    private static String filterDCCOut(String pfx, String msg, DCCHelper helper) {
        // strip trailing ctcp (other one is in pfx)
        int ctcp = msg.indexOf(0x01);
        if (ctcp > 0)
            msg = msg.substring(0, ctcp);
        String[] args = msg.split(" ", 5);
        if (args.length <= 0)
            return null;
        String type = args[0];
        // no IP in these but port needs to be fixed still
        //if (type == "RESUME" || type == "ACCEPT")
        //    return msg;
        if (!(type.equals("CHAT") || type.equals("SEND"))) {
            if (ALLOW_ALL_DCC_OUT) {
                if (ctcp > 0)
                    return pfx + msg + (char) 0x01;
                return pfx + msg;
            }
        }
        if (helper == null || !helper.isEnabled())
            return null;
        if (args.length < 4)
            return null;
        String arg = args[1];
        byte[] ip;
        try {
            String ips = args[2];
            long ipl = Long.parseLong(ips);
            if (ipl < 0x01000000) {
                // "reverse/firewall DCC"
                // http://en.wikipedia.org/wiki/Direct_Client-to-Client
                // xchat sends an IP of 199 and a port of 0
                System.err.println("Reverse / Firewall DCC not supported IP = 0x" + Long.toHexString(ipl));
                return null;
            }
            ip = DataHelper.toLong(4, ipl);
        } catch (NumberFormatException nfe) {
            return null;
        }
        int cPort;
        try {
            String cp = args[3];
            cPort = Integer.parseInt(cp);
        } catch (NumberFormatException nfe) {
            return null;
        }
        if (cPort <= 0) {
            // "reverse/firewall DCC"
            // http://en.wikipedia.org/wiki/Direct_Client-to-Client
            System.err.println("Reverse / Firewall DCC not supported");
            return null;
        }
        int port = helper.newOutgoing(ip, cPort, type);
        if (port < 0)
            return null;
        StringBuilder buf = new StringBuilder(256);
        buf.append(pfx)
           .append(type).append(' ').append(arg).append(' ')
           .append(helper.getB32Hostname()).append(' ')
           .append(port);
        if (args.length > 4)
            buf.append(' ').append(args[4]);
        if (pfx.indexOf(0x01) >= 0)
            buf.append((char) 0x01);
        return buf.toString();
    }
}
