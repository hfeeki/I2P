package net.i2p.syndie.web;

import java.io.*;
import java.util.*;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletException;

// temporarily, we use our overwride, until jetty applies our patches
//import org.mortbay.servlet.MultiPartRequest;

import net.i2p.I2PAppContext;
import net.i2p.client.naming.*;
import net.i2p.data.*;
import net.i2p.syndie.*;
import net.i2p.syndie.data.*;
import net.i2p.syndie.sml.*;

/**
 * Post and preview form
 *
 */
public class PostServlet extends BaseServlet {
    public static final String PARAM_ACTION = "action";
    public static final String ACTION_CONFIRM = "confirm";
    
    public static final String PARAM_SUBJECT = "entrysubject";
    public static final String PARAM_TAGS = ThreadedHTMLRenderer.PARAM_TAGS;
    public static final String PARAM_INCLUDENAMES = "includenames";
    public static final String PARAM_TEXT = "entrytext";
    public static final String PARAM_HEADERS = "entryheaders";
    
    public static final String PARAM_PARENT = "parentURI";
    public static final String PARAM_IN_NEW_THREAD = "replyInNewThread";
    public static final String PARAM_REFUSE_REPLIES = "refuseReplies";
    
    public static final String PARAM_REMOTE_ARCHIVE = "archive";
    
    private static final String ATTR_POST_BEAN = "post";
    
    protected void renderServletDetails(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index, 
                                        int threadOffset, BlogURI visibleEntry, Archive archive) throws IOException {
        if (!user.getAuthenticated()) {
            out.write("<tr><td colspan=\"3\">You must be logged in to post</td></tr>\n");
        } else {
            PostBean post = getPostBean(user, req);
            String action = req.getParameter(PARAM_ACTION);
            if (!empty(action) && ACTION_CONFIRM.equals(action)) {
                postEntry(user, req, archive, post, out);
                post.reinitialize();
                post.setUser(user);
            } else {
                String contentType = req.getContentType();
                if (!empty(contentType) && (contentType.indexOf("boundary=") != -1)) {
                    previewPostedData(user, req, archive, contentType, post, out);
                } else {
                    displayNewForm(user, req, post, out);
                }
            }
        }
    }
    
