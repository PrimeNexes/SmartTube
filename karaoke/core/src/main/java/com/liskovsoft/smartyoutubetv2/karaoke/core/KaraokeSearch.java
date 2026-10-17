package com.liskovsoft.smartyoutubetv2.karaoke.core;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a music video title into a search for its karaoke (instrumental + lyrics) version.
 */
public final class KaraokeSearch {
    private static final Pattern BRACKETS = Pattern.compile("\\([^)]*\\)|\\[[^]]*]|【[^】]*】|\\{[^}]*}");
    private static final Pattern NOISE_WORDS = Pattern.compile(
            "(?i)\\b(official\\s+(music\\s+)?video|official\\s+audio|official|lyric(s)?\\s+video|lyrics?|music\\s+video|" +
                    "audio|visuali[sz]er|remaster(ed)?|hd|hq|4k|mv|m/v)\\b");
    private static final Pattern SEPARATORS = Pattern.compile("\\s*[|•·]\\s*.*$");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[\\s\\-–—:,.]+$");

    private KaraokeSearch() {
    }

    /**
     * "Artist - Song (Official Video) [4K]" -> "Artist - Song karaoke"
     */
    public static String buildQuery(String title, String karaokeWord) {
        if (title == null) {
            return karaokeWord;
        }

        String query = BRACKETS.matcher(title).replaceAll(" ");
        query = SEPARATORS.matcher(query).replaceAll(""); // "Song | Artist Channel" tails
        query = NOISE_WORDS.matcher(query).replaceAll(" ");
        query = SPACES.matcher(query).replaceAll(" ").trim();
        query = TRAILING_PUNCTUATION.matcher(query).replaceAll("");

        if (query.isEmpty()) {
            query = title.trim();
        }

        if (query.toLowerCase(Locale.ROOT).contains(karaokeWord.toLowerCase(Locale.ROOT))) {
            return query;
        }

        return query + " " + karaokeWord;
    }
}
