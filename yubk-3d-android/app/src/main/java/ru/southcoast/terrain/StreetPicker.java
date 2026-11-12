package ru.southcoast.terrain;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import java.util.*;

/** Graphic street browser with search, settlement filters and one-tap teleport. */
final class StreetPicker {
    interface Listener { void onSelect(Region.Street street); }

    private final Context context;
    private final Region region;
    private final Listener listener;
    private final FrameLayout overlay;
    private final LinearLayout panel;
    private final EditText search;
    private final LinearLayout chips;
    private final ListView list;
    private final TextView count, empty;
    private final ArrayList<Region.Street> all = new ArrayList<>();
    private final ArrayList<Region.Street> shown = new ArrayList<>();
    private final ArrayList<String> cityNames = new ArrayList<>();
    private final ArrayList<TextView> chipViews = new ArrayList<>();
    private final HashMap<String, Integer> cityCounts = new HashMap<>();
    private final HashMap<String, Float> cityOrder = new HashMap<>();
    private StreetAdapter adapter;
    private String cityFilter;

    StreetPicker(Context context, Region region, Listener listener) {
        this.context = context;
        this.region = region;
        this.listener = listener;
        collectStreets();

        overlay = new FrameLayout(context);
        overlay.setBackgroundColor(Color.argb(214, 3, 12, 15));
        overlay.setClickable(true);
        overlay.setVisibility(View.GONE);

        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(8));
        GradientDrawable panelBg = rounded(Color.rgb(12, 32, 36), 24, Color.argb(115, 145, 205, 193), 1);
        panel.setBackground(panelBg);
        panel.setElevation(dp(18));
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(context.getResources().getDisplayMetrics().heightPixels * .82f),
                Gravity.BOTTOM);
        panelParams.setMargins(dp(6), 0, dp(6), dp(6));
        overlay.addView(panel, panelParams);

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Улицы ЮБК", 21, Color.rgb(240, 249, 240), true);
        title.setPadding(0, 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        count = text("", 12, Color.rgb(255, 196, 80), true);
        count.setGravity(Gravity.CENTER);
        count.setPadding(dp(9), dp(5), dp(9), dp(5));
        count.setBackground(rounded(Color.argb(120, 39, 65, 68), 12, Color.argb(90, 255, 204, 112), 1));
        header.addView(count);
        TextView close = text("×", 29, Color.rgb(218, 242, 234), false);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Закрыть выбор улицы");
        close.setPadding(dp(12), 0, dp(4), dp(3));
        close.setOnClickListener(v -> hide());
        header.addView(close);
        panel.addView(header);

        TextView hint = text("Город → улица → телепорт в 3D", 12, Color.rgb(164, 213, 202), false);
        hint.setPadding(dp(1), dp(1), 0, dp(7));
        panel.addView(hint);

        search = new EditText(context);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTextColor(Color.rgb(239, 248, 239));
        search.setHintTextColor(Color.argb(165, 192, 222, 211));
        search.setHint("Найти улицу или город");
        search.setPadding(dp(14), dp(9), dp(14), dp(9));
        search.setBackground(rounded(Color.argb(170, 22, 48, 52), 14, Color.argb(100, 129, 193, 181), 1));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { filter(); }
        });
        panel.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView chipScroll = new HorizontalScrollView(context);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setClipToPadding(false);
        chipScroll.setPadding(0, dp(7), 0, dp(5));
        chips = row();
        chipScroll.addView(chips, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(chipScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        buildChips();

        FrameLayout listArea = new FrameLayout(context);
        list = new ListView(context);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setBackgroundColor(Color.TRANSPARENT);
        list.setVerticalScrollBarEnabled(false);
        adapter = new StreetAdapter();
        list.setAdapter(adapter);
        listArea.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        empty = text("Ничего не найдено", 15, Color.rgb(180, 208, 198), false);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        listArea.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panel.addView(listArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView footer = text("Улицы сгруппированы по ближайшему городу или посёлку. Переход загружает нужный участок 3D.", 10, Color.argb(180, 189, 218, 208), false);
        footer.setPadding(dp(2), dp(5), dp(2), 0);
        panel.addView(footer);

        list.setOnItemClickListener((parent, view, position, id) -> select(shown.get(position)));
    }

    private int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private LinearLayout row() { LinearLayout v = new LinearLayout(context); v.setOrientation(LinearLayout.HORIZONTAL); return v; }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(context);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setPadding(dp(4), dp(3), dp(4), dp(3));
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private GradientDrawable rounded(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (strokeWidth > 0) d.setStroke(dp(strokeWidth), strokeColor);
        return d;
    }

    private void collectStreets() {
        LinkedHashMap<String, Region.Street> unique = new LinkedHashMap<>();
        for (Region.Street street : region.streets) {
            String key = street.city + "\u0000" + street.name;
            if (!unique.containsKey(key)) unique.put(key, street);
        }
        all.addAll(unique.values());
        Collections.sort(all, (a, b) -> {
            int byCity = Float.compare(a.cityRouteM, b.cityRouteM);
            return byCity != 0 ? byCity : a.name.compareToIgnoreCase(b.name);
        });
        for (Region.Street street : all) {
            Integer count = cityCounts.get(street.city);
            cityCounts.put(street.city, count == null ? 1 : count + 1);
            Float route = cityOrder.get(street.city);
            cityOrder.put(street.city, route == null ? street.cityRouteM : Math.min(route, street.cityRouteM));
        }
        cityNames.addAll(cityCounts.keySet());
        Collections.sort(cityNames, (a, b) -> {
            int byRoute = Float.compare(cityOrder.get(a), cityOrder.get(b));
            return byRoute != 0 ? byRoute : a.compareToIgnoreCase(b);
        });
    }

    private void buildChips() {
        chips.removeAllViews();
        chipViews.clear();
        addChip("Все", "ALL");
        for (String city : cityNames) addChip(city + "  " + cityCounts.get(city), city);
    }

    private void addChip(String label, String key) {
        TextView chip = text(label, 12, Color.rgb(220, 241, 232), false);
        chip.setTag(key);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(11), dp(6), dp(11), dp(6));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, dp(6), 0);
        chip.setOnClickListener(v -> {
            cityFilter = "ALL".equals(key) ? null : key;
            updateChipStyles();
            filter();
        });
        chipViews.add(chip);
        chips.addView(chip, params);
    }

    private void updateChipStyles() {
        for (TextView chip : chipViews) {
            String key = (String) chip.getTag();
            boolean active = cityFilter == null ? "ALL".equals(key) : key.equals(cityFilter);
            chip.setTextColor(active ? Color.rgb(29, 46, 44) : Color.rgb(220, 241, 232));
            chip.setBackground(rounded(active ? Color.rgb(255, 196, 80) : Color.argb(150, 25, 51, 55),
                    13, active ? Color.argb(160, 255, 229, 166) : Color.argb(90, 134, 193, 180), 1));
        }
    }

    private void filter() {
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (Region.Street street : all) {
            if (cityFilter != null && !cityFilter.equals(street.city)) continue;
            if (!query.isEmpty()) {
                String haystack = (street.name + " " + street.city).toLowerCase(Locale.ROOT);
                if (!haystack.contains(query)) continue;
            }
            shown.add(street);
        }
        count.setText(shown.size() + " / " + all.size());
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
        list.setSelection(0);
    }

    private void select(Region.Street street) {
        hide();
        listener.onSelect(street);
    }

    void show(ViewGroup root) {
        if (overlay.getParent() == null) {
            root.addView(overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        cityFilter = null;
        search.setText("");
        updateChipStyles();
        filter();
        overlay.setVisibility(View.VISIBLE);
        overlay.bringToFront();
    }

    void hide() {
        if (overlay.getVisibility() != View.VISIBLE) return;
        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
        overlay.setVisibility(View.GONE);
    }

    private final class StreetAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout wrapper;
            TextView name, subtitle, arrow;
            if (convertView == null) {
                wrapper = new LinearLayout(context);
                wrapper.setOrientation(LinearLayout.HORIZONTAL);
                wrapper.setGravity(Gravity.CENTER_VERTICAL);
                wrapper.setPadding(dp(3), dp(3), dp(3), dp(3));
                wrapper.setBackgroundColor(Color.TRANSPARENT);

                View accent = new View(context);
                accent.setBackground(rounded(Color.rgb(255, 194, 77), 2, Color.TRANSPARENT, 0));
                LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(dp(3), dp(30));
                accentParams.setMargins(dp(5), 0, dp(10), 0);
                wrapper.addView(accent, accentParams);

                LinearLayout words = new LinearLayout(context);
                words.setOrientation(LinearLayout.VERTICAL);
                name = text("", 16, Color.rgb(241, 249, 239), true);
                name.setPadding(0, 0, 0, 0);
                subtitle = text("", 11, Color.rgb(156, 203, 192), false);
                subtitle.setPadding(0, dp(2), 0, 0);
                words.addView(name);
                words.addView(subtitle);
                wrapper.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

                arrow = text("›", 26, Color.rgb(255, 199, 91), false);
                arrow.setGravity(Gravity.CENTER);
                wrapper.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(42)));

                wrapper.setTag(new View[]{name, subtitle});
            } else {
                wrapper = (LinearLayout) convertView;
                View[] views = (View[]) wrapper.getTag();
                name = (TextView) views[0];
                subtitle = (TextView) views[1];
            }

            Region.Street street = shown.get(position);
            Region.Tile tile = region.tileById(street.tileId);
            name.setText(street.name);
            subtitle.setText(street.city + (tile == null ? "" : "  ·  " + range(tile)));
            wrapper.setBackground(rounded(position % 2 == 0 ? Color.argb(205, 20, 45, 49) : Color.argb(185, 18, 40, 44),
                    13, Color.argb(58, 132, 190, 179), 1));
            return wrapper;
        }
    }

    private String range(Region.Tile tile) {
        String name = tile.name;
        int dot = name.indexOf(" · ");
        return dot >= 0 ? name.substring(dot + 3) : name;
    }
}
