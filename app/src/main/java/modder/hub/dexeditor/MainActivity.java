/*
 * Dex-Editor-Android an Advanced Dex Editor for Android
 * Copyright 2024-25, developer-krushna
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *     * Neither the name of developer-krushna nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package modder.hub.dexeditor;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.github.angads25.filepicker.controller.DialogSelectionListener;
import com.github.angads25.filepicker.model.DialogProperties;
import com.github.angads25.filepicker.view.FilePickerDialog;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import modder.hub.dexeditor.activity.DexEditorActivity;
import modder.hub.dexeditor.mcp.McpServerDialog;
import modder.hub.dexeditor.mcp.McpServer;
import android.view.Menu;
import android.view.MenuItem;
import modder.hub.dexeditor.utils.FilePermissionManager;
import modder.hub.dexeditor.utils.FileUtil;

public class MainActivity extends AppCompatActivity implements FilePermissionManager.PermissionCallback {
    // UI Components
    private Toolbar toolbar;
    private EditText dexFilePathEditText;
    private MaterialButton pickDexFileButton;
    private MaterialButton openDexFileButton;
    private TextView githubLinkTextView;

    private SharedPreferences sharedPreferences;
    private final Intent dexEditorIntent = new Intent();
    private final Intent githubIntent = new Intent();

    private ActivityResultLauncher<Intent> manageStorageLauncher;
    private ActivityResultLauncher<String[]> requestPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        // Register launchers before initialization
        manageStorageLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                new androidx.activity.result.ActivityResultCallback<androidx.activity.result.ActivityResult>() {
                    @Override
                    public void onActivityResult(androidx.activity.result.ActivityResult result) {
                        if (FilePermissionManager.hasStoragePermission(MainActivity.this)) {
                            onPermissionGranted();
                        } else {
                            onPermissionDenied();
                        }
                    }
                }
        );

        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                new androidx.activity.result.ActivityResultCallback<Map<String, Boolean>>() {
                    @Override
                    public void onActivityResult(Map<String, Boolean> result) {
                        boolean allGranted = true;
                        for (Map.Entry<String, Boolean> entry : result.entrySet()) {
                            if (!entry.getValue()) {
                                allGranted = false;
                                break;
                            }
                        }
                        if (allGranted) {
                            onPermissionGranted();
                        } else {
                            onPermissionDenied();
                        }
                    }
                }
        );

        // Initialize UI first but keep it disabled
        initialize(savedInstanceState);
        disableUI();

        // Check permissions through the manager
        FilePermissionManager.checkStoragePermission(this, manageStorageLauncher, requestPermissionLauncher, this);
    }

    @Override
    public void onPermissionGranted() {
        enableUI();
        initializeLogic();
    }

    @Override
    public void onPermissionDenied() {
        // Will either show another dialog or exit via the manager
        FilePermissionManager.checkStoragePermission(this, manageStorageLauncher, requestPermissionLauncher, this);
    }

    private void disableUI() {
        pickDexFileButton.setEnabled(false);
        openDexFileButton.setEnabled(false);
        dexFilePathEditText.setEnabled(false);
    }

    private void enableUI() {
        pickDexFileButton.setEnabled(true);
        openDexFileButton.setEnabled(true);
        dexFilePathEditText.setEnabled(true);
        initializeLogic();
    }

    // Initialize UI components and set up listeners
    private void initialize(Bundle ignoredSavedInstanceState) {
        toolbar = findViewById(R.id._toolbar);
        setSupportActionBar(toolbar);

        // Disable back button in the toolbar for MainActivity
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            getSupportActionBar().setHomeButtonEnabled(false);
        }

        pickDexFileButton = findViewById(R.id.materialbutton1);
        openDexFileButton = findViewById(R.id.open_dex);
        githubLinkTextView = findViewById(R.id.textview1);
        dexFilePathEditText = findViewById(R.id.edit_path);

        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences("pref", 0);

        // Set up button click listeners
        pickDexFileButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                pickDexFile(dexFilePathEditText, "Pick .dex file(s)", "Pick", "dex");
            }
        });



        openDexFileButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (dexFilePathEditText.getText().toString().isEmpty()){
                    return;
                }
                final List<String> selectedFilePaths = parseDexPathList(dexFilePathEditText.getText().toString());
                if (selectedFilePaths.isEmpty()) {
                    return;
                }

                if (McpServer.isRunning()) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                McpServer.loadDexDirectly(selectedFilePaths);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }).start();
                }

                ArrayList<String> filePathsArrayList = new ArrayList<String>(selectedFilePaths);
                dexEditorIntent.setClass(MainActivity.this, DexEditorActivity.class);
                dexEditorIntent.putStringArrayListExtra("SelectedDexFiles", filePathsArrayList);
                startActivity(dexEditorIntent);

            }
        });

        githubLinkTextView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                githubIntent.setAction("android.intent.action.VIEW");
                githubIntent.setData(Uri.parse("https://github.com/developer-krushna/Dex-Editor-Android"));
                startActivity(githubIntent);
            }
        });
    }

    // Initialize app logic (currently empty)
    private void initializeLogic() {
        // Add app logic here if needed
    }

    // Open a file picker dialog to select a .dex file
    public void pickDexFile(final TextView textView, String title, String positiveButtonText, String fileExtension) {
        final String lastPathKey = "LastPath";
        DialogProperties dialogProperties = new DialogProperties();
        dialogProperties.selection_mode = 1; // Multi file selection
        dialogProperties.selection_type = 0; // File selection
        dialogProperties.root = new File(FileUtil.getExternalStorageDir());
        dialogProperties.error_dir = new File(FileUtil.getExternalStorageDir());

        // Set the initial directory based on the last selected path
        if (sharedPreferences.contains(lastPathKey)) {
            dialogProperties.offset = new File(sharedPreferences.getString(lastPathKey, ""));
        } else {
            dialogProperties.offset = new File(FileUtil.getExternalStorageDir());
        }

        dialogProperties.extensions = new String[]{fileExtension};

        FilePickerDialog filePickerDialog = new FilePickerDialog(this, dialogProperties);
        filePickerDialog.setTitle(title);
        filePickerDialog.setPositiveBtnName(positiveButtonText);
        filePickerDialog.setNegativeBtnName(getResources().getString(R.string.close));

        filePickerDialog.setDialogSelectionListener(new DialogSelectionListener() {
            @Override
            public void onSelectedFilePaths(String[] selectedFilePaths) {
                ArrayList<String> filePaths = new ArrayList<>(Arrays.asList(selectedFilePaths));
                if (filePaths.isEmpty()) {
                    return;
                }
                textView.setText(joinDexPathList(filePaths));

                // Save the last selected directory path in SharedPreferences
                sharedPreferences.edit().putString(lastPathKey, new File(filePaths.get(0)).getParentFile().getAbsolutePath()).apply();
            }
        });

        filePickerDialog.show();
    }

    private List<String> parseDexPathList(String rawPathList) {
        List<String> paths = new ArrayList<>();
        if (rawPathList == null) {
            return paths;
        }
        String[] parts = rawPathList.split(";");
        for (String part : parts) {
            String path = part.trim();
            if (!path.isEmpty()) {
                paths.add(path);
            }
        }
        return paths;
    }

    private String joinDexPathList(List<String> paths) {
        StringBuilder builder = new StringBuilder();
        for (String path : paths) {
            if (path == null || path.trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(";");
            }
            builder.append(path.trim());
        }
        return builder.toString();
    }

    @Override
    protected void onResume() {
        super.onResume();
        McpServer.setPathChangeListener(new McpServer.PathChangeListener() {
            @Override
            public void onPathChanged(final String newPath) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (dexFilePathEditText != null) {
                            dexFilePathEditText.setText(newPath);
                        }
                    }
                });
            }
        });
    }

    @Override
    protected void onPause() {
        McpServer.setPathChangeListener(null);
        super.onPause();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 100, 0, "MCP Server")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 100) {
            McpServerDialog.show(this);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
