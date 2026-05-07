package com.mcclaude.plugin.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcclaude.plugin.McClaudePlugin;
import com.mcclaude.plugin.WebSocketClient;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;

public class FileHandler {

    private final McClaudePlugin plugin;

    public FileHandler(McClaudePlugin plugin) {
        this.plugin = plugin;
    }

    public void handleFileList(String id, JsonObject data, WebSocketClient client) {
        String path = data.has("path") ? data.get("path").getAsString() : "";

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String validationError = validatePath(path);
                if (validationError != null) {
                    client.sendError(id, validationError);
                    return;
                }

                File dir = resolveFile(path);
                if (!dir.exists()) {
                    client.sendError(id, "Path does not exist: " + path);
                    return;
                }
                if (!dir.isDirectory()) {
                    client.sendError(id, "Path is not a directory: " + path);
                    return;
                }

                File[] files = dir.listFiles();
                JsonObject result = new JsonObject();
                JsonArray entries = new JsonArray();

                if (files != null) {
                    // Sort: directories first, then files, both alphabetical
                    Arrays.sort(files, Comparator
                            .<File, Boolean>comparing(f -> !f.isDirectory())
                            .thenComparing(f -> f.getName().toLowerCase()));

                    for (File file : files) {
                        // Skip blocked files
                        if (isBlockedFile(file.getName())) {
                            continue;
                        }

                        JsonObject entry = new JsonObject();
                        entry.addProperty("name", file.getName());
                        entry.addProperty("type", file.isDirectory() ? "directory" : "file");
                        entry.addProperty("size", file.length());
                        entry.addProperty("modified", file.lastModified());
                        entries.add(entry);
                    }
                }

                result.add("entries", entries);
                result.addProperty("path", path);
                client.sendResult(id, result);

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error listing files: " + path, e);
                client.sendError(id, "Error listing files: " + e.getMessage());
            }
        });
    }

    public void handleFileRead(String id, JsonObject data, WebSocketClient client) {
        String path = data.has("path") ? data.get("path").getAsString() : "";

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String validationError = validatePath(path);
                if (validationError != null) {
                    client.sendError(id, validationError);
                    return;
                }

                File file = resolveFile(path);
                if (!file.exists()) {
                    client.sendError(id, "File does not exist: " + path);
                    return;
                }
                if (!file.isFile()) {
                    client.sendError(id, "Path is not a file: " + path);
                    return;
                }

                // Check file name against blocked list
                if (isBlockedFile(file.getName())) {
                    client.sendError(id, "File is blocked: " + file.getName());
                    return;
                }

                // Check file size
                long fileSizeKb = file.length() / 1024;
                if (fileSizeKb > plugin.getMaxFileSizeKb()) {
                    client.sendError(id, "File too large: " + fileSizeKb + "KB (max: " + plugin.getMaxFileSizeKb() + "KB)");
                    return;
                }

                byte[] rawBytes = Files.readAllBytes(file.toPath());

                JsonObject result = new JsonObject();
                result.addProperty("path", path);
                result.addProperty("size", rawBytes.length);
                result.addProperty("content_base64", Base64.getEncoder().encodeToString(rawBytes));
                client.sendResult(id, result);

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error reading file: " + path, e);
                client.sendError(id, "Error reading file: " + e.getMessage());
            }
        });
    }

    public void handleFileWrite(String id, JsonObject data, WebSocketClient client) {
        String path = data.has("path") ? data.get("path").getAsString() : "";
        final String content = data.has("content") ? data.get("content").getAsString() : null;
        final String contentB64 = data.has("content_base64") ? data.get("content_base64").getAsString() : null;
        final String encoding = data.has("encoding") ? data.get("encoding").getAsString() : "utf-8";

        if (content == null && contentB64 == null) {
            client.sendError(id, "No content provided");
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String validationError = validatePath(path);
                if (validationError != null) {
                    client.sendError(id, validationError);
                    return;
                }

                File file = resolveFile(path);

                // Check blocked files
                if (isBlockedFile(file.getName())) {
                    client.sendError(id, "File is blocked: " + file.getName());
                    return;
                }

                // Create parent directories if needed
                File parentDir = file.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    if (!parentDir.mkdirs()) {
                        client.sendError(id, "Failed to create parent directories for: " + path);
                        return;
                    }
                }

                byte[] fileBytes;
                if (contentB64 != null) {
                    fileBytes = Base64.getDecoder().decode(contentB64);
                } else if ("base64".equals(encoding)) {
                    fileBytes = Base64.getDecoder().decode(content);
                } else {
                    fileBytes = content.getBytes(StandardCharsets.UTF_8);
                }
                Files.write(file.toPath(), fileBytes);

                JsonObject result = new JsonObject();
                result.addProperty("path", path);
                result.addProperty("size", file.length());
                result.addProperty("success", true);
                client.sendResult(id, result);

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error writing file: " + path, e);
                client.sendError(id, "Error writing file: " + e.getMessage());
            }
        });
    }

    public void handleFileDelete(String id, JsonObject data, WebSocketClient client) {
        String path = data.has("path") ? data.get("path").getAsString() : "";

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String validationError = validatePath(path);
                if (validationError != null) {
                    client.sendError(id, validationError);
                    return;
                }

                File file = resolveFile(path);
                if (!file.exists()) {
                    client.sendError(id, "File does not exist: " + path);
                    return;
                }

                // Check blocked files
                if (isBlockedFile(file.getName())) {
                    client.sendError(id, "File is blocked: " + file.getName());
                    return;
                }

                boolean deleted;
                if (file.isDirectory()) {
                    deleted = deleteRecursive(file);
                } else {
                    deleted = file.delete();
                }
                if (!deleted) {
                    client.sendError(id, "Failed to delete file: " + path);
                    return;
                }

                JsonObject result = new JsonObject();
                result.addProperty("path", path);
                result.addProperty("success", true);
                client.sendResult(id, result);

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error deleting file: " + path, e);
                client.sendError(id, "Error deleting file: " + e.getMessage());
            }
        });
    }

    public void handleMkdir(String id, JsonObject data, WebSocketClient client) {
        String path = data.has("path") ? data.get("path").getAsString() : "";

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String validationError = validatePath(path);
                if (validationError != null) {
                    client.sendError(id, validationError);
                    return;
                }

                File dir = resolveFile(path);
                if (dir.exists()) {
                    client.sendError(id, "Path already exists: " + path);
                    return;
                }

                boolean created = dir.mkdirs();
                if (!created) {
                    client.sendError(id, "Failed to create directory: " + path);
                    return;
                }

                JsonObject result = new JsonObject();
                result.addProperty("path", path);
                result.addProperty("success", true);
                client.sendResult(id, result);

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error creating directory: " + path, e);
                client.sendError(id, "Error creating directory: " + e.getMessage());
            }
        });
    }

    public void handleRename(String id, JsonObject data, WebSocketClient client) {
        String from = data.has("from") ? data.get("from").getAsString() : "";
        String to = data.has("to") ? data.get("to").getAsString() : "";

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String fromError = validatePath(from);
                if (fromError != null) {
                    client.sendError(id, "Source: " + fromError);
                    return;
                }
                String toError = validatePath(to);
                if (toError != null) {
                    client.sendError(id, "Destination: " + toError);
                    return;
                }

                File srcFile = resolveFile(from);
                File destFile = resolveFile(to);

                if (!srcFile.exists()) {
                    client.sendError(id, "Source does not exist: " + from);
                    return;
                }
                if (destFile.exists()) {
                    client.sendError(id, "Destination already exists: " + to);
                    return;
                }

                // Create parent dirs if needed
                File destParent = destFile.getParentFile();
                if (destParent != null && !destParent.exists()) {
                    destParent.mkdirs();
                }

                boolean renamed = srcFile.renameTo(destFile);
                if (!renamed) {
                    // renameTo can fail across filesystems, try copy+delete
                    Files.move(srcFile.toPath(), destFile.toPath());
                }

                JsonObject result = new JsonObject();
                result.addProperty("from", from);
                result.addProperty("to", to);
                result.addProperty("success", true);
                client.sendResult(id, result);

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error renaming: " + from + " -> " + to, e);
                client.sendError(id, "Error renaming: " + e.getMessage());
            }
        });
    }

    /**
     * Validates that a path is under one of the allowed paths and not blocked.
     * Returns null if valid, or an error message if invalid.
     */
    private String validatePath(String path) {
        if (path == null) {
            path = ".";
        }
        if (path.isEmpty() || path.equals("/") || path.equals(".")) {
            // Root path — check if allowed-paths includes "" (allow all)
            List<String> allowedPaths = plugin.getAllowedPaths();
            for (String ap : allowedPaths) {
                if (ap.isEmpty()) return null; // "" means allow everything
            }
            return "Root path not allowed. Allowed: " + allowedPaths;
        }

        // Prevent path traversal attacks
        if (path.contains("..")) {
            return "Path traversal not allowed (contains '..')";
        }

        try {
            File file = resolveFile(path);
            String canonicalPath = file.getCanonicalPath();
            File serverDir = plugin.getServer().getWorldContainer().getCanonicalFile();
            String serverPath = serverDir.getCanonicalPath();

            // Check that the canonical path is actually under the server directory
            if (!canonicalPath.startsWith(serverPath)) {
                return "Path is outside the server directory";
            }

            // Get the relative path from the server directory
            String relativePath = canonicalPath.substring(serverPath.length());
            // Normalize separators
            relativePath = relativePath.replace('\\', '/');
            if (relativePath.startsWith("/")) {
                relativePath = relativePath.substring(1);
            }

            // Check against allowed paths
            List<String> allowedPaths = plugin.getAllowedPaths();
            boolean allowed = false;
            for (String allowedPath : allowedPaths) {
                String normalizedAllowed = allowedPath.replace('\\', '/');
                if (normalizedAllowed.endsWith("/")) {
                    normalizedAllowed = normalizedAllowed.substring(0, normalizedAllowed.length() - 1);
                }
                if (relativePath.startsWith(normalizedAllowed)) {
                    allowed = true;
                    break;
                }
            }

            if (!allowed) {
                return "Path '" + path + "' is not under any allowed path. Allowed: " + allowedPaths;
            }

            return null;

        } catch (IOException e) {
            return "Error validating path: " + e.getMessage();
        }
    }

    /**
     * Resolves a relative path against the server directory.
     */
    public File resolveFilePublic(String path) {
        return resolveFile(path);
    }

    private File resolveFile(String path) {
        File serverDir = plugin.getServer().getWorldContainer();
        return new File(serverDir, path);
    }

    /**
     * Checks if a filename is in the blocked files list.
     */
    private boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        return file.delete();
    }

    private boolean isBlockedFile(String fileName) {
        for (String blocked : plugin.getBlockedFiles()) {
            if (fileName.equalsIgnoreCase(blocked)) {
                return true;
            }
        }
        return false;
    }
}
