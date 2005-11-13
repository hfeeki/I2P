package net.i2p.syndie.sml;

import java.io.*;
import java.text.*;
import java.util.*;
import net.i2p.I2PAppContext;
import net.i2p.client.naming.PetName;
import net.i2p.data.*;
import net.i2p.syndie.*;
import net.i2p.syndie.data.*;
import net.i2p.syndie.web.*;
import net.i2p.util.Log;

/**
 *
 */
public class ThreadedHTMLRenderer extends HTMLRenderer {
    private Log _log;
    private String _baseURI;
    
    public ThreadedHTMLRenderer(I2PAppContext ctx) {
        super(ctx);
        _log = ctx.logManager().getLog(ThreadedHTMLRenderer.class);
    }
    
    /** what, if any, post should be rendered */
    public static final String PARAM_VIEW_POST = "post";
    /** what, if any, thread should be rendered in its entirety */
    public static final String PARAM_VIEW_THREAD = "thread";
    /** what post should be visible in the nav tree */
    public static final String PARAM_VISIBLE = "visible";
    public static final String PARAM_ADD_TO_GROUP_LOCATION = "addLocation";
    public static final String PARAM_ADD_TO_GROUP_NAME = "addGroup";
    /** name of the bookmarked entry to remove */
    public static final String PARAM_REMOVE_FROM_GROUP_NAME = "removeName";
    /** group to remove from the bookmarked entry, or if blank, remove the entry itself */
    public static final String PARAM_REMOVE_FROM_GROUP = "removeGroup";
    /** index into the nav tree to start displaying */
    public static final String PARAM_OFFSET = "offset";
    public static final String PARAM_TAGS = "tags";
    public static final String PARAM_AUTHOR = "author";
    // parameters for editing one's profile
    public static final String PARAM_PROFILE_NAME = "profileName";
    public static final String PARAM_PROFILE_DESC = "profileDesc";
    public static final String PARAM_PROFILE_URL = "profileURL";
    public static final String PARAM_PROFILE_OTHER = "profileOther";

    public static final String PARAM_ARCHIVE = "archiveLocation";
    
    public static String getFilterByTagLink(String uri, ThreadNode node, User user, String tag, String author) { 
        StringBuffer buf = new StringBuffer(64);
        buf.append(uri).append('?');
        if (node != null) {
            buf.append(PARAM_VIEW_POST).append('=');
            buf.append(node.getEntry().getKeyHash().toBase64()).append('/');
            buf.append(node.getEntry().getEntryId()).append('&');
        }
        
        if (!empty(tag))
            buf.append(PARAM_TAGS).append('=').append(tag).append('&');
        
        if (!empty(author))
            buf.append(PARAM_AUTHOR).append('=').append(author).append('&');
        
        return buf.toString();
    }
    
    public static String getNavLink(String uri, String viewPost, String viewThread, String tags, String author, int offset) {
        StringBuffer buf = new StringBuffer(64);
        buf.append(uri);
        buf.append('?');
        if (!empty(viewPost))
            buf.append(PARAM_VIEW_POST).append('=').append(viewPost).append('&');
        else if (!empty(viewThread))
            buf.append(PARAM_VIEW_THREAD).append('=').append(viewThread).append('&');
        
        if (!empty(tags))
            buf.append(PARAM_TAGS).append('=').append(tags).append('&');
        
        if (!empty(author))
            buf.append(PARAM_AUTHOR).append('=').append(author).append('&');
        
        buf.append(PARAM_OFFSET).append('=').append(offset).append('&');
        
        return buf.toString();
    }
    
