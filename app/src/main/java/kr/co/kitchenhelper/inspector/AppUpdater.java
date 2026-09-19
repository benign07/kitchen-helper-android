package kr.co.kitchenhelper.inspector;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Checks GitHub Releases, downloads a newer APK, and opens Android's installer. */
public final class AppUpdater {
    private static final String RELEASE_API =
            "https://api.github.com/repos/benign07/kitchen-helper-android/releases/latest";
    private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor();

    private AppUpdater() {}

    public static void check(Activity activity) {
        Toast.makeText(activity, "GitHub에서 업데이트를 확인합니다…", Toast.LENGTH_SHORT).show();
        NETWORK.execute(() -> {
            try {
                Release release = fetchLatestRelease();
                String current = currentVersion(activity);
                activity.runOnUiThread(() -> showResult(activity, current, release));
            } catch (Exception error) {
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        "업데이트 확인 실패: 인터넷 연결을 확인하세요.", Toast.LENGTH_LONG).show());
            }
        });
    }

    private static Release fetchLatestRelease() throws Exception {
        HttpURLConnection connection = open(RELEASE_API);
        int status = connection.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IllegalStateException("GitHub response " + status);
        }
        String json;
        try (java.io.InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            json = output.toString(java.nio.charset.StandardCharsets.UTF_8.name());
        } finally {
            connection.disconnect();
        }
        JSONObject root = new JSONObject(json);
        JSONArray assets = root.optJSONArray("assets");
        String url = "";
        String digest = "";
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null || !asset.optString("name").toLowerCase(Locale.US)
                        .endsWith(".apk")) continue;
                url = asset.optString("browser_download_url");
                digest = asset.optString("digest");
                break;
            }
        }
        if (url.isEmpty()) throw new IllegalStateException("Release APK missing");
        return new Release(root.optString("tag_name"), root.optString("body"), url, digest);
    }

    private static void showResult(Activity activity, String current, Release release) {
        if (activity.isFinishing()) return;
        if (!isNewer(release.version, current)) {
            Toast.makeText(activity, "최신 버전입니다. (v" + current + ")",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String notes = release.notes.trim();
        if (notes.length() > 700) notes = notes.substring(0, 700) + "…";
        String message = "현재 v" + current + "  →  새 버전 " + release.version;
        if (!notes.isEmpty()) message += "\n\n" + notes;
        new AlertDialog.Builder(activity)
                .setTitle("새 업데이트가 있습니다")
                .setMessage(message)
                .setNegativeButton("나중에", null)
                .setPositiveButton("다운로드·설치", (dialog, which) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            && !activity.getPackageManager().canRequestPackageInstalls()) {
                        Toast.makeText(activity,
                                "이 앱의 ‘알 수 없는 앱 설치’를 허용한 뒤 다시 눌러주세요.",
                                Toast.LENGTH_LONG).show();
                        activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName())));
                        return;
                    }
                    download(activity, release);
                })
                .show();
    }

    private static void download(Activity activity, Release release) {
        AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle("업데이트 다운로드 중")
                .setMessage("잠시 기다려주세요…")
                .setCancelable(false)
                .create();
        progress.show();
        NETWORK.execute(() -> {
            try {
                File directory = new File(activity.getCacheDir(), "updates");
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new IllegalStateException("Cannot create update directory");
                }
                File partial = new File(directory, UpdateFileProvider.FILE_NAME + ".part");
                File apk = new File(directory, UpdateFileProvider.FILE_NAME);
                HttpURLConnection connection = open(release.downloadUrl);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    connection.disconnect();
                    throw new IllegalStateException("Download failed");
                }
                try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream output = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                } finally {
                    connection.disconnect();
                }
                if (partial.length() < 10_000L) throw new IllegalStateException("APK too small");
                verifyDigest(partial, release.digest);
                Files.move(partial.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING);
                activity.runOnUiThread(() -> {
                    progress.dismiss();
                    openInstaller(activity);
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(activity, "업데이트 다운로드에 실패했습니다.",
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private static void openInstaller(Activity activity) {
        Uri apk = new Uri.Builder().scheme("content")
                .authority(activity.getPackageName() + ".updates")
                .appendPath(UpdateFileProvider.FILE_NAME).build();
        Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apk, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(install);
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "KitchenHelper-Android");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        return connection;
    }

    private static String currentVersion(Activity activity) throws Exception {
        PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
        return info.versionName == null ? "0.0.0" : info.versionName;
    }

    private static boolean isNewer(String candidate, String current) {
        int[] left = versionParts(candidate);
        int[] right = versionParts(current);
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int a = i < left.length ? left[i] : 0;
            int b = i < right.length ? right[i] : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    private static int[] versionParts(String value) {
        String clean = value == null ? "" : value.replaceFirst("^[vV]", "");
        String[] parts = clean.split("[^0-9]+");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { result[i] = Integer.parseInt(parts[i]); }
            catch (NumberFormatException ignored) { result[i] = 0; }
        }
        return result;
    }

    private static void verifyDigest(File file, String expected) throws Exception {
        if (expected == null || !expected.startsWith("sha256:")) return;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        StringBuilder actual = new StringBuilder("sha256:");
        for (byte value : digest.digest()) actual.append(String.format(Locale.US, "%02x", value & 0xff));
        if (!actual.toString().equalsIgnoreCase(expected)) {
            throw new SecurityException("Release digest mismatch");
        }
    }

    private static final class Release {
        final String version;
        final String notes;
        final String downloadUrl;
        final String digest;

        Release(String version, String notes, String downloadUrl, String digest) {
            this.version = version;
            this.notes = notes;
            this.downloadUrl = downloadUrl;
            this.digest = digest;
        }
    }
}
