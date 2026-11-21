package com.ia.callbridge.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioBridgeEngine {
    private static final String TAG = "AudioBridgeEngine";
    public static final int SAMPLE_RATE = 16000;
    public static final int CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO;
    public static final int CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO;
    public static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    public static final int FRAME_SIZE = 640; // 320 samples @ 16bit = 20ms of audio

    public static final int UDP_LISTEN_PORT = 8767;
    public static final int UDP_DEST_PORT = 8768;

    private final Context context;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;

    private DatagramSocket udpSocket;
    private InetAddress clientAddress;

    private Thread captureThread;
    private Thread playbackThread;

    public AudioBridgeEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void setClientAddress(InetAddress address) {
        this.clientAddress = address;
        Log.d(TAG, "Audio destination client set to: " + (address != null ? address.getHostAddress() : "null"));
    }

    public synchronized void start() {
        if (isRunning.get()) return;
        isRunning.set(true);

        try {
            udpSocket = new DatagramSocket(UDP_LISTEN_PORT);
            udpSocket.setReceiveBufferSize(65536);
            udpSocket.setSendBufferSize(65536);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create UDP socket on port " + UDP_LISTEN_PORT, e);
            isRunning.set(false);
            return;
        }

        setupAudioHardware();
        startCaptureThread();
        startPlaybackThread();
        Log.i(TAG, "AudioBridgeEngine started successfully.");
    }

    private void setupAudioHardware() {
        try {
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            }

            int minRecordBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT);
            int recordBufferSize = Math.max(minRecordBufferSize, FRAME_SIZE * 4);

            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_IN,
                    AUDIO_FORMAT,
                    recordBufferSize
            );

            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(audioRecord.getAudioSessionId());
                if (aec != null) {
                    aec.setEnabled(true);
                    Log.d(TAG, "AcousticEchoCanceler enabled.");
                }
            }

            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(audioRecord.getAudioSessionId());
                if (ns != null) {
                    ns.setEnabled(true);
                    Log.d(TAG, "NoiseSuppressor enabled.");
                }
            }

            int minTrackBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT);
            int trackBufferSize = Math.max(minTrackBufferSize, FRAME_SIZE * 4);

            audioTrack = new AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_OUT,
                    AUDIO_FORMAT,
                    trackBufferSize,
                    AudioTrack.MODE_STREAM
            );

        } catch (Exception e) {
            Log.e(TAG, "Error initializing AudioRecord / AudioTrack", e);
        }
    }

    private void startCaptureThread() {
        captureThread = new Thread(() -> {
            byte[] buffer = new byte[FRAME_SIZE];
            try {
                if (audioRecord != null && audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                    audioRecord.startRecording();
                    Log.d(TAG, "AudioRecord started recording.");
                } else {
                    Log.e(TAG, "AudioRecord not initialized!");
                    return;
                }

                while (isRunning.get()) {
                    int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                    if (bytesRead > 0 && clientAddress != null && udpSocket != null && !udpSocket.isClosed()) {
                        DatagramPacket packet = new DatagramPacket(buffer, bytesRead, clientAddress, UDP_DEST_PORT);
                        udpSocket.send(packet);
                    }
                }
            } catch (Exception e) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error in audio capture loop", e);
                }
            }
        }, "AudioCaptureThread");
        captureThread.setPriority(Thread.MAX_PRIORITY);
        captureThread.start();
    }

    private void startPlaybackThread() {
        playbackThread = new Thread(() -> {
            byte[] buffer = new byte[FRAME_SIZE * 2];
            try {
                if (audioTrack != null && audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                    audioTrack.play();
                    Log.d(TAG, "AudioTrack started playback.");
                } else {
                    Log.e(TAG, "AudioTrack not initialized!");
                    return;
                }

                while (isRunning.get()) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    udpSocket.receive(packet);

                    // Update clientAddress if learned from incoming audio
                    if (clientAddress == null || !clientAddress.equals(packet.getAddress())) {
                        clientAddress = packet.getAddress();
                    }

                    if (packet.getLength() > 0) {
                        audioTrack.write(packet.getData(), packet.getOffset(), packet.getLength());
                    }
                }
            } catch (Exception e) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error in audio playback loop", e);
                }
            }
        }, "AudioPlaybackThread");
        playbackThread.setPriority(Thread.MAX_PRIORITY);
        playbackThread.start();
    }

    public synchronized void stop() {
        if (!isRunning.get()) return;
        isRunning.set(false);

        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
            udpSocket = null;
        }

        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception ignored) {}
            audioRecord = null;
        }

        if (audioTrack != null) {
            try {
                if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.stop();
                }
                audioTrack.release();
            } catch (Exception ignored) {}
            audioTrack = null;
        }

        if (aec != null) {
            aec.release();
            aec = null;
        }
        if (ns != null) {
            ns.release();
            ns = null;
        }

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_NORMAL);
        }

        Log.i(TAG, "AudioBridgeEngine stopped.");
    }
}
