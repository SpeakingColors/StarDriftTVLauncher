package dev.tvlauncher.home;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * A tiny ADB client (no dependencies): connects to the TV's own adbd over TCP (normally 127.0.0.1:5555),
 * authenticates with an RSA key kept in the app's private storage, and runs shell commands.
 * Protocol: https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/protocol.txt
 *
 * Uses the legacy shell service (stdout and stderr merged, no exit code), which every adbd supports.
 */
public final class AdbClient implements Closeable {

    /** Called when adbd does not know our key yet: the TV shows "Allow USB debugging?" and waits for the user. */
    public interface AuthListener { void onAllowPrompt(); }

    private static final int CNXN = cmd("CNXN");
    private static final int AUTH = cmd("AUTH");
    private static final int OPEN = cmd("OPEN");
    private static final int OKAY = cmd("OKAY");
    private static final int WRTE = cmd("WRTE");
    private static final int CLSE = cmd("CLSE");

    private static final int VERSION = 0x01000000;
    private static final int MAX_DATA = 1024 * 1024;
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    /** ASN.1 DigestInfo prefix for SHA-1: adbd verifies the 20 byte token as an already hashed value. */
    private static final byte[] SHA1_DIGEST_INFO = {
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private static final int KEY_BYTES = 256; // 2048 bit key, the only size adbd accepts
    private static final int KEY_WORDS = KEY_BYTES / 4;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private int nextId = 1;

    private AdbClient(Socket s) throws IOException {
        socket = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
    }

    private static int cmd(String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        return (b[0] & 0xff) | (b[1] & 0xff) << 8 | (b[2] & 0xff) << 16 | (b[3] & 0xff) << 24;
    }

    // ---- key handling -------------------------------------------------------------------------------------

    /** Loads the key from keyFile, or creates and saves a new 2048 bit RSA key if there is none. */
    public static KeyPair loadOrCreateKey(File keyFile) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (keyFile.isFile()) {
            try {
                byte[] der = readAll(new FileInputStream(keyFile));
                PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(der));
                if (priv instanceof RSAPrivateCrtKey) {
                    RSAPrivateCrtKey crt = (RSAPrivateCrtKey) priv;
                    PublicKey pub = kf.generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
                    return new KeyPair(pub, priv);
                }
            } catch (Exception ignored) {
                // unreadable key file: fall through and make a new one
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        File tmp = new File(keyFile.getPath() + ".tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp)) {
            fo.write(kp.getPrivate().getEncoded());
            fo.getFD().sync();
        }
        if (!tmp.renameTo(keyFile)) throw new IOException("cannot save the key");
        return kp;
    }

    /** Android's own RSAPublicKey struct (libcrypto_utils android_pubkey.c), as adbd expects it. */
    static byte[] androidPublicKey(RSAPublicKey key) {
        BigInteger n = key.getModulus();
        if (n.bitLength() != KEY_BYTES * 8) throw new IllegalArgumentException("key must be 2048 bit");
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32)); // -1 / n[0] mod 2^32
        BigInteger rr = BigInteger.ONE.shiftLeft(KEY_BYTES * 8).modPow(BigInteger.valueOf(2), n); // (2^2048)^2 mod n
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + KEY_BYTES + KEY_BYTES + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(KEY_WORDS);
        b.putInt(n0inv.intValue());
        b.put(littleEndian(n, KEY_BYTES));
        b.put(littleEndian(rr, KEY_BYTES));
        b.putInt(key.getPublicExponent().intValue());
        return b.array();
    }

    private static byte[] littleEndian(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] le = new byte[len];
        for (int i = 0; i < len && i < be.length; i++) le[i] = be[be.length - 1 - i];
        return le;
    }

    static byte[] signToken(PrivateKey key, byte[] token) throws Exception {
        Signature s = Signature.getInstance("NONEwithRSA");
        s.initSign(key);
        s.update(SHA1_DIGEST_INFO);
        s.update(token);
        return s.sign();
    }

    // ---- connection ---------------------------------------------------------------------------------------

    /**
     * @param authTimeoutMs how long to wait for the user to tap Allow on the TV
     */
    public static AdbClient connect(KeyPair key, String host, int port, int connectTimeoutMs, int authTimeoutMs,
                                    AuthListener listener) throws Exception {
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            s.setSoTimeout(10000);
            AdbClient c = new AdbClient(s);
            c.handshake(key, authTimeoutMs, listener);
            s.setSoTimeout(0);
            return c;
        } catch (Exception e) {
            try { s.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    private void handshake(KeyPair key, int authTimeoutMs, AuthListener listener) throws Exception {
        send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
        Msg m = read();
        if (m.cmd == CNXN) return;
        if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected reply from adbd");
        send(AUTH, AUTH_SIGNATURE, 0, signToken(key.getPrivate(), m.data));
        m = read();
        if (m.cmd == CNXN) return;
        if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected reply from adbd");
        // key unknown to adbd: offer it, the TV asks the user
        String pub = Base64.getEncoder().encodeToString(androidPublicKey((RSAPublicKey) key.getPublic()));
        send(AUTH, AUTH_RSAPUBLICKEY, 0, (pub + " stardrift@tv\0").getBytes(StandardCharsets.US_ASCII));
        if (listener != null) listener.onAllowPrompt();
        socket.setSoTimeout(authTimeoutMs);
        m = read();
        if (m.cmd != CNXN) throw new IOException("the TV did not allow USB debugging");
    }

    /** Runs one shell command and returns what it printed (stdout and stderr together). */
    public String shell(String command, int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
        int local = nextId++;
        send(OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        int remote = -1;
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        while (true) {
            Msg m = read();
            if (m.cmd == OKAY) {
                if (m.arg1 == local) remote = m.arg0;
            } else if (m.cmd == WRTE) {
                if (m.arg1 == local) {
                    result.write(m.data, 0, m.data.length);
                    send(OKAY, local, m.arg0, new byte[0]);
                }
            } else if (m.cmd == CLSE) {
                if (m.arg1 == local || m.arg1 == 0) {
                    if (remote == -1) throw new IOException("adbd refused the command");
                    return new String(result.toByteArray(), StandardCharsets.UTF_8);
                }
            }
        }
    }

    @Override public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }

    // ---- wire format --------------------------------------------------------------------------------------

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd);
        out.write(h.array());
        if (data.length > 0) out.write(data);
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] hb = new byte[24];
        readFully(hb);
        ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        h.getInt(); // data checksum, not checked
        int magic = h.getInt();
        if (magic != ~m.cmd) throw new IOException("bad ADB packet");
        if (len < 0 || len > MAX_DATA) throw new IOException("bad ADB packet size");
        m.data = new byte[len];
        if (len > 0) readFully(m.data);
        return m;
    }

    private void readFully(byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new IOException("adbd closed the connection");
            off += n;
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        try (InputStream i = is) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = i.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }
}
