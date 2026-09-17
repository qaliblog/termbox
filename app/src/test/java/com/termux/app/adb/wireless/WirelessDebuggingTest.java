/*
 * TermBox ADB bridge — Android 11+ Wireless Debugging tests.
 *
 * The pairing tests run the REAL protocol end-to-end over loopback TLS:
 * the production PairingConnection (client) against FakePairingServer
 * (device), exercising TLS 1.3, the RFC 8446 keying-material export under
 * "adb-label\0", the SPAKE2 exchange, and the AES-128-GCM PeerInfo exchange.
 * No part of the protocol is mocked.
 */
package com.termux.app.adb.wireless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.security.cert.X509Certificate;

import com.termux.app.adb.remote.RemoteDevice;

/** Wireless Debugging pairing + secure transport integration tests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class WirelessDebuggingTest {

    private static final String CODE = "123456";
    private android.content.Context mContext;
    private FakePairingServer mPairServer;
    private FakeSecureAdbd mAdbd;

    @Before
    public void setUp() throws Exception {
        mContext = org.robolectric.RuntimeEnvironment.getApplication();
        WirelessTransportManager.init(mContext);
        mPairServer = null;
        mAdbd = null;
    }

    @After
    public void tearDown() {
        // Drop transports and quiet the reconnect thread between tests.
        WirelessTransportManager.disconnect(null, null);
        if (mPairServer != null) mPairServer.stop();
        if (mAdbd != null) mAdbd.stop();
    }

    // ---------- pairing (real TLS + SPAKE2 over loopback) ----------

    @Test
    public void pairWithCorrectCodeCompletesAndStoresGuid() throws Exception {
        mPairServer = new FakePairingServer();
        mPairServer.setPairingCode(CODE);

        PairingConnection.Result result = PairingConnection.pair(
            "127.0.0.1", mPairServer.port, CODE.getBytes("US-ASCII"),
            () -> mPairServer.serverCert, () -> mPairServer.serverKey,
            "BASE64KEY termbox@termbox\0", 10_000);

        assertNotNull(result);
        assertEquals("adb-T0F0B0G0-fake", result.deviceGuid);
        assertTrue("server must have decrypted the client's PeerInfo",
            mPairServer.awaitClientInfo(5000));
        assertEquals("client must present the pubkey line as its PeerInfo",
            "BASE64KEY termbox@termbox",
            new String(mPairServer.receivedKeyLine.get(), "US-ASCII"));
        assertNull("no protocol failure on the device side", mPairServer.failure());
    }

    @Test
    public void pairWithWrongCodeFailsWithHonestMessage() throws Exception {
        mPairServer = new FakePairingServer();
        mPairServer.setPairingCode("999999");

        try {
            PairingConnection.pair("127.0.0.1", mPairServer.port,
                CODE.getBytes("US-ASCII"),
                () -> mPairServer.serverCert, () -> mPairServer.serverKey,
                "BASE64KEY termbox@termbox\0", 10_000);
            fail("pairing with a wrong code must not succeed");
        } catch (PairingConnection.PairingException e) {
            assertTrue("failure must be the honest wrong-code verdict, got: "
                + e.getMessage(),
                e.getMessage().contains("wrong pairing code")
                    || e.getMessage().contains("invalid SPAKE2 message"));
            // No pairing code or key material may leak into the message.
            for (String banned : new String[]{"123456", "999999", "BASE64KEY"}) {
                assertTrue("message must not echo secrets", !e.getMessage().contains(banned));
            }
        }
        assertEquals("no successful exchange recorded", 0, mPairServer.exchanges());
    }

    @Test
    public void pairFailsAgainstNonPairingEndpoint() throws Exception {
        // A plain TCP listener that never completes the TLS handshake.
        java.net.ServerSocket plain = new java.net.ServerSocket(0,
            1, java.net.InetAddress.getLoopbackAddress());
        try {
            PairingConnection.PairingException e = null;
            try {
                PairingConnection.pair("127.0.0.1", plain.getLocalPort(),
                    CODE.getBytes("US-ASCII"),
                    () -> null, () -> null, "KEY\0", 2000);
                fail("pairing against a non-pairing endpoint must fail");
            } catch (PairingConnection.PairingException caught) {
                e = caught;
            }
            assertNotNull(e);
        } finally {
            plain.close();
        }
    }

    // ---------- transport manager (Robolectric end-to-end) ----------

    @Test
    public void pairThenConnectThenDevicesThenForget_endToEnd() throws Exception {
        mPairServer = new FakePairingServer();
        mAdbd = new FakeSecureAdbd();

        // 1. Pair (real SPAKE2/TLS) — persisted with guid + host.
        String pairMsg = WirelessTransportManager.pair("127.0.0.1",
            mPairServer.port, CODE);
        assertTrue("pairing must succeed, got: " + pairMsg,
            pairMsg.startsWith("Successfully paired"));

        WirelessDeviceStore store = WirelessDeviceStore.get(mContext);
        assertEquals(1, store.all().size());
        WirelessDeviceStore.PairedDevice paired = store.all().get(0);
        assertEquals("127.0.0.1", paired.host);

        // 2. Connect: TLS 1.3 + no-AUTH CNXN against the fake secure adbd.
        String connectMsg = WirelessTransportManager.connect(
            "127.0.0.1", mAdbd.port);
        assertTrue("connect must succeed against the secure adbd, got: "
            + connectMsg, connectMsg.startsWith("connected to"));

        WirelessDeviceStore.PairedDevice after = store.all().get(0);
        assertEquals("working ADB port must be remembered",
            mAdbd.port, after.lastAdbPort);

        // 3. The transport is registered and selectable by serial.
        RemoteDevice dev = WirelessTransportManager.bySpec(
            "127.0.0.1:" + mAdbd.port);
        assertNotNull(dev);
        assertTrue(dev.isOnline());
        assertEquals(1, WirelessTransportManager.devices().size());

        // 4. Disconnect drops it.
        String dmsg = WirelessTransportManager.disconnect("127.0.0.1", mAdbd.port);
        assertTrue(dmsg.contains("disconnected"));
        assertEquals(0, WirelessTransportManager.devices().size());
        assertNull(WirelessTransportManager.bySpec("127.0.0.1:" + mAdbd.port));

        // 5. Forget pairing clears the store entry.
        store.remove(after.guid);
        assertEquals(0, store.all().size());
    }

    @Test
    public void connectToUnknownHostReportsHonestFailure() throws Exception {
        String msg = WirelessTransportManager.connect("127.0.0.1", 1); // closed port
        assertTrue("must be an honest failure, got: " + msg,
            msg.startsWith("cannot connect to"));
        assertEquals("no transport may be registered on failure",
            0, WirelessTransportManager.devices().size());
    }

    @Test
    public void pairingCodeValidationIsEnforced() {
        assertEquals("error: pairing code must be exactly 6 digits",
            WirelessTransportManager.pair("127.0.0.1", 1, "12345"));
        assertEquals("error: pairing code must be exactly 6 digits",
            WirelessTransportManager.pair("127.0.0.1", 1, "1234567"));
        assertEquals("error: pairing code must be exactly 6 digits",
            WirelessTransportManager.pair("127.0.0.1", 1, "abcdef"));
        // Nothing persisted from the invalid attempts.
        assertEquals(0, WirelessDeviceStore.get(mContext).all().size());
    }

    @Test
    public void connectIfPairedRoutesWirelessEndpointsToTls() throws Exception {
        mAdbd = new FakeSecureAdbd();
        // Pre-seed the store as if this endpoint was paired earlier.
        WirelessDeviceStore.get(mContext).put("adb-guid-route", "127.0.0.1",
            mAdbd.port, null);

        String msg = WirelessTransportManager.connectIfPaired("127.0.0.1",
            mAdbd.port);
        assertNotNull("paired endpoint must take the TLS path", msg);
        assertTrue(msg, msg.startsWith("connected to"));
    }

    @Test
    public void connectIfPairedIgnoresUnknownEndpoints() {
        assertNull("unpaired endpoint must not be hijacked into TLS",
            WirelessTransportManager.connectIfPaired("203.0.113.9", 5555));
    }

    @Test
    public void tlsIdentityDerivesFromAdbKeyAndPersists() throws Exception {
        X509Certificate cert = WirelessTls.identityCert(mContext);
        java.security.PrivateKey key = WirelessTls.identityKey(mContext);
        assertNotNull(cert);
        assertNotNull(key);

        // Same identity on second call (persisted, not regenerated).
        X509Certificate again = WirelessTls.identityCert(mContext);
        assertEquals(cert, again);

        // The cert's public key must be the ADB RSA public key.
        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
        java.security.interfaces.RSAPrivateCrtKey crt =
            (java.security.interfaces.RSAPrivateCrtKey) key;
        java.security.interfaces.RSAPublicKey adbPub = (java.security.interfaces.RSAPublicKey)
            kf.generatePublic(new java.security.spec.RSAPublicKeySpec(
                crt.getModulus(), crt.getPublicExponent()));
        assertEquals("TLS identity must bind to the ADB RSA key",
            adbPub, cert.getPublicKey());
    }
}
