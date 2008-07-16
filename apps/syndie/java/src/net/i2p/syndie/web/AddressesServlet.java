package net.i2p.syndie.web;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Iterator;
import java.util.TreeSet;

import javax.servlet.http.HttpServletRequest;

import net.i2p.client.naming.PetName;
import net.i2p.client.naming.PetNameDB;
import net.i2p.syndie.Archive;
import net.i2p.syndie.BlogManager;
import net.i2p.syndie.User;
import net.i2p.syndie.data.BlogURI;
import net.i2p.syndie.data.FilteredThreadIndex;
import net.i2p.syndie.data.ThreadIndex;
import net.i2p.syndie.sml.ThreadedHTMLRenderer;

/**
 * Show the user's addressbook
 *
 */
public class AddressesServlet extends BaseServlet {
    public static final String PARAM_IS_PUBLIC = "addrPublic";
    public static final String PARAM_NAME = "addrName";
    public static final String PARAM_LOC = "addrLoc";
    public static final String PARAM_FAVORITE = "addrFavorite";
    public static final String PARAM_IGNORE = "addrIgnore";
    public static final String PARAM_NET = "addrNet";
    public static final String PARAM_PROTO = "addrProto";
    public static final String PARAM_SYNDICATE = "addrSyndicate";
    public static final String PARAM_TAG = "addrTag";
    public static final String PARAM_ACTION = "action";
    
    public static final String PROTO_BLOG = "syndieblog";
    public static final String PROTO_ARCHIVE = "syndiearchive";
    public static final String PROTO_I2PHEX = "i2phex";
    public static final String PROTO_EEPSITE = "eep";
    public static final String PROTO_TAG = "syndietag";

    public static final String NET_SYNDIE = "syndie";
    public static final String NET_I2P = "i2p";
    public static final String NET_IP = "ip";
    public static final String NET_FREENET = "freenet";
    public static final String NET_TOR = "tor";

    public static final String ACTION_DELETE_BLOG = "Delete author";
    public static final String ACTION_UPDATE_BLOG = "Update author";
    public static final String ACTION_ADD_BLOG = "Add author";
    public static final String ACTION_PURGE_AND_BAN_BLOG = "Purge and ban author";
    
    public static final String ACTION_DELETE_ARCHIVE = "Delete archive";
    public static final String ACTION_UPDATE_ARCHIVE = "Update archive";
    public static final String ACTION_ADD_ARCHIVE = "Add archive";
    
    public static final String ACTION_DELETE_PEER = "Delete peer";
    public static final String ACTION_UPDATE_PEER = "Update peer";
    public static final String ACTION_ADD_PEER = "Add peer";
    
    public static final String ACTION_DELETE_EEPSITE = "Delete eepsite";
    public static final String ACTION_UPDATE_EEPSITE = "Update eepsite";
    public static final String ACTION_ADD_EEPSITE = "Add eepsite";
    
    public static final String ACTION_DELETE_TAG = "Delete tag";
    public static final String ACTION_UPDATE_TAG = "Update tag";
    public static final String ACTION_ADD_TAG = "Add tag";
    
    public static final String ACTION_DELETE_OTHER = "Delete address";
    public static final String ACTION_UPDATE_OTHER = "Update address";
    public static final String ACTION_ADD_OTHER = "Add other address";
        
