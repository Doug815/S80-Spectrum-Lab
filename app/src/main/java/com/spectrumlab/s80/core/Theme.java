package com.spectrumlab.s80.core;

import android.graphics.Color;

/** Visual system per docs/05: dark charcoal + electric cyan/indigo. Red only for real faults. */
public final class Theme {
    public static final int BG        = Color.rgb(8, 13, 23);      // #080D17 near-black navy
    public static final int SURFACE   = Color.rgb(17, 28, 43);     // #111C2B
    public static final int SURFACE2  = Color.rgb(24, 38, 59);    // card hover surface
    public static final int ACCENT    = Color.rgb(53, 215, 255);  // #35D7FF electric cyan
    public static final int INDIGO    = Color.rgb(103, 136, 255); // #6788FF
    public static final int AMBER     = Color.rgb(246, 180, 73);  // #F6B449 unverified
    public static final int RED       = Color.rgb(255, 98, 105);  // #FF6269 faults only
    public static final int FG        = Color.rgb(234, 243, 255); // #EAF3FF
    public static final int MUTED     = Color.rgb(142, 163, 187); // #8EA3BB
    public static final int GREEN     = Color.rgb(84, 214, 142);   // verified OK

    private Theme() {}
}
