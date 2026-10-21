package ai.ophone.bridge;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Creates or edits one scheduled task: what to send, when, on which platform and model, into which conversation. */
public final class ScheduleEditActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(base); UiKit.applyTheme(this); }
    private static final String[] PLATFORMS = {PlatformSession.CHATGPT, PlatformSession.DEEPSEEK, PlatformSession.OPENAI, PlatformSession.CLAUDE};
    private JSONObject editing;
    private EditText title, prompt, interval;
    private AutoCompleteTextView model, effort;
    private RadioGroup modes;
    private Button startButton, timeButton;
    private Spinner unit, platform, conversation;
    private LinearLayout startRow, intervalRow, timeRow;
    private TextView effortLabel;
    private final Calendar start = Calendar.getInstance();
    private int hour = 8, minute = 0;
    private final List<String[]> conversations = new ArrayList<>(); // {mode, id, shown label, conversation name}

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        editing = ScheduleStore.get(this, getIntent().getStringExtra("id"));
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = UiKit.page(this);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        page.addView(UiKit.title(this, getString(editing == null ? R.string.schedule_add : R.string.schedule_edit)));

        // What
        page.addView(UiKit.section(this, getString(R.string.schedule_section_what)));
        LinearLayout what = UiKit.card(this);
        title = field(what, R.string.schedule_name_hint, false);
        prompt = field(what, R.string.schedule_prompt_hint, true);
        page.addView(what, UiKit.cardParams(this));

        // When
        page.addView(UiKit.section(this, getString(R.string.schedule_section_when)));
        LinearLayout when = UiKit.card(this);
        modes = new RadioGroup(this);
        modes.addView(radio(1, R.string.schedule_mode_once)); modes.addView(radio(2, R.string.schedule_mode_interval)); modes.addView(radio(3, R.string.schedule_mode_daily));
        when.addView(modes);
        startRow = row(when); startButton = UiKit.plainButton(this, "", false); startButton.setOnClickListener(v -> pickStart()); startRow.addView(label(R.string.schedule_start_label)); startRow.addView(startButton, new LinearLayout.LayoutParams(0, UiKit.dp(this, 46), 1));
        intervalRow = row(when); intervalRow.addView(label(R.string.schedule_every_label));
        interval = new EditText(this); interval.setInputType(InputType.TYPE_CLASS_NUMBER); interval.setText("1"); interval.setTextColor(UiKit.primaryTextColor(this));
        intervalRow.addView(interval, new LinearLayout.LayoutParams(UiKit.dp(this, 80), -2));
        unit = spinner(new String[]{getString(R.string.schedule_unit_minutes), getString(R.string.schedule_unit_hours), getString(R.string.schedule_unit_days)}); unit.setSelection(1);
        intervalRow.addView(unit, new LinearLayout.LayoutParams(0, -2, 1));
        timeRow = row(when); timeButton = UiKit.plainButton(this, "", false); timeButton.setOnClickListener(v -> pickTime()); timeRow.addView(label(R.string.schedule_time_label)); timeRow.addView(timeButton, new LinearLayout.LayoutParams(0, UiKit.dp(this, 46), 1));
        modes.setOnCheckedChangeListener((g, id) -> syncMode());
        page.addView(when, UiKit.cardParams(this));

        // How
        page.addView(UiKit.section(this, getString(R.string.schedule_section_how)));
        LinearLayout how = UiKit.card(this);
        how.addView(label(R.string.schedule_platform_label));
        String[] names = new String[PLATFORMS.length];
        for (int i = 0; i < PLATFORMS.length; i++) {
            ModelProvider p = ModelProvider.forPlatform(PLATFORMS[i]);
            names[i] = p == null ? getString(R.string.platform_chatgpt) : p.label + (p.store(this).ready() ? "" : " · " + getString(R.string.schedule_not_set_up));
        }
        platform = spinner(names); how.addView(platform, new LinearLayout.LayoutParams(-1, -2));
        how.addView(label(R.string.schedule_model_label));
        model = suggest(how, R.string.schedule_model_hint);
        effortLabel = label(R.string.schedule_effort_label); how.addView(effortLabel);
        effort = suggest(how, R.string.schedule_effort_hint);
        how.addView(label(R.string.schedule_conversation_label));
        conversation = new Spinner(this); how.addView(conversation, new LinearLayout.LayoutParams(-1, -2));
        platform.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int i, long id) { syncPlatform(); }
            @Override public void onNothingSelected(AdapterView<?> a) {}
        });
        page.addView(how, UiKit.cardParams(this));
        TextView note = UiKit.body(this, getString(R.string.schedule_edit_note)); note.setTextSize(13); page.addView(note);

        Button save = UiKit.actionButton(this, getString(R.string.schedule_save)); save.setOnClickListener(v -> save());
        LinearLayout saveCard = UiKit.card(this); saveCard.addView(save, new LinearLayout.LayoutParams(-1, -2)); page.addView(saveCard, UiKit.cardParams(this));
        setContentView(scroll);
        load();
    }

    /* ---------- form widgets ---------- */

    private EditText field(LinearLayout parent, int hint, boolean multi) {
        EditText e = new EditText(this); e.setHint(hint); e.setTextColor(UiKit.primaryTextColor(this)); e.setHintTextColor(UiKit.secondaryTextColor(this));
        if (multi) { e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); e.setMinLines(4); e.setGravity(android.view.Gravity.TOP); }
        else e.setSingleLine(true);
        parent.addView(e, new LinearLayout.LayoutParams(-1, -2)); return e;
    }
    private AutoCompleteTextView suggest(LinearLayout parent, int hint) {
        AutoCompleteTextView e = new AutoCompleteTextView(this); e.setHint(hint); e.setSingleLine(true); e.setThreshold(0);
        e.setTextColor(UiKit.primaryTextColor(this)); e.setHintTextColor(UiKit.secondaryTextColor(this));
        e.setOnClickListener(v -> { if (e.getAdapter() != null && e.getAdapter().getCount() > 0) e.showDropDown(); });
        e.setOnFocusChangeListener((v, f) -> { if (f && e.getAdapter() != null && e.getAdapter().getCount() > 0) e.post(e::showDropDown); });
        parent.addView(e, new LinearLayout.LayoutParams(-1, -2)); return e;
    }
    private RadioButton radio(int id, int text) { RadioButton r = new RadioButton(this); r.setId(id); r.setText(text); r.setTextColor(UiKit.primaryTextColor(this)); r.setMinHeight(UiKit.dp(this, 44)); return r; }
    private LinearLayout row(LinearLayout parent) { LinearLayout r = new LinearLayout(this); r.setGravity(android.view.Gravity.CENTER_VERTICAL); parent.addView(r, new LinearLayout.LayoutParams(-1, -2)); return r; }
    private TextView label(int text) { TextView t = UiKit.body(this, getString(text)); t.setTextSize(14); t.setPadding(0, UiKit.dp(this, 8), UiKit.dp(this, 10), UiKit.dp(this, 2)); return t; }
    private Spinner spinner(String[] items) { Spinner s = new Spinner(this); ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items); a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); s.setAdapter(a); return s; }
    private void suggestions(AutoCompleteTextView view, List<String> items) { view.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, items)); }

    /* ---------- state ---------- */

    private void load() {
        start.add(Calendar.HOUR_OF_DAY, 1); start.set(Calendar.MINUTE, 0); start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0);
        String current = PlatformPreference.get(this);
        int platformIndex = Math.max(0, java.util.Arrays.asList(PLATFORMS).indexOf(current));
        int modeId = 3;
        if (editing != null) {
            title.setText(editing.optString("title")); prompt.setText(editing.optString("prompt"));
            String mode = editing.optString("mode");
            modeId = ScheduleStore.ONCE.equals(mode) ? 1 : ScheduleStore.INTERVAL.equals(mode) ? 2 : 3;
            if (editing.optLong("at") > 0) start.setTimeInMillis(editing.optLong("at"));
            String[] hm = editing.optString("time", "08:00").split(":"); hour = Integer.parseInt(hm[0]); minute = Integer.parseInt(hm[1]);
            long every = editing.optLong("interval_min", 60);
            if (every % 1440 == 0) { interval.setText(String.valueOf(every / 1440)); unit.setSelection(2); }
            else if (every % 60 == 0) { interval.setText(String.valueOf(every / 60)); unit.setSelection(1); }
            else { interval.setText(String.valueOf(every)); unit.setSelection(0); }
            platformIndex = Math.max(0, java.util.Arrays.asList(PLATFORMS).indexOf(editing.optString("platform")));
            model.setText(editing.optString("model")); effort.setText(editing.optString("effort"));
        }
        modes.check(modeId);
        platform.setSelection(platformIndex);
        syncPlatform(); syncMode();
    }

    private String platformId() { return PLATFORMS[Math.max(0, platform.getSelectedItemPosition())]; }

    private void syncMode() {
        int id = modes.getCheckedRadioButtonId();
        startRow.setVisibility(id == 3 ? View.GONE : View.VISIBLE);
        intervalRow.setVisibility(id == 2 ? View.VISIBLE : View.GONE);
        timeRow.setVisibility(id == 3 ? View.VISIBLE : View.GONE);
        startButton.setText(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(start.getTimeInMillis())));
        timeButton.setText(String.format(Locale.ROOT, "%02d:%02d", hour, minute));
    }

    /** Model and reasoning suggestions and the conversation list depend on the platform. */
    private void syncPlatform() {
        String id = platformId(); ModelProvider p = ModelProvider.forPlatform(id);
        List<String> models = new ArrayList<>(), efforts = new ArrayList<>();
        if (p == null) {
            models.addAll(saved("model")); efforts.addAll(saved("effort"));
        } else {
            for (String m : p.models) models.add(m);
            String configured = p.store(this).config().model; if (!configured.isEmpty() && !models.contains(configured)) models.add(0, configured);
            for (String e : p.efforts) efforts.add(e);
        }
        suggestions(model, models); suggestions(effort, efforts);
        boolean hasEffort = p == null || p.supportsThinking();
        effort.setVisibility(hasEffort ? View.VISIBLE : View.GONE); effortLabel.setVisibility(hasEffort ? View.VISIBLE : View.GONE);
        conversations.clear();
        conversations.add(new String[]{ScheduleStore.CONV_NEW, "", getString(R.string.schedule_conv_new), ""});
        conversations.add(new String[]{ScheduleStore.CONV_THREAD, "", getString(R.string.schedule_conv_thread), ""});
        JSONArray known = p == null ? ChatActivity.knownConversations(this) : new ApiConversationStore(this).conversations();
        for (int i = 0; i < known.length() && i < 60; i++) {
            JSONObject c = known.optJSONObject(i); if (c == null || c.optString("id").isEmpty()) continue;
            if (p != null && c.optInt("message_count") == 0) continue;
            String name = c.optString("title").trim(); if (name.isEmpty()) name = c.optString("id");
            conversations.add(new String[]{ScheduleStore.CONV_FIXED, c.optString("id"), getString(R.string.schedule_conv_fixed_named, name), name});
        }
        String[] labels = new String[conversations.size()]; for (int i = 0; i < labels.length; i++) labels[i] = conversations.get(i)[2];
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels); a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        conversation.setAdapter(a);
        int select = 0;
        if (editing != null && editing.optString("platform").equals(id)) {
            String mode = editing.optString("conversation_mode");
            for (int i = 0; i < conversations.size(); i++) {
                String[] c = conversations.get(i);
                if (c[0].equals(mode) && (!ScheduleStore.CONV_FIXED.equals(mode) || c[1].equals(editing.optString("conversation")))) { select = i; break; }
            }
        }
        conversation.setSelection(select);
    }
    private List<String> saved(String kind) {
        List<String> out = new ArrayList<>();
        try { JSONArray a = new JSONArray(getSharedPreferences(ChatActivity.MODEL_CHOICES, MODE_PRIVATE).getString(kind, "[]")); for (int i = 0; i < a.length(); i++) out.add(a.optString(i)); } catch (Exception ignored) {}
        return out;
    }

    private void pickStart() {
        new DatePickerDialog(this, (v, y, m, d) -> {
            start.set(y, m, d);
            new TimePickerDialog(this, (t, h, min) -> { start.set(Calendar.HOUR_OF_DAY, h); start.set(Calendar.MINUTE, min); start.set(Calendar.SECOND, 0); syncMode(); },
                    start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(this)).show();
        }, start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH)).show();
    }
    private void pickTime() {
        new TimePickerDialog(this, (t, h, min) -> { hour = h; minute = min; syncMode(); }, hour, minute, android.text.format.DateFormat.is24HourFormat(this)).show();
    }

    private void save() {
        try {
            JSONObject s = editing == null ? new JSONObject() : new JSONObject(editing.toString());
            int id = modes.getCheckedRadioButtonId();
            String mode = id == 1 ? ScheduleStore.ONCE : id == 2 ? ScheduleStore.INTERVAL : ScheduleStore.DAILY;
            long n = 0; try { n = Long.parseLong(interval.getText().toString().trim()); } catch (Exception ignored) {}
            long minutes = n * (unit.getSelectedItemPosition() == 2 ? 1440 : unit.getSelectedItemPosition() == 1 ? 60 : 1);
            String[] conv = conversations.get(Math.max(0, conversation.getSelectedItemPosition()));
            s.put("title", title.getText().toString()).put("prompt", prompt.getText().toString()).put("mode", mode)
                    .put("at", start.getTimeInMillis()).put("interval_min", minutes).put("time", String.format(Locale.ROOT, "%02d:%02d", hour, minute))
                    .put("platform", platformId()).put("model", model.getText().toString().trim()).put("effort", effort.getVisibility() == View.VISIBLE ? effort.getText().toString().trim() : "")
                    .put("conversation_mode", conv[0]).put("conversation", conv[1]).put("conversation_title", conv[3]);
            if (editing != null) s.put("enabled", true);
            ScheduleStore.save(this, s);
            Toast.makeText(this, R.string.schedule_saved, Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception e) {
            Toast.makeText(this, error(String.valueOf(e.getMessage())), Toast.LENGTH_LONG).show();
        }
    }
    private String error(String code) {
        switch (code) {
            case "schedule_prompt_required": return getString(R.string.schedule_error_prompt);
            case "schedule_interval_too_short": return getString(R.string.schedule_error_interval, ScheduleStore.MIN_INTERVAL_MIN);
            case "schedule_time_in_past": return getString(R.string.schedule_error_past);
            default: return code;
        }
    }
}
