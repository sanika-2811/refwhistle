package com.example.refwhistle.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only operation log. Syncing two phones = union of their logs (duplicates ignored
 * because the operation id is unique). Order of arrival does not matter.
 */
public class OpLog {
    private final Map<String, Operation> ops = new HashMap<String, Operation>();

    public void append(Operation op) {
        ops.put(op.id(), op);
    }

    public void merge(Collection<Operation> incoming) {
        for (Operation op : incoming) {
            append(op);
        }
    }

    public int size() {
        return ops.size();
    }

    public List<Operation> all() {
        return new ArrayList<Operation>(ops.values());
    }

    /** Operations in the one global order every phone agrees on. */
    public List<Operation> sorted() {
        List<Operation> list = all();
        Collections.sort(list, new Comparator<Operation>() {
            public int compare(Operation a, Operation b) {
                return a.stamp.compareTo(b.stamp);
            }
        });
        return list;
    }
}
