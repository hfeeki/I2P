package net.i2p.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * General helper methods for messing with files
 *
 */
public class FileUtil {
    /**
     * Delete the path as well as any files or directories underneath it.
     * 
     * @param path path to the directory being deleted
     * @param failIfNotEmpty if true, do not delete anything if the directory
     *                       is not empty (and return false)
     * @return true if the path no longer exists (aka was removed), 
     *         false if it remains
     */
    public static final boolean rmdir(String path, boolean failIfNotEmpty) {
        return rmdir(new File(path), failIfNotEmpty);
    }
    /**
     * Delete the path as well as any files or directories underneath it.
     * 
     * @param target the file or directory being deleted
     * @param failIfNotEmpty if true, do not delete anything if the directory
     *                       is not empty (and return false)
     * @return true if the path no longer exists (aka was removed), 
     *         false if it remains
     */
    public static final boolean rmdir(File target, boolean failIfNotEmpty) {
        if (!target.exists()) {
            //System.out.println("info: target does not exist [" + target.getPath() + "]");
            return true;
        }
        if (!target.isDirectory()) {
            //System.out.println("info: target is not a directory [" + target.getPath() + "]");
            return target.delete();
        } else {
            File children[] = target.listFiles();
            if (children == null) {
                //System.out.println("info: target null children [" + target.getPath() + "]");
                return false;
            }
            if ( (failIfNotEmpty) && (children.length > 0) ) {
                //System.out.println("info: target is not emtpy[" + target.getPath() + "]");
                return false;
            }
            for (int i = 0; i < children.length; i++) {
                if (!rmdir(children[i], failIfNotEmpty))
                    return false;
                
                //System.out.println("info: target removed recursively [" + children[i].getPath() + "]");
            }
            return target.delete();
        }
    }
    
    public static boolean extractZip(File zipfile, File targetDir) {
        try {
            byte buf[] = new byte[16*1024];
            ZipFile zip = new ZipFile(zipfile);
            Enumeration entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = (ZipEntry)entries.nextElement();
                if (entry.getName().indexOf("..") != -1) {
                    System.err.println("ERROR: Refusing to extract a zip entry with '..' in it [" + entry.getName() + "]");
                    return false;
                }
                File target = new File(targetDir, entry.getName());
                File parent = target.getParentFile();
                if ( (parent != null) && (!parent.exists()) ) {
                    boolean parentsOk = parent.mkdirs();
                    if (!parentsOk) {
                        System.err.println("ERROR: Unable to create the parent dir for " + entry.getName() + ": [" + parent.getAbsolutePath() + "]");
                        return false;
                    }
                }
                if (entry.isDirectory()) {
                    if (!target.exists()) {
                        boolean created = target.mkdirs();
                        if (!created) {
                            System.err.println("ERROR: Unable to create the directory [" + entry.getName() + "]");
                            return false;
                        } else {
                            System.err.println("INFO: Creating directory [" + entry.getName() + "]");
                        }
                    }
                } else {
                    try {
                        InputStream in = zip.getInputStream(entry);
                        FileOutputStream fos = new FileOutputStream(target);
                        int read = 0;
                        while ( (read = in.read(buf)) != -1) {
                            fos.write(buf, 0, read);
                        }
                        fos.close();
                        in.close();
                        
                        System.err.println("INFO: File [" + entry.getName() + "] extracted");
                    } catch (IOException ioe) {
                        System.err.println("ERROR: Error extracting the zip entry (" + entry.getName() + "]");
                        ioe.printStackTrace();
                        return false;
                    }
                }
            }
            zip.close();
            return true;
        } catch (IOException ioe) {
            System.err.println("ERROR: Unable to extract the zip file");
            ioe.printStackTrace();
            return false;
        } 
    }
    
    /**
     * Read in the last few lines of a (newline delimited) textfile, or null if
     * the file doesn't exist.  
     *
     * @param startAtBeginning if true, read the first maxNumLines, otherwise read
     *                         the last maxNumLines
     *
     */
    public static String readTextFile(String filename, int maxNumLines, boolean startAtBeginning) {
        File f = new File(filename);
        if (!f.exists()) return null;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(f);
            BufferedReader in = new BufferedReader(new InputStreamReader(fis));
            List lines = new ArrayList(maxNumLines);
            String line = null;
            while ( (line = in.readLine()) != null) {
                lines.add(line);
                if (lines.size() >= maxNumLines) {
                    if (startAtBeginning)
                        break;
                    else
                        lines.remove(0);
                }
            }
            StringBuffer buf = new StringBuffer(lines.size() * 80);
            for (int i = 0; i < lines.size(); i++)
                buf.append((String)lines.get(i)).append('\n');
            return buf.toString();
        } catch (IOException ioe) {
            return null;
        } finally {
            if (fis != null) try { fis.close(); } catch (IOException ioe) {}
        }
    }
    
    /** return true if it was copied successfully */
    public static boolean copy(String source, String dest, boolean overwriteExisting) {
        File src = new File(source);
        File dst = new File(dest);

	if (dst.exists() && dst.isDirectory())
            dst = new File(dst, src.getName());
        
        if (!src.exists()) return false;
        if (dst.exists() && !overwriteExisting) return false;
        
        byte buf[] = new byte[4096];
        try {
            FileInputStream in = new FileInputStream(src);
            FileOutputStream out = new FileOutputStream(dst);
            
            int read = 0;
            while ( (read = in.read(buf)) != -1)
                out.write(buf, 0, read);
            
            in.close();
            out.close();
            return true;
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        }
    }
    
    /**
     * Usage: FileUtil (delete path | copy source dest)
     *
     */
    public static void main(String args[]) {
        if ( (args == null) || (args.length < 2) ) {
            testRmdir();
        } else if ("delete".equals(args[0])) {
            boolean deleted = FileUtil.rmdir(args[1], false);
            if (!deleted)
                System.err.println("Error deleting [" + args[1] + "]");
        } else if ("copy".equals(args[0])) {
            boolean copied = FileUtil.copy(args[1], args[2], false);
            if (!copied) 
                System.err.println("Error copying [" + args[1] + "] to [" + args[2] + "]");
        }
    }
    
    private static void testRmdir() {
        File t = new File("rmdirTest/test/subdir/blah");
        boolean created = t.mkdirs();
        if (!t.exists()) throw new RuntimeException("Unable to create test");
        boolean deleted = FileUtil.rmdir("rmdirTest", false);
        if (!deleted) 
            System.err.println("FAIL: unable to delete rmdirTest");
        else
            System.out.println("PASS: rmdirTest deleted");
    }
}
