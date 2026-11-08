package de.delorean23.cloud;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

/** Cover image in poster format (2:3). */
public class PosterView extends ImageView {
    public PosterView(Context c) { super(c); }
    public PosterView(Context c, AttributeSet a) { super(c, a); }
    public PosterView(Context c, AttributeSet a, int s) { super(c, a, s); }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, width * 3 / 2);
    }
}
