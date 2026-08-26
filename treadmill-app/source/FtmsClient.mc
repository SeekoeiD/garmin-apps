using Toybox.BluetoothLowEnergy as Ble;
using Toybox.Lang;
using Toybox.System;

//! Owns the BLE link to the treadmill: profile registration, scanning,
//! pairing, CCCD subscription and decoding of Treadmill Data notifications.
//!
//! Latest decoded values are held on the instance so the view can read them
//! synchronously; nothing here touches the FIT recording.
class FtmsClient extends Ble.BleDelegate {

    enum {
        STATE_IDLE,
        STATE_SCANNING,
        STATE_CONNECTING,
        STATE_SUBSCRIBING,
        STATE_LIVE,
        STATE_ERROR
    }

    var state as Lang.Number = STATE_IDLE;
    var statusDetail as Lang.String = "";

    // Latest values from the treadmill. null until the first packet arrives.
    var speedMps as Lang.Float or Null = null;
    var inclinePct as Lang.Float or Null = null;
    var machineDistanceM as Lang.Number or Null = null;
    var machineElevGainM as Lang.Float or Null = null;
    var machineHr as Lang.Number or Null = null;
    var lastPacketMs as Lang.Number = 0;
    var packetCount as Lang.Number = 0;

    // Debug page material.
    var lastRaw as Lang.ByteArray or Null = null;
    var lastFlags as Lang.Number = 0;
    var seenNames as Lang.Array<Lang.String> = [];

    private var _device as Ble.Device or Null = null;
    private var _serviceUuid as Ble.Uuid or Null = null;
    private var _dataUuid as Ble.Uuid or Null = null;
    private var _nameFilter as Lang.String or Null = null;

    function initialize(nameFilter as Lang.String or Null) {
        BleDelegate.initialize();

        if (nameFilter != null && nameFilter.length() > 0) {
            _nameFilter = nameFilter.toUpper();
        }

        _serviceUuid = Ble.stringToUuid(Ftms.SERVICE_UUID);
        _dataUuid = Ble.stringToUuid(Ftms.TREADMILL_DATA);
    }

    //! Register the FTMS profile. Must be called once, before scanning, or
    //! getService()/getCharacteristic() return null after connecting.
    function registerProfile() as Void {
        var profile = {
            :uuid => _serviceUuid,
            :characteristics => [
                {
                    :uuid => _dataUuid,
                    :descriptors => [Ble.cccdUuid()]
                },
                {
                    :uuid => Ble.stringToUuid(Ftms.MACHINE_FEATURE)
                }
            ]
        };

        try {
            Ble.registerProfile(profile);
        } catch (e) {
            state = STATE_ERROR;
            statusDetail = "profile reg failed";
        }
    }

    function startScan() as Void {
        if (state == STATE_LIVE || state == STATE_CONNECTING) {
            return;
        }

        seenNames = [];
        state = STATE_SCANNING;
        statusDetail = "";

        try {
            Ble.setScanState(Ble.SCAN_STATE_SCANNING);
        } catch (e) {
            state = STATE_ERROR;
            statusDetail = "scan failed";
        }
    }

    function stopScan() as Void {
        try {
            Ble.setScanState(Ble.SCAN_STATE_OFF);
        } catch (e) {
            // Radio already off or unavailable; nothing useful to do.
        }
    }

    function disconnect() as Void {
        stopScan();

        if (_device != null) {
            try {
                Ble.unpairDevice(_device);
            } catch (e) {
                // Device may already be gone.
            }

            _device = null;
        }

        state = STATE_IDLE;
    }

    //! True when a packet arrived recently enough to trust the values.
    function isFresh() as Lang.Boolean {
        if (lastPacketMs == 0) {
            return false;
        }

        return (System.getTimer() - lastPacketMs) < 5000;
    }

    function isIdle() as Lang.Boolean {
        return state == STATE_IDLE;
    }

    function isLive() as Lang.Boolean {
        return state == STATE_LIVE && isFresh();
    }

    //! Somewhere between idle and streaming: scanning, pairing or subscribing.
    function isBusy() as Lang.Boolean {
        return state == STATE_SCANNING
            || state == STATE_CONNECTING
            || state == STATE_SUBSCRIBING;
    }

    function onProfileRegister(uuid as Ble.Uuid, status as Ble.Status) as Void {
        if (status != Ble.STATUS_SUCCESS) {
            state = STATE_ERROR;
            statusDetail = "profile " + status.toString();
        }
    }

