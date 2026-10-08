package dev.goldenhead.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.goldenhead.kos.Kos;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.commands.arguments.PlayerListEntryArgumentType;
import meteordevelopment.meteorclient.systems.friends.Friend;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.command.CommandSource;

public class KosCommand extends Command {
    public KosCommand() {
        super("kos", "Manages the KOS list. Combat targets only these players while the list has anyone.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("add")
            .then(argument("player", PlayerListEntryArgumentType.create())
                .executes(context -> {
                    GameProfile profile = PlayerListEntryArgumentType.get(context).getProfile();
                    Friend friend = new Friend(profile.name(), profile.id());

                    if (!Kos.get().add(friend)) {
                        error("Already on the KOS list.");
                        return SINGLE_SUCCESS;
                    }

                    info("Added (highlight)%s(default) to KOS. Combat targets only this list.", friend.getName());
                    if (Friends.get().get(friend.getName()) != null) {
                        warning("%s is a friend, so they still won't be attacked.", friend.getName());
                    }
                    return SINGLE_SUCCESS;
                })
            )
        );

        builder.then(literal("remove")
            .then(argument("player", KosArgumentType.create())
                .executes(context -> {
                    Friend friend = KosArgumentType.get(context);
                    if (friend == null) {
                        error("That player is not on the KOS list.");
                        return SINGLE_SUCCESS;
                    }

                    if (Kos.get().remove(friend)) {
                        info("Removed (highlight)%s(default) from KOS.", friend.getName());
                        if (Kos.get().isEmpty()) info("KOS is empty, so combat targets everyone except friends again.");
                    }
                    else error("Failed to remove that player.");

                    return SINGLE_SUCCESS;
                })
            )
        );

        builder.then(literal("list").executes(context -> {
            info("--- KOS ((highlight)%s(default)) ---", Kos.get().count());
            Kos.get().forEach(friend -> ChatUtils.info("(highlight)%s".formatted(friend.getName())));
            if (Kos.get().isEmpty()) info("Empty. Combat targets everyone except friends.");
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("clear").executes(context -> {
            Kos.get().clear();
            info("Cleared KOS. Combat targets everyone except friends again.");
            return SINGLE_SUCCESS;
        }));
    }
}