    public static String getViewPostLink(String uri, ThreadNode node, User user, boolean isPermalink, 
                                         String offset, String tags, String author) {
        StringBuffer buf = new StringBuffer(64);
        buf.append(uri);
        if (node.getChildCount() > 0) {
            buf.append('?').append(PARAM_VISIBLE).append('=');
            ThreadNode child = node.getChild(0);
            buf.append(child.getEntry().getKeyHash().toBase64()).append('/');
            buf.append(child.getEntry().getEntryId()).append('&');
        } else {
            buf.append('?').append(PARAM_VISIBLE).append('=');
            buf.append(node.getEntry().getKeyHash().toBase64()).append('/');
            buf.append(node.getEntry().getEntryId()).append('&');
        }
        buf.append(PARAM_VIEW_POST).append('=');
        buf.append(node.getEntry().getKeyHash().toBase64()).append('/');
        buf.append(node.getEntry().getEntryId()).append('&');
        
        if (!isPermalink) {
            if (!empty(offset))
                buf.append(PARAM_OFFSET).append('=').append(offset).append('&');
            if (!empty(tags))
                buf.append(PARAM_TAGS).append('=').append(tags).append('&');
            if (!empty(author))
                buf.append(PARAM_AUTHOR).append('=').append(author).append('&');
        }
        
        return buf.toString();
    }
    
    
    private static final boolean empty(String val) { return (val == null) || (val.trim().length() <= 0); }
    
