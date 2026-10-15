// SPDX-License-Identifier: GPL-3.0-or-later
package com.multiverse.programming.peripheral;

import org.bukkit.Instrument;
import org.bukkit.Location;
import org.bukkit.Note;
import org.bukkit.SoundCategory;
import org.bukkit.plugin.java.JavaPlugin;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.Locale;

/**
 * Peripheral that allows playing musical notes, tones, and Minecraft sound effects.
 */
public final class SpeakerPeripheral implements Peripheral {

    private final JavaPlugin plugin;
    private final Location location;

    public SpeakerPeripheral(JavaPlugin plugin, Location location) {
        this.plugin = plugin;
        this.location = location;
    }

    @Override
    public String getType() {
        return "speaker";
    }

    @Override
    public Location getLocation() {
        return location;
    }

    static final float MAX_VOLUME = 3.0f;
    // The note block pling at pitch 1.0 sounds F#4 (~370 Hz), not A4.
    static final double PITCH_ONE_HZ = 369.994;

    static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Converts a frequency to a sound pitch; playable range is 185-740 Hz (pitch 0.5-2.0). */
    static float toneToPitch(double freqHz) {
        return clamp((float) (freqHz / PITCH_ONE_HZ), 0.5f, 2.0f);
    }

    private static Instrument parseInstrument(String name) {
        if (name == null) {
            return Instrument.PIANO;
        }
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "piano", "harp" -> Instrument.PIANO;
            case "bass", "bass_drum", "bassdrum" -> Instrument.BASS_DRUM;
            case "snare", "snare_drum" -> Instrument.SNARE_DRUM;
            case "sticks", "hat", "click" -> Instrument.STICKS;
            case "bass_guitar", "bassguitar" -> Instrument.BASS_GUITAR;
            case "flute" -> Instrument.FLUTE;
            case "bell" -> Instrument.BELL;
            case "guitar" -> Instrument.GUITAR;
            case "chime" -> Instrument.CHIME;
            case "xylophone" -> Instrument.XYLOPHONE;
            case "iron_xylophone", "iron" -> Instrument.IRON_XYLOPHONE;
            case "cow_bell", "cowbell" -> Instrument.COW_BELL;
            case "didgeridoo" -> Instrument.DIDGERIDOO;
            case "bit" -> Instrument.BIT;
            case "banjo" -> Instrument.BANJO;
            case "pling" -> Instrument.PLING;
            default -> {
                try {
                    yield Instrument.valueOf(name.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    yield Instrument.PIANO;
                }
            }
        };
    }

    @Override
    public LuaValue toLuaTable() {
        LuaTable table = new LuaTable();

        // speaker.playNote(instrument, note) -> note is integer 0-24
        table.set("playNote", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue arg1, LuaValue arg2) {
                Instrument inst = parseInstrument(arg1.tojstring());
                int noteVal = Math.max(0, Math.min(24, arg2.checkint()));
                SyncDispatcher.syncVoid(plugin, () -> {
                    if (location.getWorld() != null) {
                        location.getWorld().playNote(location, inst, new Note(noteVal));
                    }
                });
                return LuaValue.NONE;
            }
        });

        // speaker.playSound(soundName, [volume], [pitch])
        table.set("playSound", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String soundName = args.checkjstring(1);
                // Volume scales hearing range (~16 blocks per 1.0): cap it so one speaker cannot flood the area.
                float volume = clamp(args.narg() >= 2 ? (float) args.optdouble(2, 1.0) : 1.0f, 0.0f, MAX_VOLUME);
                float pitch = clamp(args.narg() >= 3 ? (float) args.optdouble(3, 1.0) : 1.0f, 0.5f, 2.0f);

                SyncDispatcher.syncVoid(plugin, () -> {
                    if (location.getWorld() != null) {
                        location.getWorld().playSound(location, soundName, SoundCategory.BLOCKS, volume, pitch);
                    }
                });
                return LuaValue.NONE;
            }
        });

        // speaker.playTone(frequencyHz, [durationMs]) -> converts Hz to note pitch
        table.set("playTone", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                double freq = args.checkdouble(1);
                float pitch = toneToPitch(freq);
                SyncDispatcher.syncVoid(plugin, () -> {
                    if (location.getWorld() != null) {
                        location.getWorld().playSound(location, "block.note_block.pling", SoundCategory.BLOCKS, 1.0f, pitch);
                    }
                });
                return LuaValue.NONE;
            }
        });

        return table;
    }
}
