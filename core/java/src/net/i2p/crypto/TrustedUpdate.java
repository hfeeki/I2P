package net.i2p.crypto;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.SequenceInputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.StringTokenizer;

import net.i2p.CoreVersion;
import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.util.Log;

/**
 * <p>Handles DSA signing and verification of update files.
 * </p>
 * <p>For convenience this class also makes certain operations available via the
 * command line. These can be invoked as follows:
 * </p>
 * <pre>
 * java net.i2p.crypto.TrustedUpdate keygen       <i>publicKeyFile privateKeyFile</i>
 * java net.i2p.crypto.TrustedUpdate showversion  <i>signedFile</i>
 * java net.i2p.crypto.TrustedUpdate sign         <i>inputFile signedFile privateKeyFile version</i>
 * java net.i2p.crypto.TrustedUpdate verifysig    <i>signedFile</i>
 * java net.i2p.crypto.TrustedUpdate verifyupdate <i>signedFile</i>
 * </pre>
 * 
 * @author jrandom and smeghead
 */
public class TrustedUpdate {

    /**
     * <p>Default trusted key generated by jrandom@i2p.net. This can be
     * authenticated via <code>gpg</code> without modification:</p>
     * <p>
     * <code>gpg --verify TrustedUpdate.java</code></p>
     */
/*
-----BEGIN PGP SIGNED MESSAGE-----
Hash: SHA1

*/
    private static final String DEFAULT_TRUSTED_KEY =
        "W4kJbnv9KSVwbnapV7SaNW2kMIZKs~hwL0ro9pZXFo1xTwqz45nykCp1H" +
        "M7sAKYDZay5z1HvYYOl9CNVz00xF03KPU9RUCVxhDZ1YXhZIskPKjUPUs" +
        "CIpE~Z1C~N9KSEV6~2stDlBNH10VZ4T0X1TrcXwb3IBXliWo2y2GAx~Ow=";
/*
-----BEGIN PGP SIGNATURE-----
Version: GnuPG v1.2.4 (GNU/Linux)

iD8DBQFCZ38IWYfZ3rPnHH0RAgOHAJ4wNgmfO2AkL8IXiGnPtWrTlXcVogCfQ79z
jP69nPbh4KLGhF+SD0+0bW4=
=npPe
-----END PGP SIGNATURE-----
*/
    private static final String VALID_VERSION_CHARS = "0123456789.";
    private static final int    VERSION_BYTES       = 16;
    private static final int    HEADER_BYTES        = Signature.SIGNATURE_BYTES + VERSION_BYTES;
    private static final String PROP_TRUSTED_KEYS   = "router.trustedUpdateKeys";

    private static I2PAppContext _context;

    private Log       _log;
    private ArrayList _trustedKeys;

    /**
     * Constructs a new <code>TrustedUpdate</code> with the default global
     * context.
     */
    public TrustedUpdate() {
        this(I2PAppContext.getGlobalContext());
    }

    /**
     * Constructs a new <code>TrustedUpdate</code> with the given
     * {@link net.i2p.I2PAppContext}.
     * 
     * @param context An instance of <code>I2PAppContext</code>.
     */
    public TrustedUpdate(I2PAppContext context) {
        _context = context;
        _log = _context.logManager().getLog(TrustedUpdate.class);
        _trustedKeys = new ArrayList();

        String propertyTrustedKeys = context.getProperty(PROP_TRUSTED_KEYS);

        if ( (propertyTrustedKeys != null) && (propertyTrustedKeys.length() > 0) ) {
            StringTokenizer propertyTrustedKeysTokens = new StringTokenizer(propertyTrustedKeys, ",");

            while (propertyTrustedKeysTokens.hasMoreTokens())
                _trustedKeys.add(propertyTrustedKeysTokens.nextToken().trim());

        } else {
            _trustedKeys.add(DEFAULT_TRUSTED_KEY);
        }
    }

