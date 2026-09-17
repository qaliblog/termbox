/*
 * TermBox ADB bridge — TLS plumbing for Android 11+ Wireless Debugging.
 *
 * Both the pairing connection and the secure ADB transport need:
 *  - a TLS 1.3-only SSLContext presenting our ADB RSA key + a self-signed
 *    X.509 certificate (AOSP adb_wifi.cpp generates the certificate from the
 *    adb RSA private key and uses the client certificate as the identity the
 *    device matches against its pairing records), and
 *  - TLS keying-material export under the label "adb-label\0" (AOSP
 *    tls_connection.cpp kExportedKeyLabel — the trailing NUL is part of the
 *    label) to salt the SPAKE2 password.
 *
 * The stock JSSE has no exporter API, so this uses Conscrypt
 * (org.conscrypt:conscrypt-android on-device; conscrypt-openjdk-uber in unit
 * tests — identical static API). This mirrors what AOSP gets for free from
 * BoringSSL's SSL_export_keying_material in adb's tls_connection.cpp.
 */
package com.termux.app.adb.wireless;

import android.content.Context;

import org.conscrypt.Conscrypt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/** Conscrypt-backed TLS 1.3 context factory + keying-material exporter. */
public final class WirelessTls {

    /** AOSP tls_connection.cpp: kExportedKeyLabel[], sizeof includes the NUL. */
    public static final String EXPORTED_KEY_LABEL = "adb-label\u0000";
    /** pairing_connection.cpp kExportedKeySize. */
    public static final int EXPORTED_KEY_SIZE = 64;

    private static final String CERT_FILE = "adb-tls-cert.der";
    private static final String CERT_KEY_FILE = "adb-tls-cert-key.pk8.b64";

    private static final Object sIdentityLock = new Object();
    private static X509Certificate sCert;
    private static PrivateKey sKey;

    private WirelessTls() {
    }

    /**
     * TLS 1.3-only SSLContext presenting {@code cert} + {@code key} and
     * trusting any peer certificate (AOSP sets an any-cert verify callback;
     * the pairing code — not PKI — is what authenticates the session).
     */
    public static SSLContext newContext(X509Certificate cert, PrivateKey key)
        throws java.security.GeneralSecurityException {
        KeyManager[] kms = new KeyManager[]{new SingleCertKeyManager(cert, key)};
        SSLContext ctx = SSLContext.getInstance("TLS", Conscrypt.newProvider());
        ctx.init(kms, new javax.net.ssl.TrustManager[]{new TrustAll()}, new SecureRandom());
        return ctx;
    }

    /** Client-mode TLS 1.3 socket over an already-connected plain socket. */
    public static SSLSocket newClientSocket(SSLContext ctx, Socket raw, String host, int port)
        throws java.io.IOException {
        SSLSocket ssl = (SSLSocket) ctx.getSocketFactory()
            .createSocket(raw, host, port, true);
        ssl.setUseClientMode(true);
        ssl.setEnabledProtocols(new String[]{"TLSv1.3"});
        return ssl;
    }

    /**
     * RFC 8446 exporter with the AOSP adb label. Conscrypt exposes the
     * exporter as a static extension on SSLSocket.
     */
    public static byte[] exportKeyingMaterial(SSLSocket socket, String label, int length)
        throws javax.net.ssl.SSLException {
        return Conscrypt.exportKeyingMaterial(socket, label, null, length);
    }

    /** Our TLS identity certificate (created from the ADB RSA key on first use). */
    public static X509Certificate identityCert(Context context)
        throws java.io.IOException, java.security.GeneralSecurityException {
        ensureIdentity(context);
        return sCert;
    }

    /** The private key matching {@link #identityCert}. */
    public static PrivateKey identityKey(Context context)
        throws java.io.IOException, java.security.GeneralSecurityException {
        ensureIdentity(context);
        return sKey;
    }

