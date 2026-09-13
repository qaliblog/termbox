/*
 * TermBox ADB bridge — host services.
 *
 * Implements the AOSP host services used by an adb client talking to this
 * bridge (verified against AOSP docs/dev/services.md and commandline.cpp):
 *
 *   host:version                     OKAY + hex4 ADB_SERVER_VERSION + tail
 *   host:features                    feature set the official client keys on
 *   host:devices[-l]                 device list (payload only; the client
 *                                    prints the "List of devices attached"
 *                                    header itself)
 *   host:track-devices               long-lived streaming device list
 *   host-serial:<serial>:<service>   serial-scoped host queries
 *   host:transport[-any][:<serial>]  transport selection, then the next
 *                                    service string on the same socket
 *   host:forward[:norebind]:l;r      forward registry (OKAY / FAIL)
 *   host:list-forward / killforward[-all]
 *   host:kill                        server shutdown (with prompt restart)
 *   anything else                    device service dispatch (DeviceServices)
 *
 * Reply discipline (smart socket): every successful service activation sends
 * a bare "OKAY" first; query services then send a hex4 payload and the
 * trailing "0000" tail; activation failures send "FAIL" + hex4 reason. Device
 * services are raw streams after OKAY and must not get the tail.
 */
package com.termux.app.adb;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** AOSP-compatible host services layer for the TermBox ADB bridge. */
final class HostServices {

    static final String SERIAL = "emulator-5554";

    /** Wire protocol version this bridge reports (ADB_SERVER_VERSION 41). */
    static final int ADB_SERVER_VERSION = 41;

    /**
     * Features advertised to the client. Matches AOSP adb's feature string
     * minus capabilities the bridge does not implement (sendrecv_v2, abb,
     * abb_exec, incremental, brotli/zstd/deflate compression), so official
     * clients fall back to the v1 sync path and streamed install via `cmd`.
     */
    private static final String FEATURES = String.join(",",
        "shell_v2", "cmd", "stat_v2", "ls_v2", "fixed_push_mkdir", "apex");

    static final byte[] OKAY = {'O', 'K', 'A', 'Y'};
    static final byte[] FAIL = {'F', 'A', 'I', 'L'};

    /** Set when host:kill unwinds the listener; suppresses the error log. */
    static final AtomicBoolean SHUTDOWN = new AtomicBoolean(false);

    private static volatile boolean DEBUG = false;

    static void setDebug(boolean on) {
        DEBUG = on;
    }

    static boolean isDebugEnabled() {
        return DEBUG;
    }

    private static void d(String svc, String msg) {
        if (DEBUG) TermboxAdbBridge.logDebug("HostServices", svc + ": " + msg);
    }

    private final DeviceServices mDeviceServices = new DeviceServices();
    private final ForwardRegistry mForwards = new ForwardRegistry();

    static boolean isShutdownCaused() {
        return SHUTDOWN.get();
    }

    /** Read a 4-hex-digit length + payload smart-protocol message. */
    static String readMessage(InputStream in) throws IOException {
        byte[] lenBuf = new byte[4];
        readFully(in, lenBuf, 0, 4);
        int len = parseHex4(lenBuf);
        byte[] buf = new byte[len];
        readFully(in, buf, 0, len);
        return new String(buf, StandardCharsets.UTF_8);
    }

    /**
     * Read a device service string sent RAW (no 4-byte hex length prefix)
     * after transport selection. The client writes the service string in one
     * packet; we read up to a reasonable max length.
     */
    static String readDeviceServiceString(InputStream in) throws IOException {
        byte[] buf = new byte[4096];
        int n = in.read(buf);
        if (n <= 0) throw new EOFException("EOF reading device service string");
        return new String(buf, 0, n, StandardCharsets.UTF_8);
    }

