package modder.hub.dexeditor.mcp;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;

public class McpServerDialog {
    private static final int DEFAULT_PORT = 8788;
    private static int activePort = DEFAULT_PORT;
    private static final StringBuilder logBuffer = new StringBuilder();

    public static void show(final Activity activity) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
        builder.setTitle("MCP Server Control");

        // Main Layout
        LinearLayout mainLayout = new LinearLayout(activity);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        int padding = dpToPx(activity, 16);
        mainLayout.setPadding(padding, padding, padding, padding);

        // Port Input Layout
        final TextInputLayout portInputLayout = new TextInputLayout(activity, null, com.google.android.material.R.style.Widget_MaterialComponents_TextInputLayout_OutlinedBox);
        portInputLayout.setHint("Port");
        LinearLayout.LayoutParams portParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.wrapContent);
        portParams.bottomMargin = dpToPx(activity, 12);
        portInputLayout.setLayoutParams(portParams);

        final EditText portEditText = new EditText(activity);
        portEditText.setInputType(InputType.TYPE_CLASS_NUMBER);
        portEditText.setText(String.valueOf(activePort));
        portInputLayout.addView(portEditText);
        mainLayout.addView(portInputLayout);

        // Status & IP Addresses TextView
        final TextView statusTextView = new TextView(activity);
        statusTextView.setTextSize(14);
        statusTextView.setPadding(0, 0, 0, dpToPx(activity, 12));
        mainLayout.addView(statusTextView);

        // Start/Stop Button
        final MaterialButton actionButton = new MaterialButton(activity, null, com.google.android.material.R.style.Widget_MaterialComponents_Button);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.wrapContent);
        btnParams.bottomMargin = dpToPx(activity, 16);
        actionButton.setLayoutParams(btnParams);
        mainLayout.addView(actionButton);

        // Console Log header
        TextView consoleHeader = new TextView(activity);
        consoleHeader.setText("Console Logs:");
        consoleHeader.setTypeface(Typeface.DEFAULT_BOLD);
        consoleHeader.setPadding(0, 0, 0, dpToPx(activity, 4));
        mainLayout.addView(consoleHeader);

        // ScrollView for Log
        final ScrollView scrollView = new ScrollView(activity);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(activity, 150));
        scrollView.setLayoutParams(scrollParams);
        scrollView.setBackgroundColor(Color.parseColor("#1E1E1E"));

        final TextView logTextView = new TextView(activity);
        logTextView.setTextColor(Color.parseColor("#A9B7C6"));
        logTextView.setTypeface(Typeface.MONOSPACE);
        logTextView.setTextSize(11);
        int logPadding = dpToPx(activity, 8);
        logTextView.setPadding(logPadding, logPadding, logPadding, logPadding);
        logTextView.setText(logBuffer.toString());
        scrollView.addView(logTextView);
        mainLayout.addView(scrollView);

        builder.setView(mainLayout);
        builder.setPositiveButton("Close", null);

        final androidx.appcompat.app.AlertDialog dialog = builder.create();

        // Update UI State Helper
        final Runnable updateUi = new Runnable() {
            @Override
            public void run() {
                boolean running = McpServer.isRunning();
                portEditText.setEnabled(!running);
                if (running) {
                    actionButton.setText("Stop Server");
                    actionButton.setBackgroundColor(Color.parseColor("#D32F2F")); // Red

                    List<String> ips = McpServer.getIpAddresses();
                    StringBuilder sb = new StringBuilder("Status: Running\nAddresses:\n");
                    sb.append("  - http://127.0.0.1:").append(activePort).append("/mcp\n");
                    for (String ip : ips) {
                        sb.append("  - http://").append(ip).append(":").append(activePort).append("/mcp\n");
                    }
                    statusTextView.setText(sb.toString());
                    statusTextView.setTextColor(Color.parseColor("#388E3C")); // Green
                } else {
                    actionButton.setText("Start Server");
                    actionButton.setBackgroundColor(Color.parseColor("#1976D2")); // Blue
                    statusTextView.setText("Status: Stopped");
                    statusTextView.setTextColor(Color.GRAY);
                }
            }
        };

        // Initialize UI State
        updateUi.run();

        // Button Listener
        actionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (McpServer.isRunning()) {
                    McpServer.stop();
                    updateUi.run();
                } else {
                    String portStr = portEditText.getText().toString().trim();
                    int port = DEFAULT_PORT;
                    try {
                        port = Integer.parseInt(portStr);
                    } catch (Exception ignored) {}

                    try {
                        McpServer.start(port);
                        activePort = port;
                        updateUi.run();
                        Toast.makeText(activity, "Server started successfully", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(activity, "Failed to start server: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            }
        });

        // Set Logger listener to pipe logs directly to the Dialog's TextView
        McpServer.setLogListener(new McpServer.LogListener() {
            @Override
            public void onLog(final String message) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        logBuffer.append(message).append("\n");
                        // Keep buffer size reasonable
                        if (logBuffer.length() > 50000) {
                            logBuffer.delete(0, 10000);
                        }
                        logTextView.setText(logBuffer.toString());
                        // Auto scroll to bottom
                        scrollView.post(new Runnable() {
                            @Override
                            public void run() {
                                scrollView.fullScroll(View.FOCUS_DOWN);
                            }
                        });
                    }
                });
            }
        });

        dialog.show();
    }

    private static int dpToPx(Context context, int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round((float) dp * density);
    }
}
