import AVFoundation
import Observation

/// A microphone the user can record from.
struct InputDevice: Identifiable, Equatable {
    /// `AVAudioSessionPortDescription.uid`, stable across reconnects; used to remember the choice.
    let id: String
    let name: String
    let kind: String
    let isBluetooth: Bool
}

enum InputDeviceNaming {
    static func kind(_ type: AVAudioSession.Port) -> String {
        switch type {
        case .builtInMic: return "Built-in microphone"
        case .headsetMic: return "Wired headset"
        case .bluetoothHFP: return "Bluetooth headset"
        case .usbAudio: return "USB microphone"
        case .carAudio: return "Car audio"
        case .lineIn: return "Line in"
        default: return "Microphone"
        }
    }

    static func isBluetooth(_ type: AVAudioSession.Port) -> Bool {
        type == .bluetoothHFP || type == .bluetoothLE
    }

    static func device(_ port: AVAudioSessionPortDescription) -> InputDevice {
        let kind = kind(port.portType)
        let name = port.portName.trimmingCharacters(in: .whitespaces)
        return InputDevice(
            id: port.uid,
            name: name.isEmpty || port.portType == .builtInMic && name.lowercased().contains("iphone") ? kind : name,
            kind: kind,
            isBluetooth: isBluetooth(port.portType)
        )
    }
}

/// Tracks the connected microphones and routes recording to the chosen one.
/// Bluetooth headsets show up as inputs because the session allows HFP.
@MainActor
@Observable
final class AudioInputs {
    private(set) var devices: [InputDevice] = []
    /// Mic iOS is actually recording from right now.
    private(set) var activeInputName: String?

    /// Called when the list of connected mics changes, with the ids that were present before.
    @ObservationIgnored var onDevicesChanged: ((Set<String>) -> Void)?

    private let session = AVAudioSession.sharedInstance()

    init() {
        configureCategory()
        refresh()
        NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.refresh() }
        }
    }

    /// The category must allow input before `availableInputs` lists anything.
    func configureCategory() {
        try? session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetoothHFP, .allowBluetoothA2DP])
    }

    func refresh() {
        let before = Set(devices.map(\.id))
        var seen = Set<String>()
        let all: [InputDevice] = (session.availableInputs ?? []).map(InputDeviceNaming.device)
        let unique = all.filter { seen.insert($0.id).inserted }
        devices = unique.sorted(by: Self.builtInFirst)
        activeInputName = session.currentRoute.inputs.first.map { InputDeviceNaming.device($0).name }
        if Set(devices.map(\.id)) != before { onDevicesChanged?(before) }
    }

    private static func builtInFirst(_ a: InputDevice, _ b: InputDevice) -> Bool {
        let aBuiltIn = a.kind == "Built-in microphone", bBuiltIn = b.kind == "Built-in microphone"
        if aBuiltIn != bBuiltIn { return aBuiltIn }
        return a.name < b.name
    }

    /// Prefers the mic with `id` (nil = let iOS choose). A mic that isn't
    /// connected falls back to the system default.
    func apply(_ id: String?) {
        let port = id.flatMap { id in session.availableInputs?.first { $0.uid == id } }
        try? session.setPreferredInput(port)
        refresh()
    }
}
