package com.farmeraccounts.common;

/** Compares dotted numeric versions such as 1.2.10. Missing or garbled parts count as 0. */
public final class Versions {

    private Versions() {}

    public static int compare(String a, String b) {
        String[] pa = (a == null ? "" : a).split("\\.");
        String[] pb = (b == null ? "" : b).split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int x = i < pa.length ? parse(pa[i]) : 0;
            int y = i < pb.length ? parse(pb[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    public static boolean isValid(String v) {
        return v != null && v.matches("\\d{1,4}(\\.\\d{1,4}){0,3}");
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
