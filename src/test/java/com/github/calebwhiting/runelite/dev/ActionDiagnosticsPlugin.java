package com.github.calebwhiting.runelite.dev;

import com.github.calebwhiting.runelite.api.event.DestinationChanged;
import com.github.calebwhiting.runelite.api.event.LocalRegionChanged;
import com.github.calebwhiting.runelite.data.IDQuery;
import com.github.calebwhiting.runelite.plugins.actionprogress.ActionProgressPlugin;
import com.google.inject.Inject;
import com.google.inject.Provides;
import com.jogamp.common.util.IntObjectHashMap;
import net.runelite.api.*;
import net.runelite.api.annotations.Varbit;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import org.apache.commons.text.WordUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@PluginDescriptor(
        name = "Action Diagnostics",
        description = "A tool to find action triggers.",
        developerPlugin = true,
        enabledByDefault = false
)
@PluginDependency(ActionProgressPlugin.class)
public class ActionDiagnosticsPlugin extends Plugin {

    private static final Logger log = LoggerFactory.getLogger(ActionDiagnosticsPlugin.class);

    private LinkedList<HistoricEvent> history;

    @Inject private Client client;

    @Inject private ActionDiagnosticsConfig config;

    private IDQuery clientScriptsQuery;

    private int lastTriggerTick = 0;

    private int[] pClientVars;
    private IntObjectHashMap inventoryMap;

    @Override
    protected void startUp() {
        IDQuery.ofScripts();
        this.history = new LinkedList<>();
        this.clientScriptsQuery = new IDQuery(ClientScriptID.class);
        this.pClientVars = this.client.getVarps().clone();
        this.inventoryMap = new IntObjectHashMap();
        for (InventoryID iid : InventoryID.values()) {
            ItemContainer container = client.getItemContainer(iid);
            if (container == null) {
                continue;
            }
            Item[] curr = new Item[container.size()];
            for (int i = 0; i < container.size(); i++) {
                curr[i] = container.getItem(i);
            }
            inventoryMap.put(iid.getId(), curr);
        }
    }

    @Override
    protected void shutDown() throws Exception {
        super.shutDown();
        this.history.clear();
        this.history = null;
        this.clientScriptsQuery = null;
        this.pClientVars = null;
        this.inventoryMap.clear();
    }

