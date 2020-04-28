import Foundation
import Capacitor
import CoreBluetooth

// TODO: Get withoutResponse value from call
private var WRITE_TYPE = CBCharacteristicWriteType.withoutResponse

extension String {
    static var authenticatedSignedWrites = "authenticatedSignedWrites"
    static var broadcast = "broadcast"
    static var characteristic = "characteristic"
    static var characteristics = "characteristics"
    static var connected = "connected"
    static var deviceDisconnected = "deviceDisconnected"
    static var devices = "devices"
    static var disconnected = "disconnected"
    static var discovered  = "discovered"
    static var descriptors = "descriptors"
    static var id = "id"
    static var included = "included"
    static var indicate = "indicate"
    static var isAvailable = "isAvailable"
    static var isEnabled = "isEnabled"
    static var isPrimary = "isPrimary"
    static var enabled = "enabled"
    static var name = "name"
    static var notify = "notify"
    static var properties = "properties"
    static var read = "read"
    static var rssi = "rssi"
    static var service = "service"
    static var services = "services"
    static var stopOnFirstResult = "stopOnFirstResult"
    static var timeout = "timeout"
    static var uuid = "uuid"
    static var value = "value"
    static var write = "write"
    static var writeWithoutResponse = "writeWithoutResponse"
}

enum CallType {
    case scan
    case connect
    case disconnect
    case discover
}

enum CharacteristicCallType {
    case read
    case write
    case writeWithoutResponse
}

enum WriteStatus {
    case queuedWriteWithoutResponse
    case success
}

