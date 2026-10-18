package com.example.refwhistle.net;

import android.os.Handler;
import android.os.Looper;

import com.example.refwhistle.data.DBHelper;
import com.example.refwhistle.domain.LogCodec;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;

/**
 * Phone-to-phone sync over the same Wi-Fi / hotspot. Both sides send their whole op log and merge
 * what they receive. Because the log is append-only and ordered by hybrid logical clock stamps,
 * merging is just a set union, so it does not matter who connects to whom or how often.
 */
public class PeerSync {
    public static final int PORT = 8765;

    public interface Callback {
        void onDone(int newOps, String error);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Host callbacks: one call per phone that syncs, then one when hosting ends. */
    public interface HostCallback {
        void onPeer(int peersSoFar, int newOps);

        void onFinished(int peers, String error);
    }

    /** How long the host keeps listening after the last phone (or after starting). */
    public static final int LISTEN_MS = 60000;

    /**
     * Wait for any number of phones. Each phone that connects exchanges logs with this one. Because
     * the host merges everything it receives, the next phone gets the earlier phones' changes too,
     * so after a round every phone that connected holds the same data.
     */
    public static void host(final DBHelper db, final HostCallback cb) {
        new Thread(new Runnable() {
            public void run() {
                int peers = 0;
                String error = null;
                try (ServerSocket ss = new ServerSocket()) {
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress(PORT));
                    long deadline = System.currentTimeMillis() + LISTEN_MS;
                    while (true) {
                        int left = (int) (deadline - System.currentTimeMillis());
                        if (left <= 0) break;
                        ss.setSoTimeout(left);
                        try (Socket s = ss.accept()) {
                            s.setSoTimeout(15000);
                            final int added = exchange(s, db);
                            peers++;
                            final int n = peers;
                            MAIN.post(new Runnable() {
                                public void run() {
                                    cb.onPeer(n, added);
                                }
                            });
                            deadline = System.currentTimeMillis() + LISTEN_MS;   // keep listening for more phones
                        } catch (java.net.SocketTimeoutException e) {
                            break;
                        }
                    }
                } catch (Exception e) {
                    error = String.valueOf(e.getMessage());
                }
                final int total = peers;
                final String err = error;
                MAIN.post(new Runnable() {
                    public void run() {
                        cb.onFinished(total, err);
                    }
                });
            }
        }).start();
    }

    /** Connect to a hosting phone and exchange logs. */
    public static void connect(final DBHelper db, final String ip, final Callback cb) {
        new Thread(new Runnable() {
            public void run() {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(ip, PORT), 8000);
                    s.setSoTimeout(15000);
                    report(cb, exchange(s, db), null);
                } catch (Exception e) {
                    report(cb, 0, String.valueOf(e.getMessage()));
                }
            }
        }).start();
    }

    private static int exchange(Socket s, DBHelper db) throws IOException {
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"));
        BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
        w.write(LogCodec.encodeAll(db.getOps()));
        w.write("END\n");
        w.flush();
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null && !line.equals("END")) {
            sb.append(line).append('\n');
        }
        return db.mergeOps(LogCodec.decodeAll(sb.toString()));
    }

    private static void report(final Callback cb, final int n, final String err) {
        MAIN.post(new Runnable() {
            public void run() {
                cb.onDone(n, err);
            }
        });
    }

    /** This phone's Wi-Fi/hotspot address, to type into the other phone. */
    public static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }
}
