package com.liskovsoft.smartyoutubetv2.karaoke.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class KaraokeSearchTest {
    @Test
    public void stripsVideoNoise() {
        assertEquals("Queen - Bohemian Rhapsody karaoke",
                KaraokeSearch.buildQuery("Queen – Bohemian Rhapsody (Official Video Remastered) [4K]".replace("–", "-"), "karaoke"));
        assertEquals("Adele - Hello karaoke",
                KaraokeSearch.buildQuery("Adele - Hello (Official Music Video)", "karaoke"));
        assertEquals("Shape of You karaoke",
                KaraokeSearch.buildQuery("Shape of You | Ed Sheeran Official", "karaoke"));
        assertEquals("BTS 'Dynamite' karaoke",
                KaraokeSearch.buildQuery("BTS (방탄소년단) 'Dynamite' Official MV", "karaoke"));
    }

    @Test
    public void keepsKaraokeTitlesAsIs() {
        assertEquals("Let It Go Karaoke Version",
                KaraokeSearch.buildQuery("Let It Go Karaoke Version", "karaoke"));
    }

    @Test
    public void fallsBackToTitle() {
        assertEquals("(Official Video) karaoke", KaraokeSearch.buildQuery("(Official Video)", "karaoke"));
        assertEquals("karaoke", KaraokeSearch.buildQuery(null, "karaoke"));
    }
}
