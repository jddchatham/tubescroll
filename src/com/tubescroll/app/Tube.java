package com.tubescroll.app;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One YouTube video as displayed in the pager. */
public class Tube {

    public String title;
    public String link;        // watch URL
    public String id;          // video id
    public String channel;     // channel display name
    public String channelKey;  // pill key this came from
    public long views;
    public String imageUrl;    // hq thumbnail
    public String dateText;
    public long dateMillis;

    public static ArrayList<Tube> shuffledCopy(List<Tube> in, Random rnd) {
        ArrayList<Tube> copy = new ArrayList<Tube>(in);
        Collections.shuffle(copy, rnd);
        return copy;
    }

    public static ArrayList<Tube> sortByDate(List<Tube> in) {
        ArrayList<Tube> copy = new ArrayList<Tube>(in);
        Collections.sort(copy, new Comparator<Tube>() {
            @Override
            public int compare(Tube a, Tube b) {
                if (b.dateMillis != a.dateMillis) return b.dateMillis > a.dateMillis ? 1 : -1;
                return 0;
            }
        });
        return copy;
    }

    public static String formatCount(long n) {
        if (n < 1000) return String.valueOf(n);
        double k = n / 1000.0;
        String s = String.format(Locale.US, "%.1f", k);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s + "k";
    }

    public static String relTime(long millis) {
        if (millis <= 0) return "";
        long diff = System.currentTimeMillis() - millis;
        if (diff < 60 * 1000L) return "just now";
        long m = diff / (60 * 1000L);
        if (m < 60) return m + "m ago";
        long h = m / 60;
        if (h < 24) return h + "h ago";
        long d = h / 24;
        if (d < 7) return d + "d ago";
        SimpleDateFormat f = new SimpleDateFormat("MMM d", Locale.US);
        return f.format(new Date(millis));
    }

    // ------------------------------------------------------------- parsing

    public static final class Listing {
        public final ArrayList<Tube> posts = new ArrayList<Tube>();
    }

    /** Last parse outcome, surfaced on the error screen for diagnosis. */
    public static String lastError = "";

    private static final String NS_ATOM = "http://www.w3.org/2005/Atom";
    private static final String NS_MEDIA = "http://search.yahoo.com/mrss/";
    private static final String NS_YT = "http://www.youtube.com/xml/schemas/2015";