enum PluginError: Error {
    case missingParameter(_ parameter: String)
    case peripheralNotFound(id: String)
    case serviceNotFound(uuid: CBUUID)
    case characteristicNotFound(uuid: CBUUID)
    case dataEncodingError
    case notImplemented(_ feature: String)

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
        case .notImplemented(let feature):
            return "\(feature) not implemented"
        }
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
    var manager: CBCentralManager?

    var state: CBManagerState = .unknown
    let scanTimeout = 2000
    var stopOnFirstResult = false
    var scanResults: [ScanResult] = []
    var knownPeripherals: [String: CBPeripheral] = [:]
    var connectedPeripherals: [String: CBPeripheral] = [:]
    var servicesAwaitingDiscovery: [String: Set<CBService>] = [:]

    var savedCalls: [CallType: CAPPluginCall] = [:]
    var savedCharacteristicCalls: [CBUUID: [CharacteristicCallType: CAPPluginCall]] = [:]

    @objc override public func load() {
        manager = CBCentralManager()
        manager?.delegate = self
    }

    // The last iOS devices not supporting BLE were the 4S and the iPad 2, which cannot run iOS 10+,
    // so any iOS device running iOS 11+ supports BLE
    @objc func isAvailable(_ call: CAPPluginCall) {
        call.resolve([.isAvailable: true])
    }

    @objc func isEnabled(_ call: CAPPluginCall) {
        let isEnabled = manager?.state == .poweredOn
        call.resolve([.isEnabled: isEnabled])
    }

    @objc func enable(_ call: CAPPluginCall) {
        if manager?.state == .poweredOn {
            call.resolve([.enabled: true])
        }

        // TODO: Ask user to enable Bluetooth
    }

    @objc func scan(_ call: CAPPluginCall) {
        switch getUuids(call, key: .services) {

        case .success(let services):
            let timeout = call.getInt(.timeout) ?? scanTimeout
            stopOnFirstResult = call.getBool(.stopOnFirstResult) ?? false

            saveCall(call, type: .scan)

            scanResults = []

            manager?.scanForPeripherals(withServices: services, options: nil)

            DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(timeout)) {
                self.stopScan()
            }

        case .failure(let err):
            call.error(err.errorDescription, err)
        }
    }

    @objc func connect(_ call: CAPPluginCall) {
        switch getScannedPeripheral(call) {

        case .success(let peripheral):
            saveCall(call, type: .connect)
            manager?.connect(peripheral, options: nil)

        case .failure(let err):
            call.error(err.errorDescription, err)
        }
    }

    @objc func discover(_ call: CAPPluginCall) {
        switch getConnectedPeripheral(call) {

        case .success(let peripheral):
            saveCall(call, type: .discover)
            peripheral.discoverServices(nil)

        case .failure(let err):
            call.error(err.errorDescription, err)
        }
    }

    @objc func disconnect(_ call: CAPPluginCall) {
        switch getConnectedPeripheral(call) {

        case .success(let peripheral):
            saveCall(call, type: .disconnect)
            manager?.cancelPeripheralConnection(peripheral)

        case .failure(let err):
            call.error(err.errorDescription, err)
        }
    }

    @objc func read(_ call: CAPPluginCall) {
        switch getPeripheralAndCharacteristic(call) {

        case .success(let (peripheral, characteristic)):
            saveCall(call, characteristic: characteristic, type: .read)
            print("[ios] reading   \(externalUuidString(characteristic.uuid))")
            peripheral.readValue(for: characteristic)

        case .failure(let err):
            call.error(err.errorDescription, err)
        }
    }

    @objc func write(_ call: CAPPluginCall) {
        switch getPeripheralAndCharacteristic(call) {

        case .success(let (peripheral, characteristic)):
            switch getValueData(call) {

            case .success(let data):
                if characteristic.properties.contains(.write) {
                    print("[ios] writing   \(externalUuidString(characteristic.uuid))")
                    saveCall(call, characteristic: characteristic, type: .write)
                    peripheral.writeValue(data, for: characteristic, type: .withResponse)
                } else {
                    //                    if peripheral.canSendWriteWithoutResponse {
                    print("[ios] writwor   \(externalUuidString(characteristic.uuid))")
                    peripheral.writeValue(data, for: characteristic, type: .withoutResponse)
                    call.resolve()
                }


            case .failure(let err):
                call.error(err.errorDescription, err)
            }

        case .failure(let err):
            call.error(err.errorDescription, err)
        }
    }

    @objc func readDescriptor(_ call: CAPPluginCall) {
        let err = PluginError.notImplemented("readDescriptor()")
        call.error(err.errorDescription, err)
        return
    }

    @objc func writeDescriptor(_ call: CAPPluginCall) {
        let err = PluginError.notImplemented("writeDescriptor()")
        call.error(err.errorDescription, err)
        return
    }

    @objc func getServices(_ call: CAPPluginCall) {
        let err = PluginError.notImplemented("getServices()")
        call.error(err.errorDescription, err)
        return
    }

    @objc func getService(_ call: CAPPluginCall) {
        switch getPeripheralAndService(call) {
        case .success(let (_, service)):
            call.resolve(serialize(service))
        case .failure(let error):
            call.reject(error.errorDescription)
        }
    }

    @objc func getCharacteristics(_ call: CAPPluginCall) {
        let err = PluginError.notImplemented("getCharacteristics()")
        call.error(err.errorDescription, err)
        return
    }

    @objc func getCharacteristic(_ call: CAPPluginCall) {
        switch getPeripheralAndCharacteristic(call) {
        case .success(let (_, characteristic)):
            call.resolve(serialize(characteristic))
        case .failure(let error):
            call.reject(error.errorDescription)
        }
    }

    @objc func enableNotifications(_ call: CAPPluginCall) {
        let enabled = true

        switch getPeripheralAndCharacteristic(call) {

        case .success(let (peripheral, characteristic)):
            peripheral.setNotifyValue(enabled, for: characteristic)
            call.resolve([.enabled: enabled])

        case .failure(let err):
            call.error(err.errorDescription, err)
        }
    }

    @objc func disableNotifications(_ call: CAPPluginCall) {
        let enabled = false

        switch getPeripheralAndCharacteristic(call) {

        case .success(let (peripheral, characteristic)):
            peripheral.setNotifyValue(enabled, for: characteristic)
            call.resolve([.enabled: enabled])

        case .failure(let err):
            call.error(err.errorDescription, err)
        }
    }

    private func stopScan() {
        if let manager = manager, manager.isScanning {
            manager.stopScan()
        }

        if let call = popSavedCall(type: .scan) {
            let devices: [PluginResultData] = scanResults.map {
                return [
                    .name: $0.peripheral.name ?? "",
                    .id: externalUuidString($0.peripheral.identifier),
                    .rssi: $0.rssi
                ]
            }

            call.resolve([ .devices: devices ])
        }
    }

    private func getScannedPeripheral(_ id: String) -> CBPeripheral? {
        return scanResults
            .first { externalUuidString($0.peripheral.identifier) == id }?
            .peripheral
    }

    private func getConnectedPeripheral(_ id: String) -> CBPeripheral? {
        return connectedPeripherals[id]
    }

    private func addServiceAwaitingDiscovery(_ service: CBService) {
        let id = externalUuidString(service.peripheral.identifier)
        var services = servicesAwaitingDiscovery[id] ?? Set<CBService>()
        services.insert(service)
        servicesAwaitingDiscovery[id] = services
    }

    private func removeServiceAwaitingDiscovery(_ service: CBService) {
        let id = externalUuidString(service.peripheral.identifier)
        var services = servicesAwaitingDiscovery[id] ?? Set<CBService>()
        services.remove(service)
        servicesAwaitingDiscovery[id] = services
    }

    private func getServicesAwaitingDiscovery(_ peripheral: CBPeripheral) -> Set<CBService> {
        let id = externalUuidString(peripheral.identifier)
        return servicesAwaitingDiscovery[id] ?? Set<CBService>()
    }

    private func saveCall(_ call: CAPPluginCall, type: CallType) {
        savedCalls[type] = call
    }

    private func saveCall(_ call: CAPPluginCall, characteristic: CBCharacteristic, type: CharacteristicCallType) {
        if savedCharacteristicCalls[characteristic.uuid] == nil {
            savedCharacteristicCalls[characteristic.uuid] = [type : call]
        } else {
            savedCharacteristicCalls[characteristic.uuid]?[type] = call
        }
    }

    private func popSavedCall(type: CallType) -> CAPPluginCall? {
        return savedCalls.removeValue(forKey: type)
    }

    private func popSavedCall(characteristic: CBCharacteristic, type: CharacteristicCallType) -> CAPPluginCall? {
        return savedCharacteristicCalls[characteristic.uuid]?.removeValue(forKey: type)
    }

    private func getUuid(call: CAPPluginCall, key: String) -> CBUUID? {
        if let int = call.getInt(key) {
            return makeUuid(int)
        }

        if let string = call.getString(key) {
            return makeUuid(string)
        }

        return nil
    }

    private func makeUuid(_ int: Int) -> CBUUID? {
        let hexString = String(format: "%04x", int)

        if hexString.count == 4 {
            return CBUUID(string: "0000\(hexString)-0000-1000-8000-00805F9B34FB");
        }

        return nil
    }

    private func makeUuid(_ string: String) -> CBUUID? {
        return CBUUID(string: string)
    }

    private func get16BitUUID(uuid: CBUUID) -> Int? {
        let uuidString = uuid.uuidString;
        let start = uuidString.index(uuidString.startIndex, offsetBy: 4)
        let end = uuidString.index(uuidString.startIndex, offsetBy: 8)
        let shortUuidString = uuidString[start..<end]
        return Int(shortUuidString, radix: 16);
    }

    private func decode(_ base64: String) -> Data? {
        return Data(base64Encoded: base64, options: .ignoreUnknownCharacters)
    }

    private func encode(_ data: Data) -> String {
        return data.base64EncodedString()
    }

    private func encodeToByteArray(_ data: Data) -> [UInt8] {
        return data.map { $0 }
    }

    private func peripheralService(peripheral: CBPeripheral, uuid: CBUUID) -> CBService? {
        return peripheral.services?.first { $0.uuid == uuid }
    }

    private func serviceCharacteristic(service: CBService, uuid: CBUUID) -> CBCharacteristic? {
        return service.characteristics?.first { $0.uuid == uuid }
    }

    private func serialize(_ service: CBService) -> PluginResultData {
        let characteristics = service.characteristics?.map { serialize($0) } ?? []

        var dict: PluginResultData = [
            .uuid : service.uuid.uuidString,
            .isPrimary: service.isPrimary,
            .characteristics: characteristics
        ]

        if let included = service.includedServices?.map({ serialize($0) }) {
            dict[.included] = included
        }

        return dict
    }

    private func serialize(_ characteristic: CBCharacteristic) -> PluginResultData {
        return [
            .uuid: characteristic.uuid.uuidString,
            .properties: serialize(characteristic.properties),
            .descriptors: serialize(characteristic.descriptors)
        ]
    }

    private func serialize(_ properties: CBCharacteristicProperties) -> PluginResultData {
        return [
            .authenticatedSignedWrites: properties.contains(.authenticatedSignedWrites),
            .broadcast: properties.contains(.broadcast),
            .indicate: properties.contains(.indicate),
            .notify: properties.contains(.notify),
            .read: properties.contains(.read),
            .write: properties.contains(.write),
            .writeWithoutResponse: properties.contains(.writeWithoutResponse)
        ]
    }

    private func serialize(_ descriptors: [CBDescriptor]?) -> [String] {
        guard let descriptors = descriptors else { return [] }
        return descriptors.compactMap { $0.value as? String }
    }
}