    private void previewPostedData(User user, HttpServletRequest rawRequest, Archive archive, String contentType, PostBean post, PrintWriter out) throws IOException {
        MultiPartRequest req = new MultiPartRequest(rawRequest);
        
        if (!authAction(req.getString(PARAM_AUTH_ACTION))) {
            out.write("<tr><td colspan=\"3\"><span class=\"b_postMsgErro\">Invalid form submission... stale data?</span></td></tr>");
            return;
        }
        
        // not confirmed but they posted stuff... gobble up what they give
        // and display it as a prview (then we show the confirm form
        
        out.write("<tr><td colspan=\"3\">");
        
        //post.reinitialize();
        //post.setUser(user);
        
        boolean inNewThread = getInNewThread(req.getString(PARAM_IN_NEW_THREAD));
        boolean refuseReplies = getRefuseReplies(req.getString(PARAM_REFUSE_REPLIES));
        
        String entrySubject = req.getString(PARAM_SUBJECT);
        String entryTags = req.getString(PARAM_TAGS);
        String entryText = req.getString(PARAM_TEXT);
        String entryHeaders = req.getString(PARAM_HEADERS);
        String style = ""; //req.getString("style");
        if ( (style != null) && (style.trim().length() > 0) ) {
          if (entryHeaders == null) entryHeaders = HTMLRenderer.HEADER_STYLE + ": " + style;
          else entryHeaders = entryHeaders + '\n' + HTMLRenderer.HEADER_STYLE + ": " + style;
        }
        String replyTo = req.getString(PARAM_PARENT);
        if ( (replyTo != null) && (replyTo.trim().length() > 0) ) {
          byte r[] = Base64.decode(replyTo);
          if (r != null) {
            replyTo = new String(r, "UTF-8");
            if (!replyTo.startsWith("entry://") && !replyTo.startsWith("blog://"))
                replyTo = "entry://" + replyTo;
            if (entryHeaders == null) entryHeaders = HTMLRenderer.HEADER_IN_REPLY_TO + ": " + replyTo;
            else entryHeaders = entryHeaders + '\n' + HTMLRenderer.HEADER_IN_REPLY_TO + ": " + replyTo;
          } else {
            replyTo = null;
          }
        }
        
        if (entryTags == null) entryTags = "";
        
        if ( (entryHeaders == null) || (entryHeaders.trim().length() <= 0) )
            entryHeaders = ThreadedHTMLRenderer.HEADER_FORCE_NEW_THREAD + ": " + inNewThread + '\n' +
                           ThreadedHTMLRenderer.HEADER_REFUSE_REPLIES + ": " + refuseReplies;
        else
            entryHeaders = entryHeaders.trim() + '\n' +
                           ThreadedHTMLRenderer.HEADER_FORCE_NEW_THREAD + ": " + inNewThread + '\n' +
                           ThreadedHTMLRenderer.HEADER_REFUSE_REPLIES + ": " + refuseReplies;
        
        String includeNames = req.getString(PARAM_INCLUDENAMES);
        if ( (includeNames != null) && (includeNames.trim().length() > 0) ) {
          PetNameDB db = user.getPetNameDB();
          if (entryHeaders == null) entryHeaders = "";
          for (Iterator iter = db.getNames().iterator(); iter.hasNext(); ) {
            PetName pn = db.getByName((String)iter.next());
            if ( (pn != null) && (pn.getIsPublic()) ) {
              entryHeaders = entryHeaders.trim() + '\n' + HTMLRenderer.HEADER_PETNAME + ": " + 
                             pn.getName() + "\t" + pn.getNetwork() + "\t" + pn.getProtocol() + "\t" + pn.getLocation();
            }
          }
        }
        
        post.setSubject(entrySubject);
        post.setTags(entryTags);
        post.setText(entryText);
        post.setHeaders(entryHeaders);

        for (int i = 0; i < 32; i++) {
          String filename = req.getFilename("entryfile" + i);
          if ( (filename != null) && (filename.trim().length() > 0) ) {
            Hashtable params = req.getParams("entryfile" + i);
            String type = "application/octet-stream";
            for (Iterator iter = params.keySet().iterator(); iter.hasNext(); ) {
              String cur = (String)iter.next();
              if ("content-type".equalsIgnoreCase(cur)) {
                type = (String)params.get(cur);
                break;
              }
            }
            post.addAttachment(filename.trim(), req.getInputStream("entryfile" + i), type);
          }
        }

        post.renderPreview(out);
        out.write("<hr /><span class=\"b_postConfirm\"><form action=\"" + getPostURI() + "\" method=\"POST\">\n");
        writeAuthActionFields(out);
        out.write("Please confirm that the above is ok");
        if (BlogManager.instance().authorizeRemote(user)) { 
            out.write(", and select what additional archive you want the post transmitted to: ");
            out.write("<select class=\"b_postConfirm\" name=\"" + PARAM_REMOTE_ARCHIVE + "\">\n");
            PetNameDB db = user.getPetNameDB();
            TreeSet names = new TreeSet();
            for (Iterator iter = db.getNames().iterator(); iter.hasNext(); ) {
              String name = (String)iter.next();
              PetName pn = db.getByName(name);
              if ("syndiearchive".equals(pn.getProtocol()))
                names.add(pn.getName());
            }
            for (Iterator iter = names.iterator(); iter.hasNext(); ) {
              String name = (String)iter.next();
              out.write("<option value=\"" + HTMLRenderer.sanitizeTagParam(name) + "\">"
                        + HTMLRenderer.sanitizeString(name) + "</option>\n");
            }
            out.write("<option name=\"\">None - don't push this post anywhere</option>\n");
            
            out.write("</select><br />\n");
            out.write("If you don't push this post remotely now, you can do so later on the <a href=\"syndicate.jsp\">syndicate</a> screen ");
            out.write("by choosing an archive, verifying that they don't already have the post, and selecting which posts to push.\n");
        }
        out.write("</span><input class=\"b_postConfirm\" type=\"submit\" name=\"" + PARAM_ACTION 
                  + "\" value=\"" + ACTION_CONFIRM + "\" />\n");
        
        out.write("</form>\n");
        
        displayEditForm(user, req, post, out);
        
        out.write("</td></tr>\n");
    }
    
