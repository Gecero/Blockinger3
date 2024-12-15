package org.blockinger2.game.database;

import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import org.blockinger2.game.database.serialization.ScoreSerialization;
import org.capnproto.MessageBuilder;
import org.capnproto.MessageReader;
import org.capnproto.SerializePacked;
import org.capnproto.StructList;

import java.io.*;
import java.net.*;
import java.nio.channels.Channels;
import java.util.Enumeration;
import java.util.concurrent.*;

public class ScoreSync {

    private static Context ctx;
    private static final int BROADCAST_PORT = 56665;
    private static final int TCP_PORT = 56666;
    private static final String BROADCAST_MESSAGE = "Blockinger Highscore Synchronizer V.1";
    private static final String LOG_TAG = "ScoreSync";
    /// The interval in which outgoing sync broadcasts are sent
    /// and other clients will most probably will send their broadcasts at, too
    private static final int SYNC_INTERVAL_MS = 2*60*1000;

    public static void listenAndServe(Context ctx) {
        ScoreSync.ctx = ctx;

        ExecutorService executor = Executors.newFixedThreadPool(3);
        executor.execute(ScoreSync::broadcastSender);
        executor.execute(ScoreSync::broadcastDiscovery);
        executor.execute(ScoreSync::serveScores);
    }

    private static void broadcastSender() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            InetAddress broadcastAddress = InetAddress.getByName("255.255.255.255");
            byte[] buffer = BROADCAST_MESSAGE.getBytes();

            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length, broadcastAddress, BROADCAST_PORT);
                socket.send(packet);
                Log.d(LOG_TAG, "Broadcast out: " + BROADCAST_MESSAGE);
                Thread.sleep(SYNC_INTERVAL_MS);
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Couldn't send broadcast", e);
        }
    }

    private static void broadcastDiscovery() {
        try (DatagramSocket socket = new DatagramSocket(BROADCAST_PORT)) {
            byte[] buffer = new byte[128];

            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                if(isLocalAddress(packet.getAddress())) // Don't sync with ourselves
                    continue;

                String receivedMessage = new String(packet.getData(), 0, packet.getLength());
                Log.d(LOG_TAG, "Broadcast in (" + packet.getAddress() + "): " + receivedMessage);

                if (BROADCAST_MESSAGE.equals(receivedMessage)) {
                    InetAddress senderAddress = packet.getAddress();
                    fetchScores(senderAddress);
                }
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Couldn't discover broadcast", e);
        }
    }

    private static void fetchScores(InetAddress address) {
        try (Socket socket = new Socket(address, TCP_PORT);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            Log.d(LOG_TAG, "TCP Client connected to: " + address.getHostAddress());
            MessageReader message = SerializePacked.readFromUnbuffered(
                    Channels.newChannel(
                            socket.getInputStream()));

            ScoreSerialization.ScoreList.Reader remoteHighscores =
                    message.getRoot(ScoreSerialization.ScoreList.factory);

            ScoreDataSource db = new ScoreDataSource(ctx);
            db.open();

            for(ScoreSerialization.Score.Reader score : remoteHighscores.getScores()) {
                String name = score.getPlayername().toString();
                Score scr = db.createScore(score.getScore(), name);
                Log.d(LOG_TAG, "TCP Client got highscore from user: " + name + " (Inserted? " + (scr == null ? "No)" : "Yes)"));
            }



        } catch (Exception e) {
            Log.e(LOG_TAG, "Couldnt fetch scores", e);
        }
    }

    private static void serveScores() {
        ServerSocket serverSocket = null;
        try {
            serverSocket = new ServerSocket(TCP_PORT);
        } catch (Exception e) {
            Log.e(LOG_TAG, "TCP Server couldn't be started: ", e);
            return;
        }
        Log.d(LOG_TAG, "TCP Server up on port " + TCP_PORT);

        while (true) {
            try {
                Socket clientSocket = serverSocket.accept();
                Log.d(LOG_TAG, "TCP Server incoming connection from: " + clientSocket.getInetAddress());

                // Prepare DB
                ScoreDataSource db = new ScoreDataSource(ctx);
                db.open();
                Cursor cur = db.getCursor();
                cur.moveToFirst();
                int rowCount = cur.getCount();

                // Prepare Cap'n Proto message
                MessageBuilder message = new MessageBuilder();
                ScoreSerialization.ScoreList.Builder scoreList = message.initRoot(ScoreSerialization.ScoreList.factory);
                StructList.Builder<ScoreSerialization.Score.Builder> scores = scoreList.initScores(rowCount);

                // Copy data to Cap'n Proto
                for (int i = 0; i < rowCount; i++) {
                    ScoreSerialization.Score.Builder score = scores.get(i);
                    Score scoreInternal = ScoreDataSource.cursorToScore(cur);
                    score.setPlayername(scoreInternal.getName());
                    score.setScore(scoreInternal.getScore());
                    if(!cur.moveToNext()) break;
                }

                // Send
                SerializePacked.writeToUnbuffered(
                        Channels.newChannel(clientSocket.getOutputStream()), message);

                // Close all connections
                clientSocket.close();
                db.close();
            } catch (Exception e) {
                Log.e(LOG_TAG, "TCP Server error", e);
            }
        }
    }

    private static boolean isLocalAddress(InetAddress address) {
        try {
            // Fetch all local network interfaces
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();

                // Check all IP addresses of all interfaces
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress localAddress = addresses.nextElement();
                    if (localAddress.equals(address)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "", e);
        }
        return false;
    }
}
