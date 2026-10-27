package io.github.alphaly.aeronauticsinnerworld.content.probe;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity;
import com.simibubi.create.content.trains.display.FlapDisplayLayout;
import com.simibubi.create.content.trains.display.FlapDisplaySection;
import java.util.List;
import net.minecraft.network.chat.MutableComponent;

public final class ProbeDisplaySource extends DisplaySource {
    private final EchoSounderBlockEntity.Readout page;

    public ProbeDisplaySource(EchoSounderBlockEntity.Readout page) {
        this.page = page;
    }

    @Override
    public List<MutableComponent> provideText(DisplayLinkContext context, DisplayTargetStats stats) {
        return context.getSourceBlockEntity() instanceof EchoSounderBlockEntity sounder
                ? sounder.getReadout(page) : EMPTY;
    }

    @Override
    public int getPassiveRefreshTicks() {
        return 20;
    }

    @Override
    public void loadFlapDisplayLayout(DisplayLinkContext context, FlapDisplayBlockEntity display, FlapDisplayLayout layout) {
        String id = "InnerworldProbe";
        if (!layout.isLayout(id)) {
            // Whole-line flaps preserve translated text and variable-width glyphs.
            layout.configure(id, List.of(new FlapDisplaySection(display.getMaxCharCount() * FlapDisplaySection.MONOSPACE,
                    "alphabet", true, false)));
        }
    }
}