    protected void renderServletDetails(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index, 
                                        int threadOffset, BlogURI visibleEntry, Archive archive) throws IOException {
        if (!user.getAuthenticated()) {
            out.write("<tr><td colspan=\"3\">You must log in to view your addressbook</d></tr>\n");
        } else {
            PetNameDB db = user.getPetNameDB();
            String uri = req.getRequestURI();
            
            PetName pn = buildNewName(req, PROTO_BLOG);
            _log.debug("pn for protoBlog [" + req.getParameter(PARAM_PROTO) + "]: " + pn);
            renderBlogs(user, db, uri, pn, out);
            pn = buildNewName(req, PROTO_ARCHIVE);
            _log.debug("pn for protoArchive [" + req.getParameter(PARAM_PROTO) + "]: " + pn);
            renderArchives(user, db, uri, pn, out);
            pn = buildNewName(req, PROTO_TAG);
            _log.debug("pn for protoTag [" + req.getParameter(PARAM_TAG) + "]: " + pn);
            renderTags(user, db, uri, pn, out);
            pn = buildNewName(req, PROTO_I2PHEX);
            _log.debug("pn for protoPhex [" + req.getParameter(PARAM_PROTO) + "]: " + pn);
            renderI2Phex(user, db, uri, pn, out);
            pn = buildNewName(req, PROTO_EEPSITE);
            _log.debug("pn for protoEep [" + req.getParameter(PARAM_PROTO) + "]: " + pn);
            renderEepsites(user, db, uri, pn, out);
            pn = buildNewName(req);
            _log.debug("pn for proto other [" + req.getParameter(PARAM_PROTO) + "]: " + pn);
            renderOther(user, db, uri, pn, out);
        }
    }

