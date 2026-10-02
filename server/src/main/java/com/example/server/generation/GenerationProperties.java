package com.example.server.generation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "generation")
public class GenerationProperties {
    private String provider = "mock";
    private boolean paidEnabled = false;
    private boolean recoveryEnabled = false;
    public boolean isRecoveryEnabled() { return recoveryEnabled; }
    public void setRecoveryEnabled(boolean value) { recoveryEnabled = value; }
    private String apiKey = "";
    private String baseUrl = "https://api.siliconflow.cn/v1";
    private String textModel = "Wan-AI/Wan2.2-T2V-A14B";
    private String imageModel = "Wan-AI/Wan2.2-I2V-A14B";
    private String artifactHosts = "";
    private Seedance seedance = new Seedance();
    public Seedance getSeedance() { return seedance; }
    public void setSeedance(Seedance value) { seedance = value; }
    public String artifactHostsFor(String provider) {
        return "seedance".equals(provider) ? seedance.getArtifactHosts() : artifactHosts;
    }
    /** Separate credentials keep recovery of SiliconFlow tasks independent of the selected provider. */
    public static class Seedance {
        private String apiKey = "";
        private String baseUrl = "https://ark.cn-beijing.volces.com/api/v3";
        private String artifactHosts = "";
        private boolean recoveryEnabled = false;
        public String getApiKey() { return apiKey; }
        public void setApiKey(String value) { apiKey = value; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String value) { baseUrl = value; }
        public String getArtifactHosts() { return artifactHosts; }
        public void setArtifactHosts(String value) { artifactHosts = value; }
        public boolean isRecoveryEnabled() { return recoveryEnabled; }
        public void setRecoveryEnabled(boolean value) { recoveryEnabled = value; }
    }
    private Capability text = new Capability();
    private Capability image = new Capability();
    private String authorizationId = "";
    private java.util.List<String> approvedModels = java.util.List.of();
    private int maxPaidTasks = 0;
    private java.math.BigDecimal budgetLimit = java.math.BigDecimal.ZERO;
    private java.math.BigDecimal reservationPerTask = java.math.BigDecimal.ZERO;
    public Capability getText() { return text; }
    public void setText(Capability value) { text = value; }
    public Capability getImage() { return image; }
    public void setImage(Capability value) { image = value; }
    public String getAuthorizationId() { return authorizationId; }
    public void setAuthorizationId(String value) { authorizationId = value; }
    public java.util.List<String> getApprovedModels() { return approvedModels; }
    public void setApprovedModels(java.util.List<String> value) { approvedModels = value; }
    public int getMaxPaidTasks() { return maxPaidTasks; }
    public void setMaxPaidTasks(int value) { maxPaidTasks = value; }
    public java.math.BigDecimal getBudgetLimit() { return budgetLimit; }
    public void setBudgetLimit(java.math.BigDecimal value) { budgetLimit = value; }
    public java.math.BigDecimal getReservationPerTask() { return reservationPerTask; }
    public void setReservationPerTask(java.math.BigDecimal value) { reservationPerTask = value; }
    public void requireAuthorization(String model) {
        if (!paidEnabled || !authorizationId.matches("[A-Za-z0-9_.:-]{1,128}")
                || maxPaidTasks < 1 || budgetLimit.signum() <= 0 || reservationPerTask.signum() <= 0
                || budgetLimit.scale() > 6 || reservationPerTask.scale() > 6
                || budgetLimit.precision() > 18 || reservationPerTask.precision() > 18
                || budgetLimit.precision() - budgetLimit.scale() > 12
                || reservationPerTask.precision() - reservationPerTask.scale() > 12
                || reservationPerTask.compareTo(budgetLimit) > 0 || !approvedModels.contains(model)) {
            throw new com.example.server.exception.BusinessException(com.example.server.common.ErrorCode.FORBIDDEN,
                    "需先确认模型、任务数和预算，并配置本次调用授权");
        }
    }
    public Capability capability(GenerationRequest.Kind kind) {
        return kind == GenerationRequest.Kind.TEXT_TO_VIDEO ? text : image;
    }
    public String authorizationHash() {
        String policy = maxPaidTasks + "|" + budgetLimit.stripTrailingZeros().toPlainString() + "|"
                + reservationPerTask.stripTrailingZeros().toPlainString() + "|"
                + String.join(",", approvedModels.stream().sorted().toList());
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(policy.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static class Capability {
        private boolean enabled = true;
        private java.util.List<String> sizes = java.util.List.of("1280x720", "720x1280", "960x960");
        private boolean negativePrompt = true;
        private boolean seed = true;
        private int maxPromptLength = 2000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public java.util.List<String> getSizes() { return sizes; }
        public void setSizes(java.util.List<String> value) { sizes = value; }
        public boolean isNegativePrompt() { return negativePrompt; }
        public void setNegativePrompt(boolean value) { negativePrompt = value; }
        public boolean isSeed() { return seed; }
        public void setSeed(boolean value) { seed = value; }
        public int getMaxPromptLength() { return maxPromptLength; }
        public void setMaxPromptLength(int value) { maxPromptLength = value; }
    }
    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    public boolean isPaidEnabled() { return paidEnabled; }
    public void setPaidEnabled(boolean value) { paidEnabled = value; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String value) { apiKey = value; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = value; }
    public String getTextModel() { return textModel; }
    public void setTextModel(String value) { textModel = value; }
    public String getImageModel() { return imageModel; }
    public void setImageModel(String value) { imageModel = value; }
    public String getArtifactHosts() { return artifactHosts; }
    public void setArtifactHosts(String value) { artifactHosts = value; }
}
