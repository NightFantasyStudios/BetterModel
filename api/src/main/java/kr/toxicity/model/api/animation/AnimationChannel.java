/*
 * This source file is part of BetterModel.
 * Copyright (c) 2024 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.api.animation;

/**
 * Identifies which transform channel an animation updates for a specific bone.
 * <p>
 * State-machine blending uses this information to decide whether a higher-priority
 * animation should replace or preserve the lower-priority pose for position,
 * rotation, and scale independently.
 * </p>
 *
 * @since 3.0.2
 */
public enum AnimationChannel {
    /**
     * Translation channel.
     *
     * @since 3.0.2
     */
    POSITION,
    /**
     * Rotation channel.
     *
     * @since 3.0.2
     */
    ROTATION,
    /**
     * Scale channel.
     *
     * @since 3.0.2
     */
    SCALE
}
