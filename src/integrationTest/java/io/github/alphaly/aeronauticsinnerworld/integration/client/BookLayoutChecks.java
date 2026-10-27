package io.github.alphaly.aeronauticsinnerworld.integration.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.core.component.DataComponents;
import net.minecraft.locale.Language;
import net.minecraft.world.item.ItemStack;

final class BookLayoutChecks {
    static void check(Minecraft client, ItemStack book) {
        var content = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null || content.pages().isEmpty()) {
            throw new IllegalStateException("Expedition book has no usable pages");
        }
        var original = Language.getInstance();
        var oversized = new ArrayList<String>();
        try {
            for (var locale : List.of("en_us", "zh_cn")) {
                Language.inject(ClientLanguage.loadFrom(client.getResourceManager(), List.of("en_us", locale), false));
                for (int i = 0; i < content.pages().size(); i++) {
                    int height = client.font.wordWrapHeight(content.pages().get(i).raw(), 114);
                    if (height > 128) {
                        oversized.add(locale + " page " + (i + 1) + " height=" + height);
                    }
                }
                var engineGuide = net.minecraft.network.chat.Component.translatable("book.aeronautics_innerworld.heat_engine.guide")
                        .getString().replace("$(br2)", "\n\n");
                if (client.font.wordWrapHeight(engineGuide,114)>128) oversized.add(locale+" heat engine guide");
            }
        } finally {
            Language.inject(original);
        }
        if (!oversized.isEmpty()) {
            throw new IllegalStateException("Text exceeds native book page: " + oversized);
        }
    }

    private BookLayoutChecks() {
    }
}