    @Provides
    ActionDiagnosticsConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(ActionDiagnosticsConfig.class);
    }

    private void push(String event) {
        if (history.size() >= config.capacity()) {
            history.remove();
        }
        HistoricEvent historic = new HistoricEvent(client.getTickCount(), event);
        history.add(historic);
    }

    private void push(String eventId, Object... args) {
        if (args.length % 2 != 0) {
            throw new IllegalArgumentException();
        }
        StringBuilder data = new StringBuilder();
        for (int i = 0; i < args.length; i += 2) {
            if (i > 0) {
                data.append(", ");
            }
            String s;
            Object key = args[i];
            Object value = args[i + 1];
            switch (value == null ? "" : value.getClass().getSimpleName()) {
                case "LocalPoint": {
                    LocalPoint localPoint = (LocalPoint) value;
                    s = String.format("(%d, %d)", localPoint.getX(), localPoint.getY());
                    break;
                }
                case "WorldPoint": {
                    WorldPoint worldPoint = (WorldPoint) value;
                    s = String.format("(%d, %d, %d)", worldPoint.getX(), worldPoint.getY(), worldPoint.getPlane());
                    break;
                }
                default:
                    s = String.valueOf(value);
                    break;
            }
            data.append(key).append("=\"").append(s).append("\"");
        }
        push(String.format("%-20s %s", eventId, data));
    }

    private void trigger() {
        int tick = client.getTickCount();
        int targetTick = tick - config.ticks();
        List<HistoricEvent> events = new LinkedList<>(this.history);
        Map<Object, Integer> eventCounts = new LinkedHashMap<>();
        events.stream().filter(Objects::nonNull).forEach(event -> {
            if (event.getTick() == targetTick) {
                int previous = eventCounts.getOrDefault(event.getEvent(), 0);
                eventCounts.put(event.getEvent(), previous + 1);
            }
        });
        System.out.println("[" + tick + "] triggered");
        eventCounts.keySet().forEach(event -> {
            boolean unique = true;
            for (HistoricEvent e : events) {
                if (e.getTick() != targetTick && e.getTick() > lastTriggerTick) {
                    if (Objects.equals(event, e.getEvent())) {
                        unique = false;
                        break;
                    }
                }
            }
            if (!unique && config.onlyUniqueEvents()) {
                return;
            }
            System.out.println("[" + tick + "] " + (unique ? "[*]" : "   ") + "[" + eventCounts.get(event) + "] " + event);
        });
        this.lastTriggerTick = tick;
    }

    @Subscribe
    public void onDestinationChanged(DestinationChanged evt) {
        push("destination-change",
                "from", evt.getFrom(),
                "to", evt.getTo()
        );
    }

    @Subscribe
    public void onLocalRegionChanged(LocalRegionChanged evt) {
        push("region-changed", "from", evt.getFrom(), "to", evt.getTo());
    }

    @Subscribe
    public void onAnimationChanged(AnimationChanged evt) {
        Actor actor = evt.getActor();
        Player local = client.getLocalPlayer();
        String animation = IDQuery.ofAnimations().getNameString(actor.getAnimation());
        animation = animation == null ? String.valueOf(actor.getAnimation()) : animation;
        if (local != null && evt.getActor() == local) {
            push(
                    "animation",
                    "src", "self",
                    "id", animation
            );
        } else if (local instanceof NPC) {
            NPC npc = (NPC) actor;
            String name = IDQuery.ofNPCs().getNameString(npc.getId());
            name = name == null ? String.valueOf(npc.getId()) : name;
            push(
                    "animation",
                    "src", "NPC:" + name,
                    "id", animation
            );
        }
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged evt) {
        @Varbit int index = evt.getIndex();
        if (client.getVarps()[index] == this.pClientVars[index]) {
            return;
        }
        String name = IDQuery.ofVarbits().getNameString(index);
        name = name == null ? String.valueOf(index) : name;
        push(
                "varbit",
                "index", name,
                "from", this.pClientVars[index],
                "to", client.getVarbitValue(index)
        );
        this.pClientVars[index] = client.getVarps()[index];
    }

    @Subscribe
    public void onScriptCallbackEvent(ScriptCallbackEvent evt) {
        Script script = evt.getScript();
        String instructions = script.getInstructions() == null ? "[]" :
                IntStream.of(script.getInstructions())
                        .mapToObj(String::valueOf)
                        .collect(Collectors.joining(", ", "[", "]"));
        String operands = script.getIntOperands() == null ? "[]" :
                IntStream.of(script.getIntOperands())
                        .mapToObj(String::valueOf)
                        .collect(Collectors.joining(", ", "[", "]"));
        push(
                "script-callback",
                "name", evt.getEventName(),
                "instructions", instructions,
                "operands", operands
        );
    }

    @Subscribe
    public void onScriptPreFired(ScriptPreFired evt) {
        String name = clientScriptsQuery.getNameString(evt.getScriptId());
        name = name == null ? String.valueOf(evt.getScriptId()) : name;
        ScriptEvent se = evt.getScriptEvent();
        if (se != null) {
            Object[] args = se.getArguments();
            String argsString = Stream.of(args)
                    .skip(1)
                    .map(String::valueOf)
                    .collect(Collectors.joining(", ", "[", "]"));
            push(
                    "script-fire",
                    "id", name,
                    "src", se.getSource() == null ? -1 : se.getSource().getId(),
                    "args", argsString,
                    "op", se.getOp(),
                    "op-base", se.getOpbase(),
                    "mouse", String.format("(%d, %d)", se.getMouseX(), se.getMouseY()),
                    "typed", se.getTypedKeyCode()
            );
        } else {
            push("script-begin", "id", name);
        }
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired evt) {
        String name = clientScriptsQuery.getNameString(evt.getScriptId());
        name = name == null ? String.valueOf(evt.getScriptId()) : name;
        push("script-end", "id", name);
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed evt) {
        push(
                "widget-closed",
                "group", evt.getGroupId(),
                "mode", evt.getModalMode(),
                "unload", evt.isUnload()
        );
    }

    @Subscribe
    public void onChatMessage(ChatMessage evt) {
        push(
                "chat-received",
                "type", WordUtils.capitalizeFully(evt.getType().name().replace('_', ' ')),
                "message", evt.getMessage()
        );
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked evt) {
        push(
                "menu-option",
                "0", evt.getParam0(),
                "1", evt.getParam1(),
                "opt", evt.getMenuOption(),
                "target", evt.getMenuTarget(),
                "action", evt.getMenuAction(),
                "id", evt.getId(),
                "selected-index", client.getSelectedItemIndex()
        );
    }

    @Subscribe
    public void onAreaSoundEffectPlayed(AreaSoundEffectPlayed evt) {
        push(
                "area-sound-effect",
                "src", evt.getSource() == null ? "null" : evt.getSource().getName(),
                "id", evt.getSoundId(),
                "pos", String.format("(%d, %d)", evt.getSceneX(), evt.getSceneY()),
                "range", evt.getRange(),
                "delay", evt.getDelay()
        );
    }

    @Subscribe
    public void onStatChanged(StatChanged evt) {
        push(
                "stat-changed",
                "skill", evt.getSkill(),
                "xp", evt.getXp(),
                "level", evt.getLevel(),
                "boost", evt.getBoostedLevel()
        );
        if (config.trigger() == Trigger.EXPERIENCE) {
            Skill triggerSkill = config.experienceTriggerSkill();
            int triggerSize = config.experienceTriggerSize();
            if (triggerSkill != Skill.OVERALL && triggerSkill != evt.getSkill()) {
                return;
            }
            if (triggerSize != 0 && evt.getXp() != triggerSize) {
                return;
            }
            trigger();
        }
    }

    @Subscribe
    public void onSoundEffectPlayed(SoundEffectPlayed evt) {
        push(
                "sound-effect",
                "src", evt.getSource() == null ? "null" : evt.getSource().getName(),
                "id", evt.getSoundId(),
                "delay", evt.getDelay()
        );
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded evt) {
        push("widget-loaded", "group", evt.getGroupId());
    }

    @Subscribe
    public void onVarClientIntChanged(VarClientIntChanged evt) {
        @Varbit int index = evt.getIndex();
        if (client.getVarps()[index] == this.pClientVars[index]) {
            return;
        }
        String name = IDQuery.ofVarbits().getNameString(index);
        name = name == null ? String.valueOf(index) : name;
        push(
                "int-var",
                "index", name,
                "value", client.getVarcIntValue(index)
        );
        this.pClientVars[index] = client.getVarps()[index];
    }

    @Subscribe
    public void onVarClientStrChanged(VarClientStrChanged evt) {
        @Varbit int index = evt.getIndex();
        String name = IDQuery.ofVarbits().getNameString(index);
        name = name == null ? String.valueOf(index) : name;
        push(
                "str-var",
                "id", name,
                "value", client.getVarcStrValue(index)
        );
    }

    private static String getItemName(int id) {
        String name = IDQuery.ofItems().getNameString(id);
        return name == null ? String.valueOf(id) : name;
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged evt) {
        ItemContainer container = evt.getItemContainer();
        push("item-change", "container", container.getId());
        Item[] prev = (Item[]) this.inventoryMap.get(container.getId());
        Item[] curr = new Item[container.size()];
        for (int i = 0; i < container.size(); i++) {
            curr[i] = container.getItem(i);
        }
        if (prev != null) {
            for (int i = 0; i < container.size(); i++) {
                Item a = i < prev.length ? prev[i] : null;
                Item b = i < curr.length ? curr[i] : null;
                if (!Objects.equals(a, b)) {
                    push("item-change",
                            "container", evt.getContainerId(),
                            "slot", i,
                            "from-x", a == null ? 0 : a.getQuantity(),
                            "from", a == null ? "null" : getItemName(a.getId()),
                            "to-x", b == null ? 0 : b.getQuantity(),
                            "to", b == null ? "null" : getItemName(b.getId())
                    );
                }
            }
        }
        this.inventoryMap.put(container.getId(), curr);
    }

}
