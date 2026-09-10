package me.shiny.matesignal;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.client.IModGuiFactory;

import java.util.Collections;
import java.util.Set;

/**
 * Hooks MateSignal's config screen into the 1.12.2 mod list ("Config" button).
 * Declared through {@code guiFactory} in the {@code @Mod} annotation.
 */
public class MateSignalGuiFactory implements IModGuiFactory {

    @Override
    public void initialize(Minecraft minecraftInstance) {
        // No runtime options to bind.
    }

    @Override
    public boolean hasConfigGui() {
        return true;
    }

    @Override
    public GuiScreen createConfigGui(GuiScreen parentScreen) {
        return new MateSignalConfigScreen(parentScreen);
    }

    @Override
    public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() {
        return Collections.emptySet();
    }
}
