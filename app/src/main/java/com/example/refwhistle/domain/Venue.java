package com.example.refwhistle.domain;

/** A ground or court. Times are minutes after midnight (08:00 = 480). */
public class Venue {
    public final String id;
    public final String name;
    public final boolean outdoor;
    public final int openMin;
    public final int closeMin;
    public final double lat;
    public final double lng;

    public Venue(String id, String name, boolean outdoor, int openMin, int closeMin) {
        this(id, name, outdoor, openMin, closeMin, 0, 0);
    }

    public Venue(String id, String name, boolean outdoor, int openMin, int closeMin,
                 double lat, double lng) {
        this.id = id;
        this.name = name;
        this.outdoor = outdoor;
        this.openMin = openMin;
        this.closeMin = closeMin;
        this.lat = lat;
        this.lng = lng;
    }

    public boolean hasLocation() {
        return lat != 0 || lng != 0;
    }
}
