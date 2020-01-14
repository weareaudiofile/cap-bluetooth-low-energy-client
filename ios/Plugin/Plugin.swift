import Foundation
import Capacitor
import CoreBluetooth

enum Key: String {
    case isAvailable = "isAvailable"
    case isEnabled = "isEnabled"
    case enabled = "enabled"
    case services = "services"
}

extension CAPPluginCall {
    func setValue(_ value: Any, forKey key: Key) {
        setValue(value, forKey: key.rawValue)
    }

    func getArray<T>(_ key: Key, _ type: T.Type) -> [T]? {
        return getArray(key.rawValue, type)
    }
}

/**
 * Please read the Capacitor iOS Plugin Development Guide
 * here: https://capacitor.ionicframework.com/docs/plugins/ios
 */
@objc(BluetoothLEClient)
public class BluetoothLEClient: CAPPlugin {
    let manager = CBCentralManager()
    var state: CBManagerState = .unknown

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
        manager.scanForPeripherals(withServices: services, options: nil)
    }

    @objc func connect(_ call: CAPPluginCall) {}

    @objc func discover(_ call: CAPPluginCall) {}

    @objc func disconnect(_ call: CAPPluginCall) {}

    @objc func read(_ call: CAPPluginCall) {}

    @objc func write(_ call: CAPPluginCall) {}

    @objc func readDescriptor(_ call: CAPPluginCall) {}

    @objc func writeDescriptor(_ call: CAPPluginCall) {}

    @objc func getServices(_ call: CAPPluginCall) {}

    @objc func getService(_ call: CAPPluginCall) {}

    @objc func getCharacteristics(_ call: CAPPluginCall) {}

    @objc func getCharacteristic(_ call: CAPPluginCall) {}

    @objc func enableNotifications(_ call: CAPPluginCall) {}

    @objc func disableNotifications(_ call: CAPPluginCall) {}

    private func set(call: CAPPluginCall, key: Key, value: Any) {
        call.setValue(value, forKey: key.rawValue)
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


}
