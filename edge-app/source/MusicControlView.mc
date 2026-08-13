import Toybox.Communications;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.Timer;
import Toybox.WatchUi;

enum ButtonId {
    BTN_PREV = 0,
    BTN_PLAYPAUSE = 1,
    BTN_NEXT = 2,
    BTN_VOLDOWN = 3,
    BTN_VOLUP = 4
}

enum SendStatus {
    SEND_IDLE = 0,
    SEND_OK = 1,
    SEND_FAILED = 2
}

const BUTTON_COUNT = 5;

//! The exact strings the companion app expects as the value of "cmd".
function commandFor(id as Number) as String {
    if (id == $.BTN_PREV) {
        return "prev";
    }

    if (id == $.BTN_NEXT) {
        return "next";
    }

    if (id == $.BTN_VOLDOWN) {
        return "voldown";
    }

    if (id == $.BTN_VOLUP) {
        return "volup";
    }

    return "playpause";
}

const COLOR_BG = 0x000000;
const COLOR_FG = 0xFFFFFF;
const COLOR_DIM = 0xAAAAAA;
const COLOR_BUTTON = 0x333333;
const COLOR_BUTTON_EDGE = 0x777777;
const COLOR_OK = 0x00AA00;
const COLOR_FAIL = 0xFF0000;
const COLOR_VOL_TRACK = 0x333333;

const MARGIN = 6;
const GAP = 6;
const CORNER_RADIUS = 8;
const VOL_BAR_HEIGHT = 12;
const STATUS_GLYPH = 12;

const PRESS_FLASH_MS = 180;
const SEND_FLASH_MS = 1500;

class MusicControlView extends WatchUi.View {

    private var _button as Array< Array<Number> >;

    private var _statusY as Number = 0;
    private var _titleY as Number = 0;
    private var _artistY as Number = 0;
    private var _dividerY as Number = 0;
    private var _volBarY as Number = 0;
    private var _width as Number = 0;

    private var _pressed as Number = -1;
    private var _sendStatus as SendStatus = SEND_IDLE;

    private var _pressTimer as Timer.Timer;
    private var _sendTimer as Timer.Timer;

    public function initialize() {
        View.initialize();

        _button = [[0, 0, 0, 0], [0, 0, 0, 0], [0, 0, 0, 0], [0, 0, 0, 0], [0, 0, 0, 0]];

        _pressTimer = new Timer.Timer();
        _sendTimer = new Timer.Timer();
    }

    public function onLayout(dc as Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();

        _width = w;

        var y = 3;

        _statusY = y;
        y += Graphics.getFontHeight(Graphics.FONT_XTINY) + 2;

        _titleY = y;
        y += Graphics.getFontHeight(Graphics.FONT_MEDIUM) + 1;

        _artistY = y;
        y += Graphics.getFontHeight(Graphics.FONT_TINY) + 4;

        _dividerY = y;
        y += 6;

        var footer = Graphics.getFontHeight(Graphics.FONT_XTINY) + $.VOL_BAR_HEIGHT + 10;
        var gridTop = y;
        var gridHeight = h - gridTop - footer - $.MARGIN;

        var transportHeight = ((gridHeight - $.GAP) * 55) / 100;
        var volumeHeight = gridHeight - $.GAP - transportHeight;

        var wideWidth = (w - (2 * $.MARGIN) - (2 * $.GAP)) / 3;
        var halfWidth = (w - (2 * $.MARGIN) - $.GAP) / 2;

        _button[$.BTN_PREV] = [$.MARGIN, gridTop, wideWidth, transportHeight];
        _button[$.BTN_PLAYPAUSE] = [$.MARGIN + wideWidth + $.GAP, gridTop, wideWidth, transportHeight];
        _button[$.BTN_NEXT] = [$.MARGIN + (2 * (wideWidth + $.GAP)), gridTop, wideWidth, transportHeight];

        var volumeTop = gridTop + transportHeight + $.GAP;

        _button[$.BTN_VOLDOWN] = [$.MARGIN, volumeTop, halfWidth, volumeHeight];
        _button[$.BTN_VOLUP] = [$.MARGIN + halfWidth + $.GAP, volumeTop, halfWidth, volumeHeight];

        _volBarY = volumeTop + volumeHeight + 6;
    }

    public function onHide() as Void {
        _pressTimer.stop();
        _sendTimer.stop();

        _pressed = -1;
        _sendStatus = $.SEND_IDLE;
    }

    public function onUpdate(dc as Dc) as Void {
        dc.setColor($.COLOR_FG, $.COLOR_BG);
        dc.clear();

        drawStatusLine(dc);
        drawTrackInfo(dc);

        dc.setColor($.COLOR_BUTTON_EDGE, Graphics.COLOR_TRANSPARENT);
        dc.setPenWidth(1);
        dc.drawLine($.MARGIN, _dividerY, _width - $.MARGIN, _dividerY);

        for (var i = 0; i < $.BUTTON_COUNT; i++) {
            drawButton(dc, i);
        }

        drawVolumeBar(dc);
    }

