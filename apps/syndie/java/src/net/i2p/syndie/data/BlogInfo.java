package net.i2p.syndie.data;

import java.io.*;
import java.util.*;
import net.i2p.data.*;
import net.i2p.I2PAppContext;

/**
 * Blog metadata.  Formatted as: <pre>
 * [key:val\n]*
 * </pre>
 *
 * Required keys:
 *  Owner: base64 of their signing public key
 *  Signature: base64 of the DSA signature of the rest of the ordered metadata
 *  Edition: base10 unique identifier for this metadata (higher clobbers lower)
 *
 * Optional keys:
 *  Posters: comma delimited list of base64 signing public keys that
 *           can post to the blog
 *  Name: name of the blog
 *  Description: brief description of the blog
 *  
 */
public class BlogInfo {
    private SigningPublicKey _key;
    private SigningPublicKey _posters[];
    private String _optionNames[];
    private String _optionValues[];
    private Signature _signature;

    public BlogInfo() {}
    
    public BlogInfo(SigningPublicKey key, SigningPublicKey posters[], Properties opts) {
        _optionNames = new String[0];
        _optionValues = new String[0];
        setKey(key);
        setPosters(posters);
        for (Iterator iter = opts.keySet().iterator(); iter.hasNext(); ) {
            String k = (String)iter.next();
            String v = opts.getProperty(k);
            setProperty(k.trim(), v.trim());
        }
    }
    
    public SigningPublicKey getKey() { return _key; }
    public void setKey(SigningPublicKey key) { 
        _key = key; 
        setProperty(OWNER_KEY, Base64.encode(key.getData()));
    }
    
    public static final String OWNER_KEY = "Owner";
    public static final String POSTERS = "Posters";
    public static final String SIGNATURE = "Signature";
    public static final String NAME = "Name";
    public static final String DESCRIPTION = "Description";
    public static final String EDITION = "Edition";
    
    public void load(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        List names = new ArrayList();
        List vals = new ArrayList();
        String line = null;
        while ( (line = reader.readLine()) != null) {
            line = line.trim();
            int len = line.length();
            int split = line.indexOf(':');
            if ( (len <= 0) || (split <= 0) ) {
                continue;
            } else if (split >= len - 1) {
                names.add(line.substring(0, split).trim());
                vals.add("");
                continue;
            }
            
            String key = line.substring(0, split).trim();
            String val = line.substring(split+1).trim();
            names.add(key);
            vals.add(val);
        }
        _optionNames = new String[names.size()];
        _optionValues = new String[names.size()];
        for (int i = 0; i < _optionNames.length; i++) {
            _optionNames[i] = (String)names.get(i);
            _optionValues[i] = (String)vals.get(i);
        }
        
        String keyStr = getProperty(OWNER_KEY);
        if (keyStr == null) throw new IOException("Owner not found");
        _key = new SigningPublicKey(Base64.decode(keyStr));
        
        String postersStr = getProperty(POSTERS);
        if (postersStr != null) {
            StringTokenizer tok = new StringTokenizer(postersStr, ", \t");
            _posters = new SigningPublicKey[tok.countTokens()];
            for (int i = 0; tok.hasMoreTokens(); i++)
                _posters[i] = new SigningPublicKey(Base64.decode(tok.nextToken()));
        }
        
        String sigStr = getProperty(SIGNATURE);
        if (sigStr == null) throw new IOException("Signature not found");
        _signature = new Signature(Base64.decode(sigStr));
    }
    
    public void write(OutputStream out) throws IOException { write(out, true); }
    public void write(OutputStream out, boolean includeRealSignature) throws IOException {
        StringBuffer buf = new StringBuffer(512);
        for (int i = 0; i < _optionNames.length; i++) {
            if ( (includeRealSignature) || (!SIGNATURE.equals(_optionNames[i])) )
                buf.append(_optionNames[i]).append(':').append(_optionValues[i]).append('\n');
        }
        String s = buf.toString();
        out.write(s.getBytes());
    }
    
    public String getProperty(String name) {
        for (int i = 0; i < _optionNames.length; i++) {
            if (_optionNames[i].equals(name))
                return _optionValues[i];
        }
        return null;
    }
    
