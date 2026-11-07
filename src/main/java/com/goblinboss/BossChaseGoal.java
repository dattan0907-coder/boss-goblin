package com.goblinboss;

import java.util.EnumSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

/** Đuổi theo mục tiêu tới khoảng ~3.5 block rồi đứng lại để dùng skill (skill được xử lý trong GoblinBossEntity). */
public class BossChaseGoal extends Goal {
    private final GoblinBossEntity boss;
    private int repath;

    public BossChaseGoal(GoblinBossEntity boss) {
        this.boss = boss;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = boss.getTarget();
        return t != null && t.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        repath = 0;
    }

    @Override
    public void stop() {
        boss.getNavigation().stop();
    }

    @Override
    public void tick() {
        LivingEntity t = boss.getTarget();
        if (t == null) return;
        boss.getLookControl().setLookAt(t, 30.0F, 30.0F);
        double d = boss.distanceToSqr(t);
        if (d > 12.25D) {
            if (--repath <= 0) {
                repath = 5 + boss.getRandom().nextInt(5);
                boss.getNavigation().moveTo(t, 1.0D);
            }
        } else {
            boss.getNavigation().stop();
        }
    }
}