    /**
     * Parses command line arguments when this class is used from the command
     * line.
     * 
     * @param args Command line parameters.
     */
    public static void main(String[] args) {
        try {
            if ("keygen".equals(args[0])) {
                genKeysCLI(args[1], args[2]);
            } else if ("showversion".equals(args[0])) {
                showVersionCLI(args[1]);
            } else if ("sign".equals(args[0])) {
                signCLI(args[1], args[2], args[3], args[4]);
            } else if ("verifysig".equals(args[0])) {
                verifySigCLI(args[1]);
            } else if ("verifyupdate".equals(args[0])) {
                verifyUpdateCLI(args[1]);
            } else {
                showUsageCLI();
            }
        } catch (ArrayIndexOutOfBoundsException aioobe) {
            showUsageCLI();
        }
    }

    /**
     * Checks if the given version is newer than the given current version.
     * 
     * @param currentVersion The current version.
     * @param newVersion     The version to test.
     * 
     * @return <code>true</code> if the given version is newer than the current
     *         version, otherwise <code>false</code>.
     */
    public static final boolean needsUpdate(String currentVersion, String newVersion) {
        StringTokenizer newVersionTokens = new StringTokenizer(sanitize(newVersion), ".");
        StringTokenizer currentVersionTokens = new StringTokenizer(sanitize(currentVersion), ".");

        while (newVersionTokens.hasMoreTokens() && currentVersionTokens.hasMoreTokens()) {
            String newNumber = newVersionTokens.nextToken();
            String currentNumber = currentVersionTokens.nextToken();

            switch (compare(newNumber, currentNumber)) {
                case -1: // newNumber is smaller
                    return false;
                case 0: // eq
                    break;
                case 1: // newNumber is larger
                    return true;
            }
        }

        if (newVersionTokens.hasMoreTokens() && !currentVersionTokens.hasMoreTokens())
            return true;

        return false;
    }

    private static final int compare(String lop, String rop) {
        try {
            int left = Integer.parseInt(lop);
            int right = Integer.parseInt(rop);

            if (left < right) 
                return -1;
            else if (left == right)
                return 0;
            else
                return 1;
        } catch (NumberFormatException nfe) {
            return 0;
        }
    }

    private static final void genKeysCLI(String publicKeyFile, String privateKeyFile) {
        FileOutputStream fileOutputStream = null;

        _context = I2PAppContext.getGlobalContext();
        try {
            Object signingKeypair[] = _context.keyGenerator().generateSigningKeypair();
            SigningPublicKey signingPublicKey = (SigningPublicKey) signingKeypair[0];
            SigningPrivateKey signingPrivateKey = (SigningPrivateKey) signingKeypair[1];

            fileOutputStream = new FileOutputStream(publicKeyFile);
            signingPublicKey.writeBytes(fileOutputStream);
            fileOutputStream.close();
            fileOutputStream = null;

            fileOutputStream = new FileOutputStream(privateKeyFile);
            signingPrivateKey.writeBytes(fileOutputStream);

            System.out.println("\r\nPrivate key written to: " + privateKeyFile);
            System.out.println("Public key written to: " + publicKeyFile);
            System.out.println("\r\nPublic key: " + signingPublicKey.toBase64() + "\r\n");
        } catch (Exception e) {
            System.err.println("Error writing keys:");
            e.printStackTrace();
        } finally {
            if (fileOutputStream != null)
                try {
                    fileOutputStream.close();
                } catch (IOException ioe) {
                }
        }
    }

    private static final String sanitize(String versionString) {
        StringBuffer versionStringBuffer = new StringBuffer(versionString);

        for (int i = 0; i < versionStringBuffer.length(); i++) {
            if (VALID_VERSION_CHARS.indexOf(versionStringBuffer.charAt(i)) == -1) {
                versionStringBuffer.deleteCharAt(i);
                i--;
            }
        }

        return versionStringBuffer.toString();
    }

    private static final void showUsageCLI() {
        System.err.println("Usage: TrustedUpdate keygen       publicKeyFile privateKeyFile");
        System.err.println("       TrustedUpdate showversion  signedFile");
        System.err.println("       TrustedUpdate sign         inputFile signedFile privateKeyFile version");
        System.err.println("       TrustedUpdate verifysig    signedFile");
        System.err.println("       TrustedUpdate verifyupdate signedFile");
    }

