package com.example.server.storyboard;

import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static com.example.server.storyboard.StoryboardDraft.*;

/** Deterministic product-introduction template; no model, HTTP or paid service is invoked. */
@Component
public class TemplateStoryboardPlanner implements StoryboardPlanner {
    public String name() { return "product-template-v1"; }
    public StoryboardDraft draft(CreativeBrief brief, List<String> previousErrors) {
        List<Shot> shots = new ArrayList<>();
        for (int index = 0; index < brief.shotCount(); index++) {
            boolean first = index == 0, last = index == brief.shotCount() - 1;
            String point = brief.sellingPoints().get(Math.max(0, index - 1) % brief.sellingPoints().size());
            String title = first ? "产品开场" : last ? "产品总结" : "卖点展示 " + index;
            String action = first ? "展示产品整体外观" : last ? "定格产品全貌与核心卖点" : "展示与「" + point + "」相关的产品细节";
            String camera = first ? "缓慢推进" : last ? "镜头平稳拉远" : "平稳横移与局部特写";
            String caption = first ? brief.productName() : last ? "了解更多" : point;
            String narration = first ? "认识" + brief.productName() + "。" : last ? "让" + brief.productName() + "融入你的生活。" : point + "。";
            int duration = brief.desiredDurationSeconds() / brief.shotCount()
                    + (index < brief.desiredDurationSeconds() % brief.shotCount() ? 1 : 0);
            String setting = "简洁产品展示场景，柔和自然光";
            String prompt = brief.productName() + "，" + action + "，" + setting + "，" + camera + "，" + brief.style();
            shots.add(new Shot(UUID.randomUUID().toString(), index + 1, title, brief.productName(), action,
                    setting, camera, brief.referenceAssetId(), duration, brief.frameRatio(), caption, narration,
                    prompt, 1, new Parameters(null, null)));
        }
        return new StoryboardDraft("创作目标：" + brief.goal() + "\n" + String.join("\n", shots.stream().map(Shot::narration).toList()), shots);
    }
}