    private void postEntry(User user, HttpServletRequest req, Archive archive, PostBean post, PrintWriter out) throws IOException {
        if (!authAction(req)) {
            out.write("<tr><td colspan=\"3\"><span class=\"b_postMsgErro\">Invalid form submission... stale data?</span></td></tr>");
            return;
        }
        String remArchive = req.getParameter(PARAM_REMOTE_ARCHIVE);
        post.setArchive(remArchive);
        BlogURI uri = post.postEntry(); 
        if (uri != null) {
            out.write("<tr><td colspan=\"3\"><span class=\"b_postMsgOk\">Entry <a class=\"b_postOkLink\" href=\"threads.jsp?regenerateIndex=true&post=" +
                      uri.getKeyHash().toBase64() + "/" + uri.getEntryId() + "\">posted</a>!</span></td></tr>");
        } else {
            out.write("<tr><td colspan=\"3\"><span class=\"b_postMsgErro\">There was an unknown error posting the entry...</span></td></tr>");
        }
    }
    
    private void displayNewForm(User user, HttpServletRequest req, PostBean post, PrintWriter out) throws IOException {
        // logged in and not confirmed because they didn't send us anything!  
        // give 'em a new form
        
        post.reinitialize();
        post.setUser(user);
        
        String parentURI = req.getParameter(PARAM_PARENT);
        
        String subject = getParam(req, PARAM_SUBJECT);
        
        out.write("<form action=\"" + getPostURI() + "\" method=\"POST\" enctype=\"multipart/form-data\">\n");
        writeAuthActionFields(out);
        out.write("<tr><td colspan=\"3\">\n");
        out.write("<span class=\"b_postField\">Post subject:</span> ");
        out.write("<input type=\"text\" class=\"b_postSubject\" size=\"80\" name=\"" + PARAM_SUBJECT 
                  + "\" value=\"" + HTMLRenderer.sanitizeTagParam(subject) + "\" title=\"One line summary\" /><br />\n");
        out.write("<span class=\"b_postField\">Post content (in raw <a href=\"smlref.jsp\" target=\"_blank\" title=\"SML cheatsheet\">SML</a>, no headers):</span><br />\n");
        out.write("<textarea class=\"b_postText\" rows=\"6\" cols=\"80\" name=\"" + PARAM_TEXT + "\">" + getParam(req, PARAM_TEXT) + "</textarea><br />\n");
        out.write("<span class=\"b_postField\">SML post headers:</span><br />\n");
        out.write("<textarea class=\"b_postHeaders\" rows=\"2\" cols=\"80\" name=\"" + PARAM_HEADERS + "\" title=\"Most people can leave this empty\" >" + getParam(req, PARAM_HEADERS) + "</textarea><br />\n");
        
        if ( (parentURI != null) && (parentURI.trim().length() > 0) )
            out.write("<input type=\"hidden\" name=\"" + PARAM_PARENT + "\" value=\"" + parentURI + "\" />\n");

        out.write(" Tags: ");
        BaseServlet.writeTagField(user, getParam(req, PARAM_TAGS), out, "Optional tags to categorize your post", "No tags", false);
        //<input type=\"text\" size=\"10\" name=\"" + PARAM_TAGS + "\" value=\"" + getParam(req, PARAM_TAGS) + "\" title=\"Optional tags to categorize your response\" /><br />\n");
        out.write("<br />\n");
        
        boolean inNewThread = getInNewThread(req);
        boolean refuseReplies = getRefuseReplies(req);

        out.write("In a new thread? <input type=\"checkbox\" value=\"true\" name=\"" + PARAM_IN_NEW_THREAD + 
                  (inNewThread ? "\" checked=\"true\" " : "\" " ) 
                  + " title=\"If true, this will fork a new top level thread\" /><br />\n");
        out.write("Refuse replies? <input type=\"checkbox\" value=\"true\" name=\"" + PARAM_REFUSE_REPLIES + 
                  (refuseReplies ? "\" checked=\"true\" " : "\" " ) 
                  + " title=\"If true, only you will be able to reply to the post\" /><br />\n");
        
        out.write("<span class=\"b_postField\">Include public names?</span> ");
        out.write("<input class=\"b_postNames\" type=\"checkbox\" name=\"" + PARAM_INCLUDENAMES 
                  + "\" value=\"true\" title=\"If true, everything marked 'public' in your addressbook is shared\" /><br />\n");
        
        out.write(ATTACHMENT_FIELDS);

        out.write("<hr />\n");
        out.write("<input class=\"b_postPreview\" type=\"submit\" name=\"Post\" value=\"Preview...\" /> ");
        out.write("<input class=\"b_postReset\" type=\"reset\" value=\"Cancel\" />\n");
        
        if (parentURI != null) {
            out.write("<hr /><span id=\"parentText\" class=\"b_postParent\">");
            String decoded = DataHelper.getUTF8(Base64.decode(parentURI));
            post.renderReplyPreview(out, "entry://" + decoded);
            out.write("</span><hr/>\n");
        } 
        
        out.write("</td></tr>\n");
        out.write("</form>\n");
    }
    
