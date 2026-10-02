package com.example.server.storyboard;

import com.example.server.common.ErrorCode;
import com.example.server.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;

@Service
public class StoryboardModelService {
    private final StoryboardModelRepository repository;
    private final StoryboardService storyboards;
    private final StoryboardModelProperties properties;
    private final DeepSeekStoryboardPlanner planner;
    private final ObjectMapper json;
    public StoryboardModelService(StoryboardModelRepository repository,StoryboardService storyboards,StoryboardModelProperties properties,DeepSeekStoryboardPlanner planner,ObjectMapper json) {
        this.repository=repository; this.storyboards=storyboards; this.properties=properties; this.planner=planner; this.json=json;
    }
    public StoryboardModelRepository.Task submit(long user,String project,String key,int expected) throws Exception {
        if (key==null || !key.matches("[A-Za-z0-9_.:-]{1,128}") || expected<1 || expected==Integer.MAX_VALUE) throw new IllegalArgumentException("分镜生成意图或版本不合法");
        var view=storyboards.get(user,project);
        var prior=repository.byKey(user,key);
        if (prior!=null) { StoryboardModelRepository.requireSame(prior,project,expected); return prior; }
        properties.requireEnabled();
        if (view.revision().number()!=expected) throw new BusinessException(ErrorCode.CONFLICT,"分镜版本已变化，请重新载入后生成");
        String payload=planner.prepare(storyboards.normalizePlanningBrief(user,view.brief()));
        // UTF-8 byte count is a conservative input-token ceiling; configured prices must already include supplier premiums.
        BigDecimal ceiling=properties.estimate(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,properties.getMaxTokens());
        if (properties.getReservationPerCall().compareTo(ceiling)<0) throw new BusinessException(ErrorCode.CONFLICT,"每次调用预算预留不足以覆盖输入及最大输出，请先核对价格与授权");
        try { return repository.create(user,project,key,expected,payload,properties,System.currentTimeMillis()); }
        catch (DuplicateKeyException race) {
            prior=repository.byKey(user,key);
            if (prior==null) throw race;
            StoryboardModelRepository.requireSame(prior,project,expected); return prior;
        }
    }
    public java.util.List<StoryboardModelRepository.Task> recent(long user,String project) throws Exception { storyboards.get(user,project); return repository.recent(user,project); }
    public StoryboardModelRepository.Task get(long user,String project,String task) throws Exception { storyboards.get(user,project); return repository.owned(user,project,task); }
    public RuntimeView runtime() {
        boolean ready=true; String reason="已配置；提交会请求真实模型并可能扣费";
        try { properties.requireEnabled(); } catch (BusinessException blocked) { ready=false; reason=blocked.getMessage(); }
        var budget=repository.budget(properties.getAuthorizationId());
        if (ready && (budget.usedCalls()>=properties.getMaxCalls() || budget.reservedCost().add(properties.getReservationPerCall()).compareTo(properties.getBudgetLimit())>0
                || (budget.policyHash()!=null && !budget.policyHash().equals(properties.policyHash())))) {
            ready=false; reason="分镜调用次数或预算已用尽，或同一授权配置发生变化";
        }
        return new RuntimeView("deepseek",properties.getModel(),ready,reason,properties.getCurrency(),properties.getMaxCalls(),budget.usedCalls(),
                properties.getBudgetLimit(),budget.reservedCost(),properties.getReservationPerCall(),DeepSeekStoryboardPlanner.PROMPT_VERSION);
    }
    public record RuntimeView(String provider,String model,boolean ready,String reason,String currency,int maxCalls,int usedCalls,
            BigDecimal budgetLimit,BigDecimal reservedCost,BigDecimal reservationPerCall,String promptVersion) { }
}
