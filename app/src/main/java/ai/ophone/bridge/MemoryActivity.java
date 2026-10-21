package ai.ophone.bridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

/** Lets the user read and delete everything the agent has remembered. */
public final class MemoryActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(base); UiKit.applyTheme(this); }
    private LinearLayout list;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        list = UiKit.page(this);
        scroll.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        render();
    }

    private void render() {
        list.removeAllViews();
        list.addView(UiKit.title(this, getString(R.string.memory_title)));
        list.addView(UiKit.subtitle(this, getString(R.string.memory_desc)));
        list.addView(UiKit.spacer(this, 12));
        JSONArray all;
        try { all = MemoryStore.get(this).all(); } catch (Exception e) { all = new JSONArray(); }
        if (all.length() > 0) {
            Button clear = UiKit.plainButton(this, getString(R.string.memory_clear), true);
            clear.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(R.string.memory_clear)
                    .setMessage(R.string.memory_clear_confirm).setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.memory_clear, (d, w) -> { MemoryStore.get(this).clear(); render(); }).show());
            list.addView(clear, UiKit.cardParams(this));
        } else {
            list.addView(UiKit.body(this, getString(R.string.memory_empty)));
        }
        for (int i = 0; i < all.length(); i++) {
            JSONObject m = all.optJSONObject(i);
            if (m == null) continue;
            LinearLayout card = UiKit.card(this);
            TextView title = new TextView(this);
            title.setText(m.optString("title"));
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextSize(15f);
            title.setTextColor(UiKit.primaryTextColor(this));
            card.addView(title);
            String meta = m.optString("kind") + (m.optString("app").isEmpty() ? "" : " · " + m.optString("app"));
            TextView kind = UiKit.body(this, meta);
            kind.setTextSize(12f);
            card.addView(kind);
            TextView body = UiKit.body(this, m.optString("text"));
            body.setTextSize(13.5f);
            body.setTextIsSelectable(true);
            body.setPadding(0, UiKit.dp(this, 6), 0, 0);
            card.addView(body);
            String id = m.optString("id");
            Button delete = UiKit.plainButton(this, getString(R.string.delete_chat), true);
            delete.setOnClickListener(v -> { try { MemoryStore.get(this).delete(id); } catch (Exception ignored) {} render(); });
            card.addView(delete);
            list.addView(card, UiKit.cardParams(this));
        }
    }
}
