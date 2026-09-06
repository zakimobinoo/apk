package com.boos.magneticscanner;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Boos Magnetic Scanner — v2.3
 * English UI, no name field, grid colors as you scan (like before)
 */
public class MainActivity extends Activity {

    static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    static final int ROWS = 10, COLS = 12, POINTS = 120;
    static final int RECORD_OFFSET = 443, RECORD_SIZE = 36;

    BluetoothAdapter adapter;
    BluetoothSocket socket;
    Thread reader;
    volatile boolean scanning = false;
    ExecutorService io = Executors.newSingleThreadExecutor();

    Spinner devices;
    Button btnConnect, btnStart, btnUndo, btnSave, btnRefresh;
    TextView status, value, cell, hint;
    GridLayout grid;
    double[] data = new double[POINTS];
    View[] boxes = new View[POINTS];
    int index = 0;
    StringBuilder partial = new StringBuilder();

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        Arrays.fill(data, Double.NaN);
        setContentView(buildUi());
        adapter = BluetoothAdapter.getDefaultAdapter();
        requestBtPermissions();
        refreshDeviceList();
    }

    LinearLayout buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.setBackgroundColor(Color.parseColor("#F5F7FA"));

        TextView title = makeText("Boos Magnetic Scanner", 22, true);
        title.setTextColor(Color.parseColor("#1565C0"));
        root.addView(title, lp(-1, dp(36)));

        status = makeText("Bluetooth: ready", 14, false);
        status.setTextColor(Color.parseColor("#37474F"));
        root.addView(status, lp(-1, dp(26)));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        devices = new Spinner(this);
        devices.setBackground(roundedBg(Color.WHITE, 8));
        row1.addView(devices, new LinearLayout.LayoutParams(0, dp(48), 1f));

        btnRefresh = makeButton("↻", Color.parseColor("#607D8B"));
        btnRefresh.setOnClickListener(v -> refreshDeviceList());
        row1.addView(btnRefresh, lp(dp(48), dp(48)));

        btnConnect = makeButton("CONNECT", Color.parseColor("#1565C0"));
        btnConnect.setOnClickListener(v -> connect());
        row1.addView(btnConnect, lp(dp(110), dp(48)));

        root.addView(row1, lp(-1, dp(54)));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(4), 0, dp(4));

        btnStart = makeButton("START SCAN", Color.parseColor("#2E7D32"));
        btnStart.setOnClickListener(v -> toggleScan());
        row2.addView(btnStart, new LinearLayout.LayoutParams(0, dp(50), 1.2f));

        btnUndo = makeButton("UNDO", Color.parseColor("#F57C00"));
        btnUndo.setOnClickListener(v -> undo());
        row2.addView(btnUndo, new LinearLayout.LayoutParams(0, dp(50), 1f));

        btnSave = makeButton("SAVE V3D", Color.parseColor("#6A1B9A"));
        btnSave.setOnClickListener(v -> saveV3D());
        row2.addView(btnSave, new LinearLayout.LayoutParams(0, dp(50), 1.2f));

        root.addView(row2, lp(-1, dp(58)));

        value = makeText("Value: —", 20, true);
        value.setTextColor(Color.parseColor("#212121"));
        root.addView(value, lp(-1, dp(34)));

        cell = makeText("Point: 0 / 120", 15, false);
        cell.setTextColor(Color.parseColor("#546E7A"));
        root.addView(cell, lp(-1, dp(26)));

        hint = makeText("1) Select device  2) CONNECT  3) START SCAN  4) Press device button", 12, false);
        hint.setTextColor(Color.parseColor("#78909C"));
        root.addView(hint, lp(-1, dp(32)));

        ScrollView sc = new ScrollView(this);
        grid = new GridLayout(this);
        grid.setColumnCount(COLS);
        grid.setRowCount(ROWS);
        for (int i = 0; i < POINTS; i++) {
            View box = new View(this);
            box.setBackgroundColor(Color.LTGRAY);
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = 0;
            p.height = dp(36);
            p.columnSpec = GridLayout.spec(i % COLS, 1f);
            p.rowSpec = GridLayout.spec(i / COLS);
            p.setMargins(dp(2), dp(2), dp(2), dp(2));
            grid.addView(box, p);
            boxes[i] = box;
        }
        sc.addView(grid);
        root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1f));

        return root;
    }

    TextView makeText(String s, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setGravity(Gravity.CENTER);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    Button makeButton(String text, int bgColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(roundedBg(bgColor, 10));
        b.setPadding(dp(6), dp(6), dp(6), dp(6));
        return b;
    }

    GradientDrawable roundedBg(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    void requestBtPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                }, 1);
            }
        }
    }

    void refreshDeviceList() {
        ArrayList<String> names = new ArrayList<>();
        if (adapter == null) {
            status.setText("Bluetooth not supported");
            return;
        }
        if (!adapter.isEnabled()) {
            status.setText("Bluetooth is OFF — turn it on");
            toast("Please turn on Bluetooth");
            return;
        }
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    String name = d.getName();
                    if (name == null || name.isEmpty()) name = "Unknown";
                    names.add(name + " | " + d.getAddress());
                }
            }
        } catch (SecurityException e) {
            status.setText("Bluetooth permission denied");
            toast("Grant BLUETOOTH_CONNECT permission");
            return;
        }
        if (names.isEmpty()) {
            names.add("No paired devices");
            status.setText("No paired devices — pair first");
        } else {
            status.setText("Bluetooth: ready (" + names.size() + " device" + (names.size() > 1 ? "s" : "") + ")");
        }
        devices.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
    }

    void connect() {
        if (devices.getSelectedItem() == null) {
            toast("No device selected");
            return;
        }
        String sel = devices.getSelectedItem().toString();
        if (!sel.contains("|")) {
            toast("Pair the device first in phone settings");
            return;
        }
        final String mac = sel.substring(sel.lastIndexOf("|") + 1).trim();
        status.setText("Connecting...");
        btnConnect.setEnabled(false);

        io.execute(() -> {
            try {
                closeSocketQuietly();
                BluetoothDevice dev = adapter.getRemoteDevice(mac);

                if (Build.VERSION.SDK_INT >= 31 &&
                        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("BLUETOOTH_CONNECT permission missing");
                }

                BluetoothSocket s = null;
                try {
                    s = dev.createRfcommSocketToServiceRecord(SPP);
                    s.connect();
                } catch (IOException e1) {
                    try {
                        if (s != null) try { s.close(); } catch (Exception ignored) {}
                        s = (BluetoothSocket) dev.getClass()
                                .getMethod("createInsecureRfcommSocketToServiceRecord", UUID.class)
                                .invoke(dev, SPP);
                        s.connect();
                    } catch (Exception e2) {
                        try {
                            if (s != null) try { s.close(); } catch (Exception ignored) {}
                            s = (BluetoothSocket) dev.getClass()
                                    .getMethod("createRfcommSocket", int.class)
                                    .invoke(dev, 1);
                            s.connect();
                        } catch (Exception e3) {
                            throw new IOException("Connection failed: " + e1.getMessage());
                        }
                    }
                }

                socket = s;
                runOnUiThread(() -> {
                    status.setText("Connected ✓  " + mac);
                    status.setTextColor(Color.parseColor("#2E7D32"));
                    btnConnect.setEnabled(true);
                    toast("Connected successfully");
                });
                startReader();
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("Connection failed");
                    status.setTextColor(Color.parseColor("#C62828"));
                    btnConnect.setEnabled(true);
                    toast("Error: " + e.getMessage());
                });
            }
        });
    }

    void closeSocketQuietly() {
        try {
            if (reader != null) {
                reader.interrupt();
                reader = null;
            }
        } catch (Exception ignored) {}
        try {
            if (socket != null) {
                socket.close();
                socket = null;
            }
        } catch (Exception ignored) {}
    }

    void startReader() {
        if (reader != null) reader.interrupt();
        reader = new Thread(() -> {
            try {
                InputStream in = socket.getInputStream();
                byte[] buf = new byte[512];
                while (!Thread.interrupted() && socket != null && socket.isConnected()) {
                    int n = in.read(buf);
                    if (n <= 0) break;
                    partial.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
                    processIncoming();
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("Disconnected");
                    status.setTextColor(Color.parseColor("#C62828"));
                });
            }
        }, "bt-reader");
        reader.start();
    }

    void processIncoming() {
        while (true) {
            int nl = partial.indexOf("\n");
            if (nl < 0) {
                nl = partial.indexOf("\r");
                if (nl < 0) break;
            }
            String line = partial.substring(0, nl).trim();
            partial.delete(0, nl + 1);
            if (line.isEmpty()) continue;

            try {
                String[] parts = line.split("[\\s,;]+");
                for (String p : parts) {
                    if (p.isEmpty()) continue;
                    final double v = Double.parseDouble(p);
                    runOnUiThread(() -> onValueReceived(v));
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    void toggleScan() {
        if (socket == null || !socket.isConnected()) {
            toast("Connect to device first");
            return;
        }
        if (!scanning) {
            index = 0;
            Arrays.fill(data, Double.NaN);
            for (View box : boxes) box.setBackgroundColor(Color.LTGRAY);
            value.setText("Value: —");
            cell.setText("Point: 0 / 120");
            scanning = true;
            btnStart.setText("STOP SCAN");
            btnStart.setBackground(roundedBg(Color.parseColor("#C62828"), 10));
            status.setText("Scanning... press device button");
            status.setTextColor(Color.parseColor("#1565C0"));
            toast("Scan started — press device button for each point");
        } else {
            scanning = false;
            btnStart.setText("START SCAN");
            btnStart.setBackground(roundedBg(Color.parseColor("#2E7D32"), 10));
            status.setText("Scan stopped (" + index + " points)");
            status.setTextColor(Color.parseColor("#37474F"));
        }
    }

    void onValueReceived(double v) {
        value.setText("Value: " + fmt(v));

        if (!scanning) return;
        if (index >= POINTS) {
            scanning = false;
            btnStart.setText("START SCAN");
            btnStart.setBackground(roundedBg(Color.parseColor("#2E7D32"), 10));
            status.setText("Scan complete (120 points)");
            status.setTextColor(Color.parseColor("#2E7D32"));
            toast("Scan complete");
            return;
        }

        data[index] = v;
        boxes[index].setBackgroundColor(colorFor(v));
        cell.setText("Point: " + (index + 1) + " / 120");
        index++;

        if (index >= POINTS) {
            scanning = false;
            btnStart.setText("START SCAN");
            btnStart.setBackground(roundedBg(Color.parseColor("#2E7D32"), 10));
            status.setText("Scan complete ✓");
            status.setTextColor(Color.parseColor("#2E7D32"));
            toast("120 points recorded — you can save now");
        }
    }

    void undo() {
        if (index <= 0) {
            toast("Nothing to undo");
            return;
        }
        index--;
        data[index] = Double.NaN;
        boxes[index].setBackgroundColor(Color.LTGRAY);
        value.setText("Value: —");
        cell.setText("Point: " + index + " / 120");
        if (!scanning) {
            status.setText("One point undone");
        }
    }

    int colorFor(double v) {
        double mn = Double.POSITIVE_INFINITY, mx = Double.NEGATIVE_INFINITY;
        for (double x : data) {
            if (!Double.isNaN(x)) {
                mn = Math.min(mn, x);
                mx = Math.max(mx, x);
            }
        }
        float t = (mx <= mn) ? 0.5f : (float) ((v - mn) / (mx - mn));
        t = Math.max(0f, Math.min(1f, t));
        return Color.HSVToColor(new float[]{240f - 240f * t, 0.85f, 0.95f});
    }

    void saveV3D() {
        if (index == 0) {
            toast("No data to save");
            return;
        }
        if (index < POINTS) {
            toast((POINTS - index) + " points remaining. Finish the scan first.");
            return;
        }
        try {
            InputStream is = getAssets().open("m3_template.v3d");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            is.close();
            byte[] file = out.toByteArray();

            if (file.length < RECORD_OFFSET + POINTS * RECORD_SIZE) {
                throw new IOException("Invalid V3D template size");
            }

            for (int i = 0; i < POINTS; i++) {
                int off = RECORD_OFFSET + i * RECORD_SIZE;
                float val = Double.isNaN(data[i]) ? 0f : (float) data[i];
                ByteBuffer.wrap(file, off, 4).order(ByteOrder.LITTLE_ENDIAN).putFloat(val);
            }

            File dir = new File(getExternalFilesDir(null), "Boos");
            if (!dir.exists()) dir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File f = new File(dir, "Boos_" + stamp + ".v3d");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(file);
            }
            toast("Saved:\n" + f.getName());
            status.setText("V3D saved: " + f.getName());
            status.setTextColor(Color.parseColor("#6A1B9A"));
        } catch (Exception e) {
            toast("Save failed: " + e.getMessage());
        }
    }

    String fmt(double x) {
        return String.format(Locale.US, "%.4f", x).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    void toast(String s) {
        runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }

    @Override
    protected void onDestroy() {
        scanning = false;
        closeSocketQuietly();
        io.shutdownNow();
        super.onDestroy();
    }
}
