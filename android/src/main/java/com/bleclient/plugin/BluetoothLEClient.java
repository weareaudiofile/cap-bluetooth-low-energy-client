package com.bleclient.plugin;

import android.Manifest;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.ParcelUuid;
import android.preference.PreferenceManager;
import android.util.Base64;
import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.NativePlugin;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

@NativePlugin(
        permissions = {
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
        },
        requestCodes = {
                BluetoothLEClient.REQUEST_ENABLE_BT
        }
)
public class BluetoothLEClient extends Plugin {

    static final int REQUEST_ENABLE_BT = 420;

    static final int SERVICES_UNDISCOVERED = 0;
    static final int SERVICES_DISCOVERING = 1;
    static final int SERVICES_DISCOVERED = 2;

    static final String BASE_UUID_HEAD = "0000";
    static final String BASE_UUID_TAIL = "-0000-1000-8000-00805F9B34FB";

    static final String keyDiscovered = "discoveredState";
    static final String keyPeripheral = "peripheral";
    static final String keyConnectionState = "connectionState";

    static final String keyEnabled = "enabled";
    static final String keyAvailable = "isAvailable";
    static final String keyAvailableDevices = "devices";
    static final String keyAddress = "id";
    static final String keyUuid = "uuid";
    static final String keyServices = "services";
    static final String keyService = "service";
    static final String keyAutoConnect = "autoConnect";
    static final String keyConnected = "connected";
    static final String keyDisconnected = "disconnected";
    static final String keyIncludedServices = "included";
    static final String keyCharacteristics = "characteristics";
    static final String keyCharacteristic = "characteristic";
    static final String keyDescriptor = "descriptor";
    static final String keyValue = "value";
    static final String keyTimeout = "timeout";
    static final String keyStopOnFirstResult = "stopOnFirstResult";
    static final String keyDiscoveryState = "discovered";
    static final String keySuccess = "success";
    static final String keyDeviceType = "type";
    static final String keyBondState = "bondState";
    static final String keyDeviceName = "name";
    static final String keyRssi = "rssi";
    static final String keyCharacterisicDescripors = "descriptors";
    static final String keyCharacteristicProperies = "properties";
    static final String keyIsPrimaryService = "isPrimary";
    static final String keyPropertyAuthenticatedSignedWrites = "authenticatedSignedWrites";
    static final String keyPropertyBroadcast = "broadcast";
    static final String keyPropertyIndicate = "indicate";
    static final String keyPropertyNotify = "notify";
    static final String keyPropertyRead = "read";
    static final String keyPropertyWrite = "write";
    static final String keyPropertyWriteWithoutResponse = "writeWithoutResponse";

    static final String keyErrorAddressMissing = "Property id is required";
    static final String keyErrorServiceMissing = "Property service is required";
    static final String keyErrorCharacteristicMissing = "Property characteristic is required";
    static final String keyErrorDescriptorMissing = "Property descriptor is required";
    static final String keyErrorNotConnected = "Not connected to peripheral";
    static final String keyErrorServiceNotFound = "Service not found";
    static final String keyErrorCharacteristicNotFound = "Characteristic not found";
    static final String keyErrorDescriptorNotFound = "Descriptor not found";
    static final String keyErrorValueMissing = "Property value is required";
    static final String keyErrorValueSet = "Failed to set value";
    static final String keyErrorValueWrite = "Failed to write value";
    static final String keyErrorValueRead = "Failed to read value";
    static final String keyErrorValueDisconnected = "Device is disconnected";


    static final String keyOperationConnect = "connectCallback";
    static final String keyOperationDisconnect = "disconnectCallback";
    static final String keyOperationDiscover = "discoverCallback";
    static final String keyOperationReadDescriptor = "readDescriptorCallback";
    static final String keyOperationWriteDescriptor = "writeDescriptorCallback";
    static final String keyOperationRead = "readCharacteristicCallback";
    static final String keyOperationWrite = "writeCharacteristicCallback";

    private static final String keyEventDeviceDisconnected = "deviceDisconnected";

    static final int clientCharacteristicConfigurationUuid = 0x2902;

    private static final int defaultScanTimeout = 2000;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;

    private BLEScanCallback scanCallback;
    private HashMap<String, Device> availableDevices = new HashMap<>();
    private HashMap<String, Object> connections = new HashMap<>();

    private enum BLECommandType {
        write, read, discoverServices
    }

    private final class BLECommand {
        BLECommandType type;
        BluetoothGatt gatt;
        BluetoothGattCharacteristic characteristic;
        PluginCall call;
        byte[] data;