    /** Write a 4-hex-digit length + payload smart-protocol message. */
    static void writeMessage(OutputStream out, String payload) throws IOException {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        if (data.length > 0xFFFF) throw new IOException("payload too large");
        out.write(String.format("%04x", data.length).getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.flush();
    }

    /** Write the bare OKAY activation marker (no framing). */
    static void writeOkay(OutputStream out) throws IOException {
        out.write(OKAY);
        out.flush();
    }

    /** Write a FAIL activation marker with a hex4-framed reason. */
    static void writeFail(OutputStream out, String reason) throws IOException {
        out.write(FAIL);
        writeMessage(out, reason);
    }

    /** Trailing "0000" that closes a query-style service reply. */
    static void writeQueryTail(OutputStream out) throws IOException {
        out.write('0');
        out.write('0');
        out.write('0');
        out.write('0');
        out.flush();
    }

    /** OKAY + payload in one call for query-style services. */
    private static void writeOkayPayload(OutputStream out, String payload) throws IOException {
        writeOkay(out);
        writeMessage(out, payload);
    }

    private static void readFully(InputStream in, byte[] buf, int off, int len)
        throws IOException {
        int got = 0;
        while (got < len) {
            int n = in.read(buf, off + got, len - got);
            if (n < 0) throw new EOFException("EOF after " + got + "/" + len + " bytes");
            got += n;
        }
    }

    private static int parseHex4(byte[] buf) throws IOException {
        int len = 0;
        for (int i = 0; i < 4; i++) {
            int v = Character.digit((char) (buf[i] & 0xff), 16);
            if (v < 0) throw new IOException("bad hex4 length prefix");
            len = (len << 4) | v;
        }
        return len;
    }

    /**
     * Smart-protocol connection lifecycle. Handles the service activation and
     * the trailing "0000" for query-style services; device services manage
     * their own raw streams and signal "no tail" via NoQueryTailException.
     */
    boolean handleSmartProtocol(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            String service = readMessage(in);
            d("smart proto", "service received: " + service);
            boolean handled = handleService(socket, in, out, service);
            if (handled) {
                // Device services already signalled NoQueryTailException;
                // only query-style services reach here.
                d("host service", "writing query tail for: " + service);
                writeQueryTail(out);
            }
            return handled;
        } catch (DeviceServices.NoQueryTailException ignored) {
            // Raw device service: the "0000" tail must not be appended.
            d("smart proto", "device service finished (no tail)");
            return true;
        } catch (ShutdownException e) {
            // Reply already completed inside handleService.
            d("smart proto", "shutdown");
            return true;
        } catch (Exception e) {
            d("smart proto", "error: " + e);
            return false;
        }
    }

