<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - help</title>
<link rel="stylesheet" href="default.css" type="text/css" />
</head><body>

<%@include file="nav.jsp" %>
<%@include file="summary.jsp" %>

<div class="main" id="main">
<h2>Help</h2>
Sorry, there's not much help text here yet, so also check out the
<a href="http://www.i2p2.i2p/faq.html">FAQ on www.i2p2.i2p</a>
or the
<a href="http://www.i2p2.i2p/faq_de.html">Deutsch FAQ</a>.
You may also try the
<a href="http://forum.i2p/">forum</a>
or IRC.
<br />

<h2>Summary Bar Information</h2>
Many of the stats on the summary bar may be
<a href="configstats.jsp">configured</a> to be
<a href="graphs.jsp">graphed</a> for further analysis.

<h3>General</h3>
<ul>
<li><b>Ident:</b>
The first four characters (24 bits) of your 44-character (256-bit) Base64 router hash.
The full hash is shown on your <a href="netdb.jsp?r=.">router info page</a>.
Never reveal this to anyone, as your router info contains your IP.
<li><b>Version:</b>
The version of the I2P software you are running.
<li><b>Now:</b>
The current time (UTC) and the skew, if any. I2P requires your computer's time be accurate.
If the skew is more than a few seconds, please correct the problem by adjusting
your computer's time.
<li><b>Reachability:</b>
The router's view of whether it can be contacted by other routers.
Further information is on the <a href="config.jsp#help">configuration page</a>.
</ul>

<h3>Peers</h3>
<ul>
<li><b>Active:</b>
The first number is the number of peers you've sent or received a message from in the last few minutes.
This may range from 8-10 to several hundred, depending on your total bandwidth,
shared bandwidth, and locally-generated traffic.
The second number is the number of peers seen in the last hour or so.
Do not be concerned if these numbers vary widely.
<a href="configstats.jsp#router.activePeers">Enable graphing</a>
<li><b>Fast:</b>
This is the number of peers you use for building client tunnels. It is generally in the
range 8-15. Your fast peers are shown on the <a href="profiles.jsp">profiles page</a>.
<a href="configstats.jsp#router.fastPeers">Enable graphing</a>
<li><b>High Capacity:</b>
This is the number of peers you use for building some of your exploratory tunnels. It is generally in the
range 8-25. The fast peers are included in the high capacity tier.
Your high capacity peers are shown on the <a href="profiles.jsp">profiles page</a>.
<a href="configstats.jsp#router.highCapacityPeers">Enable graphing</a>
<li><b>Well Integrated:</b>
This is the number of peers you use for network database inquiries.
These are usually the "floodfill" peers.
Your well integrated peers are shown on the bottom of the <a href="profiles.jsp">profiles page</a>.
<li><b>Known:</b>
This is the total number of routers you know about.
They are listed on the <a href="netdb.jsp">network database page</a>.
This may range from under 100 to 1000 or more.
This number is not the total size of the network;
it may vary widely depending on your total bandwidth,
shared bandwidth, and locally-generated traffic.
I2P does not require a router to know every other router.
</ul>

<h3>Bandwidth in/out</h3>
Should be self-explanatory. All values are in bytes per second, not bits per second.
Change your bandwidth limits on the <a href="config.jsp#help">configuration page</a>.
Bandwidth is <a href="graphs.jsp">graphed</a> by default.

<h3>Local destinations</h3>
The local applications connecting through your router.
These may be clients started through <a href="i2ptunnel/index.jsp">I2PTunnel</a>
or external programs connecting through SAM, BOB, or directly to I2CP.

<h3>Tunnels in/out</h3>
The actual tunnels are shown on the <a href="tunnels.jsp">the tunnels page</a>.
<ul>
<li><b>Exploratory:</b>
Tunnels built by your router and used for communication with the floodfill peers,
building new tunnels, and testing existing tunnels.
<li><b>Client:</b>
Tunnels built by your router for each client's use.
<li><b>Participating:</b>
Tunnels built by other routers through your router.
This may vary widely depending on network demand, your
shared bandwidth, and amount of locally-generated traffic.
The recommended method for limiting participating tunnels is
to change your share percentage on the <a href="config.jsp#help">configuration page</a>.
You may also limit the total number by setting <tt>router.maxParticipatingTunnels=nnn</tt> on
the <a href="configadvanced.jsp">advanced configuration page</a>.
<a href="configstats.jsp#tunnel.participatingTunnels">Enable graphing</a>
</ul>

