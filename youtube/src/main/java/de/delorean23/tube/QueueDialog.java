package de.delorean23.tube;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.List;

/**
 * Warteschlange: was als Nächstes kommt. Antippen = abspielen,
 * gedrückt halten und ziehen = verschieben, zur Seite wischen = entfernen.
 * Created by DeLorean23
 */
public final class QueueDialog {

    private QueueDialog() {
    }

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static void show(Activity a) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);

        // Schalter oben
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(a, 16), dp(a, 4), dp(a, 8), dp(a, 4));
        TextView shuffle = chip(a);
        TextView repeat = chip(a);
        bar.addView(shuffle);
        bar.addView(repeat);
        View spacer = new View(a);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        SwitchCompat auto = new SwitchCompat(a);
        auto.setText("Autoplay ");
        auto.setTextColor(a.getResources().getColor(R.color.text));
        auto.setChecked(Playback.autoplay());
        auto.setOnCheckedChangeListener((b, on) -> Playback.setBool("autoplay", on));
        bar.addView(auto);
        root.addView(bar);

        RecyclerView rv = new RecyclerView(a);
        rv.setLayoutManager(new LinearLayoutManager(a));
        Adapter adapter = new Adapter(a);
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (a.getResources().getDisplayMetrics().heightPixels * 0.55f)));

        Runnable updateChips = () -> {
            shuffle.setText("Zufall");
            shuffle.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_shuffle, 0, 0, 0);
            styleOn(a, shuffle, PlayQueue.shuffle());
            int r = PlayQueue.repeat();
            repeat.setText(r == PlayQueue.REPEAT_ONE ? "Video wiederholen"
                    : r == PlayQueue.REPEAT_ALL ? "Liste wiederholen" : "Wiederholen");
            repeat.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_repeat, 0, 0, 0);
            styleOn(a, repeat, r != PlayQueue.REPEAT_OFF);
        };
        updateChips.run();
        shuffle.setOnClickListener(v -> {
            PlayQueue.setShuffle(!PlayQueue.shuffle());
            updateChips.run();
        });
        repeat.setOnClickListener(v -> {
            PlayQueue.setRepeat((PlayQueue.repeat() + 1) % 3);
            PlaybackService.applyRepeat();
            updateChips.run();
        });

        final boolean[] dragging = {false};
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public int getSwipeDirs(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder vh) {
                // das laufende Video kann man nicht wegwischen
                return vh.getBindingAdapterPosition() == PlayQueue.index() ? 0 : super.getSwipeDirs(r, vh);
            }

            @Override
            public boolean onMove(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder vh,
                                  @NonNull RecyclerView.ViewHolder target) {
                int from = vh.getBindingAdapterPosition(), to = target.getBindingAdapterPosition();
                PlayQueue.move(from, to);
                adapter.notifyItemMoved(from, to);
                return true;
            }

            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder vh, int state) {
                super.onSelectedChanged(vh, state);
                if (state == ItemTouchHelper.ACTION_STATE_DRAG) dragging[0] = true;
            }

            @Override
            public void clearView(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(r, vh);
                if (dragging[0]) {
                    dragging[0] = false;
                    adapter.notifyDataSetChanged();
                }
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
                PlayQueue.remove(vh.getBindingAdapterPosition());
            }
        }).attachToRecyclerView(rv);

        PlayQueue.Listener l = () -> {
            if (!dragging[0]) adapter.notifyDataSetChanged();
        };
        PlayQueue.addListener(l);

        String title = PlayQueue.title() != null ? PlayQueue.title() : "Warteschlange";
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle(title + "  (" + PlayQueue.size() + ")")
                .setView(root)
                .setPositiveButton("Schließen", null)
                .create();
        d.setOnDismissListener(x -> PlayQueue.removeListener(l));
        d.show();
        rv.scrollToPosition(Math.max(0, PlayQueue.index()));
    }

    private static TextView chip(Activity a) {
        TextView t = new TextView(a);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(a, 10), 0, dp(a, 12), 0);
        t.setCompoundDrawablePadding(dp(a, 4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(a, 32));
        lp.rightMargin = dp(a, 6);
        t.setLayoutParams(lp);
        return t;
    }

    private static void styleOn(Activity a, TextView t, boolean on) {
        t.setBackgroundResource(on ? R.drawable.chip_on_bg : R.drawable.chip_bg);
        t.setTextColor(a.getResources().getColor(on ? R.color.black : R.color.text));
    }

    private static final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final Activity a;

        Adapter(Activity a) {
            this.a = a;
        }

        @Override
        public int getItemCount() {
            return PlayQueue.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(a, 16), dp(a, 6), dp(a, 16), dp(a, 6));
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(a.getResources().getColor(R.color.card));
            row.addView(iv, new LinearLayout.LayoutParams(dp(a, 80), dp(a, 45)));
            LinearLayout texts = new LinearLayout(a);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(a, 12), 0, 0, 0);
            TextView t1 = new TextView(a);
            t1.setMaxLines(2);
            t1.setTextColor(a.getResources().getColor(R.color.text));
            TextView t2 = new TextView(a);
            t2.setTextSize(12);
            t2.setMaxLines(1);
            t2.setTextColor(a.getResources().getColor(R.color.text_dim));
            texts.addView(t1);
            texts.addView(t2);
            row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(row) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int position) {
            List<Item> items = PlayQueue.items();
            if (position >= items.size()) return;
            Item it = items.get(position);
            LinearLayout row = (LinearLayout) vh.itemView;
            ImageView iv = (ImageView) row.getChildAt(0);
            LinearLayout texts = (LinearLayout) row.getChildAt(1);
            TextView t1 = (TextView) texts.getChildAt(0);
            TextView t2 = (TextView) texts.getChildAt(1);
            boolean current = position == PlayQueue.index();
            t1.setText((current ? "▶ " : "") + (it.title != null ? it.title : it.url));
            t1.setTypeface(null, current ? Typeface.BOLD : Typeface.NORMAL);
            t1.setTextColor(a.getResources().getColor(current ? R.color.accent : R.color.text));
            t2.setText(it.uploader != null ? it.uploader : "");
            String th = it.thumb;
            String id = Util.videoId(it.url);
            if (th == null && id != null) th = "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg";
            Glide.with(a).load(th).centerCrop().into(iv);
            row.setOnClickListener(v -> {
                int p = vh.getBindingAdapterPosition();
                if (p != PlayQueue.index()) PlaybackService.playQueueIndex(p);
            });
        }
    }
}