    private void setProperty(String name, String val) {
        for (int i = 0; i < _optionNames.length; i++) {
            if (_optionNames[i].equals(name)) {
                _optionValues[i] = val;
                return;
            }
        }
            
        String names[] = new String[_optionNames.length + 1];
        String values[] = new String[_optionValues.length + 1];
        for (int i = 0; i < _optionNames.length; i++) {
            names[i] = _optionNames[i];
            values[i] = _optionValues[i];
        }
        names[names.length-1] = name;
        values[values.length-1] = val;
        _optionNames = names;
        _optionValues = values;
    }
    
    public int getEdition() { 
        String e = getProperty(EDITION);
        if (e != null) {
            try {
                return Integer.parseInt(e);
            } catch (NumberFormatException nfe) {
                return 0;
            }
        }
        return 0;
    }
    
    public String[] getProperties() { return _optionNames; }
    
    public SigningPublicKey[] getPosters() { return _posters; }
    public void setPosters(SigningPublicKey posters[]) { 
        _posters = posters; 
        StringBuffer buf = new StringBuffer();
        for (int i = 0; posters != null && i < posters.length; i++) {
            buf.append(Base64.encode(posters[i].getData()));
            if (i + 1 < posters.length)
                buf.append(',');
        }
        setProperty(POSTERS, buf.toString());
    }
    
    public boolean verify(I2PAppContext ctx) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(512);
            write(out, false);
            out.close();
            byte data[] = out.toByteArray();
            return ctx.dsa().verifySignature(_signature, data, _key);
        } catch (IOException ioe) {
            return false;
        }
    }
    
    public void sign(I2PAppContext ctx, SigningPrivateKey priv) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(512);
            write(out, false);
            byte data[] = out.toByteArray();
            Signature sig = ctx.dsa().sign(data, priv);
            if (sig == null)
                throw new IOException("wtf, why is the signature null? data.len = " + data.length + " priv: " + priv);
            setProperty(SIGNATURE, Base64.encode(sig.getData()));
            _signature = sig;
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }
    
    public String toString() {
        StringBuffer buf = new StringBuffer();
        buf.append("Blog ").append(getKey().calculateHash().toBase64());
        for (int i = 0; i < _optionNames.length; i++) {
            if ( (!SIGNATURE.equals(_optionNames[i])) &&
                 (!OWNER_KEY.equals(_optionNames[i])) &&
                 (!SIGNATURE.equals(_optionNames[i])) )
                buf.append(' ').append(_optionNames[i]).append(": ").append(_optionValues[i]);
        }

        if ( (_posters != null) && (_posters.length > 0) ) {
            buf.append(" additional posts by");
            for (int i = 0; i < _posters.length; i++) {
                buf.append(' ').append(_posters[i].calculateHash().toBase64());
                if (i + 1 < _posters.length)
                    buf.append(',');
            }
        }
        return buf.toString();
    }
    
    public static void main(String args[]) {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        /*
        try {
            Object keys[] = ctx.keyGenerator().generateSigningKeypair();
            SigningPublicKey pub = (SigningPublicKey)keys[0];
            SigningPrivateKey priv = (SigningPrivateKey)keys[1];

            Properties opts = new Properties();
            opts.setProperty("Name", "n4m3");
            opts.setProperty("Description", "foo");
            opts.setProperty("Edition", "0");
            opts.setProperty("ContactURL", "u@h.org");

            BlogInfo info = new BlogInfo(pub, null, opts);
            System.err.println("\n");
            System.err.println("\n");
            info.sign(ctx, priv);
            System.err.println("\n");
            boolean ok = info.verify(ctx);
            System.err.println("\n");
            System.err.println("sign&verify: " + ok);
            System.err.println("\n");
            System.err.println("\n");
            
            FileOutputStream o = new FileOutputStream("bloginfo-test.dat");
            info.write(o, true);
            o.close();
            FileInputStream i = new FileInputStream("bloginfo-test.dat");
            byte buf[] = new byte[4096];
            int sz = DataHelper.read(i, buf);
            BlogInfo read = new BlogInfo();
            read.load(new ByteArrayInputStream(buf, 0, sz));
            ok = read.verify(ctx);
            System.err.println("write to disk, verify read: " + ok);
            System.err.println("Data: " + Base64.encode(buf, 0, sz));
            System.err.println("Str : " + new String(buf, 0, sz));
        } catch (Exception e) { e.printStackTrace(); }
        */
        try {
            FileInputStream in = new FileInputStream(args[0]);
            BlogInfo info = new BlogInfo();
            info.load(in);
            boolean ok = info.verify(I2PAppContext.getGlobalContext());
            System.out.println("OK? " + ok + " :" + info);
        } catch (Exception e) { e.printStackTrace(); }
    }
}
