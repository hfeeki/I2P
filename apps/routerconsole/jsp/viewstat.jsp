<%
boolean rendered = false;
String templateFile = request.getParameter("template");
if (templateFile != null) {
  java.io.OutputStream cout = response.getOutputStream();
  response.setContentType("image/png");
  rendered = net.i2p.router.web.StatSummarizer.instance().renderPng(cout, templateFile);  
}
net.i2p.stat.Rate rate = null;
String stat = request.getParameter("stat");
String period = request.getParameter("period");
net.i2p.stat.RateStat rs = net.i2p.I2PAppContext.getGlobalContext().statManager().getRate(stat);
if ( !rendered && (rs != null)) {
  long per = -1;
  try { 
    per = Long.parseLong(period); 
    rate = rs.getRate(per);
    if (rate != null) {
      java.io.OutputStream cout = response.getOutputStream();
      String format = request.getParameter("format");
      if ("xml".equals(format)) {
        response.setContentType("text/xml");
        rendered = net.i2p.router.web.StatSummarizer.instance().getXML(rate, cout);
      } else {
        response.setContentType("image/png");
        int width = -1;
        int height = -1;
        int periodCount = -1;
        String str = request.getParameter("width");
        if (str != null) try { width = Integer.parseInt(str); } catch (NumberFormatException nfe) {}
        str = request.getParameter("height");
        if (str != null) try { height = Integer.parseInt(str); } catch (NumberFormatException nfe) {}
        str = request.getParameter("periodCount");
        if (str != null) try { periodCount = Integer.parseInt(str); } catch (NumberFormatException nfe) {}
        boolean hideLegend = Boolean.valueOf(""+request.getParameter("hideLegend")).booleanValue();
        boolean hideGrid = Boolean.valueOf(""+request.getParameter("hideGrid")).booleanValue();
        boolean hideTitle = Boolean.valueOf(""+request.getParameter("hideTitle")).booleanValue();
        boolean showEvents = Boolean.valueOf(""+request.getParameter("showEvents")).booleanValue();
        boolean showCredit = true;
        if (request.getParameter("showCredit") != null)
          showCredit = Boolean.valueOf(""+request.getParameter("showCredit")).booleanValue();
        rendered = net.i2p.router.web.StatSummarizer.instance().renderPng(rate, cout, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount, showCredit);
      }
      if (rendered)
        cout.close();
      //System.out.println("Rendered period " + per + " for the stat " + stat + "? " + rendered);
    }
  } catch (NumberFormatException nfe) {}
}
if (!rendered) { 
  response.sendError(404, "That stat is not available");
}
%>