import CoreBluetooth
import Foundation

/// How the link to the glasses is doing, for the settings row.
enum GlassesLinkState: Equatable {
    case idle
    case scanning
    case connecting
    case connected(battery: Int?)
    case failed(String)

    var label: String {
        switch self {
        case .idle: return "Off"
        case .scanning: return "Looking for the glasses…"
        case .connecting: return "Connecting…"
        case .connected(let battery): return battery.map { "Connected · \($0)%" } ?? "Connected"
        case .failed(let reason): return reason
        }
    }
}

/// The BLE link to one pair of G1s: scan, pair the two arms, write pages to both.
///
/// Both arms are written together, left then right, as Even Realities' demo app
/// does; each page packet is acknowledged on the read characteristic. The official
/// `0x25` heartbeat goes out every 8 s per arm (the link drops after ~30 s of
/// silence) and a battery check every ~30 s.
/// Not `@MainActor` on purpose: `CBCentralManagerDelegate` requirements are
/// nonisolated, and the manager is created on the main queue, so every callback
/// already arrives on the main thread.
final class GlassesLink: NSObject {
    /// Called on every state change; the settings row and the controller read it.
    var onChange: ((GlassesLinkState) -> Void)?
    /// The page currently on the glasses, for re-sending after a reconnect.
    private var lastPage: [String]?
    private var state: GlassesLinkState = .idle {
        didSet { if state != oldValue { onChange?(state) } }
    }

    private var central: CBCentralManager?
    /// Arms we have found or connected, keyed by side.
    private var arms: [G1Name.Side: Arm] = [:]
    private var heartbeat: Timer?
    private var battery: Timer?

    private var serviceID: CBUUID { CBUUID(string: GlassesProtocol.uartService) }
    private var writeID: CBUUID { CBUUID(string: GlassesProtocol.uartWrite) }
    private var readID: CBUUID { CBUUID(string: GlassesProtocol.uartRead) }

    /// One arm's connection and its in-flight write.
    private final class Arm {
        let peripheral: CBPeripheral
        let side: G1Name.Side
        let channel: Int
        var write: CBCharacteristic?
        var read: CBCharacteristic?
        /// Resumed by the acknowledgement of the packet being written, if any.
        var ack: CheckedContinuation<Bool, Never>?
        init(peripheral: CBPeripheral, side: G1Name.Side, channel: Int) {
            self.peripheral = peripheral
            self.side = side
            self.channel = channel
        }

        var ready: Bool { write != nil && read != nil }
    }

    func start() {
        guard central == nil else { return }
        arms.removeAll()
        state = .scanning
        central = CBCentralManager(delegate: self, queue: .main, options: [CBCentralManagerOptionShowPowerAlertKey: true])
    }

    func stop() {
        heartbeat?.invalidate()
        battery?.invalidate()
        heartbeat = nil
        battery = nil
        for arm in arms.values { central?.cancelPeripheralConnection(arm.peripheral) }
        arms.removeAll()
        central?.stopScan()
        central = nil
        lastPage = nil
        state = .idle
    }

    /// Puts `page` on both arms. Returns when both have acknowledged, or gives up.
    func show(_ page: [String]) async {
        lastPage = page
        let text = page.joined(separator: "\n")
        await write(GlassesProtocol.text(text, sequence: nextSequence()), to: .left)
        await write(GlassesProtocol.text(text, sequence: nextSequence()), to: .right)
    }

    /// Leaves the glasses on their own idle screen (conversation mode closing).
    func exit() async {
        lastPage = nil
        await write([GlassesProtocol.exitPacket()], to: .left)
        await write([GlassesProtocol.exitPacket()], to: .right)
    }

    private var sequence = 0
    private func nextSequence() -> Int {
        sequence = (sequence + 1) & 0xFF
        return sequence
    }

    /// Writes packets one at a time, waiting for each acknowledgement before the next.
    private func write(_ packets: [[UInt8]], to side: G1Name.Side) async {
        guard let arm = arms[side], arm.ready, let write = arm.write else { return }
        for packet in packets {
            let acknowledged = await send(packet, arm: arm, characteristic: write)
            // One refused or unanswered packet is enough: the page is incomplete.
            if !acknowledged { return }
        }
    }

