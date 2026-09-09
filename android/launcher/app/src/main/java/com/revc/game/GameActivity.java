package com.revc.game;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.libsdl.app.SDLActivity;

import java.io.File;

/**
 * Actividad principal del juego.
 *
 * Le indica al motor nativo (via el argumento "--dir", que ya soporta en
 * todas las plataformas) que busque los archivos del juego (data/, models/,
 * audio/, anim/, etc.) en una carpeta simple y visible en cualquier
 * explorador de archivos: el almacenamiento interno del dispositivo,
 * carpeta "reVC" (por ej. "Almacenamiento interno/reVC").
 *
 * Evita depender de Android/data/com.revc.game/files o de un OBB, que la
 * mayoría de usuarios no sabe ubicar ni gestionar.
 */
public class GameActivity extends SDLActivity {

    /**
     * Nombre de la carpeta con los archivos del juego, en la raíz del almacenamiento
     * interno. Viene de BuildConfig (ver productFlavors en build.gradle) para que
     * cada edición (standard, longnight, ...) use su propia carpeta sin tocar la
     * de las demás -- son apps instaladas por separado (applicationId distinto).
     */
    public static final String GAME_FOLDER_NAME = BuildConfig.GAME_FOLDER_NAME;

    private static final int REQUEST_LEGACY_STORAGE_PERMISSION = 1001;

    public static File getGameDir() {
        return new File(Environment.getExternalStorageDirectory(), GAME_FOLDER_NAME);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        File gameDir = getGameDir();
        if (!gameDir.exists()) {
            gameDir.mkdirs();
        }

        if (!hasFullStorageAccess()) {
            requestFullStorageAccess();
            // Frenamos acá: el motor nativo no debe arrancar sin poder leer
            // los archivos del juego. El usuario reabre la app luego de
            // conceder el permiso.
            return;
        }

        super.onCreate(savedInstanceState);

        // Draw the game (and our touch controls) behind the camera cutout
        // consistently in landscape, instead of the system letterboxing
        // around it -- our TouchControlsView reads the actual safe-area
        // insets and steers controls clear of it either way, but the game
        // view itself should still fill the whole screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        // libreVC.so is already loaded at this point (loadLibraries(), called
        // from within super.onCreate(), just did it), so TouchControlsView's
        // native methods resolve fine without a separate System.loadLibrary.
        mLayout.addView(new TouchControlsView(this),
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    protected String[] getArguments() {
        return new String[]{"--dir", getGameDir().getAbsolutePath()};
    }

    @Override
    protected String[] getLibraries() {
        // El target de CMake se llama "reVC" (mayúscula incluida, ver
        // /CMakeLists.txt), por lo que el binario resultante es
        // "libreVC.so". El nombre acá debe coincidir EXACTO: Android es
        // case-sensitive y System.loadLibrary("revc") jamás encontraría
        // "libreVC.so".
        return new String[]{"SDL2", "openal", "mpg123", "reVC"};
    }

    private boolean hasFullStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestFullStorageAccess() {
        Toast.makeText(this,
                "Concedé el permiso \"Todos los archivos\" y volvé a abrir reVC.\n"
                        + "Copiá los archivos del juego en: Almacenamiento interno/" + GAME_FOLDER_NAME,
                Toast.LENGTH_LONG).show();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_LEGACY_STORAGE_PERMISSION);
        }

        finish();
    }
}
