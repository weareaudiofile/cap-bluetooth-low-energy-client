import Foundation
import Capacitor
import CoreBluetooth

enum PluginKey: String {
    case characteristic = "characteristic"
    case connected = "connected"
    case devices = "devices"
    case disconnected = "disconnected"
    case discovered  = "discovered"
    case id = "id"
    case isAvailable = "isAvailable"
    case isEnabled = "isEnabled"
    case enabled = "enabled"
    case name = "name"
    case service = "service"
    case services = "services"
    case stopOnFirstResult = "stopOnFirstResult"
    case timeout = "timeout"
    case value = "value"
    case withoutResponse = "withoutResponse"
}

enum CallType {
    case scan
    case connect
    case disconnect
    case discover
    case read
}

enum PluginError: Error {
    case missingParameter(_ parameter: PluginKey)
    case peripheralNotFound(id: String)
    case serviceNotFound(uuid: CBUUID)
    case characteristicNotFound(uuid: CBUUID)
    case dataEncodingError
    case notImplemented

    var errorDescription: String {
        switch self {
        case .missingParameter(let parameter):
            return "missing parameter \"\(parameter)\""
        case .peripheralNotFound(let id):
            return "peripheral \"\(id)\" not found"
        case .serviceNotFound(let uuid):
            return "service \"\(uuid.uuidString)\" not found"
        case .characteristicNotFound(let uuid):
            return "characteristic \"\(uuid.uuidString)\" not found"
        case .dataEncodingError:
            return "data encoding error"
        case .notImplemented:
            return "not implemented"
        }
    }
}

typealias ResolveData = [PluginKey: Any]

extension Dictionary where Dictionary.Key == PluginKey {
    func stringKeys() -> PluginResultData {
        var result: PluginResultData = [:]

        for (key, value) in self {
            if let resolveData = value as? ResolveData {
                result[key.rawValue] = resolveData.stringKeys()
            } else if let array = value as? Array<ResolveData> {
                result[key.rawValue] = array.map { $0.stringKeys() }
            } else {
                result[key.rawValue] = value
            }
        }

        return result
    }
}

struct ScanResult {
    let peripheral: CBPeripheral
    let advertisementData: [String : Any]
    let rssi: NSNumber
}

/**
 * Please read the Capacitor iOS Plugin Development Guide
 * here: https://capacitor.ionicframework.com/docs/plugins/ios
 */
@objc(BluetoothLEClient)
public class BluetoothLEClient: CAPPlugin {
    lazy var manager: CBCentralManager = {
        let manager = CBCentralManager()
        manager.delegate = self
        return manager
    }()

    var state: CBManagerState = .unknown
    let scanTimeout = 2000
    var stopOnFirstResult = false
    var scanResults: [ScanResult] = []
    var connectedPeripherals: [String: CBPeripheral] = [:]

    var savedCalls: [CallType: CAPPluginCall] = [:]

    @objc func isAvailable(_ call: CAPPluginCall) {
        let isAvailable = manager.state != .unknown && manager.state != .unsupported
        resolve(call, [.isAvailable: isAvailable])
    }

    @objc func isEnabled(_ call: CAPPluginCall) {
        let isEnabled = manager.state != .unknown && manager.state != .unauthorized
        resolve(call, [.isEnabled: isEnabled])
    }

    @objc func enable(_ call: CAPPluginCall) {
        if manager.state == .poweredOn {
            resolve(call, [.enabled: true])
        }

        // TODO: Ask user to enable Bluetooth
    }

