package com.github.calebwhiting.runelite.plugins.actionprogress.detect;

import com.github.calebwhiting.runelite.api.event.LocalAnimationChanged;
import com.github.calebwhiting.runelite.plugins.actionprogress.Action;
import com.github.calebwhiting.runelite.plugins.actionprogress.ActionProgressPlugin;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.eventbus.Subscribe;

@Slf4j
@Singleton
public class SandpitDetector extends ActionDetector {

    @Inject private Client client;
    @Inject private ActionProgressPlugin plugin;

    @Subscribe
    public void onLocalAnimationChanged(LocalAnimationChanged evt) {
        Player me = evt.getLocalPlayer();
        if (me.getAnimation() != AnimationID.SAND_COLLECTION) {
            return;
        }
        if (this.actionManager.getCurrentAction() == Action.COLLECT_SAND) {
            return;
        }
        ItemContainer inventory = this.client.getItemContainer(InventoryID.INVENTORY);
        if (inventory == null) {
            return;
        }
        int buckets = inventory.count(ItemID.BUCKET);
        this.actionManager.setAction(Action.COLLECT_SAND, buckets, ItemID.BUCKET_OF_SAND);
    }

}