    private func send(_ bytes: [UInt8], arm: Arm, characteristic: CBCharacteristic) async -> Bool {
        await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
            arm.ack = continuation
            arm.peripheral.writeValue(Data(bytes), for: characteristic, type: .withResponse)
            // A missing answer must not wedge the queue: give up after 5 s.
            Task {
                try? await Task.sleep(for: .seconds(5))
                if let pending = arm.ack {
                    arm.ack = nil
                    pending.resume(returning: false)
                }
            }
        }
    }

    private func resume(_ arm: Arm, accepted: Bool) {
        guard let pending = arm.ack else { return }
        arm.ack = nil
        pending.resume(returning: accepted)
    }

    private func startTimers() {
        heartbeat?.invalidate()
        battery?.invalidate()
        heartbeat = Timer.scheduledTimer(withTimeInterval: 8, repeats: true) { [weak self] _ in
            self?.beat()
        }
        battery = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            self?.checkBattery()
        }
        beat()
        checkBattery()
    }

    private func beat() {
        let packet = GlassesProtocol.heartbeat(sequence: nextSequence())
        for arm in arms.values where arm.ready {
            if let write = arm.write { arm.peripheral.writeValue(Data(packet), for: write, type: .withoutResponse) }
        }
    }

    private func checkBattery() {
        for arm in arms.values where arm.ready {
            if let write = arm.write { arm.peripheral.writeValue(Data(GlassesProtocol.battery()), for: write, type: .withResponse) }
        }
    }

    private func updateState() {
        let ready = arms.values.filter(\.ready)
        if ready.isEmpty {
            state = arms.isEmpty ? .scanning : .connecting
        } else {
            state = .connected(battery: batteryLevel)
        }
        if arms.values.contains(where: \.ready) {
            if heartbeat == nil { startTimers() }
        }
    }

    private var batteryLevel: Int?
}

// MARK: - CoreBluetooth

extension GlassesLink: CBCentralManagerDelegate, CBPeripheralDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            // Scan by service: the glasses advertise Nordic's UART service.
            central.scanForPeripherals(withServices: [serviceID], options: nil)
            state = .scanning
        case .unauthorized:
            state = .failed("Bluetooth is not allowed for VoicePad")
        case .poweredOff:
            state = .failed("Bluetooth is off")
        default:
            state = .failed("Bluetooth is unavailable")
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? peripheral.name
        guard case let .arm(channel, side, _)? = G1Name.parse(name) else { return }
        // First pair only: the glasses are a two-arm set on one channel.
        if let existing = arms[side], existing.channel != channel { return }
        if arms[side] == nil {
            arms[side] = Arm(peripheral: peripheral, side: side, channel: channel)
            peripheral.delegate = self
            central.connect(peripheral, options: nil)
        }
        if arms.count == 2 { central.stopScan() }
        updateState()
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([serviceID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        if let arm = arms.values.first(where: { $0.peripheral == peripheral }) {
            arms[arm.side] = nil
        }
        state = .failed(error?.localizedDescription ?? "Could not connect to the glasses")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        guard let arm = arms.values.first(where: { $0.peripheral == peripheral }) else { return }
        arm.write = nil
        arm.read = nil
        heartbeat?.invalidate()
        heartbeat = nil
        battery?.invalidate()
        battery = nil
        // Keep the peripheral so the reconnect keeps trying; report the gap.
        state = .connecting
        central.connect(peripheral, options: nil)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == serviceID }) else { return }
        peripheral.discoverCharacteristics([writeID, readID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let arm = arms.values.first(where: { $0.peripheral == peripheral }) else { return }
        for characteristic in service.characteristics ?? [] {
            if characteristic.uuid == writeID { arm.write = characteristic }
            if characteristic.uuid == readID {
                arm.read = characteristic
                peripheral.setNotifyValue(true, for: characteristic)
            }
        }
        updateState()
        // A page written before the reconnect finished goes out now.
        if arm.ready, let page = lastPage {
            Task { await self.show(page) }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let arm = arms.values.first(where: { $0.peripheral == peripheral }), characteristic.uuid == readID else { return }
        let bytes = [UInt8](characteristic.value ?? Data())
        if let level = GlassesProtocol.batteryLevel(bytes) {
            batteryLevel = level
            updateState()
            return
        }
        if GlassesProtocol.heartbeatAnswered(bytes) { return }
        resume(arm, accepted: GlassesProtocol.accepted(bytes))
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        // The write characteristic confirms delivery; the page acknowledgement comes
        // on the read characteristic, which is what `show` waits for.
    }
}
