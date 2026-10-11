package dev.packshelf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URL;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ModrinthApi {
    private static final String API = "https://api.modrinth.com/v2";
    private static final long MAX_DOWNLOAD_BYTES = 512L * 1024L * 1024L;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private ModrinthApi() {}

    record Pack(String projectId, String title, String author, long downloads) {}

    static List<Pack> search(String query) throws IOException, InterruptedException {
        String facets = "[[\"project_type:resourcepack\"],[\"versions:1.20.1\"]]";
        String url = API + "/search?limit=8&facets=" + encode(facets) + "&query=" + encode(query);
        JsonObject root = JsonParser.parseString(get(url)).getAsJsonObject();
        JsonArray hits = root.getAsJsonArray("hits");
        List<Pack> results = new ArrayList<>();
        for (JsonElement element : hits) {
            JsonObject hit = element.getAsJsonObject();
            results.add(new Pack(
                    string(hit, "project_id", ""),
                    string(hit, "title", "Untitled resource pack"),
                    string(hit, "author", "Unknown author"),
                    number(hit, "downloads", 0L)
            ));
        }
        return results;
    }

    static String download(String projectId) throws IOException, InterruptedException {
        if (projectId == null || !projectId.matches("[A-Za-z0-9_-]{2,100}")) {
            throw new IOException("Invalid Modrinth project ID.");
        }
        String gameVersions = "[\"1.20.1\"]";
        String url = API + "/project/" + projectId + "/version?game_versions=" + encode(gameVersions);
        JsonArray versions = JsonParser.parseString(get(url)).getAsJsonArray();
        if (versions.isEmpty()) throw new IOException("No compatible version was found.");

        JsonObject selectedFile = null;
        for (JsonElement versionElement : versions) {
            JsonObject version = versionElement.getAsJsonObject();
            JsonArray files = version.getAsJsonArray("files");
            if (files == null) continue;
            for (JsonElement fileElement : files) {
                JsonObject file = fileElement.getAsJsonObject();
                String filename = string(file, "filename", "");
                if (!filename.toLowerCase(Locale.ROOT).endsWith(".zip")) continue;
                if (selectedFile == null || bool(file, "primary", false)) selectedFile = file;
                if (bool(file, "primary", false)) break;
            }
            if (selectedFile != null) break;
        }
        if (selectedFile == null) throw new IOException("No ZIP resource-pack file was found.");

        String downloadUrl = string(selectedFile, "url", "");
        URI downloadUri;
        try {
            downloadUri = URI.create(downloadUrl);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid download URL.", e);
        }
        if (!"https".equalsIgnoreCase(downloadUri.getScheme())
                || downloadUri.getHost() == null
                || !downloadUri.getHost().equalsIgnoreCase("cdn.modrinth.com")) {
            throw new IOException("The file URL was not from the official Modrinth CDN.");
        }

        String originalName = string(selectedFile, "filename", "resource-pack.zip");
        String filename = originalName.replaceAll("[^A-Za-z0-9._() -]", "_").replaceAll("[. ]+$", "");
        if (filename.isBlank() || !filename.toLowerCase(Locale.ROOT).endsWith(".zip")) filename = "resource-pack.zip";

        Path directory = Path.of("resourcepacks").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path destination = uniquePath(directory, filename);
        Path temporary = Files.createTempFile(directory, "packshelf-", ".part");
        boolean moved = false;
        try {
            URL target = downloadUri.toURL();
            HttpURLConnection connection = (HttpURLConnection) target.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "PackShelf/1.0 (Fabric Minecraft mod)");
            connection.setInstanceFollowRedirects(true);
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) throw new IOException("Download server returned HTTP " + response + ".");
            long announcedLength = connection.getContentLengthLong();
            if (announcedLength > MAX_DOWNLOAD_BYTES) throw new IOException("The resource pack is larger than 512 MB.");
            try (InputStream in = connection.getInputStream(); var out = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int count;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_DOWNLOAD_BYTES) throw new IOException("The resource pack is larger than 512 MB.");
                    out.write(buffer, 0, count);
                }
            } finally {
                connection.disconnect();
            }
            if (Files.size(temporary) == 0) throw new IOException("The downloaded file was empty.");
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
            return destination.getFileName().toString();
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    private static Path uniquePath(Path directory, String filename) {
        Path candidate = directory.resolve(filename).normalize();
        int suffix = 1;
        String stem = filename.substring(0, filename.length() - 4);
        while (Files.exists(candidate)) {
            candidate = directory.resolve(stem + " (" + suffix++ + ").zip").normalize();
        }
        return candidate;
    }

    private static String get(String url) throws IOException, InterruptedException {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "PackShelf/1.0 (Fabric Minecraft mod)")
                .header("Accept", "application/json")
                .GET().build();
        var response = HTTP.send(request, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Modrinth API returned HTTP " + response.statusCode() + ".");
        }
        return response.body();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    private static long number(JsonObject object, String key, long fallback) {
        JsonElement element = object.get(key);
        try { return element == null || element.isJsonNull() ? fallback : element.getAsLong(); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement element = object.get(key);
        try { return element == null || element.isJsonNull() ? fallback : element.getAsBoolean(); }
        catch (RuntimeException ignored) { return fallback; }
    }
}
