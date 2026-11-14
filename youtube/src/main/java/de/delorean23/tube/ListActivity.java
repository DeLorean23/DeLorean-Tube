package de.delorean23.tube;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.List;

/** Kanal, Playlist, "Mag ich", Verlauf, Später ansehen oder Abo-Liste. Created by DeLorean23 */
public class ListActivity extends AppCompatActivity {

    public static final int MODE_CHANNEL = 1, MODE_PLAYLIST = 2, MODE_API_PLAYLIST = 3,
            MODE_LIKED = 4, MODE_HISTORY = 5, MODE_SUBS = 6, MODE_LOCAL_PLAYLIST = 7;

    public static Intent intent(Context c, int mode, String arg, String title) {
        return new Intent(c, ListActivity.class)
                .putExtra("mode", mode)
                .putExtra("arg", arg)
                .putExtra("title", title);
    }

    private int mode;
    private String arg;
    private String listTitle;
    private ItemAdapter adapter;
    private SwipeRefreshLayout refresh;
    private TextView emptyText, btnSubscribe, title, btnEdit;
    private int loadToken;
    private boolean loadingMore;
    private boolean editing;
    private int dragFrom = -1, dragTo = -1;

    // Weiterblättern
    private Page next;
    private ListLinkHandler channelTab;

    private String channelId;
    private String subscriptionId;
    private MiniPlayer mini;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        mode = getIntent().getIntExtra("mode", MODE_HISTORY);
        arg = getIntent().getStringExtra("arg");
        listTitle = getIntent().getStringExtra("title");

        title = findViewById(R.id.listTitle);
        title.setText(listTitle);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        emptyText = findViewById(R.id.emptyText);
        btnSubscribe = findViewById(R.id.btnSubscribe);
        refresh = findViewById(R.id.refresh);
        refresh.setColorSchemeResources(R.color.accent);
        refresh.setProgressBackgroundColorSchemeResource(R.color.card);
        refresh.setOnRefreshListener(this::load);

        RecyclerView list = findViewById(R.id.list);
        adapter = new ItemAdapter(this, this::open);
        list.setLayoutManager(ItemAdapter.layout(this, adapter));
        list.setAdapter(adapter);
        adapter.setOnNearEnd(this::loadMore);
        adapter.setOnLongClick(this::longPress);
        if (isVideoList()) adapter.setHeader(buildHeader());
        if (isOwnPlaylist()) setupEditing(list);

        if (mode == MODE_CHANNEL) {
            channelId = Util.channelId(arg);
            btnSubscribe.setOnClickListener(v -> toggleSubscribe());
            if (Account.isLoggedIn() && channelId != null) checkSubscription();
        }

        mini = new MiniPlayer(this, findViewById(R.id.contentFrame));
        refresh.setRefreshing(true);
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mini.onResume();
        adapter.refreshProgress();
        // Später ansehen / lokale Playlist kann sich geändert haben
        if (mode == MODE_LOCAL_PLAYLIST && !editing) load();
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

    private boolean isVideoList() {
        return mode == MODE_PLAYLIST || mode == MODE_API_PLAYLIST || mode == MODE_LOCAL_PLAYLIST
                || mode == MODE_LIKED || mode == MODE_HISTORY || mode == MODE_CHANNEL;
    }

    /** Eigene Playlist (bearbeitbar)? */
    private boolean isOwnPlaylist() {
        return mode == MODE_API_PLAYLIST || mode == MODE_LOCAL_PLAYLIST;
    }

    /** Spielt Antippen die ganze Liste ab dem Video ab (wie bei YouTube in Playlists)? */
    private boolean playsAsList() {
        return mode == MODE_PLAYLIST || mode == MODE_API_PLAYLIST || mode == MODE_LOCAL_PLAYLIST
                || mode == MODE_LIKED;
    }

    // ------------------------------------------------------------ Kopfteil: Abspielen / Bearbeiten