        BLECommand(PluginCall call, BLECommandType type, BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] data) {
            this.call = call;
            this.type = type;
            this.gatt = gatt;
            this.characteristic = characteristic;
            this.data = data;
        }
    }

    private ConcurrentLinkedQueue<BLECommand> queue = new ConcurrentLinkedQueue<BLECommand>();
    Handler commandHandler = new Handler();
    BLECommand commandInProgress;
    private Boolean queueIsBusy() {
        return commandInProgress != null;
    }

    private static final class Device {
        BluetoothDevice device;
        int rssi;

        Device(ScanResult result) {
            this.device = result.getDevice();
            this.rssi = result.getRssi();
        }

        public String getAddress() {
            return this.device.getAddress();
        }
    }

    private BluetoothGattCallback bluetoothGattCallback = new BluetoothGattCallback() {
        @Override
        public void onPhyUpdate(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
            super.onPhyUpdate(gatt, txPhy, rxPhy, status);
        }

        @Override
        public void onPhyRead(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
            super.onPhyRead(gatt, txPhy, rxPhy, status);
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

            BluetoothDevice device = gatt.getDevice();
            String address = device.getAddress();

            HashMap<String, Object> connection = (HashMap<String, Object>) connections.get(address);

            if (connection == null) {
                return;
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {

                switch (newState) {

                    case BluetoothProfile.STATE_CONNECTING: {
                        connection.put(keyConnectionState, BluetoothProfile.STATE_CONNECTING);
                        break;
                    }
                    case BluetoothProfile.STATE_CONNECTED: {
                        connection.put(keyConnectionState, BluetoothProfile.STATE_CONNECTED);

                        PluginCall call = (PluginCall) connection.get(keyOperationConnect);

                        if (call == null) {
                            break;
                        }

                        JSObject ret = new JSObject();
                        addProperty(ret, keyConnected, true);
                        call.resolve(ret);
                        connection.remove(keyOperationConnect);
                        break;
                    }
                    case BluetoothProfile.STATE_DISCONNECTING: {
                        connection.put(keyConnectionState, BluetoothProfile.STATE_DISCONNECTING);
                        break;
                    }
                    case BluetoothProfile.STATE_DISCONNECTED: {
                        connection.put(keyConnectionState, BluetoothProfile.STATE_DISCONNECTED);

                        PluginCall call = (PluginCall) connection.get(keyOperationDisconnect);

                        if (call != null) {
                            JSObject ret = new JSObject();
                            addProperty(ret, keyDisconnected, true);
                            call.resolve(ret);
                        }

                        connection.remove(keyOperationDisconnect);

                        // If disconnected, all stored calls must error
                        for (Object value : connection.values()) {
                            if (value instanceof PluginCall) {
                                PluginCall storedCall = (PluginCall) value;
                                storedCall.error(keyErrorValueDisconnected);
                            }
                        }

                        connections.remove(address);

                        JSObject data = new JSObject();
                        data.put(keyAddress, address);
                        notifyListeners(keyEventDeviceDisconnected, data);
                        break;
                    }
                }

            } else {


                // If disconnected, all stored calls must error
                for (Object value : connection.values()) {
                    if (value instanceof PluginCall) {
                        PluginCall storedCall = (PluginCall) value;
                        storedCall.error(keyErrorValueDisconnected);
                    }
                }


                if (connection.get(keyOperationConnect) != null) {

                    PluginCall call = (PluginCall) connection.get(keyOperationConnect);

                    call.error("Unable to connect to Peripheral");
                    connection.remove(keyOperationConnect);
                    return;

                } else if (connection.get(keyOperationDisconnect) != null) {

                    PluginCall call = (PluginCall) connection.get(keyOperationDisconnect);

                    call.error("Unable to disconnect from Peripheral");
                    connection.remove(keyOperationDisconnect);

                    return;

                } else if (connection.get(keyOperationDiscover) != null) {

                    PluginCall call = (PluginCall) connection.get(keyOperationDiscover);

                    call.error("Unable to discover services for Peripheral");
                    connection.remove(keyOperationDiscover);

                    return;
                } else {

                    Log.e(getLogTag(), "GATT operation unsuccessfull");
                    return;

                }

            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BLECommandType expectedType = BLECommandType.discoverServices;

            if (commandInProgress == null) {
                Log.e(getLogTag(), "Expected command in progress to be " + expectedType + ", but was null");
                return;
            }

            if (commandInProgress.type != expectedType) {
                Log.e(getLogTag(), "Expected command in progress to be " + expectedType + ", but got " + commandInProgress.type);
                return;
            }

            BluetoothDevice device = gatt.getDevice();
            String address = device.getAddress();

            HashMap<String, Object> connection = (HashMap<String, Object>) connections.get(address);

            if (connection == null) {
                Log.e(getLogTag(), "No connection");
                commandInProgress = null;
                return;
            }

            PluginCall call = commandInProgress.call;

            if (call == null) {
                Log.e(getLogTag(), "No saved call");
                commandInProgress = null;
                return;
            }

            JSObject ret = new JSObject();

            if (status == BluetoothGatt.GATT_SUCCESS) {
                connection.put(keyDiscovered, SERVICES_DISCOVERED);
                addProperty(ret, keyDiscoveryState, true);
                call.resolve(ret);
            } else {
                call.error("Service discovery unsuccessful");
            }

            commandInProgress = null;
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            BLECommandType expectedType = BLECommandType.read;

            if (commandInProgress == null) {
                Log.e(getLogTag(), "Expected command in progress to be " + expectedType + ", but was null");
                return;
            }

            if (commandInProgress.type != expectedType) {
                Log.e(getLogTag(), "Expected command in progress to be " + expectedType + ", but got " + commandInProgress.type);
                return;
            }

            BluetoothDevice device = gatt.getDevice();
            String address = device.getAddress();

            HashMap<String, Object> connection = (HashMap<String, Object>) connections.get(address);

            if (connection == null) {
                Log.e(getLogTag(), "No connection found");
                commandInProgress = null;
                return;
            }

            PluginCall call = commandInProgress.call;

            if (call == null) {
                Log.e(getLogTag(), "No callback for operation found");
                commandInProgress = null;
                return;
            }

            JSObject ret = new JSObject();

            if (status == BluetoothGatt.GATT_SUCCESS) {
                byte[] characteristicValue = characteristic.getValue();
                addProperty(ret, keyValue, jsByteArray(characteristicValue));
                call.resolve(ret);
            } else {
                call.error(keyErrorValueRead);
            }

            commandInProgress = null;
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            handleWrite(gatt, characteristic, status);
            completedCommand();
        }

        private void handleWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            BLECommandType expectedType = BLECommandType.write;

            if (commandInProgress == null) {
                Log.e(getLogTag(), "Expected command in progress to be " + expectedType + ", but was null");
                return;
            }

            if (commandInProgress.type != expectedType) {
                Log.e(getLogTag(), "Expected command in progress to be " + expectedType + ", but got " + commandInProgress.type);
                return;
            }

            BluetoothDevice device = gatt.getDevice();
            String address = device.getAddress();

            HashMap<String, Object> connection = (HashMap<String, Object>) connections.get(address);

            if (connection == null) {
                Log.e(getLogTag(), "No connection found");
                return;
            }

            PluginCall call = commandInProgress.call;

            if (call == null) {
                Log.e(getLogTag(), "No callback for operation found");
                return;
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                JSObject ret = new JSObject();
                byte[] value = characteristic.getValue();
                addProperty(ret, keyValue, jsByteArray(value));
                commandInProgress.call.resolve(ret);
            } else {
                call.error(keyErrorValueWrite);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {

            BluetoothDevice device = gatt.getDevice();
            String address = device.getAddress();

            UUID characteristicUuid = characteristic.getUuid();

            BluetoothGattService service = characteristic.getService();
            UUID serviceUuid = service.getUuid();

            byte[] characteristicValue = characteristic.getValue();

            if (characteristicUuid == null) {
                return;
            }

            JSObject ret = new JSObject();
            addProperty(ret, keyAddress, address);
            addProperty(ret, keyValue, jsByteArray(characteristicValue));

            notifyListeners(characteristicUuid.toString(), ret);
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {

            BluetoothDevice device = gatt.getDevice();
            String address = device.getAddress();

            HashMap<String, Object> connection = (HashMap<String, Object>) connections.get(address);

            if (connection == null) {
                return;
            }

            PluginCall call = (PluginCall) connection.get(keyOperationReadDescriptor);

            if (call == null) {
                return;
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {

                JSObject ret = new JSObject();

                byte[] value = descriptor.getValue();
                addProperty(ret, keyValue, jsByteArray(value));

                call.resolve(ret);
            } else {
                call.error(keyErrorValueRead);
            }

            connection.remove(keyOperationReadDescriptor);

        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {

            BluetoothDevice device = gatt.getDevice();
            String address = device.getAddress();

            HashMap<String, Object> connection = (HashMap<String, Object>) connections.get(address);

            if (connection == null) {
                return;
            }

            PluginCall call = (PluginCall) connection.get(keyOperationWriteDescriptor);

            if (call == null) {
                return;
            }

            byte[] value = descriptor.getValue();

            JSObject ret = new JSObject();

            if (status == BluetoothGatt.GATT_SUCCESS) {

                addProperty(ret, keyValue, jsByteArray(value));
                call.resolve(ret);

            } else {
                call.error(keyErrorValueWrite);
            }

            connection.remove(keyOperationWriteDescriptor);

        }

        @Override
        public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
            super.onReliableWriteCompleted(gatt, status);
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            super.onReadRemoteRssi(gatt, rssi, status);
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            super.onMtuChanged(gatt, mtu, status);
        }

    };

    private class BLEScanCallback extends ScanCallback {
        private Runnable timeoutCallback;
        private Integer timeout;
        private Boolean stopOnFirstResult;
        private Handler handler;

        public BLEScanCallback(Runnable timeoutCallback, Integer timeout, Boolean stopOnFirstResult) {
            this.timeoutCallback = timeoutCallback;
            this.timeout = timeout;
            this.stopOnFirstResult = stopOnFirstResult;
            this.handler = new Handler();
        }

        public void startScanTimeout() {
            this.handler.postDelayed(this.timeoutCallback, this.timeout);
        }

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);

            Device device = new Device(result);
            availableDevices.put(device.getAddress(), device);

            if (stopOnFirstResult && availableDevices.size() > 0) {
                this.handler.removeCallbacks(this.timeoutCallback);
                this.handler.post(this.timeoutCallback);
            }

            return;

        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(getLogTag(), "BLE scan failed with code " + errorCode);
            return;
        }
    }

    private class AnyUuid {
        final Integer intValue;
        final String stringValue;
        final Boolean isValid;

        AnyUuid(PluginCall call, String key) {
            this.intValue = call.getInt(key);
            this.stringValue = call.getString(key);
            this.isValid = !(this.intValue == null && this.stringValue == null);
        }
    }

    @Override
    protected void handleOnStart() {
        BluetoothManager bluetoothManager = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
    }

    @PluginMethod()
    public void isAvailable(PluginCall call) {

        JSObject ret = new JSObject();

        if (getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            ret.put(keyAvailable, true);
            call.resolve(ret);
        } else {
            ret.put(keyAvailable, false);
            call.resolve(ret);
        }
    }

    @PluginMethod()
    public void isEnabled(PluginCall call) {

        JSObject ret = new JSObject();

        if (bluetoothAdapter.isEnabled()) {
            ret.put(keyEnabled, true);
            call.resolve(ret);
        } else {
            ret.put(keyEnabled, false);
            call.resolve(ret);
        }
    }

    @PluginMethod()
    public void enable(PluginCall call) {

        if (!bluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(call, enableIntent, REQUEST_ENABLE_BT);
        }
    }

    @PluginMethod()
    public void scan(PluginCall call) {

        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        availableDevices = new HashMap<>();

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();


        List<UUID> uuids = getServiceUuids(call.getArray(keyServices));
        Integer timeout = call.getInt(keyTimeout, defaultScanTimeout);
        Boolean stopOnFirstResult = call.getBoolean(keyStopOnFirstResult, false);

        scanCallback = new BLEScanCallback(this::stopScan, timeout, stopOnFirstResult);

        List<ScanFilter> filters = new ArrayList<>();

        for (UUID uuid : uuids) {
            ScanFilter filter = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(uuid)).build();
            filters.add(filter);
        }

        bleScanner.startScan(filters, settings, scanCallback);
        scanCallback.startScanTimeout();

        saveCall(call);
    }

    @PluginMethod()
    public void connect(PluginCall call) {

        String address = call.getString(keyAddress);

        if (address == null) {
            call.reject(keyErrorAddressMissing);
            return;
        }

        HashMap<String, Object> connection = (HashMap<String, Object>) connections.get(address);

        if(connection != null){

            Integer connectionStateRaw = (Integer) connection.get(keyConnectionState);
            Integer servicesDiscoveredRaw = (Integer) connection.get(keyDiscovered);
            boolean isAlreadyConnected =  connectionStateRaw != null && connectionStateRaw == BluetoothProfile.STATE_CONNECTED;
            boolean servicesDiscovered =  servicesDiscoveredRaw != null && servicesDiscoveredRaw == SERVICES_DISCOVERED;

            if(isAlreadyConnected && servicesDiscovered ){
                JSObject ret = new JSObject();
                addProperty(ret, keyConnected, true);
                call.resolve(ret);
                return;
            }

            connections.remove(address);
        }

        BluetoothDevice bluetoothDevice = bluetoothAdapter.getRemoteDevice(address);

        if (bluetoothDevice == null) {
            call.reject("Device not found");
            return;
        }

        Boolean autoConnect = call.getBoolean(keyAutoConnect);
        autoConnect = autoConnect == null ? false : autoConnect;


        HashMap<String, Object> con = new HashMap<>();
        con.put(keyDiscovered, SERVICES_UNDISCOVERED);
        con.put(keyOperationConnect, call);

        BluetoothGatt gatt = bluetoothDevice.connectGatt(getContext(), autoConnect, bluetoothGattCallback);

        con.put(keyPeripheral, gatt);
        connections.put(address, con);

    }

    @PluginMethod()
    public void disconnect(PluginCall call) {

        String address = call.getString(keyAddress);

        if (address == null) {
            call.reject(keyErrorAddressMissing);
            return;
        }

        HashMap<String, Object> connection = (HashMap<String, Object>) connections.get(address);

        if (connection == null) {

            JSObject ret = new JSObject();
            addProperty(ret, keyDisconnected, true);
            call.resolve(ret);

            return;
        }

        connection.put(keyOperationDisconnect, call);

        BluetoothGatt gatt = (BluetoothGatt) connection.get(keyPeripheral);
        gatt.disconnect();

        return;

    }

    @PluginMethod()
    public void discover(PluginCall call) {

        String address = call.getString(keyAddress);

        if (address == null) {
            call.reject(keyErrorAddressMissing);
            return;
        }

        HashMap<String, Object> connection = (HashMap<String, Object>) connections.get(address);

        if (connection == null) {
            call.reject(keyErrorNotConnected);
            return;
        }

        BluetoothGatt gatt = (BluetoothGatt) connection.get(keyPeripheral);

        connection.put(keyDiscovered, SERVICES_DISCOVERING);
        BLECommand command = new BLECommand(call, BLECommandType.discoverServices, gatt, null, null);
        queue.add(command);
        processQueue();
    }

    @PluginMethod()
    public void enableNotifications(PluginCall call) {

        String address = call.getString(keyAddress);

        if (address == null) {
            call.reject(keyErrorAddressMissing);
            return;
        }

        HashMap<String, Object> connection = (HashMap<String, Object>) connections.get(address);

        if (connection == null) {
            call.reject(keyErrorNotConnected);
            return;
        }

        BluetoothGatt gatt = (BluetoothGatt) connection.get(keyPeripheral);

        AnyUuid propertyService = getUuid(call, keyService);

        if (!propertyService.isValid) {
            call.reject(keyErrorServiceMissing);
            return;
        }

        UUID serviceUuid = get128BitUUID(propertyService);

        BluetoothGattService service = gatt.getService(serviceUuid);

        if (service == null) {
            call.reject(keyErrorServiceNotFound);
            return;
        }

        AnyUuid propertyCharacteristic = getUuid(call, keyCharacteristic);

        if (!propertyCharacteristic.isValid) {
            call.reject(keyErrorCharacteristicMissing);
            return;
        }

        UUID charactristicUuid = get128BitUUID(propertyCharacteristic);

        BluetoothGattCharacteristic characteristic = service.getCharacteristic(charactristicUuid);

        if (characteristic == null) {
            call.reject(keyErrorCharacteristicNotFound);
            return;
        }

        UUID clientCharacteristicConfDescriptorUuid = get128BitUUID(clientCharacteristicConfigurationUuid);
        BluetoothGattDescriptor notificationDescriptor = characteristic.getDescriptor(clientCharacteristicConfDescriptorUuid);

        if (notificationDescriptor == null) {
            call.reject(keyErrorDescriptorNotFound);
            return;
        }

        boolean notificationSet = gatt.setCharacteristicNotification(characteristic, true);

        if (!notificationSet) {
            call.reject("Unable to set characteristic notification");
            return;
        }

        boolean result = false;


        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == BluetoothGattCharacteristic.PROPERTY_NOTIFY) {
            result = notificationDescriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        } else {
            result = notificationDescriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
        }

        if (!result) {
            call.reject(keyErrorValueSet);
            return;
        }

        connection.put(keyOperationWriteDescriptor, call);

        result = gatt.writeDescriptor(notificationDescriptor);

        if (!result) {
            connection.remove(keyOperationWriteDescriptor);
            call.reject(keyErrorValueWrite);
            return;
        }


    }

    @PluginMethod()
    public void disableNotifications(PluginCall call) {

        String address = call.getString(keyAddress);

        if (address == null) {
            call.reject(keyErrorAddressMissing);
            return;
        }

        HashMap<String, Object> connection = (HashMap<String, Object>) connections.get(address);

        if (connection == null) {
            call.reject(keyErrorNotConnected);
            return;
        }

        BluetoothGatt gatt = (BluetoothGatt) connection.get(keyPeripheral);

        AnyUuid propertyService = getUuid(call, keyService);

        if (!propertyService.isValid) {
            call.reject(keyErrorServiceMissing);
            return;
        }

        UUID serviceUuid = get128BitUUID(propertyService);

        BluetoothGattService service = gatt.getService(serviceUuid);

        if (service == null) {
            call.reject(keyErrorServiceNotFound);
            return;
        }

        AnyUuid propertyCharacteristic = getUuid(call, keyCharacteristic);

        if (!propertyCharacteristic.isValid) {
            call.reject(keyErrorCharacteristicMissing);
            return;
        }

        UUID characteristicUuid = get128BitUUID(propertyCharacteristic);

        BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUuid);

        if (characteristic == null) {
            call.reject(keyErrorCharacteristicNotFound);
            return;
        }

        UUID clientCharacteristicConfDescriptorUuid = get128BitUUID(clientCharacteristicConfigurationUuid);
        BluetoothGattDescriptor notificationDescriptor = characteristic.getDescriptor(clientCharacteristicConfDescriptorUuid);

        if (notificationDescriptor == null) {
            call.reject(keyErrorDescriptorNotFound);
            return;
        }

        boolean notificationUnset = gatt.setCharacteristicNotification(characteristic, false);

        if (!notificationUnset) {
            call.reject("Unable to unset characteristic notification");
            return;
        }

        boolean result = notificationDescriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);

        if (!result) {
            call.reject(keyErrorValueSet);
            return;
        }

        connection.put(keyOperationWriteDescriptor, call);

        result = gatt.writeDescriptor(notificationDescriptor);

        if (!result) {
            connection.remove(keyOperationWriteDescriptor);
            call.reject(keyErrorValueWrite);
            return;
        }
    }

    @PluginMethod()
    public void read(PluginCall call) {

        String address = call.getString(keyAddress);

        if (address == null) {
            call.reject(keyErrorAddressMissing);
            return;
        }

        HashMap<String, Object> connection = (HashMap<String, Object>) connections.get(address);

        if (connection == null) {
            call.reject(keyErrorNotConnected);
            return;
        }

        BluetoothGatt gatt = (BluetoothGatt) connection.get(keyPeripheral);

        AnyUuid propertyCharacteristic = getUuid(call, keyCharacteristic);

        if (!propertyCharacteristic.isValid) {
            call.reject(keyErrorCharacteristicMissing);
            return;
        }

        AnyUuid propertyService = getUuid(call, keyService);

        if (!propertyService.isValid) {
            call.reject(keyErrorServiceMissing);
            return;
        }


        UUID service128BitUuid = get128BitUUID(propertyService);
        BluetoothGattService service = gatt.getService(service128BitUuid);

        if (service == null) {
            call.reject(keyErrorServiceNotFound);
            return;
        }

        UUID characteristic128BitUuid = get128BitUUID(propertyCharacteristic);
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristic128BitUuid);

        if (characteristic == null) {
            call.reject(keyErrorCharacteristicNotFound);
            return;
        }

        BLECommand command = new BLECommand(call, BLECommandType.read, gatt, characteristic, null);
        queue.add(command);
        processQueue();
    }

    @PluginMethod()
    public void write(PluginCall call) {

        String address = call.getString(keyAddress);

        if (address == null) {
            call.reject(keyErrorAddressMissing);
            return;
        }

        HashMap<String, Object> connection = (HashMap<String, Object>) connections.get(address);

        if (connection == null) {
            call.reject(keyErrorNotConnected);
            return;
        }

        BluetoothGatt gatt = (BluetoothGatt) connection.get(keyPeripheral);

        AnyUuid propertyCharacteristic = getUuid(call, keyCharacteristic);

        if (!propertyCharacteristic.isValid) {
            call.reject(keyErrorCharacteristicMissing);
            return;
        }

        AnyUuid propertyService = getUuid(call, keyService);

        if (!propertyService.isValid) {
            call.reject(keyErrorServiceMissing);
            return;
        }

        UUID service128BitUuid = get128BitUUID(propertyService);
        BluetoothGattService service = gatt.getService(service128BitUuid);

        if (service == null) {
            call.reject(keyErrorServiceNotFound);
            return;
        }

        UUID characteristic128BitUuid = get128BitUUID(propertyCharacteristic);
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristic128BitUuid);

        if (characteristic == null) {
            call.reject(keyErrorCharacteristicNotFound);
            return;
        }

        String value = call.getString(keyValue);

        if (value == null) {
            call.reject(keyErrorValueMissing);
            return;
        }

        byte[] toWrite = toByteArray(value);

        if (toWrite == null) {
            call.reject("Unsufficient value given");
            return;
        }

        Boolean withoutResponse = call.getBoolean(keyPropertyWriteWithoutResponse, false);
        int writeType = withoutResponse ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        characteristic.setWriteType(writeType);

        BLECommand task = new BLECommand(call, BLECommandType.write, gatt, characteristic, toWrite);
        queue.add(task);
        processQueue();
    }

    @PluginMethod()
    public void readDescriptor(PluginCall call) {

        String address = call.getString(keyAddress);

        if (address == null) {
            call.reject(keyErrorAddressMissing);
            return;
        }

        HashMap<String, Object> connection = (HashMap<String, Object>) connections.get(address);

        if (connection == null) {
            call.reject(keyErrorNotConnected);
            return;
        }

        BluetoothGatt gatt = (BluetoothGatt) connection.get(keyPeripheral);

        AnyUuid propertyService = getUuid(call, keyService);

        if (!propertyService.isValid) {
            call.reject(keyErrorServiceMissing);
            return;
        }

        AnyUuid propertyCharacteristic = getUuid(call, keyCharacteristic);

        if (!propertyCharacteristic.isValid) {
            call.reject(keyErrorCharacteristicMissing);
            return;
        }

        AnyUuid propertyDescriptor = getUuid(call, keyDescriptor);

        if (!propertyDescriptor.isValid) {
            call.reject(keyErrorDescriptorMissing);
            return;
        }

        BluetoothGattService service = gatt.getService(get128BitUUID(propertyService));

        if (service == null) {
            call.reject(keyErrorServiceNotFound);
            return;
        }

        BluetoothGattCharacteristic characteristic = service.getCharacteristic(get128BitUUID(propertyCharacteristic));

        if (characteristic == null) {
            call.reject(keyErrorCharacteristicNotFound);
            return;
        }

        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(get128BitUUID(propertyDescriptor));

        if (descriptor == null) {
            call.reject(keyErrorDescriptorNotFound);
            return;
        }

        connection.put(keyOperationReadDescriptor, call);

        boolean success = gatt.readDescriptor(descriptor);

        if (!success) {
            connection.remove(keyOperationReadDescriptor);
            call.reject(keyErrorValueRead);
            return;
        }


    }

    @PluginMethod()
    public void getServices(PluginCall call) {

        String address = call.getString(keyAddress);

        if (address == null) {
            call.reject(keyErrorAddressMissing);
            return;
        }

        HashMap<String, Object> connection = (HashMap<String, Object>) connections.get(address);

        if (connection == null) {
            call.reject(keyErrorNotConnected);
            return;
        }

        BluetoothGatt gatt = (BluetoothGatt) connection.get(keyPeripheral);

        List<BluetoothGattService> services = gatt.getServices();
        ArrayList<JSObject> retServices = new ArrayList<>();

        for (BluetoothGattService service : services) {
            retServices.add(createJSBluetoothGattService(service));
        }

        JSObject ret = new JSObject();
        addProperty(ret, keyServices, JSArray.from(retServices.toArray()));

        call.resolve(ret);

    }

    @PluginMethod()
    public void getService(PluginCall call) {

        String address = call.getString(keyAddress);

        if (address == null) {
            call.reject(keyErrorAddressMissing);
            return;
        }

        HashMap<String, Object> connection = (HashMap<String, Object>) connections.get(address);

        if (connection == null) {
            call.reject(keyErrorNotConnected);
            return;
        }

        BluetoothGatt peripheral = (BluetoothGatt) connection.get(keyPeripheral);

        AnyUuid propertyService = getUuid(call, keyService);

        if (!propertyService.isValid) {
            call.reject(keyErrorServiceMissing);
            return;
        }

        BluetoothGattService service = peripheral.getService(get128BitUUID(propertyService));

        if (service == null) {
            call.reject(keyErrorServiceNotFound);
            return;
        }

        call.resolve(createJSBluetoothGattService(service));
    }

    @PluginMethod()
    public void getCharacteristics(PluginCall call) {

        String address = call.getString(keyAddress);

        if (address == null) {
            call.reject(keyErrorAddressMissing);
            return;
        }

        HashMap<String, Object> connection = (HashMap<String, Object>) connections.get(address);

        if (connection == null) {
            call.reject(keyErrorNotConnected);
            return;
        }

        BluetoothGatt gatt = (BluetoothGatt) connection.get(keyPeripheral);

        AnyUuid propertyService = getUuid(call, keyService);

        if (!propertyService.isValid) {
            call.reject(keyErrorServiceMissing);
            return;
        }

        BluetoothGattService service = gatt.getService(get128BitUUID(propertyService));

        if (service == null) {
            call.reject(keyErrorServiceNotFound);
            return;
        }

        List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();

        ArrayList<JSObject> retCharacteristics = new ArrayList<>();

        for (BluetoothGattCharacteristic characteristic : characteristics) {
            retCharacteristics.add(createJSBluetoothGattCharacteristic(characteristic));
        }

        JSObject ret = new JSObject();
        addProperty(ret, keyCharacteristics, JSArray.from(retCharacteristics.toArray()));

        call.resolve(ret);
    }

    @PluginMethod()
    public void getCharacteristic(PluginCall call) {

        String address = call.getString(keyAddress);

        if (address == null) {
            call.reject(keyErrorAddressMissing);
            return;
        }

        HashMap<String, Object> connection = (HashMap<String, Object>) connections.get(address);

        if (connection == null) {
            call.reject(keyErrorNotConnected);
            return;
        }

        BluetoothGatt gatt = (BluetoothGatt) connection.get(keyPeripheral);

        AnyUuid propertyService = getUuid(call, keyService);

        if (!propertyService.isValid) {
            call.reject(keyErrorServiceMissing);
            return;
        }

        BluetoothGattService service = gatt.getService(get128BitUUID(propertyService));

        if (service == null) {
            call.reject(keyErrorServiceNotFound);
            return;
        }

        AnyUuid propertyCharacteristic = getUuid(call, keyCharacteristic);

        if (!propertyCharacteristic.isValid) {
            call.reject(keyErrorCharacteristicMissing);
            return;
        }

        BluetoothGattCharacteristic characteristic = service.getCharacteristic(get128BitUUID(propertyCharacteristic));

        if (characteristic == null) {
            call.reject(keyErrorCharacteristicNotFound);
            return;
        }

        JSObject retCharacteristic = createJSBluetoothGattCharacteristic(characteristic);

        call.resolve(retCharacteristic);

    }

    private void processQueue() {
        if (queueIsBusy()) {
            return;
        }

        commandInProgress = queue.poll();

        if (commandInProgress == null) {
            return;
        }

        switch (commandInProgress.type) {
            case write:
                commandHandler.post(runnableWriteCommand(commandInProgress));
                break;
            case read:
                commandHandler.post(runnableReadCommand(commandInProgress));
                break;
            case discoverServices:
                commandHandler.post(runnableDiscoverServicesCommand(commandInProgress));
                break;
            default:
                Log.e(getLogTag(), "Unknown command type " + commandInProgress.type);
        }
    }

    private Runnable runnableDiscoverServicesCommand(BLECommand command) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    boolean discoveryStarted = command.gatt.discoverServices();

                    if (!discoveryStarted) {
                        command.call.reject("Failed to start service discovery");
                    }

                } catch (Exception e) {
                    Log.d(getLogTag(), e.getMessage());
                    command.call.error("Failed to start service discovery");
                }
            }
        };
    }

    private Runnable runnableReadCommand(BLECommand command) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    boolean success = command.gatt.readCharacteristic(command.characteristic);

                    if (!success) {
                        command.call.error(keyErrorValueRead);
                    }
                } catch (Exception e) {
                    Log.d(getLogTag(), e.getMessage());
                    command.call.error(keyErrorValueRead);
                }
            }
        };
    }

    // TODO: Handle write with response
    private Runnable runnableWriteCommand(BLECommand command) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    Log.d(getLogTag(), "writing data " + command.data);
                    boolean valueSet = command.characteristic.setValue(command.data);

                    if (!valueSet) {
                        command.call.reject(keyErrorValueSet);
                        completedCommand();
                        return;
                    }

                    boolean success = command.gatt.writeCharacteristic(command.characteristic);

                    if (!success) {
                        command.call.error(keyErrorValueWrite);
                        completedCommand();
                        return;
                    }

                    // call resolved in onCharacteristicWrite()
                } catch (Exception e) {
                    Log.e(getLogTag(), e.getMessage());
                    command.call.error(keyErrorValueWrite);
                    completedCommand();
                }
            }
        };
    }

    private void completedCommand() {
        commandInProgress = null;
        processQueue();
    }


    private void stopScan() {

        if (bleScanner == null) {
            bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        }

        bleScanner.flushPendingScanResults(scanCallback);
        bleScanner.stopScan(scanCallback);

        JSObject ret = new JSObject();
        ret.put(keyAvailableDevices, getScanResult());

        PluginCall savedCall = getSavedCall();
        savedCall.resolve(ret);
        savedCall.release(getBridge());
        return;

    }

    private JSObject createBLEDeviceResult(Device scanRecord) {

        JSObject ret = new JSObject();

        BluetoothDevice device = scanRecord.device;
        int rssi = scanRecord.rssi;

        addProperty(ret, keyDeviceName, device.getName());
        addProperty(ret, keyAddress, device.getAddress());
        addProperty(ret, keyBondState, device.getBondState());
        addProperty(ret, keyDeviceType, device.getType());
        addProperty(ret, keyRssi, rssi);

        return ret;
    }

    private JSObject createJSBluetoothGattService(BluetoothGattService service) {
        JSObject retService = new JSObject();

        addProperty(retService, keyUuid, service.getUuid().toString());

        if (service.getType() == BluetoothGattService.SERVICE_TYPE_PRIMARY) {
            addProperty(retService, keyIsPrimaryService, true);
        } else {
            addProperty(retService, keyIsPrimaryService, false);
        }


        ArrayList<String> included = new ArrayList<>();
        List<BluetoothGattService> subServices = service.getIncludedServices();

        for (BluetoothGattService incService : subServices) {
            included.add(incService.getUuid().toString());
        }

        retService.put(keyIncludedServices, JSArray.from(included.toArray()));

        ArrayList<String> retCharacteristics = new ArrayList<>();
        List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();

        for (BluetoothGattCharacteristic characteristic : characteristics) {
            retCharacteristics.add(characteristic.getUuid().toString());
        }

        retService.put(keyCharacteristics, JSArray.from(retCharacteristics.toArray()));

        return retService;
    }

    private JSObject createJSBluetoothGattCharacteristic(BluetoothGattCharacteristic characteristic) {

        JSObject retCharacteristic = new JSObject();

        addProperty(retCharacteristic, keyUuid, characteristic.getUuid().toString());
        addProperty(retCharacteristic, keyCharacteristicProperies, getCharacteristicProperties(characteristic));

        List<BluetoothGattDescriptor> descriptors = characteristic.getDescriptors();
        ArrayList<String> descriptorUuids = new ArrayList<>();

        for (BluetoothGattDescriptor descriptor : descriptors) {
            descriptorUuids.add(descriptor.getUuid().toString());
        }

        addProperty(retCharacteristic, keyCharacterisicDescripors, JSArray.from(descriptorUuids.toArray()));

        return retCharacteristic;

    }

    private JSObject getCharacteristicProperties(BluetoothGattCharacteristic characteristic) {

        JSObject properties = new JSObject();

        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE) != 0) {
            addProperty(properties, keyPropertyAuthenticatedSignedWrites, true);
        } else {
            addProperty(properties, keyPropertyAuthenticatedSignedWrites, false);
        }

        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_BROADCAST) != 0) {
            addProperty(properties, keyPropertyBroadcast, true);
        } else {
            addProperty(properties, keyPropertyBroadcast, false);
        }

        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
            addProperty(properties, keyPropertyIndicate, true);
        } else {
            addProperty(properties, keyPropertyIndicate, false);
        }

        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            addProperty(properties, keyPropertyNotify, true);
        } else {
            addProperty(properties, keyPropertyNotify, false);
        }

        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
            addProperty(properties, keyPropertyRead, true);
        } else {
            addProperty(properties, keyPropertyRead, false);
        }

        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            addProperty(properties, keyPropertyWrite, true);
        } else {
            addProperty(properties, keyPropertyWrite, false);
        }

        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            addProperty(properties, keyPropertyWriteWithoutResponse, true);
        } else {
            addProperty(properties, keyPropertyWriteWithoutResponse, false);
        }

        return properties;

    }

    private JSArray getScanResult() {

        ArrayList<JSObject> scanResults = new ArrayList<>();

        for (Map.Entry<String, Device> entry : availableDevices.entrySet()) {

            Device record = entry.getValue();
            scanResults.add(createBLEDeviceResult(record));
        }

        return JSArray.from(scanResults.toArray());

    }

    @Override
    protected void handleOnActivityResult(int requestCode, int resultCode, Intent data) {
        super.handleOnActivityResult(requestCode, resultCode, data);

        Log.i(getLogTag(), "Handler called with " + resultCode);

        if (requestCode == REQUEST_ENABLE_BT) {
            PluginCall call = getSavedCall();

            if (call == null) {
                return;
            }

            JSObject ret = new JSObject();
            addProperty(ret, keyEnabled, resultCode == 0 ? false : true);
            call.resolve(ret);
            call.release(getBridge());
        }

    }

    private JSArray jsByteArray(byte[] bytes) {
        int[] ints = new int[bytes.length];

        for (int i=0; i<bytes.length; i++) {
            ints[i] = bytes[i] & 0xff;
        }

        return JSArray.from(ints);
    }

    private AnyUuid getUuid(PluginCall call, String key) {
        return new AnyUuid(call, key);
    }

    private List<UUID> getServiceUuids(JSArray serviceUuidArray) {


        ArrayList<UUID> emptyList = new ArrayList<>();

        if (serviceUuidArray == null) {
            return emptyList;
        }

        try {
            return getServiceUuidsFromIntegers(serviceUuidArray);
        } catch (Exception e) {
            // fallthrough
        }

        try {
            return getServiceUuidsFromStrings(serviceUuidArray);
        } catch (JSONException ee) {
            Log.e(getLogTag(), "Error while converting JSArray to List");
            return emptyList;
        } catch (IllegalArgumentException eee) {
            Log.e(getLogTag(), "Invalid uuid string");
            return emptyList;
        }
    }

    private List<UUID> getServiceUuidsFromStrings(JSArray serviceUuidArray) throws JSONException {
        List<UUID> serviceUuids = new ArrayList<>();
        List<String> uuidList = serviceUuidArray.toList();

        if (!(uuidList.size() > 0)) {
            Log.i(getLogTag(), "No uuids given");
            return serviceUuids;
        }

        for (String uuid : uuidList) {

            UUID uuid128 = get128BitUUID(uuid);

            if (uuid128 != null) {
                serviceUuids.add(uuid128);
            }
        }

        return serviceUuids;
    }

    private List<UUID> getServiceUuidsFromIntegers(JSArray serviceUuidArray) throws JSONException {
        List<UUID> serviceUuids = new ArrayList<>();
        List<Integer> uuidList = serviceUuidArray.toList();

        if (!(uuidList.size() > 0)) {
            Log.i(getLogTag(), "No uuids given");
            return serviceUuids;
        }

        for (Integer uuid : uuidList) {

            UUID uuid128 = get128BitUUID(uuid);

            if (uuid128 != null) {
                serviceUuids.add(uuid128);
            }
        }

        return serviceUuids;
    }

    private byte[] toByteArray(String base64Value) {
        if (base64Value == null) {
            return null;
        }

        byte[] bytes = Base64.decode(base64Value, Base64.NO_WRAP);

        if (bytes == null || bytes.length == 0) {
            return null;
        }

        return bytes;
    }

    private byte[] toByteArray(JSArray arrayValue) {
        if (arrayValue == null) {
            return null;
        }

        byte[] bytes = new byte[arrayValue.length()];

        for (int i=0; i<bytes.length; i++) {
            try {
                bytes[i] = (byte) arrayValue.get(i);
            } catch (JSONException e) {
                bytes[i] = 0;
            }
        }

        return bytes;
    }

    private UUID get128BitUUID(Integer uuid) {

        if (uuid == null) {
            return null;
        }

        String hexString = Integer.toHexString(uuid);

        if (hexString.length() != 4) {
            return null;
        }

        String uuidString = BASE_UUID_HEAD + hexString + BASE_UUID_TAIL;
        return UUID.fromString(uuidString);


    }

    private UUID get128BitUUID(String uuid) {
        return UUID.fromString(uuid);
    }

    private UUID get128BitUUID(AnyUuid uuid) {
        if (!uuid.isValid) {
            return null;
        }

        if (uuid.intValue != null) {
            return get128BitUUID(uuid.intValue);
        }

        return get128BitUUID(uuid.stringValue);
    }

    private int get16BitUUID(UUID uuid) {
        String uuidString = uuid.toString();
        int hexUuid = Integer.parseInt(uuidString.substring(4, 8), 16);
        return hexUuid;
    }

    private void addProperty(JSObject obj, String key, Object value) {

        if (value == null) {
            obj.put(key, JSObject.NULL);
            return;
        }

        obj.put(key, value);

    }
}
