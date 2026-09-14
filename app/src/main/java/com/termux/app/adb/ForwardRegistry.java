package com.termux.app.adb;

import android.net.LocalServerSocket;
import android.net.LocalSocketAddress;
import android.net.LocalSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Forward/reverse registry for the bridge.
 *
 * `adb forward` listeners run inside the bridge server (the "host" role):
 * guest processes connect to the local endpoint and are bridged to the remote
 * device endpoint. `adb reverse` listeners run on the "device" side (this
 * server, which IS the device process): the device listens and connects back
 * to the host endpoint when a device-side connection arrives.
 *
 * Guest and host share one loopback and network namespace (proot does not
 * namespace the network), so both directions reduce to listener + connect with
 * swapped roles. Supported endpoints: tcp:PORT, localabstract:NAME (real
 * AF_UNIX abstract sockets via android.net.LocalServerSocket — visible from
 * the guest because the abstract namespace is shared), and local:PATH (a
 * filesystem socket inside the app sandbox). jdwp: is refused with the same
 * error a real device gives a caller with nothing to connect to.
 */
final class ForwardRegistry {

    private static final String LOG_TAG = TermboxAdbBridge.LOG_TAG;

    private static final Object sLock = new Object();
    private static final List<Forward> sForwards = new ArrayList<>();

    /** The listener could not be bound (a real bind error; see the error text). */
    static final int ERR_CANNOT_BIND = -1;

    /** A listener for the same local endpoint exists and `norebind` was set. */
    static final int ERR_CANNOT_REBIND = -2;

    ForwardRegistry() {
    }

    // ---------- registry (host:forward / reverse:forward semantics) ----------

    /**
     * Register a listener for local;remote.
     *
     * Mirrors AOSP install_listener: the forward and reverse directions have
     * separate namespaces (on a real device the forward listeners live on the
     * host and the reverse listeners on the device), an existing listener for
     * the same local endpoint is *replaced* unless `norebind` is set, and a
     * `tcp:0` request is stored under the port it actually bound so that
     * list-forward and later killforward/rebind requests address it by name.
     *
     * @param reverse   true for a device-side (`reverse:`) listener.
     * @param local     the endpoint this side listens on.
     * @param remote    the endpoint connections are relayed to.
     * @param norebind  refuse to replace an existing listener.
     * @param errorOut  receives the human-readable failure text when one occurs.
     * @return the resolved TCP port (0 for non-tcp endpoints, or when the caller
     *         requested a specific port), or ERR_CANNOT_BIND / ERR_CANNOT_REBIND.
     */
    static int addForward(boolean reverse, String local, String remote, boolean norebind,
                          StringBuilder errorOut) {
        synchronized (sLock) {
            for (int i = 0; i < sForwards.size(); i++) {
                Forward f = sForwards.get(i);
                if (f.reverse != reverse || !f.localSpec.equals(local)) continue;
                if (norebind) {
                    if (errorOut != null) errorOut.append("cannot rebind existing socket");
                    return ERR_CANNOT_REBIND;
                }
                // AOSP install_listener: without --no-rebind the existing
                // listener is replaced rather than refused.
                f.stop();
                sForwards.remove(i);
                break;
            }
            Forward f;
            try {
                f = new Forward(reverse, local, remote);
            } catch (IOException e) {
                TermboxAdbBridge.logWarn(LOG_TAG,
                    "forward " + local + " failed: " + e.getMessage());
                if (errorOut != null) {
                    errorOut.append(e.getMessage() == null ? "bind failed" : e.getMessage());
                }
                return ERR_CANNOT_BIND;
            }
            sForwards.add(f);
            f.start();
            return f.resolvedTcpPort;
        }
    }

    /**
     * Remove the listener whose local endpoint matches, within one direction.
     * AOSP remove_listener addresses listeners by their canonical local name.
     */
    static boolean killForward(boolean reverse, String localSpec) {
        synchronized (sLock) {
            for (int i = 0; i < sForwards.size(); i++) {
                Forward f = sForwards.get(i);
                if (f.reverse == reverse && f.localSpec.equals(localSpec)) {
                    f.stop();
                    sForwards.remove(i);
                    return true;
                }
            }
            return false;
        }
    }