    @objc func scan(_ call: CAPPluginCall) {
        let services: [CBUUID]? = getArray(call, .services, String.self)?.compactMap { CBUUID(string: $0) }

        let timeout = getInt(call, .timeout) ?? scanTimeout
        stopOnFirstResult = getBool(call, .stopOnFirstResult) ?? false

        saveCall(call, type: .scan)

        scanResults = []

        manager.scanForPeripherals(withServices: services, options: nil)

        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(timeout)) {
            self.stopScan()
        }
    }

    @objc func connect(_ call: CAPPluginCall) {
        guard let id = getString(call, .id) else {
            error(call, .missingParameter(.id))
            return
        }

        guard let peripheral = getScannedPeripheral(id) else {
            error(call, .peripheralNotFound(id: id))
            return
        }

        saveCall(call, type: .connect)

        manager.connect(peripheral, options: nil)
    }

    @objc func discover(_ call: CAPPluginCall) {
        guard let id = getString(call, .id) else {
            error(call, .missingParameter(.id))
            return
        }

        guard let peripheral = getConnectedPeripheral(id) else {
            error(call, .peripheralNotFound(id: id))
            return
        }

        saveCall(call, type: .discover)

        peripheral.discoverServices(nil)
    }

    @objc func disconnect(_ call: CAPPluginCall) {
        guard let id = getString(call, .id) else {
            error(call, .missingParameter(.id))
            return
        }

        guard let peripheral = getConnectedPeripheral(id) else {
            error(call, .peripheralNotFound(id: id))
            return
        }

        saveCall(call, type: .disconnect)

        manager.cancelPeripheralConnection(peripheral)
    }

    @objc func read(_ call: CAPPluginCall) {
        guard let id = getString(call, .id) else {
            error(call, .missingParameter(.id))
            return
        }

        guard let serviceUuid = getUuid(call: call, key: .service) else {
            error(call, .missingParameter(.service))
            return
        }

        guard let characteristicUuid = getUuid(call: call, key: .characteristic) else {
            error(call, .missingParameter(.service))
            return
        }

        guard let peripheral = getConnectedPeripheral(id) else {
            error(call, .peripheralNotFound(id: id))
            return
        }

        guard let service = peripheralService(peripheral: peripheral, uuid: serviceUuid) else {
            error(call, .serviceNotFound(uuid: serviceUuid))
            return
        }

        guard let characteristic = serviceCharacteristic(service: service, uuid: characteristicUuid) else {
            error(call, .characteristicNotFound(uuid: characteristicUuid))
            return
        }

        saveCall(call, type: .read)

        peripheral.readValue(for: characteristic)
    }

    @objc func write(_ call: CAPPluginCall) {
        guard let id = getString(call, .id) else {
            error(call, .missingParameter(.id))
            return
        }

        guard let serviceUuid = getUuid(call: call, key: .service) else {
            error(call, .missingParameter(.service))
            return
        }

        guard let characteristicUuid = getUuid(call: call, key: .characteristic) else {
            error(call, .missingParameter(.service))
            return
        }

        guard let value = getString(call, .value) else {
            error(call, .missingParameter(.value))
            return
        }


        guard let peripheral = getConnectedPeripheral(id) else {
            error(call, .peripheralNotFound(id: id))
            return
        }

        guard let service = peripheralService(peripheral: peripheral, uuid: serviceUuid) else {
            error(call, .serviceNotFound(uuid: serviceUuid))
            return
        }

        guard let characteristic = serviceCharacteristic(service: service, uuid: characteristicUuid) else {
            error(call, .characteristicNotFound(uuid: characteristicUuid))
            return
        }

        guard let data = decode(value) else {
            error(call, .dataEncodingError)
            return
        }

        saveCall(call, type: .read)

        peripheral.writeValue(data, for: characteristic, type: .withoutResponse)
    }

    @objc func readDescriptor(_ call: CAPPluginCall) {
        error(call, .notImplemented)
        return
    }

    @objc func writeDescriptor(_ call: CAPPluginCall) {
        error(call, .notImplemented)
        return}

    @objc func getServices(_ call: CAPPluginCall) {
        error(call, .notImplemented)
        return
    }

    @objc func getService(_ call: CAPPluginCall) {
        error(call, .notImplemented)
        return
    }

    @objc func getCharacteristics(_ call: CAPPluginCall) {
        error(call, .notImplemented)
        return
    }

    @objc func getCharacteristic(_ call: CAPPluginCall) {
        error(call, .notImplemented)
        return
    }

    @objc func enableNotifications(_ call: CAPPluginCall) {
        error(call, .notImplemented)
        return
    }

    @objc func disableNotifications(_ call: CAPPluginCall) {
        error(call, .notImplemented)
        return
    }

    private func stopScan() {
        if manager.isScanning {
            manager.stopScan()
        }

        if let call = popSavedCall(type: .scan) {
            let devices: [ResolveData] = scanResults.map {
                return [
                    .name: $0.peripheral.name ?? "",
                    .id: $0.peripheral.identifier.uuidString
                ]
            }

            resolve(call, [ .devices: devices ])
        }
    }

    private func getScannedPeripheral(_ id: String) -> CBPeripheral? {
        return scanResults
            .first { $0.peripheral.identifier.uuidString == id }?
            .peripheral
    }

    private func getConnectedPeripheral(_ id: String) -> CBPeripheral? {
        return connectedPeripherals[id]
    }

    private func saveCall(_ call: CAPPluginCall, type: CallType) {
        savedCalls[type] = call
    }

    private func popSavedCall(type: CallType) -> CAPPluginCall? {
        return savedCalls.removeValue(forKey: type)
    }

    private func getUuid(call: CAPPluginCall, key: PluginKey) -> CBUUID? {
        if let int = getInt(call, key) {
            return makeUuid(int)
        }

        if let string = getString(call, key) {
            return makeUuid(string)
        }

        return nil
    }

    private func makeUuid(_ int: Int) -> CBUUID? {
        return nil
    }

    private func makeUuid(_ string: String) -> CBUUID? {
        return CBUUID(string: string)
    }

    private func decode(_ base64: String) -> Data? {
        return Data(base64Encoded: base64, options: .ignoreUnknownCharacters)
    }

    private func encode(_ data: Data) -> String {
        return data.base64EncodedString()
    }

    private func peripheralService(peripheral: CBPeripheral, uuid: CBUUID) -> CBService? {
        return peripheral.services?.first { $0.uuid == uuid }
    }

    private func serviceCharacteristic(service: CBService, uuid: CBUUID) -> CBCharacteristic? {
        return service.characteristics?.first { $0.uuid == uuid }
    }

    private func notifyListeners(_ event: String, data: ResolveData) {
        notifyListeners(event, data: data.stringKeys())
    }
}

