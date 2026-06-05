/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.api.animation.layer;

import kr.toxicity.model.api.animation.AnimationChannel;
import kr.toxicity.model.api.animation.AnimationIterator;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.animation.AnimationProgress;
import kr.toxicity.model.api.bone.BoneMovement;
import kr.toxicity.model.api.bone.BoneName;
import kr.toxicity.model.api.data.blueprint.BlueprintAnimation;
import kr.toxicity.model.api.data.blueprint.BlueprintAnimator;
import kr.toxicity.model.api.tracker.Tracker;
import kr.toxicity.model.api.util.InterpolationUtil;
import kr.toxicity.model.api.util.MathUtil;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Internal state-machine layer engine for a single bone.
 * <p>
 * This separates layered animation composition from render-bone ownership so the
 * layer system can evolve independently from display and tracker internals.
 * </p>
 *
 * @since 3.0.2
 */
@ApiStatus.Internal
public final class BoneAnimationLayerEngine {

    private static final float TRACKER_TICK_SECONDS = Tracker.TRACKER_TICK_INTERVAL / 1000F;

    private final @NotNull BoneName boneName;
    private final @NotNull BoneMovement defaultFrame;
    private final TreeMap<Integer, LinkedHashMap<String, StateAnimationSlot>> stateSlots = new TreeMap<>();
    private final Map<String, StateAnimationSlot> stateSlotsByKey = new HashMap<>();

    /**
     * Creates a new bone layer engine.
     *
     * @param boneName target bone name
     * @param defaultFrame default bone transform
     * @since 3.0.2
     */
    public BoneAnimationLayerEngine(@NotNull BoneName boneName, @NotNull BoneMovement defaultFrame) {
        this.boneName = boneName;
        this.defaultFrame = defaultFrame;
    }

    /**
     * Checks whether any layer slot is active.
     *
     * @return true if at least one slot exists
     * @since 3.0.2
     */
    public boolean hasSlots() {
        synchronized (stateSlots) {
            return !stateSlotsByKey.isEmpty();
        }
    }

    /**
     * Replaces or creates a layer slot.
     *
     * @param slotKey stable slot key
     * @param priority slot priority
     * @param animation animation source
     * @param modifier animation modifier
     * @param skipLastFrame whether loop playback should omit the terminal replay frame
     * @param emptyZero whether empty channels should behave as zero/default channels
     * @return true if the slot changed
     * @since 3.0.2
     */
    public boolean setSlot(
        @NotNull String slotKey,
        int priority,
        @NotNull BlueprintAnimation animation,
        @NotNull AnimationModifier modifier,
        @Nullable Boolean skipLastFrame,
        @Nullable Boolean emptyZero
    ) {
        var type = modifier.type(animation.loop());
        var animator = animation.animator().get(boneName);
        var slot = new StateAnimationSlot(
            slotKey,
            priority,
            animation,
            animator,
            modifier,
            type,
            modifier.override(animation.override()),
            animator != null ? copyChannels(animator.channels()) : EnumSet.noneOf(AnimationChannel.class),
            Boolean.TRUE.equals(skipLastFrame),
            Boolean.TRUE.equals(emptyZero)
        );
        synchronized (stateSlots) {
            var previous = stateSlotsByKey.get(slotKey);
            if (previous != null) {
                if (sameSlot(previous, priority, animation, modifier, type, skipLastFrame, emptyZero)) {
                    return false;
                }
                slot.capturePrevious(previous.sampleCurrent());
                removeSlot(previous);
            }
            addSlot(slot);
        }
        return true;
    }

    /**
     * Stops a slot.
     *
     * @param slotKey slot identifier
     * @param force whether to remove immediately
     * @return true if the slot existed
     * @since 3.0.2
     */
    public boolean stopSlot(@NotNull String slotKey, boolean force) {
        synchronized (stateSlots) {
            var slot = stateSlotsByKey.get(slotKey);
            if (slot == null) {
                return false;
            }
            if (force || !slot.beginLerpOut()) {
                removeSlot(slot);
            }
        }
        return true;
    }

    /**
     * Clears every slot.
     *
     * @since 3.0.2
     */
    public void clearSlots() {
        synchronized (stateSlots) {
            stateSlots.clear();
            stateSlotsByKey.clear();
        }
    }

