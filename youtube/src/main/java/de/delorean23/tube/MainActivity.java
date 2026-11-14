package de.delorean23.tube;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bumptech.glide.Glide;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.kiosk.KioskInfo;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Startseite mit Kategorien, Abos und Bibliothek. Created by DeLorean23 */
public class MainActivity extends AppCompatActivity {

    private static final int TAB_HOME = 0, TAB_SUBS = 1, TAB_LIB = 2, TAB_SEARCH = 3;

    private RecyclerView list;
    private ItemAdapter adapter;
    private SwipeRefreshLayout refresh;
    private ProgressBar progress;
    private View emptyBox, chipScroll, topBar, searchBar;
    private TextView emptyText;
    private Button emptyButton;
    private EditText searchField;
    private ImageButton btnAccount;
    private LinearLayout chips;
    private TextView[] tabs;
    private MiniPlayer mini;
    private View suggestScroll;
    private LinearLayout suggestBox;
    private int suggestToken;
    private final Runnable suggestRunnable = this::loadSuggestions;

    private int tab = TAB_HOME;
    private int loadToken = 0;
    private boolean loadingMore;

    // Start: Kategorien
    private final Map<String, String> kiosks = new LinkedHashMap<>();
    private String kiosk;
    private Page kioskPage;

    // Suche
    private SearchQueryHandler searchQuery;
    private Page searchPage;

    private ActivityResultLauncher<IntentSenderRequest> loginLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        list = findViewById(R.id.list);
        refresh = findViewById(R.id.refresh);
        progress = findViewById(R.id.progress);
        emptyBox = findViewById(R.id.emptyBox);
        emptyText = findViewById(R.id.emptyText);
        emptyButton = findViewById(R.id.emptyButton);
        chipScroll = findViewById(R.id.chipScroll);
        chips = findViewById(R.id.chips);
        topBar = findViewById(R.id.topBar);
        searchBar = findViewById(R.id.searchBar);
        searchField = findViewById(R.id.searchField);
        btnAccount = findViewById(R.id.btnAccount);

        adapter = new ItemAdapter(this, this::open);
        list.setLayoutManager(ItemAdapter.layout(this, adapter));
        list.setAdapter(adapter);
        adapter.setOnNearEnd(this::loadMore);
        adapter.setOnLongClick(this::longPress);
        suggestScroll = findViewById(R.id.suggestScroll);
        suggestBox = findViewById(R.id.suggestBox);

        refresh.setColorSchemeResources(R.color.accent);
        refresh.setProgressBackgroundColorSchemeResource(R.color.card);
        refresh.setOnRefreshListener(() -> load(true));

        loginLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                    if (result.getData() != null) {
                        Account.handleResult(this, result.getData(), this::onLogin);
                    } else {
                        onLogin(false, "Anmeldung abgebrochen");
                    }
                });

        tabs = new TextView[]{findViewById(R.id.tabHome), findViewById(R.id.tabSubs),
                findViewById(R.id.tabLibrary)};
        for (int i = 0; i < tabs.length; i++) {
            final int t = i;
            tabs[i].setOnClickListener(v -> selectTab(t));
        }

        findViewById(R.id.btnSearch).setOnClickListener(v -> showSearch(true));
        findViewById(R.id.btnSearchBack).setOnClickListener(v -> showSearch(false));
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                search(searchField.getText().toString().trim());
                return true;
            }
            return false;
        });
        searchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                if (searchBar.getVisibility() != View.VISIBLE) return;
                App.MAIN.removeCallbacks(suggestRunnable);
                App.MAIN.postDelayed(suggestRunnable, 250);
            }
        });
        searchField.setOnFocusChangeListener((v, has) -> {
            if (has && searchBar.getVisibility() == View.VISIBLE) loadSuggestions();
        });
        btnAccount.setOnClickListener(v -> accountMenu());
        emptyButton.setOnClickListener(v -> login());

        findViewById(R.id.tabShorts).setOnClickListener(v ->
                startActivity(new Intent(this, ShortsActivity.class)));
        mini = new MiniPlayer(this, findViewById(R.id.contentFrame));
        Caster.setupButton(this, findViewById(R.id.castButton));

        setupKiosks();
        updateAccountButton();
        askNotificationPermission();
        selectTab(TAB_HOME);
        CrashLog.showIfAny(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mini.onResume();
        updateAccountButton();
        adapter.refreshProgress();
        if (tab == TAB_LIB) load(false);
    }

    @Override
    protected void onPause() {
        mini.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mini.onStop();
    }

    @Override
    protected void onDestroy() {
        mini.release();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (suggestScroll.getVisibility() == View.VISIBLE && tab == TAB_SEARCH) {
            suggestScroll.setVisibility(View.GONE);
            hideKeyboard();
            return;
        }
        if (searchBar.getVisibility() == View.VISIBLE) {
            showSearch(false);
            return;
        }
        if (tab != TAB_HOME) {
            selectTab(TAB_HOME);
            return;
        }
        super.onBackPressed();
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    // ------------------------------------------------------------ Konto

    private void updateAccountButton() {
        String av = Account.avatar();
        if (Account.isLoggedIn() && av != null) {
            btnAccount.setPadding(dp(10), dp(10), dp(10), dp(10));
            Glide.with(this).load(av).circleCrop().into(btnAccount);
        } else {
            Glide.with(this).clear(btnAccount);
            btnAccount.setPadding(dp(12), dp(12), dp(12), dp(12));
            btnAccount.setImageResource(R.drawable.ic_account);
        }
    }

    private void login() {
        Account.login(this, (PendingIntent pi) ->
                        loginLauncher.launch(new IntentSenderRequest.Builder(pi).build()),
                this::onLogin);
    }

    private void onLogin(boolean ok, String msg) {
        if (!ok) {
            if (msg != null) new AlertDialog.Builder(this).setMessage(msg)
                    .setPositiveButton("OK", null).show();
            return;
        }
        updateAccountButton();
        Toast.makeText(this, "Angemeldet" + (Account.name() != null ? " als " + Account.name() : ""),
                Toast.LENGTH_SHORT).show();
        if (tab == TAB_SUBS || tab == TAB_LIB) load(true);
    }

    private void accountMenu() {
        if (!Account.isLoggedIn()) {
            new AlertDialog.Builder(this)
                    .setItems(new String[]{"Mit Google anmelden", "Einstellungen"}, (d, which) -> {
                        if (which == 0) login();
                        else startActivity(new Intent(this, SettingsActivity.class));
                    })
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(Account.name() != null ? Account.name() : "Google-Konto")
                .setItems(new String[]{"Meine Abos", "Einstellungen", "Abmelden"}, (d, which) -> {
                    if (which == 0) {
                        startActivity(ListActivity.intent(this, ListActivity.MODE_SUBS, null, "Meine Abos"));
                    } else if (which == 1) {
                        startActivity(new Intent(this, SettingsActivity.class));
                    } else {
                        Account.logout();
                        updateAccountButton();
                        load(false);
                    }
                })
                .setNegativeButton("Schließen", null)
                .show();
    }

    // ------------------------------------------------------------ Navigation

    private void selectTab(int t) {
        if (searchBar.getVisibility() == View.VISIBLE) {
            searchBar.setVisibility(View.GONE);
            topBar.setVisibility(View.VISIBLE);
            hideKeyboard();
        }
        suggestScroll.setVisibility(View.GONE);
        tab = t;
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setAlpha(i == t ? 1f : 0.55f);
        }
        chipScroll.setVisibility(t == TAB_HOME ? View.VISIBLE : View.GONE);
        adapter.set(null);
        load(false);
    }

    private void showSearch(boolean show) {
        if (show) {
            topBar.setVisibility(View.GONE);
            searchBar.setVisibility(View.VISIBLE);
            chipScroll.setVisibility(View.GONE);
            searchField.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT);
            loadSuggestions();
        } else {
            hideKeyboard();
            suggestScroll.setVisibility(View.GONE);
            searchBar.setVisibility(View.GONE);
            topBar.setVisibility(View.VISIBLE);
            if (tab == TAB_SEARCH) selectTab(TAB_HOME);
            else chipScroll.setVisibility(tab == TAB_HOME ? View.VISIBLE : View.GONE);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
    }

    private void search(String q) {
        if (q.isEmpty()) return;
        String direct = Util.findYoutubeUrl(q);
        if (direct != null) {
            startActivity(PlayerActivity.intent(this, direct, null));
            return;
        }
        hideKeyboard();
        SearchHistory.add(q);
        suggestScroll.setVisibility(View.GONE);
        searchField.clearFocus();
        tab = TAB_SEARCH;
        try {
            StreamingService yt = ServiceList.YouTube;
            searchQuery = yt.getSearchQHFactory().fromQuery(q, Collections.emptyList(), "");
        } catch (Throwable e) {
            showEmpty("Suche nicht möglich: " + e.getMessage(), false);
            return;
        }
        adapter.set(null);
        load(false);
    }

    private void open(Item it) {
        if (it.url != null && it.url.startsWith("app:")) {
            switch (it.url) {
                case "app:history":
                    startActivity(ListActivity.intent(this, ListActivity.MODE_HISTORY, null, it.title));
                    break;
                case "app:wl":
                    startActivity(ListActivity.intent(this, ListActivity.MODE_LOCAL_PLAYLIST,
                            LocalPlaylists.WATCH_LATER, it.title));
                    break;
                case "app:liked":
                    startActivity(ListActivity.intent(this, ListActivity.MODE_LIKED, null, it.title));
                    break;
                case "app:subs":
                    startActivity(ListActivity.intent(this, ListActivity.MODE_SUBS, null, it.title));
                    break;
                case "app:new":
                    PlaylistDialogs.create(this, null, () -> load(false));
                    break;
                case "app:downloads":
                    startActivity(new Intent(this, DownloadsActivity.class));
                    break;
                case "app:settings":
                    startActivity(new Intent(this, SettingsActivity.class));
                    break;
                case "app:alarms":
                    startActivity(new Intent(this, AlarmsActivity.class));
                    break;
                default:
                    break;
            }
            return;
        }
        if (it.type == Item.VIDEO) {
            PlayQueue.playSingle(it);
            startActivity(PlayerActivity.intent(this, it.url, it));
        } else if (it.type == Item.CHANNEL) {
            startActivity(ListActivity.intent(this, ListActivity.MODE_CHANNEL, it.url, it.title));
        } else if (it.localPlaylistId != null) {
            startActivity(ListActivity.intent(this, ListActivity.MODE_LOCAL_PLAYLIST,
                    it.localPlaylistId, it.title));
        } else if (it.apiPlaylistId != null) {
            startActivity(ListActivity.intent(this, ListActivity.MODE_API_PLAYLIST,
                    it.apiPlaylistId, it.title));
        } else {
            startActivity(ListActivity.intent(this, ListActivity.MODE_PLAYLIST, it.url, it.title));
        }
    }

    /** Langes Drücken: Video-Menü oder eigene Playlist verwalten. */
    private void longPress(Item it) {
        if (it.type == Item.VIDEO) {
            VideoMenu.show(this, it);
        } else if (it.type == Item.PLAYLIST && (it.localPlaylistId != null
                || (it.apiPlaylistId != null && tab == TAB_LIB))) {
            PlaylistDialogs.manage(this, it, () -> load(false));
        }
    }

    // ------------------------------------------------------------ Suchvorschläge

    private void loadSuggestions() {
        if (searchBar.getVisibility() != View.VISIBLE) return;
        final String q = searchField.getText().toString().trim();
        final int token = ++suggestToken;
        if (q.isEmpty()) {
            showSuggestions(SearchHistory.all(), true);
            return;
        }
        // passende Einträge aus dem Verlauf sofort, Vorschläge von YouTube dazu
        final List<String> fromHistory = new ArrayList<>();
        for (String h : SearchHistory.all()) {
            if (h.toLowerCase(java.util.Locale.ROOT).startsWith(q.toLowerCase(java.util.Locale.ROOT))) {
                fromHistory.add(h);
            }
            if (fromHistory.size() >= 3) break;
        }
        showSuggestions(fromHistory, true);
        App.IO.execute(() -> {
            try {
                List<String> sugg = ServiceList.YouTube.getSuggestionExtractor().suggestionList(q);
                App.MAIN.post(() -> {
                    if (token != suggestToken) return;
                    List<String> all = new ArrayList<>(fromHistory);
                    for (String x : sugg) if (!all.contains(x)) all.add(x);
                    showSuggestions(all, false, fromHistory.size());
                });
            } catch (Throwable ignored) {
            }
        });
    }

    private void showSuggestions(List<String> list, boolean allHistory) {
        showSuggestions(list, allHistory, allHistory ? list.size() : 0);
    }

    /** Die ersten historyCount Einträge kommen aus dem Suchverlauf (Uhr-Symbol, löschbar). */
    private void showSuggestions(List<String> list, boolean unused, int historyCount) {
        suggestBox.removeAllViews();
        if (list.isEmpty()) {
            suggestScroll.setVisibility(View.GONE);
            return;
        }
        for (int i = 0; i < list.size() && i < 15; i++) {
            final String q = list.get(i);
            final boolean hist = i < historyCount;
            TextView t = new TextView(this);
            t.setText(q);
            t.setTextSize(16);
            t.setTextColor(getResources().getColor(R.color.text));
            t.setGravity(android.view.Gravity.CENTER_VERTICAL);
            t.setPadding(dp(16), 0, dp(16), 0);
            t.setMinHeight(dp(48));
            t.setCompoundDrawablesWithIntrinsicBounds(hist ? R.drawable.ic_history : R.drawable.ic_search, 0, 0, 0);
            t.setCompoundDrawablePadding(dp(16));
            t.setBackgroundResource(android.R.drawable.list_selector_background);
            t.setOnClickListener(v -> {
                searchField.setText(q);
                searchField.setSelection(q.length());
                search(q);
            });
            if (hist) {
                t.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(this)
                            .setMessage("„" + q + "“ aus dem Suchverlauf löschen?")
                            .setPositiveButton("Löschen", (d, w) -> {
                                SearchHistory.remove(q);
                                loadSuggestions();
                            })
                            .setNegativeButton("Abbrechen", null)
                            .show();
                    return true;
                });
            }
            suggestBox.addView(t, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        suggestScroll.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------ Kategorien

    private void setupKiosks() {
        kiosks.put("trending_music", "Musik");
        kiosks.put("trending_gaming", "Gaming");
        kiosks.put("live", "Live");
        kiosks.put("trending_podcasts_episodes", "Podcasts");
        kiosks.put("trending_movies_and_shows", "Filme & Serien");
        // YouTube hat die alte Trends-Seite abgeschafft; bleibt als letzte Auswahl, falls sie wiederkommt
        kiosks.put("Trending", "Trends");
        try {
            java.util.Set<String> available = ServiceList.YouTube.getKioskList().getAvailableKiosks();
            kiosks.keySet().retainAll(available);
        } catch (Exception ignored) {
        }
        chips.removeAllViews();
        for (Map.Entry<String, String> e : kiosks.entrySet()) {
            TextView c = new TextView(this);
            c.setText(e.getValue());
            c.setTextSize(14);
            c.setGravity(android.view.Gravity.CENTER);
            c.setPadding(dp(14), 0, dp(14), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(32));
            lp.rightMargin = dp(8);
            c.setLayoutParams(lp);
            c.setTag(e.getKey());
            c.setOnClickListener(v -> {
                kiosk = (String) v.getTag();
                updateChips();
                adapter.set(null);
                load(false);
            });
            chips.addView(c);
        }
        if (!kiosks.isEmpty()) kiosk = kiosks.keySet().iterator().next();
        updateChips();
    }

    private void updateChips() {
        for (int i = 0; i < chips.getChildCount(); i++) {
            TextView c = (TextView) chips.getChildAt(i);
            boolean on = c.getTag().equals(kiosk);
            c.setBackgroundResource(on ? R.drawable.chip_on_bg : R.drawable.chip_bg);
            c.setTextColor(getResources().getColor(on ? R.color.black : R.color.text));
        }
    }

    // ------------------------------------------------------------ Laden

    private void showEmpty(String text, boolean loginButton) {
        refresh.setRefreshing(false);
        progress.setVisibility(View.GONE);
        emptyBox.setVisibility(View.VISIBLE);
        emptyText.setText(text);
        emptyButton.setVisibility(loginButton ? View.VISIBLE : View.GONE);
    }

    private void showList(List<Item> items) {
        refresh.setRefreshing(false);
        progress.setVisibility(View.GONE);
        adapter.set(items);
        if (items.isEmpty()) {
            showEmpty(getString(R.string.empty), false);
        } else {
            emptyBox.setVisibility(View.GONE);
        }
    }

    private void load(boolean force) {
        final int token = ++loadToken;
        loadingMore = false;
        emptyBox.setVisibility(View.GONE);
        if (!refresh.isRefreshing() && adapter.size() == 0) progress.setVisibility(View.VISIBLE);

        if (tab == TAB_SUBS && !Account.isLoggedIn()) {
            adapter.set(null);
            showEmpty(getString(R.string.login_needed), true);
            return;
        }
        if (tab == TAB_LIB) {
            List<Item> base = libraryItems();
            base.addAll(libraryFooter());
            showList(base);
            if (Account.isLoggedIn()) {
                App.IO.execute(() -> {
                    try {
                        List<Item> pls = Account.myPlaylists();
                        App.MAIN.post(() -> {
                            if (token != loadToken) return;
                            List<Item> all = libraryItems();
                            all.addAll(pls);
                            all.addAll(libraryFooter());
                            showList(all);
                        });
                    } catch (Throwable e) {
                        App.MAIN.post(() -> toast(e.getMessage()));
                    }
                });
            }
            return;
        }

        App.IO.execute(() -> {
            try {
                List<Item> result = new ArrayList<>();
                if (tab == TAB_HOME) {
                    if (kiosk == null) throw new Exception("Keine Kategorien verfügbar");
                    KioskExtractor ex = ServiceList.YouTube.getKioskList().getExtractorById(kiosk, null);
                    ex.fetchPage();
                    KioskInfo info = KioskInfo.getInfo(ex);
                    for (StreamInfoItem s : info.getRelatedItems()) addItem(result, s);
                    kioskPage = info.hasNextPage() ? info.getNextPage() : null;
                } else if (tab == TAB_SUBS) {
                    boolean hide = Playback.hideShortsInFeed();
                    for (Item it : Feed.load(force)) if (!hide || !it.shorts) result.add(it);
                } else if (tab == TAB_SEARCH) {
                    SearchInfo info = SearchInfo.getInfo(ServiceList.YouTube, searchQuery);
                    for (InfoItem ii : info.getRelatedItems()) addItem(result, ii);
                    searchPage = info.hasNextPage() ? info.getNextPage() : null;
                }
                App.MAIN.post(() -> {
                    if (token == loadToken) showList(result);
                });
            } catch (Throwable e) {
                App.MAIN.post(() -> {
                    if (token != loadToken) return;
                    adapter.set(null);
                    showEmpty(getString(R.string.error_load) + "\n\n" + e.getMessage(),
                            tab == TAB_SUBS && !Account.isLoggedIn());
                });
            }
        });
    }

    private static void addItem(List<Item> out, InfoItem ii) {
        Item it = Item.from(ii);
        if (it != null) out.add(it);
    }

    private void loadMore() {
        if (loadingMore) return;
        final Page page;
        if (tab == TAB_HOME) page = kioskPage;
        else if (tab == TAB_SEARCH) page = searchPage;
        else return;
        if (page == null) return;
        loadingMore = true;
        final int token = loadToken;
        final int t = tab;
        final String k = kiosk;
        final SearchQueryHandler q = searchQuery;
        App.IO.execute(() -> {
            try {
                List<Item> more = new ArrayList<>();
                Page next;
                if (t == TAB_HOME) {
                    KioskExtractor ex = ServiceList.YouTube.getKioskList().getExtractorById(k, page);
                    ListExtractor.InfoItemsPage<StreamInfoItem> p = ex.getPage(page);
                    for (StreamInfoItem s : p.getItems()) addItem(more, s);
                    next = p.hasNextPage() ? p.getNextPage() : null;
                } else {
                    ListExtractor.InfoItemsPage<InfoItem> p =
                            SearchInfo.getMoreItems(ServiceList.YouTube, q, page);
                    for (InfoItem ii : p.getItems()) addItem(more, ii);
                    next = p.hasNextPage() ? p.getNextPage() : null;
                }
                App.MAIN.post(() -> {
                    if (token != loadToken) return;
                    if (t == TAB_HOME) kioskPage = next;
                    else searchPage = next;
                    adapter.add(more);
                    loadingMore = false;
                });
            } catch (Throwable e) {
                App.MAIN.post(() -> loadingMore = false);
            }
        });
    }

    private List<Item> libraryItems() {
        List<Item> l = new ArrayList<>();
        l.add(libItem("app:history", "Verlauf", R.drawable.ic_history));
        l.add(libItem("app:wl", "Später ansehen", R.drawable.ic_watch_later));
        l.add(libItem("app:downloads", "Downloads", R.drawable.ic_download));
        if (Account.isLoggedIn()) {
            l.add(libItem("app:liked", "Mag ich", R.drawable.ic_like));
            l.add(libItem("app:subs", "Meine Abos", R.drawable.ic_subs));
        }
        l.add(libItem("app:new", "Neue Playlist", R.drawable.ic_add));
        for (LocalPlaylists.Playlist p : LocalPlaylists.all()) l.add(LocalPlaylists.asItem(p));
        return l;
    }

    private List<Item> libraryFooter() {
        List<Item> l = new ArrayList<>();
        l.add(libItem("app:alarms", "Wecker", R.drawable.ic_alarm));
        l.add(libItem("app:settings", "Einstellungen", R.drawable.ic_settings));
        return l;
    }

    private static Item libItem(String url, String title, int icon) {
        Item it = new Item();
        it.type = Item.PLAYLIST;
        it.url = url;
        it.title = title;
        it.extra = "@icon:" + icon;
        return it;
    }

    private void toast(String s) {
        if (s != null) Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
