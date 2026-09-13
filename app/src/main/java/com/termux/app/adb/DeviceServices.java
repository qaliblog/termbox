package com.termux.app.adb;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Device service dispatcher (the "adbd" role of the bridge).
 *
 * Parses device service strings (AOSP docs/dev/services.md) and hands each
 * connection to the matching implementation:
 *
 *   shell[,v2][,TERM=x][,pty|raw]:<command>   ShellRunner (+ DeviceCommandHandlers)
 *   exec:<command>                            raw pipe (stdout+stderr merged),
 *                                             incl. "cmd package ..." installs
 *   sync:                                     FileSyncService (push/pull)
 *   tcp:<port>, local:..., localabstract:...  transport connect + relay
 *   track-jdwp                                empty stream (no debuggable VMs)
 *   reverse:forward:...                       device-side reverse listener
 *
 * Every device service is a raw stream after the OKAY activation, so this
 * class always signals {@link NoQueryTailException} on success — the smart
 * protocol "0000" tail applies only to host query services.
 *
 * Commands run as this app's own UID on the real Android host. Android's own
 * permission checks are the security boundary and are never bypassed: what
 * Android rejects (pm install without consent, dumpsys of protected services,
 * force-stop of other packages, ...) fails here exactly as it fails for the
 * app, with genuine error text and non-zero exit status.
 */
final class DeviceServices {

    static final String SERIAL = HostServices.SERIAL;

    /** Thrown by raw stream services so the smart-protocol tail is skipped. */
    static final class NoQueryTailException extends RuntimeException {
        NoQueryTailException() {
            // Superclass fills in the stack trace lazily; these are control flow.
            super(null, null, false, false);
        }
    }

    DeviceServices() {
    }

    private static void d(String svc, String msg, String ctx) {
        if (HostServices.isDebugEnabled()) TermboxAdbBridge.logDebug("DeviceServices", svc + ": " + msg + " ctx=" + ctx);
    }


    /** Serial this bridge advertises for the device. */

    /** Devices that the bridge considers "known" (for transport selection). */
    static boolean knownSerial(String serial) {
        return SERIAL.equals(serial) || (serial == null) || serial.isEmpty();
    }

    /** Payload for host:devices[-l] (the client prints the header itself). */
    static String devicesList(boolean longForm) {
        if (longForm) {
            return SERIAL + "\tdevice product:termbox model:termbox device:termbox"
                + " transport_id:1\n";
        }
        return SERIAL + "\tdevice\n";
    }

    /** Dispatch a device service string. Always a raw stream on success. */
    static boolean handleDeviceService(Socket socket, InputStream in, OutputStream out,
                                       String service) throws IOException {
        d(service, "device service dispatch", service);
        if (service.equals("sync:")) {
            HostServices.writeOkay(out);
            FileSyncService.serve(in, out);
            throw new NoQueryTailException();
        }
        if (service.startsWith("shell")) {
            return handleShell(in, out, service);
        }
        if (service.startsWith("exec:")) {
            return handleExec(in, out, service.substring("exec:".length()));
        }
        if (service.startsWith("reverse:")) {
            handleReverse(out, service.substring("reverse:".length()));
            return true;
        }
        if (service.equals("track-jdwp")) {
            // No debuggable VMs are visible to this bridge: an empty stream.
            HostServices.writeOkay(out);
            throw new NoQueryTailException();
        }
        if (service.startsWith("tcp:") || service.startsWith("local:")
            || service.startsWith("localabstract:")) {
            return handleTransportConnect(in, out, service);
        }
        return false;
    }

    // ---------- shell ----------

