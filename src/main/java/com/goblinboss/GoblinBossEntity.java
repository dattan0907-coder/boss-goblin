package com.goblinboss;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;

/**
 * Goblin Boss - port từ MythicMobs (am_goblin_boss) sang NeoForge.
 *
 * Phase 1 (nonfrenzy): atk1..atk4, vỡ giáp ở 90% / 75% / 50% máu.
 * Phase 2 (frenzy): sau khi vỡ giáp thân (<=50%), gầm lên (atk5) rồi dùng atk11..atk44
 *                   (sát thương x2, cooldown /2), chạy nhanh hơn.
 */
public class GoblinBossEntity extends Monster implements GeoEntity {

    // ===== synced data =====
    /** 0 = đủ giáp, 1 = vỡ giáp tay trái + đầu gối, 2 = + tay phải + hông, 3 = + thân (frenzy) */
    private static final EntityDataAccessor<Integer> STAGE = SynchedEntityData.defineId(GoblinBossEntity.class, EntityDataSerializers.INT);
    /** 0 = không đánh, 1..5 = đang chạy animation atk1..atk5 */
    private static final EntityDataAccessor<Integer> ATTACK = SynchedEntityData.defineId(GoblinBossEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> ATTACK_SPEED = SynchedEntityData.defineId(GoblinBossEntity.class, EntityDataSerializers.FLOAT);
    /** Mắt đỏ (frenzy_eye) sau khi gầm xong */
    private static final EntityDataAccessor<Boolean> FRENZY_EYES = SynchedEntityData.defineId(GoblinBossEntity.class, EntityDataSerializers.BOOLEAN);

    private static final String A = "animation.goblin_boss.";
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop(A + "idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop(A + "walk");
    private static final RawAnimation FASTWALK = RawAnimation.begin().thenLoop(A + "fastwalk");
    private static final RawAnimation DEATH = RawAnimation.begin().thenPlayAndHold(A + "death");
    private static final RawAnimation[] ATK = {
            null,
            RawAnimation.begin().thenPlay(A + "atk1"),
            RawAnimation.begin().thenPlay(A + "atk2"),
            RawAnimation.begin().thenPlay(A + "atk3"),
            RawAnimation.begin().thenPlay(A + "atk4"),
            RawAnimation.begin().thenPlay(A + "atk5")
    };
    /** độ dài animation (giây) atk1..atk5 */
    private static final double[] ATK_LEN = {0, 3.5, 1.85, 3.25, 2.75, 2.0};

    private static final ResourceLocation FRENZY_SPEED_ID = ResourceLocation.fromNamespaceAndPath(GoblinBossMod.MODID, "frenzy_speed");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private final ServerBossEvent bossEvent = (ServerBossEvent) new ServerBossEvent(
            Component.literal("Goblin Boss"), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS)
            .setDarkenScreen(true).setCreateWorldFog(true);

    // ===== server state =====
    private boolean frenzyRoarPending = false;
    private int gcdUntil = 0;
    private int animTicks = 0;
    private int stepCooldown = 0;
    private final Map<Integer, Integer> cooldownUntil = new HashMap<>();
    private final List<Pending> pending = new ArrayList<>();

    private static final class Pending {
        int ticks;
        final Runnable run;
        Pending(int ticks, Runnable run) { this.ticks = ticks; this.run = run; }
    }

    public GoblinBossEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.xpReward = 50;
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 150.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.22D)
                .add(Attributes.ATTACK_DAMAGE, 0.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.75D)
                .add(Attributes.FOLLOW_RANGE, 50.0D);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new BossChaseGoal(this));
        this.goalSelector.addGoal(3, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 16.0F));
        this.goalSelector.addGoal(5, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, AbstractVillager.class, false));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, IronGolem.class, true));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(STAGE, 0);
        builder.define(ATTACK, 0);
        builder.define(ATTACK_SPEED, 1.0F);
        builder.define(FRENZY_EYES, false);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Stage", this.entityData.get(STAGE));
        tag.putBoolean("FrenzyEyes", this.entityData.get(FRENZY_EYES));
        tag.putBoolean("RoarPending", this.frenzyRoarPending);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.entityData.set(STAGE, tag.getInt("Stage"));
        this.entityData.set(FRENZY_EYES, tag.getBoolean("FrenzyEyes"));
        this.frenzyRoarPending = tag.getBoolean("RoarPending");
    }

    // ===== client getters (renderer) =====
    public int getStage() { return this.entityData.get(STAGE); }
    public boolean hasFrenzyEyes() { return this.entityData.get(FRENZY_EYES); }

    // =====================================================================
    //  Animation (GeckoLib)
    // =====================================================================
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 3, this::predicate));
    }

    private PlayState predicate(AnimationState<GoblinBossEntity> state) {
        if (this.isDeadOrDying()) {
            state.getController().setAnimationSpeed(1.0D);
            return state.setAndContinue(DEATH);
        }
        int atk = this.entityData.get(ATTACK);
        if (atk >= 1 && atk <= 5) {
            state.getController().setAnimationSpeed(this.entityData.get(ATTACK_SPEED));
            return state.setAndContinue(ATK[atk]);
        }
        state.getController().setAnimationSpeed(1.0D);
        if (state.isMoving()) {
            return state.setAndContinue(this.hasFrenzyEyes() ? FASTWALK : WALK);
        }
        return state.setAndContinue(IDLE);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    // =====================================================================
    //  Tick
    // =====================================================================
    @Override
    public void aiStep() {
        super.aiStep();
        // particle mắt khi <= 50% máu (dust_color_transition #F05455 -> #5B0C2A)
        if (this.level().isClientSide && this.isAlive() && this.getHealth() <= this.getMaxHealth() * 0.5F) {
            DustColorTransitionOptions dust = new DustColorTransitionOptions(
                    new Vector3f(240F / 255F, 84F / 255F, 85F / 255F),
                    new Vector3f(91F / 255F, 12F / 255F, 42F / 255F), 1.0F);
            for (int side = -1; side <= 1; side += 2) {
                Vec3 p = partPos(side * 2.5D, 37.5D, -7.0D);
                for (int i = 0; i < 2; i++) {
                    this.level().addParticle(dust,
                            p.x + (this.random.nextDouble() - 0.5D) * 0.1D,
                            p.y + (this.random.nextDouble() - 0.5D) * 0.1D,
                            p.z + (this.random.nextDouble() - 0.5D) * 0.1D, 0, 0, 0);
                }
            }
        }
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (!(this.level() instanceof ServerLevel sl)) return;

        // ---- boss bar (range 50) ----
        this.bossEvent.setProgress(this.getHealth() / this.getMaxHealth());
        if (this.tickCount % 10 == 0) {
            for (ServerPlayer p : sl.players()) {
                if (p.distanceTo(this) <= 50.0F && !p.isSpectator()) this.bossEvent.addPlayer(p);
                else this.bossEvent.removePlayer(p);
            }
        }

        // ---- scheduler ----
        List<Runnable> due = null;
        for (Iterator<Pending> it = this.pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (--p.ticks <= 0) {
                if (due == null) due = new ArrayList<>();
                due.add(p.run);
                it.remove();
            }
        }
        if (due != null) for (Runnable r : due) r.run();

        if (this.animTicks > 0 && --this.animTicks == 0) {
            this.entityData.set(ATTACK, 0);
        }

        // ---- sound ----
        if (this.tickCount % 205 == 0) sound("entity.strider.happy", 2.0F, 0.5F);
        if (this.onGround() && this.getDeltaMovement().horizontalDistanceSqr() > 2.5E-4D && --this.stepCooldown <= 0) {
            sound("block.ancient_debris.step", 0.8F, 0.7F);
            this.stepCooldown = this.hasFrenzyEyes() ? 11 : 20;
        }

        // ---- chọn skill mỗi 20 tick (onTimer:20) ----
        if (this.tickCount % 20 == 0 && this.tickCount >= this.gcdUntil) {
            chooseSkill();
        }
    }

    // =====================================================================
    //  Skill selection
    // =====================================================================
    private void chooseSkill() {
        boolean frenzy = getStage() >= 3;
        int id;
        if (!frenzy) {
            int roll = 1 + this.random.nextInt(5); // randomskill atk1..atk5
            id = (roll == 5) ? 0 : roll;           // atk5 cần tag frenzyroar -> chưa có
        } else if (this.frenzyRoarPending && this.random.nextInt(5) == 0) {
            id = 5;
        } else {
            id = new int[]{11, 22, 33, 44}[this.random.nextInt(4)];
        }
        if (id == 0) return;
        if (id == 5) {
            if (this.frenzyRoarPending) castSkill(5);
            return;
        }
        LivingEntity t = this.getTarget();
        if (t == null || !t.isAlive()) return;
        double range = (id == 4 || id == 44) ? 25.0D : 6.0D;
        if (this.distanceTo(t) > range) return;
        if (this.tickCount < this.cooldownUntil.getOrDefault(id, 0)) return;
        castSkill(id);
    }

    private void castSkill(int id) {
        switch (id) {
            case 1 -> atk1();
            case 2 -> atk2();
            case 3 -> atk3();
            case 4 -> atk4(false);
            case 5 -> atk5();
            case 11 -> atk11();
            case 22 -> atk22();
            case 33 -> atk33();
            case 44 -> atk4(true);
        }
    }

    private void startSkill(int gcdTicks, int cooldownTicks, int id) {
        this.gcdUntil = this.tickCount + gcdTicks;
        if (cooldownTicks > 0) this.cooldownUntil.put(id, this.tickCount + cooldownTicks);
    }

    private void playAnim(int anim, float speed) {
        this.entityData.set(ATTACK, anim);
        this.entityData.set(ATTACK_SPEED, speed);
        this.animTicks = (int) Math.ceil(ATK_LEN[anim] * 20.0D / speed);
    }

    private void later(int delay, Runnable r) {
        if (delay <= 0) r.run();
        else this.pending.add(new Pending(delay, r));
    }

    // =====================================================================
    //  Phase 1 skills
    // =====================================================================
    private void atk1() {
        startSkill(75, 160, 1);
        playAnim(1, 1.5F);
        later(12, () -> sound("entity.witch.throw", 1.0F, 0.5F));
        later(28, () -> sound("item.trident.throw", 1.0F, 0.4F));
        later(14, () -> damageCone(8.0F, 120, 6));
        later(30, () -> damageCone(10.0F, 90, 6));
    }

    private void atk2() {
        startSkill(37, 160, 2);
        slowSelf(40, 20);
        playAnim(2, 1.0F);
        later(15, () -> slam180(14.0F));
    }

    private void atk3() {
        startSkill(70, 160, 3);
        playAnim(3, 1.0F);
        later(23, () -> sound("entity.witch.throw", 1.0F, 0.3F));
        later(37, () -> sound("entity.witch.throw", 1.0F, 0.3F));
        later(25, () -> { damageCone(9.0F, 120, 6); throwCone(120, 6, 0.2D, 0.6D); });
        later(39, () -> { damageCone(9.0F, 90, 6); throwCone(90, 6, 0.2D, 0.6D); });
    }

    private void atk4(boolean frenzy) {
        if (!frenzy) {
            startSkill(60, 400, 4);
            playAnim(4, 1.0F);
            slowSelf(19, 20);
            later(20, () -> hop(0.35D));
            later(30, this::leapToTarget);
            later(45, () -> bigSlam(16.0F));
        } else {
            startSkill(42, 160, 44);
            playAnim(4, 1.5F);
            slowSelf(19, 20);
            later(5, () -> hop(0.44D));
            later(13, this::leapToTarget);
            later(25, () -> bigSlam(32.0F));
        }
    }

    private void atk5() {
        startSkill(40, 0, 5);
        playAnim(5, 1.0F);
        this.frenzyRoarPending = false;
        later(10, () -> sound("entity.warden.roar", 1.2F, 1.5F));
        later(15, () -> {
            this.entityData.set(FRENZY_EYES, true);
            // setspeed 1.7 (walking)
            var attr = this.getAttribute(Attributes.MOVEMENT_SPEED);
            if (attr != null) {
                attr.addOrReplacePermanentModifier(new AttributeModifier(FRENZY_SPEED_ID, 0.7D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
            for (Player p : playersInRadius(40.0D)) {
                p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 2), this);
            }
            for (Player p : playersInRadius(25.0D)) knock(p, 0.8D, 0.6D);
        });
    }

    // =====================================================================
    //  Phase 2 (frenzy) skills
    // =====================================================================
    private void atk11() {
        startSkill(40, 100, 11);
        playAnim(1, 2.0F);
        later(6, () -> sound("entity.witch.throw", 1.0F, 0.5F));
        later(20, () -> sound("item.trident.throw", 1.0F, 0.4F));
        later(6, () -> damageCone(16.0F, 120, 6));
        later(20, () -> damageCone(20.0F, 120, 7));
    }

    private void atk22() {
        startSkill(24, 100, 22);
        slowSelf(24, 20);
        playAnim(2, 2.0F);
        later(8, () -> slam180(28.0F));
    }

    private void atk33() {
        startSkill(38, 100, 33);
        playAnim(3, 2.0F);
        later(13, () -> sound("entity.witch.throw", 1.0F, 0.3F));
        later(23, () -> sound("entity.witch.throw", 1.0F, 0.3F));
        later(13, () -> { damageCone(18.0F, 120, 6); throwCone(120, 6, 0.2D, 0.2D); });
        later(23, () -> { damageCone(18.0F, 120, 6); throwCone(120, 6, 0.2D, 0.6D); });
    }

    // =====================================================================
    //  Skill helpers
    // =====================================================================
    private void slowSelf(int ticks, int amp) {
        this.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, amp, false, false));
    }

    /** atk2 / atk22: đập đất 180 độ, r=6, làm chậm + sát thương + vòng bụi */
    private void slam180(float dmg) {
        for (LivingEntity e : cone(180, 6)) {
            e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20, 3), this);
        }
        sound("item.mace.smash_ground", 1.5F, 0.8F);
        ring(3.0D, 64);
        damageCone(dmg, 180, 6);
    }

    /** atk4 / atk44: nhảy đập, AoE lên người chơi trong r=12 */
    private void bigSlam(float dmg) {
        for (Player p : playersInRadius(12.0D)) {
            p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 2), this);
        }
        sound("item.mace.smash_ground", 1.5F, 0.8F);
        ring(6.0D, 128);
        for (Player p : playersInRadius(12.0D)) {
            p.hurt(this.damageSources().mobAttack(this), dmg);
        }
    }

    private void hop(double vy) {
        Vec3 d = this.getDeltaMovement();
        this.setDeltaMovement(d.x, vy, d.z);
        this.hasImpulse = true;
    }

    private void leapToTarget() {
        LivingEntity t = this.getTarget();
        Vec3 d;
        if (t != null) d = t.position().subtract(this.position());
        else d = new Vec3(-Math.sin(Math.toRadians(this.yBodyRot)), 0, Math.cos(Math.toRadians(this.yBodyRot))).scale(6);
        double hd = Math.sqrt(d.x * d.x + d.z * d.z);
        double v = hd < 0.01D ? 0.0D : Math.min(hd / 8.0D, 1.5D);
        double nx = hd < 0.01D ? 0 : d.x / hd, nz = hd < 0.01D ? 0 : d.z / hd;
        this.setDeltaMovement(nx * v, 0.62D, nz * v);
        this.hasImpulse = true;
    }

    private void sound(String id, float vol, float pitch) {
        SoundEvent ev = BuiltInRegistries.SOUND_EVENT.get(ResourceLocation.parse("minecraft:" + id));
        if (ev == null) return;
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(), ev, SoundSource.HOSTILE, vol, pitch);
    }

    private List<LivingEntity> cone(double angleDeg, double radius) {
        double yaw = Math.toRadians(this.yBodyRot);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double cosHalf = Math.cos(Math.toRadians(angleDeg / 2.0D));
        return this.level().getEntitiesOfClass(LivingEntity.class,
                this.getBoundingBox().inflate(radius, 2.0D, radius), e -> {
                    if (e == this || e instanceof GoblinBossEntity || !e.isAlive() || e.isSpectator()) return false;
                    Vec3 d = e.position().subtract(this.position());
                    double hd = Math.sqrt(d.x * d.x + d.z * d.z);
                    if (hd - e.getBbWidth() / 2.0D > radius) return false;
                    if (hd < 1.0E-4D) return true;
                    return (d.x * fx + d.z * fz) / hd >= cosHalf;
                });
    }

    private void damageCone(float amount, double angle, double radius) {
        for (LivingEntity e : cone(angle, radius)) {
            e.hurt(this.damageSources().mobAttack(this), amount);
        }
    }

    private void throwCone(double angle, double radius, double h, double v) {
        for (LivingEntity e : cone(angle, radius)) knock(e, h, v);
    }

    private void knock(LivingEntity e, double h, double v) {
        Vec3 d = e.position().subtract(this.position());
        double hd = Math.sqrt(d.x * d.x + d.z * d.z);
        double nx = hd < 1.0E-4D ? 0 : d.x / hd, nz = hd < 1.0E-4D ? 0 : d.z / hd;
        e.setDeltaMovement(nx * h, v, nz * h);
        e.hurtMarked = true;
    }

    private List<Player> playersInRadius(double r) {
        return this.level().getEntitiesOfClass(Player.class, this.getBoundingBox().inflate(r),
                p -> !p.isSpectator() && p.isAlive() && p.distanceTo(this) <= r);
    }

    /** particlering{p=block} quanh chân boss */
    private void ring(double radius, int points) {
        if (!(this.level() instanceof ServerLevel sl)) return;
        BlockState bs = this.level().getBlockState(this.blockPosition().below());
        if (bs.isAir()) bs = Blocks.STONE.defaultBlockState();
        BlockParticleOption opt = new BlockParticleOption(ParticleTypes.BLOCK, bs);
        for (int i = 0; i < points; i++) {
            double a = 2.0D * Math.PI * i / points;
            sl.sendParticles(opt, this.getX() + Math.cos(a) * radius, this.getY() + 0.1D, this.getZ() + Math.sin(a) * radius,
                    3, 0.3D, 0.05D, 0.3D, 0.1D);
        }
    }

    /** vị trí trong world của 1 điểm trên model (đơn vị px của Blockbench, 16px = 1 block) */
    private Vec3 partPos(double x, double y, double z) {
        double yaw = Math.toRadians(this.yBodyRot);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double rx = -fz, rz = fx;
        double fwd = -z / 16.0D, right = x / 16.0D;
        return this.position().add(rx * right + fx * fwd, y / 16.0D, rz * right + fz * fwd);
    }

    // =====================================================================
    //  Armor break
    // =====================================================================
    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean r = super.hurt(source, amount);
        if (r && !this.level().isClientSide && this.isAlive()) checkArmorBreak();
        return r;
    }

    private void checkArmorBreak() {
        float pct = this.getHealth() / this.getMaxHealth();
        if (getStage() == 0 && pct <= 0.90F) {
            this.entityData.set(STAGE, 1);
            sound("item.shield.break", 1.5F, 1.2F);
            armorParticles(25, partPos(-8.7D, 29.35D, 0.5D), partPos(-3.5D, 9.75D, -0.75D));
        }
        if (getStage() == 1 && pct <= 0.75F) {
            this.entityData.set(STAGE, 2);
            sound("item.shield.break", 1.5F, 0.8F);
            armorParticles(25, partPos(8.7D, 29.35D, 0.5D), partPos(0.0D, 21.0D, 0.0D));
        }
        if (getStage() == 2 && pct <= 0.50F) {
            this.entityData.set(STAGE, 3);
            sound("item.shield.break", 1.5F, 0.6F);
            armorParticles(40, partPos(0.0D, 29.5D, 0.0D));
            this.frenzyRoarPending = true;
        }
    }

    private void armorParticles(int count, Vec3... at) {
        if (!(this.level() instanceof ServerLevel sl)) return;
        BlockParticleOption opt = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DEEPSLATE.defaultBlockState());
        for (Vec3 p : at) sl.sendParticles(opt, p.x, p.y, p.z, count, 0.15D, 0.15D, 0.15D, 0.05D);
    }

    // =====================================================================
    //  Sounds / death
    // =====================================================================
    @Override
    protected void playHurtSound(DamageSource source) {
        sound("entity.strider.hurt", 1.5F, 0.6F);
        if (this.getHealth() >= this.getMaxHealth() * 0.5F) sound("entity.blaze.hurt", 1.0F, 0.6F);
    }

    @Override
    protected SoundEvent getDeathSound() {
        return BuiltInRegistries.SOUND_EVENT.get(ResourceLocation.parse("minecraft:entity.strider.death"));
    }

    @Override
    protected float getSoundVolume() { return 2.0F; }

    @Override
    public float getVoicePitch() { return 0.6F; }

    @Override
    protected SoundEvent getAmbientSound() { return null; }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) { /* step sound được xử lý riêng */ }

    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, DamageSource source) { return false; }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) { return false; }

    /** Animation death dài 2.5s -> giữ xác 50 tick thay vì 20. */
    @Override
    protected void tickDeath() {
        ++this.deathTime;
        if (this.level() instanceof ServerLevel sl) {
            if (this.deathTime == 49) {
                // am_goblin_boss_death
                sl.sendParticles(ParticleTypes.POOF, partPos(0, 24, 0).x, partPos(0, 24, 0).y, partPos(0, 24, 0).z, 25, 1.0D, 0.7D, 1.0D, 0.02D);
                Vec3 torso = partPos(0, 24, 0), leg = partPos(0, 8, 0), arm = partPos(8, 22, 0);
                for (Vec3 p : new Vec3[]{torso, leg, arm}) {
                    sl.sendParticles(ParticleTypes.POOF, p.x, p.y, p.z, 5, 0.4D, 0.4D, 0.4D, 0.02D);
                }
            }
            if (this.deathTime >= 50 && !this.isRemoved()) {
                sl.broadcastEntityEvent(this, (byte) 60);
                this.remove(RemovalReason.KILLED);
            }
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        this.bossEvent.removeAllPlayers();
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        if (player.distanceTo(this) <= 50.0F) this.bossEvent.addPlayer(player);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        this.bossEvent.removePlayer(player);
    }
}