    private static final void showVersionCLI(String signedFile) {
        String versionString = new TrustedUpdate().getVersionString(signedFile);

        if (versionString == "")
            System.out.println("No version string found in file '" + signedFile + "'");
        else
            System.out.println("Version: " + versionString);
    }

    private static final void signCLI(String inputFile, String signedFile, String privateKeyFile, String version) {
        Signature signature = new TrustedUpdate().sign(inputFile, signedFile, privateKeyFile, version);

        if (signature != null)
            System.out.println("Input file '" + inputFile + "' signed and written to '" + signedFile + "'");
        else
            System.out.println("Error signing input file '" + inputFile + "'");
    }

    private static final void verifySigCLI(String signedFile) {
        boolean isValidSignature = new TrustedUpdate().verify(signedFile);

        if (isValidSignature)
            System.out.println("Signature VALID");
        else
            System.out.println("Signature INVALID");
    }

    private static final void verifyUpdateCLI(String signedFile) {
        boolean isUpdate = new TrustedUpdate().isUpdatedVersion(CoreVersion.VERSION, signedFile);

        if (isUpdate)
            System.out.println("File version is newer than current version.");
        else
            System.out.println("File version is older than or equal to current version.");
    }

    /**
     * Fetches the trusted keys for the current instance.
     * 
     * @return An <code>ArrayList</code> containting the trusted keys.
     */
    public ArrayList getTrustedKeys() {
        return _trustedKeys;
    }

    /**
     * Reads the version string from a signed update file.
     * 
     * @param signedFile A signed update file.
     * 
     * @return The version string read, or an empty string if no version string
     *         is present.
     */
    public String getVersionString(String signedFile) {
        FileInputStream fileInputStream = null;

        try {
            fileInputStream = new FileInputStream(signedFile);
            long skipped = fileInputStream.skip(Signature.SIGNATURE_BYTES);
            if (skipped != Signature.SIGNATURE_BYTES)
                return "";
            byte[] data = new byte[VERSION_BYTES];
            int bytesRead = DataHelper.read(fileInputStream, data);

            if (bytesRead != VERSION_BYTES) {
                return "";
            }

            for (int i = 0; i < VERSION_BYTES; i++) 
                if (data[i] == 0x00) {
                    return new String(data, 0, i, "UTF-8");
                }

            return new String(data, "UTF-8");
        } catch (UnsupportedEncodingException uee) {
            throw new RuntimeException("wtf, your JVM doesnt support utf-8? " + uee.getMessage());
        } catch (IOException ioe) {
            return "";
        } finally {
            if (fileInputStream != null)
                try {
                    fileInputStream.close();
                } catch (IOException ioe) {
                }
        }
    }

    /**
     * Verifies that the version of the given signed update file is newer than
     * <code>currentVersion</code>.
     * 
     * @param currentVersion The current version to check against.
     * @param signedFile     The signed update file.
     * 
     * @return <code>true</code> if the signed update file's version is newer
     *         than the current version, otherwise <code>false</code>.
     */
    public boolean isUpdatedVersion(String currentVersion, String signedFile) {
        if (needsUpdate(currentVersion, getVersionString(signedFile)))
            return true;
        else
            return false;
    }