<h3>Congestion</h3>
Some basic indications of router overload.
<ul>
<li><b>Job lag:</b>
How long jobs are waiting before execution. The job queue is listed on the <a href="jobs.jsp">jobs page</a>.
Unfortunately, there are several other job queues in the router that may be congested,
and their status is not available in the router console.
The job lag should generally be zero.
If it is consistently higher than 500ms, your computer is very slow, or the
router has serious problems.
<a href="configstats.jsp#jobQueue.jobLag">Enable graphing</a>
<li><b>Message delay:</b>
How long an outbound message waits in the queue.
This should generally be a few hundred milliseconds or less.
If it is consistently higher than 1000ms, your computer is very slow,
or you should adjust your bandwidth limits, or your (bittorrent?) clients
may be sending too much data and should have their transmit bandwidth limit reduced.
<a href="configstats.jsp#transport.sendProcessingTime">Enable graphing</a> (transport.sendProcessingTime)
<li><b>Tunnel lag:</b>
This is the round trip time for a tunnel test, which sends a single message
out a client tunnel and in an exploratory tunnel, or vice versa.
It should usually be less than 5 seconds.
If it is consistently higher than that, your computer is very slow,
or you should adjust your bandwidth limits, or there are network problems.
<a href="configstats.jsp#tunnel.testSuccessTime">Enable graphing</a> (tunnel.testSuccessTime)
<li><b>Handle backlog:</b>
This is the number of pending requests from other routers to build a
participating tunnel through your router.
It should usually be close to zero.
If it is consistently high, your computer is too slow,
and you should reduce your share bandwidth limits.
<li><b>Accepting/Rejecting:</b>
Your routers' status on accepting or rejecting
requests from other routers to build a
participating tunnel through your router.
Your router may accept all requests, accept or reject a percentage of requests,
or reject all requests for a number of reasons, to control
the bandwidth and CPU demands and maintain capacity for
local clients.
</ul>

<h2>Legal stuff</h2>
The I2P router (router.jar) and SDK (i2p.jar) are almost entirely public domain, with 
a few notable exceptions:<ul>
<li>ElGamal and DSA code, under the BSD license, written by TheCrypto</li>
<li>SHA256 and HMAC-SHA256, under the MIT license, written by the Legion of the Bouncycastle</li>
<li>AES code, under the Cryptix (MIT) license, written by the Cryptix team</li>
<li>SNTP code, under the BSD license, written by Adam Buckley</li>
<li>The rest is outright public domain, written by jrandom, mihi, hypercubus, oOo, 
    ugha, duck, shendaras, and others.</li>
</ul>

<p>On top of the I2P router are a series of client applications, each with their own set of
licenses and dependencies.  This webpage is being served as part of the I2P routerconsole
client application, which is built off a trimmed down <a href="http://jetty.mortbay.com/jetty/index.html">Jetty</a>
instance (trimmed down, as in, we do not include the demo apps or other add-ons, and we simplify configuration), 
allowing you to deploy standard JSP/Servlet web applications into your router.  Jetty in turn makes use of 
Apache's javax.servlet (javax.servlet.jar) implementation.
This product includes software developed by the Apache Software Foundation 
(http://www.apache.org/). </p>

<p>Another application you can see on this webpage is <a href="http://www.i2p2.i2p/i2ptunnel">I2PTunnel</a>
(your <a href="i2ptunnel/" target="_blank">web interface</a>) - a GPL'ed application written by mihi that
lets you tunnel normal TCP/IP traffic over I2P (such as the eepproxy and the irc proxy).  There is also a
<a href="http://susi.i2p/">susimail</a> web based mail client <a href="susimail/susimail">available</a> on
the console, which is a GPL'ed application written by susi23.  The addressbook application, written by 
<a href="http://ragnarok.i2p/">Ragnarok</a> helps maintain your hosts.txt files (see ./addressbook/ for
more information).</p>

<p>The router by default also includes human's public domain <a href="http://www.i2p2.i2p/sam">SAM</a> bridge,
which other client applications (such the <a href="http://duck.i2p/i2p-bt/">bittorrent port</a>) can use.  
There is also an optimized library for doing large number calculations - jbigi - which in turn uses the 
LGPL licensed <a href="http://swox.com/gmp/">GMP</a> library, tuned for various PC architectures.  Launchers for windows users are built with <a href="http://launch4j.sourceforge.net/">Launch4J</a>, and the installer is built with <a href="http://www.izforge.com/izpack/">IzPack</a>.  For 
details on other applications available, as well as their licenses, please see the 
<a href="http://www.i2p2.i2p/licenses">license policy</a>.  Source for the I2P code and most bundled
client applications can be found on our <a href="http://www.i2p2.i2p/download">download page</a>.
.</p>

<h2>Release history</h2>
 <jsp:useBean class="net.i2p.router.web.ContentHelper" id="contenthelper" scope="request" />
 <jsp:setProperty name="contenthelper" property="page" value="history.txt" />
 <jsp:setProperty name="contenthelper" property="maxLines" value="500" />
 <jsp:setProperty name="contenthelper" property="startAtBeginning" value="true" />
 <jsp:getProperty name="contenthelper" property="textContent" />
 
 <p>
 A more complete list of changes can be found 
 in the history.txt file in your i2p directory.
 </p>
</div>

</body>
</html>
