package io.github.restioson.siege.game;

import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import xyz.nucleoid.plasmid.game.GameSpace;
import xyz.nucleoid.plasmid.game.player.GameTeam;
import xyz.nucleoid.plasmid.util.PlayerRef;
import xyz.nucleoid.plasmid.util.Scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

public final class SiegeCaptureLogic {
    public static final int CAPTURE_TIME_TICKS = 20 * 10;

    private final GameSpace gameSpace;
    private final SiegeActive game;

    private final List<ServerPlayerEntity> defendersPresent = new ArrayList<>();
    private final List<ServerPlayerEntity> attackersPresent = new ArrayList<>();

    SiegeCaptureLogic(SiegeActive game) {
        this.gameSpace = game.gameSpace;
        this.game = game;
    }

    void tick(ServerWorld world, int interval) {
        for (SiegeFlag flag : this.game.map.flags) {
            this.tickCaptureFlag(world, flag, interval);
        }
    }

    private void tickCaptureFlag(ServerWorld world, SiegeFlag flag, int interval) {
        List<ServerPlayerEntity> defendersPresent = this.defendersPresent;
        List<ServerPlayerEntity> attackersPresent = this.attackersPresent;

        defendersPresent.clear();
        attackersPresent.clear();

        for (Object2ObjectMap.Entry<PlayerRef, SiegePlayer> entry : Object2ObjectMaps.fastIterable(this.game.participants)) {
            ServerPlayerEntity player = entry.getKey().getEntity(world);
            if (player == null) {
                continue;
            }

            SiegePlayer siegePlayer = entry.getValue();

            if (flag.bounds.contains(player.getBlockPos())) {
                GameTeam team = siegePlayer.team;
                if (team == SiegeTeams.DEFENDERS) {
                    defendersPresent.add(player);
                } else if (team == SiegeTeams.ATTACKERS) {
                    attackersPresent.add(player);
                }
            }
        }

        boolean defendersCapturing = !defendersPresent.isEmpty();
        boolean attackersCapturing = !attackersPresent.isEmpty();
        boolean contested = defendersCapturing && attackersCapturing;
        boolean capturing = defendersCapturing || attackersCapturing;

        CapturingState capturingState = null;
        GameTeam captureTeam = flag.team;
        List<ServerPlayerEntity> capturingPlayers = ImmutableList.of();

        if (capturing) {
            if (!contested) {
                if (defendersCapturing) {
                    captureTeam = SiegeTeams.DEFENDERS;
                    capturingPlayers = defendersPresent;
                } else {
                    captureTeam = SiegeTeams.ATTACKERS;
                    capturingPlayers = attackersPresent;
                }

                capturingState = captureTeam != flag.team ? CapturingState.CAPTURING : null;
            } else {
                capturingState = CapturingState.CONTESTED;
            }
        } else {
            if (flag.captureProgressTicks > 0) {
                capturingState = CapturingState.SECURING;
            }
        }

        flag.capturingState = capturingState;

        if (capturingState == CapturingState.CAPTURING) {
            this.tickCapturing(flag, interval, captureTeam, capturingPlayers);
        } else if (capturingState == CapturingState.SECURING) {
            this.tickSecuring(flag, interval);
        }
    }

    private void tickCapturing(SiegeFlag flag, int interval, GameTeam captureTeam, List<ServerPlayerEntity> capturingPlayers) {
        // Just began capturing
        if (flag.captureProgressTicks == 0) {
            this.broadcastStartCapture(flag, captureTeam);
        }

        if (flag.incrementCapture(captureTeam, interval * capturingPlayers.size())) {
            this.broadcastCaptured(flag, captureTeam);

            for (ServerPlayerEntity player : capturingPlayers) {
                player.sendMessage(new LiteralText("Captured!").formatted(Formatting.AQUA), true);
            }
        } else {
            int capturePercent = flag.captureProgressTicks * 100 / CAPTURE_TIME_TICKS;
            for (ServerPlayerEntity player : capturingPlayers) {
                player.sendMessage(new LiteralText("Capturing: " + capturePercent + "%").formatted(Formatting.RED), true);
            }
        }
    }

    private void tickSecuring(SiegeFlag flag, int interval) {
        flag.captureProgressTicks -= interval;

        if (flag.decrementCapture(interval)) {
            this.broadcastSecured(flag);
        }
    }

    private void broadcastStartCapture(SiegeFlag flag, GameTeam captureTeam) {
        this.gameSpace.getPlayers().sendMessage(
                new LiteralText("The ")
                        .append(new LiteralText(flag.name).formatted(Formatting.YELLOW))
                        .append(" is being captured by the ")
                        .append(new LiteralText(captureTeam.getDisplay()).formatted(captureTeam.getFormatting()))
                        .append("...")
                        .formatted(Formatting.BOLD)
        );

        this.gameSpace.getPlayers().sendSound(SoundEvents.BLOCK_BELL_USE);

        for (Object2ObjectMap.Entry<PlayerRef, SiegePlayer> entry : Object2ObjectMaps.fastIterable(this.game.participants)) {
            if (entry.getValue().team == captureTeam) {
                continue;
            }

            entry.getKey().ifOnline(
                    this.gameSpace.getWorld(),
                    player -> {
                        AtomicInteger plays = new AtomicInteger();
                        Scheduler.INSTANCE.repeatWhile(
                                s -> player.playSound(SoundEvents.BLOCK_BELL_USE, SoundCategory.PLAYERS, 1.0f, 1.0f),
                                t -> plays.incrementAndGet() < 3,
                                0,
                                7
                        );
                    }
            );
        }
    }

    private void broadcastCaptured(SiegeFlag flag, GameTeam captureTeam) {
        this.gameSpace.getPlayers().sendMessage(
                new LiteralText("The ")
                        .append(new LiteralText(flag.name).formatted(Formatting.YELLOW))
                        .append(" has been captured by the ")
                        .append(new LiteralText(captureTeam.getDisplay()).formatted(captureTeam.getFormatting()))
                        .append("!")
                        .formatted(Formatting.BOLD)
        );

        ServerWorld world = this.gameSpace.getWorld();

        Vec3d pos = SiegeSpawnLogic.choosePos(world.getRandom(), flag.bounds);
        LightningEntity lightningEntity = EntityType.LIGHTNING_BOLT.create(world);
        Objects.requireNonNull(lightningEntity).refreshPositionAfterTeleport(pos);
        lightningEntity.setCosmetic(true);
        world.spawnEntity(lightningEntity);
    }

    private void broadcastSecured(SiegeFlag flag) {
        this.gameSpace.getPlayers().sendMessage(
                new LiteralText("The ")
                        .append(new LiteralText(flag.name).formatted(Formatting.YELLOW))
                        .append(" has been defended by the ")
                        .append(new LiteralText(flag.team.getDisplay()).formatted(flag.team.getFormatting()))
                        .append("!")
                        .formatted(Formatting.BOLD)
        );
    }
}
