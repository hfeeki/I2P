<%
/*
 * USE CAUTION WHEN EDITING
 * Trailing whitespace OR NEWLINE on the last line will cause
 * IllegalStateExceptions !!!
 *
 * Do not tag this file for translation.
 */
response.setContentType("text/plain");
String base = net.i2p.I2PAppContext.getGlobalContext().getBaseDir().getAbsolutePath();
net.i2p.util.FileUtil.readFile("history.txt", base, response.getOutputStream());
%>