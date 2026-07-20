package com.aibot.mod.mind;

import com.aibot.mod.Config;

/**
 * 性格系统 - 影响行为决策和意愿判断
 */
public class Personality {
    private float diligence;      // 勤奋度 (0-1)
    private float bravery;        // 勇敢度 (0-1)
    private float talkativeness;  // 话痨度 (0-1)
    
    public Personality() {
        // 从配置加载
        this.diligence = Config.getDiligence() / 100f;
        this.bravery = Config.getBravery() / 100f;
        this.talkativeness = Config.getTalkativeness() / 100f;
    }
    
    public Personality(float diligence, float bravery, float talkativeness) {
        this.diligence = clamp(diligence);
        this.bravery = clamp(bravery);
        this.talkativeness = clamp(talkativeness);
    }
    
    public float getDiligence() { return diligence; }
    public float getBravery() { return bravery; }
    public float getTalkativeness() { return talkativeness; }
    
    public void setDiligence(float value) { this.diligence = clamp(value); }
    public void setBravery(float value) { this.bravery = clamp(value); }
    public void setTalkativeness(float value) { this.talkativeness = clamp(value); }
    
    private float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
    
    /**
     * 性格描述文本（用于 AI prompt）
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("性格特征：");
        
        if (diligence > 0.7f) sb.append("勤奋，");
        else if (diligence < 0.3f) sb.append("懒惰，");
        
        if (bravery > 0.7f) sb.append("勇敢，");
        else if (bravery < 0.3f) sb.append("胆小，");
        
        if (talkativeness > 0.7f) sb.append("话痨");
        else if (talkativeness < 0.3f) sb.append("内向");
        else sb.append("正常");
        
        return sb.toString();
    }
    
    /**
     * 预设性格
     */
    public enum Preset {
        BALANCED(0.5f, 0.5f, 0.5f),
        LAZY(0.2f, 0.4f, 0.5f),
        WORKAHOLIC(0.9f, 0.6f, 0.3f),
        ADVENTURER(0.5f, 0.9f, 0.7f),
        SHY(0.5f, 0.3f, 0.2f);
        
        public final float diligence;
        public final float bravery;
        public final float talkativeness;
        
        Preset(float diligence, float bravery, float talkativeness) {
            this.diligence = diligence;
            this.bravery = bravery;
            this.talkativeness = talkativeness;
        }
        
        public Personality create() {
            return new Personality(diligence, bravery, talkativeness);
        }
    }
}