    /**
     * Advances slot timers.
     *
     * @return true if any slot changed this tick
     * @since 3.0.2
     */
    public boolean tick() {
        synchronized (stateSlots) {
            if (stateSlotsByKey.isEmpty()) {
                return false;
            }
            var changed = false;
            for (var entry = stateSlots.entrySet().iterator(); entry.hasNext(); ) {
                var byPriority = entry.next().getValue();
                for (var iterator = byPriority.values().iterator(); iterator.hasNext(); ) {
                    var slot = iterator.next();
                    var tickResult = slot.tick();
                    changed |= tickResult.changed();
                    if (tickResult.remove()) {
                        iterator.remove();
                        stateSlotsByKey.remove(slot.slotKey());
                    }
                }
                if (byPriority.isEmpty()) {
                    entry.remove();
                }
            }
            return changed;
        }
    }

    /**
     * Applies layered composition over an already-sampled base movement.
     *
     * @param target sampled base movement to mutate
     * @param baseSkipInterpolation base animation skip-interpolation flag
     * @param baseGlobalRotation base animation global-rotation flag
     * @param baseFrameDuration base animation frame duration in seconds
     * @return composition result flags
     * @since 3.0.2
     */
    public @NotNull LayerComposition compose(
        @NotNull BoneMovement target,
        boolean baseSkipInterpolation,
        boolean baseGlobalRotation,
        float baseFrameDuration
    ) {
        var globalRotation = baseGlobalRotation;
        var skipInterpolation = baseSkipInterpolation;
        var frameDuration = mergeFrame(0F, baseFrameDuration);
        synchronized (stateSlots) {
            for (var byPriority : stateSlots.values()) {
                for (var slot : byPriority.values()) {
                    if (!slot.modifier().predicateValue()) {
                        continue;
                    }
                    var currentSample = slot.sampleCurrent();
                    var previousSample = slot.previous();
                    frameDuration = mergeFrame(frameDuration, currentSample.frameDuration());
                    if (previousSample != null) {
                        frameDuration = mergeFrame(frameDuration, previousSample.frameDuration());
                    }
                    skipInterpolation |= currentSample.skipInterpolation() || (previousSample != null && previousSample.skipInterpolation());
                    switch (slot.phase()) {
                        case PLAY -> {
                            if (currentSample.channels().contains(AnimationChannel.POSITION)) {
                                applyPlayPosition(target, currentSample, slot.override());
                            }
                            if (currentSample.channels().contains(AnimationChannel.SCALE)) {
                                applyPlayScale(target, currentSample, slot.override());
                            }
                            if (currentSample.channels().contains(AnimationChannel.ROTATION)) {
                                applyPlayRotation(target, currentSample, slot.override());
                                globalRotation = currentSample.globalRotation();
                            }
                        }
                        case LERPOUT -> {
                            if (currentSample.channels().contains(AnimationChannel.POSITION)) {
                                applyFadeOutPosition(target, currentSample, slot.override(), slot.lerpOutRatio());
                            }
                            if (currentSample.channels().contains(AnimationChannel.SCALE)) {
                                applyFadeOutScale(target, currentSample, slot.override(), slot.lerpOutRatio());
                            }
                            if (currentSample.channels().contains(AnimationChannel.ROTATION)) {
                                applyFadeOutRotation(target, currentSample, slot.override(), slot.lerpOutRatio());
                                globalRotation = currentSample.globalRotation();
                            }
                        }
                        case LERPIN -> {
                            if (currentSample.channels().contains(AnimationChannel.POSITION)
                                || previousSample != null && previousSample.channels().contains(AnimationChannel.POSITION)) {
                                applyLerpInPosition(target, currentSample, previousSample, slot.override(), slot.lerpInRatio());
                            }
                            if (currentSample.channels().contains(AnimationChannel.SCALE)
                                || previousSample != null && previousSample.channels().contains(AnimationChannel.SCALE)) {
                                applyLerpInScale(target, currentSample, previousSample, slot.override(), slot.lerpInRatio());
                            }
                            if (currentSample.channels().contains(AnimationChannel.ROTATION)
                                || previousSample != null && previousSample.channels().contains(AnimationChannel.ROTATION)) {
                                applyLerpInRotation(target, currentSample, previousSample, slot.override(), slot.lerpInRatio());
                                globalRotation = currentSample.channels().contains(AnimationChannel.ROTATION)
                                    ? currentSample.globalRotation()
                                    : previousSample != null && previousSample.globalRotation();
                            }
                        }
                    }
                }
            }
        }
        return new LayerComposition(skipInterpolation, globalRotation, frameDuration);
    }