    /**
     * Load or create the TLS identity. Like AOSP's adb_wifi.cpp, the
     * certificate self-signs the ADB RSA key, so the TLS identity the device
     * binds pairing records to is the same key that authorizes legacy
     * transports.
     */
    private static void ensureIdentity(Context context)
        throws java.io.IOException, java.security.GeneralSecurityException {
        synchronized (sIdentityLock) {
            if (sCert != null) return;
            File dir = new File(context.getFilesDir(), "adb");
            File certFile = new File(dir, CERT_FILE);
            File keyFile = new File(dir, CERT_KEY_FILE);
            if (certFile.isFile() && keyFile.isFile()) {
                try {
                    sCert = (X509Certificate) java.security.cert.CertificateFactory
                        .getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(readAll(certFile)));
                    byte[] pkcs8 = android.util.Base64.decode(
                        new String(readAll(keyFile), StandardCharsets.US_ASCII).trim(),
                        android.util.Base64.NO_WRAP);
                    sKey = KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
                    return;
                } catch (Exception e) {
                    sCert = null;
                    sKey = null;
                    // Fall through and regenerate.
                }
            }

            com.termux.app.adb.remote.AdbKeyPair adbPair;
            try {
                adbPair = com.termux.app.adb.remote.AdbKeyStore.get(context);
            } catch (java.io.IOException e) {
                throw e;
            } catch (Exception e) {
                throw new java.security.GeneralSecurityException("ADB key unavailable", e);
            }
            byte[] pkcs8 = adbPair.getPrivateKeyPkcs8();
            PrivateKey priv = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            // Derive (n, e) from the PKCS#8 DER itself. getKeySpec(...,
            // RSAPrivateCrtKeySpec.class) is NOT provider-agnostic: the JDK's
            // RSA KeyFactory casts the PrivateKey to RSAPrivateCrtKey and
            // throws ClassCastException for opaque key implementations (e.g.
            // Conscrypt's), while other providers return the non-CRT
            // RSAPrivateKeySpec. Parsing the DER works with every provider.
            java.math.BigInteger[] modulusExponent = pkcs8RsaModulusExponent(pkcs8);
            RSAPublicKey pub = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(
                    modulusExponent[0], modulusExponent[1]));