    private void renderBlogs(User user, PetNameDB db, String baseURI, PetName newName, PrintWriter out) throws IOException {
        TreeSet names = new TreeSet();
        for (Iterator iter = db.getNames().iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            PetName pn = db.getByName(name);
            if (PROTO_BLOG.equals(pn.getProtocol()))
                names.add(name);
        }
        out.write("<tr><td colspan=\"3\"><b>Syndie authors</b></td></tr>\n");
        for (Iterator iter = names.iterator(); iter.hasNext(); ) {
            PetName pn = db.getByName((String)iter.next());
            out.write("<form action=\"" + baseURI + "\" method=\"POST\">");
            out.write("<input type=\"hidden\" name=\"" + PARAM_PROTO + "\" value=\"" + PROTO_BLOG + "\" />");
            out.write("<input type=\"hidden\" name=\"" + PARAM_NET + "\" value=\"" + NET_SYNDIE + "\" />");
            writeAuthActionFields(out);
            out.write("<tr><td colspan=\"3\">");
            out.write("<input type=\"checkbox\" name=\"" + PARAM_IS_PUBLIC + "\" value=\"true\" " + (pn.getIsPublic() ? " checked=\"true\" " : "") 
                      + " title=\"If checked, this name can be shared with one click when posting\" />\n");
            out.write("Name: <input type=\"hidden\" name=\"" + PARAM_NAME + "\" value=\"" + pn.getName() 
                      + "\" title=\"Short, locally unique 'pet name' for the author\" />" + pn.getName() + " ");
            out.write("Location: <input type=\"text\" name=\"" + PARAM_LOC + "\" size=\"3\" value=\"" + pn.getLocation() 
                      + "\" title=\"Blog hash for the author\" /> ");
            if (pn.isMember(FilteredThreadIndex.GROUP_FAVORITE))
                out.write("Favorite? <input type=\"checkbox\" name=\"" + PARAM_FAVORITE 
                          + "\" checked=\"true\" value=\"true\" title=\"If true, their posts are highlighted\" /> ");
            else
                out.write("Favorite? <input type=\"checkbox\" name=\"" + PARAM_FAVORITE 
                          + "\" value=\"true\" title=\"If true, their posts are highlighted\" /> ");
            
            if (pn.isMember(FilteredThreadIndex.GROUP_IGNORE)) {
                out.write("Ignored? <input type=\"checkbox\" name=\"" + PARAM_IGNORE 
                          + "\" checked=\"true\" value=\"true\" title=\"If true, their threads are hidden\" /> ");
                if (BlogManager.instance().authorizeRemote(user))
                    out.write("<input type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\"" + ACTION_PURGE_AND_BAN_BLOG + "\" /> ");
            } else {
                out.write("Ignored? <input type=\"checkbox\" name=\"" + PARAM_IGNORE 
                          + "\" value=\"true\" title=\"If true, their threads are hidden\" /> ");
                out.write("<a href=\"" + getControlTarget() + "?" + ThreadedHTMLRenderer.PARAM_AUTHOR + '=' 
                          + pn.getLocation() + "\" title=\"View threads by the given author\">View posts</a> ");
            }
            
            out.write("<input type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\"" + ACTION_DELETE_BLOG + "\" /> ");
            out.write("<input type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\"" + ACTION_UPDATE_BLOG + "\" /> ");
            out.write("</td></tr>\n");
            out.write("</form>\n");
        }
        
        out.write("<form action=\"" + baseURI + "\" method=\"POST\">");
        writeAuthActionFields(out);
        out.write("<input type=\"hidden\" name=\"" + PARAM_PROTO + "\" value=\"" + PROTO_BLOG + "\" />");
        out.write("<input type=\"hidden\" name=\"" + PARAM_NET + "\" value=\"" + NET_SYNDIE + "\" />");
        out.write("<tr><td colspan=\"3\">");
        out.write("<input type=\"checkbox\" name=\"" + PARAM_IS_PUBLIC + "\" value=\"true\" " + (newName.getIsPublic() ? " checked=\"true\" " : "") 
                  + " title=\"If checked, this name can be shared with one click when posting\" />\n");
        out.write("Name: <input type=\"text\" name=\"" + PARAM_NAME + "\" size=\"10\" value=\"" + newName.getName() 
                  + "\" title=\"Short, locally unique 'pet name' for the author\" /> ");
        out.write("Location: <input type=\"text\" name=\"" + PARAM_LOC + "\" size=\"3\" value=\"" + newName.getLocation() 
                  + "\"  title=\"Blog hash for the author\" /> ");
        if (newName.isMember(FilteredThreadIndex.GROUP_FAVORITE))
            out.write("Favorite? <input type=\"checkbox\" name=\"" + PARAM_FAVORITE 
                      + "\" checked=\"true\" value=\"true\" title=\"If true, their posts are highlighted\" /> ");
        else
            out.write("Favorite? <input type=\"checkbox\" name=\"" + PARAM_FAVORITE 
                      + "\" value=\"true\" title=\"If true, their posts are highlighted\" /> ");

        if (newName.isMember(FilteredThreadIndex.GROUP_IGNORE)) {
            out.write("Ignored? <input type=\"checkbox\" name=\"" + PARAM_IGNORE 
                      + "\" checked=\"true\" value=\"true\" title=\"If true, their threads are hidden\" /> ");
        } else {
            out.write("Ignored? <input type=\"checkbox\" name=\"" + PARAM_IGNORE 
                      + "\" value=\"true\" title=\"If true, their threads are hidden\" /> ");
        }

        out.write("<input type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\"" + ACTION_ADD_BLOG + "\" /> ");
        out.write("</td></tr>\n");
        out.write("</form>\n");
            
        out.write("<tr><td colspan=\"3\"><hr /></td></tr>\n");
    }