// MARK: - Call handling

extension BluetoothLEClient {
    private func getConnectedPeripheral(_ call: CAPPluginCall) -> Result<CBPeripheral, PluginError> {
        guard let id = call.getString(.id) else {
            return .failure(.missingParameter(.id))
        }

        guard let peripheral = getConnectedPeripheral(id) else {
            return .failure(.peripheralNotFound(id: id))
        }

        return .success(peripheral)
    }

    private func getScannedPeripheral(_ call: CAPPluginCall) -> Result<CBPeripheral, PluginError> {
        guard let id = call.getString(.id) else {
            return .failure(.missingParameter(.id))
        }

        guard let peripheral = getScannedPeripheral(id) else {
            return .failure(.peripheralNotFound(id: id))
        }

        return .success(peripheral)
    }

    private func getPeripheralAndService(_ call: CAPPluginCall) -> Result<(CBPeripheral, CBService), PluginError> {
        switch getConnectedPeripheral(call) {
        case .success(let peripheral):
            guard let serviceUuid = getUuid(call: call, key: .service) else {
                return .failure(.missingParameter(.service))
            }

            guard let service = peripheralService(peripheral: peripheral, uuid: serviceUuid) else {
                return .failure(.serviceNotFound(uuid: serviceUuid))
            }

            return .success((peripheral, service))

        case .failure(let err):
            return .failure(err)
        }
    }

