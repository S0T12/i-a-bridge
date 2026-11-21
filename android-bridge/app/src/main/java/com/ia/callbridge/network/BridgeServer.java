package com.ia.callbridge.network;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.ia.callbridge.audio.AudioBridgeEngine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

public class BridgeServer {
    private static final String TAG = "BridgeServer";
    public static final int TCP_PORT = 8765;

    public interface CallActionListener {
        void onAnswerCall();
        void onDeclineCall();
        void onClientConnected(String clientIp);
        void onClientDisconnected();
    }

    private final Context context;
    private final CallActionListener listener;
    private final AudioBridgeEngine audioEngine;
    private final Gson gson = new Gson();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private ServerSocket serverSocket;
    private Socket currentClientSocket;
    private PrintWriter clientWriter;
    private Thread serverThread;

    public BridgeServer(Context context, AudioBridgeEngine audioEngine, CallActionListener listener) {
        this.context = context;
        this.audioEngine = audioEngine;
        this.listener = listener;
    }

    public synchronized void start() {
        if (isRunning.get()) return;
        isRunning.set(true);

        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(TCP_PORT);
                Log.i(TAG, "BridgeServer listening on TCP port " + TCP_PORT);

                while (isRunning.get()) {
                    Socket socket = serverSocket.accept();
                    handleNewClient(socket);
                }
            } catch (Exception e) {
                if (isRunning.get()) {
                    Log.e(TAG, "Server socket error", e);
                }
            }
        }, "BridgeServerThread");
        serverThread.start();
    }

    private synchronized void handleNewClient(Socket socket) {
        closeCurrentClient();

        this.currentClientSocket = socket;
        String clientIp = socket.getInetAddress().getHostAddress();
        Log.i(TAG, "iPhone connected from: " + clientIp);

        // Tell AudioEngine about iPhone's IP
        if (audioEngine != null) {
            audioEngine.setClientAddress(socket.getInetAddress());
        }

        mainHandler.post(() -> listener.onClientConnected(clientIp));

        new Thread(() -> {
            try {
                clientWriter = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                // Send Welcome
                JsonObject hello = new JsonObject();
                hello.addProperty("event", "HELLO");
                hello.addProperty("device", "Samsung A6+");
                sendJson(hello);

                String line;
                while ((line = reader.readLine()) != null) {
                    processIncomingMessage(line);
                }
            } catch (Exception e) {
                Log.w(TAG, "Client connection lost: " + e.getMessage());
            } finally {
                mainHandler.post(listener::onClientDisconnected);
                closeCurrentClient();
            }
        }, "ClientHandlerThread").start();
    }

    private void processIncomingMessage(String jsonString) {
        try {
            JsonObject obj = gson.fromJson(jsonString, JsonObject.class);
            if (obj == null) return;

            String action = obj.has("action") ? obj.get("action").getAsString() : "";
            Log.d(TAG, "Received action from iPhone: " + action);

            if ("ANSWER".equalsIgnoreCase(action)) {
                mainHandler.post(listener::onAnswerCall);
            } else if ("DECLINE".equalsIgnoreCase(action) || "END".equalsIgnoreCase(action)) {
                mainHandler.post(listener::onDeclineCall);
            } else if ("PING".equalsIgnoreCase(action)) {
                JsonObject pong = new JsonObject();
                pong.addProperty("event", "PONG");
                pong.addProperty("timestamp", System.currentTimeMillis());
                sendJson(pong);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing incoming JSON: " + jsonString, e);
        }
    }

    public synchronized void sendCallIncoming(String number, String name) {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "INCOMING_CALL");
        obj.addProperty("number", number != null ? number : "Unknown");
        obj.addProperty("name", name != null ? name : "");
        obj.addProperty("timestamp", System.currentTimeMillis());
        sendJson(obj);
    }

    public synchronized void sendCallAnswered() {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "CALL_ANSWERED");
        sendJson(obj);
    }

    public synchronized void sendCallEnded() {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "CALL_ENDED");
        sendJson(obj);
    }

    private synchronized void sendJson(JsonObject obj) {
        if (clientWriter != null) {
            try {
                clientWriter.println(gson.toJson(obj));
                clientWriter.flush();
            } catch (Exception e) {
                Log.e(TAG, "Error sending JSON to client", e);
            }
        }
    }

    private synchronized void closeCurrentClient() {
        if (clientWriter != null) {
            try { clientWriter.close(); } catch (Exception ignored) {}
            clientWriter = null;
        }
        if (currentClientSocket != null && !currentClientSocket.isClosed()) {
            try { currentClientSocket.close(); } catch (Exception ignored) {}
            currentClientSocket = null;
        }
    }

    public synchronized void stop() {
        if (!isRunning.get()) return;
        isRunning.set(false);

        closeCurrentClient();

        if (serverSocket != null && !serverSocket.isClosed()) {
            try { serverSocket.close(); } catch (Exception ignored) {}
            serverSocket = null;
        }
        Log.i(TAG, "BridgeServer stopped.");
    }
}