    public void render(User user, Writer out, Archive archive, BlogURI post, 
                       boolean inlineReply, ThreadIndex index, String baseURI,
                       String offset, String requestTags, String filteredAuthor) throws IOException {
        EntryContainer entry = archive.getEntry(post);
        if (entry == null) return;
        _entry = entry;
   
        _baseURI = baseURI;
        _user = user;
        _out = out;
        _archive = archive;
        _cutBody = false;
        _showImages = true;
        _headers = new HashMap();
        _bodyBuffer = new StringBuffer(1024);
        _postBodyBuffer = new StringBuffer(1024);
        _addresses = new ArrayList();
        _links = new ArrayList();
        _blogs = new ArrayList();
        _archives = new ArrayList();
        
        _parser.parse(entry.getEntry().getText(), this);
        
        out.write("<!-- body begin -->\n");
        out.write("<!-- body meta begin -->\n");
        out.write("<tr class=\"postMeta\" id=\"" + post.toString() + "\">\n");
        
        String subject = (String)_headers.get(HTMLRenderer.HEADER_SUBJECT);
        if (subject == null)
            subject = "";
        out.write(" <td colspan=\"3\" class=\"postMetaSubject\" id=\"bodySubject\">");
        out.write(subject);
        out.write("</td></tr>\n");
        out.write("<tr class=\"postMeta\"><td colspan=\"3\" class=\"postMetaLink\">\n");
        out.write("<a href=\"");
        out.write(getMetadataURL(post.getKeyHash()));
        out.write("\" title=\"View the author's profile\">");
        
        String author = null;
        PetName pn = user.getPetNameDB().getByLocation(post.getKeyHash().toBase64());
        if (pn == null) {
            BlogInfo info = archive.getBlogInfo(post.getKeyHash());
            if (info != null)
                author = info.getProperty(BlogInfo.NAME);
        } else {
            author = pn.getName();
        }
        if ( (author == null) || (author.trim().length() <= 0) )
            author = post.getKeyHash().toBase64().substring(0,6);
        
        ThreadNode node = index.getNode(post);
        
        out.write(author);
        out.write("</a> @ ");
        out.write(getEntryDate(post.getEntryId()));
        
        Collection tags = node.getTags();
        if ( (tags != null) && (tags.size() > 0) ) {
            out.write("\nTags: \n");
            for (Iterator tagIter = tags.iterator(); tagIter.hasNext(); ) {
                String tag = (String)tagIter.next();
                out.write("<a href=\"");
                out.write(getFilterByTagLink(baseURI, node, user, tag, filteredAuthor));
                out.write("\" title=\"Filter threads to only include posts tagged as '");
                out.write(tag);
                out.write("'\">");
                out.write(" " + tag);
                out.write("</a>\n");
            }
        }
        
        out.write("\n<a href=\"");
        out.write(getViewPostLink(baseURI, node, user, true, offset, requestTags, filteredAuthor));
        out.write("\" title=\"Select a shareable link directly to this post\">permalink</a>\n");
        
        out.write("</td>\n</tr>\n");
        out.write("<!-- body meta end -->\n");
        out.write("<!-- body post begin -->\n");
        out.write("<tr class=\"postData\">\n");
        out.write("<td colspan=\"3\">\n");
        out.write(_bodyBuffer.toString());
        out.write("</td>\n</tr>\n");
        out.write("<!-- body post end -->\n");
        out.write("<!-- body details begin -->\n");
        out.write(_postBodyBuffer.toString());
/*
"<tr class=\"postDetails\">\n" +
" <form action=\"viewattachment.jsp\" method=\"GET\">\n" +
" <td colspan=\"3\">\n" +
" External links:\n" +
"  <a href=\"external.jsp?foo\" title=\"View foo.i2p\">http://foo.i2p/</a>\n" +
"  <a href=\"external.jsp?bar\" title=\"View bar.i2p\">http://bar.i2p/</a>\n" +
" <br />\n" +
" Attachments: <select name=\"attachment\">\n" +
"  <option value=\"0\">sampleRawSML.sml: Sample SML file with headers (4KB, type text/plain)</option>\n" +
" </select> <input type=\"submit\" name=\"action\" value=\"Download\" />\n" +
" <br /><a href=\"\" title=\"Expand the entire thread\">Full thread</a>\n" +
" <a href=\"\" title=\"Previous post in the thread\">Prev in thread</a> \n" +
" <a href=\"\" title=\"Next post in the thread\">Next in thread</a> \n" +
" </td>\n" +
" </form>\n" +
"</tr>\n" +
 */
        out.write("<!-- body details end -->\n");
        if (inlineReply && user.getAuthenticated() ) {
            String refuseReplies = (String)_headers.get(HTMLRenderer.HEADER_REFUSE_REPLIES);
            // show the reply form if we are the author or replies have not been explicitly rejected
            if ( (user.getBlog().equals(post.getKeyHash())) ||
                 (refuseReplies == null) || (!Boolean.valueOf(refuseReplies).booleanValue()) ) {
                out.write("<!-- body reply begin -->\n");
                out.write("<form action=\"post.jsp\" method=\"POST\" enctype=\"multipart/form-data\">\n");
                out.write("<input type=\"hidden\" name=\"" + PostServlet.PARAM_PARENT + "\" value=\"");
                out.write(Base64.encode(post.toString()));
                out.write("\" />");
                out.write("<input type=\"hidden\" name=\"" + PostServlet.PARAM_SUBJECT + "\" value=\"");
                if (subject.indexOf("re: ") == -1)
                    out.write("re: ");
                out.write(HTMLRenderer.sanitizeTagParam(subject));
                out.write("\" />");
                out.write("<tr class=\"postReply\">\n");
                out.write("<td colspan=\"3\">Reply: (<a href=\"smlref.jsp\" title=\"SML cheatsheet\" target=\"_blank\">SML reference</a>)</td>\n</tr>\n");
                out.write("<tr class=\"postReplyText\">\n");
                out.write("<td colspan=\"3\"><textarea name=\"" + PostServlet.PARAM_TEXT + "\" rows=\"2\" cols=\"100\"></textarea></td>\n");
                out.write("</tr>\n");
                out.write("<tr class=\"postReplyOptions\">\n");
                out.write(" <td colspan=\"3\">\n");
                out.write(" <input type=\"submit\" value=\"Preview...\" name=\"Post\" />\n");
                out.write(" Tags: <input type=\"text\" size=\"10\" name=\"" + PostServlet.PARAM_TAGS + "\" />\n");
                out.write(" in a new thread? <input type=\"checkbox\" name=\"" + PostServlet.PARAM_IN_NEW_THREAD + "\" value=\"true\" />\n");
                out.write(" refuse replies? <input type=\"checkbox\" name=\"" + PostServlet.PARAM_REFUSE_REPLIES + "\" value=\"true\" />\n");
                out.write(" attachment: <input type=\"file\" name=\"entryfile0\" />\n");
                out.write(" </td>\n</tr>\n</form>\n");
                out.write("<!-- body reply end -->\n");
            }
        }
        out.write("<!-- body end -->\n");
    }
    
