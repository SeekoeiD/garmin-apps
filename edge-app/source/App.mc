import Toybox.Application;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

//! Full-screen music remote for the Edge 830.
//! Commands go out over the Connect IQ mobile channel; the companion Android
//! app answers with playback status which is cached here for the view.
class MusicControlApp extends Application.AppBase {

    private var _phoneMethod as Method(msg as PhoneAppMessage) as Void;

    private var _playing as Boolean or Null;
    private var _track as String or Null;
    private var _artist as String or Null;
    private var _volume as Number or Null;

    public function initialize() {
        AppBase.initialize();

        _phoneMethod = method(:onPhoneAppMessage);
        _playing = null;
        _track = null;
        _artist = null;
        _volume = null;
    }

    public function onStart(state as Dictionary?) as Void {
        if (Communications has :registerForPhoneAppMessages) {
            Communications.registerForPhoneAppMessages(_phoneMethod);
        }
    }

    public function onStop(state as Dictionary?) as Void {
        if (Communications has :registerForPhoneAppMessages) {
            Communications.registerForPhoneAppMessages(null);
        }
    }

    public function getInitialView() as [Views] or [Views, InputDelegates] {
        var view = new $.MusicControlView();

        return [view, new $.MusicControlDelegate(view)];
    }

    //! Companion status message. Everything here is untrusted input: any key may
    //! be absent, null, or the wrong type, and none of that may crash the app.
    public function onPhoneAppMessage(msg as PhoneAppMessage) as Void {
        var data = msg.data;

        if (!(data instanceof Lang.Dictionary)) {
            return;
        }

        var dict = data as Dictionary;

        if (dict.hasKey("state")) {
            var state = dict.get("state");

            if (state instanceof Lang.String) {
                if (state.equals("playing")) {
                    _playing = true;
                } else if (state.equals("paused")) {
                    _playing = false;
                } else {
                    _playing = null;
                }
            } else {
                _playing = null;
            }
        }

        if (dict.hasKey("track")) {
            _track = asDisplayString(dict.get("track"));
        }

        if (dict.hasKey("artist")) {
            _artist = asDisplayString(dict.get("artist"));
        }

        if (dict.hasKey("vol")) {
            _volume = asPercent(dict.get("vol"));
        }

        WatchUi.requestUpdate();
    }

    public function isPlaying() as Boolean or Null {
        return _playing;
    }

    public function getTrack() as String or Null {
        return _track;
    }

    public function getArtist() as String or Null {
        return _artist;
    }

    public function getVolume() as Number or Null {
        return _volume;
    }

    private function asDisplayString(value as Object or Null) as String or Null {
        if (value instanceof Lang.String) {
            var text = value as String;

            if (text.length() == 0) {
                return null;
            }

            return text;
        }

        if (value == null) {
            return null;
        }

        return value.toString();
    }

    private function asPercent(value as Object or Null) as Number or Null {
        var number = null;

        if (value instanceof Lang.Number) {
            number = value as Number;
        } else if (value instanceof Lang.Float || value instanceof Lang.Double || value instanceof Lang.Long) {
            number = (value as Numeric).toNumber();
        } else if (value instanceof Lang.String) {
            number = (value as String).toNumber();
        }

        if (number == null) {
            return null;
        }

        if (number < 0) {
            return 0;
        }

        if (number > 100) {
            return 100;
        }

        return number;
    }

}

function getApp() as MusicControlApp {
    return Application.getApp() as MusicControlApp;
}