    /** remove_all_listeners for one direction (host forwards or device reverses). */
    static void killForwardAll(boolean reverse) {
        synchronized (sLock) {
            for (int i = sForwards.size() - 1; i >= 0; i--) {
                if (sForwards.get(i).reverse == reverse) {
                    sForwards.get(i).stop();
                    sForwards.remove(i);
                }
            }
        }
    }

    /**
     * list-forward payload, one direction at a time.
     *
     * AOSP format_listeners: "<serial> <local> <remote>\n", with "(reverse)"
     * standing in for the serial of device-side (reverse) listeners.
     */
    static String listForward(boolean reverse) {
        StringBuilder sb = new StringBuilder();
        synchronized (sLock) {
            for (Forward f : sForwards) {
                if (f.reverse != reverse) continue;
                sb.append(reverse ? "(reverse)" : HostServices.SERIAL)
                    .append(' ')
                    .append(f.localSpec)
                    .append(' ')
                    .append(f.remoteSpec)
                    .append('\n');
            }
        }
        return sb.toString();
    }

    /** Close every listener (server shutdown). */
    static void killAllListeners() {
        synchronized (sLock) {
            for (Forward f : sForwards) f.stop();
            sForwards.clear();
        }
    }

    // ---------- one forward ----------

    private static final class Forward {
        /** true = device-side reverse listener, false = host-side forward listener. */
        final boolean reverse;
        /** Canonical local name: a bound tcp:0 listener is stored as tcp:<port>. */
        final String localSpec;
        final String remoteSpec;
        /** Port actually bound for a tcp:0 listener; 0 otherwise. */
        final int resolvedTcpPort;
        private final Listener mListener;
        private Thread mThread;
        private volatile boolean mRunning;

        Forward(boolean reverse, String localSpec, String remoteSpec) throws IOException {
            this.reverse = reverse;
            this.remoteSpec = remoteSpec;
            ListenerAndPort lp = openListener(localSpec);
            this.mListener = lp.listener;
            this.resolvedTcpPort = lp.port;
            // AOSP install_listener renames a tcp:0 request to the port it
            // actually bound; keep that canonical name for listing/matching.
            this.localSpec = lp.port != 0 ? "tcp:" + lp.port : localSpec;
        }

        void start() {
            mRunning = true;
            mThread = new Thread(this::acceptLoop, "adb-fwd-" + localSpec);
            mThread.setDaemon(true);
            mThread.start();
        }

        void stop() {
            mRunning = false;
            try { mListener.close(); } catch (IOException ignored) {}
        }

        private void acceptLoop() {
            while (mRunning) {
                try {
                    pump(mListener.accept());
                } catch (IOException e) {
                    if (mRunning) TermboxAdbBridge.logDebug(LOG_TAG,
                        "forward accept ended: " + e.getMessage());
                    break;
                }
            }
        }

        /** Accept on a tcp or local listener; yields a bidirectional handle. */
        private void pump(final Connection client) {
            Thread t = new Thread(() -> {
                Connection remote = null;
                try {
                    remote = connectRemote(remoteSpec);
                } catch (IOException e) {
                    try { client.close(); } catch (IOException ignored) {}
                    return;
                }
                try {
                    relay(client, remote);
                } catch (IOException ignored) {
                } finally {
                    try { client.close(); } catch (IOException ignored) {}
                    try { remote.close(); } catch (IOException ignored) {}
                }
            }, "adb-fwd-pump");
            t.setDaemon(true);
            t.start();
        }
    }

    // ---------- endpoint plumbing ----------

    /** Abstraction over java.net sockets and android.net.LocalSocket. */
    private interface Connection {
        InputStream getInputStream() throws IOException;

        OutputStream getOutputStream() throws IOException;

        void close() throws IOException;
    }

    private static final class TcpConnection implements Connection {
        final Socket mSocket;

