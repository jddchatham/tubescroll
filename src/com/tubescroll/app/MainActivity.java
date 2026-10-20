package com.tubescroll.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    // Preset channels (handles resolve automatically at first load).
    private static final String[][] PRESETS = {
        {"NYPREPPER", "@NYPrepper"},
        {"CANADIAN PREPPER", "@CanadianPrepper"},
    };

    private static final int SORT_NEW = 0;
    private static final int SORT_RND = 1;

    private final ExecutorService io = Executors.newFixedThreadPool(2);

    private ArticlePager pager;
    private FrameLayout root;
    private View hintView;
    private LinearLayout errorView;
    private TextView errorTitle;
    private Button errorButton;
    private TextView offlinePill;
    private TextView loadingPill;
    private HorizontalScrollView catScroll;
    private LinearLayout addRow;
    private EditText addInput;
    private ArrayList<String> customs = new ArrayList<String>();
    private TextView sortNewPill;
    private TextView sortRndPill;
    private TextView refreshPill;
    private TextView downloadPill;

    private final java.util.Random random = new java.util.Random();
    private ArrayList<Tube> shuffled = null;
    private int feedGen = 0;
    private int shuffledGen = -1;

    private int pendingStartPage = -1;
    private boolean firstPageEvent = true;

    private LinearLayout topBar;

    private ImageLoader images;
    private SharedPreferences prefs;

    private final ArrayList<Tube> posts = new ArrayList<Tube>();
    private final HashMap<String, String> idCache = new HashMap<String, String>();
    private final HashMap<String, String> chnames = new HashMap<String, String>();

    private final ArrayList<PageRef> pageRefs = new ArrayList<PageRef>();
    private ArrayList<Tube> visible = new ArrayList<Tube>();

    private String currentKey = "@NYPrepper";
    private boolean currentIsCustom = false;
    private int sortMode = SORT_NEW;
    private boolean loading = false;
    private boolean renderedOnce = false;
    private int reqSeq = 0;

    private static final int ACCENT = 0xFFFF3333;
    private static final String TERMUX_SCRIPT =
            "file:///data/data/com.termux/files/home/bin/tube-dl";

    private static class PageRef {
        Tube post;
        ImageView image;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("tubescroll", MODE_PRIVATE);
        images = new ImageLoader(this);

        String saved = prefs.getString("lastChannel", "@NYPrepper");
        boolean ok = false;
        for (String[] p : PRESETS) {
            if (p[1].equalsIgnoreCase(saved)) { currentKey = p[1]; currentIsCustom = false; ok = true; break; }
        }
        loadCustoms();
        if (!ok && containsIgnoreCase(customs, saved)) {
            currentKey = saved;
            currentIsCustom = true;
        }
        // Restore resolved channel ids and display names.
        String ids = prefs.getString("chids", "");
        if (!ids.isEmpty()) {
            for (String line : ids.split("\n")) {
                int i = line.indexOf('=');
                if (i > 0) idCache.put(line.substring(0, i), line.substring(i + 1));
            }
        }
        String names = prefs.getString("chnames", "");
        if (!names.isEmpty()) {
            for (String line : names.split("\n")) {
                int i = line.indexOf('=');
                if (i > 0) chnames.put(line.substring(0, i), line.substring(i + 1));
            }
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0E11);

        pager = new ArticlePager(this);
        pager.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        pager.setOnPageChangeListener(new ArticlePager.OnPageChangeListener() {
            @Override
            public void onPageChanged(int page) {
                if (firstPageEvent) {
                    firstPageEvent = false;
                    return;
                }
                hideHint();
                updateVisibleImages(page);
            }
        });
        pager.setOnPullListener(new ArticlePager.OnPullListener() {
            @Override
            public void onPullRefresh() {
                if (!loading) loadFeed(false);
            }
        });
        root.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildTopBar();
        root.addView(buildOfflinePill());
        loadingPill = buildLoadingPill();
        root.addView(loadingPill);
        errorView = buildErrorView();
        errorView.setVisibility(View.GONE);
        root.addView(errorView);

        if (!prefs.getBoolean("hintShown", false)) {
            hintView = buildHint();
            root.addView(hintView);
        }

        setContentView(root);

        String cached = CacheStore.load(this, cacheKey());
        if (cached != null) {
            Tube.Listing l = Tube.parseFeed(cached, currentKey);
            if (l != null) fillPosts(l);
        }
        loadFeed(false);
    }

    // ------------------------------------------------------------------ data

    private String cacheKey() {
        String id = idCache.get(currentKey);
        return currentKey + "_" + (id == null ? "unresolved" : id);
    }

    private void loadCustoms() {
        customs.clear();
        String raw = prefs.getString("channels", "");
        if (!raw.isEmpty()) {
            for (String q : raw.split("\n")) {
                String t = q.trim();
                if (!t.isEmpty()) customs.add(t);
            }
        }
    }

    private void saveCustoms() {
        StringBuilder sb = new StringBuilder();
        for (String q : customs) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(q.replace("\n", " ").trim());
        }
        prefs.edit().putString("channels", sb.toString()).apply();
    }

    private void saveIdCache() {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : idCache.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        prefs.edit().putString("chids", sb.toString()).apply();
    }

    private static boolean containsIgnoreCase(ArrayList<String> list, String s) {
        for (String x : list) if (x.equalsIgnoreCase(s)) return true;
        return false;
    }

    private void loadFeed(final boolean forceResolve) {
        if (loading) return;
        loading = true;
        setLoadingPill(renderedOnce, "LOADING\u2026");
        final int seq = ++reqSeq;
        final String key = currentKey;
        io.execute(new Runnable() {
            @Override
            public void run() {
                String id = idCache.get(key);
                if (id == null || forceResolve) {
                    id = Tube.resolveChannelId(key);
                    if (id != null) {
                        idCache.put(key, id);
                        saveIdCache();
                    }
                }
                final String resolvedId = id;
                Tube.Listing listing = null;
                String xml = null;
                if (id != null) {
                    xml = Fetch.get(Tube.feedUrlForChannel(id));
                    listing = xml == null ? null : Tube.parseFeed(xml, key);
                }
                final String body = xml;
                final Tube.Listing result = listing;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        setLoadingPill(false, null);
                        if (seq != reqSeq) return; // stale
                        if (result == null) {
                            if (resolvedId == null) {
                                showError("Can\u2019t find channel \u00B7 " + Tube.lastError, "RETRY");
                            } else {
                                onFailure();
                            }
                        } else {
                            offlinePill.setVisibility(View.GONE);
                            CacheStore.save(MainActivity.this, cacheKey(), body);
                            boolean hadContent = !posts.isEmpty() || !visible.isEmpty();
                            fillPosts(result);
                            if (posts.isEmpty() && !hadContent) {
                                showError("0 videos \u00B7 HTTP " + Fetch.lastCode
                                                + " \u00B7 " + Tube.lastError, "RETRY");
                                return;
                            }
                        }
                    }
                });
            }
        });
    }

    private void fillPosts(Tube.Listing l) {
        posts.clear();
        feedGen++;
        for (Tube t : l.posts) posts.add(t);
        rebuild(false);
    }

    private void onFailure() {
        if (!posts.isEmpty()) {
            flashOfflinePill();
            return;
        }
        final String why = Fetch.lastCode == 0
                ? "no connection (DNS/TLS)" : "HTTP " + Fetch.lastCode;
        showError("Can\u2019t reach YouTube \u00B7 " + why + " \u00B7 " + Tube.lastError, "RETRY");
    }

    private void onRefreshTap() {
        if (loading) return;
        refreshPill.animate().rotationBy(360).setDuration(500).start();
        loadFeed(false);
    }

    // -------------------------------------------------------------- rendering

    private void rebuild(boolean keepPosition) {
        String keepLink = null;
        if (keepPosition && visible.size() > 0) {
            int idx = Math.min(pager.getCurrentPage(), visible.size() - 1);
            keepLink = visible.get(idx).link;
        }

        visible = computeVisible();

        pager.removeAllViews();
        pageRefs.clear();
        for (Tube t : visible) {
            pager.addView(buildPage(t));
        }

        int target = 0;
        if (pendingStartPage >= 0) {
            target = Math.min(pendingStartPage, Math.max(0, visible.size() - 1));
            pendingStartPage = -1;
        } else if (keepLink != null) {
            for (int i = 0; i < visible.size(); i++) {
                if (keepLink.equals(visible.get(i).link)) { target = i; break; }
            }
        }
        pager.setPage(target, false);
        pager.requestLayout();
        updateVisibleImages(target);
        renderedOnce = true;
        errorView.setVisibility(View.GONE);
        root.bringChildToFront(errorView);
        root.bringChildToFront(topBar);
        if (hintView != null) root.bringChildToFront(hintView);
    }

    private ArrayList<Tube> computeVisible() {
        if (sortMode == SORT_NEW) return Tube.sortByDate(posts);
        if (shuffled == null || shuffledGen != feedGen) {
            shuffled = Tube.shuffledCopy(posts, random);
            shuffledGen = feedGen;
        }
        return new ArrayList<Tube>(shuffled);
    }

    private void updateVisibleImages(int center) {
        for (int i = 0; i < pageRefs.size(); i++) {
            PageRef r = pageRefs.get(i);
            if (Math.abs(i - center) <= 1) {
                images.load(r.image, r.post.imageUrl);
            } else {
                r.image.setImageDrawable(null);
            }
        }
    }

    private View buildPage(final Tube t) {
        FrameLayout page = new FrameLayout(this);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(0xFF14181C);
        page.addView(img, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x59000000, 0x00000000, 0x55000000, 0xF2000000}));
        page.addView(shade, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.BOTTOM);
        int pad = dp(22);
        box.setPadding(pad, pad, pad, dp(30));

        TextView brand = new TextView(this);
        brand.setText((t.channel.isEmpty() ? "YOUTUBE" : t.channel.toUpperCase())
                + "  \u00B7  TUBESCROLL");
        brand.setTextColor(ACCENT);
        brand.setTextSize(11);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setLetterSpacing(0.18f);
        box.addView(brand);

        TextView title = new TextView(this);
        title.setText(t.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(4);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(0, dp(10), 0, 0);
        box.addView(title, tlp);

        StringBuilder meta = new StringBuilder();
        if (t.views > 0) meta.append(Tube.formatCount(t.views)).append(" views");
        if (t.dateText != null && !t.dateText.isEmpty()) {
            if (meta.length() > 0) meta.append("   \u00B7   ");
            meta.append(t.dateText);
        }
        TextView date = new TextView(this);
        date.setText(meta.toString());
        date.setTextColor(0xFFA9B6BF);
        date.setTextSize(12);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.setMargins(0, dp(6), 0, 0);
        box.addView(date, dlp);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);

        Button watch = makeSmallButton("WATCH  \u25B6", ACCENT, 0xFF0B0E11);
        watch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openLink(t.link);
            }
        });
        btnRow.addView(watch);

        Button dl = makeSmallButton("\u2B07 DOWNLOAD", 0xFF232B34, 0xFFD6DEE4);
        LinearLayout.LayoutParams dllp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(42));
        dllp.setMargins(dp(10), 0, 0, 0);
        dl.setLayoutParams(dllp);
        dl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askDownload(t.link, t.title);
            }
        });
        btnRow.addView(dl);

        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        brlp.setMargins(0, dp(14), 0, 0);
        box.addView(btnRow, brlp);

        page.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        PageRef ref = new PageRef();
        ref.post = t;
        ref.image = img;
        pageRefs.add(ref);
        return page;
    }

    private Button makeSmallButton(String text, int bgColor, int textColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(textColor);
        b.setTextSize(12);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(8));
        b.setBackground(bg);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    // ------------------------------------------------------------------ downloads

    /** MP4 or MP3 -> Termux yt-dlp; clipboard fallback if Termux unreachable. */
    private void askDownload(final String url, final String label) {
        String name = label == null ? url : (label.length() > 42 ? label.substring(0, 42) + "\u2026" : label);
        // NOTE: setMessage + setItems fight on Material themes (message wins,
        // list disappears) — put the label in the title instead.
        new AlertDialog.Builder(this)
                .setTitle("Download \u2014 " + name)
                .setItems(new String[]{"Video (MP4, up to 1080p)", "Audio only (MP3)"},
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which) {
                                runDownload(url, which == 0 ? "mp4" : "mp3");
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Queue the download: drop a queue file into the shared Download folder;
     * the Termux tube-queue-daemon picks it up and runs yt-dlp. Clipboard is
     * the fallback; RUN_COMMAND is attempted too (works after Termux reloads
     * its config once).
     */
    private void runDownload(String url, String kind) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                // Literal column names: this compile jar's MediaStore lacks the
                // nested Downloads class, but the runtime values are standard.
                android.content.ContentValues v = new android.content.ContentValues();
                v.put("_display_name", "tubequeue-" + System.currentTimeMillis() + ".txt");
                v.put("mime_type", "text/plain");
                v.put("relative_path", "Download");
                Uri collection = Uri.parse("content://media/external_primary/downloads");
                Uri u = getContentResolver().insert(collection, v);
                if (u != null) {
                    java.io.OutputStream os = getContentResolver().openOutputStream(u);
                    os.write((url + "|" + kind).getBytes("UTF-8"));
                    os.close();
                    Toast.makeText(this,
                            "Queued \u2713 \u2014 notification when it lands in your Download folder",
                            Toast.LENGTH_LONG).show();
                    return;
                }
            } catch (Exception ignored) { }
        }
        // Fallback: stage the command on the clipboard and open Termux.
        String cmd = "tube-dl \"" + url + "\" " + kind;
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("cmd", cmd));
        try {
            Intent i = new Intent();
            i.setClassName("com.termux", "com.termux.app.TermuxService");
            i.setAction("com.termux.service_execute");
            i.setData(Uri.parse(TERMUX_SCRIPT));
            i.putExtra("arguments", new String[]{url, kind});
            i.putExtra("workdir", "/data/data/com.termux/files/home");
            startService(i);
        } catch (Exception e) {
            try {
                startActivity(getPackageManager().getLaunchIntentForPackage("com.termux"));
            } catch (Exception ignored) { }
        }
        Toast.makeText(this,
                "Command copied \u2014 open Termux, long-press \u2192 Paste \u2192 Enter",
                Toast.LENGTH_LONG).show();
    }

    /** Paste any URL (YouTube, TikTok, anything yt-dlp knows) and download it. */
    private void askDownloadFromInput() {
        final EditText input = new EditText(this);
        input.setHint("Paste a link \u2014 YouTube, TikTok, anything yt-dlp knows");
        input.setHintTextColor(0xFF7C8A94);
        input.setTextColor(Color.WHITE);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        FrameLayout wrap = new FrameLayout(this);
        int p = dp(18);
        input.setPadding(p, p, p, 0);
        wrap.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Download a link")
                .setView(wrap)
                .setPositiveButton("Next", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        String url = input.getText().toString().trim();
                        if (!url.isEmpty()) askDownload(url, url);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------- top bar

    private void buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        topBar = bar;
        bar.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(10);
        bar.setPadding(pad, dp(12), pad, 0);

        bar.addView(buildCatRow(), 0);
        bar.addView(buildSortRow());

        addRow = buildAddRow();
        addRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        alp.setMargins(0, dp(8), 0, 0);
        addRow.setLayoutParams(alp);
        bar.addView(addRow);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        root.addView(bar, lp);
        stylePills();
    }

    private HorizontalScrollView buildCatRow() {
        catScroll = new HorizontalScrollView(this);
        catScroll.setHorizontalScrollBarEnabled(false);
        catScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout catRow = new LinearLayout(this);
        catRow.setOrientation(LinearLayout.HORIZONTAL);
        for (String[] p : PRESETS) {
            catRow.addView(makeCatPill(p[0], p[1], false));
        }
        for (String q : customs) {
            catRow.addView(makeCatPill(prettyChannel(q), q, true));
        }
        catRow.addView(makePlusPill());
        catScroll.addView(catRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        catScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));
        return catScroll;
    }

    private String prettyChannel(String key) {
        String named = chnames.get(key);
        if (named != null && !named.isEmpty()) return named.toUpperCase();
        String s = key;
        int i = s.lastIndexOf('/');
        if (i >= 0) s = s.substring(i + 1);
        if (s.startsWith("@")) s = s.substring(1);
        if (s.startsWith("UC") && s.length() > 20) return "CHANNEL";
        return s.toUpperCase();
    }

    private TextView makeCatPill(String label, final String key, final boolean isCustom) {
        TextView pill = new TextView(this);
        pill.setText(label);
        pill.setTextSize(12);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setGravity(Gravity.CENTER);
        pill.setSingleLine(true);
        pill.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pill.setMaxWidth(dp(150));
        pill.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
        plp.setMargins(0, 0, dp(6), 0);
        pill.setLayoutParams(plp);
        pill.setTag(key);
        pill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setChannel(key, isCustom); }
        });
        if (isCustom) {
            pill.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    customs.remove(key);
                    saveCustoms();
                    Toast.makeText(MainActivity.this, "Removed " + prettyChannel(key),
                            Toast.LENGTH_SHORT).show();
                    rebuildCatRow();
                    if (currentIsCustom && currentKey.equals(key)) {
                        setChannel(PRESETS[0][1], false);
                    }
                    return true;
                }
            });
        }
        return pill;
    }

    private TextView makePlusPill() {
        TextView pill = makePill("+");
        LinearLayout.LayoutParams plp = (LinearLayout.LayoutParams) pill.getLayoutParams();
        plp.setMargins(0, 0, 0, 0);
        stylePill(pill, false);
        pill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { expandAdd(); }
        });
        return pill;
    }

    private LinearLayout buildSortRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34));
        rlp.setMargins(0, dp(8), 0, 0);
        row.setLayoutParams(rlp);

        sortNewPill = makePill("NEW");
        sortNewPill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setSort(SORT_NEW); }
        });
        row.addView(sortNewPill);

        sortRndPill = makePill("RND");
        LinearLayout.LayoutParams qlp = pillMargins();
        sortRndPill.setLayoutParams(qlp);
        sortRndPill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setSort(SORT_RND); }
        });
        row.addView(sortRndPill);

        View spacer = new View(this);
        row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        downloadPill = makePill("\u2B07");
        downloadPill.setLayoutParams(pillMargins());
        downloadPill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askDownloadFromInput(); }
        });
        row.addView(downloadPill);

        refreshPill = makePill("\u27F3");
        LinearLayout.LayoutParams rflp = pillMargins();
        rflp.setMargins(dp(6), 0, 0, 0);
        refreshPill.setLayoutParams(rflp);
        refreshPill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onRefreshTap(); }
        });
        row.addView(refreshPill);
        return row;
    }

    private LinearLayout.LayoutParams pillMargins() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
        lp.setMargins(dp(6), 0, 0, 0);
        return lp;
    }

    private LinearLayout buildAddRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        addInput = new EditText(this);
        addInput.setHint("Add channel \u2014 @handle, name, or youtube link");
        addInput.setHintTextColor(0xFF7C8A94);
        addInput.setTextColor(Color.WHITE);
        addInput.setTextSize(13);
        addInput.setSingleLine(true);
        addInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF232B34);
        bg.setCornerRadius(dp(17));
        bg.setStroke(dp(1), 0x4DFFFFFF);
        addInput.setBackground(bg);
        addInput.setPadding(dp(14), 0, dp(14), 0);
        row.addView(addInput, new LinearLayout.LayoutParams(0, dp(38), 1f));

        TextView go = makePill("ADD");
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(38));
        glp.setMargins(dp(8), 0, 0, 0);
        go.setLayoutParams(glp);
        stylePill(go, true);
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { submitAdd(); }
        });
        row.addView(go);

        TextView close = makePill("\u2715");
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(38));
        clp.setMargins(dp(8), 0, 0, 0);
        close.setLayoutParams(clp);
        stylePill(close, false);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { collapseAdd(); }
        });
        row.addView(close);

        addInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                submitAdd();
                return true;
            }
        });
        return row;
    }

    private TextView makePill(String text) {
        TextView pill = new TextView(this);
        pill.setText(text);
        pill.setTextSize(12);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
        pill.setLayoutParams(lp);
        return pill;
    }

    private void stylePill(TextView pill, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        if (selected) {
            bg.setColor(ACCENT);
            pill.setTextColor(0xFF0B0E11);
        } else {
            bg.setColor(0x99262B33);
            pill.setTextColor(0xFFD6DEE4);
        }
        bg.setCornerRadius(dp(17));
        pill.setBackground(bg);
    }

    private void stylePills() {
        LinearLayout catRow = (LinearLayout) catScroll.getChildAt(0);
        for (int i = 0; i < catRow.getChildCount(); i++) {
            View v = catRow.getChildAt(i);
            if (!(v instanceof TextView)) continue;
            String tag = (String) v.getTag();
            if (tag != null) stylePill((TextView) v, tag.equals(currentKey));
        }
        stylePill(sortNewPill, sortMode == SORT_NEW);
        stylePill(sortRndPill, sortMode == SORT_RND);
    }

    private void rebuildCatRow() {
        topBar.removeView(catScroll);
        topBar.addView(buildCatRow(), 0);
        stylePills();
    }

    private void setChannel(String key, boolean isCustom) {
        if (key.equals(currentKey) && isCustom == currentIsCustom) return;
        currentKey = key;
        currentIsCustom = isCustom;
        prefs.edit().putString("lastChannel", key).apply();
        posts.clear();
        feedGen++;
        String cached = CacheStore.load(this, cacheKey());
        if (cached != null) {
            Tube.Listing l = Tube.parseFeed(cached, key);
            if (l != null) {
                fillPosts(l);
            }
        } else {
            pager.removeAllViews();
            pageRefs.clear();
            visible = new ArrayList<Tube>();
            renderedOnce = false;
        }
        stylePills();
        loadFeed(false);
    }

    private void expandAdd() {
        addRow.setVisibility(View.VISIBLE);
        addInput.requestFocus();
        addInput.post(new Runnable() {
            @Override public void run() {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(addInput, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void submitAdd() {
        final String q = addInput.getText().toString().trim();
        hideKeyboard();
        if (q.isEmpty()) return;
        if (q.length() > 80) { addChannelDirect(q.substring(0, 80)); return; }
        // Search YouTube for channels matching the text, then let the user pick.
        setLoadingPill(true, "SEARCHING CHANNELS\u2026");
        io.execute(new Runnable() {
            @Override
            public void run() {
                final ArrayList<String[]> results = Tube.searchChannels(q);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setLoadingPill(false, null);
                        if (results.isEmpty()) {
                            Toast.makeText(MainActivity.this,
                                    "Search: " + Tube.lastError + " \u2014 trying direct",
                                    Toast.LENGTH_LONG).show();
                            addChannelDirect(q);
                        } else {
                            showChannelPicker(results);
                        }
                    }
                });
            }
        });
    }

    private void showChannelPicker(final ArrayList<String[]> results) {
        final String[] labels = new String[results.size()];
        for (int i = 0; i < results.size(); i++) {
            labels[i] = results.get(i)[1];
        }
        new AlertDialog.Builder(this)
                .setTitle("Pick a channel")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        String id = results.get(which)[0];
                        String name = results.get(which)[1];
                        chnames.put(id, name);
                        saveChNames();
                        if (!customs.contains(id)) {
                            customs.add(id);
                            saveCustoms();
                        }
                        addInput.setText("");
                        addRow.setVisibility(View.GONE);
                        rebuildCatRow();
                        setChannel(id, true);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveChNames() {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : chnames.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.getKey()).append('=').append(e.getValue().replace("\n", " "));
        }
        prefs.edit().putString("chnames", sb.toString()).apply();
    }

    /** Old behavior: interpret the text as a handle / link and add directly. */
    private void addChannelDirect(String q) {
        if (q.startsWith("https://")) {
            int at = q.indexOf(".com/");
            if (at > 0) q = q.substring(at + 5);
            while (q.startsWith("/")) q = q.substring(1);
            int slash = q.indexOf('/');
            if (slash > 0) q = q.substring(0, slash);
        }
        if (!q.startsWith("@") && !q.startsWith("UC")) q = "@" + q;
        if (!containsIgnoreCase(customs, q) && !isPreset(q)) {
            customs.add(q);
            saveCustoms();
        }
        addInput.setText("");
        addRow.setVisibility(View.GONE);
        rebuildCatRow();
        setChannel(q, true);
    }

    private boolean isPreset(String key) {
        for (String[] p : PRESETS) if (p[1].equalsIgnoreCase(key)) return true;
        return false;
    }

    private void collapseAdd() {
        hideKeyboard();
        addInput.setText("");
        addRow.setVisibility(View.GONE);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && addInput != null) {
            imm.hideSoftInputFromWindow(addInput.getWindowToken(), 0);
        }
    }

    private void setSort(int mode) {
        if (sortMode == mode && mode != SORT_RND) return;
        sortMode = mode;
        if (mode == SORT_RND) {
            shuffled = null;
            shuffledGen = -1;
            if (!posts.isEmpty()) pendingStartPage = random.nextInt(posts.size());
        }
        stylePills();
        rebuild(false);
    }

    // ------------------------------------------------------------------ overlays

    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show();
        }
    }

    private TextView buildOfflinePill() {
        offlinePill = new TextView(this);
        offlinePill.setText("OFFLINE \u00B7 SHOWING CACHED");
        offlinePill.setTextColor(0xFFD6DEE4);
        offlinePill.setTextSize(11);
        offlinePill.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB3262B33);
        bg.setCornerRadius(dp(14));
        offlinePill.setBackground(bg);
        int p = dp(10);
        offlinePill.setPadding(p, dp(6), p, dp(6));
        offlinePill.setAlpha(0f);
        offlinePill.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.setMargins(0, dp(130), 0, 0);
        offlinePill.setLayoutParams(lp);
        return offlinePill;
    }

    private void flashOfflinePill() {
        offlinePill.setVisibility(View.VISIBLE);
        offlinePill.animate().alpha(1f).setDuration(200).start();
        offlinePill.postDelayed(new Runnable() {
            @Override
            public void run() {
                offlinePill.animate().alpha(0f).setDuration(600).start();
            }
        }, 2500);
    }

    private TextView buildLoadingPill() {
        TextView pill = new TextView(this);
        pill.setText("LOADING\u2026");
        pill.setTextColor(0xFF0B0E11);
        pill.setTextSize(11);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ACCENT);
        bg.setCornerRadius(dp(14));
        pill.setBackground(bg);
        pill.setPadding(dp(12), dp(6), dp(12), dp(6));
        pill.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.setMargins(0, 0, 0, dp(24));
        pill.setLayoutParams(lp);
        return pill;
    }

    private void setLoadingPill(boolean show, String text) {
        if (text != null) loadingPill.setText(text);
        loadingPill.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private View buildHint() {
        TextView hint = new TextView(this);
        hint.setText("Swipe up for the next video \u2191 \u00B7 \u2B07 downloads via Termux");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(14);
        hint.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC1C232B);
        bg.setCornerRadius(dp(18));
        hint.setBackground(bg);
        hint.setPadding(dp(20), dp(10), dp(20), dp(10));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.setMargins(0, dp(165), 0, 0);
        hint.setLayoutParams(lp);
        return hint;
    }

    private void hideHint() {
        if (hintView == null) return;
        prefs.edit().putBoolean("hintShown", true).apply();
        final View h = hintView;
        hintView = null;
        h.animate().alpha(0f).setDuration(300).start();
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                root.removeView(h);
            }
        }, 350);
    }

    private LinearLayout buildErrorView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xFF0B0E11);
        int pad = dp(32);
        box.setPadding(pad, pad, pad, pad);

        errorTitle = new TextView(this);
        errorTitle.setText("Can\u2019t reach YouTube");
        errorTitle.setTextColor(Color.WHITE);
        errorTitle.setTextSize(20);
        errorTitle.setTypeface(Typeface.DEFAULT_BOLD);
        errorTitle.setGravity(Gravity.CENTER);
        box.addView(errorTitle);

        TextView sub = new TextView(this);
        sub.setText("Check your connection and try again.");
        sub.setTextColor(0xFFA9B6BF);
        sub.setTextSize(14);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.setMargins(0, dp(8), 0, 0);
        box.addView(sub, slp);

        errorButton = new Button(this);
        errorButton.setText("RETRY");
        errorButton.setTextColor(0xFF0B0E11);
        errorButton.setTextSize(13);
        errorButton.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ACCENT);
        bg.setCornerRadius(dp(8));
        errorButton.setBackground(bg);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(160), dp(46));
        rlp.setMargins(0, dp(24), 0, 0);
        box.addView(errorButton, rlp);
        return box;
    }

    private void showError(String title, String buttonText) {
        errorTitle.setText(title);
        errorButton.setText(buttonText);
        errorButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                errorView.setVisibility(View.GONE);
                loadFeed(false);
            }
        });
        errorView.setVisibility(View.VISIBLE);
        root.bringChildToFront(topBar);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