    /**
     * Verifies the signature of a signed update file, and if it's valid and the
     * file's version is newer than the given current version, migrates the data
     * out of <code>signedFile</code> and into <code>outputFile</code>.
     * 
     * @param currentVersion The current version to check against.
     * @param signedFile     A signed update file.
     * @param outputFile     The file to write the verified data to.
     * 
     * @return <code>true</code> if the signature and version were valid and the
     *         data was moved, <code>false</code> otherwise.
     */
    public boolean migrateVerified(String currentVersion, String signedFile, String outputFile) {
        if (!isUpdatedVersion(currentVersion, signedFile))
            return false;

        if (!verify(signedFile))
            return false;

        FileInputStream fileInputStream = null;
        FileOutputStream fileOutputStream = null;

        try {
            fileInputStream = new FileInputStream(signedFile);
            fileOutputStream = new FileOutputStream(outputFile);
            long skipped = 0;

            while (skipped < HEADER_BYTES)
                skipped += fileInputStream.skip(HEADER_BYTES - skipped);

            byte[] buffer = new byte[1024];
            int bytesRead = 0;

            while ( (bytesRead = fileInputStream.read(buffer)) != -1) 
                fileOutputStream.write(buffer, 0, bytesRead);
        } catch (IOException ioe) {
            return false;
        } finally {
            if (fileInputStream != null)
                try {
                    fileInputStream.close();
                } catch (IOException ioe) {
                }

            if (fileOutputStream != null)
                try {
                    fileOutputStream.close();
                } catch (IOException ioe) {
                }
        }

        return true;
    }

    /**
     * Uses the given private key to sign the given input file along with its
     * version string using DSA. The output will be a signed update file where
     * the first 40 bytes are the resulting DSA signature, the next 16 bytes are
     * the input file's version string encoded in UTF-8 (padded with trailing
     * <code>0h</code> characters if necessary), and the remaining bytes are the
     * raw bytes of the input file.
     * 
     * @param inputFile      The file to be signed.
     * @param signedFile     The signed update file to write.
     * @param privateKeyFile The name of the file containing the private key to
     *                       sign <code>inputFile</code> with.
     * @param version        The version string of the input file. If this is
     *                       longer than 16 characters it will be truncated.
     * 
     * @return An instance of {@link net.i2p.data.Signature}, or
     *         <code>null</code> if there was an error.
     */
    public Signature sign(String inputFile, String signedFile, String privateKeyFile, String version) {
        FileInputStream fileInputStream = null;
        SigningPrivateKey signingPrivateKey = new SigningPrivateKey();

        try {
            fileInputStream = new FileInputStream(privateKeyFile);
            signingPrivateKey.readBytes(fileInputStream);
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to load the signing key", ioe);

            return null;
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to load the signing key", dfe);

            return null;
        } finally {
            if (fileInputStream != null)
                try {
                    fileInputStream.close();
                } catch (IOException ioe) {
                }
        }

        return sign(inputFile, signedFile, signingPrivateKey, version);
    }

    /**
     * Uses the given {@link net.i2p.data.SigningPrivateKey} to sign the given
     * input file along with its version string using DSA. The output will be a
     * signed update file where the first 40 bytes are the resulting DSA
     * signature, the next 16 bytes are the input file's version string encoded
     * in UTF-8 (padded with trailing <code>0h</code> characters if necessary),
     * and the remaining bytes are the raw bytes of the input file.
     * 
     * @param inputFile         The file to be signed.
     * @param signedFile        The signed update file to write.
     * @param signingPrivateKey An instance of <code>SigningPrivateKey</code>
     *                          to sign <code>inputFile</code> with.
     * @param version           The version string of the input file. If this is
     *                          longer than 16 characters it will be truncated.
     * 
     * @return An instance of {@link net.i2p.data.Signature}, or
     *         <code>null</code> if there was an error.
     */
    public Signature sign(String inputFile, String signedFile, SigningPrivateKey signingPrivateKey, String version) {
        byte[] versionHeader = {
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00 };
        byte[] versionRawBytes = null;

        if (version.length() > VERSION_BYTES)
            version = version.substring(0, VERSION_BYTES);

        try {
            versionRawBytes = version.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("wtf, your JVM doesnt support utf-8? " + e.getMessage());
        }

        System.arraycopy(versionRawBytes, 0, versionHeader, 0, versionRawBytes.length);

        FileInputStream fileInputStream = null;
        Signature signature = null;
        SequenceInputStream bytesToSignInputStream = null;
        ByteArrayInputStream versionHeaderInputStream = null;

        try {
            fileInputStream = new FileInputStream(inputFile);
            versionHeaderInputStream = new ByteArrayInputStream(versionHeader);
            bytesToSignInputStream = new SequenceInputStream(versionHeaderInputStream, fileInputStream);
            signature = _context.dsa().sign(bytesToSignInputStream, signingPrivateKey);

        } catch (Exception e) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error signing", e);

            return null;
        } finally {
            if (bytesToSignInputStream != null)
                try {
                    bytesToSignInputStream.close();
                } catch (IOException ioe) {
                }

            fileInputStream = null;
        }

