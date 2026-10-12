package com.spectrumlab.s80.sensors;

/** Frequency (MHz) -> Wi-Fi channel mapping via table arithmetic, never guessed ranges (WIFI-003). */
public final class WifiChannels {
    private WifiChannels() {}

    /** @return channel number or null when frequency is not a known 802.11 center frequency. */
    public static Integer fromFrequencyMHz(int freqMhz) {
        if (freqMhz == 2484) return 14;                       // ch 14
        if (freqMhz >= 2412 && freqMhz <= 2472) return (freqMhz - 2407) / 5; // ch 1..13
        if (freqMhz >= 4910 && freqMhz <= 4980) return (freqMhz - 4000) / 5; // 5 GHz lower subband
        if (freqMhz >= 5000 && freqMhz <= 5925) return (freqMhz - 5000) / 5; // ch 0..185
        if (freqMhz >= 5955 && freqMhz <= 7115) return (freqMhz - 5950) / 5; // 6 GHz ch 1..233
        return null;
    }

    public static String bandOf(int freqMhz) {
        if (freqMhz <= 2484) return "2.4GHz";
        if (freqMhz <= 5925) return "5GHz";
        return "6GHz";
    }

    /** Compact security summary from ScanResult.capabilities — information only, no verdict (WIFI-004). */
    public static String securitySummary(String capabilities) {
        if (capabilities == null || capabilities.trim().isEmpty()) return "OPEN?";
        String c = capabilities.toUpperCase();
        boolean wpa3 = c.contains("SAE");
        boolean wpa2 = c.contains("WPA2");
        boolean wpa = c.contains("WPA") && !wpa2 && !wpa3;
        boolean wep = c.contains("WEP");
        StringBuilder s = new StringBuilder();
        if (wpa3) s.append("WPA3-SAE");
        else if (wpa2) s.append("WPA2");
        else if (wpa) s.append("WPA");
        else if (wep) s.append("WEP");
        else s.append("OPEN");
        if (c.contains("EAP") || c.contains("802.1X")) s.append("-EAP");
        else if (c.contains("PSK")) s.append("-PSK");
        if (c.contains("-PSK+SAE")) s.append(" (mixed)");
        return s.toString();
    }
}
