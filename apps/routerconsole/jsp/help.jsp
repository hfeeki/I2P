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
hmm.  we should probably have some help text here.<br />

<h2>Legal stuff</h2>
The I2P router (router.jar) and SDK (i2p.jar) are almost entirely public domain, with 
a few notable exceptions:<ul>
<li>ElGamal, DSA, and SHA256 code, under the BSD license, written by TheCrypto</li>
<li>AES code, under the Cryptix (MIT) license, written by the Cryptix team</li>
<li>SNTP code, under the BSD license, written by Adam Buckley</li>
<li>The rest is outright public domain, written by jrandom, mihi, hypercubus, oOo, ugha, duck, and shendaras</li>
</ul>

<p>On top of the I2P router are a series of client applications, each with their own set of
licenses and dependencies.  This webpage is being served as part of the I2P routerconsole
client application, which is built off a trimmed down <a href="http://jetty.mortbay.com/jetty/index.html">Jetty</a>
instance (trimmed down, as in, we do not include the demo apps or other add-ons, and we simplify configuration), 
allowing you to deploy standard JSP/Servlet web applications into your router.  Jetty in turn makes use of 
Apache's javax.servlet (javax.servlet.jar) implementation, as well as their xerces-j XML parser (xerces.jar).
Their XML parser requires the Sun XML APIs (JAXP) which is included in binary form (xml-apis.jar) as required 
by their binary code license.  This product includes software developed by the Apache Software Foundation 
(http://www.apache.org/). </p>

<p>Another application you can see on this webpage is <a href="http://www.i2p.net/i2ptunnel">I2PTunnel</a>
(your <a href="/i2ptunnel/" target="_blank">web interface</a>) - a GPL'ed application written by mihi that
lets you tunnel normal TCP/IP traffic over I2P (such as the eepproxy and the irc proxy).</p>

<p>The router by default also includes human's public domain <a href="http://www.i2p.net/sam">SAM</a> bridge,
which other client applications (such as aum's <a href="http://stasher.i2p/">stasher</a>) can use.  For 
details on other applications available, as well as their licenses, please see the 
<a href="http://www.i2p.net/licenses">license policy</a>.  Source for the I2P code and most bundled
client applications can be found on our <a href="http://www.i2p.net/download">download page</a>, and is
in <a href="http://www.i2p.net/cvs">cvs</a>.</p>
</div>

</body>
</html>
