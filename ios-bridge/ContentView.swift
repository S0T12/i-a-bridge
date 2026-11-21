import SwiftUI

struct ContentView: View, NetworkClientDelegate, CallKitManagerDelegate {
    @State private var androidIp = "192.168.43.1"
    @State private var isConnected = false
    @State private var callStatus = "آزاد (IDLE)"
    @State private var lastCaller = ""

    var body: some View {
        ZStack {
            Color.black.edgesIgnoringSafeArea(.all)

            VStack(spacing: 24) {
                // Header
                VStack(spacing: 8) {
                    Image(systemName: "phone.fill.badge.checkmark")
                        .font(.system(size: 54))
                        .foregroundColor(.green)
                    
                    Text("CallBridge iOS")
                        .font(.title)
                        .fontWeight(.bold)
                        .foregroundColor(.white)
                    
                    Text("دریافت مستقیم تماس‌های اندروید")
                        .font(.subheadline)
                        .foregroundColor(.gray)
                }
                .padding(.top, 40)

                // Status Card
                VStack(alignment: .leading, spacing: 14) {
                    HStack {
                        Circle()
                            .fill(isConnected ? Color.green : Color.orange)
                            .frame(width: 14, height: 14)
                        
                        Text(isConnected ? "متصل به اندروید" : "در انتظار اتصال به هات‌اسپات")
                            .font(.headline)
                            .foregroundColor(.white)
                    }

                    Text("وضعیت تماس: \(callStatus)")
                        .font(.subheadline)
                        .foregroundColor(.gray)

                    if !lastCaller.isEmpty {
                        Text("آخرین تماس: \(lastCaller)")
                            .font(.caption)
                            .foregroundColor(.white.opacity(0.8))
                    }
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(white: 0.12))
                .cornerRadius(16)
                .padding(.horizontal)

                // IP Address input
                VStack(alignment: .leading, spacing: 8) {
                    Text("آدرس IP هات‌اسپات اندروید:")
                        .font(.caption)
                        .foregroundColor(.gray)

                    TextField("192.168.43.1", text: $androidIp)
                        .padding()
                        .background(Color(white: 0.18))
                        .cornerRadius(10)
                        .foregroundColor(.white)
                        .keyboardType(.numbersAndPunctuation)
                }
                .padding(.horizontal)

                // Connect Button
                Button(action: toggleConnection) {
                    HStack {
                        Image(systemName: isConnected ? "stop.circle.fill" : "play.circle.fill")
                        Text(isConnected ? "قطع اتصال" : "اتصال به اندروید")
                    }
                    .font(.headline)
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 52)
                    .background(isConnected ? Color.red : Color.blue)
                    .cornerRadius(12)
                }
                .padding(.horizontal)

                // Test Call Button
                Button(action: triggerTestCall) {
                    HStack {
                        Image(systemName: "bell.badge.fill")
                        Text("تست زنگ خوردن CallKit (آزمایشی)")
                    }
                    .font(.subheadline)
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 48)
                    .background(Color(white: 0.2))
                    .cornerRadius(12)
                }
                .padding(.horizontal)

                Spacer()
            }
        }
        .onAppear {
            NetworkClient.shared.delegate = self
            CallKitManager.shared.delegate = self
            connectToAndroid()
        }
    }

    private func toggleConnection() {
        if isConnected {
            NetworkClient.shared.disconnect()
            AudioEngine.shared.stop()
        } else {
            connectToAndroid()
        }
    }

    private func connectToAndroid() {
        AudioEngine.shared.configure(remoteIp: androidIp)
        NetworkClient.shared.connect(to: androidIp)
    }

    private func triggerTestCall() {
        CallKitManager.shared.reportIncomingCall(callerName: "تست CallBridge", callerNumber: "09121234567")
    }

    // MARK: - NetworkClientDelegate

    func clientDidConnect() {
        isConnected = true
        callStatus = "آماده دریافت تماس"
    }

    func clientDidDisconnect() {
        isConnected = false
        callStatus = "قطع ارتباط"
    }

    func didReceiveIncomingCall(number: String, name: String) {
        lastCaller = "\(name.isEmpty ? "" : name + " - ")\(number)"
        callStatus = "در حال زنگ خوردن: \(number)"
        CallKitManager.shared.reportIncomingCall(callerName: name, callerNumber: number)
    }

    func didCallAnswered() {
        callStatus = "مکالمه فعال"
        AudioEngine.shared.start()
    }

    func didCallEnded() {
        callStatus = "تماس پایان یافت"
        AudioEngine.shared.stop()
        CallKitManager.shared.endCall()
    }

    // MARK: - CallKitManagerDelegate

    func callDidAnswer() {
        NetworkClient.shared.sendAnswer()
        AudioEngine.shared.start()
        callStatus = "پاسخ داده شد (مکالمه)"
    }

    func callDidEnd() {
        NetworkClient.shared.sendDecline()
        AudioEngine.shared.stop()
        callStatus = "تماس قطع شد"
    }
}