            X509Certificate cert = MiniCert.generate("termbox-adb", pub, priv);

            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new java.io.IOException("cannot create " + dir);
            }
            writeAll(certFile, cert.getEncoded());
            writeAll(keyFile, android.util.Base64.encodeToString(pkcs8,
                android.util.Base64.NO_WRAP).getBytes(StandardCharsets.US_ASCII));
            // Private material: owner-only read.
            keyFile.setReadable(false, false);
            keyFile.setReadable(true, true);
            certFile.setReadable(false, false);
            certFile.setReadable(true, true);

            sCert = cert;
            sKey = priv;
        }
    }

    /**
     * Extract (modulus, publicExponent) from a PKCS#8 RSAPrivateKey DER
     * (RFC 8017 A.1.2): SEQUENCE { version, n, e, d, p, q, ... }. Only the
     * two leading INTEGERs are read; the rest of the structure is skipped.
     */
    static java.math.BigInteger[] pkcs8RsaModulusExponent(byte[] pkcs8)
        throws java.security.GeneralSecurityException {
        try {
            java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(pkcs8);
            DerReader outer = new DerReader(readTlv(in));   // PrivateKeyInfo
            DerReader skipVersion = new DerReader(outer.next()); // version INTEGER
            skipVersion.finish();
            byte[] algorithmId = outer.next();               // AlgorithmIdentifier
            new DerReader(algorithmId).finish();
            byte[] privateKeyOctets = outer.next();          // OCTET STRING
            // (Optional [0] attributes may follow — intentionally not read.)
            DerReader pkcs1 = new DerReader(privateKeyOctets);
            DerReader skipPkcs1Version = new DerReader(pkcs1.next());
            skipPkcs1Version.finish();
            java.math.BigInteger n = new java.math.BigInteger(pkcs1.next()); // modulus
            java.math.BigInteger e = new java.math.BigInteger(pkcs1.next()); // publicExponent
            // (d, p, q, dp, dq, qinv follow — intentionally not read.)
            return new java.math.BigInteger[]{n, e};
        } catch (java.io.IOException e) {
            throw new java.security.GeneralSecurityException(
                "malformed PKCS#8 RSA private key", e);
        }
    }

    /** One DER TLV from {@code in}; returns tag byte followed by encoded contents. */
    private static byte[] readTlv(java.io.ByteArrayInputStream in) throws java.io.IOException {
        int tag = in.read();
        if (tag < 0) throw new java.io.EOFException("DER: truncated tag");
        int first = in.read();
        if (first < 0) throw new java.io.EOFException("DER: truncated length");
        int len;
        if ((first & 0x80) == 0) {
            len = first;
        } else {
            int count = first & 0x7f;
            if (count == 0 || count > 4) {
                throw new java.io.IOException("DER: unsupported length form");
            }
            len = 0;
            for (int i = 0; i < count; i++) {
                int b = in.read();
                if (b < 0) throw new java.io.EOFException("DER: truncated long length");
                len = (len << 8) | b;
            }
        }
        byte[] contents = new byte[len];
        int got = 0;
        while (got < len) {
            int n = in.read(contents, got, len - got);
            if (n < 0) throw new java.io.EOFException("DER: truncated contents");
            got += n;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(tag);
        writeDerLength(out, len);
        out.write(contents);
        return out.toByteArray();
    }

    private static void writeDerLength(java.io.ByteArrayOutputStream out, int len) {
        if (len < 0x80) {
            out.write(len);
        } else if (len < 0x100) {
            out.write(0x81);
            out.write(len);
        } else if (len < 0x10000) {
            out.write(0x82);
            out.write(len >>> 8);
            out.write(len);
        } else {
            out.write(0x83);
            out.write(len >>> 16);
            out.write(len >>> 8);
            out.write(len);
        }
    }

    /** Minimal DER reader: wraps a TLV, iterates over its contents. */
    private static final class DerReader {
        private final java.io.ByteArrayInputStream mIn;

        DerReader(byte[] tlv) throws java.io.IOException {
            java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(tlv);
            int tag = in.read();
            if (tag < 0) throw new java.io.IOException("DER: empty TLV");
            int first = in.read();
            if (first < 0) throw new java.io.IOException("DER: truncated length");
            int len;
            if ((first & 0x80) == 0) {
                len = first;
            } else {
                int count = first & 0x7f;
                if (count == 0 || count > 4) {
                    throw new java.io.IOException("DER: unsupported length form");
                }
                len = 0;
                for (int i = 0; i < count; i++) {
                    int b = in.read();
                    if (b < 0) throw new java.io.EOFException("DER: truncated long length");
                    len = (len << 8) | b;
                }
            }
            byte[] contents = new byte[len];
            int got = 0;
            while (got < len) {
                int n = in.read(contents, got, len - got);
                if (n < 0) throw new java.io.EOFException("DER: truncated contents");
                got += n;
            }
            mIn = new java.io.ByteArrayInputStream(contents);
        }

        /** The next child TLV, positioned inside the parent's contents. */
        byte[] next() throws java.io.IOException {
            return readTlv(mIn);
        }

        /** Nothing but this TLV's contents may remain. */
        void finish() throws java.io.IOException {
            if (mIn.read() >= 0) {
                throw new java.io.IOException("DER: trailing bytes");
            }
        }
    }

    // ---- single-cert KeyManager ----

    private static final class SingleCertKeyManager extends X509ExtendedKeyManager {
        private final X509Certificate cert;
        private final PrivateKey key;

        SingleCertKeyManager(X509Certificate cert, PrivateKey key) {
            this.cert = cert;
            this.key = key;
        }

        @Override
        public String chooseClientAlias(String[] keyType, java.security.Principal[] issuers,
                                        Socket socket) {
            return "termbox-adb";
        }

        @Override
        public String chooseServerAlias(String keyType, java.security.Principal[] issuers,
                                        Socket socket) {
            return "termbox-adb";
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return new X509Certificate[]{cert};
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return key;
        }

        @Override
        public String[] getClientAliases(String keyType, java.security.Principal[] issuers) {
            return new String[]{"termbox-adb"};
        }

        @Override
        public String[] getServerAliases(String keyType, java.security.Principal[] issuers) {
            return new String[]{"termbox-adb"};
        }
    }

    /** AOSP-style any-cert trust: the pairing code, not PKI, is the root. */
    private static final class TrustAll implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    private static byte[] readAll(File f) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void writeAll(File f, byte[] data) throws java.io.IOException {
        try (java.io.OutputStream out = new java.io.FileOutputStream(f)) {
            out.write(data);
        }
    }
}
