import Foundation
import CallKit
import AVFoundation

protocol CallKitManagerDelegate: AnyObject {
    func callDidAnswer()
    func callDidEnd()
}

class CallKitManager: NSObject, CXProviderDelegate {
    static let shared = CallKitManager()
    
    weak var delegate: CallKitManagerDelegate?
    private let provider: CXProvider
    private let callController = CXCallController()
    private var activeCallUUID: UUID?

    override init() {
        let configuration = CXProviderConfiguration()
        configuration.localizedName = "CallBridge"
        configuration.supportsVideo = false
        configuration.maximumCallsPerCallGroup = 1
        configuration.supportedHandleTypes = [.phoneNumber, .generic]
        if let icon = UIImage(named: "AppIcon") {
            configuration.iconTemplateImageData = icon.pngData()
        }
        
        self.provider = CXProvider(configuration: configuration)
        super.init()
        self.provider.setDelegate(self, queue: nil)
    }

    func reportIncomingCall(uuid: UUID = UUID(), callerName: String, callerNumber: String, completion: ((Error?) -> Void)? = nil) {
        self.activeCallUUID = uuid
        
        let update = CXCallUpdate()
        let handle = CXHandle(type: .phoneNumber, value: callerNumber)
        update.remoteHandle = handle
        update.localizedCallerName = callerName.isEmpty ? callerNumber : callerName
        update.hasVideo = false
        update.supportsDTMF = false
        update.supportsHolding = false
        update.supportsGrouping = false
        update.supportsUngrouping = false

        provider.reportNewIncomingCall(with: uuid, update: update) { error in
            if let error = error {
                print("Failed to report incoming call: \(error.localizedDescription)")
            } else {
                print("Incoming call reported to CallKit with UUID: \(uuid)")
            }
            completion?(error)
        }
    }

    func endCall() {
        guard let uuid = activeCallUUID else { return }
        let endCallAction = CXEndCallAction(call: uuid)
        let transaction = CXTransaction(action: endCallAction)
        callController.request(transaction) { error in
            if let error = error {
                print("Error requesting end call: \(error.localizedDescription)")
            } else {
                self.activeCallUUID = nil
            }
        }
    }

    // MARK: - CXProviderDelegate

    func providerDidReset(_ provider: CXProvider) {
        activeCallUUID = nil
        delegate?.callDidEnd()
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        print("CallKit: User answered call")
        configureAudioSession()
        delegate?.callDidAnswer()
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        print("CallKit: User ended call")
        activeCallUUID = nil
        delegate?.callDidEnd()
        action.fulfill()
    }

    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        print("CallKit: AudioSession activated")
    }

    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        print("CallKit: AudioSession deactivated")
    }

    private func configureAudioSession() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .allowBluetoothA2DP, .defaultToSpeaker])
            try session.setPreferredSampleRate(16000.0)
            try session.setPreferredIOBufferDuration(0.02) // 20ms buffer
            try session.setActive(true)
        } catch {
            print("Failed to configure audio session: \(error)")
        }
    }
}