    private void renderArchives(User user, PetNameDB db, String baseURI, PetName newName, PrintWriter out) throws IOException {
        TreeSet names = new TreeSet();
        for (Iterator iter = db.getNames().iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            PetName pn = db.getByName(name);
            if (PROTO_ARCHIVE.equals(pn.getProtocol()))
                names.add(name);
        }
        out.write("<tr><td colspan=\"3\"><b>Syndie archives</b></td></tr>\n");
        for (Iterator iter = names.iterator(); iter.hasNext(); ) {
            PetName pn = db.getByName((String)iter.next());
            out.write("<form action=\"" + baseURI + "\" method=\"POST\">");
            writeAuthActionFields(out);
            out.write("<input type=\"hidden\" name=\"" + PARAM_PROTO + "\" value=\"" + PROTO_ARCHIVE + "\" />");
            out.write("<input type=\"hidden\" name=\"" + PARAM_NET + "\" value=\"" + NET_SYNDIE + "\" />");
            out.write("<tr><td colspan=\"3\">");
            out.write("<input type=\"checkbox\" name=\"" + PARAM_IS_PUBLIC + "\" value=\"true\" " + (pn.getIsPublic() ? " checked=\"true\" " : "") 
                      + " title=\"If checked, this name can be shared with one click when posting\" />\n");
            out.write("Name: <input type=\"hidden\" name=\"" + PARAM_NAME + "\" size=\"10\" value=\"" + pn.getName() 
                      + "\" title=\"Short, locally unique 'pet name' for the remote archive\" />" + pn.getName() + " ");
            out.write("Location: <input type=\"text\" name=\"" + PARAM_LOC + "\" size=\"20\" value=\"" + pn.getLocation() 
                      + "\" title=\"URL to the remote archive's archive/archive.txt\" /> ");
            if (BlogManager.instance().authorizeRemote(user)) {
                if (BlogManager.instance().syndicationScheduled(pn.getLocation()))
                    out.write("Syndicate? <input type=\"checkbox\" name=\"" + PARAM_SYNDICATE 
                              + "\" checked=\"true\" value=\"true\" title=\"If true, periodically pull down posts they have\" />");
                else
                    out.write("Syndicate? <input type=\"checkbox\" name=\"" + PARAM_SYNDICATE 
                              + "\" value=\"true\" title=\"If true, periodically pull down posts they have\" />");

                out.write("<a href=\"" + getSyndicateLink(user, pn.getLocation()) 
                          + "\" title=\"Synchronize manually with the peer\">Sync manually</a> ");
            } else {
                out.write("You are not <a href=\"admin.jsp\">authorized</a> to syndicate with the archive ");
            }
            out.write("<input type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\"" + ACTION_DELETE_ARCHIVE + "\" /> ");
            out.write("<input type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\"" + ACTION_UPDATE_ARCHIVE + "\" /> ");
            out.write("</td></tr>\n");
            out.write("</form>\n");
        }

        out.write("<form action=\"" + baseURI + "\" method=\"POST\">");
        writeAuthActionFields(out);
        out.write("<input type=\"hidden\" name=\"" + PARAM_PROTO + "\" value=\"" + PROTO_ARCHIVE + "\" />");
        out.write("<input type=\"hidden\" name=\"" + PARAM_NET + "\" value=\"" + NET_SYNDIE + "\" />");
        out.write("<tr><td colspan=\"3\">");
        out.write("<input type=\"checkbox\" name=\"" + PARAM_IS_PUBLIC + "\" value=\"true\" " + (newName.getIsPublic() ? " checked=\"true\" " : "") 
                  + " title=\"If checked, this name can be shared with one click when posting\" />\n");
        out.write("Name: <input type=\"text\" name=\"" + PARAM_NAME + "\" size=\"10\" value=\"" + newName.getName() 
                  + "\" title=\"Short, locally unique 'pet name' for the remote archive\" /> ");
        out.write("Location: <input type=\"text\" name=\"" + PARAM_LOC + "\" size=\"20\" value=\"" + newName.getLocation() 
                  + "\" title=\"URL to the remote archive's archive/archive.txt\" /> ");
        if (BlogManager.instance().authorizeRemote(user)) {
            if (BlogManager.instance().syndicationScheduled(newName.getLocation()))
                out.write("Syndicate? <input type=\"checkbox\" name=\"" + PARAM_SYNDICATE 
                          + "\" checked=\"true\" value=\"true\" title=\"If true, periodically pull down posts they have\" />");
            else
                out.write("Syndicate? <input type=\"checkbox\" name=\"" + PARAM_SYNDICATE 
                          + "\" value=\"true\" title=\"If true, periodically pull down posts they have\" />");
        }

        out.write("<input type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\"" + ACTION_ADD_ARCHIVE + "\" /> ");
        out.write("</td></tr>\n");
        out.write("</form>\n");
        
        out.write("<tr><td colspan=\"3\"><hr /></td></tr>\n");
    }
    