    /** Handle a service string; returns false if the service is unknown. */
    boolean handleService(Socket socket, InputStream in, OutputStream out, String service)
        throws IOException {
        String serial = null;
        String rest;

        if (service.startsWith("host-serial:") || service.startsWith("host-usb:") || service.startsWith("host-local:")) {
            int prefixLen = service.startsWith("host-serial:") ? "host-serial:".length()
                    : service.startsWith("host-usb:") ? "host-usb:".length()
                    : "host-local:".length();
            int idx = service.indexOf(':', prefixLen);
            if (idx < 0) return false;
            serial = service.substring(prefixLen, idx);
            rest = service.substring(idx + 1);
            // If rest is a device service, delegate immediately (host-serial:SERIAL:device_service).
            if (isDeviceService(rest)) {
                d("host service", "host-serial device service rest=" + rest + " serial=" + serial);
                if (!DeviceServices.knownSerial(serial)) {
                    writeFail(out, "device '" + serial + "' not found");
                    return true;
                }
                mDeviceServices.handleDeviceService(socket, in, out, rest);
                throw new DeviceServices.NoQueryTailException();
            }
            // Otherwise, fall through to host service handling with the parsed serial.
        } else if (service.startsWith("host:")) {
            rest = service.substring("host:".length());
        } else {
            // Direct device service (client already selected the transport).
            return mDeviceServices.handleDeviceService(socket, in, out, service);
        }

        // Transport selection: OKAY, then the client sends the device service
        // string on the same socket (AOSP handle_transport_request).
        // The device service string is sent RAW (no 4-byte hex length prefix),
        // unlike host services. Read a reasonable buffer directly.
        if (rest.equals("transport-any")) {
            d(service, "transport-any serial=" + serial);
            if (DeviceServices.knownSerial(serial)) {
                writeOkay(out);
                String next = readDeviceServiceString(in);
                d("host service", "transport-any next: " + next);
                mDeviceServices.handleDeviceService(socket, in, out, next);
                throw new DeviceServices.NoQueryTailException();
            }
            writeFail(out, "device not found");
            return true;
        }
        if (rest.startsWith("transport:")) {
            String wanted = rest.substring("transport:".length());
            d(service, "transport serial=" + serial + " wanted=" + wanted);
            if (SERIAL.equals(wanted) || DeviceServices.knownSerial(wanted)) {
                writeOkay(out);
                String next = readDeviceServiceString(in);
                d("host service", "transport next: " + next);
                mDeviceServices.handleDeviceService(socket, in, out, next);
                throw new DeviceServices.NoQueryTailException();
            }
            writeFail(out, "device '" + wanted + "' not found");
            return true;
        }

        if (rest.startsWith("tport:serial:")) {
            return mDeviceServices.handleDeviceService(socket, in, out,
                rest.substring("tport:serial:".length()));
        }
        switch (rest) {
            case "version":
                writeOkayPayload(out, String.format("%04x", ADB_SERVER_VERSION));
                return true;
            case "features":
            case "host-features":
                writeOkayPayload(out, FEATURES);
                return true;
            case "devices":
            case "devices-l":
                writeOkayPayload(out, DeviceServices.devicesList(rest.endsWith("-l")));
                return true;
            case "track-devices":
                writeOkay(out);
                handleTrackDevices(in, out);
                return true;
            case "kill":
                writeOkayPayload(out, "successfully kicked");
                // Bring the server down; TermboxAdbBridge restarts it shortly,
                // matching how a real `adb kill-server` is followed by a new
                // daemon on the next client command.
                SHUTDOWN.set(true);
                TermboxAdbBridge.requestServerRestart();
                throw new ShutdownException();
            case "reconnect":
            case "reconnect-offline":
                writeOkayPayload(out, "done");
                return true;
            case "get-state":
                writeOkayPayload(out, "device");
                return true;
            case "get-serialno":
                writeOkayPayload(out, SERIAL);
                return true;
            case "get-devpath":
                writeOkayPayload(out, SERIAL);
                return true;
            case "get-transport-id":
                writeOkayPayload(out, "1");
                return true;
            case "get-product":
                writeOkayPayload(out, "termbox");
                return true;
            case "list-forward":
                writeOkayPayload(out, mForwards.listForward(serial));
                return true;
            case "killforward-all":
                mForwards.killForwardAll();
                writeOkay(out);
                return true;
            default:
                break;
        }
        if (rest.startsWith("killforward:")) {
            String local = rest.substring("killforward:".length());
            if (local.startsWith("norebind:")) local = local.substring("norebind:".length());
            mForwards.killForward(local);
            writeOkay(out);
            return true;
        }
        if (rest.startsWith("forward")) {
            String spec = rest.substring("forward".length());
            if (spec.startsWith(":")) spec = spec.substring(1);
            if (spec.startsWith("norebind:")) spec = spec.substring("norebind:".length());
            int semi = spec.indexOf(';');
            if (semi < 0) {
                writeFail(out, "invalid forward spec");
                return true;
            }
            String local = spec.substring(0, semi);
            String remote = spec.substring(semi + 1);
            if (!mForwards.addForward(serial, local, remote)) {
                writeFail(out, "cannot rebind existing adb server socket");
                return true;
            }
            writeOkay(out);
            return true;
        }
        if (serial != null) {
            // Any other serial-scoped query on our single device.
            d("host service", "unhandled serial-scoped: " + rest + " serial=" + serial);
            writeFail(out, "device '" + serial + "' not found");
            return true;
        }
        d("host service", "unknown service, returning false");
        return false;
    }

    /**
     * Returns true if the given service string is a device service (shell/exec/sync/
     * reverse/track-jdwp/transport-connect) that should be delegated to
     * DeviceServices rather than handled as a host query.
     */
    private static boolean isDeviceService(String service) {
        return service.equals("sync:")
            || service.startsWith("shell")
            || service.startsWith("exec:")
            || service.startsWith("reverse:")
            || service.equals("track-jdwp")
            || service.startsWith("tcp:")
            || service.startsWith("local:")
            || service.startsWith("localabstract:");
    }

    /** Long-lived host:track-devices stream. */
    private void handleTrackDevices(InputStream in, OutputStream out) throws IOException {
        writeMessage(out, DeviceServices.devicesList(false));
        // Real adb pushes updates on device-list changes; we have exactly one
        // "device", so the stream just stays open until the client closes it.
        byte[] buf = new byte[4096];
        while (in.read(buf) != -1) { /* client went away */ }
        d("host service", "track-devices client closed");
    }

    /** Thrown to unwind the connection when host:kill shuts the server down. */
    static final class ShutdownException extends RuntimeException {
    }
}
