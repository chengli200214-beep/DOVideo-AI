package com.example.server.storyboard;

import com.example.server.common.ErrorCode;
import com.example.server.exception.BusinessException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Separate authorization from both the original analysis LLM and video generation. */
@Component
@ConfigurationProperties(prefix="storyboard.model")
public class StoryboardModelProperties {
    private boolean paidEnabled = false;
    private String apiKey = "", baseUrl = "https://api.deepseek.com", model = "deepseek-flash";
    private String authorizationId = "", approvedModel = "", currency = "CNY";
    private int maxCalls = 0, maxTokens = 4096, timeoutSeconds = 60;
    private BigDecimal budgetLimit = BigDecimal.ZERO, reservationPerCall = BigDecimal.ZERO;
    private BigDecimal inputPricePerMillion = BigDecimal.ZERO, outputPricePerMillion = BigDecimal.ZERO;
    public void requireEnabled() {
        if (!paidEnabled) throw new BusinessException(ErrorCode.FORBIDDEN, "DeepSeek 分镜付费调用尚未启用；可继续使用本地模板");
        URI base;
        try { base = URI.create(baseUrl); } catch (RuntimeException invalid) { throw unavailable(); }
        if (!"https".equals(base.getScheme()) || base.getUserInfo()!=null || base.getQuery()!=null || base.getFragment()!=null
                || !"api.deepseek.com".equals(base.getHost())
                || (base.getPort()!=-1 && base.getPort()!=443)
                || !java.util.Set.of("", "/", "/v1", "/v1/").contains(base.getPath())
                || apiKey == null || apiKey.isBlank() || model==null || !model.matches("[A-Za-z0-9_./:-]{1,80}")
                || !model.equals(approvedModel) || authorizationId==null || !authorizationId.matches("[A-Za-z0-9_.:-]{1,128}")
                || currency==null || !java.util.Set.of("CNY", "USD").contains(currency) || maxCalls<1 || maxTokens<512 || maxTokens>8192
                || timeoutSeconds<1 || timeoutSeconds>120 || !positive(budgetLimit) || !positive(reservationPerCall)
                || !positive(inputPricePerMillion) || !positive(outputPricePerMillion)
                || reservationPerCall.compareTo(budgetLimit)>0) throw unavailable();
    }
    public String policyHash() {
        try {
            String policy = String.join("|", DeepSeekStoryboardPlanner.PROMPT_VERSION, baseUrl.replaceAll("/+$", ""), model, currency, Integer.toString(maxCalls),
                    budgetLimit.toPlainString(), reservationPerCall.toPlainString(), Integer.toString(maxTokens),
                    inputPricePerMillion.toPlainString(), outputPricePerMillion.toPlainString(), Integer.toString(timeoutSeconds));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(policy.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    public BigDecimal estimate(long input, long output) {
        return inputPricePerMillion.multiply(BigDecimal.valueOf(input)).add(outputPricePerMillion.multiply(BigDecimal.valueOf(output)))
                .divide(BigDecimal.valueOf(1_000_000), 6, java.math.RoundingMode.CEILING);
    }
    private static boolean positive(BigDecimal value) { return value!=null && value.signum()>0 && value.scale()<=6 && value.compareTo(new BigDecimal("999999999999.999999"))<=0; }
    private static BusinessException unavailable() { return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "DeepSeek 分镜配置不完整：仅支持官方 api.deepseek.com，需单独授权模型、次数、币种、价格与预算"); }
    public boolean isPaidEnabled() { return paidEnabled; } public void setPaidEnabled(boolean v) { paidEnabled=v; }
    public String getApiKey() { return apiKey; } public void setApiKey(String v) { apiKey=v; }
    public String getBaseUrl() { return baseUrl; } public void setBaseUrl(String v) { baseUrl=v; }
    public String getModel() { return model; } public void setModel(String v) { model=v; }
    public String getAuthorizationId() { return authorizationId; } public void setAuthorizationId(String v) { authorizationId=v; }
    public String getApprovedModel() { return approvedModel; } public void setApprovedModel(String v) { approvedModel=v; }
    public String getCurrency() { return currency; } public void setCurrency(String v) { currency=v; }
    public int getMaxCalls() { return maxCalls; } public void setMaxCalls(int v) { maxCalls=v; }
    public int getMaxTokens() { return maxTokens; } public void setMaxTokens(int v) { maxTokens=v; }
    public int getTimeoutSeconds() { return timeoutSeconds; } public void setTimeoutSeconds(int v) { timeoutSeconds=v; }
    public BigDecimal getBudgetLimit() { return budgetLimit; } public void setBudgetLimit(BigDecimal v) { budgetLimit=v; }
    public BigDecimal getReservationPerCall() { return reservationPerCall; } public void setReservationPerCall(BigDecimal v) { reservationPerCall=v; }
    public BigDecimal getInputPricePerMillion() { return inputPricePerMillion; } public void setInputPricePerMillion(BigDecimal v) { inputPricePerMillion=v; }
    public BigDecimal getOutputPricePerMillion() { return outputPricePerMillion; } public void setOutputPricePerMillion(BigDecimal v) { outputPricePerMillion=v; }
}