    private void addSlot(@NotNull StateAnimationSlot slot) {
        stateSlots.computeIfAbsent(slot.priority(), ignored -> new LinkedHashMap<>()).put(slot.slotKey(), slot);
        stateSlotsByKey.put(slot.slotKey(), slot);
    }

    private void removeSlot(@NotNull StateAnimationSlot slot) {
        var slots = stateSlots.get(slot.priority());
        if (slots != null) {
            slots.remove(slot.slotKey());
            if (slots.isEmpty()) {
                stateSlots.remove(slot.priority());
            }
        }
        stateSlotsByKey.remove(slot.slotKey());
    }

    private boolean sameSlot(
        @NotNull StateAnimationSlot previous,
        int priority,
        @NotNull BlueprintAnimation animation,
        @NotNull AnimationModifier modifier,
        @NotNull AnimationIterator.Type type,
        @Nullable Boolean skipLastFrame,
        @Nullable Boolean emptyZero
    ) {
        if (previous.phase() == SlotPhase.LERPOUT) {
            return false;
        }
        if (previous.priority() != priority) {
            return false;
        }
        if (!previous.animation().name().equals(animation.name())) {
            return false;
        }
        if (previous.type() != type) {
            return false;
        }
        if (previous.override() != modifier.override(animation.override())) {
            return false;
        }
        if (previous.skipLastFrame() != Boolean.TRUE.equals(skipLastFrame)) {
            return false;
        }
        if (previous.emptyZero() != Boolean.TRUE.equals(emptyZero)) {
            return false;
        }
        var previousModifier = previous.modifier();
        if (previousModifier.start() != modifier.start() || previousModifier.end() != modifier.end()) {
            return false;
        }
        if (!MathUtil.isSimilar(previousModifier.speedValue(), modifier.speedValue())) {
            return false;
        }
        return previousModifier.player() == modifier.player();
    }

    private @NotNull SampledState sampleAnimator(
        @Nullable BlueprintAnimator animator,
        @NotNull EnumSet<AnimationChannel> channels,
        float elapsedSeconds,
        @NotNull AnimationIterator.Type type,
        float speed
    ) {
        if (animator == null || channels.isEmpty()) {
            return new SampledState(new BoneMovement().set(defaultFrame), EnumSet.noneOf(AnimationChannel.class), false, false, 0F);
        }

        var keyframe = animator.keyframe();
        if (keyframe.size() == 0) {
            return new SampledState(new BoneMovement().set(defaultFrame), copyChannels(channels), false, false, 0F);
        }

        if (elapsedSeconds <= MathUtil.FLOAT_COMPARISON_EPSILON) {
            var progress = keyframe.get(0);
            return new SampledState(
                progress.animate(defaultFrame, new BoneMovement()),
                copyChannels(channels),
                progress.skipInterpolation(),
                progress.globalRotation(),
                keyframe.size() > 1 ? keyframe.get(1).time() / speed : 0F
            );
        }

        float totalLength = 0F;
        for (int i = 1; i < keyframe.size(); i++) {
            totalLength += keyframe.get(i).time();
        }
        float localTime = switch (type) {
            case LOOP -> totalLength <= MathUtil.FLOAT_COMPARISON_EPSILON ? 0F : elapsedSeconds % totalLength;
            case HOLD_ON_LAST, PLAY_ONCE -> Math.min(elapsedSeconds, totalLength);
        };

        float cumulative = 0F;
        AnimationProgress before = keyframe.get(0);
        for (int i = 1; i < keyframe.size(); i++) {
            var next = keyframe.get(i);
            cumulative += next.time();
            if (localTime <= cumulative + MathUtil.FLOAT_COMPARISON_EPSILON) {
                var beforeMovement = before.animate(defaultFrame, new BoneMovement());
                if (next.time() <= MathUtil.FLOAT_COMPARISON_EPSILON || next.skipInterpolation()) {
                    return new SampledState(
                        next.animate(defaultFrame, new BoneMovement()),
                        copyChannels(channels),
                        before.skipInterpolation() || next.skipInterpolation(),
                        before.globalRotation() || next.globalRotation(),
                        next.time() / speed
                    );
                }
                float start = cumulative - next.time();
                float alpha = Math.clamp((localTime - start) / next.time(), 0F, 1F);
                var afterMovement = next.animate(defaultFrame, new BoneMovement());
                return new SampledState(
                    beforeMovement.lerp(afterMovement, alpha, new BoneMovement()),
                    copyChannels(channels),
                    before.skipInterpolation() || next.skipInterpolation(),
                    before.globalRotation() || next.globalRotation(),
                    next.time() / speed
                );
            }
            before = next;
        }

        return new SampledState(
            keyframe.getLast().animate(defaultFrame, new BoneMovement()),
            copyChannels(channels),
            keyframe.getLast().skipInterpolation(),
            keyframe.getLast().globalRotation(),
            0F
        );
    }