    //! Returns true when the coordinates landed on a button and a command was sent.
    public function handleTap(x as Number, y as Number) as Boolean {
        for (var i = 0; i < $.BUTTON_COUNT; i++) {
            var r = _button[i];

            if (x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3]) {
                pressButton(i);

                return true;
            }
        }

        return false;
    }

    public function pressButton(id as Number) as Void {
        if (id < 0 || id >= $.BUTTON_COUNT) {
            return;
        }

        _pressed = id;

        _pressTimer.stop();
        _pressTimer.start(method(:onPressTimeout), $.PRESS_FLASH_MS, false);

        WatchUi.requestUpdate();

        sendCommand(id);
    }

    public function onSendResult(succeeded as Boolean) as Void {
        if (succeeded) {
            _sendStatus = $.SEND_OK;
        } else {
            _sendStatus = $.SEND_FAILED;
        }

        _sendTimer.stop();
        _sendTimer.start(method(:onSendTimeout), $.SEND_FLASH_MS, false);

        WatchUi.requestUpdate();
    }

    public function onPressTimeout() as Void {
        _pressed = -1;

        WatchUi.requestUpdate();
    }

    public function onSendTimeout() as Void {
        _sendStatus = $.SEND_IDLE;

        WatchUi.requestUpdate();
    }

    private function sendCommand(id as Number) as Void {
        var payload = { "cmd" => $.commandFor(id) };

        Communications.transmit(payload, null, new $.CommandListener(self));
    }

    private function drawStatusLine(dc as Dc) as Void {
        var connected = System.getDeviceSettings().phoneConnected;
        var label = "Phone: disconnected";

        if (connected) {
            label = "Phone: connected";
            dc.setColor($.COLOR_FG, Graphics.COLOR_TRANSPARENT);
        } else {
            dc.setColor($.COLOR_FAIL, Graphics.COLOR_TRANSPARENT);
        }

        dc.drawText($.MARGIN, _statusY, Graphics.FONT_XTINY, label, Graphics.TEXT_JUSTIFY_LEFT);

        if (_sendStatus == $.SEND_IDLE) {
            return;
        }

        var glyphLeft = _width - $.MARGIN - $.STATUS_GLYPH;
        var glyphTop = _statusY + ((Graphics.getFontHeight(Graphics.FONT_XTINY) - $.STATUS_GLYPH) / 2);

        if (_sendStatus == $.SEND_OK) {
            dc.setColor($.COLOR_OK, Graphics.COLOR_TRANSPARENT);
            dc.drawText(glyphLeft - 4, _statusY, Graphics.FONT_XTINY, "sent", Graphics.TEXT_JUSTIFY_RIGHT);
            drawCheck(dc, glyphLeft, glyphTop);
        } else {
            dc.setColor($.COLOR_FAIL, Graphics.COLOR_TRANSPARENT);
            dc.drawText(glyphLeft - 4, _statusY, Graphics.FONT_XTINY, "failed", Graphics.TEXT_JUSTIFY_RIGHT);
            drawCross(dc, glyphLeft, glyphTop);
        }
    }

    private function drawTrackInfo(dc as Dc) as Void {
        var app = $.getApp();
        var available = _width - (2 * $.MARGIN);

        var track = app.getTrack();

        if (track == null) {
            track = "-";
        }

        var artist = app.getArtist();

        if (artist == null) {
            artist = "-";
        }

        dc.setColor($.COLOR_FG, Graphics.COLOR_TRANSPARENT);
        dc.drawText(
            _width / 2,
            _titleY,
            Graphics.FONT_MEDIUM,
            fitText(dc, track, Graphics.FONT_MEDIUM, available),
            Graphics.TEXT_JUSTIFY_CENTER
        );

        dc.setColor($.COLOR_DIM, Graphics.COLOR_TRANSPARENT);
        dc.drawText(
            _width / 2,
            _artistY,
            Graphics.FONT_TINY,
            fitText(dc, artist, Graphics.FONT_TINY, available),
            Graphics.TEXT_JUSTIFY_CENTER
        );
    }

    private function drawButton(dc as Dc, id as Number) as Void {
        var r = _button[id];
        var pressed = (_pressed == id);
        var ink = $.COLOR_FG;

        if (pressed) {
            dc.setColor($.COLOR_FG, Graphics.COLOR_TRANSPARENT);
            ink = $.COLOR_BG;
        } else {
            dc.setColor($.COLOR_BUTTON, Graphics.COLOR_TRANSPARENT);
        }

        dc.fillRoundedRectangle(r[0], r[1], r[2], r[3], $.CORNER_RADIUS);

        dc.setColor($.COLOR_BUTTON_EDGE, Graphics.COLOR_TRANSPARENT);
        dc.setPenWidth(1);
        dc.drawRoundedRectangle(r[0], r[1], r[2], r[3], $.CORNER_RADIUS);

        dc.setColor(ink, Graphics.COLOR_TRANSPARENT);

        var cx = r[0] + (r[2] / 2);
        var cy = r[1] + (r[3] / 2);

        if (id == $.BTN_PREV) {
            drawSkip(dc, cx, cy, 18, false);
        } else if (id == $.BTN_NEXT) {
            drawSkip(dc, cx, cy, 18, true);
        } else if (id == $.BTN_PLAYPAUSE) {
            if ($.getApp().isPlaying() == true) {
                drawPause(dc, cx, cy, 20);
            } else {
                drawPlay(dc, cx, cy, 20);
            }
        } else {
            drawVolumeLabel(dc, r, ink, id == $.BTN_VOLUP);
        }
    }

    private function drawPlay(dc as Dc, cx as Number, cy as Number, s as Number) as Void {
        dc.fillPolygon([
            [cx - (s / 2), cy - s],
            [cx - (s / 2), cy + s],
            [cx + s, cy]
        ]);
    }

    private function drawPause(dc as Dc, cx as Number, cy as Number, s as Number) as Void {
        var bar = s / 2;

        dc.fillRectangle(cx - bar - (bar / 2), cy - s, bar, 2 * s);
        dc.fillRectangle(cx + (bar / 2), cy - s, bar, 2 * s);
    }

    //! Bar plus a triangle pointing away from it — forward when `forward` is true.
    private function drawSkip(dc as Dc, cx as Number, cy as Number, s as Number, forward as Boolean) as Void {
        var bar = 5;

        if (forward) {
            dc.fillRectangle(cx + s - bar, cy - s, bar, 2 * s);
            dc.fillPolygon([
                [cx - s, cy - s],
                [cx - s, cy + s],
                [cx + s - bar - 3, cy]
            ]);
        } else {
            dc.fillRectangle(cx - s, cy - s, bar, 2 * s);
            dc.fillPolygon([
                [cx + s, cy - s],
                [cx + s, cy + s],
                [cx - s + bar + 3, cy]
            ]);
        }
    }

    private function drawVolumeLabel(dc as Dc, r as Array<Number>, ink as Number, up as Boolean) as Void {
        var cx = r[0] + (r[2] / 2);

        dc.setColor(ink, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, r[1] + 8, Graphics.FONT_SMALL, "VOL", Graphics.TEXT_JUSTIFY_CENTER);

        var signY = r[1] + ((r[3] * 2) / 3);
        var length = 34;
        var thickness = 8;

        dc.fillRectangle(cx - (length / 2), signY - (thickness / 2), length, thickness);

        if (up) {
            dc.fillRectangle(cx - (thickness / 2), signY - (length / 2), thickness, length);
        }
    }

    private function drawVolumeBar(dc as Dc) as Void {
        var volume = $.getApp().getVolume();

        if (volume == null) {
            return;
        }

        var full = _width - (2 * $.MARGIN);

        dc.setColor($.COLOR_VOL_TRACK, Graphics.COLOR_TRANSPARENT);
        dc.fillRectangle($.MARGIN, _volBarY, full, $.VOL_BAR_HEIGHT);

        dc.setColor($.COLOR_FG, Graphics.COLOR_TRANSPARENT);
        dc.fillRectangle($.MARGIN, _volBarY, (full * volume) / 100, $.VOL_BAR_HEIGHT);

        dc.setColor($.COLOR_DIM, Graphics.COLOR_TRANSPARENT);
        dc.drawText(
            _width / 2,
            _volBarY + $.VOL_BAR_HEIGHT + 1,
            Graphics.FONT_XTINY,
            volume.toString() + "%",
            Graphics.TEXT_JUSTIFY_CENTER
        );
    }

    private function drawCheck(dc as Dc, left as Number, top as Number) as Void {
        dc.setPenWidth(3);
        dc.drawLine(left + 1, top + 6, left + 4, top + 10);
        dc.drawLine(left + 4, top + 10, left + 11, top + 2);
    }

    private function drawCross(dc as Dc, left as Number, top as Number) as Void {
        dc.setPenWidth(3);
        dc.drawLine(left + 1, top + 1, left + 11, top + 11);
        dc.drawLine(left + 11, top + 1, left + 1, top + 11);
    }

    private function fitText(dc as Dc, text as String, font as Graphics.FontType, maxWidth as Number) as String {
        if (dc.getTextWidthInPixels(text, font) <= maxWidth) {
            return text;
        }

        var trimmed = text;

        while (trimmed.length() > 1) {
            var shorter = trimmed.substring(0, trimmed.length() - 1);

            if (shorter == null) {
                return trimmed;
            }

            trimmed = shorter;

            if (dc.getTextWidthInPixels(trimmed + "...", font) <= maxWidth) {
                return trimmed + "...";
            }
        }

        return trimmed;
    }

}
