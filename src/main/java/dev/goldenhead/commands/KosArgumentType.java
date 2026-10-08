package dev.goldenhead.commands;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.goldenhead.kos.Kos;
import meteordevelopment.meteorclient.systems.friends.Friend;
import net.minecraft.command.CommandSource;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.StreamSupport;

public class KosArgumentType implements ArgumentType<String> {
    private static final KosArgumentType INSTANCE = new KosArgumentType();
    private static final Collection<String> EXAMPLES = List.of("Notch", "Player");

    public static KosArgumentType create() {
        return INSTANCE;
    }

    public static Friend get(CommandContext<?> context) {
        return Kos.get().get(context.getArgument("player", String.class));
    }

    private KosArgumentType() {}

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        return reader.readString();
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        return CommandSource.suggestMatching(StreamSupport.stream(Kos.get().spliterator(), false).map(Friend::getName), builder);
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}
