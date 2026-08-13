import Toybox.Communications;
import Toybox.Lang;
import Toybox.WatchUi;

//! Reports the outcome of a Communications.transmit back to the view so it can
//! flash the sent/failed indicator.
class CommandListener extends Communications.ConnectionListener {

    private var _view as MusicControlView;

    public function initialize(view as MusicControlView) {
        Communications.ConnectionListener.initialize();

        _view = view;
    }

    public function onComplete() as Void {
        _view.onSendResult(true);
    }

    public function onError() as Void {
        _view.onSendResult(false);
    }

}

class MusicControlDelegate extends WatchUi.BehaviorDelegate {

    private var _view as MusicControlView;

    public function initialize(view as MusicControlView) {
        BehaviorDelegate.initialize();

        _view = view;
    }

    public function onTap(evt as ClickEvent) as Boolean {
        var coordinates = evt.getCoordinates();

        return _view.handleTap(coordinates[0], coordinates[1]);
    }

    //! Physical-key fallback for gloved or wet-screen use. Everything other than
    //! the enter key falls through so back still exits the app.
    public function onKey(evt as KeyEvent) as Boolean {
        if (evt.getKey() == WatchUi.KEY_ENTER) {
            _view.pressButton($.BTN_PLAYPAUSE);

            return true;
        }

        return false;
    }

}
