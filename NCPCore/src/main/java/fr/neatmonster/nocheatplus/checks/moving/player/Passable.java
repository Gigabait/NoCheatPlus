/*
 * This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package fr.neatmonster.nocheatplus.checks.moving.player;

import java.util.Locale;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import fr.neatmonster.nocheatplus.NCPAPIProvider;
import fr.neatmonster.nocheatplus.actions.ParameterName;
import fr.neatmonster.nocheatplus.checks.Check;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.ViolationData;
import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.compat.blocks.changetracker.BlockChangeTracker;
import fr.neatmonster.nocheatplus.utilities.collision.ICollidePassable;
import fr.neatmonster.nocheatplus.utilities.collision.PassableAxisTracing;
import fr.neatmonster.nocheatplus.utilities.collision.PassableRayTracing;
import fr.neatmonster.nocheatplus.utilities.location.LocUtil;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
import fr.neatmonster.nocheatplus.utilities.location.TrigUtil;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;

public class Passable extends Check {

    /** TESTING RATHER. */

    // TODO: Configuration.
    // TODO: Test cases.
    private static boolean rt_legacy = false;
    // TODO: Should keep an eye on passable vs. on-ground, when checking with reduced margins.
    // rt_xzFactor = 1.0; // Problems: Doors, fences. 
    private static double rt_xzFactor = 0.98;
    // rt_heightFactor = 1.0; // Since 10.2 (at some point) passable FP with 2-high ceiling.
    private static double rt_heightFactor = 0.99999999;

    /**
     * Convenience for player moving, to keep a better overview.
     * 
     * @param from
     * @param to
     * @return
     */
    public static boolean isPassable(Location from, Location to) {
        return rt_legacy ? BlockProperties.isPassable(from, to) : BlockProperties.isPassableAxisWise(from, to);
    }

    // TODO: Store both and select on check (with config then).
    private final ICollidePassable rayTracingLegacy = new PassableRayTracing();
    private final ICollidePassable rayTracingActual = new PassableAxisTracing();
    private final BlockChangeTracker blockTracker;

    public Passable() {
        super(CheckType.MOVING_PASSABLE);
        // TODO: Configurable maxSteps?
        rayTracingLegacy.setMaxSteps(60);
        rayTracingActual.setMaxSteps(60);
        blockTracker = NCPAPIProvider.getNoCheatPlusAPI().getBlockChangeTracker();
    }

    public Location check(final Player player, final PlayerLocation from, final PlayerLocation to, 
            final MovingData data, final MovingConfig cc, final int tick, final boolean useBlockChangeTracker) {
        if (rt_legacy) {
            return checkLegacy(player, from, to, data, cc);
        }
        else {
            return checkActual(player, from, to, data, cc, tick, useBlockChangeTracker);
        }
    }

    private Location checkActual(final Player player, final PlayerLocation from, final PlayerLocation to, 
            final MovingData data, final MovingConfig cc, final int tick, final boolean useBlockChangeTracker) {
        // TODO: Distinguish feet vs. box.

        // Block distances (sum, max) for from-to (not for loc!).
        final int manhattan = from.manhattan(to);

        // General condition check for using ray-tracing.
        if (cc.passableRayTracingCheck && (!cc.passableRayTracingBlockChangeOnly || manhattan > 0)) {
            final String newTag = checkRayTracing(player, from, to, manhattan, data, cc, tick, useBlockChangeTracker);
            if (newTag != null) {
                // Direct return.
                return potentialViolation(player, from, to, manhattan, newTag, data, cc);
            }
            // TODO: Return already here, if not colliding?
        }
        else if (!to.isPassable()) {
            // TODO: Only do ray tracing and remove this anyway?
            // TODO: Do make use of isPassableBox. 
            // TODO: Some cases seem not to be covered here (same block !?).
            return potentialViolationLegacy(player, from, to, manhattan, "", data, cc);
        }
        // No early return on violation happened.
        // (Might consider if vl>=1: only decrease if from and loc are passable too, though micro...)
        data.passableVL *= 0.99;
        return null;
    }

    private String checkRayTracing(final Player player, final PlayerLocation from, final PlayerLocation to,
            final int manhattan, final MovingData data, final MovingConfig cc, final int tick, final boolean useBlockChangeTracker) {
        String tags = null;
        setNormalMargins(rayTracingActual, from);
        rayTracingActual.set(from, to);
        rayTracingActual.setIgnoreInitiallyColliding(true);
        if (useBlockChangeTracker) { // TODO: Extra flag for 'any' block changes.
            rayTracingActual.setBlockChangeTracker(blockTracker, data.blockChangeRef, tick, from.getWorld().getUID());
        }
        //rayTracing.setCutOppositeDirectionMargin(true);
        rayTracingActual.loop();
        rayTracingActual.setIgnoreInitiallyColliding(false);
        //rayTracing.setCutOppositeDirectionMargin(false);
        if (rayTracingActual.collides()) {
            tags = "raytracing_collide_";
        }
        else if (rayTracingActual.getStepsDone() >= rayTracingActual.getMaxSteps()) {
            tags = "raytracing_maxsteps_";
        }
        if (data.debug) {
            debugExtraCollisionDetails(player, rayTracingActual, "std");
        }
        rayTracingActual.cleanup();
        return tags;
    }

    /**
     * Default/normal margins.
     * @param rayTracing
     * @param from
     */
    private void setNormalMargins(final ICollidePassable rayTracing, final PlayerLocation from) {
        rayTracing.setMargins(from.getBoxMarginVertical() * rt_heightFactor, from.getWidth() / 2.0 * rt_xzFactor); // max from/to + resolution ?
    }

    /**
     * Axis-wise ray-tracing violation skipping conditions.
     * 
     * @param player
     * @param from
     * @param to
     * @param manhattan
     * @param tags
     * @param data
     * @param cc
     * @return
     */
    private Location potentialViolation(final Player player, 
            final PlayerLocation from, final PlayerLocation to, final int manhattan, 
            String tags, final MovingData data, final MovingConfig cc) {

        // TODO: Might need the workaround for fences.

        return actualViolation(player, from, to, tags, data, cc);
    }

    private Location actualViolation(final Player player, final PlayerLocation from, final PlayerLocation to,
            final String tags, final MovingData data, final MovingConfig cc) {
        Location setBackLoc = null; // Alternative to from.getLocation().

        // Prefer the set back location from the data.
        if (data.hasSetBack()) {
            setBackLoc = data.getSetBack(to);
            if (data.debug) {
                debug(player, "Using set back location for passable.");
            }
        }

        // Return the reset position.
        data.passableVL += 1d;
        final ViolationData vd = new ViolationData(this, player, data.passableVL, 1, cc.passableActions);
        if (data.debug || vd.needsParameters()) {
            vd.setParameter(ParameterName.LOCATION_FROM, String.format(Locale.US, "%.2f, %.2f, %.2f", from.getX(), from.getY(), from.getZ()));
            vd.setParameter(ParameterName.LOCATION_TO, String.format(Locale.US, "%.2f, %.2f, %.2f", to.getX(), to.getY(), to.getZ()));
            vd.setParameter(ParameterName.DISTANCE, String.format(Locale.US, "%.2f", TrigUtil.distance(from, to)));
            // TODO: Consider adding from.getTypeId() too, if blocks differ and non-air.
            vd.setParameter(ParameterName.BLOCK_ID, "" + to.getTypeId());
            if (!tags.isEmpty()) {
                vd.setParameter(ParameterName.TAGS, tags);
            }
        }
        if (executeActions(vd).willCancel()) {
            // TODO: Consider another set back position for this, also keeping track of players moving around in blocks.
            final Location newTo;
            if (setBackLoc != null) {
                // Ensure the given location is cloned.
                newTo = LocUtil.clone(setBackLoc);
            } else {
                newTo = from.getLocation();
                if (data.debug) {
                    debug(player, "Using from location for passable.");
                }
            }
            newTo.setYaw(to.getYaw());
            newTo.setPitch(to.getPitch());
            return newTo;
        }
        else{
            // No cancel action set.
            return null;
        }
    }

    /**
     * Debug only if colliding.
     * 
     * @param player
     * @param rayTracing
     * @param tag
     */
    private void debugExtraCollisionDetails(Player player, ICollidePassable rayTracing, String tag) {
        if (rayTracing.collides()) {
            debug(player, "Raytracing collision (" + tag + "): " + rayTracing.getCollidingAxis());
        }
        else if (rayTracing.getStepsDone() >= rayTracing.getMaxSteps()) {
            debug(player, "Raytracing max steps exceeded (" + tag + "): "+ rayTracing.getCollidingAxis());
        }
        // TODO: Detect having used past block changes and log or set a tag.
    }

    private Location checkLegacy(final Player player, final PlayerLocation from, final PlayerLocation to, 
            final MovingData data, final MovingConfig cc) {
        // TODO: Distinguish feet vs. box.

        String tags = "";
        // Block distances (sum, max) for from-to (not for loc!).
        final int manhattan = from.manhattan(to);

        // Skip moves inside of ignored blocks right away [works as long as we only check between foot-locations].
        if (manhattan <= 1 && BlockProperties.isPassable(from.getTypeId())) {
            // TODO: Monitor: BlockProperties.isPassable checks slightly different than before.
            if (manhattan == 0){
                return null;
            } else {
                // manhattan == 1
                if (BlockProperties.isPassable(to.getTypeId())) {
                    return null;
                }
            }
        }

        boolean toPassable = to.isPassable();
        // General condition check for using ray-tracing.
        if (toPassable && cc.passableRayTracingCheck 
                && (!cc.passableRayTracingBlockChangeOnly || manhattan > 0)) {
            final String newTag = checkRayTracingLegacy(player, from, to, manhattan, data, cc);
            if (newTag != null) {
                toPassable = false;
                tags = newTag;
            }
        }

        // TODO: Checking order: If loc is not the same as from, a quick return here might not be wanted.
        if (toPassable) {
            // Quick return.
            // (Might consider if vl>=1: only decrease if from and loc are passable too, though micro...)
            data.passableVL *= 0.99;
            return null;
        } else {
            return potentialViolationLegacy(player, from, to, manhattan, tags, data, cc);
        }
    }

    private String checkRayTracingLegacy(final Player player, final PlayerLocation from, final PlayerLocation to,
            final int manhattan, final MovingData data, final MovingConfig cc) {
        setNormalMargins(rayTracingLegacy, from);
        rayTracingLegacy.set(from, to);
        rayTracingLegacy.loop();
        String tags = null;
        if (rayTracingLegacy.collides() || rayTracingLegacy.getStepsDone() >= rayTracingLegacy.getMaxSteps()) {
            if (data.debug) {
                debugExtraCollisionDetails(player, rayTracingLegacy, "legacy");
            }
            final int maxBlockDist = manhattan <= 1 ? manhattan : from.maxBlockDist(to);
            if (maxBlockDist <= 1 && rayTracingLegacy.getStepsDone() == 1 && !from.isPassable()) {
                // Redo ray-tracing for moving out of blocks.
                if (collidesIgnoreFirst(from, to)) {
                    tags = "raytracing_2x_";
                    if (data.debug) {
                        debugExtraCollisionDetails(player, rayTracingLegacy, "ingoreFirst");
                    }
                }
                else if (data.debug) {
                    debug(player, "Allow moving out of a block.");
                }
            }
            else{
                if (!allowsSplitMove(from, to, manhattan, data)) {
                    tags = "raytracing_";
                }
            }
        }
        // TODO: Future: If accuracy is demanded, also check the head position (or bounding box right away).
        rayTracingLegacy.cleanup();
        return tags;
    }

    /**
     * Legacy skipping conditions, before triggering an actual violation.
     * 
     * @param player
     * @param from
     * @param to
     * @param manhattan
     * @param tags
     * @param data
     * @param cc
     * @return
     */
    private Location potentialViolationLegacy(final Player player, 
            final PlayerLocation from, final PlayerLocation to, final int manhattan, 
            String tags, final MovingData data, final MovingConfig cc) {
        // Moving into a block, possibly a violation.
        // TODO: Do account for settings and ray-tracing here.

        // First check if the player is moving from a passable location.
        // If not, the move might still be allowed, if moving inside of the same block, or from and to have head position passable.
        if (from.isPassable()) {
            // Put one workaround for 1.5 high blocks here:
            if (from.isBlockAbove(to) && (BlockProperties.getBlockFlags(to.getTypeId()) & BlockProperties.F_HEIGHT150) != 0) {
                // Check if the move went from inside of the block.
                if (BlockProperties.collidesBlock(to.getBlockCache(), 
                        from.getX(), from.getY(), from.getZ(), 
                        from.getX(), from.getY(), from.getZ(), 
                        to.getBlockX(), to.getBlockY(), to.getBlockZ(), to.getOrCreateBlockCacheNode(), null,
                        BlockProperties.getBlockFlags(to.getTypeId()))) {
                    // Allow moving inside of 1.5 high blocks.
                    return null;
                }
            }
            // From should be the set back.
            tags += "into";
        }

        //				} else if (BlockProperties.isPassableExact(from.getBlockCache(), loc.getX(), loc.getY(), loc.getZ(), from.getTypeId(lbX, lbY, lbZ))) {
        // (Mind that this can be the case on the same block theoretically.)
        // Keep loc as set back.
        //				}
        else if (manhattan == 1 && to.isBlockAbove(from) 
                && BlockProperties.isPassable(from.getBlockCache(), from.getX(), from.getY() + from.getBoxMarginVertical(), from.getZ(), from.getBlockCache().getOrCreateBlockCacheNode(from.getBlockX(), Location.locToBlock(from.getY() + from.getBoxMarginVertical()), from.getBlockZ(), false), null)) {
            //				else if (to.isBlockAbove(from) && BlockProperties.isPassableExact(from.getBlockCache(), from.getX(), from.getY() + from.getBoxMarginVertical(), from.getZ(), from.getTypeId(from.getBlockX(), Location.locToBlock(from.getY() + from.getBoxMarginVertical()), from.getBlockZ()))) {
            // Allow the move up if the head is free.
            return null;
        }
        else if (manhattan > 0) {
            // Otherwise keep from as set back.
            tags += "cross";
        }
        else {
            // manhattan == 0
            // TODO: Even legacy ray-tracing will now account for actual initial collision.
            return null;
        }

        return actualViolation(player, from, to, tags, data, cc);
    }

    /**
     * Test collision with ignoring the first block.
     * @param from
     * @param to
     * @return
     */
    private boolean collidesIgnoreFirst(PlayerLocation from, PlayerLocation to) {
        rayTracingLegacy.set(from, to);
        rayTracingLegacy.setIgnoreInitiallyColliding(true);
        rayTracingLegacy.setCutOppositeDirectionMargin(true);
        rayTracingLegacy.loop();
        rayTracingLegacy.setIgnoreInitiallyColliding(false);
        rayTracingLegacy.setCutOppositeDirectionMargin(false);
        return rayTracingLegacy.collides() || rayTracingLegacy.getStepsDone() >= rayTracingLegacy.getMaxSteps();
    }

    /**
     * Test the move split into y-move and horizontal move, provided some pre-conditions are met.
     * @param from
     * @param to
     * @param manhattan
     * @return
     */
    private boolean allowsSplitMove(final PlayerLocation from, final PlayerLocation to, final int manhattan, final MovingData data) {
        if (!rayTracingLegacy.mightNeedSplitAxisHandling()) {
            return false;
        }
        // Always check y first.
        rayTracingLegacy.set(from.getX(), from.getY(), from.getZ(), from.getX(), to.getY(), from.getZ());
        rayTracingLegacy.loop();
        if (!rayTracingLegacy.collides() && rayTracingLegacy.getStepsDone() < rayTracingLegacy.getMaxSteps()) {
            // horizontal second.
            rayTracingLegacy.set(from.getX(), to.getY(), from.getZ(), to.getX(), to.getY(), to.getZ());
            rayTracingLegacy.loop();
            if (!rayTracingLegacy.collides() && rayTracingLegacy.getStepsDone() < rayTracingLegacy.getMaxSteps()) {
                return true;
            }
        }
        // Horizontal first may be obsolete, due to splitting moves anyway and due to not having been called ever (!). 
        //        final double yDiff = to.getY() - from.getY() ;
        //        if (manhattan <= 3 && Math.abs(yDiff)  < 1.0 && yDiff < 0.0) {
        //            // Workaround for client-side calculations not being possible (y vs. horizontal move). Typically stairs.
        //            // horizontal first.
        //            if (data.debug) {
        //                DebugUtil.debug(from.getPlayer().getName() + " passable - Test horizontal move first.");
        //            }
        //            rayTracingLegacy.set(from.getX(), from.getY(), from.getZ(), to.getX(), from.getY(), to.getZ());
        //            rayTracingLegacy.loop();
        //            if (!rayTracingLegacy.collides() && rayTracingLegacy.getStepsDone() < rayTracingLegacy.getMaxSteps()) {
        //                // y second.
        //                rayTracingLegacy.set(to.getX(), from.getY(), to.getZ(), to.getX(), to.getY(), to.getZ());
        //                rayTracingLegacy.loop();
        //                if (!rayTracingLegacy.collides() && rayTracingLegacy.getStepsDone() < rayTracingLegacy.getMaxSteps()) {
        //                    return true;
        //                }
        //            }
        //        }
        if (data.debug) {
            debug(from.getPlayer(), "Raytracing collision (split move): (no details)");
        }
        return false;
    }

}
