import Foundation
import Network

protocol NetworkClientDelegate: AnyObject {
    func clientDidConnect()
    func clientDidDisconnect()
    func didReceiveIncomingCall(number: String, name: String)
    func didCallAnswered()
    func didCallEnded()
}

class NetworkClient {
    static let shared = NetworkClient()
    
    weak var delegate: NetworkClientDelegate?
    private var connection: NWConnection?
    private var isConnected = false
    private let queue = DispatchQueue(label: "com.ia.callbridge.network", qos: .userInitiated)
    
    private var host: NWEndpoint.Host = "192.168.43.1"
    private let port: NWEndpoint.Port = 8765

    func connect(to ipAddress: String) {
        disconnect()
        
        self.host = NWEndpoint.Host(ipAddress)
        let params = NWParameters.tcp
        connection = NWConnection(host: host, port: port, using: params)
        
        connection?.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                print("Connected to Android Bridge at \(ipAddress):\(self?.port.rawValue ?? 0)")
                self?.isConnected = true
                DispatchQueue.main.async { self?.delegate?.clientDidConnect() }
                self?.readNextMessage()
            case .failed(let error):
                print("Connection failed: \(error)")
                self?.handleDisconnect()
            case .cancelled:
                print("Connection cancelled")
                self?.handleDisconnect()
            default:
                break
            }
        }
        
        connection?.start(queue: queue)
    }

    private func handleDisconnect() {
        if isConnected {
            isConnected = false
            DispatchQueue.main.async { self.delegate?.clientDidDisconnect() }
        }
    }

    private func readNextMessage() {
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, context, isComplete, error in
            if let data = data, !data.isEmpty, let text = String(data: data, encoding: .utf8) {
                let lines = text.components(separatedBy: "\n")
                for line in lines where !line.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    self?.processMessage(line)
                }
            }
            if error == nil && self?.isConnected == true {
                self?.readNextMessage()
            }
        }
    }

    private func processMessage(_ line: String) {
        guard let data = line.data(using: .utf8) else { return }
        do {
            if let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
                let event = json["event"] as? String ?? ""
                print("Received event from Android: \(event)")

                DispatchQueue.main.async {
                    switch event {
                    case "INCOMING_CALL":
                        let number = json["number"] as? String ?? "Unknown"
                        let name = json["name"] as? String ?? ""
                        self.delegate?.didReceiveIncomingCall(number: number, name: name)
                    case "CALL_ANSWERED":
                        self.delegate?.didCallAnswered()
                    case "CALL_ENDED":
                        self.delegate?.didCallEnded()
                    default:
                        break
                    }
                }
            }
        } catch {
            print("Failed to parse JSON: \(error)")
        }
    }

    func sendAnswer() {
        sendAction("ANSWER")
    }

    func sendDecline() {
        sendAction("DECLINE")
    }

    private func sendAction(_ action: String) {
        let payload: [String: Any] = ["action": action]
        if let data = try? JSONSerialization.data(withJSONObject: payload),
           var str = String(data: data, encoding: .utf8) {
            str.append("\n")
            if let finalData = str.data(using: .utf8) {
                connection?.send(content: finalData, completion: .contentProcessed { error in
                    if let error = error {
                        print("Error sending action \(action): \(error)")
                    } else {
                        print("Sent action \(action) to Android")
                    }
                })
            }
        }
    }

    func disconnect() {
        connection?.cancel()
        connection = nil
        handleDisconnect()
    }
}