    private void renderI2Phex(User user, PetNameDB db, String baseURI, PetName newName, PrintWriter out) throws IOException {
        TreeSet names = new TreeSet();
        for (Iterator iter = db.getNames().iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            PetName pn = db.getByName(name);
            if (PROTO_I2PHEX.equals(pn.getProtocol()))
                names.add(name);
        }
        out.write("<tr><td colspan=\"3\"><b>I2Phex peers</b></td></tr>\n");
        
        for (Iterator iter = names.iterator(); iter.hasNext(); ) {
            PetName pn = db.getByName((String)iter.next());
            out.write("<form action=\"" + baseURI + "\" method=\"POST\">");
            writeAuthActionFields(out);
            out.write("<input type=\"hidden\" name=\"" + PARAM_PROTO + "\" value=\"" + PROTO_I2PHEX + "\" />");
            out.write("<input type=\"hidden\" name=\"" + PARAM_NET + "\" value=\"" + NET_I2P + "\" />");
            out.write("<tr><td colspan=\"3\">");
            out.write("<input type=\"checkbox\" name=\"" + PARAM_IS_PUBLIC + "\" value=\"true\" " + (pn.getIsPublic() ? " checked=\"true\" " : "") 
                      + " title=\"If checked, this name can be shared with one click when posting\" />\n");
            out.write("Name: <input type=\"hidden\" name=\"" + PARAM_NAME + "\" value=\"" + pn.getName() 
                      + "\" title=\"Short, locally unique 'pet name' for the I2Phex peer\" />" + pn.getName() + " ");
            out.write("Location: <input type=\"text\" name=\"" + PARAM_LOC + "\" size=\"3\" value=\"" + pn.getLocation() 
                      + "\" title=\"I2P destination of the I2Phex peer\" /> ");
            
            out.write("<input type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\"" + ACTION_DELETE_PEER + "\" /> ");
            out.write("<input type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\"" + ACTION_UPDATE_PEER + "\" /> ");
            out.write("</td></tr>\n");
            out.write("</form>\n");
        }
        
        out.write("<form action=\"" + baseURI + "\" method=\"POST\">");
        writeAuthActionFields(out);
        out.write("<input type=\"hidden\" name=\"" + PARAM_PROTO + "\" value=\"" + PROTO_I2PHEX + "\" />");
        out.write("<input type=\"hidden\" name=\"" + PARAM_NET + "\" value=\"" + NET_I2P + "\" />");
        out.write("<tr><td colspan=\"3\">");
        out.write("<input type=\"checkbox\" name=\"" + PARAM_IS_PUBLIC + "\" value=\"true\" " + (newName.getIsPublic() ? " checked=\"true\" " : "") 
                  + " title=\"If checked, this name can be shared with one click when posting\" />\n");
        out.write("Name: <input type=\"text\" name=\"" + PARAM_NAME + "\" size=\"10\" value=\"" + newName.getName() 
                  + "\" title=\"Short, locally unique 'pet name' for the I2Phex peer\" /> ");
        out.write("Location: <input type=\"text\" name=\"" + PARAM_LOC + "\" size=\"3\" value=\"" + newName.getLocation() 
                  + "\" title=\"I2P destination of the I2Phex peer\" /> ");

        out.write("<input type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\"" + ACTION_ADD_PEER + "\" /> ");
        out.write("</td></tr>\n");
        out.write("</form>\n");
        
        out.write("<tr><td colspan=\"3\"><hr /></td></tr>\n");
    }
    private void renderEepsites(User user, PetNameDB db, String baseURI, PetName newName, PrintWriter out) throws IOException {
        TreeSet names = new TreeSet();
        for (Iterator iter = db.getNames().iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            PetName pn = db.getByName(name);
            if (PROTO_EEPSITE.equals(pn.getProtocol()))
                names.add(name);
        }
        out.write("<tr><td colspan=\"3\"><b>Eepsites</b></td></tr>\n");
        
        for (Iterator iter = names.iterator(); iter.hasNext(); ) {
            PetName pn = db.getByName((String)iter.next());
            out.write("<form action=\"" + baseURI + "\" method=\"POST\">");
            writeAuthActionFields(out);
            out.write("<input type=\"hidden\" name=\"" + PARAM_PROTO + "\" value=\"" + PROTO_EEPSITE + "\" />");
            out.write("<input type=\"hidden\" name=\"" + PARAM_NET + "\" value=\"" + NET_I2P + "\" />");
            out.write("<tr><td colspan=\"3\">");
            out.write("<input type=\"checkbox\" name=\"" + PARAM_IS_PUBLIC + "\" value=\"true\" " + (pn.getIsPublic() ? " checked=\"true\" " : "") 
                      + " title=\"If checked, this name can be shared with one click when posting\" />\n");
            out.write("Name: <input type=\"hidden\" name=\"" + PARAM_NAME + "\" value=\"" + pn.getName() 
                      + "\" title=\"Short, locally unique 'pet name' for the eepsite\" />" + pn.getName() + " ");
            out.write("Location: <input type=\"text\" name=\"" + PARAM_LOC + "\" size=\"3\" value=\"" + pn.getLocation() 
                      + "\" title=\"I2P destination of the eepsite\" /> ");
            
            out.write("<input type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\"" + ACTION_DELETE_EEPSITE + "\" /> ");
            out.write("<input type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\"" + ACTION_UPDATE_EEPSITE + "\" /> ");
            out.write("</td></tr>\n");
            out.write("</form>\n");
        }
        
        out.write("<form action=\"" + baseURI + "\" method=\"POST\">");
        writeAuthActionFields(out);
        out.write("<input type=\"hidden\" name=\"" + PARAM_PROTO + "\" value=\"" + PROTO_EEPSITE + "\" />");
        out.write("<input type=\"hidden\" name=\"" + PARAM_NET + "\" value=\"" + NET_I2P + "\" />");
        out.write("<tr><td colspan=\"3\">");
        out.write("<input type=\"checkbox\" name=\"" + PARAM_IS_PUBLIC + "\" value=\"true\" " + (newName.getIsPublic() ? " checked=\"true\" " : "") 
                  + " title=\"If checked, this name can be shared with one click when posting\" />\n");
        out.write("Name: <input type=\"text\" name=\"" + PARAM_NAME + "\" size=\"10\" value=\"" + newName.getName() 
                  + "\" title=\"Short, locally unique 'pet name' for the eepsite\" /> ");
        out.write("Location: <input type=\"text\" name=\"" + PARAM_LOC + "\" size=\"3\" value=\"" + newName.getLocation() 
                  + "\" title=\"I2P destination of the eepsite\" /> ");

        out.write("<input type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\"" + ACTION_ADD_EEPSITE + "\" /> ");
        out.write("</td></tr>\n");
        out.write("</form>\n");
        
        out.write("<tr><td colspan=\"3\"><hr /></td></tr>\n");
    }
    