    public void receiveEnd() { 
        _postBodyBuffer.append("<tr class=\"postDetails\">\n");
        _postBodyBuffer.append(" <form action=\"viewattachment.jsp\" method=\"GET\">\n");
        _postBodyBuffer.append(" <td colspan=\"3\" valign=\"top\" align=\"left\">\n");
        
        _postBodyBuffer.append("<input type=\"hidden\" name=\"").append(ArchiveViewerBean.PARAM_BLOG);
        _postBodyBuffer.append("\" value=\"");
        if (_entry != null)
            _postBodyBuffer.append(Base64.encode(_entry.getURI().getKeyHash().getData()));
        else
            _postBodyBuffer.append("unknown");
        _postBodyBuffer.append("\" />\n");
        _postBodyBuffer.append("<input type=\"hidden\" name=\"").append(ArchiveViewerBean.PARAM_ENTRY);
        _postBodyBuffer.append("\" value=\"");
        if (_entry != null) 
            _postBodyBuffer.append(_entry.getURI().getEntryId());
        else
            _postBodyBuffer.append("unknown");
        _postBodyBuffer.append("\" />\n");
        
        //_postBodyBuffer.append("<td colspan=\"2\" valign=\"top\" align=\"left\" ").append(getClass("summDetail")).append(" >\n");

        if ( (_entry != null) && (_entry.getAttachments() != null) && (_entry.getAttachments().length > 0) ) {
            _postBodyBuffer.append(getSpan("summDetailAttachment")).append("Attachments:</span> ");
            _postBodyBuffer.append("<select ").append(getClass("summDetailAttachmentId")).append(" name=\"").append(ArchiveViewerBean.PARAM_ATTACHMENT).append("\">\n");
            for (int i = 0; i < _entry.getAttachments().length; i++) {
                _postBodyBuffer.append("<option value=\"").append(i).append("\">");
                Attachment a = _entry.getAttachments()[i];
                _postBodyBuffer.append(sanitizeString(a.getName()));
                if ( (a.getDescription() != null) && (a.getDescription().trim().length() > 0) ) {
                    _postBodyBuffer.append(": ");
                    _postBodyBuffer.append(sanitizeString(a.getDescription()));
                }
                _postBodyBuffer.append(" (").append(a.getDataLength()/1024).append("KB");
                _postBodyBuffer.append(", type ").append(sanitizeString(a.getMimeType())).append(")</option>\n");
            }
            _postBodyBuffer.append("</select>\n");
            _postBodyBuffer.append("<input ").append(getClass("summDetailAttachmentDl")).append(" type=\"submit\" value=\"Download\" name=\"Download\" /><br />\n");
        }

        if (_blogs.size() > 0) {
            _postBodyBuffer.append(getSpan("summDetailBlog")).append("Blog references:</span>");
            for (int i = 0; i < _blogs.size(); i++) {
                Blog b = (Blog)_blogs.get(i);
                _postBodyBuffer.append("<a ").append(getClass("summDetailBlogLink")).append(" href=\"");
                boolean expanded = (_user != null ? _user.getShowExpanded() : false);
                boolean images = (_user != null ? _user.getShowImages() : false);
                _postBodyBuffer.append(getPageURL(new Hash(Base64.decode(b.hash)), b.tag, b.entryId, -1, -1, expanded, images));
                _postBodyBuffer.append("\">").append(sanitizeString(b.name)).append("</a> ");
            }
            _postBodyBuffer.append("<br />\n");
        }

        if (_links.size() > 0) {
            _postBodyBuffer.append(getSpan("summDetailExternal")).append("External links:</span> ");
            for (int i = 0; i < _links.size(); i++) {
                Link l = (Link)_links.get(i);
                _postBodyBuffer.append("<a ").append(getClass("summDetailExternalLink")).append(" href=\"externallink.jsp?");
                if (l.schema != null)
                    _postBodyBuffer.append("schema=").append(sanitizeURL(l.schema)).append('&');
                if (l.location != null)
                    _postBodyBuffer.append("location=").append(sanitizeURL(l.location)).append('&');
                _postBodyBuffer.append("\">").append(sanitizeString(l.location, 60));
                _postBodyBuffer.append(getSpan("summDetailExternalNet")).append(" (").append(sanitizeString(l.schema)).append(")</span></a> ");
            }
            _postBodyBuffer.append("<br />\n");
        }

        if (_addresses.size() > 0) {
            _postBodyBuffer.append(getSpan("summDetailAddr")).append("Addresses:</span>");
            for (int i = 0; i < _addresses.size(); i++) {
                Address a = (Address)_addresses.get(i);
                importAddress(a);
                PetName pn = null;
                if (_user != null)
                    pn = _user.getPetNameDB().getByLocation(a.location);
                if (pn != null) {
                    _postBodyBuffer.append(' ').append(getSpan("summDetailAddrKnown"));
                    _postBodyBuffer.append(sanitizeString(pn.getName())).append("</span>");
                } else {
                    _postBodyBuffer.append(" <a ").append(getClass("summDetailAddrLink")).append(" href=\"addresses.jsp?");
                    if (a.schema != null)
                        _postBodyBuffer.append("network=").append(sanitizeTagParam(a.schema)).append('&');
                    if (a.location != null)
                        _postBodyBuffer.append("location=").append(sanitizeTagParam(a.location)).append('&');
                    if (a.name != null)
                        _postBodyBuffer.append("name=").append(sanitizeTagParam(a.name)).append('&');
                    if (a.protocol != null)
                        _postBodyBuffer.append("protocol=").append(sanitizeTagParam(a.protocol)).append('&');
                    _postBodyBuffer.append("\">").append(sanitizeString(a.name)).append("</a>");
                }                    
            }
            _postBodyBuffer.append("<br />\n");
        }

        if (_archives.size() > 0) {
            _postBodyBuffer.append(getSpan("summDetailArchive")).append("Archives:</span>");
            for (int i = 0; i < _archives.size(); i++) {
                ArchiveRef a = (ArchiveRef)_archives.get(i);
                _postBodyBuffer.append(" <a ").append(getClass("summDetailArchiveLink")).append(" href=\"").append(getArchiveURL(null, new SafeURL(a.locationSchema + "://" + a.location)));
                _postBodyBuffer.append("\">").append(sanitizeString(a.name)).append("</a>");
                if (a.description != null)
                    _postBodyBuffer.append(": ").append(getSpan("summDetailArchiveDesc")).append(sanitizeString(a.description)).append("</span>");
                if (null == _user.getPetNameDB().getByLocation(a.location)) {
                    _postBodyBuffer.append(" <a ").append(getClass("summDetailArchiveBookmark")).append(" href=\"");
                    _postBodyBuffer.append(getBookmarkURL(a.name, a.location, a.locationSchema, "syndiearchive"));
                    _postBodyBuffer.append("\">bookmark it</a>");
                }
            }
            _postBodyBuffer.append("<br />\n");
        }

        if (_entry != null) {
            List replies = _archive.getIndex().getReplies(_entry.getURI());
            if ( (replies != null) && (replies.size() > 0) ) {
                _postBodyBuffer.append(getSpan("summDetailReplies")).append("Replies:</span> ");
                for (int i = 0; i < replies.size(); i++) { 
                    BlogURI reply = (BlogURI)replies.get(i);
                    _postBodyBuffer.append("<a ").append(getClass("summDetailReplyLink")).append(" href=\"");
                    _postBodyBuffer.append(getPageURL(reply.getKeyHash(), null, reply.getEntryId(), -1, -1, true, _user.getShowImages()));
                    _postBodyBuffer.append("\">");
                    _postBodyBuffer.append(getSpan("summDetailReplyAuthor"));
                    BlogInfo replyAuthor = _archive.getBlogInfo(reply);
                    if (replyAuthor != null) {
                        _postBodyBuffer.append(sanitizeString(replyAuthor.getProperty(BlogInfo.NAME)));
                    } else {
                        _postBodyBuffer.append(reply.getKeyHash().toBase64().substring(0,16));
                    }
                    _postBodyBuffer.append("</span> on ");
                    _postBodyBuffer.append(getSpan("summDetailReplyDate"));
                    _postBodyBuffer.append(getEntryDate(reply.getEntryId()));
                    _postBodyBuffer.append("</a></span> ");
                }
                _postBodyBuffer.append("<br />");
            }
        }

        String inReplyTo = (String)_headers.get(HEADER_IN_REPLY_TO);
        if ( (inReplyTo != null) && (inReplyTo.trim().length() > 0) ) {
            BlogURI replyURI = new BlogURI(inReplyTo);
            if (replyURI.getEntryId() > 0)
                _postBodyBuffer.append(" <a ").append(getClass("summDetailParent")).append(" href=\"").append(getPageURL(replyURI.getKeyHash(), null, replyURI.getEntryId(), 0, 0, true, true)).append("\">(view parent)</a><br />\n");
        }

        _postBodyBuffer.append(" </td>\n");
        _postBodyBuffer.append(" </form>\n");
        _postBodyBuffer.append("</tr>\n");
    }
    