        TcpConnection(Socket socket) {
            mSocket = socket;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return mSocket.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return mSocket.getOutputStream();
        }

        @Override
        public void close() throws IOException {
            mSocket.close();
        }
    }

    private static final class LocalConnection implements Connection {
        final LocalSocket mSocket;

        LocalConnection(LocalSocket socket) {
            mSocket = socket;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return mSocket.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return mSocket.getOutputStream();
        }

        @Override
        public void close() throws IOException {
            mSocket.close();
        }
    }

    private interface Listener {
        Connection accept() throws IOException;

        void close() throws IOException;
    }

    /** A listener plus the TCP port it actually bound (0 when not a tcp listener). */
    private static final class ListenerAndPort {
        final Listener listener;
        final int port;

        ListenerAndPort(Listener listener, int port) {
            this.listener = listener;
            this.port = port;
        }
    }

    private static ListenerAndPort openListener(String spec) throws IOException {
        if (spec.startsWith("tcp:")) {
            int port = parsePort(spec);
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            final int bound = ss.getLocalPort();
            return new ListenerAndPort(new Listener() {
                @Override
                public Connection accept() throws IOException {
                    return new TcpConnection(ss.accept());
                }

                @Override
                public void close() throws IOException {
                    ss.close();
                }
            }, bound);
        }
        if (spec.startsWith("localabstract:")) {
            LocalServerSocket ls = new LocalServerSocket(spec.substring("localabstract:".length()));
            return new ListenerAndPort(new Listener() {
                @Override
                public Connection accept() throws IOException {
                    return new LocalConnection(ls.accept());
                }

                @Override
                public void close() throws IOException {
                    ls.close();
                }
            }, 0);
        }
        if (spec.startsWith("local:")) {
            LocalServerSocket ls = new LocalServerSocket(spec.substring("local:".length()));
            // Note: LocalServerSocket(String) binds an abstract socket; for a
            // filesystem path the name must carry the path, which the platform
            // maps through LocalSocketAddress parsing of the same string form.
            return new ListenerAndPort(new Listener() {
                @Override
                public Connection accept() throws IOException {
                    return new LocalConnection(ls.accept());
                }

                @Override
                public void close() throws IOException {
                    ls.close();
                }
            }, 0);
        }
        throw new IOException("unsupported forward endpoint '" + spec + "'");
    }

    private static Connection connectRemote(String spec) throws IOException {
        if (spec.startsWith("tcp:")) {
            int port = parsePort(spec);
            Socket s = new Socket();
            s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 5000);
            s.setTcpNoDelay(true);
            return new TcpConnection(s);
        }
        if (spec.startsWith("localabstract:")) {
            LocalSocket s = new LocalSocket();
            s.connect(new LocalSocketAddress(spec.substring("localabstract:".length())), 5000);
            return new LocalConnection(s);
        }
        if (spec.startsWith("local:")) {
            LocalSocket s = new LocalSocket();
            s.connect(new LocalSocketAddress(spec.substring("local:".length())), 5000);
            return new LocalConnection(s);
        }
        if (spec.startsWith("jdwp:") || spec.startsWith("vsock:")) {
            throw new IOException("failed to connect to '" + spec + "'");
        }
        throw new IOException("unsupported remote endpoint '" + spec + "'");
    }

    private static int parsePort(String spec) throws IOException {
        try {
            return Integer.parseInt(spec.substring(4).trim());
        } catch (NumberFormatException e) {
            throw new IOException("invalid tcp port in '" + spec + "'");
        }
    }

    /** Full-duplex relay between two connections. */
    static void relay(Connection a, Connection b) throws IOException {
        Thread t = new Thread(() -> {
            try {
                copy(b.getInputStream(), a.getOutputStream());
            } catch (IOException ignored) {
            } finally {
                try { a.close(); } catch (IOException ignored) {}
            }
        }, "adb-fwd-relay");
        t.setDaemon(true);
        t.start();
        copy(a.getInputStream(), b.getOutputStream());
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            out.flush();
        }
    }
}