    private float mergeFrame(float current, float candidate) {
        if (candidate <= 0F) {
            return current;
        }
        return current <= 0F ? candidate : Math.min(current, candidate);
    }

    private void applyPlayPosition(@NotNull BoneMovement target, @NotNull SampledState sample, boolean override) {
        if (override) {
            target.position().set(sample.movement().position());
            return;
        }
        target.position().add(sample.movement().position().sub(defaultFrame.position(), new Vector3f()));
    }

    private void applyPlayScale(@NotNull BoneMovement target, @NotNull SampledState sample, boolean override) {
        if (override) {
            target.scale().set(sample.movement().scale());
            return;
        }
        target.scale().mul(scaleRatio(defaultFrame.scale(), sample.movement().scale(), new Vector3f(1F)));
    }

    private void applyPlayRotation(@NotNull BoneMovement target, @NotNull SampledState sample, boolean override) {
        if (override) {
            target.rawRotation().set(sample.movement().rawRotation());
        } else {
            target.rawRotation().add(sample.movement().rawRotation().sub(defaultFrame.rawRotation(), new Vector3f()));
        }
        MathUtil.toQuaternion(target.rawRotation(), target.rotation());
    }

    private void applyFadeOutPosition(@NotNull BoneMovement target, @NotNull SampledState sample, boolean override, float alpha) {
        var currentValue = override
            ? sample.movement().position()
            : target.position().add(sample.movement().position().sub(defaultFrame.position(), new Vector3f()), new Vector3f());
        target.position().set(InterpolationUtil.lerp(currentValue, target.position(), alpha, new Vector3f()));
    }

    private void applyFadeOutScale(@NotNull BoneMovement target, @NotNull SampledState sample, boolean override, float alpha) {
        var currentValue = override
            ? sample.movement().scale()
            : target.scale().mul(scaleRatio(defaultFrame.scale(), sample.movement().scale(), new Vector3f(1F)), new Vector3f());
        target.scale().set(InterpolationUtil.lerp(currentValue, target.scale(), alpha, new Vector3f()));
    }

    private void applyFadeOutRotation(@NotNull BoneMovement target, @NotNull SampledState sample, boolean override, float alpha) {
        var currentValue = override
            ? sample.movement().rawRotation()
            : target.rawRotation().add(sample.movement().rawRotation().sub(defaultFrame.rawRotation(), new Vector3f()), new Vector3f());
        target.rawRotation().set(InterpolationUtil.lerp(currentValue, target.rawRotation(), alpha, new Vector3f()));
        MathUtil.toQuaternion(target.rawRotation(), target.rotation());
    }

    private void applyLerpInPosition(
        @NotNull BoneMovement target,
        @NotNull SampledState currentSample,
        @Nullable SampledState previousSample,
        boolean override,
        float alpha
    ) {
        var base = new Vector3f(target.position());
        var currentValue = currentSample.channels().contains(AnimationChannel.POSITION)
            ? (override
                ? currentSample.movement().position()
                : base.add(currentSample.movement().position().sub(defaultFrame.position(), new Vector3f()), new Vector3f()))
            : base;
        var previousValue = previousSample != null && previousSample.channels().contains(AnimationChannel.POSITION)
            ? (override
                ? previousSample.movement().position()
                : base.add(previousSample.movement().position().sub(defaultFrame.position(), new Vector3f()), new Vector3f()))
            : base;
        target.position().set(InterpolationUtil.lerp(previousValue, currentValue, alpha, new Vector3f()));
    }