        FileOutputStream fileOutputStream = null;

        try {
            fileOutputStream = new FileOutputStream(signedFile);
            fileOutputStream.write(signature.getData());
            fileOutputStream.write(versionHeader);
            fileInputStream = new FileInputStream(inputFile);
            byte[] buffer = new byte[1024];
            int bytesRead = 0;
            while ( (bytesRead = fileInputStream.read(buffer)) != -1) 
                fileOutputStream.write(buffer, 0, bytesRead);
            fileOutputStream.close();
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.log(Log.WARN, "Error writing signed file " + signedFile, ioe);

            return null;
        } finally {
            if (fileInputStream != null)
                try {
                    fileInputStream.close();
                } catch (IOException ioe) {
                }

            if (fileOutputStream != null)
                try {
                    fileOutputStream.close();
                } catch (IOException ioe) {
                }
        }

        return signature;
    }

    /**
     * Verifies the DSA signature of a signed update file.
     * 
     * @param signedFile The signed update file to check.
     * 
     * @return <code>true</code> if the file has a valid signature, otherwise
     *         <code>false</code>.
     */
    public boolean verify(String signedFile) {
        for (int i = 0; i < _trustedKeys.size(); i++) {
            SigningPublicKey signingPublicKey = new SigningPublicKey();

            try {
                signingPublicKey.fromBase64((String)_trustedKeys.get(i));
                boolean isValidSignature = verify(signedFile, signingPublicKey);

                if (isValidSignature)
                    return true;
            } catch (DataFormatException dfe) {
                _log.log(Log.CRIT, "Trusted key " + i + " is not valid");
            }
        }

        if (_log.shouldLog(Log.WARN))
            _log.warn("None of the keys match");

        return false;
    }

    /**
     * Verifies the DSA signature of a signed update file.
     * 
     * @param signedFile    The signed update file to check.
     * @param publicKeyFile A file containing the public key to use for
     *                      verification.
     * 
     * @return <code>true</code> if the file has a valid signature, otherwise
     *         <code>false</code>.
     */
    public boolean verify(String signedFile, String publicKeyFile) {
        SigningPublicKey signingPublicKey = new SigningPublicKey();
        FileInputStream fileInputStream = null;

        try {
            fileInputStream = new FileInputStream(signedFile);
            signingPublicKey.readBytes(fileInputStream);
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to load the signature", ioe);

            return false;
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to load the signature", dfe);

            return false;
        } finally {
            if (fileInputStream != null)
                try {
                    fileInputStream.close();
                } catch (IOException ioe) {
                }
        }

        return verify(signedFile, signingPublicKey);
    }

    /**
     * Verifies the DSA signature of a signed update file.
     * 
     * @param signedFile       The signed update file to check.
     * @param signingPublicKey An instance of
     *                         {@link net.i2p.data.SigningPublicKey} to use for
     *                         verification.
     * 
     * @return <code>true</code> if the file has a valid signature, otherwise
     *         <code>false</code>.
     */
    public boolean verify(String signedFile, SigningPublicKey signingPublicKey) {
        FileInputStream fileInputStream = null;

        try {
            fileInputStream = new FileInputStream(signedFile);
            Signature signature = new Signature();

            signature.readBytes(fileInputStream);

            return _context.dsa().verifySignature(signature, fileInputStream, signingPublicKey);
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error reading " + signedFile + " to verify", ioe);

            return false;
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error reading the signature", dfe);

            return false;
        } finally {
            if (fileInputStream != null)
                try {
                    fileInputStream.close();
                } catch (IOException ioe) {
                }
        }
    }
}