    private View buildHeader() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), dp(8), dp(4), dp(12));
        hs.addView(row);
        row.addView(action("Alle abspielen", R.drawable.ic_play, v -> playAll(false)));
        row.addView(action("Zufällig", R.drawable.ic_shuffle, v -> playAll(true)));
        if (mode != MODE_CHANNEL) {
            row.addView(action("Herunterladen", R.drawable.ic_download,
                    v -> DownloadDialog.playlist(this, new ArrayList<>(adapter.items()), listTitle)));
        }
        if (isOwnPlaylist()) {
            btnEdit = action("Bearbeiten", R.drawable.ic_edit, v -> toggleEdit());
            row.addView(btnEdit);
        }
        return hs;
    }

    private TextView action(String text, int icon, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(getResources().getColor(R.color.text));
        t.setBackgroundResource(R.drawable.chip_bg);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(12), 0, dp(14), 0);
        t.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0);
        t.setCompoundDrawablePadding(dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        t.setOnClickListener(l);
        return t;
    }

    private void playAll(boolean shuffle) {
        if (mode == MODE_PLAYLIST && next != null) {
            // öffentliche Playlist: erst alle Seiten holen (bis 500 Videos)
            Toast.makeText(this, "Playlist wird geladen …", Toast.LENGTH_SHORT).show();
            final List<Item> all = new ArrayList<>(adapter.items());
            final Page start = next;
            App.IO.execute(() -> {
                Page p = start;
                try {
                    while (p != null && all.size() < 500) {
                        ListExtractor.InfoItemsPage<StreamInfoItem> page =
                                PlaylistInfo.getMoreItems(ServiceList.YouTube, arg, p);
                        for (StreamInfoItem s : page.getItems()) add(all, s);
                        p = page.hasNextPage() ? page.getNextPage() : null;
                    }
                } catch (Throwable ignored) {
                }
                App.MAIN.post(() -> startList(all, 0, shuffle));
            });
            return;
        }
        startList(new ArrayList<>(adapter.items()), 0, shuffle);
    }

    private void startList(List<Item> items, int index, boolean shuffle) {
        List<Item> videos = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it.type != Item.VIDEO || it.url == null) continue;
            if (i == index) start = videos.size();
            videos.add(it);
        }
        if (videos.isEmpty()) {
            Toast.makeText(this, "Keine Videos zum Abspielen", Toast.LENGTH_SHORT).show();
            return;
        }
        PlayQueue.playList(videos, start, shuffle, listTitle);
        Item first = PlayQueue.current();
        startActivity(PlayerActivity.intent(this, first.url, first).putExtra("queue", true));
    }

    // ------------------------------------------------------------ Bearbeiten (eigene Playlists)

    private void toggleEdit() {
        editing = !editing;
        btnEdit.setText(editing ? "Fertig" : "Bearbeiten");
        btnEdit.setBackgroundResource(editing ? R.drawable.chip_on_bg : R.drawable.chip_bg);
        btnEdit.setTextColor(getResources().getColor(editing ? R.color.black : R.color.text));
        adapter.setOnLongClick(editing ? null : this::longPress);
        refresh.setEnabled(!editing);
        if (editing) {
            Toast.makeText(this, "Gedrückt halten und ziehen = verschieben, zur Seite wischen = entfernen",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void setupEditing(RecyclerView list) {
        ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                if (!editing || adapter.indexOf(vh.getBindingAdapterPosition()) < 0) return 0;
                return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN,
                        ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT);
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return editing;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh,
                                  @NonNull RecyclerView.ViewHolder target) {
                int from = adapter.indexOf(vh.getBindingAdapterPosition());
                int to = adapter.indexOf(target.getBindingAdapterPosition());
                if (from < 0 || to < 0) return false;
                if (dragFrom < 0) dragFrom = from;
                dragTo = to;
                adapter.move(from, to);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
                int i = adapter.indexOf(vh.getBindingAdapterPosition());
                Item removed = adapter.removeAt(i);
                if (removed != null) removeFromPlaylist(removed);
            }

            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                if (dragFrom >= 0 && dragTo >= 0 && dragFrom != dragTo) {
                    saveMove(dragFrom, dragTo);
                }
                dragFrom = dragTo = -1;
            }
        });
        helper.attachToRecyclerView(list);
    }

    private void saveMove(int from, int to) {
        if (mode == MODE_LOCAL_PLAYLIST) {
            LocalPlaylists.move(arg, from, to);
            return;
        }
        Item it = adapter.items().get(to);
        if (it.playlistItemId == null) return;
        final String vid = Util.videoId(it.url);
        App.IO.execute(() -> {
            try {
                Account.movePlaylistItem(it.playlistItemId, arg, vid, to);
            } catch (Throwable e) {
                App.MAIN.post(() -> {
                    Toast.makeText(this, "Verschieben fehlgeschlagen: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    load();
                });
            }
        });
    }

    private void removeFromPlaylist(Item it) {
        if (mode == MODE_LOCAL_PLAYLIST) {
            LocalPlaylists.remove(arg, it.url);
            Toast.makeText(this, "Entfernt", Toast.LENGTH_SHORT).show();
            return;
        }
        if (it.playlistItemId == null) return;
        App.IO.execute(() -> {
            try {
                Account.removeFromPlaylist(it.playlistItemId);
                App.MAIN.post(() -> Toast.makeText(this, "Entfernt", Toast.LENGTH_SHORT).show());
            } catch (Throwable e) {
                App.MAIN.post(() -> {
                    Toast.makeText(this, "Entfernen fehlgeschlagen: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    load();
                });
            }
        });
    }

    // ------------------------------------------------------------ Antippen / langes Drücken

    private void open(Item it) {
        if (editing) return;
        if (it.type == Item.VIDEO) {
            if (playsAsList()) {
                startList(new ArrayList<>(adapter.items()), adapter.items().indexOf(it), false);
            } else {
                PlayQueue.playSingle(it);
                startActivity(PlayerActivity.intent(this, it.url, it));
            }
        } else if (it.type == Item.CHANNEL) {
            startActivity(intent(this, MODE_CHANNEL, it.url, it.title));
        } else if (it.localPlaylistId != null) {
            startActivity(intent(this, MODE_LOCAL_PLAYLIST, it.localPlaylistId, it.title));
        } else if (it.apiPlaylistId != null) {
            startActivity(intent(this, MODE_API_PLAYLIST, it.apiPlaylistId, it.title));
        } else {
            startActivity(intent(this, MODE_PLAYLIST, it.url, it.title));
        }
    }

    private void longPress(Item it) {
        if (it.type != Item.VIDEO) return;
        VideoMenu.Extra remove = null;
        if (isOwnPlaylist()) {
            remove = new VideoMenu.Extra("Aus dieser Playlist entfernen", () -> {
                int i = adapter.items().indexOf(it);
                if (i >= 0) adapter.removeAt(i);
                removeFromPlaylist(it);
            });
        }
        VideoMenu.show(this, it, remove);
    }

    // ------------------------------------------------------------ Laden

    private void done(int token, List<Item> items) {
        App.MAIN.post(() -> {
            if (token != loadToken) return;
            refresh.setRefreshing(false);
            adapter.set(items);
            emptyText.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            emptyText.setText(mode == MODE_LOCAL_PLAYLIST
                    ? "Noch leer. Bei einem Video „Speichern“ oder lange drücken → „Später ansehen“."
                    : getString(R.string.empty));
        });
    }

    private void fail(int token, Throwable e) {
        App.MAIN.post(() -> {
            if (token != loadToken) return;
            refresh.setRefreshing(false);
            emptyText.setVisibility(View.VISIBLE);
            emptyText.setText(getString(R.string.error_load) + "\n\n" + e.getMessage());
        });
    }

    private void load() {
        final int token = ++loadToken;
        next = null;
        App.IO.execute(() -> {
            try {
                List<Item> items = new ArrayList<>();
                switch (mode) {
                    case MODE_HISTORY:
                        items.addAll(History.all());
                        break;
                    case MODE_LIKED:
                        items.addAll(Account.likedVideos());
                        break;
                    case MODE_API_PLAYLIST:
                        items.addAll(Account.playlistItems(arg));
                        break;
                    case MODE_LOCAL_PLAYLIST: {
                        LocalPlaylists.Playlist p = LocalPlaylists.get(arg);
                        if (p != null) {
                            for (Item it : p.items) {
                                Item copy = Item.fromJson(it.toJson());
                                copy.type = Item.VIDEO;
                                items.add(copy);
                            }
                        }
                        break;
                    }
                    case MODE_SUBS:
                        for (String[] s : Feed.subscriptions(true)) {
                            Item it = new Item();
                            it.type = Item.CHANNEL;
                            it.url = "https://www.youtube.com/channel/" + s[0];
                            it.title = s[1];
                            it.thumb = s[2];
                            it.extra = "Kanal";
                            items.add(it);
                        }
                        break;
                    case MODE_PLAYLIST: {
                        PlaylistInfo info = PlaylistInfo.getInfo(ServiceList.YouTube, arg);
                        for (StreamInfoItem s : info.getRelatedItems()) add(items, s);
                        next = info.hasNextPage() ? info.getNextPage() : null;
                        break;
                    }
                    case MODE_CHANNEL: {
                        ChannelInfo ch = ChannelInfo.getInfo(ServiceList.YouTube, arg);
                        if (channelId == null) channelId = ch.getId();
                        App.MAIN.post(() -> title.setText(ch.getName()));
                        ListLinkHandler videosTab = null;
                        for (ListLinkHandler t : ch.getTabs()) {
                            if (t.getContentFilters().contains(ChannelTabs.VIDEOS)) {
                                videosTab = t;
                                break;
                            }
                        }
                        if (videosTab == null && !ch.getTabs().isEmpty()) videosTab = ch.getTabs().get(0);
                        if (videosTab != null) {
                            ChannelTabInfo tabInfo = ChannelTabInfo.getInfo(ServiceList.YouTube, videosTab);
                            for (InfoItem ii : tabInfo.getRelatedItems()) add(items, ii);
                            channelTab = videosTab;
                            next = tabInfo.hasNextPage() ? tabInfo.getNextPage() : null;
                        }
                        if (Account.isLoggedIn() && channelId != null) {
                            App.MAIN.post(this::checkSubscription);
                        }
                        break;
                    }
                    default:
                        break;
                }
                done(token, items);
            } catch (Throwable e) {
                fail(token, e);
            }
        });
    }

    private static void add(List<Item> out, InfoItem ii) {
        Item it = Item.from(ii);
        if (it != null) out.add(it);
    }

    private void loadMore() {
        if (loadingMore || next == null) return;
        if (mode != MODE_PLAYLIST && mode != MODE_CHANNEL) return;
        loadingMore = true;
        final Page page = next;
        final int token = loadToken;
        App.IO.execute(() -> {
            try {
                List<Item> more = new ArrayList<>();
                Page n;
                if (mode == MODE_PLAYLIST) {
                    ListExtractor.InfoItemsPage<StreamInfoItem> p =
                            PlaylistInfo.getMoreItems(ServiceList.YouTube, arg, page);
                    for (StreamInfoItem s : p.getItems()) add(more, s);
                    n = p.hasNextPage() ? p.getNextPage() : null;
                } else {
                    ListExtractor.InfoItemsPage<InfoItem> p =
                            ChannelTabInfo.getMoreItems(ServiceList.YouTube, channelTab, page);
                    for (InfoItem ii : p.getItems()) add(more, ii);
                    n = p.hasNextPage() ? p.getNextPage() : null;
                }
                App.MAIN.post(() -> {
                    if (token != loadToken) return;
                    next = n;
                    adapter.add(more);
                    loadingMore = false;
                });
            } catch (Throwable e) {
                App.MAIN.post(() -> loadingMore = false);
            }
        });
    }

    // ------------------------------------------------------------ Abonnieren

    private void checkSubscription() {
        if (channelId == null) return;
        App.IO.execute(() -> {
            try {
                String id = Account.subscriptionId(channelId);
                App.MAIN.post(() -> {
                    subscriptionId = id;
                    updateSubscribe();
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void updateSubscribe() {
        btnSubscribe.setVisibility(View.VISIBLE);
        if (subscriptionId != null) {
            btnSubscribe.setText("Abonniert");
            btnSubscribe.setBackgroundResource(R.drawable.chip_bg);
            btnSubscribe.setTextColor(getResources().getColor(R.color.text));
        } else {
            btnSubscribe.setText("Abonnieren");
            btnSubscribe.setBackgroundResource(R.drawable.sub_bg);
            btnSubscribe.setTextColor(getResources().getColor(R.color.black));
        }
    }

    private void toggleSubscribe() {
        if (channelId == null) return;
        final String sub = subscriptionId;
        App.IO.execute(() -> {
            try {
                String newId = null;
                if (sub != null) Account.unsubscribe(sub);
                else newId = Account.subscribe(channelId);
                final String id = newId;
                App.MAIN.post(() -> {
                    subscriptionId = id;
                    updateSubscribe();
                });
            } catch (Throwable e) {
                App.MAIN.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
