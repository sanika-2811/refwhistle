package com.example.refwhistle.domain;

import java.util.ArrayList;
import java.util.List;

/** Result of a scheduler run: placed matches plus the pairings that could not be placed. */
public class Schedule {
    public final List<Match> matches = new ArrayList<Match>();
    public final List<String> unplaced = new ArrayList<String>();
}
