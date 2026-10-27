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

    static void checkCoreStatus(Minecraft client) {
        var original = Language.getInstance();
        try {
            for (var locale : List.of("en_us", "zh_cn")) {
                Language.inject(ClientLanguage.loadFrom(client.getResourceManager(), List.of("en_us", locale), false));
                for (var phase : List.of("warden_phase_1", "warden_phase_2", "warden_phase_3", "warden_exposure")) {
                    for (var cooling : List.of("core_cooling_ready", "core_cooling_recharging", "core_cooling_queued")) {
                        var title = net.minecraft.network.chat.Component.translatable("message.aeronautics_innerworld.warden_status",
                                net.minecraft.network.chat.Component.translatable("entity.aeronautics_innerworld.core_warden"),
                                net.minecraft.network.chat.Component.translatable("message.aeronautics_innerworld." + phase,8))
                                .append(" · ").append(net.minecraft.network.chat.Component.translatable("message.aeronautics_innerworld." + cooling,15));
                        if (client.font.width(title) > 300) throw new IllegalStateException("Core status exceeds narrow GUI width: " + locale + " / " + title.getString());
                    }
                }
            }
        } finally {Language.inject(original);}
    }

    private BookLayoutChecks() {
    }
}