    private func getPeripheralAndCharacteristic(_ call: CAPPluginCall) -> Result<(CBPeripheral, CBCharacteristic), PluginError> {
        switch getPeripheralAndService(call) {
        case .success(let (peripheral, service)):
            guard let characteristicUuid = getUuid(call: call, key: .characteristic) else {
                return .failure(.missingParameter(.service))
            }

            guard let characteristic = serviceCharacteristic(service: service, uuid: characteristicUuid) else {
                return .failure(.characteristicNotFound(uuid: characteristicUuid))
            }

            return .success((peripheral, characteristic))

        case .failure(let err):
            return .failure(err)
        }
    }

    private func getValueData(_ call: CAPPluginCall) -> Result<Data, PluginError> {
        guard let value = call.getString(.value) else {
            return .failure(.missingParameter(.value))
        }

        guard let data = decode(value) else {
            return .failure(.dataEncodingError)
        }

        return .success(data)
    }

    private func getUuids(_ call: CAPPluginCall, key: String) -> Result<[CBUUID], PluginError> {
        guard let services = call.getArray(key, String.self) else {
            return .failure(.missingParameter(.services))
        }

        return .success(services.compactMap { CBUUID(string: $0) })
    }

    private func externalUuidString(_ uuid: CBUUID) -> String {
        return uuid.uuidString.lowercased()
    }

    private func externalUuidString(_ uuid: UUID) -> String {
        return uuid.uuidString.lowercased()
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
        knownPeripherals[externalUuidString(peripheral.identifier)] = peripheral
        scanResults.append(ScanResult(peripheral: peripheral, advertisementData: advertisementData, rssi: RSSI))

        if (stopOnFirstResult) {
            stopScan()
        }
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.delegate = self
        connectedPeripherals[externalUuidString(peripheral.identifier)] = peripheral

        if let call = popSavedCall(type: .connect) {
            call.resolve([
                .connected: true
            ])
        }
    }

    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        peripheral.delegate = nil
        connectedPeripherals.removeValue(forKey: externalUuidString(peripheral.identifier))

        if let call = popSavedCall(type: .disconnect) {
            call.resolve([
                .disconnected: true
            ])
        }

        notifyListeners(.deviceDisconnected, data: [.id : externalUuidString(peripheral.identifier)])
    }
}

extension BluetoothLEClient: CBPeripheralDelegate {
    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let services = peripheral.services {
            for service in services {
                addServiceAwaitingDiscovery(service)
                peripheral.discoverCharacteristics(nil, for: service)
            }
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        removeServiceAwaitingDiscovery(service)

        guard getServicesAwaitingDiscovery(peripheral).isEmpty else { return }

        if let call = popSavedCall(type: .discover) {
            call.resolve([
                .discovered: true
            ])
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        let encodedValue = encodeToByteArray(characteristic.value ?? Data())
        let data: PluginResultData = [
            .id: externalUuidString(peripheral.identifier),
            .value: encodedValue
        ]

        notifyListeners(externalUuidString(characteristic.uuid), data: data)

        print("[ios] updated   \(externalUuidString(characteristic.uuid))")

        if let call = popSavedCall(characteristic: characteristic, type: .read) {
            print("[ios] resolving \(externalUuidString(characteristic.uuid)) (read)")
            call.resolve(data)
        }

        if let call = popSavedCall(characteristic: characteristic, type: .write) {
            print("[ios] resolving \(externalUuidString(characteristic.uuid)) (write)")
            call.resolve(data)
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error = error {
            print(error)
            return
        }

        print("[iOS] wrote \(String(describing: characteristic.value)) to \(characteristic.uuid.uuidString)")


        if let call = popSavedCall(characteristic: characteristic, type: .write) {
            print("[ios] resolving \(externalUuidString(characteristic.uuid)) (write)")
            call.resolve()
        }
    }
}
