using Toybox.Activity;
using Toybox.Graphics;
using Toybox.Lang;
using Toybox.Math;
using Toybox.System;
using Toybox.Timer;
using Toybox.WatchUi;

//! Live screen. Page 0 is the run display, page 1 is a diagnostics page that
//! shows the raw FTMS bytes so an unexpected packet layout can be read off the
//! watch instead of guessed at.
class MainView extends WatchUi.View {

    const PAGE_MAIN = 0;
    const PAGE_DEBUG = 1;

    var page as Lang.Number = PAGE_MAIN;

    private var _client as FtmsClient;
    private var _recorder as Recorder;
    private var _timer as Timer.Timer or Null = null;
    private var _ticks as Lang.Number = 0;

    // Heart rate comes from the watch, sampled once per tick alongside
    // everything else rather than re-read on every redraw.
    private var _hr as Lang.Number or Null = null;
    private var _avgHr as Lang.Number or Null = null;

    function initialize(client as FtmsClient, recorder as Recorder) {
        View.initialize();
        _client = client;
        _recorder = recorder;
    }

    function onShow() as Void {
        if (_timer == null) {
            _timer = new Timer.Timer();
            _timer.start(method(:onTick), 1000, true);
        }
    }

    function onHide() as Void {
        if (_timer != null) {
            _timer.stop();
            _timer = null;
        }
    }

    function onTick() as Void {
        _ticks += 1;

        // If profile registration was slow or silent, kick off the scan here.
        if (_client.isIdle() && _ticks > 3) {
            _client.startScan();
        }

        sampleHeartRate();
        _recorder.update(_client, 1.0);
        _recorder.writeSummaryFields();
        WatchUi.requestUpdate();
    }

    //! Prefer the watch's own optical HR, since that is what the FIT file
    //! records natively; fall back to a strap relayed by the treadmill.
    private function sampleHeartRate() as Void {
        var info = Activity.getActivityInfo();

        if (info != null && info.currentHeartRate != null) {
            _hr = info.currentHeartRate;
        } else if (_client.machineHr != null && _client.isFresh()) {
            _hr = _client.machineHr;
        } else {
            _hr = null;
        }

        // Only populated once a session is recording.
        if (info != null && info.averageHeartRate != null) {
            _avgHr = info.averageHeartRate;
        }
    }

    function nextPage() as Void {
        page = (page == PAGE_MAIN) ? PAGE_DEBUG : PAGE_MAIN;
        WatchUi.requestUpdate();
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        if (page == PAGE_DEBUG) {
            drawDebug(dc);
        } else {
            drawMain(dc);
        }
    }

    //! Three tiers of data, stacked and centred:
    //!
    //!            TIME    DIST
    //!       HR   INCLINE   SPEED
    //!      AVG    ELEV     PACE
    //!
    //! Font sizes are fixed per tier but every row still measures itself and
    //! pulls its columns inward to stay inside the circle at its own depth.
    //! The bottom of a 454px round screen is only ~280px wide, so a three-up
    //! row that would be fine across the middle spills off the rim down there.
    private function drawMain(dc as Graphics.Dc) as Void {
        var hLabel = dc.getFontHeight(Graphics.FONT_XTINY);

        // Measured on the FR965: MEDIUM 61px, LARGE 71px, XTINY 37px tall.
        var fontA = Graphics.FONT_MEDIUM;
        var fontB = Graphics.FONT_LARGE;
        var fontC = Graphics.FONT_XTINY;

        var content = hLabel + 6
            + hLabel + dc.getFontHeight(fontA) + 10
            + hLabel + dc.getFontHeight(fontB) + 10
            + hLabel + dc.getFontHeight(fontC);

        var y = (dc.getHeight() - content) / 2;

        if (y < 20) {
            y = 20;
        }

        drawStatusLine(dc, dc.getWidth() / 2, y);
        y += hLabel + 6;

        var white = Graphics.COLOR_WHITE;

        y = row(dc, y, fontA,
            ["TIME", "DIST"],
            [clock(_recorder.elapsedSec), (_recorder.distanceM / 1000.0).format("%.2f")],
            [white, white]);
        y += 10;

        y = row(dc, y, fontB,
            ["HR", "INCLINE", "SPEED"],
            [bpm(_hr), inclineText(), speedText()],
            [Graphics.COLOR_RED, Graphics.COLOR_ORANGE, white]);
        y += 10;

        row(dc, y, fontC,
            ["AVG", "ELEV", "PACE"],
            [bpm(_avgHr), _recorder.ascentM.toNumber().toString() + " m", pace()],
            [Graphics.COLOR_RED, Graphics.COLOR_ORANGE, white]);
    }

    private function speedText() as Lang.String {
        if (_client.speedMps == null || !_client.isFresh()) {
            return "--.-";
        }

        return (_client.speedMps * 3.6).format("%.1f");
    }

    private function inclineText() as Lang.String {
        if (_client.inclinePct == null || !_client.isFresh()) {
            return "--.-";
        }

        return _client.inclinePct.format("%.1f");
    }