    /**
     * "shell[,v2][,TERM=x][,pty|raw]:<command>"
     *
     * v2: shell-protocol packets (stdin/stdout/stderr/exit/winsize).
     * legacy (no v2): raw byte stream, PTY always, no exit status.
     */
    private static boolean handleShell(InputStream in, OutputStream out, String service)
        throws IOException {
        int colon = service.indexOf(':');
        if (colon < 0) return false;
        String args = service.substring("shell".length(), colon);
        String command = service.substring(colon + 1);

        boolean v2 = false;
        boolean pty = false;
        boolean raw = false;
        String term = null;
        for (String arg : args.split(",")) {
            if (arg.isEmpty()) continue;
            if (arg.equals("v2")) {
                v2 = true;
            } else if (arg.equals("pty")) {
                pty = true;
            } else if (arg.equals("raw")) {
                raw = true;
            } else if (arg.startsWith("TERM=")) {
                term = arg.substring("TERM=".length());
            }
            // Unknown args are ignored, like adbd.
        }

        ShellRunner runner = new ShellRunner();

        // Java-backed emulation for commands that need it; everything else
        // runs the real Android binary via sh -c.
        DeviceCommandHandlers.Result emulated = DeviceCommandHandlers.handle(command);
        if (emulated != null) {
            d(service, "emulated command exit=" + emulated.mExit, service);
            HostServices.writeOkay(out);
            if (v2) {
                byte[] data = emulated.mStdout.getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
                int off = 0;
                while (off < data.length) {
                    int n = Math.min(ShellProtocol.MAX_PAYLOAD, data.length - off);
                    ShellProtocol.writePacket(out, ShellProtocol.ID_STDOUT, data, off, n);
                    off += n;
                }
                if (emulated.mStderr != null && !emulated.mStderr.isEmpty()) {
                    byte[] err = emulated.mStderr.getBytes(
                        java.nio.charset.StandardCharsets.UTF_8);
                    ShellProtocol.writePacket(out, ShellProtocol.ID_STDERR, err,
                        0, err.length);
                }
                ShellProtocol.writeExit(out, emulated.mExit);
            } else {
                // Legacy: single merged stream, no exit status.
                out.write(emulated.mStdout.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (emulated.mStderr != null) {
                    out.write(emulated.mStderr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                out.flush();
            }
            // Legacy shell services: some ADB clients expect the stream to close
            // after output instead of waiting indefinitely. Emit CLOSE_STDIN (v2)
            // or simply close the socket by throwing NoQueryTailException.
            throw new NoQueryTailException();
        }

        boolean usePty = raw ? false : (pty || !v2);
        // An empty command means a login shell on the device.
        String effectiveCommand = command.isEmpty() ? "sh" : command;
        d(service, "executing command via sh -c (pty=" + usePty + " v2=" + v2 + " command=" + effectiveCommand + ")", service);

        HostServices.writeOkay(out);

        if (usePty) {
            ShellRunner.PtyEngine engine = runner.startPty(effectiveCommand, term, 0, 0);
            if (engine == null) {
                // PTY unavailable (libadbpty not loadable): fall back to pipes,
                // which is what adbd does when the pty provider fails.
                d(service, "pty unavailable, falling back to pipe", service);
                runPipeShell(runner, in, out, effectiveCommand, v2);
                throw new NoQueryTailException();
            }
            final InputStream fin = in;
            final ShellRunner.PtyEngine fengine = engine;
            final boolean fpty = pty;
            final boolean fv2 = v2;
            Thread stdinThread = new Thread(() -> pumpShellStdin(fin, fengine, fv2, fpty),
                "adb-shell-stdin");
            stdinThread.setDaemon(true);
            stdinThread.start();
            d(service, "pty shell started pid=" + fengine.mPid, service);
            engine.run(out);
            engine.kill(); // if output EOF'd early, ensure the child is gone
            throw new NoQueryTailException();
        }
        runPipeShell(runner, in, out, effectiveCommand, v2);
        throw new NoQueryTailException();
    }

    private static void runPipeShell(ShellRunner runner, InputStream in, OutputStream out,
                                     String command, boolean v2) throws IOException {
        ShellRunner.PipeEngine engine = runner.startPipe(command);
        Thread stdinThread = new Thread(() -> pumpShellStdin(in, engine, v2, false),
            "adb-shell-stdin");
        stdinThread.setDaemon(true);
        stdinThread.start();
        engine.run(out);
        engine.kill();
    }

    /**
     * Read shell-protocol packets (or raw bytes for legacy) from the client
     * and forward stdin to the running engine until the client goes away.
     */
    private static void pumpShellStdin(InputStream in, Object engine, boolean v2,
                                       boolean ptyMode) {
        try {
            if (!v2) {
                // Legacy: raw bytes to the pty master.
                byte[] buf = new byte[8 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (ptyMode && engine instanceof ShellRunner.PtyEngine) {
                        if (((ShellRunner.PtyEngine) engine).writeStdin(buf, n) < 0) break;
                    }
                }
                return;
            }
            byte[] data = new byte[ShellProtocol.MAX_PAYLOAD];
            int[] len = new int[1];
            while (true) {
                int id = ShellProtocol.readPacket(in, data, len);
                if (id < 0) return; // client closed
                if (id == ShellProtocol.ID_STDIN) {
                    if (len[0] == 0) continue;
                    if (engine instanceof ShellRunner.PtyEngine) {
                        if (((ShellRunner.PtyEngine) engine).writeStdin(data, len[0]) < 0) {
                            return;
                        }
                    } else if (engine instanceof ShellRunner.PipeEngine) {
                        if (!((ShellRunner.PipeEngine) engine).writeStdin(data, len[0])) {
                            return;
                        }
                    }
                } else if (id == ShellProtocol.ID_WINDOW_SIZE_CHANGE) {
                    int[] rc = ShellProtocol.parseWindowSize(data, len[0]);
                    if (rc != null && engine instanceof ShellRunner.PtyEngine) {
                        ((ShellRunner.PtyEngine) engine).setWindowSize(rc[0], rc[1]);
                    }
                } else if (id == ShellProtocol.ID_CLOSE_STDIN) {
                    if (engine instanceof ShellRunner.PipeEngine) {
                        ((ShellRunner.PipeEngine) engine).closeStdin();
                    }
                    // PTYs cannot be half-closed; ignore, like adbd.
                } else if (id == ShellProtocol.ID_EXIT || id == ShellProtocol.ID_STDOUT
                    || id == ShellProtocol.ID_STDERR) {
                    // Device-bound ids from a client are ignored, like adbd.
                } else {
                    return; // invalid id: drop the connection
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Connection closed or protocol garbage: stop pumping stdin.
        }
    }

    // ---------- exec ----------

    /**
     * "exec:<command>": raw merged stdout+stderr, no PTY, no exit status.
     * Handles the streamed install path ("cmd package install-*") via
     * PackageManagerService; everything else executes for real.
     */
    private static boolean handleExec(InputStream in, OutputStream out, String command)
        throws IOException {
        d(command, "exec", command);
        HostServices.writeOkay(out);
        String trimmed = command.trim();
        if (trimmed.startsWith("cmd package ")) {
            PackageManagerService pkg = new PackageManagerService();
            pkg.handleCmdPackage(trimmed.substring("cmd ".length()), in, out);
            d(command, "cmd package done", command);
            out.flush();
            throw new NoQueryTailException();
        }
        DeviceCommandHandlers.Result emulated = DeviceCommandHandlers.handle(command);
        if (emulated != null) {
            d(command, "emulated exec exit=" + emulated.mExit, command);
            out.write((emulated.mStdout + (emulated.mStderr == null ? ""
                : emulated.mStderr)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            throw new NoQueryTailException();
        }
        // Real execution with merged stderr (exec: cannot separate streams).
        ShellRunner runner = new ShellRunner();
        ShellRunner.PipeEngine engine = runner.startExec(command);
        d(command, "executing via sh -c", command);
        engine.runRaw(out);
        engine.kill();
        throw new NoQueryTailException();
    }

    // ---------- reverse ----------

    /**
     * "reverse:forward:<remote>;<local>" etc. The device side listens on the
     * remote endpoint; incoming device connections are forwarded to the host
     * endpoint. Since bridge "device" and "host" are the same process, this
     * mirrors ForwardRegistry with swapped specs.
     */
    private static void handleReverse(OutputStream out, String spec) throws IOException {
        if (spec.startsWith("list-forward")) {
            HostServices.writeOkay(out);
            HostServices.writeMessage(out, ForwardRegistry.listForward(null));
            throw new NoQueryTailException();
        }
        if (spec.equals("killforward-all")) {
            ForwardRegistry.killForwardAll();
            HostServices.writeOkay(out);
            throw new NoQueryTailException();
        }
        if (spec.startsWith("killforward")) {
            String local = spec.substring("killforward".length());
            if (local.startsWith(":")) local = local.substring(1);
            ForwardRegistry.killForward(local);
            HostServices.writeOkay(out);
            throw new NoQueryTailException();
        }
        if (spec.startsWith("forward")) {
            String rest = spec.substring("forward".length());
            if (rest.startsWith(":")) rest = rest.substring(1);
            if (rest.startsWith("norebind:")) rest = rest.substring("norebind:".length());
            int semi = rest.indexOf(';');
            if (semi < 0) {
                HostServices.writeFail(out, "invalid reverse spec");
                throw new NoQueryTailException();
            }
            String remote = rest.substring(0, semi);
            String local = rest.substring(semi + 1);
            if (!ForwardRegistry.addForward(null, remote, local)) {
                HostServices.writeFail(out, "cannot rebind existing adb server socket");
                throw new NoQueryTailException();
            }
            HostServices.writeOkay(out);
            throw new NoQueryTailException();
        }
    }

    // ---------- transport connect ----------

    /** tcp:PORT / local:... device service: connect and relay. */
    private static boolean handleTransportConnect(InputStream in, OutputStream out, String spec)
        throws IOException {
        HostServices.writeOkay(out);
        // The client will stream raw bytes both ways; relay through the same
        // plumbing forwards use. (Not exercised by the bundled client.)
        java.net.Socket relay = null;
        try {
            if (spec.startsWith("tcp:")) {
                int port = Integer.parseInt(spec.substring(4).trim());
                relay = new java.net.Socket();
                relay.connect(new java.net.InetSocketAddress(
                    java.net.InetAddress.getByName("127.0.0.1"), port), 5000);
            } else {
                throw new IOException("unsupported transport endpoint '" + spec + "'");
            }
        } catch (IOException e) {
            throw new NoQueryTailException();
        }
        java.net.Socket finalRelay = relay;
        Thread t = new Thread(() -> {
            try {
                ForwardRegistry.copy(in, finalRelay.getOutputStream());
            } catch (IOException ignored) {
            } finally {
                try {
                    finalRelay.close();
                } catch (IOException ignored) {
                }
            }
        }, "adb-tport-out");
        t.setDaemon(true);
        t.start();
        try {
            ForwardRegistry.copy(finalRelay.getInputStream(), out);
        } catch (IOException ignored) {
        }
        try {
            finalRelay.close();
        } catch (IOException ignored) {
        }
        throw new NoQueryTailException();
    }

    /** Application context for Java-backed command emulation. */
    static Context appContext() {
        return TermboxAdbBridge.appContext();
    }
}
