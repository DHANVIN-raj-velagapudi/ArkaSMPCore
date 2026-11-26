package com.arka.smp.core;

/** Plain checks for PlayTimes: java -cp ... com.arka.smp.core.PlayTimesCheck */
public final class PlayTimesCheck {
    private static int failed;

    private static void eq(String name, String got, String want) {
        boolean ok = got.equals(want);
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name + (ok ? "" : "  got '" + got + "' want '" + want + "'"));
        if (!ok) failed++;
    }

    private static long min(long m) {
        return m * 1200;
    }

    public static void main(String[] args) {
        eq("0 ticks", PlayTimes.format(0), "0M");
        eq("59 seconds is still 0M", PlayTimes.format(1199), "0M");
        eq("1 minute", PlayTimes.format(min(1)), "1M");
        eq("45 minutes", PlayTimes.format(min(45)), "45M");
        eq("59 minutes", PlayTimes.format(min(59)), "59M");
        eq("exactly 1 hour", PlayTimes.format(min(60)), "1HR 0M");
        eq("1 hour 1 minute", PlayTimes.format(min(61)), "1HR 1M");
        eq("3 hours 20 minutes", PlayTimes.format(min(200)), "3HR 20M");
        eq("23 hours 59 minutes", PlayTimes.format(min(1439)), "23HR 59M");
        eq("exactly 24 hours", PlayTimes.format(min(1440)), "1 day 0HRS");
        eq("24 hours 59 minutes has no minutes", PlayTimes.format(min(1499)), "1 day 0HRS");
        eq("1 day 5 hours", PlayTimes.format(min(1440 + 300)), "1 day 5HRS");
        eq("2 days 5 hours 40 minutes", PlayTimes.format(min(2 * 1440 + 340)), "2 day 5HRS");
        eq("10 days", PlayTimes.format(min(10 * 1440)), "10 day 0HRS");
        eq("negative is zero", PlayTimes.format(-5), "0M");
        eq("custom wording", PlayTimes.format(min(125), "{m}m", "{h}h {m}m", "{d}d {h}h"), "2h 5m");
        System.out.println(failed == 0 ? "ALL PASSED" : failed + " FAILED");
        if (failed != 0) System.exit(1);
    }
}