    public void receiveHeaderEnd() {
        //_preBodyBuffer.append("<table ").append(getClass("overall")).append(" width=\"100%\" border=\"0\">\n");
        //renderSubjectCell();
        //renderMetaCell();
        //renderPreBodyCell();
    }
    
    public String getMetadataURL(Hash blog) {
        return buildProfileURL(blog);
    }
    public static String buildProfileURL(Hash blog) {
        if ( (blog != null) && (blog.getData() != null) )
            return "profile.jsp?" + ThreadedHTMLRenderer.PARAM_AUTHOR + "=" +
                   Base64.encode(blog.getData());
        else
            return "profile.jsp";
    }
    protected String getEntryURL() { return getEntryURL(_user != null ? _user.getShowImages() : false); }
    protected String getEntryURL(boolean showImages) {
        if (_entry == null) 
            return _baseURI;
        else
            return _baseURI + '?' + PARAM_VIEW_POST + '=' + 
                   Base64.encode(_entry.getURI().getKeyHash().getData()) + '/' 
                   + _entry.getURI().getEntryId() + '&';
    }
    
    public String getPageURL(User user, String selector, int numPerPage, int pageNum) { return _baseURI; }
    
    public String getPageURL(Hash blog, String tag, long entryId, String group, int numPerPage, int pageNum, boolean expandEntries, boolean showImages) {
        StringBuffer buf = new StringBuffer(128);
        buf.append(_baseURI).append('?');
        if ( (blog != null) && (entryId > 0) ) {
            buf.append(PARAM_VIEW_POST).append('=').append(Base64.encode(blog.getData())).append('/').append(entryId).append('&');
            buf.append(PARAM_VISIBLE).append('=').append(Base64.encode(blog.getData())).append('/').append(entryId).append('&');
        }
        if (tag != null)
            buf.append(PARAM_TAGS).append('=').append(sanitizeTagParam(tag)).append('&');
        return buf.toString();
    }
    public String getArchiveURL(Hash blog, SafeURL archiveLocation) {
        return "remote.jsp?" 
               //+ "action=Continue..." // should this be the case?
               + "&schema=" + sanitizeTagParam(archiveLocation.getSchema()) 
               + "&location=" + sanitizeTagParam(archiveLocation.getLocation());
    }
    public String getBookmarkURL(String name, String location, String schema, String protocol) {
        return "addresses.jsp?name=" + sanitizeTagParam(name)
               + "&network=" + sanitizeTagParam(schema)
               + "&protocol=" + sanitizeTagParam(protocol)
               + "&location=" + sanitizeTagParam(location);
               
    }
}