    private void renderTags(User user, PetNameDB db, String baseURI, PetName newName, PrintWriter out) throws IOException {
        TreeSet names = new TreeSet();
        for (Iterator iter = db.getNames().iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            PetName pn = db.getByName(name);
            if (PROTO_TAG.equals(pn.getProtocol()))
                names.add(name);
        }
        out.write("<tr><td colspan=\"3\"><b>Favorite tags</b></td></tr>\n");
        
        for (Iterator iter = names.iterator(); iter.hasNext(); ) {
            PetName pn = db.getByName((String)iter.next());
            out.write("<form action=\"" + baseURI + "\" method=\"POST\">");
            writeAuthActionFields(out);
            out.write("<input type=\"hidden\" name=\"" + PARAM_PROTO + "\" value=\"" + PROTO_TAG + "\" />");
            out.write("<input type=\"hidden\" name=\"" + PARAM_NET + "\" value=\"" + NET_SYNDIE + "\" />");
            out.write("<tr><td colspan=\"3\">");
            out.write("<input type=\"checkbox\" name=\"" + PARAM_IS_PUBLIC + "\" value=\"true\" " + (pn.getIsPublic() ? " checked=\"true\" " : "") 
                      + " title=\"If checked, this name can be shared with one click when posting\" />\n");
            out.write("Name: <input type=\"hidden\" name=\"" + PARAM_NAME + "\" value=\"" + pn.getName() 
                      + "\" />" + pn.getName() + " ");
            out.write("<input type=\"hidden\" name=\"" + PARAM_LOC + "\" value=\"" + pn.getLocation() 
                      + "\" /> ");
            
            out.write("<input type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\"" + ACTION_DELETE_TAG + "\" /> ");
            out.write("</td></tr>\n");
            out.write("</form>\n");
        }
        
        out.write("<form action=\"" + baseURI + "\" method=\"POST\">");
        writeAuthActionFields(out);
        out.write("<input type=\"hidden\" name=\"" + PARAM_PROTO + "\" value=\"" + PROTO_TAG + "\" />");
        out.write("<input type=\"hidden\" name=\"" + PARAM_NET + "\" value=\"" + NET_SYNDIE + "\" />");
        out.write("<tr><td colspan=\"3\">");
        out.write("<input type=\"checkbox\" name=\"" + PARAM_IS_PUBLIC + "\" value=\"true\" " + (newName.getIsPublic() ? " checked=\"true\" " : "") 
                  + " title=\"If checked, this name can be shared with one click when posting\" />\n");
        out.write("Name: <input type=\"text\" name=\"" + PARAM_NAME + "\" size=\"10\" value=\"" + newName.getName() 
                  + "\" title=\"Tag (or group of tags)\" /> ");

        out.write("<input type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\"" + ACTION_ADD_TAG + "\" /> ");
        out.write("</td></tr>\n");
        out.write("</form>\n");
        
        out.write("<tr><td colspan=\"3\"><hr /></td></tr>\n");
    }
    
