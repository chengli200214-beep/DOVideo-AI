package com.example.server.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.NoSuchElementException;
import java.util.TreeSet;

/** Call within the transaction that persists the referencing document. FK protects concurrent cleanup. */
public final class GenerationAssetReferences {
    private static final ObjectMapper JSON = new ObjectMapper();
    private GenerationAssetReferences() { }

    public static void attach(JdbcTemplate jdbc, long user, String kind, String owner, String... documents) {
        var ids = new TreeSet<String>();
        try {
            for (String document : documents) if (document != null) collect(JSON.readTree(document), ids);
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("素材引用记录无效", invalid);
        }
        for (String id : ids) {
            if (jdbc.queryForList("SELECT id FROM generation_assets WHERE id=? AND user_id=? FOR UPDATE", id, user).isEmpty())
                throw new NoSuchElementException("参考素材不存在或已删除");
            jdbc.update("INSERT IGNORE INTO generation_asset_references(asset_id,owner_type,owner_id) VALUES (?,?,?)", id, kind, owner);
        }
    }

    private static void collect(JsonNode node, TreeSet<String> ids) {
        if (node == null) return;
        if (node.isObject()) {
            for (String field : java.util.List.of("referenceAssetId", "referenceImageId")) {
                var reference = node.get(field);
                if (reference != null && reference.isTextual() && !reference.asText().isBlank()) ids.add(reference.asText());
            }
        }
        if (node.isContainerNode()) for (JsonNode child : node) collect(child, ids);
    }
}
