import Foundation
import AVFoundation
import Network

class AudioEngine {
    static let shared = AudioEngine()

    private let audioEngine = AVAudioEngine()
    private var udpConnection: NWConnection?
    private var udpListener: NWListener?
    
    private var remoteHost: NWEndpoint.Host = "192.168.43.1"
    private let remotePort: NWEndpoint.Port = 8767
    private let localPort: NWEndpoint.Port = 8768
    
    private var isRunning = false
    private let queue = DispatchQueue(label: "com.ia.callbridge.audio", qos: .userInteractive)

    func configure(remoteIp: String) {
        self.remoteHost = NWEndpoint.Host(remoteIp)
    }

    func start() {
        guard !isRunning else { return }
        isRunning = true

        startUdpListener()
        setupAudioEngine()
    }

    private func setupAudioEngine() {
        let inputNode = audioEngine.inputNode
        let hardwareFormat = inputNode.inputFormat(forBus: 0)
        
        // 16kHz Mono 16-bit PCM format matching Android bridge
        guard let pcmFormat = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16000, channels: 1, interleaved: true) else {
            print("Failed to create PCM audio format")
            return
        }

        // Install tap on microphone input
        inputNode.installTap(onBus: 0, bufferSize: 320, format: hardwareFormat) { [weak self] buffer, time in
            guard let self = self, self.isRunning else { return }
            
            // Convert to 16kHz Int16 PCM
            guard let converter = AVAudioConverter(from: hardwareFormat, to: pcmFormat) else { return }
            guard let convertedBuffer = AVAudioPCMBuffer(pcmFormat: pcmFormat, frameCapacity: 320) else { return }
            
            var error: NSError?
            converter.convert(to: convertedBuffer, error: &error) { inNumPackets, outStatus in
                outStatus.pointee = .haveData
                return buffer
            }
            
            if error == nil, let int16Data = convertedBuffer.int16ChannelData {
                let byteCount = Int(convertedBuffer.frameLength) * MemoryLayout<Int16>.size
                let data = Data(bytes: int16Data[0], count: byteCount)
                self.sendAudioData(data)
            }
        }

        do {
            try audioEngine.start()
            print("AVAudioEngine started successfully")
        } catch {
            print("Failed to start AVAudioEngine: \(error)")
        }
    }

    private func startUdpListener() {
        do {
            let params = NWParameters.udp
            params.allowLocalEndpointReuse = true
            udpListener = try NWListener(using: params, on: localPort)
            
            udpListener?.newConnectionHandler = { [weak self] connection in
                connection.start(queue: self?.queue ?? .main)
                self?.receiveAudio(on: connection)
            }
            
            udpListener?.start(queue: queue)
            print("AudioEngine UDP listener started on port \(localPort)")
        } catch {
            print("Failed to start UDP listener: \(error)")
        }

        // Setup outbound UDP connection
        let params = NWParameters.udp
        udpConnection = NWConnection(host: remoteHost, port: remotePort, using: params)
        udpConnection?.start(queue: queue)
    }

    private func receiveAudio(on connection: NWConnection) {
        connection.receiveMessage { [weak self] data, context, isComplete, error in
            if let data = data, !data.isEmpty, let self = self, self.isRunning {
                // Play received audio buffer
                self.playAudioData(data)
            }
            if error == nil && self?.isRunning == true {
                self?.receiveAudio(on: connection)
            }
        }
    }

    private func playAudioData(_ data: Data) {
        guard let pcmFormat = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16000, channels: 1, interleaved: true) else { return }
        let frameCount = UInt32(data.count / MemoryLayout<Int16>.size)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: pcmFormat, frameCapacity: frameCount) else { return }
        buffer.frameLength = frameCount
        
        data.withUnsafeBytes { rawBuffer in
            if let ptr = rawBuffer.baseAddress, let int16Data = buffer.int16ChannelData {
                memcpy(int16Data[0], ptr, data.count)
            }
        }
    }

    private func sendAudioData(_ data: Data) {
        udpConnection?.send(content: data, completion: .contentProcessed { error in
            if let error = error {
                print("Error sending audio packet: \(error)")
            }
        })
    }

    func stop() {
        guard isRunning else { return }
        isRunning = false

        audioEngine.inputNode.removeTap(onBus: 0)
        audioEngine.stop()

        udpConnection?.cancel()
        udpConnection = nil

        udpListener?.cancel()
        udpListener = nil

        print("AudioEngine stopped")
    }
}
