// SPDX-License-Identifier: GPL-3.0-or-later
package com.multiverse.programming.peripheral;

import com.multiverse.programming.BukkitMockHelper;
import org.bukkit.Instrument;
import org.bukkit.Location;
import org.bukkit.Note;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaValue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpeakerPeripheralTest {

    private JavaPlugin plugin;
    private Location location;
    private World world;
    private SpeakerPeripheral speaker;

    @BeforeEach
    void setUp() {
        BukkitMockHelper.setUpMockServer();
        plugin = mock(JavaPlugin.class);
        when(plugin.isEnabled()).thenReturn(true);

        world = mock(World.class);
        location = new Location(world, 0, 64, 0);

        speaker = new SpeakerPeripheral(plugin, location);
    }

    @AfterEach
    void tearDown() {
        BukkitMockHelper.tearDownMockServer();
    }

    @Test
    @DisplayName("Speaker reports type as speaker")
    void testType() {
        assertEquals("speaker", speaker.getType());
    }

    @Test
    @DisplayName("playNote plays note on world")
    void testPlayNote() {
        LuaValue table = speaker.toLuaTable();
        table.get("playNote").call(LuaValue.valueOf("harp"), LuaValue.valueOf(12));

        verify(world).playNote(eq(location), eq(Instrument.PIANO), any(Note.class));
    }

    @Test
    @DisplayName("playTone plays pling sound with converted pitch")
    void testPlayTone() {
        LuaValue table = speaker.toLuaTable();
        // F#4 is the pling's native pitch (1.0)
        table.get("playTone").call(LuaValue.valueOf(SpeakerPeripheral.PITCH_ONE_HZ));

        verify(world).playSound(eq(location), eq("block.note_block.pling"), any(), eq(1.0f), eq(1.0f));
    }

    @Test
    @DisplayName("Speaker clamps volume and tunes tones to the pling pitch")
    void testClampsAndTuning() {
        assertEquals(SpeakerPeripheral.MAX_VOLUME, SpeakerPeripheral.clamp(1_000_000f, 0f, SpeakerPeripheral.MAX_VOLUME));
        assertEquals(1.0f, SpeakerPeripheral.toneToPitch(369.994), 0.001f);
        assertEquals(2.0f, SpeakerPeripheral.toneToPitch(740.0), 0.01f);
        assertEquals(0.5f, SpeakerPeripheral.toneToPitch(20.0), 0.001f);
    }
}