    private void displayEditForm(User user, MultiPartRequest req, PostBean post, PrintWriter out) throws IOException {
        String parentURI = req.getString(PARAM_PARENT);
        
        String subject = getParam(req, PARAM_SUBJECT);
        
        out.write("<hr />\n");
        out.write("<form action=\"" + getPostURI() + "\" method=\"POST\" enctype=\"multipart/form-data\">\n");
        writeAuthActionFields(out);
        out.write("<tr><td colspan=\"3\">\n");
        out.write("<span class=\"b_postField\">Post subject:</span> ");
        out.write("<input type=\"text\" class=\"b_postSubject\" size=\"80\" name=\"" + PARAM_SUBJECT 
                  + "\" value=\"" + HTMLRenderer.sanitizeTagParam(subject) + "\" /><br />\n");
        out.write("<span class=\"b_postField\">Post content (in raw <a href=\"smlref.jsp\" target=\"_blank\" title=\"SML cheatsheet\">SML</a>, no headers):</span><br />\n");
        out.write("<textarea class=\"b_postText\" rows=\"6\" cols=\"80\" name=\"" + PARAM_TEXT + "\">" + getParam(req, PARAM_TEXT) + "</textarea><br />\n");
        out.write("<span class=\"b_postField\">SML post headers:</span><br />\n");
        out.write("<textarea class=\"b_postHeaders\" rows=\"3\" cols=\"80\" name=\"" + PARAM_HEADERS + "\">" + getParam(req, PARAM_HEADERS) + "</textarea><br />\n");
        
        if ( (parentURI != null) && (parentURI.trim().length() > 0) )
            out.write("<input type=\"hidden\" name=\"" + PARAM_PARENT + "\" value=\"" + parentURI + "\" />\n");

        out.write(" Tags: ");
        //<input type=\"text\" size=\"10\" name=\"" + PARAM_TAGS + "\" value=\"" + getParam(req, PARAM_TAGS) + "\" /><br />\n");
        out.write(" Tags: ");
        BaseServlet.writeTagField(user, getParam(req, PARAM_TAGS), out, "Optional tags to categorize your post", "No tags", false);
        out.write("<br />\n");
        
        boolean inNewThread = getInNewThread(req);
        boolean refuseReplies = getRefuseReplies(req);

        out.write("In a new thread? <input type=\"checkbox\" value=\"true\" name=\"" + PARAM_IN_NEW_THREAD + 
                  (inNewThread ? "\" checked=\"true\" " : "\" " ) + " /><br />\n");
        out.write("Refuse replies? <input type=\"checkbox\" value=\"true\" name=\"" + PARAM_REFUSE_REPLIES + 
                  (refuseReplies ? "\" checked=\"true\" " : "\" " ) + " /><br />\n");
        
        out.write("<span class=\"b_postField\">Include public names?</span> ");
        out.write("<input class=\"b_postNames\" type=\"checkbox\" name=\"" + PARAM_INCLUDENAMES 
                  + "\" value=\"true\" /><br />\n");
        
        int newCount = 0;
        for (int i = 0; i < 32 && newCount < 3; i++) {
          String filename = req.getFilename("entryfile" + i);
          if ( (filename != null) && (filename.trim().length() > 0) ) {
              out.write("<span class=\"b_postField\">Attachment " + i + ":</span> ");
              out.write(HTMLRenderer.sanitizeString(filename));
              out.write("<br />");
          } else {
              out.write("<span class=\"b_postField\">Attachment " + i + ":</span> ");
              out.write("<input class=\"b_postField\" type=\"file\" name=\"entryfile" + i + "\" ");
              out.write("/><br />");
              newCount++;
          }
        }

        out.write("<hr />\n");
        out.write("<input class=\"b_postPreview\" type=\"submit\" name=\"Post\" value=\"Preview...\" /> ");
        out.write("<input class=\"b_postReset\" type=\"reset\" value=\"Cancel\" />\n");
        
        out.write("</form>\n");
    }
    
