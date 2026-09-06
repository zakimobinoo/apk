package com.boos.magneticscanner;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    static final int ROWS = 10, COLS = 12, POINTS = 120, RECORD_OFFSET = 443, RECORD_SIZE = 36;

    BluetoothAdapter adapter;
    BluetoothSocket socket;
    Thread reader;
    ExecutorService io = Executors.newSingleThreadExecutor();

    Spinner devices;
    Button connect, start, undo, save;
    TextView status, value, cell;
    GridLayout grid;
    double[] data = new double[POINTS];
    View[] boxes = new View[POINTS];
    int index = 0;
    StringBuilder partial = new StringBuilder();

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        Arrays.fill(data, Double.NaN);
        setContentView(ui());
        adapter = BluetoothAdapter.getDefaultAdapter();
        requestBt();
        refresh();
    }

    LinearLayout ui() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(14, 10, 14, 10);

        TextView title = t("Boos", 25);
        r.addView(title, lp(-1, 55));
        status = t("Bluetooth: ready", 14);
        r.addView(status, lp(-1, 40));

        LinearLayout c = new LinearLayout(this);
        devices = new Spinner(this);
        c.addView(devices, new LinearLayout.LayoutParams(0, 58, 1));
        connect = new Button(this);
        connect.setText("CONNECT");
        c.addView(connect, lp(125, 58));
        r.addView(c);

        LinearLayout b = new LinearLayout(this);
        start = new Button(this);
        start.setText("START SCAN");
        undo = new Button(this);
        undo.setText("UNDO");
        save = new Button(this);
        save.setText("SAVE V3D");
        b.addView(start, new LinearLayout.LayoutParams(0, 60, 1));
        b.addView(undo, new LinearLayout.LayoutParams(0, 60, 1));
        b.addView(save, new LinearLayout.LayoutParams(0, 60, 1));
        r.addView(b);

        value = t("Value: —", 22);
        cell = t("Point: —", 16);
        r.addView(value, lp(-1, 48));
        r.addView(cell, lp(-1, 36));

        ScrollView sc = new ScrollView(this);
        grid = new GridLayout(this);
        grid.setColumnCount(COLS);
        grid.setRowCount(ROWS);
        for (int i = 0; i < POINTS; i++) {
            View box = new View(this);
            box.setBackgroundColor(Color.LTGRAY);
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = 0;
            p.height = 48;
            p.columnSpec = GridLayout.spec(i % COLS, 1f);
            p.rowSpec = GridLayout.spec(i / COLS);
            p.setMargins(2, 2, 2, 2);
            grid.addView(box, p);
            boxes[i] = box;
        }
        sc.addView(grid);
        r.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));

        connect.setOnClickListener(v -> connect());
        start.setOnClickListener(v -> startScan());
        undo.setOnClickListener(v -> undo());
        save.setOnClickListener(v -> saveV3D());
        return r;
    }

    TextView t(String s, int size) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setGravity(Gravity.CENTER);
        return v;
    }

    LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    void requestBt() {
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
            }, 1);
        }
    }

    void refresh() {
        ArrayList<String> names = new ArrayList<>();
        if (adapter != null) {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                names.add(d.getName() + " | " + d.getAddress());
            }
        }
        devices.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
    }

    void connect() {
        if (devices.getSelectedItem() == null) {
            toast("No device selected");
            return;
        }
        String sel = devices.getSelectedItem().toString();
        String mac = sel.substring(sel.lastIndexOf("|") + 1).trim();
        status.setText("Connecting...");
        io.execute(() -> {
            try {
                if (socket != null) socket.close();
                BluetoothDevice dev = adapter.getRemoteDevice(mac);
                if (Build.VERSION.SDK_INT >= 31 &&
                        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("Bluetooth permission");
                }
                socket = dev.createRfcommSocketToServiceRecord(SPP);
                socket.connect();
                runOnUiThread(() -> status.setText("Connected: " + mac));
                startReader();
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("Connect failed");
                    toast(e.getMessage());
                });
            }
        });
    }

    void startReader() {
        if (reader != null) reader.interrupt();
        reader = new Thread(() -> {
            try {
                InputStream in = socket.getInputStream();
                byte[] buf = new byte[256];
                while (!Thread.interrupted()) {
                    int n = in.read(buf);
                    if (n <= 0) break;
                    partial.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
                    process();
                }
            } catch (Exception ignored) {
            }
        });
        reader.start();
    }

    void process() {
        while (true) {
            int nl = partial.indexOf("\n");
            if (nl < 0) break;
            String line = partial.substring(0, nl).trim();
            partial.delete(0, nl + 1);
            if (line.isEmpty()) continue;
            try {
                double v = Double.parseDouble(line);
                runOnUiThread(() -> addPoint(v));
            } catch (Exception ignored) {
            }
        }
    }

    void startScan() {
        index = 0;
        Arrays.fill(data, Double.NaN);
        for (View box : boxes) box.setBackgroundColor(Color.LTGRAY);
        value.setText("Value: —");
        cell.setText("Point: 0 / 120");
        status.setText("Scanning...");
        if (socket == null || !socket.isConnected()) {
            toast("Not connected");
            return;
        }
        io.execute(() -> {
            try {
                socket.getOutputStream().write("S\n".getBytes(StandardCharsets.US_ASCII));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Start failed: " + e.getMessage()));
            }
        });
    }

    void addPoint(double v) {
        if (index >= POINTS) return;
        data[index] = v;
        boxes[index].setBackgroundColor(color(v));
        value.setText("Value: " + fmt(v));
        cell.setText("Point: " + (index + 1) + " / 120");
        index++;
        if (index >= POINTS) status.setText("Scan complete");
    }

    void undo() {
        if (index <= 0) return;
        index--;
        data[index] = Double.NaN;
        boxes[index].setBackgroundColor(Color.LTGRAY);
        value.setText("Value: —");
        cell.setText("Point: " + index + " / 120");
    }

    int color(double v) {
        double mn = Double.POSITIVE_INFINITY, mx = Double.NEGATIVE_INFINITY;
        for (double x : data) {
            if (!Double.isNaN(x)) {
                mn = Math.min(mn, x);
                mx = Math.max(mx, x);
            }
        }
        float t = (float) ((mx <= mn) ? 0.5 : (v - mn) / (mx - mn));
        t = Math.max(0, Math.min(1, t));
        return Color.HSVToColor(new float[]{240f - 240f * t, .9f, .95f});
    }

    void saveV3D() {
        if (index == 0) {
            toast("No scan data.");
            return;
        }
        if (index < POINTS) {
            toast("Finish all 120 points before saving V3D.");
            return;
        }
        try {
            InputStream is = getAssets().open("m3_template.v3d");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = is.read(b)) > 0) out.write(b, 0, n);
            is.close();
            byte[] file = out.toByteArray();
            if (file.length < RECORD_OFFSET + POINTS * RECORD_SIZE)
                throw new IOException("Template size invalid");

            for (int i = 0; i < POINTS; i++) {
                int off = RECORD_OFFSET + i * RECORD_SIZE;
                ByteBuffer.wrap(file, off, 4).order(ByteOrder.LITTLE_ENDIAN).putFloat((float) data[i]);
            }

            File dir = new File(getExternalFilesDir(null), "Boos");
            if (!dir.exists()) dir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File f = new File(dir, "Boos_" + stamp + ".v3d");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(file);
            }
            toast("Saved: " + f.getAbsolutePath());
            status.setText("V3D saved");
        } catch (Exception e) {
            toast("Save failed: " + e.getMessage());
        }
    }

    String fmt(double x) {
        return String.format(Locale.US, "%.6f", x).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    void toast(String s) {
        runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }

    @Override
    protected void onDestroy() {
        try {
            if (reader != null) reader.interrupt();
        } catch (Exception ignored) {
        }
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        io.shutdownNow();
        super.onDestroy();
    }
}
