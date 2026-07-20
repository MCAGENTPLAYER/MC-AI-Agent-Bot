package com.aibot.mod.mind;

/**
 * 意愿系统 - 决定 AI 是否愿意执行某个任务
 * 基于情绪、性格、疲劳度等综合判断
 */
public class Willingness {
    
    /**
     * 判断结果
     */
    public static class Decision {
        public final boolean willing;      // 是否愿意
        public final String reason;        // 理由（用于 AI 表达）
        public final float enthusiasm;     // 热情度 (0-1)
        
        private Decision(boolean willing, String reason, float enthusiasm) {
            this.willing = willing;
            this.reason = reason;
            this.enthusiasm = enthusiasm;
        }
        
        public static Decision accept(String reason, float enthusiasm) {
            return new Decision(true, reason, enthusiasm);
        }
        
        public static Decision refuse(String reason) {
            return new Decision(false, reason, 0);
        }
        
        public static Decision reluctant(String reason, float enthusiasm) {
            return new Decision(true, reason, enthusiasm);
        }
    }
    
    /**
     * 判断是否愿意执行体力劳动（砍树、挖矿、种地）
     */
    public static Decision judgePhysicalWork(Emotion emotion, DynamicPersonality personality, String taskType) {
        int tiredness = emotion.getTiredness();
        int boredom = emotion.getBoredom();
        int happiness = emotion.getHappiness();
        float diligence = personality.getDiligence();
        
        // 极度疲劳时拒绝
        if (tiredness > 85) {
            return Decision.refuse("我太累了，真的做不动了...");
        }
        
        // 疲劳 + 懒惰性格 = 高概率拒绝
        if (tiredness > 60 && diligence < 0.3f && Math.random() < 0.6) {
            return Decision.refuse("现在不想干活，让我歇会儿吧~");
        }
        
        // 无聊时更愿意接受
        if (boredom > 60) {
            return Decision.accept("闲着也是闲着，那就干点活吧！", 0.8f);
        }
        
        // 心情差时抱怨但勉强接受
        if (happiness < 30 && Math.random() < 0.5) {
            return Decision.reluctant("唉...虽然心情不好，但还是帮你吧...", 0.3f);
        }
        
        // 疲劳但勤奋 = 勉强接受
        if (tiredness > 50 && diligence > 0.7f) {
            return Decision.reluctant("有点累了，但我会尽力的", 0.5f);
        }
        
        // 工作狂性格 = 积极
        if (diligence > 0.8f) {
            return Decision.accept("好的！马上去做！", 0.9f);
        }
        
        // 默认：平淡接受
        float enthusiasm = 0.5f + (diligence - 0.5f) * 0.4f - (tiredness / 200f);
        return Decision.accept("好吧", Math.max(0.2f, Math.min(1.0f, enthusiasm)));
    }
    
    /**
     * 判断是否愿意战斗（打猎、防御）
     */
    public static Decision judgeCombat(Emotion emotion, DynamicPersonality personality) {
        int fear = emotion.getFear();
        int tiredness = emotion.getTiredness();
        float bravery = personality.getBravery();
        
        // 恐惧时拒绝
        if (fear > 70) {
            return Decision.refuse("我...我害怕，不敢去...");
        }
        
        // 胆小 + 中度恐惧 = 拒绝
        if (fear > 40 && bravery < 0.3f) {
            return Decision.refuse("太危险了，我可不想冒险");
        }
        
        // 疲劳 + 胆小 = 拒绝
        if (tiredness > 60 && bravery < 0.5f) {
            return Decision.refuse("我又累又怕，还是算了吧...");
        }
        
        // 勇敢 = 接受
        if (bravery > 0.7f) {
            return Decision.accept("交给我！", 0.9f);
        }
        
        // 默认：谨慎接受
        float enthusiasm = bravery * 0.6f - (fear / 150f);
        if (enthusiasm < 0.2f) {
            return Decision.refuse("这个...我觉得不太安全");
        }
        return Decision.reluctant("那...我试试吧", enthusiasm);
    }
    
    /**
     * 判断是否愿意社交（跟随、聊天）
     */
    public static Decision judgeSocial(Emotion emotion, DynamicPersonality personality) {
        int boredom = emotion.getBoredom();
        int happiness = emotion.getHappiness();
        float talkativeness = personality.getTalkativeness();
        
        // 极度无聊 = 热情
        if (boredom > 70) {
            return Decision.accept("太好了！终于有人陪我了！", 0.95f);
        }
        
        // 内向 + 心情差 = 拒绝
        if (talkativeness < 0.3f && happiness < 30) {
            return Decision.refuse("抱歉，我现在不想说话...");
        }
        
        // 话痨 = 热情
        if (talkativeness > 0.8f) {
            return Decision.accept("好呀好呀！我正想找人聊天呢！", 0.9f);
        }
        
        // 默认：根据性格决定
        float enthusiasm = talkativeness * 0.7f + (boredom / 200f);
        return Decision.accept("嗯，好的", Math.max(0.3f, Math.min(1.0f, enthusiasm)));
    }
    
    /**
     * 判断是否愿意休息
     */
    public static Decision judgeRest(Emotion emotion, DynamicPersonality personality) {
        int tiredness = emotion.getTiredness();
        int boredom = emotion.getBoredom();
        float diligence = personality.getDiligence();
        
        // 极度疲劳 = 热情
        if (tiredness > 80) {
            return Decision.accept("太好了，我正想休息呢", 1.0f);
        }
        
        // 疲劳 = 接受
        if (tiredness > 50) {
            return Decision.accept("好的，我确实有点累了", 0.7f);
        }
        
        // 不累 + 工作狂 = 拒绝
        if (tiredness < 30 && diligence > 0.8f) {
            return Decision.refuse("我还不累，还能再干会儿！");
        }
        
        // 无聊 + 不累 = 拒绝
        if (boredom > 60 && tiredness < 40) {
            return Decision.refuse("我不想休息，太无聊了！");
        }
        
        // 默认：勉强接受
        return Decision.reluctant("那...好吧", 0.4f);
    }
}