    private void applyLerpInScale(
        @NotNull BoneMovement target,
        @NotNull SampledState currentSample,
        @Nullable SampledState previousSample,
        boolean override,
        float alpha
    ) {
        var base = new Vector3f(target.scale());
        var currentValue = currentSample.channels().contains(AnimationChannel.SCALE)
            ? (override
                ? currentSample.movement().scale()
                : base.mul(scaleRatio(defaultFrame.scale(), currentSample.movement().scale(), new Vector3f(1F)), new Vector3f()))
            : base;
        var previousValue = previousSample != null && previousSample.channels().contains(AnimationChannel.SCALE)
            ? (override
                ? previousSample.movement().scale()
                : base.mul(scaleRatio(defaultFrame.scale(), previousSample.movement().scale(), new Vector3f(1F)), new Vector3f()))
            : base;
        target.scale().set(InterpolationUtil.lerp(previousValue, currentValue, alpha, new Vector3f()));
    }

    private void applyLerpInRotation(
        @NotNull BoneMovement target,
        @NotNull SampledState currentSample,
        @Nullable SampledState previousSample,
        boolean override,
        float alpha
    ) {
        var base = new Vector3f(target.rawRotation());
        var currentValue = currentSample.channels().contains(AnimationChannel.ROTATION)
            ? (override
                ? currentSample.movement().rawRotation()
                : base.add(currentSample.movement().rawRotation().sub(defaultFrame.rawRotation(), new Vector3f()), new Vector3f()))
            : base;
        var previousValue = previousSample != null && previousSample.channels().contains(AnimationChannel.ROTATION)
            ? (override
                ? previousSample.movement().rawRotation()
                : base.add(previousSample.movement().rawRotation().sub(defaultFrame.rawRotation(), new Vector3f()), new Vector3f()))
            : base;
        target.rawRotation().set(InterpolationUtil.lerp(previousValue, currentValue, alpha, new Vector3f()));
        MathUtil.toQuaternion(target.rawRotation(), target.rotation());
    }

    private @NotNull Vector3f scaleRatio(@NotNull Vector3f base, @NotNull Vector3f target, @NotNull Vector3f dest) {
        dest.x = Math.abs(base.x) <= MathUtil.FLOAT_COMPARISON_EPSILON ? target.x : target.x / base.x;
        dest.y = Math.abs(base.y) <= MathUtil.FLOAT_COMPARISON_EPSILON ? target.y : target.y / base.y;
        dest.z = Math.abs(base.z) <= MathUtil.FLOAT_COMPARISON_EPSILON ? target.z : target.z / base.z;
        return dest;
    }

    private @NotNull EnumSet<AnimationChannel> copyChannels(@NotNull EnumSet<AnimationChannel> channels) {
        return channels.isEmpty() ? EnumSet.noneOf(AnimationChannel.class) : EnumSet.copyOf(channels);
    }

    private record TickResult(boolean changed, boolean remove) {}

    private record SampledState(
        @NotNull BoneMovement movement,
        @NotNull EnumSet<AnimationChannel> channels,
        boolean skipInterpolation,
        boolean globalRotation,
        float frameDuration
    ) {}

    private enum SlotPhase {
        LERPIN,
        PLAY,
        LERPOUT
    }

    private final class StateAnimationSlot {
        private final String slotKey;
        private final int priority;
        private final BlueprintAnimation animation;
        private final @Nullable BlueprintAnimator animator;
        private final AnimationModifier modifier;
        private final AnimationIterator.Type type;
        private final boolean override;
        private final EnumSet<AnimationChannel> channels;
        private final boolean skipLastFrame;
        private final boolean emptyZero;
        private SlotPhase phase;
        private @Nullable SampledState previous;
        private float elapsedSeconds;
        private float phaseSeconds;

        private StateAnimationSlot(
            @NotNull String slotKey,
            int priority,
            @NotNull BlueprintAnimation animation,
            @Nullable BlueprintAnimator animator,
            @NotNull AnimationModifier modifier,
            @NotNull AnimationIterator.Type type,
            boolean override,
            @NotNull EnumSet<AnimationChannel> channels,
            boolean skipLastFrame,
            boolean emptyZero
        ) {
            this.slotKey = slotKey;
            this.priority = priority;
            this.animation = animation;
            this.animator = animator;
            this.modifier = modifier;
            this.type = type;
            this.override = override;
            this.channels = channels;
            this.skipLastFrame = skipLastFrame;
            this.emptyZero = emptyZero;
            this.phase = modifier.start() > 0 ? SlotPhase.LERPIN : SlotPhase.PLAY;
        }

