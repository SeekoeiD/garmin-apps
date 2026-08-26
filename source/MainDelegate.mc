using Toybox.Attention;
using Toybox.Lang;
using Toybox.System;
using Toybox.WatchUi;

//! START toggles the timer, BACK opens save/discard, UP/DOWN flips between the
//! run screen and the diagnostics page.
class MainDelegate extends WatchUi.BehaviorDelegate {

    private var _view as MainView;
    private var _client as FtmsClient;
    private var _recorder as Recorder;

    function initialize(view as MainView, client as FtmsClient, recorder as Recorder) {
        BehaviorDelegate.initialize();
        _view = view;
        _client = client;
        _recorder = recorder;
    }

    function onSelect() as Lang.Boolean {
        if (_recorder.isRecording()) {
            _recorder.stop();
        } else {
            _recorder.start();
        }

        buzz();
        WatchUi.requestUpdate();

        return true;
    }

    function onNextPage() as Lang.Boolean {
        return flipPage();
    }

    function onPreviousPage() as Lang.Boolean {
        return flipPage();
    }

    //! Lap on long-press of the menu key, matching the native running app.
    function onMenu() as Lang.Boolean {
        if (_recorder.hasSession()) {
            _recorder.addLap();
            buzz();
            WatchUi.requestUpdate();
        } else {
            _client.startScan();
        }

        return true;
    }

    function onBack() as Lang.Boolean {
        var uploader = _recorder.uploader;
        var pending = _recorder.isCloud() && uploader != null && uploader.hasPending();

        // An upload still in flight must not be abandoned by a reflexive back
        // press, so it opens the menu instead of exiting. Exiting from there
        // is still safe: onStop parks the run in storage.
        var busy = _recorder.isCloud() && uploader != null && !uploader.isSettled();

        if (!_recorder.hasSession() && !pending && !busy) {
            _client.disconnect();

            return false;
        }

        WatchUi.pushView(
            buildMenu(pending),
            new EndMenuDelegate(_client, _recorder),
            WatchUi.SLIDE_UP
        );

        return true;
    }

    private function flipPage() as Lang.Boolean {
        _view.nextPage();

        return true;
    }

    private function buildMenu(pending as Lang.Boolean) as WatchUi.Menu2 {
        var menu = new WatchUi.Menu2({:title => "Treadmill"});

        if (_recorder.hasSession()) {
            menu.addItem(new WatchUi.MenuItem("Save", "finish and sync", :save, {}));
            menu.addItem(new WatchUi.MenuItem("Resume", null, :resume, {}));
            menu.addItem(new WatchUi.MenuItem("Discard", "delete this run", :discard, {}));
        }

        if (pending) {
            menu.addItem(new WatchUi.MenuItem("Retry upload", "resend stored run", :retry, {}));
        }

        if (!_recorder.hasSession()) {
            menu.addItem(new WatchUi.MenuItem("Exit", null, :exit, {}));
        }

        return menu;
    }

    private function buzz() as Void {
        if (Attention has :vibrate) {
            Attention.vibrate([new Attention.VibeProfile(50, 150)]);
        }
    }
}

class EndMenuDelegate extends WatchUi.Menu2InputDelegate {

    private var _client as FtmsClient;
    private var _recorder as Recorder;

    function initialize(client as FtmsClient, recorder as Recorder) {
        Menu2InputDelegate.initialize();
        _client = client;
        _recorder = recorder;
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        var id = item.getId();

        if (id == :save) {
            _recorder.save();
            _client.disconnect();
            WatchUi.popView(WatchUi.SLIDE_DOWN);

            // In cloud mode the run is not safe until the finish callback
            // lands, so the main view keeps showing upload progress and the
            // user exits with back once it reads UPLOADED.
            if (!_recorder.isCloud()) {
                System.exit();
            }
        } else if (id == :discard) {
            _recorder.discard();
            _client.disconnect();
            WatchUi.popView(WatchUi.SLIDE_DOWN);
            System.exit();
        } else if (id == :retry) {
            var uploader = _recorder.uploader;

            if (uploader != null) {
                uploader.retry();
            }

            WatchUi.popView(WatchUi.SLIDE_DOWN);
        } else if (id == :exit) {
            _client.disconnect();
            WatchUi.popView(WatchUi.SLIDE_DOWN);
            System.exit();
        } else {
            WatchUi.popView(WatchUi.SLIDE_DOWN);
        }
    }

    function onBack() as Void {
        WatchUi.popView(WatchUi.SLIDE_DOWN);
    }
}
