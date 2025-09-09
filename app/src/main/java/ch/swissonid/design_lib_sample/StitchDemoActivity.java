package ch.swissonid.design_lib_sample;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

import ch.swissonid.design_lib_sample.util.ScrollStitchUtil;

public class StitchDemoActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stitch_demo);

        ImageView iv = findViewById(R.id.resultImage);
        TextView tv = findViewById(R.id.debugText);

        List<Bitmap> frames = synthesizeFrames(1080, 1600, 6, 240); // width, height, count, scrollStep
        ScrollStitchUtil.StitchOptions opts = new ScrollStitchUtil.StitchOptions();
        opts.pyramidLevels = 3;
        opts.maxSearchPercent = 0.45f;
        opts.refineWindowPx = 12;
        opts.sampleXStep = 2;
        opts.sampleYStep = 2;
        opts.blendBandPx = 28;
        opts.cropTopPx = 80; // 模拟固定头部
        opts.cropBottomPx = 0;

        ScrollStitchUtil.StitchResult sr = ScrollStitchUtil.stitch(frames, opts);
        iv.setImageBitmap(sr.bitmap);

        StringBuilder sb = new StringBuilder();
        sb.append("Offsets (px), NCC: \n");
        for (ScrollStitchUtil.OffsetResult o : sr.offsets) {
            sb.append(o.offsetPx).append(", ").append(String.format("%.3f", o.confidence)).append("\n");
        }
        tv.setText(sb.toString());
    }

    private List<Bitmap> synthesizeFrames(int width, int height, int count, int scrollStep) {
        // 生成一个高于屏幕的长内容，然后按 scrollStep 向上切割模拟滚动截屏
        int contentHeight = height + (count - 1) * scrollStep + 200;
        Bitmap longContent = Bitmap.createBitmap(width, contentHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(longContent);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // 背景渐变条纹
        for (int y = 0; y < contentHeight; y += 40) {
            int c = Color.rgb((y * 3) % 256, (y * 7) % 256, (y * 11) % 256);
            p.setColor(c);
            canvas.drawRect(0, y, width, y + 40, p);
        }
        // 固定头部（模拟悬浮栏）
        p.setColor(Color.argb(220, 30, 30, 30));
        canvas.drawRect(0, 0, width, 80, p);
        p.setColor(Color.WHITE);
        p.setTextSize(48f);
        canvas.drawText("Fixed Header", 24, 56, p);
        // 插入一些图形与文字
        p.setTextSize(36f);
        for (int i = 0; i < 50; i++) {
            int y = 120 + i * 100;
            p.setColor(Color.WHITE);
            canvas.drawText("Row " + (i + 1) + " — Lorem ipsum dolor sit amet.", 24, y, p);
            p.setColor(Color.argb(160, (i * 23) % 255, (i * 53) % 255, (i * 83) % 255));
            canvas.drawRect(24, y + 20, width - 24, y + 60, p);
        }

        ArrayList<Bitmap> frames = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int top = i * scrollStep;
            if (top + height > contentHeight) top = contentHeight - height;
            Bitmap frame = Bitmap.createBitmap(longContent, 0, top, width, height);
            frames.add(frame);
        }
        return frames;
    }
}