        private @NotNull String slotKey() {
            return slotKey;
        }

        private int priority() {
            return priority;
        }

        private @NotNull BlueprintAnimation animation() {
            return animation;
        }

        private @NotNull AnimationModifier modifier() {
            return modifier;
        }

        private @NotNull AnimationIterator.Type type() {
            return type;
        }

        private boolean override() {
            return override;
        }

        private boolean skipLastFrame() {
            return skipLastFrame;
        }

        private boolean emptyZero() {
            return emptyZero;
        }

        private @NotNull SlotPhase phase() {
            return phase;
        }

        private float lerpInRatio() {
            if (modifier.start() <= 0) {
                return 1F;
            }
            return Math.clamp(phaseSeconds / (modifier.start() / 20F), 0F, 1F);
        }

        private float lerpOutRatio() {
            if (modifier.end() <= 0) {
                return 1F;
            }
            return Math.clamp(phaseSeconds / (modifier.end() / 20F), 0F, 1F);
        }

        private void capturePrevious(@NotNull SampledState previous) {
            this.previous = previous;
        }

        private @Nullable SampledState previous() {
            return previous;
        }

        private boolean beginLerpOut() {
            if (modifier.end() <= 0) {
                return false;
            }
            phase = SlotPhase.LERPOUT;
            phaseSeconds = 0F;
            previous = null;
            return true;
        }

        private @NotNull SampledState sampleCurrent() {
            var effectiveChannels = emptyZero ? EnumSet.allOf(AnimationChannel.class) : channels;
            return sampleAnimator(
                animator,
                effectiveChannels,
                elapsedSeconds,
                type,
                Math.max(modifier.speedValue(), MathUtil.FLOAT_COMPARISON_EPSILON)
            );
        }

        private @NotNull TickResult tick() {
            var changed = false;
            if (phase == SlotPhase.LERPOUT) {
                phaseSeconds += TRACKER_TICK_SECONDS;
                return new TickResult(true, phaseSeconds >= modifier.end() / 20F);
            }

            if (phase == SlotPhase.LERPIN) {
                phaseSeconds += TRACKER_TICK_SECONDS;
                changed = true;
                if (phaseSeconds >= modifier.start() / 20F) {
                    phase = SlotPhase.PLAY;
                    phaseSeconds = 0F;
                    previous = null;
                }
                return new TickResult(changed, false);
            }

            var speed = Math.max(modifier.speedValue(), MathUtil.FLOAT_COMPARISON_EPSILON);
            var previousElapsed = elapsedSeconds;
            switch (type) {
                case LOOP -> {
                    if (animation.length() > MathUtil.FLOAT_COMPARISON_EPSILON) {
                        var delta = TRACKER_TICK_SECONDS * speed;
                        var loopLength = animation.length() + (skipLastFrame ? 0F : delta);
                        elapsedSeconds = loopLength <= MathUtil.FLOAT_COMPARISON_EPSILON
                            ? 0F
                            : (elapsedSeconds + delta) % loopLength;
                        changed = true;
                    }
                }
                case HOLD_ON_LAST, PLAY_ONCE -> {
                    if (animation.length() > MathUtil.FLOAT_COMPARISON_EPSILON && elapsedSeconds < animation.length()) {
                        elapsedSeconds = Math.min(animation.length(), elapsedSeconds + TRACKER_TICK_SECONDS * speed);
                        changed = !MathUtil.isSimilar(previousElapsed, elapsedSeconds);
                    }
                }
            }

            if (type == AnimationIterator.Type.PLAY_ONCE
                && animation.length() > MathUtil.FLOAT_COMPARISON_EPSILON
                && elapsedSeconds >= animation.length() - MathUtil.FLOAT_COMPARISON_EPSILON) {
                if (modifier.end() > 0) {
                    phase = SlotPhase.LERPOUT;
                    phaseSeconds = 0F;
                    previous = null;
                    return new TickResult(true, false);
                }
                return new TickResult(true, true);
            }

            return new TickResult(changed, false);
        }
    }

    /**
     * Result flags from layer composition.
     *
     * @param skipInterpolation final skip-interpolation flag
     * @param globalRotation final global-rotation flag
     * @param frameDuration effective frame duration in seconds
     * @since 3.0.2
     */
    public record LayerComposition(
        boolean skipInterpolation,
        boolean globalRotation,
        float frameDuration
    ) {}
}