    /** Parse a channel Atom feed. */
    public static Listing parseFeed(String xml, String channelKey) {
        if (xml == null) { lastError = "null body"; return null; }
        Listing listing = new Listing();
        lastError = "";
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            p.setInput(new StringReader(xml));
            Tube cur = null;
            int seen = 0;
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String ns = p.getNamespace();
                    String name = p.getName();
                    if (isAtomNs(ns) && "entry".equals(name)) {
                        cur = new Tube();
                        cur.channelKey = channelKey;
                        seen++;
                    } else if (cur != null) {
                        readField(p, cur, ns, name);
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    if (cur != null && isAtomNs(p.getNamespace()) && "entry".equals(p.getName())) {
                        finish(cur);
                        if (cur.id != null && !cur.id.isEmpty()) listing.posts.add(cur);
                        cur = null;
                    }
                }
                ev = p.next();
            }
            lastError = "feed ok, entries=" + seen + ", posts=" + listing.posts.size();
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            if (listing.posts.isEmpty()) return null;
        }
        return listing;
    }

    private static void readField(XmlPullParser p, Tube t, String ns, String name)
            throws Exception {
        if (isAtomNs(ns)) {
            if ("title".equals(name)) {
                t.title = p.nextText();
            } else if ("published".equals(name)) {
                t.dateMillis = parseIso(p.nextText().trim());
            } else if ("link".equals(name)) {
                if (t.link == null) {
                    String href = p.getAttributeValue(null, "href");
                    if (href != null && !href.isEmpty()) t.link = href.trim();
                }
                skipElement(p);
            } else if ("author".equals(name)) {
                readAuthor(p, t);
            } else {
                skipElement(p);
            }
        } else if (NS_YT.equals(ns) && "videoId".equals(name)) {
            // nextText() leaves the parser ON the end tag; never skipElement here.
            t.id = p.nextText().trim();
        } else if (NS_MEDIA.equals(ns) && "group".equals(name)) {
            readMediaGroup(p, t);
        } else {
            skipElement(p);
        }
    }

    /** Atom namespace, with an empty-ns fallback for parser quirks. */
    private static boolean isAtomNs(String ns) {
        return ns == null || ns.isEmpty() || NS_ATOM.equals(ns);
    }

    /** <author><name>..</name></author> — pure event walk, no nextText. */
    private static void readAuthor(XmlPullParser p, Tube t) throws Exception {
        int depth = 1;
        boolean wantText = false;
        while (depth > 0) {
            int ev = p.next();
            if (ev == XmlPullParser.START_TAG) {
                depth++;
                wantText = depth == 2 && "name".equals(p.getName());
            } else if (ev == XmlPullParser.END_TAG) {
                depth--;
                wantText = false;
            } else if (ev == XmlPullParser.TEXT && wantText && (t.channel == null || t.channel.isEmpty())) {
                t.channel = p.getText();
            }
        }
    }

    /** <media:group> thumbnail/statistics — attributes only, pure event walk. */
    private static void readMediaGroup(XmlPullParser p, Tube t) throws Exception {
        int depth = 1;
        while (depth > 0) {
            int ev = p.next();
            if (ev == XmlPullParser.START_TAG) {
                depth++;
                if (NS_MEDIA.equals(p.getNamespace())) {
                    String n = p.getName();
                    if ("thumbnail".equals(n) && (t.imageUrl == null || t.imageUrl.isEmpty())) {
                        String url = p.getAttributeValue(null, "url");
                        if (url != null && !url.isEmpty()) t.imageUrl = url.trim();
                    } else if ("statistics".equals(n) && t.views == 0) {
                        String v = p.getAttributeValue(null, "views");
                        if (v != null) {
                            try { t.views = Long.parseLong(v.replaceAll("[^0-9]", "")); }
                            catch (Exception ignored) { }
                        }
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                depth--;
            }
        }
    }

    private static void skipElement(XmlPullParser p) throws Exception {
        int depth = 1;
        while (depth > 0) {
            int ev = p.next();
            if (ev == XmlPullParser.START_TAG) depth++;
            else if (ev == XmlPullParser.END_TAG) depth--;
        }
    }

    private static void finish(Tube t) {
        t.title = t.title == null ? "" : t.title.trim();
        if (t.link == null || t.link.isEmpty()) {
            t.link = "https://www.youtube.com/watch?v=" + t.id;
        }
        if (t.imageUrl == null || t.imageUrl.isEmpty()) {
            t.imageUrl = "https://i.ytimg.com/vi/" + t.id + "/hqdefault.jpg";
        }
        if (t.channel == null) t.channel = "";
        t.dateText = relTime(t.dateMillis);
    }

    private static long parseIso(String s) {
        if (s == null || s.isEmpty()) return 0;
        s = s.trim();
        int cut = s.lastIndexOf('+', 19);
        int z = s.indexOf('Z');
        if (z > 0) s = s.substring(0, z);
        else if (cut > 0) s = s.substring(0, cut);
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(s).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------ channels

    /**
     * Resolve a pill key to a channel id. Keys: "UC..." (already an id),
     * "@handle", or a full youtube URL. Returns null when unresolvable.
     */
    public static String resolveChannelId(String key) {
        if (key == null) return null;
        key = key.trim();
        if (key.startsWith("UC") && key.length() > 20) return key;
        String url = key;
        if (key.startsWith("@")) url = "https://www.youtube.com/" + key;
        else if (!key.startsWith("http")) url = "https://www.youtube.com/@" + key;
        String html = Fetch.get(url);
        if (html == null) { lastError = "channel page fetch failed"; return null; }
        Matcher m = Pattern.compile("\\\"channelId\\\":\\\"(UC[A-Za-z0-9_\\\\-]{20,})\\\"").matcher(html);
        if (m.find()) return m.group(1);
        m = Pattern.compile("itemprop=\\\"identifier\\\" content=\\\"(UC[A-Za-z0-9_\\\\-]{20,})\\\"").matcher(html);
        if (m.find()) return m.group(1);
        m = Pattern.compile("channel_id=(UC[A-Za-z0-9_\\\\-]{20,})").matcher(html);
        if (m.find()) return m.group(1);
        lastError = "no channel id on page";
        return null;
    }

    /**
     * Search YouTube for channels; returns up to 8 [channelId, title] pairs.
     * Uses the desktop UA because the mobile shell carries no SSR data.
     */
    public static ArrayList<String[]> searchChannels(String query) {
        ArrayList<String[]> out = new ArrayList<String[]>();
        lastError = "";
        try {
            String enc = java.net.URLEncoder.encode(query, "UTF-8");
            String url = "https://www.youtube.com/results?search_query=" + enc + "&sp=EgIQAg%3D%3D";
            // Try desktop UA first (SSR data), then mobile UA as fallback —
            // YouTube sometimes answers bare requests with a consent page.
            String html = Fetch.getDesktop(url);
            if (countChannelIds(html) == 0) {
                html = Fetch.get(url);
            }
            if (html == null) {
                lastError = "search fetch failed HTTP " + Fetch.lastCode;
                return out;
            }
            Matcher m = Pattern.compile(
                    "\\\"channelId\\\":\\\"(UC[A-Za-z0-9_\\\\-]{10,})\\\",\\\"title\\\":\\{\\\"simpleText\\\":\\\"((?:[^\\\"\\\\\\\\]|\\\\\\\\.)*)\\\"\\}")
                    .matcher(html);
            java.util.LinkedHashMap<String, String> seen = new java.util.LinkedHashMap<String, String>();
            while (m.find()) {
                String id = m.group(1);
                String title = unescapeJson(m.group(2)).trim();
                if (!seen.containsKey(id) && !title.isEmpty()) seen.put(id, title);
            }
            for (java.util.Map.Entry<String, String> e : seen.entrySet()) {
                out.add(new String[]{e.getKey(), e.getValue()});
                if (out.size() >= 8) break;
            }
            if (out.isEmpty()) lastError = "search ok but 0 parsed HTTP " + Fetch.lastCode;
            else lastError = "search ok, " + out.size() + " results";
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
        }
        return out;
    }

    private static int countChannelIds(String html) {
        if (html == null) return 0;
        Matcher m = Pattern.compile("\\\"channelId\\\":\\\"UC").matcher(html);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private static String unescapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 'u' && i + 4 < s.length()) {
                    try {
                        sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                    } catch (Exception e) {
                        sb.append('u');
                    }
                } else if (n == 'n') {
                    sb.append('\n');
                } else {
                    sb.append(n); // \" \\ \/
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String feedUrlForChannel(String channelId) {
        // The feed 404s without an extra query param; hl=en satisfies it.
        return "https://www.youtube.com/feeds/videos.xml?channel_id="
                + channelId + "&hl=en";
    }
}

/** HTTPS GET with a browser UA (YouTube gates odd user agents). */
final class Fetch {

    static final String UA = "Mozilla/5.0 (Linux; Android 13; SM-S918B) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

    // Desktop UA: YouTube serves SSR data (search results, channel ids) only
    // to desktop clients; mobile UAs get an empty JS shell.
    static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    static int lastCode;

    private Fetch() {}

    static String get(String urlString) {
        return getWithUa(urlString, UA);
    }

    static String getDesktop(String urlString) {
        return getWithUa(urlString, DESKTOP_UA);
    }

    private static String getWithUa(String urlString, String ua) {
        HttpURLConnection con = null;
        lastCode = 0;
        try {
            URL url = new URL(urlString);
            con = (HttpURLConnection) url.openConnection();
            con.setRequestProperty("User-Agent", ua);
            con.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            con.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            con.setConnectTimeout(12000);
            con.setReadTimeout(15000);
            con.setInstanceFollowRedirects(true);
            int code = con.getResponseCode();
            lastCode = code;
            if (code < 200 || code >= 300) return null;
            return readAll(new BufferedInputStream(con.getInputStream()));
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) buf.write(b, 0, n);
        return new String(buf.toByteArray(), "UTF-8");
    }
}

/** Persists the last successfully downloaded feed per channel. */
final class CacheStore {

    private CacheStore() {}

    public static String load(android.content.Context c, String key) {
        File f = c.getFileStreamPath(fileFor(key));
        if (!f.exists()) return null;
        try {
            FileInputStream fis = new FileInputStream(f);
            try {
                return Fetch.readAll(new BufferedInputStream(fis));
            } finally {
                fis.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static void save(android.content.Context c, String key, String xml) {
        if (xml == null) return;
        String name = fileFor(key);
        File tmp = new File(c.getFilesDir(), name + ".tmp");
        File out = c.getFileStreamPath(name);
        try {
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                fos.write(xml.getBytes("UTF-8"));
            } finally {
                fos.close();
            }
            if (out.exists()) out.delete();
            if (!tmp.renameTo(out)) {
                FileInputStream fis = new FileInputStream(tmp);
                FileOutputStream fos2 = new FileOutputStream(out);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) > 0) fos2.write(buf, 0, n);
                } finally {
                    fis.close();
                    fos2.close();
                }
                tmp.delete();
            }
        } catch (Exception e) {
            // Cache write failure is non-fatal; the app keeps running.
        }
    }

    private static String fileFor(String key) {
        return "tb_" + key.replaceAll("[^A-Za-z0-9_@]", "_") + ".xml";
    }
}
