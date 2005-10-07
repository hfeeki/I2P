package net.i2p.syndie;

import java.io.*;
import java.text.*;
import java.util.*;

import net.i2p.I2PAppContext;
import net.i2p.client.naming.PetName;
import net.i2p.client.naming.PetNameDB;
import net.i2p.data.*;
import net.i2p.syndie.data.*;
import net.i2p.syndie.sml.*;

/**
 *
 */
public class BlogManager {
    private I2PAppContext _context;
    private static BlogManager _instance;
    private File _blogKeyDir;
    private File _privKeyDir;
    private File _archiveDir;
    private File _userDir;
    private File _cacheDir;
    private File _tempDir;
    private File _rootDir;
    private Archive _archive;
    
    static {
        TimeZone.setDefault(TimeZone.getTimeZone("GMT"));
        String rootDir = I2PAppContext.getGlobalContext().getProperty("syndie.rootDir");
        if (false) {
            if (rootDir == null)
                rootDir = System.getProperty("user.home");
            rootDir = rootDir + File.separatorChar + ".syndie";
        } else {
            if (rootDir == null)
                rootDir = "./syndie";
        }
        _instance = new BlogManager(I2PAppContext.getGlobalContext(), rootDir);
    }
    public static BlogManager instance() { return _instance; }
    
    public BlogManager(I2PAppContext ctx, String rootDir) {
        _context = ctx;
        _rootDir = new File(rootDir);
        _rootDir.mkdirs();
        readConfig();
        _blogKeyDir = new File(_rootDir, "blogkeys");
        _privKeyDir = new File(_rootDir, "privkeys");
        String archiveDir = _context.getProperty("syndie.archiveDir");
        if (archiveDir != null)
            _archiveDir = new File(archiveDir);
        else
            _archiveDir = new File(_rootDir, "archive");
        _userDir = new File(_rootDir, "users");
        _cacheDir = new File(_rootDir, "cache");
        _tempDir = new File(_rootDir, "temp");
        _blogKeyDir.mkdirs();
        _privKeyDir.mkdirs();
        _archiveDir.mkdirs();
        _cacheDir.mkdirs();
        _userDir.mkdirs();
        _tempDir.mkdirs();
        _archive = new Archive(ctx, _archiveDir.getAbsolutePath(), _cacheDir.getAbsolutePath());
        _archive.regenerateIndex();
    }
    
