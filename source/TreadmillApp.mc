using Toybox.Application;
using Toybox.BluetoothLowEnergy as Ble;
using Toybox.Lang;
using Toybox.WatchUi;

class TreadmillApp extends Application.AppBase {

    var client as FtmsClient or Null = null;
    var recorder as Recorder or Null = null;

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

        client = new FtmsClient(filter);
        recorder = new Recorder();

        Ble.setDelegate(client);
        client.registerProfile();
        client.startScan();
    }

    function onStop(state as Lang.Dictionary or Null) as Void {
        // Never lose a run to an accidental exit: an open session is saved.
        if (recorder != null && recorder.hasSession()) {
            recorder.save();
        }

        if (client != null) {
            client.stopScan();
        }
    }

    function getInitialView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates] {
        var view = new MainView(client, recorder);

        return [view, new MainDelegate(view, client, recorder)];
    }
}

function getApp() as TreadmillApp {
    return Application.getApp() as TreadmillApp;
}