    private boolean getInNewThread(HttpServletRequest req) {
        return getInNewThread(req.getParameter(PARAM_IN_NEW_THREAD));
    }
    private boolean getInNewThread(MultiPartRequest req) {
        return getInNewThread(getParam(req, PARAM_IN_NEW_THREAD));
    }
    private boolean getInNewThread(String val) {
        boolean rv = false;
        String inNewThread = val;
        if ( (inNewThread != null) && (Boolean.valueOf(inNewThread).booleanValue()) )
            rv = true;
        return rv;
    }
    private boolean getRefuseReplies(HttpServletRequest req) {
        return getRefuseReplies(req.getParameter(PARAM_REFUSE_REPLIES));
    }
    private boolean getRefuseReplies(MultiPartRequest req) {
        return getRefuseReplies(getParam(req, PARAM_REFUSE_REPLIES));
    }
    private boolean getRefuseReplies(String val) {
        boolean rv = false;
        String refuseReplies = val;
        if ( (refuseReplies != null) && (Boolean.valueOf(refuseReplies).booleanValue()) )
            rv = true;
        return rv;
    }
    
    private PostBean getPostBean(User user, HttpServletRequest req) {
        PostBean bean = (PostBean)req.getSession().getAttribute(ATTR_POST_BEAN);
        if (bean == null) {
            bean = new PostBean();
            req.getSession().setAttribute(ATTR_POST_BEAN, bean);
        }
        bean.setUser(user);
        return bean;
    }
    
    private String getParam(HttpServletRequest req, String param) {
        String val = req.getParameter(param);
        if (val == null) val = "";
        return val;
    }
    private String getParam(MultiPartRequest req, String param) {
        String val = req.getString(param);
        if (val == null) return "";
        return val;
    }
    
    private static final String ATTACHMENT_FIELDS = ""
        + "<span class=\"b_postField\">Attachment 0:</span> <input class=\"b_postField\" type=\"file\" name=\"entryfile0\" /><br />"
        + "<span class=\"b_postField\">Attachment 1:</span> <input class=\"b_postField\" type=\"file\" name=\"entryfile1\" /><br />"
        + "<span class=\"b_postField\">Attachment 2:</span> <input class=\"b_postField\" type=\"file\" name=\"entryfile2\" /><br />"
        + "<span class=\"b_postField\">Attachment 3:</span> <input class=\"b_postField\" type=\"file\" name=\"entryfile3\" /><br />\n";

    protected String getTitle() { return "Syndie :: Post new content"; }
}