    private File getConfigFile() { return new File(_rootDir, "syndie.config"); }
    private void readConfig() {
        File config = getConfigFile();
        if (config.exists()) {
            try {
                Properties p = new Properties();
                DataHelper.loadProps(p, config);
                for (Iterator iter = p.keySet().iterator(); iter.hasNext(); ) {
                    String key = (String)iter.next();
                    System.setProperty(key, p.getProperty(key));
                    System.out.println("Read config prop [" + key + "] = [" + p.getProperty(key) + "]");
                }
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
        } else {
            System.out.println("Config doesn't exist: " + config.getPath());
        }
    }
    
    public void writeConfig() {
        File config = new File(_rootDir, "syndie.config");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(config);
            for (Iterator iter = _context.getPropertyNames().iterator(); iter.hasNext(); ) {
                String name = (String)iter.next();
                if (name.startsWith("syndie."))
                    out.write(DataHelper.getUTF8(name + '=' + _context.getProperty(name) + '\n'));
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        } finally {
            if (out != null) try { out.close(); } catch (IOException ioe) {}
        }
    }
    
    public BlogInfo createBlog(String name, String description, String contactURL, String archives[]) {
        return createBlog(name, null, description, contactURL, archives);
    }
    public BlogInfo createBlog(String name, SigningPublicKey posters[], String description, String contactURL, String archives[]) {
        Object keys[] = _context.keyGenerator().generateSigningKeypair();
        SigningPublicKey pub = (SigningPublicKey)keys[0];
        SigningPrivateKey priv = (SigningPrivateKey)keys[1];
        
        try {
            FileOutputStream out = new FileOutputStream(new File(_privKeyDir, Base64.encode(pub.calculateHash().getData()) + ".priv"));
            pub.writeBytes(out);
            priv.writeBytes(out);
        } catch (DataFormatException dfe) {
            dfe.printStackTrace();
            return null;
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        }
        
        return createInfo(pub, priv, name, posters, description, contactURL, archives, 0);
    }
    
    public BlogInfo createInfo(SigningPublicKey pub, SigningPrivateKey priv, String name, SigningPublicKey posters[], 
                               String description, String contactURL, String archives[], int edition) {
        Properties opts = new Properties();
        opts.setProperty("Name", name);
        opts.setProperty("Description", description);
        opts.setProperty("Edition", Integer.toString(edition));
        opts.setProperty("ContactURL", contactURL);
        for (int i = 0; archives != null && i < archives.length; i++) 
            opts.setProperty("Archive." + i, archives[i]);
        
        BlogInfo info = new BlogInfo(pub, posters, opts);
        info.sign(_context, priv);
        
        _archive.storeBlogInfo(info);
        
        return info;
    }
    
    public boolean updateMetadata(User user, Hash blog, Properties opts) {
        if (!user.getAuthenticated()) return false;
        BlogInfo oldInfo = getArchive().getBlogInfo(blog);
        if (oldInfo == null) return false;
        if (!user.getBlog().equals(oldInfo.getKey().calculateHash())) return false;
        int oldEdition = 0;
        try { 
            String ed = oldInfo.getProperty("Edition");
            if (ed != null)
                oldEdition = Integer.parseInt(ed);
        } catch (NumberFormatException nfe) {}
        opts.setProperty("Edition", oldEdition + 1 + "");
        BlogInfo info = new BlogInfo(oldInfo.getKey(), oldInfo.getPosters(), opts);
        SigningPrivateKey key = getMyPrivateKey(oldInfo);
        info.sign(_context, key);
        getArchive().storeBlogInfo(info);
        user.setLastMetaEntry(oldEdition+1);
        saveUser(user);
        return true;
    }
    
    public Archive getArchive() { return _archive; }
    public File getTempDir() { return _tempDir; }
    public File getRootDir() { return _rootDir; }
    
    public List listMyBlogs() {
        File files[] = _privKeyDir.listFiles();
        List rv = new ArrayList();
        for (int i = 0; i < files.length; i++) {
            if (files[i].isFile() && !files[i].isHidden()) {
                try {
                    SigningPublicKey pub = new SigningPublicKey();
                    pub.readBytes(new FileInputStream(files[i]));
                    BlogInfo info = _archive.getBlogInfo(pub.calculateHash());
                    if (info != null)
                        rv.add(info);
                } catch (IOException ioe) {
                    ioe.printStackTrace();
                } catch (DataFormatException dfe) {
                    dfe.printStackTrace();
                }
            }
        }
        return rv;
    }
    
    public SigningPrivateKey getMyPrivateKey(BlogInfo blog) {
        if (blog == null) return null;
        File keyFile = new File(_privKeyDir, Base64.encode(blog.getKey().calculateHash().getData()) + ".priv");
        try {
            FileInputStream in = new FileInputStream(keyFile);
            SigningPublicKey pub = new SigningPublicKey();
            pub.readBytes(in);
            SigningPrivateKey priv = new SigningPrivateKey();
            priv.readBytes(in);
            return priv;
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        } catch (DataFormatException dfe) {
            dfe.printStackTrace();
            return null;
        }
    }
    
    public String login(User user, String login, String pass) {
        if ( (login == null) || (pass == null) ) return "<span class=\"b_loginMsgErr\">Login not specified</span>";
        Hash userHash = _context.sha().calculateHash(DataHelper.getUTF8(login));
        Hash passHash = _context.sha().calculateHash(DataHelper.getUTF8(pass));
        File userFile = new File(_userDir, Base64.encode(userHash.getData()));
        System.out.println("Attempting to login to " + login + " w/ pass = " + pass 
                           + ": file = " + userFile.getAbsolutePath() + " passHash = "
                           + Base64.encode(passHash.getData()));
        if (userFile.exists()) {
            try {
                Properties props = new Properties();
                FileInputStream fin = new FileInputStream(userFile);
                BufferedReader in = new BufferedReader(new InputStreamReader(fin, "UTF-8"));
                String line = null;
                while ( (line = in.readLine()) != null) {
                    int split = line.indexOf('=');
                    if (split <= 0) continue;
                    String key = line.substring(0, split);
                    String val = line.substring(split+1);
                    props.setProperty(key.trim(), val.trim());
                }
                return user.login(login, pass, props);
            } catch (IOException ioe) {
                ioe.printStackTrace();
                return "<span class=\"b_loginMsgErr\">Error logging in - corrupt userfile</span>";
            }
        } else {
            return "<span class=\"b_loginMsgErr\">User does not exist</span>";
        }
    }
    
    /** hash of the password required to register and create a new blog (null means no password required) */
    public String getRegistrationPasswordHash() { 
        String pass = _context.getProperty("syndie.registrationPassword");
        if ( (pass == null) || (pass.trim().length() <= 0) ) return null;
        return pass; 
    }
    
    /** Password required to access the remote syndication functinoality (null means no password required) */
    public String getRemotePasswordHash() { 
        String pass = _context.getProperty("syndie.remotePassword");
        
        System.out.println("Remote password? [" + pass + "]");
        if ( (pass == null) || (pass.trim().length() <= 0) ) return null;
        return pass;
    }
    public String getAdminPasswordHash() { 
        String pass = _context.getProperty("syndie.adminPassword");
        if ( (pass == null) || (pass.trim().length() <= 0) ) return "";
        return pass;
    }
    
    public boolean isConfigured() {
        File cfg = getConfigFile();
        return (cfg.exists());
    }
    
    /**
     * If true, this syndie instance is meant for just one local user, so we don't need
     * to password protect registration, remote.jsp, or admin.jsp
     *
     */
    public boolean isSingleUser() {
        String isSingle = _context.getProperty("syndie.singleUser");
        return ( (isSingle != null) && (Boolean.valueOf(isSingle).booleanValue()) );
    }

    public String getDefaultProxyHost() { return _context.getProperty("syndie.defaultProxyHost", ""); }
    public String getDefaultProxyPort() { return _context.getProperty("syndie.defaultProxyPort", ""); }
    public int getUpdateDelay() { return Integer.parseInt(_context.getProperty("syndie.updateDelay", "12")); }
    public String[] getUpdateArchives() { return _context.getProperty("syndie.updateArchives", "").split(","); } 
    
    public boolean authorizeAdmin(String pass) {
        if (isSingleUser()) return true;
        String admin = getAdminPasswordHash();
        if ( (admin == null) || (admin.trim().length() <= 0) )
            return false;
        String hash = Base64.encode(_context.sha().calculateHash(DataHelper.getUTF8(pass.trim())).getData());
        return (hash.equals(admin));
    }
    public boolean authorizeRemote(String pass) {
        if (isSingleUser()) return true;
        String rem = getRemotePasswordHash();
        if ( (rem == null) || (rem.trim().length() <= 0) )
            return false;
        String hash = Base64.encode(_context.sha().calculateHash(DataHelper.getUTF8(pass.trim())).getData());
        return (hash.equals(rem));
    }
    public boolean authorizeRemote(User user) {
        if (isSingleUser()) return true;
        return (user.getAuthenticated() && user.getAllowAccessRemote());
    }
    
    public void configure(String registrationPassword, String remotePassword, String adminPass, String defaultSelector, 
                          String defaultProxyHost, int defaultProxyPort, boolean isSingleUser, Properties opts) {
        File cfg = getConfigFile();
        Writer out = null;
        try {
            out = new OutputStreamWriter(new FileOutputStream(cfg), "UTF-8");
            if (registrationPassword != null)
                out.write("syndie.registrationPassword="+Base64.encode(_context.sha().calculateHash(DataHelper.getUTF8(registrationPassword.trim())).getData()) + "\n");
            if (remotePassword != null)
                out.write("syndie.remotePassword="+Base64.encode(_context.sha().calculateHash(DataHelper.getUTF8(remotePassword.trim())).getData()) + "\n");
            if (adminPass != null)
                out.write("syndie.adminPassword="+Base64.encode(_context.sha().calculateHash(DataHelper.getUTF8(adminPass.trim())).getData()) + "\n");
            if (defaultSelector != null)
                out.write("syndie.defaultSelector="+defaultSelector.trim() + "\n");
            if (defaultProxyHost != null)
                out.write("syndie.defaultProxyHost="+defaultProxyHost.trim() + "\n");
            if (defaultProxyPort > 0)
                out.write("syndie.defaultProxyPort="+defaultProxyPort + "\n");
            out.write("syndie.singleUser=" + isSingleUser + "\n");
            if (opts != null) {
                for (Iterator iter = opts.keySet().iterator(); iter.hasNext(); ) {
                    String key = (String)iter.next();
                    String val = opts.getProperty(key);
                    out.write(key.trim() + "=" + val.trim() + "\n");
                }
            }
            _archive.setDefaultSelector(defaultSelector);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        } finally {
            if (out != null) try { out.close(); } catch (IOException ioe) {}
            readConfig();
        }
    }
    
    public String authorizeRemoteAccess(User user, String password) {
        if (!user.getAuthenticated()) return "<span class=\"b_remoteMsgErr\">Not logged in</span>";
        String remPass = getRemotePasswordHash();
        if (remPass == null)
            return "<span class=\"b_remoteMsgErr\">Remote access password not configured - please <a href=\"admin.jsp\">specify</a> a remote " +
                   "archive password</span>";
        
        if (authorizeRemote(password)) {
            user.setAllowAccessRemote(true);
            saveUser(user);
            return "<span class=\"b_remoteMsgOk\">Remote access authorized</span>";
        } else {
            return "<span class=\"b_remoteMsgErr\">Remote access denied</span>";
        }
    }
    
    public void saveUser(User user) {
        if (!user.getAuthenticated()) return;
        String userHash = Base64.encode(_context.sha().calculateHash(DataHelper.getUTF8(user.getUsername())).getData());
        File userFile = new File(_userDir, userHash);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(userFile);
            out.write(DataHelper.getUTF8(user.export()));
            user.getPetNameDB().store(user.getAddressbookLocation());
        } catch (IOException ioe) {
            ioe.printStackTrace();
        } finally {
            if (out != null) try { out.close(); } catch (IOException ioe){}
        }
    }
    public String register(User user, String login, String password, String registrationPassword, String blogName, String blogDescription, String contactURL) {
        System.err.println("Register [" + login + "] pass [" + password + "] name [" + blogName + "] descr [" + blogDescription + "] contact [" + contactURL + "] regPass [" + registrationPassword + "]");
        String hashedRegistrationPassword = getRegistrationPasswordHash();
        if ( (hashedRegistrationPassword != null) && (!isSingleUser()) ) {
            try {
                if (!hashedRegistrationPassword.equals(Base64.encode(_context.sha().calculateHash(registrationPassword.getBytes("UTF-8")).getData())))
                    return "<span class=\"b_regMsgErr\">Invalid registration password</span>";
            } catch (UnsupportedEncodingException uee) {
                return "<span class=\"b_regMsgErr\">Error registering</span>";
            }
        }
        String userHash = Base64.encode(_context.sha().calculateHash(DataHelper.getUTF8(login)).getData());
        File userFile = new File(_userDir, userHash);
        if (userFile.exists()) {
            return "<span class=\"b_regMsgErr\">Cannot register the login " + login + ": it already exists</span>";
        } else {
            BlogInfo info = createBlog(blogName, blogDescription, contactURL, null);
            String hashedPassword = Base64.encode(_context.sha().calculateHash(DataHelper.getUTF8(password)).getData());
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(userFile);
                BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(out, "UTF-8"));
                bw.write("password=" + hashedPassword + "\n");
                bw.write("blog=" + Base64.encode(info.getKey().calculateHash().getData()) + "\n");
                bw.write("lastid=-1\n");
                bw.write("lastmetaedition=0\n");
                bw.write("addressbook=userhosts-"+userHash + ".txt\n");
                bw.write("showimages=false\n");
                bw.write("showexpanded=false\n");
                bw.flush();
            } catch (IOException ioe) {
                ioe.printStackTrace();
                return "<span class=\"b_regMsgErr\">Internal error registering - " + ioe.getMessage() + "</span>";
            } finally {
                if (out != null) try { out.close(); } catch (IOException ioe) {}
            }
            String loginResult = login(user, login, password);
            _archive.regenerateIndex();
            return loginResult;
        }
    }

    public String exportHosts(User user) {
        if (!user.getAuthenticated() || !user.getAllowAccessRemote())
            return "<span class=\"b_addrMsgErr\">Not authorized to export the hosts</span>";
        PetNameDB userDb = user.getPetNameDB();
        PetNameDB routerDb = _context.petnameDb();
        // horribly inefficient...
        for (Iterator names = userDb.getNames().iterator(); names.hasNext();) {
            PetName pn = userDb.get((String)names.next());
            if (pn == null) continue;
            Destination existing = _context.namingService().lookup(pn.getName());
            if (existing == null && pn.getNetwork().equalsIgnoreCase("i2p")) {
                routerDb.set(pn.getName(), pn);
                try {
                    routerDb.store();
                } catch (IOException ioe) {
                    ioe.printStackTrace();
                    return "<span class=\"b_addrMsgErr\">Error exporting the hosts: " + ioe.getMessage() + "</span>";
                }
            }
        }
        return "<span class=\"b_addrMsgOk\">Hosts exported</span>";
    }
    
    public BlogURI createBlogEntry(User user, String subject, String tags, String entryHeaders, String sml) {
        return createBlogEntry(user, subject, tags, entryHeaders, sml, null, null, null);
    }
    public BlogURI createBlogEntry(User user, String subject, String tags, String entryHeaders, String sml, List fileNames, List fileStreams, List fileTypes) {
        if (!user.getAuthenticated()) return null;
        BlogInfo info = getArchive().getBlogInfo(user.getBlog());
        if (info == null) return null;
        SigningPrivateKey privkey = getMyPrivateKey(info);
        if (privkey == null) return null;
        
        long entryId = -1;
        long now = _context.clock().now();
        long dayBegin = getDayBegin(now);
        if (user.getMostRecentEntry() >= dayBegin)
            entryId = user.getMostRecentEntry() + 1;
        else
            entryId = dayBegin;
        
        StringTokenizer tok = new StringTokenizer(tags, " ,\n\t");
        String tagList[] = new String[tok.countTokens()];
        for (int i = 0; i < tagList.length; i++) 
            tagList[i] = tok.nextToken().trim();
        
        BlogURI uri = new BlogURI(user.getBlog(), entryId);
        
        try {
            StringBuffer raw = new StringBuffer(sml.length() + 128);
            raw.append("Subject: ").append(subject).append('\n');
            raw.append("Tags: ");
            for (int i = 0; i < tagList.length; i++) 
                raw.append(tagList[i]).append('\t');
            raw.append('\n');
            if ( (entryHeaders != null) && (entryHeaders.trim().length() > 0) ) {
                System.out.println("Entry headers: " + entryHeaders);
                BufferedReader userHeaders = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(DataHelper.getUTF8(entryHeaders)), "UTF-8"));
                String line = null;
                while ( (line = userHeaders.readLine()) != null) {
                    line = line.trim();
                    System.out.println("Line: " + line);
                    if (line.length() <= 0) continue;
                    int split = line.indexOf('=');
                    int split2 = line.indexOf(':');
                    if ( (split < 0) || ( (split2 > 0) && (split2 < split) ) ) split = split2;
                    String key = line.substring(0,split).trim();
                    String val = line.substring(split+1).trim();
                    raw.append(key).append(": ").append(val).append('\n');
                }
            }
            raw.append('\n');
            raw.append(sml);
            
            EntryContainer c = new EntryContainer(uri, tagList, DataHelper.getUTF8(raw));
            if ((fileNames != null) && (fileStreams != null) && (fileNames.size() == fileStreams.size()) ) {
                for (int i = 0; i < fileNames.size(); i++) {
                    String name = (String)fileNames.get(i);
                    InputStream in = (InputStream)fileStreams.get(i);
                    String fileType = (fileTypes != null ? (String)fileTypes.get(i) : "application/octet-stream");
                    ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
                    byte buf[] = new byte[1024];
                    while (true) {
                        int read = in.read(buf);
                        if (read == -1) break;
                        baos.write(buf, 0, read);
                    }
                    byte att[] = baos.toByteArray();
                    if ( (att != null) && (att.length > 0) )
                        c.addAttachment(att, new File(name).getName(), null, fileType);
                }
            }
            //for (int i = 7; i < args.length; i++) {
            //    c.addAttachment(read(args[i]), new File(args[i]).getName(), 
            //                    "Attached file", "application/octet-stream");
            //}
            SessionKey entryKey = null;
            //if (!"NONE".equals(args[5]))
            //    entryKey = new SessionKey(Base64.decode(args[5]));
            c.seal(_context, privkey, null);
            boolean ok = getArchive().storeEntry(c);
            if (ok) {
                getArchive().regenerateIndex();
                user.setMostRecentEntry(entryId);
                saveUser(user);
                return uri;
            } else {
                return null;
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        }
    }
    
    /** 
     * read in the syndie blog metadata file from the stream, verifying it and adding it to 
     * the archive if necessary
     *
     */
    public boolean importBlogMetadata(InputStream metadataStream) throws IOException {
        try {
            BlogInfo info = new BlogInfo();
            info.load(metadataStream);
            return _archive.storeBlogInfo(info);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        }
    }
    
    /** 
     * read in the syndie entry file from the stream, verifying it and adding it to 
     * the archive if necessary
     *
     */
    public boolean importBlogEntry(InputStream entryStream) throws IOException {
        try {
            EntryContainer c = new EntryContainer();
            c.load(entryStream);
            return _archive.storeEntry(c);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        }
    }

    public String addAddress(User user, String name, String protocol, String location, String schema) {
        if (!user.getAuthenticated()) return "<span class=\"b_addrMsgErr\">Not logged in</span>";
        boolean ok = validateAddressName(name);
        if (!ok) return "<span class=\"b_addrMsgErr\">Invalid name: " + HTMLRenderer.sanitizeString(name) + "</span>";
        ok = validateAddressLocation(location);
        if (!ok) return "<span class=\"b_addrMsgErr\">Invalid location: " + HTMLRenderer.sanitizeString(location) + "</span>";
        if (!validateAddressSchema(schema)) return "<span class=\"b_addrMsgErr\">Unsupported schema: " + HTMLRenderer.sanitizeString(schema) + "</span>";
        // no need to quote user/location further, as they've been sanitized
        
        PetNameDB names = user.getPetNameDB();
        if (names.exists(name))
            return "<span class=\"b_addrMsgErr\">Name is already in use</span>";
        PetName pn = new PetName(name, schema, protocol, location);
        names.set(name, pn);
        
        try {
            names.store(user.getAddressbookLocation());
            return "<span class=\"b_addrMsgOk\">Address " + name + " written to your addressbook</span>";
        } catch (IOException ioe) {
            return "<span class=\"b_addrMsgErr\">Error writing out the name: " + ioe.getMessage() + "</span>";
        }
    }
    
    public Properties getKnownHosts(User user, boolean includePublic) throws IOException {
        Properties rv = new Properties();
        if ( (user != null) && (user.getAuthenticated()) ) {
            File userHostsFile = new File(user.getAddressbookLocation());
            rv.putAll(getKnownHosts(userHostsFile));
        }
        if (includePublic) {
            rv.putAll(getKnownHosts(new File("hosts.txt")));
        }
        return rv;
    }
    private Properties getKnownHosts(File filename) throws IOException {
        Properties rv = new Properties();
        if (filename.exists()) {
            rv.load(new FileInputStream(filename));
        }
        return rv;
    }
    
    private boolean validateAddressName(String name) {
        if ( (name == null) || (name.trim().length() <= 0) ) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && ('.' != c) && ('-' != c) && ('_' != c) )
                return false;
        }
        return true;
    }

    private boolean validateAddressLocation(String location) {
        if ( (location == null) || (location.trim().length() <= 0) ) return false;
        if (false) {
            try {
                Destination d = new Destination(location);
                return (d.getPublicKey() != null);
            } catch (DataFormatException dfe) {
                dfe.printStackTrace();
                return false;
            }
        } else {
            // not everything is an i2p destination...
            return true;
        }
    }

    private boolean validateAddressSchema(String schema) {
        if ( (schema == null) || (schema.trim().length() <= 0) ) return false;
        if (true) {
            return true;
        } else {
            return "eep".equals(schema) || "i2p".equals(schema);
        }
    }

    private final SimpleDateFormat _dateFormat = new SimpleDateFormat("yyyy/MM/dd", Locale.UK);    
    private final long getDayBegin(long now) {
        synchronized (_dateFormat) {
            try {
                String str = _dateFormat.format(new Date(now));
                return _dateFormat.parse(str).getTime();
            } catch (ParseException pe) {
                pe.printStackTrace();
                // wtf
                return -1;
            }
        }
    }
    
    public void scheduleSyndication(String location) {
        String archives[] = getUpdateArchives();
        StringBuffer buf = new StringBuffer(64);
        if ( (archives != null) && (archives.length > 0) ) {
            for (int i = 0; i < archives.length; i++)
                if ( (!archives[i].equals(location)) && (archives[i].trim().length() > 0) )
                    buf.append(archives[i]).append(",");
        }
        if ( (location != null) && (location.trim().length() > 0) )
            buf.append(location.trim());
        System.setProperty("syndie.updateArchives", buf.toString());
        Updater.wakeup();
    }
    public void unscheduleSyndication(String location) {
        String archives[] = getUpdateArchives();
        if ( (archives != null) && (archives.length > 0) ) {
            StringBuffer buf = new StringBuffer(64);
            for (int i = 0; i < archives.length; i++)
                if ( (!archives[i].equals(location)) && (archives[i].trim().length() > 0) )
                    buf.append(archives[i]).append(",");
            System.setProperty("syndie.updateArchives", buf.toString());
        }
    }
    public boolean syndicationScheduled(String location) {
        String archives[] = getUpdateArchives();
        if ( (location == null) || (archives == null) || (archives.length <= 0) )
            return false;
        for (int i = 0; i < archives.length; i++)
            if (location.equals(archives[i]))
                return true;
        return false;
    }
}
