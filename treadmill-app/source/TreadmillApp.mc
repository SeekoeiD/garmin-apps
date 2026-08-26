using Toybox.Application;
using Toybox.BluetoothLowEnergy as Ble;
using Toybox.Communications;
using Toybox.Lang;
using Toybox.WatchUi;

class TreadmillApp extends Application.AppBase {

    var client as FtmsClient or Null = null;
    var recorder as Recorder or Null = null;
    var uploader as Uploader or Null = null;

    // Held on the instance because the mailbox registration outlives the call
    // that made it.
    private var _phoneMethod as PhoneCallback or Null = null;

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state as Lang.Dictionary or Null) as Void {
        var filter = null;

        try {
            filter = Application.Properties.getValue("deviceName");
        } catch (e) {
            filter = null;
        }

        uploader = new Uploader();

        // One mailbox for the whole app: the companion answers a finished run
        // with a tl_result, and only the uploader knows what to make of it.
        if (Communications has :registerForPhoneAppMessages) {
            _phoneMethod = method(:onPhoneAppMessage);
            Communications.registerForPhoneAppMessages(_phoneMethod);
        }

        client = new FtmsClient(filter);
        recorder = new Recorder(uploader, recordMode());

        Ble.setDelegate(client);
        client.registerProfile();
        client.startScan();
    }

    function onStop(state as Lang.Dictionary or Null) as Void {
        // Never lose a run to an accidental exit: an open session is saved.
        if (recorder != null && recorder.hasSession()) {
            recorder.save();
        }

        // The transmit cannot complete once the app is gone, so park anything
        // still outstanding in storage for a later retry.
        if (uploader != null) {
            uploader.persistIfUnfinished();
        }

        if (client != null) {
            client.stopScan();
        }
    }

    //! Companion messages. Public because method(:onPhoneAppMessage) cannot
    //! reach a private symbol.
    function onPhoneAppMessage(msg as Communications.PhoneAppMessage) as Void {
        if (uploader == null) {
            return;
        }

        uploader.onPhoneMessage(msg);
        WatchUi.requestUpdate();
    }

    private function recordMode() as Lang.Number {
        var v = null;

        try {
            v = Application.Properties.getValue("recordMode");
        } catch (e) {
            v = null;
        }

        if (v instanceof Lang.Number && v == Recorder.MODE_LEGACY) {
            return Recorder.MODE_LEGACY;
        }

        return Recorder.MODE_CLOUD;
    }

    function getInitialView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates] {
        var view = new MainView(client, recorder);

        return [view, new MainDelegate(view, client, recorder)];
    }
}

function getApp() as TreadmillApp {
    return Application.getApp() as TreadmillApp;
}
