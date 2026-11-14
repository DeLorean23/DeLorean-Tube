package de.delorean23.tube;

import android.app.Activity;
import android.os.Build;
import android.text.Html;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.comments.CommentsInfo;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.stream.Description;

import java.util.ArrayList;
import java.util.List;

/** Kommentare lesen (mit Antworten). Created by DeLorean23 */
public final class CommentsDialog {

    private CommentsDialog() {
    }

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Kommentare eines Videos. */
    public static void show(Activity a, String videoUrl) {
        open(a, "Kommentare", videoUrl, null);
    }

    /** replies != null: Antworten auf einen Kommentar. */
    private static void open(Activity a, String title, String videoUrl, Page replies) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        ProgressBar pb = new ProgressBar(a);
        root.addView(pb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 48)));
        TextView info = new TextView(a);
        info.setTextColor(a.getResources().getColor(R.color.text_dim));
        info.setPadding(dp(a, 20), dp(a, 8), dp(a, 20), dp(a, 8));
        info.setVisibility(View.GONE);
        root.addView(info);

        RecyclerView rv = new RecyclerView(a);
        rv.setLayoutManager(new LinearLayoutManager(a));
        Adapter ad = new Adapter(a, videoUrl);
        rv.setAdapter(ad);
        root.addView(rv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (a.getResources().getDisplayMetrics().heightPixels * 0.65f)));

        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(root)
                .setPositiveButton("Schließen", null)
                .show();

        final Page[] next = {null};
        final boolean[] loading = {true};
        App.IO.execute(() -> {
            try {
                List<CommentsInfoItem> items;
                Page n;
                String status = null;
                if (replies == null) {
                    CommentsInfo ci = CommentsInfo.getInfo(ServiceList.YouTube, videoUrl);
                    items = ci.getRelatedItems();
                    n = ci.hasNextPage() ? ci.getNextPage() : null;
                    if (ci.isCommentsDisabled()) status = "Kommentare sind bei diesem Video ausgeschaltet.";
                    else if (ci.getCommentsCount() > 0) status = Util.count(ci.getCommentsCount()) + " Kommentare";
                } else {
                    ListExtractor.InfoItemsPage<CommentsInfoItem> p =
                            CommentsInfo.getMoreItems(ServiceList.YouTube, videoUrl, replies);
                    items = p.getItems();
                    n = p.hasNextPage() ? p.getNextPage() : null;
                }
                final String st = status;
                App.MAIN.post(() -> {
                    pb.setVisibility(View.GONE);
                    loading[0] = false;
                    next[0] = n;
                    if (st != null) {
                        info.setText(st);
                        info.setVisibility(View.VISIBLE);
                    } else if (items.isEmpty()) {
                        info.setText("Keine Kommentare.");
                        info.setVisibility(View.VISIBLE);
                    }
                    ad.add(items);
                });
            } catch (Throwable e) {
                App.MAIN.post(() -> {
                    pb.setVisibility(View.GONE);
                    info.setText("Kommentare konnten nicht geladen werden: " + e.getMessage());
                    info.setVisibility(View.VISIBLE);
                });
            }
        });

        // weiterblättern
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) r.getLayoutManager();
                if (lm == null || loading[0] || next[0] == null) return;
                if (lm.findLastVisibleItemPosition() < ad.getItemCount() - 4) return;
                loading[0] = true;
                final Page p = next[0];
                App.IO.execute(() -> {
                    try {
                        ListExtractor.InfoItemsPage<CommentsInfoItem> more =
                                CommentsInfo.getMoreItems(ServiceList.YouTube, videoUrl, p);
                        App.MAIN.post(() -> {
                            next[0] = more.hasNextPage() ? more.getNextPage() : null;
                            ad.add(more.getItems());
                            loading[0] = false;
                        });
                    } catch (Throwable e) {
                        App.MAIN.post(() -> loading[0] = false);
                    }
                });
            }
        });
        if (d.getWindow() != null) d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static CharSequence text(Description d) {
        if (d == null || d.content() == null) return "";
        if (d.type() == Description.Type.HTML) {
            return Build.VERSION.SDK_INT >= 24
                    ? Html.fromHtml(d.content(), Html.FROM_HTML_MODE_LEGACY) : Html.fromHtml(d.content());
        }
        return d.content();
    }

    private static final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final Activity a;
        private final String videoUrl;
        private final List<CommentsInfoItem> items = new ArrayList<>();

        Adapter(Activity a, String videoUrl) {
            this.a = a;
            this.videoUrl = videoUrl;
        }

        void add(List<CommentsInfoItem> more) {
            int start = items.size();
            items.addAll(more);
            notifyItemRangeInserted(start, more.size());
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(a);
            row.setPadding(dp(a, 16), dp(a, 10), dp(a, 16), dp(a, 10));
            ImageView av = new ImageView(a);
            row.addView(av, new LinearLayout.LayoutParams(dp(a, 32), dp(a, 32)));
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(a, 12), 0, 0, 0);
            TextView head = new TextView(a);
            head.setTextSize(12);
            head.setTextColor(a.getResources().getColor(R.color.text_dim));
            TextView body = new TextView(a);
            body.setTextSize(14);
            body.setTextColor(a.getResources().getColor(R.color.text));
            body.setTextIsSelectable(true);
            TextView foot = new TextView(a);
            foot.setTextSize(12);
            foot.setTextColor(a.getResources().getColor(R.color.text_dim));
            foot.setPadding(0, dp(a, 4), 0, 0);
            TextView replies = new TextView(a);
            replies.setTextSize(13);
            replies.setTextColor(a.getResources().getColor(R.color.accent));
            replies.setPadding(0, dp(a, 6), 0, 0);
            col.addView(head);
            col.addView(body);
            col.addView(foot);
            col.addView(replies);
            row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setGravity(Gravity.TOP);
            return new RecyclerView.ViewHolder(row) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int position) {
            CommentsInfoItem c = items.get(position);
            LinearLayout row = (LinearLayout) vh.itemView;
            ImageView av = (ImageView) row.getChildAt(0);
            LinearLayout col = (LinearLayout) row.getChildAt(1);
            TextView head = (TextView) col.getChildAt(0);
            TextView body = (TextView) col.getChildAt(1);
            TextView foot = (TextView) col.getChildAt(2);
            TextView replies = (TextView) col.getChildAt(3);

            String avatar = Util.thumb(c.getUploaderAvatars());
            if (avatar != null) Glide.with(a).load(avatar).circleCrop().into(av);
            else av.setImageDrawable(null);
            StringBuilder h = new StringBuilder();
            if (c.isPinned()) h.append("📌 ");
            h.append(c.getUploaderName() != null ? c.getUploaderName() : "");
            if (c.getTextualUploadDate() != null) h.append(" · ").append(c.getTextualUploadDate());
            head.setText(h);
            body.setText(text(c.getCommentText()));
            StringBuilder f = new StringBuilder();
            if (c.getLikeCount() > 0) f.append("👍 ").append(Util.count(c.getLikeCount()));
            if (c.isHeartedByUploader()) f.append(f.length() > 0 ? "   " : "").append("❤ vom Kanal");
            foot.setText(f);
            foot.setVisibility(f.length() > 0 ? View.VISIBLE : View.GONE);
            final Page rp = c.getReplies();
            if (rp != null && c.getReplyCount() != 0) {
                int n = c.getReplyCount();
                replies.setText(n > 0 ? n + (n == 1 ? " Antwort" : " Antworten") : "Antworten ansehen");
                replies.setVisibility(View.VISIBLE);
                replies.setOnClickListener(v -> open(a, "Antworten", videoUrl, rp));
            } else {
                replies.setVisibility(View.GONE);
            }
        }
    }
}
