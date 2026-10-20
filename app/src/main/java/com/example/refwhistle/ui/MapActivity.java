package com.example.refwhistle.ui;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.example.refwhistle.R;
import com.example.refwhistle.data.DBHelper;
import com.example.refwhistle.domain.Match;
import com.example.refwhistle.domain.Tournament;
import com.example.refwhistle.domain.Venue;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/**
 * Google Map of all venues. Orange pin = outdoor (heat rule applies), green pin = indoor.
 * Tap a pin for the venue's next match; long-press anywhere to choose a venue and save its location
 * (used by Navigate and the referee GPS check-in).
 */
public class MapActivity extends AppCompatActivity implements OnMapReadyCallback {
    private DBHelper db;
    private GoogleMap map;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);
        db = new DBHelper(this, getSharedPreferences("refwhistle", Context.MODE_PRIVATE)
                .getString("device_id", "dev-unknown"));
        findViewById(R.id.tvBack).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        if (!hasKey()) {
            ((TextView) findViewById(R.id.tvHint)).setText(
                    "The map is not available on this device right now.");
        }
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.map);
        if (f instanceof SupportMapFragment) {
            ((SupportMapFragment) f).getMapAsync(this);
        }
    }

    private boolean hasKey() {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
            String k = ai.metaData == null ? null : ai.metaData.getString("com.google.android.geo.API_KEY");
            return k != null && !k.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        map = googleMap;
        map.getUiSettings().setZoomControlsEnabled(true);
        map.getUiSettings().setMapToolbarEnabled(true);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try { map.setMyLocationEnabled(true); } catch (SecurityException ignored) { }
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 3);
        }
        map.setOnMapLongClickListener(new GoogleMap.OnMapLongClickListener() {
            public void onMapLongClick(LatLng p) { chooseVenue(p); }
        });
        showVenues();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (map != null && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            try { map.setMyLocationEnabled(true); } catch (SecurityException ignored) { }
        }
    }

    private void showVenues() {
        map.clear();
        Tournament t = db.loadTournament();
        List<Match> matches = db.getMatches();
        LatLngBounds.Builder bounds = new LatLngBounds.Builder();
        int placed = 0;
        for (Venue v : t.venues) {
            if (!v.hasLocation()) continue;
            LatLng pos = new LatLng(v.lat, v.lng);
            Match next = null;
            for (Match m : matches) {
                if (m.venueId.equals(v.id)) { next = m; break; }
            }
            String snippet = (v.outdoor ? "Outdoor" : "Indoor") + "  " + MainActivity.fmt(v.openMin) + "-"
                    + MainActivity.fmt(v.closeMin)
                    + (next == null ? "" : "\nNext: " + t.teamName(next.teamA) + " vs " + t.teamName(next.teamB)
                    + " D" + (next.day + 1) + " " + MainActivity.fmt(next.startMin));
            map.addMarker(new MarkerOptions().position(pos).title(v.name).snippet(snippet)
                    .icon(BitmapDescriptorFactory.defaultMarker(v.outdoor
                            ? BitmapDescriptorFactory.HUE_ORANGE : BitmapDescriptorFactory.HUE_GREEN)));
            bounds.include(pos);
            placed++;
        }
        if (placed == 1) {
            Venue only = null;
            for (Venue v : t.venues) if (v.hasLocation()) only = v;
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(only.lat, only.lng), 16));
        } else if (placed > 1) {
            try {
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 120));
            } catch (Exception e) {
                // layout not ready yet: fall back to the first venue
                for (Venue v : t.venues) {
                    if (v.hasLocation()) {
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(v.lat, v.lng), 15));
                        break;
                    }
                }
            }
        }
    }

    private void chooseVenue(final LatLng p) {
        if (!MainActivity.isAdminNow(this, db)) {
            Toast.makeText(this, "Only admins can place venues.", Toast.LENGTH_SHORT).show();
            return;
        }
        final Tournament t = db.loadTournament();
        if (t.venues.isEmpty()) {
            Toast.makeText(this, "No venues yet. Load demo data or add venues in Setup.", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[t.venues.size()];
        for (int i = 0; i < names.length; i++) names[i] = t.venues.get(i).name;
        UiKit.dialog(this).setTitle("Place which venue here?")
                .setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        db.setVenueLocation(t.venues.get(which).id, p.latitude, p.longitude);
                        showVenues();
                        Toast.makeText(MapActivity.this, t.venues.get(which).name + " location saved",
                                Toast.LENGTH_SHORT).show();
                    }
                }).setNegativeButton("Cancel", null).show();
    }
}
