package com.audiohaptics.lsp;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import java.util.HashMap;
import java.util.Map;

/** Quick Settings tile: one tap switches speaker haptics on/off. */
public class QsTileService extends TileService {
    @Override public void onStartListening() { update(); }

    @Override public void onClick() {
        Map<String, String> m = new HashMap<>();
        m.put("e", isOn() ? "0" : "1");
        Config.write(this, m);
        update();
    }

    private boolean isOn() {
        String v = Config.parse(Config.read(this)).get("e");
        return v == null || !v.equals("0");
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setLabel("اهتزاز السماعات");
        t.setState(isOn() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.updateTile();
    }
}