// MARK: - Call handling

extension BluetoothLEClient {
    func getArray<T>(_ call: CAPPluginCall, _ key: PluginKey, _ type: T.Type) -> [T]? {
        return call.getArray(key.rawValue, type)
    }

    func getBool(_ call: CAPPluginCall, _ key: PluginKey) -> Bool? {
        return call.getBool(key.rawValue)
    }

    func getInt(_ call: CAPPluginCall, _ key: PluginKey) -> Int? {
        return call.getInt(key.rawValue)
    }

    func getString(_ call: CAPPluginCall, _ key: PluginKey) -> String? {
        return call.getString(key.rawValue)
    }

    func resolve(_ call: CAPPluginCall, _ data: ResolveData) {
        call.resolve(data.stringKeys())
    }

    func error(_ call: CAPPluginCall, _ err: PluginError) {
        call.error(err.errorDescription)
    }
}

extension BluetoothLEClient: CBCentralManagerDelegate {
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            print("Bluetooth is on")
            break
        case .poweredOff:
            print("Bluetooth is off")
            break
        case .resetting:
            print("Bluetooth is resetting")
            break
        case .unauthorized:
            print("Bluetooth is unauthorized")
            break
        case .unsupported:
            print("Bluetooth is unsupported")
            break
        case .unknown:
            print("Bluetooth state is unknown")
            break
        default:
            print("Bluetooth entered unknown state")
            break
        }
    }

    public func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String : Any], rssi RSSI: NSNumber) {
        scanResults.append(ScanResult(peripheral: peripheral, advertisementData: advertisementData, rssi: RSSI))

        if (stopOnFirstResult) {
            stopScan()
        }
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.delegate = self
        connectedPeripherals[peripheral.identifier.uuidString] = peripheral

        if let call = popSavedCall(type: .connect) {
            resolve(call, [
                .connected: true
            ])
        }
    }

    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        peripheral.delegate = nil
        connectedPeripherals.removeValue(forKey: peripheral.identifier.uuidString)

        if let call = popSavedCall(type: .disconnect) {
            resolve(call, [
                .disconnected: true
            ])
        }
    }
}

extension BluetoothLEClient: CBPeripheralDelegate {
    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let services = peripheral.services {
            for service in services {
                peripheral.discoverCharacteristics(nil, for: service)
            }
        }

        if let call = popSavedCall(type: .discover) {
            resolve(call, [
                .discovered: true
            ])
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {

    }

    public func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        let value = characteristic.value ?? Data()
        notifyListeners(characteristic.uuid.uuidString, data: [.value: value])
    }
}
