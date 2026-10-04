package com.example.server.generation;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class GenerationAssetService {
    private final JdbcTemplate jdbc;
    private final GenerationArtifactStore store;
    public GenerationAssetService(JdbcTemplate jdbc, GenerationArtifactStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }
    public Asset upload(long user, String mime, byte[] bytes) throws Exception {
        if (!("image/png".equals(mime) || "image/jpeg".equals(mime))
                || bytes.length == 0 || bytes.length > 5 * 1024 * 1024) {
            throw new IllegalArgumentException("参考图片需为 PNG/JPEG，最大 5 MiB");
        }
        int width, height;
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("图片无法识别");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase();
                if (!(mime.equals("image/png") && format.equals("png"))
                        && !(mime.equals("image/jpeg") && format.equals("jpeg"))) {
                    throw new IllegalArgumentException("图片声明格式与内容不符");
                }
                width = reader.getWidth(0); height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 4096 || height > 4096) {
                    throw new IllegalArgumentException("图片边长需在 1 到 4096 之间");
                }
                // Decode after dimension checks to reject truncated/corrupt image bodies.
                if (reader.read(0) == null) throw new IllegalArgumentException("图片损坏");
            } catch (java.io.IOException error) { throw new IllegalArgumentException("图片损坏"); }
            finally { reader.dispose(); }
        }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var existing = find(user, hash);
        if (existing != null) return existing;
        String id = UUID.randomUUID().toString();
        // A fresh object name prevents cleanup of an old upload from deleting a later identical upload.
        String key = "generation-inputs/" + user + "/" + id + (mime.equals("image/png") ? ".png" : ".jpg");
        // The bucket remains private. No public ACL or browser-local data URL is persisted.
        store.putReference(key, bytes, mime);
        try {
            jdbc.update("INSERT INTO generation_assets VALUES (?,?,?,?,?,?,?,?,?)", id, user, key, mime,
                    bytes.length, hash, width, height, System.currentTimeMillis());
        } catch (DuplicateKeyException race) {
            existing = find(user, hash);
            if (existing == null) throw race;
            jdbc.update("INSERT INTO generation_asset_cleanup(id,user_id,object_key,state,next_run_at,created_at) VALUES (?,?,?,'PENDING',?,?)",
                    id, user, key, System.currentTimeMillis(), System.currentTimeMillis());
            return existing;
        }
        return owned(user, id);
    }
    public Asset inline(long user, String image) throws Exception {
        if (image == null || image.length() > 7_000_000 ||
                !(image.startsWith("data:image/png;base64,") || image.startsWith("data:image/jpeg;base64,"))) {
            throw new IllegalArgumentException("图生视频需要参考素材 ID 或 PNG/JPEG 图片");
        }
        int comma = image.indexOf(',');
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(image.substring(comma + 1)); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("图片 base64 无效"); }
        return upload(user, image.substring(5, image.indexOf(';')), bytes);
    }
    public Asset owned(long user, String id) {
        var assets = jdbc.query("SELECT * FROM generation_assets WHERE user_id=? AND id=?", (rs, row) ->
                new Asset(rs.getString("id"), rs.getString("object_key"), rs.getString("mime"),
                        rs.getLong("size_bytes"), rs.getString("sha256"), rs.getInt("width"), rs.getInt("height"),
                        rs.getLong("created_at")), user, id);
        if (assets.isEmpty()) throw new NoSuchElementException("参考素材不存在");
        return assets.getFirst();
    }
    public String readableUrl(long user, String id) { return store.readableUrl(owned(user, id).objectKey()); }
    public String submissionImage(long user, String id) throws Exception {
        var asset = owned(user, id);
        byte[] bytes = store.readReference(asset.objectKey());
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (bytes.length != asset.size() || !hash.equals(asset.sha256())) {
            throw new java.io.IOException("Reference image integrity check failed");
        }
        return "data:" + asset.mime() + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }
    private Asset find(long user, String hash) {
        var ids = jdbc.queryForList("SELECT id FROM generation_assets WHERE user_id=? AND sha256=?", String.class, user, hash);
        return ids.isEmpty() ? null : owned(user, ids.getFirst());
    }
    public record Asset(String id, String objectKey, String mime, long size, String sha256, int width, int height, long createdAt) { }
}