    private void renderOther(User user, PetNameDB db, String baseURI, PetName newName, PrintWriter out) throws IOException {
        TreeSet names = new TreeSet();
        for (Iterator iter = db.getNames().iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            PetName pn = db.getByName(name);
            if (isRightProtocol(pn.getProtocol()))
                names.add(name);
        }
        out.write("<tr><td colspan=\"3\"><b>Other addresses</b></td></tr>\n");
        
        for (Iterator iter = names.iterator(); iter.hasNext(); ) {
            PetName pn = db.getByName((String)iter.next());
            out.write("<form action=\"" + baseURI + "\" method=\"POST\">");
            writeAuthActionFields(out);
            out.write("<tr><td colspan=\"3\">");
            out.write("<input type=\"checkbox\" name=\"" + PARAM_IS_PUBLIC + "\" value=\"true\" " + (pn.getIsPublic() ? " checked=\"true\" " : "") 
                      + " title=\"If checked, this name can be shared with one click when posting\" />\n");
            out.write("Network: <input type=\"text\" name=\"" + PARAM_NET + "\" value=\"" + pn.getNetwork() 
                      + "\" title=\"What network is this on - i2p, tor, internet, freenet, etc\" /> ");
            out.write("Protocol: <input type=\"text\" name=\"" + PARAM_PROTO + "\" value=\"" + pn.getProtocol() 
                      + "\" title=\"How do we access/interact with this resource\" /> ");
            out.write("Name: <input type=\"hidden\" name=\"" + PARAM_NAME + "\" value=\"" + pn.getName() 
                      + "\" title=\"Short, locally unique 'pet name' for the location\" />" + pn.getName() +" ");
            out.write("Location: <input type=\"text\" name=\"" + PARAM_LOC + "\" size=\"3\" value=\"" + pn.getLocation() 
                      + "\" title=\"URL\" /> ");
            
            out.write("<input type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\"" + ACTION_DELETE_OTHER + "\" /> ");
            out.write("<input type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\"" + ACTION_UPDATE_OTHER + "\" /> ");
            out.write("</td></tr>\n");
            out.write("</form>\n");
        }
        
        out.write("<form action=\"" + baseURI + "\" method=\"POST\">");
        writeAuthActionFields(out);
        
        out.write("<tr><td colspan=\"3\">");
        out.write("<input type=\"checkbox\" name=\"" + PARAM_IS_PUBLIC + "\" value=\"true\" " + (newName.getIsPublic() ? " checked=\"true\" " : "") 
                  + " title=\"If checked, this name can be shared with one click when posting\" />\n");
        out.write("Network: <input type=\"text\" name=\"" + PARAM_NET + "\" value=\"" + newName.getNetwork() 
                  + "\" title=\"What network is this on - i2p, tor, internet, freenet, etc\" /> ");
        out.write("Protocol: <input type=\"text\" name=\"" + PARAM_PROTO + "\" value=\"" + newName.getProtocol() 
                  + "\" title=\"How do we access/interact with this resource\" /> ");
        out.write("Name: <input type=\"text\" name=\"" + PARAM_NAME + "\" size=\"10\" value=\"" + newName.getName() 
                  + "\" title=\"Short, locally unique 'pet name' for the location\" /> ");
        out.write("Location: <input type=\"text\" name=\"" + PARAM_LOC + "\" size=\"3\" value=\"" + newName.getLocation() 
                  + "\" title=\"URL\" /> ");

        out.write("<input type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\"" + ACTION_ADD_OTHER + "\" /> ");
        out.write("</td></tr>\n");
        out.write("</form>\n");        
        
        out.write("<tr><td colspan=\"3\"><hr /></td></tr>\n");
    }
    