    //! Draw a row of label/value columns, spaced as widely as the circle
    //! allows at this depth. Returns the y coordinate below the row.
    private function row(dc as Graphics.Dc, y as Lang.Number, valueFont as Graphics.FontType,
                         labels as Lang.Array<Lang.String>, values as Lang.Array<Lang.String>,
                         colors as Lang.Array<Graphics.ColorType>) as Lang.Number {
        var n = labels.size();
        var cx = dc.getWidth() / 2;
        var hLabel = dc.getFontHeight(Graphics.FONT_XTINY);
        var hValue = dc.getFontHeight(valueFont);
        var bottom = y + hLabel + hValue;

        // Half-width of the widest thing that has to fit in an outer column.
        var widest = 0;

        for (var i = 0; i < n; i += 1) {
            var lw = dc.getTextWidthInPixels(labels[i], Graphics.FONT_XTINY) / 2;
            var vw = dc.getTextWidthInPixels(values[i], valueFont) / 2;

            if (lw > widest) { widest = lw; }

            if (vw > widest) { widest = vw; }
        }

        // A row is pinched by whichever of its two edges sits deeper.
        var limit = safeHalfWidth(dc, y);
        var lower = safeHalfWidth(dc, bottom);

        if (lower < limit) { limit = lower; }

        var spacing = 0.0;

        if (n > 1) {
            spacing = (n == 2) ? dc.getWidth() / 2.0 - 20 : dc.getWidth() / 3.0;

            // The outermost column centre sits spacing * (n-1) / 2 from centre.
            var maxOffset = limit - widest;
            var outer = spacing * (n - 1) / 2.0;

            if (outer > maxOffset) {
                spacing = maxOffset * 2.0 / (n - 1);
            }

            if (spacing < 0) {
                spacing = 0.0;
            }
        }

        var first = -(n - 1) / 2.0;

        for (var i = 0; i < n; i += 1) {
            var x = cx + ((first + i) * spacing).toNumber();

            dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_TRANSPARENT);
            dc.drawText(x, y, Graphics.FONT_XTINY, labels[i], Graphics.TEXT_JUSTIFY_CENTER);
            dc.setColor(colors[i], Graphics.COLOR_TRANSPARENT);
            dc.drawText(x, y + hLabel, valueFont, values[i], Graphics.TEXT_JUSTIFY_CENTER);
        }

        return bottom;
    }

    //! Usable half-width of a round screen at a given y, with a small inset.
    private function safeHalfWidth(dc as Graphics.Dc, y as Lang.Number) as Lang.Number {
        var r = dc.getWidth() / 2;
        var dy = y - dc.getHeight() / 2;

        if (dy < 0) {
            dy = -dy;
        }

        if (dy >= r) {
            return 0;
        }

        return (Math.sqrt(1.0 * r * r - 1.0 * dy * dy)).toNumber() - 8;
    }

    private function bpm(v as Lang.Number or Null) as Lang.String {
        if (v == null || v == 0) {
            return "--";
        }

        return v.toString();
    }

    private function drawStatusLine(dc as Graphics.Dc, cx as Lang.Number, y as Lang.Number) as Void {
        var text = _client.stateLabel();
        var color = Graphics.COLOR_RED;

        if (_client.isLive()) {
            color = Graphics.COLOR_GREEN;
        } else if (_client.isBusy()) {
            color = Graphics.COLOR_YELLOW;
        }

        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_XTINY, text, Graphics.TEXT_JUSTIFY_CENTER);

        // The status line sits high up where the chord is narrow, so the
        // recording state is a dot rather than more words: appending
        // " / PAUSED" to "SUBSCRIBING" would run off the rim.
        if (!_recorder.hasSession()) {
            return;
        }

        var hLabel = dc.getFontHeight(Graphics.FONT_XTINY);
        var dotX = cx - dc.getTextWidthInPixels(text, Graphics.FONT_XTINY) / 2 - 16;

        dc.setColor(_recorder.isRecording() ? Graphics.COLOR_RED : Graphics.COLOR_YELLOW,
            Graphics.COLOR_TRANSPARENT);
        dc.fillCircle(dotX, y + hLabel / 2, 7);
    }

    private function drawDebug(dc as Graphics.Dc) as Void {
        var cx = dc.getWidth() / 2;
        var y = 60;
        var step = dc.getFontHeight(Graphics.FONT_XTINY) - 4;

        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);

        var lines = [
            _client.stateLabel() + " " + _client.statusDetail,
            "pkts " + _client.packetCount.toString()
                + "  flags " + _client.lastFlags.format("%04X"),
            Ftms.toHex(_client.lastRaw),
            "mach dist " + fmt(_client.machineDistanceM)
                + "  elev " + fmt(_client.machineElevGainM),
            "native rejected: "
                + (_recorder.nativeRejected.length() > 0 ? _recorder.nativeRejected : "none"),
            "alt " + _recorder.altitudeM.format("%.1f")
                + "  asc " + _recorder.ascentM.format("%.1f")
        ];

        for (var i = 0; i < lines.size(); i += 1) {
            dc.drawText(cx, y, Graphics.FONT_XTINY, lines[i], Graphics.TEXT_JUSTIFY_CENTER);
            y += step;
        }

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        y += 10;

        var names = _client.seenNames;
        var shown = names.size() > 4 ? 4 : names.size();

        for (var i = 0; i < shown; i += 1) {
            dc.drawText(cx, y, Graphics.FONT_XTINY, names[i], Graphics.TEXT_JUSTIFY_CENTER);
            y += step;
        }
    }

    private function fmt(v as Lang.Object or Null) as Lang.String {
        if (v == null) {
            return "-";
        }

        return v.toString();
    }

    private function pace() as Lang.String {
        if (_client.speedMps == null || !_client.isFresh() || _client.speedMps < 0.3) {
            return "--:--";
        }

        var secPerKm = (1000.0 / _client.speedMps).toNumber();

        // No "/km" suffix: it makes the cell too wide for a three-up row, and
        // the column is labelled PACE anyway.
        return (secPerKm / 60).format("%d") + ":" + (secPerKm % 60).format("%02d");
    }

    private function clock(sec as Lang.Number) as Lang.String {
        var h = sec / 3600;
        var m = (sec % 3600) / 60;
        var s = sec % 60;

        if (h > 0) {
            return h.format("%d") + ":" + m.format("%02d") + ":" + s.format("%02d");
        }

        return m.format("%d") + ":" + s.format("%02d");
    }
}
