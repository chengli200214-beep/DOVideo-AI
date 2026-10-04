package com.example.server.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class EmbeddingUtils {

    private final boolean enabled;
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    private final OkHttpClient client;

    @Autowired
    public EmbeddingUtils(@Value("${ai.embedding.enabled:false}") boolean enabled,
                          @Value("${ai.embedding.api-key:}") String apiKey,
                          @Value("${ai.embedding.base-url:}") String baseUrl,
                          @Value("${ai.embedding.model:}") String model) {
        this(enabled, apiKey, baseUrl, model, new OkHttpClient.Builder());
    }

    EmbeddingUtils(boolean enabled, String apiKey, String baseUrl, String model, OkHttpClient.Builder builder) {
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.client = builder.connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).build();
    }

    public List<Double> embed(String text) {
        if (text == null || text.isBlank()) return List.of();
        String endpoint = OptionalAiServiceEndpoint.require(enabled, apiKey, baseUrl, model, "ai.embedding");
        try {
            JSONObject requestJson = new JSONObject();
            requestJson.put("model", model);
            requestJson.put("input", text);

            Request request = new Request.Builder()
                    .url(endpoint + "/embeddings")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(
                            requestJson.toString(),
                            MediaType.parse("application/json; charset=utf-8")
                    ))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IllegalStateException("Embedding API failed: " + response.code());
                }
                JSONObject json = JSON.parseObject(response.body().string());
                JSONArray data = json.getJSONArray("data");
                if (data == null || data.isEmpty()) throw new IllegalStateException("Embedding data is empty");
                JSONArray values = data.getJSONObject(0).getJSONArray("embedding");
                if (values == null || values.isEmpty()) throw new IllegalStateException("Embedding vector is empty");
                List<Double> embedding = new ArrayList<>(values.size());
                for (Object value : values) {
                    embedding.add(((Number) value).doubleValue());
                }
                return embedding;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Embedding 生成失败", e);
        }
    }
}
