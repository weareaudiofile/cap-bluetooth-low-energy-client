import Foundation
import Capacitor
import CoreBluetooth

enum Key: String {
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
    case missingParameter(_ parameter: Key)
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

extension CAPPluginCall {
    func setValue(_ value: Any, forKey key: Key) {
        setValue(value, forKey: key.rawValue)
    }

    func getArray<T>(_ key: Key, _ type: T.Type) -> [T]? {
        return getArray(key.rawValue, type)
    }

    func getBool(_ key: Key) -> Bool? {
        return getBool(key.rawValue)
    }

    func getInt(_ key: Key) -> Int? {
        return getInt(key.rawValue)
    }

    func getString(_ key: Key) -> String? {
        return getString(key.rawValue)
    }

    func resolve(_ data: ResolveData) {
        resolve(data.stringKeys())
    }

    func error(_ err: PluginError) {
        error(err.errorDescription)
    }
}

typealias ResolveData = [Key: Any]

extension Dictionary where Dictionary.Key == Plugin.Key {
    func stringKeys() -> PluginResultData {
        var result: PluginResultData = [:]

        for (key, value) in self {
            if let resolveData = value as? ResolveData {
                result[key.rawValue] = resolveData.stringKeys()
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
    let manager = CBCentralManager()
    var state: CBManagerState = .unknown
    let scanTimeout = 2000
    var stopOnFirstDevice = false
    var scanResults: [ScanResult] = []
    var connectedPeripherals: [String: CBPeripheral] = [:]

    var savedCalls: [CallType: CAPPluginCall] = [:]

    @objc func isAvailable(_ call: CAPPluginCall) {
        let isAvailable = manager.state != .unknown && manager.state != .unsupported
        call.setValue(isAvailable, forKey: .isAvailable)
        call.resolve()
    }

    @objc func isEnabled(_ call: CAPPluginCall) {
        let isEnabled = manager.state != .unknown && manager.state != .unauthorized
        call.setValue(isEnabled, forKey: .isEnabled)
        call.resolve()
    }

    @objc func enable(_ call: CAPPluginCall) {
        if manager.state == .poweredOn {
            call.setValue(true, forKey: .enabled)
            call.resolve()
        }

        // TODO: Ask user to enable Bluetooth
    }

    @objc func scan(_ call: CAPPluginCall) {
        let services: [CBUUID]? = call.getArray(.services, String.self)?.compactMap { CBUUID(string: $0) }

        let timeout = call.getInt(.timeout) ?? scanTimeout
        stopOnFirstDevice = call.getBool(.stopOnFirstResult) ?? false

        scanResults = []

        manager.scanForPeripherals(withServices: services, options: nil)

        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(timeout)) {
            self.stopScan()
        }
    }

    @objc func connect(_ call: CAPPluginCall) {
        guard let id = call.getString(.id) else {
            call.error(.missingParameter(.id))
            return
        }

        guard let peripheral = getPeripheral(id) else {
            call.error(.peripheralNotFound(id: id))
            return
        }

        saveCall(call, type: .connect)

        manager.connect(peripheral, options: nil)
    }

    @objc func discover(_ call: CAPPluginCall) {
        guard let id = call.getString(.id) else {
            call.error(.missingParameter(.id))
            return
        }

        guard let peripheral = getPeripheral(id) else {
            call.error(.peripheralNotFound(id: id))
            return
        }

        saveCall(call, type: .discover)

        peripheral.discoverServices(nil)
    }

    @objc func disconnect(_ call: CAPPluginCall) {
        guard let id = call.getString(.id) else {
            call.error(.missingParameter(.id))
            return
        }

        guard let peripheral = getPeripheral(id) else {
            call.error(.peripheralNotFound(id: id))
            return
        }

        saveCall(call, type: .disconnect)

        manager.cancelPeripheralConnection(peripheral)
    }

    @objc func read(_ call: CAPPluginCall) {
        guard let id = call.getString(.id) else {
            call.error(.missingParameter(.id))
            return
        }

        guard let serviceUuid = getUuid(call: call, key: .service) else {
            call.error(.missingParameter(.service))
            return
        }

        guard let characteristicUuid = getUuid(call: call, key: .characteristic) else {
            call.error(.missingParameter(.service))
            return
        }

        guard let peripheral = getPeripheral(id) else {
            call.error(.peripheralNotFound(id: id))
            return
        }

        guard let service = peripheralService(peripheral: peripheral, uuid: serviceUuid) else {
            call.error(.serviceNotFound(uuid: serviceUuid))
            return
        }

        guard let characteristic = serviceCharacteristic(service: service, uuid: characteristicUuid) else {
            call.error(.characteristicNotFound(uuid: characteristicUuid))
            return
        }

        saveCall(call, type: .read)

        peripheral.readValue(for: characteristic)
    }

    @objc func write(_ call: CAPPluginCall) {
        guard let id = call.getString(.id) else {
            call.error(.missingParameter(.id))
            return
        }

        guard let serviceUuid = getUuid(call: call, key: .service) else {
            call.error(.missingParameter(.service))
            return
        }

        guard let characteristicUuid = getUuid(call: call, key: .characteristic) else {
            call.error(.missingParameter(.service))
            return
        }

        guard let value = call.getString(.value) else {
            call.error(.missingParameter(.value))
            return
        }


        guard let peripheral = getPeripheral(id) else {
            call.error(.peripheralNotFound(id: id))
            return
        }

        guard let service = peripheralService(peripheral: peripheral, uuid: serviceUuid) else {
            call.error(.serviceNotFound(uuid: serviceUuid))
            return
        }

        guard let characteristic = serviceCharacteristic(service: service, uuid: characteristicUuid) else {
            call.error(.characteristicNotFound(uuid: characteristicUuid))
            return
        }

        guard let data = decode(value) else {
            call.error(.dataEncodingError)
            return
        }

        saveCall(call, type: .read)

        peripheral.writeValue(data, for: characteristic, type: .withoutResponse)
    }

    @objc func readDescriptor(_ call: CAPPluginCall) {
        call.error(.notImplemented)
        return
    }

    @objc func writeDescriptor(_ call: CAPPluginCall) {
        call.error(.notImplemented)
        return}

    @objc func getServices(_ call: CAPPluginCall) {
        call.error(.notImplemented)
        return
    }

    @objc func getService(_ call: CAPPluginCall) {
        call.error(.notImplemented)
        return
    }

    @objc func getCharacteristics(_ call: CAPPluginCall) {
        call.error(.notImplemented)
        return
    }

    @objc func getCharacteristic(_ call: CAPPluginCall) {
        call.error(.notImplemented)
        return
    }

    @objc func enableNotifications(_ call: CAPPluginCall) {
        call.error(.notImplemented)
        return
    }

    @objc func disableNotifications(_ call: CAPPluginCall) {
        call.error(.notImplemented)
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

            call.resolve([ .devices: devices ])
        }
    }

    private func getPeripheral(_ id: String) -> CBPeripheral? {
        return scanResults
            .first { $0.peripheral.identifier.uuidString == id }?
            .peripheral
    }

    private func saveCall(_ call: CAPPluginCall, type: CallType) {
        savedCalls[type] = call
    }

    private func popSavedCall(type: CallType) -> CAPPluginCall? {
        return savedCalls.removeValue(forKey: type)
    }

    private func getUuid(call: CAPPluginCall, key: Key) -> CBUUID? {
        if let int = call.getInt(key) {
            return makeUuid(int)
        }

        if let string = call.getString(key) {
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

        if (stopOnFirstDevice) {
            stopScan()
        }
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        connectedPeripherals[peripheral.identifier.uuidString] = peripheral

        if let call = popSavedCall(type: .connect) {
            call.resolve([
                .connected: true
            ])
        }
    }

    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        connectedPeripherals.removeValue(forKey: peripheral.identifier.uuidString)

        if let call = popSavedCall(type: .disconnect) {
            call.resolve([
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
            call.resolve([
                .discovered: true
            ])
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        let value = characteristic.value ?? Data()
        notifyListeners(characteristic.uuid.uuidString, data: [.value: value])
    }
}