    /** build the 'other' name passed in */
    private PetName buildNewName(HttpServletRequest req) { return buildNewName(req, null); }
    /** build a petname based by the request passed in, if the new entry is of the given protocol */
    private PetName buildNewName(HttpServletRequest req, String protocol) {
        PetName pn = new PetName();
        if (!isRightProtocol(req, protocol)) {
            pn.setIsPublic(true);
            pn.setName("");
            pn.setLocation("");
            if (protocol == null)
                pn.setProtocol("");
            else
                pn.setProtocol(protocol);
            pn.setNetwork("");
            return pn;
        } else {
            pn = buildNewAddress(req);
        }
        return pn;
    }
    
    private String getParam(HttpServletRequest req, String param) {
        if (empty(req, param)) {
            return "";
        } else {
            String val = req.getParameter(param);
            return val;
        }
    }
    
    
    private boolean isRightProtocol(HttpServletRequest req, String protocol) {
        // if they hit submit, they are actually updating stuff, so don't include a 'new' one
        if (!empty(req, PARAM_ACTION))
            return false;
    
        return isRightProtocol(protocol, req.getParameter(PARAM_PROTO));
    }
    private boolean isRightProtocol(String proto) { return isRightProtocol((String)null, proto); }
    private boolean isRightProtocol(String proto, String reqProto) {
        if (empty(reqProto))
            return false;
        if (proto == null) {
            if (PROTO_ARCHIVE.equals(reqProto) || 
                PROTO_BLOG.equals(reqProto) ||
                PROTO_EEPSITE.equals(reqProto) ||
                PROTO_TAG.equals(reqProto) ||
                PROTO_I2PHEX.equals(reqProto))
                return false;
            else // its something other than the four default types
                return true;
        } else {
            return proto.equals(reqProto);
        }
    }
    
    protected String getTitle() { return "Syndie :: Addressbook"; }
}