    function onScanResults(scanResults as Ble.Iterator) as Void {
        for (var raw = scanResults.next(); raw != null; raw = scanResults.next()) {
            var r = raw as Ble.ScanResult;
            var name = r.getDeviceName();

            if (name != null && name.length() > 0 && seenNames.indexOf(name) < 0) {
                seenNames.add(name);
            }

            if (matches(r, name)) {
                connectTo(r);
                return;
            }
        }
    }

    //! A device qualifies if it advertises the FTMS service UUID, or if its
    //! name matches the configured filter, or - with no filter set - if the
    //! name looks like this treadmill.
    private function matches(r as Ble.ScanResult, name as Lang.String or Null) as Lang.Boolean {
        var uuids = r.getServiceUuids();

        for (var u = uuids.next(); u != null; u = uuids.next()) {
            if (u.equals(_serviceUuid)) {
                return true;
            }
        }

        if (name == null) {
            return false;
        }

        var upper = name.toUpper();

        if (_nameFilter != null) {
            return upper.find(_nameFilter) != null;
        }

        return upper.find("FR30") != null
            || upper.find("REEBOK") != null
            || upper.find("TREADMILL") != null;
    }

    private function connectTo(r as Ble.ScanResult) as Void {
        state = STATE_CONNECTING;
        stopScan();

        try {
            _device = Ble.pairDevice(r);
        } catch (e) {
            state = STATE_ERROR;
            statusDetail = "pair failed";
        }
    }

    function onConnectedStateChanged(device as Ble.Device, connectionState as Ble.ConnectionState) as Void {
        if (connectionState == Ble.CONNECTION_STATE_CONNECTED) {
            _device = device;
            subscribe();
            return;
        }

        // Dropped or rejected: fall back to scanning so a treadmill that
        // sleeps between intervals reconnects on its own.
        _device = null;
        speedMps = null;
        inclinePct = null;
        startScan();
    }

    private function subscribe() as Void {
        state = STATE_SUBSCRIBING;

        var service = _device.getService(_serviceUuid);

        if (service == null) {
            state = STATE_ERROR;
            statusDetail = "no FTMS service";
            return;
        }

        var characteristic = service.getCharacteristic(_dataUuid);

        if (characteristic == null) {
            state = STATE_ERROR;
            statusDetail = "no 2ACD char";
            return;
        }

        var cccd = characteristic.getDescriptor(Ble.cccdUuid());

        if (cccd == null) {
            state = STATE_ERROR;
            statusDetail = "no CCCD";
            return;
        }

        try {
            cccd.requestWrite([0x01, 0x00]b);
        } catch (e) {
            state = STATE_ERROR;
            statusDetail = "cccd write failed";
        }
    }

    function onDescriptorWrite(descriptor as Ble.Descriptor, status as Ble.Status) as Void {
        if (status == Ble.STATUS_SUCCESS) {
            state = STATE_LIVE;
            statusDetail = "";
        } else {
            state = STATE_ERROR;
            statusDetail = "notify " + status.toString();
        }
    }

    function onCharacteristicChanged(characteristic as Ble.Characteristic, value as Lang.ByteArray) as Void {
        lastRaw = value;
        packetCount += 1;
        lastPacketMs = System.getTimer();
        state = STATE_LIVE;

        var d = Ftms.parseTreadmillData(value);

        if (d.hasKey(:flags)) {
            lastFlags = d[:flags];
        }

        if (d.hasKey(:speedMps)) {
            speedMps = d[:speedMps];
        }

        if (d.hasKey(:inclinePct)) {
            inclinePct = d[:inclinePct];
        }

        if (d.hasKey(:distanceM)) {
            machineDistanceM = d[:distanceM];
        }

        if (d.hasKey(:elevGainM)) {
            machineElevGainM = d[:elevGainM];
        }

        if (d.hasKey(:heartRate)) {
            machineHr = d[:heartRate];
        }
    }

    function stateLabel() as Lang.String {
        if (state == STATE_IDLE) { return "IDLE"; }

        if (state == STATE_SCANNING) { return "SEARCHING"; }

        if (state == STATE_CONNECTING) { return "CONNECTING"; }

        if (state == STATE_SUBSCRIBING) { return "SUBSCRIBING"; }

        if (state == STATE_LIVE) { return isFresh() ? "CONNECTED" : "STALLED"; }

        return "ERROR";
    }
}
