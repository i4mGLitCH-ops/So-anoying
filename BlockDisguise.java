package dev.eventserver.blockdisguise;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.*;
import com.comphenix.protocol.wrappers.MultiBlockChangeInfo;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Non-op players see every full, non-interactive block as a single replacement block (default STONE).
 * Only the packets sent to those players are changed; the real world is untouched.
 * /blockdisguise on|off|status|reload
 */
public final class BlockDisguise extends JavaPlugin implements CommandExecutor, TabCompleter {

    private volatile boolean enabled;
    private boolean[] disguise;          // indexed by block state id
    private int replacementId;
    private Object replacementState;     // NMS BlockState of the replacement
    private Method getIdM;               // Block.getId(BlockState)
    private Method regGetId;             // fallback: registry.getId(state)
    private Object registryObj;
    private boolean parseWarned = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            buildTable();
        } catch (Throwable t) {
            getLogger().severe("Could not build block table, disabling: " + t);
            t.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        enabled = getConfig().getBoolean("enabled", true);
        getCommand("blockdisguise").setExecutor(this);
        getCommand("blockdisguise").setTabCompleter(this);

        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(this, ListenerPriority.HIGH,
                PacketType.Play.Server.MAP_CHUNK,
                PacketType.Play.Server.BLOCK_CHANGE,
                PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
            @Override
            public void onPacketSending(PacketEvent e) {
                if (!enabled || e.isPlayerTemporary() || isExempt(e.getPlayer())) return;
                try {
                    PacketType t = e.getPacketType();
                    if (t == PacketType.Play.Server.MAP_CHUNK) handleChunk(e);
                    else if (t == PacketType.Play.Server.BLOCK_CHANGE) handleBlockChange(e);
                    else handleMulti(e);
                } catch (Throwable ex) {
                    if (!parseWarned) {
                        parseWarned = true;
                        getLogger().warning("Failed to rewrite a packet (sending original): " + ex);
                    }
                }
            }
        });
    }

    private boolean isExempt(Player p) {
        return p.isOp() || p.hasPermission("blockdisguise.bypass");
    }

    // ---------- block table ----------

    private void buildTable() throws Exception {
        Class<?> blockCls = Class.forName("net.minecraft.world.level.block.Block");
        Class<?> stateCls = Class.forName("net.minecraft.world.level.block.state.BlockState");
        Object registry = blockCls.getField("BLOCK_STATE_REGISTRY").get(null);
        Method sizeM = registry.getClass().getMethod("size");
        Method byIdM = registry.getClass().getMethod("byId", int.class);
        try {
            getIdM = blockCls.getMethod("getId", stateCls);
        } catch (NoSuchMethodException ex) {
            getIdM = null;
            for (Method m : registry.getClass().getMethods()) {
                if (m.getName().equals("getId") && m.getParameterCount() == 1) {
                    regGetId = m;
                    break;
                }
            }
            if (regGetId == null) throw ex;
        }
        registryObj = registry;
        Class<?> cbd = Class.forName("org.bukkit.craftbukkit.block.data.CraftBlockData");
        Method fromData = cbd.getMethod("fromData", stateCls);
        Method getState = cbd.getMethod("getState");

        Material repl = Material.matchMaterial(getConfig().getString("replacement", "STONE"));
        if (repl == null || !repl.isBlock()) repl = Material.STONE;
        replacementState = getState.invoke(repl.createBlockData());
        replacementId = stateId(replacementState);

        Set<Material> keep = EnumSet.noneOf(Material.class);
        for (String s : getConfig().getStringList("keep-materials")) {
            Material m = Material.matchMaterial(s);
            if (m != null) keep.add(m);
        }
        boolean includeInteractable = getConfig().getBoolean("disguise-interactable-blocks", false);

        int size = (int) sizeM.invoke(registry);
        disguise = new boolean[size];
        int count = 0;
        for (int i = 0; i < size; i++) {
            Object state = byIdM.invoke(registry, i);
            if (state == null) continue;
            BlockData bd = (BlockData) fromData.invoke(null, state);
            Material m = bd.getMaterial();
            if (i == replacementId || m.isAir() || keep.contains(m)) continue;
            if (!m.isOccluding()) continue;                       // full opaque cubes only
            if (!includeInteractable && m.isInteractable()) continue; // furnaces, crafting tables, etc.
            disguise[i] = true;
            count++;
        }
        getLogger().info("Disguising " + count + " block states as " + repl);
    }

    private int stateId(Object state) throws Exception {
        if (getIdM != null) return (int) getIdM.invoke(null, state);
        return (int) regGetId.invoke(registryObj, state);
    }

    // ---------- single / multi block updates ----------

    private void handleBlockChange(PacketEvent e) throws Exception {
        PacketContainer p = e.getPacket().deepClone();
        WrappedBlockData d = p.getBlockData().read(0);
        int id = stateId(d.getHandle());
        if (id >= 0 && id < disguise.length && disguise[id]) {
            p.getBlockData().write(0, WrappedBlockData.fromHandle(replacementState));
            e.setPacket(p);
        }
    }

    private void handleMulti(PacketEvent e) throws Exception {
        PacketContainer p = e.getPacket().deepClone();
        MultiBlockChangeInfo[] infos = p.getMultiBlockChangeInfoArrays().read(0);
        boolean changed = false;
        for (MultiBlockChangeInfo info : infos) {
            int id = stateId(info.getData().getHandle());
            if (id >= 0 && id < disguise.length && disguise[id]) {
                info.setData(WrappedBlockData.fromHandle(replacementState));
                changed = true;
            }
        }
        if (changed) {
            p.getMultiBlockChangeInfoArrays().write(0, infos);
            e.setPacket(p);
        }
    }

    // ---------- chunk packets ----------

    private void handleChunk(PacketEvent e) {
        byte[] in = e.getPacket().getByteArrays().read(0);
        byte[] out = rewriteChunk(in);
        if (out == null) return;
        PacketContainer p = e.getPacket().shallowClone(); // chunk packets can be shared between players
        p.getByteArrays().write(0, out);
        e.setPacket(p);
    }

    /** Returns rewritten chunk data, or null if it could not be parsed cleanly. */
    private byte[] rewriteChunk(byte[] data) {
        try {
            Reader r = new Reader(data);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(data.length);
            while (r.pos < data.length) {
                bo.write(r.raw(2), 0, 2);                 // non-air block count
                blockContainer(r, bo);
                biomeContainer(r, bo);
            }
            return bo.toByteArray();
        } catch (Throwable t) {
            if (!parseWarned) {
                parseWarned = true;
                getLogger().warning("Chunk format not understood, sending originals: " + t);
            }
            return null;
        }
    }

    private int map(int id) {
        return (id >= 0 && id < disguise.length && disguise[id]) ? replacementId : id;
    }

    private void blockContainer(Reader r, ByteArrayOutputStream w) {
        int bits = r.u8();
        if (bits == 0) {
            w.write(0);
            writeVar(w, map(r.varInt()));
        } else if (bits <= 8) {
            w.write(bits);
            int n = r.varInt();
            writeVar(w, n);
            for (int i = 0; i < n; i++) writeVar(w, map(r.varInt()));
            copyLongs(r, w, 4096, bits);                  // indices unchanged
        } else {
            w.write(bits);
            int per = 64 / bits;
            int longs = (4096 + per - 1) / per;
            long mask = (1L << bits) - 1;
            for (int l = 0; l < longs; l++) {
                long v = r.long64(), nv = 0;
                for (int k = 0; k < per && l * per + k < 4096; k++) {
                    int sh = k * bits;
                    long id = (v >>> sh) & mask;
                    nv |= ((long) map((int) id) & mask) << sh;
                }
                writeLong(w, nv);
            }
        }
    }

    private void biomeContainer(Reader r, ByteArrayOutputStream w) {
        int bits = r.u8();
        w.write(bits);
        if (bits == 0) {
            writeVar(w, r.varInt());
        } else {
            if (bits <= 3) {
                int n = r.varInt();
                writeVar(w, n);
                for (int i = 0; i < n; i++) writeVar(w, r.varInt());
            }
            copyLongs(r, w, 64, bits);
        }
    }

    private void copyLongs(Reader r, ByteArrayOutputStream w, int entries, int bits) {
        int per = 64 / bits;
        int longs = (entries + per - 1) / per;
        byte[] b = r.raw(longs * 8);
        w.write(b, 0, b.length);
    }

    private static void writeVar(ByteArrayOutputStream w, int v) {
        while ((v & ~0x7F) != 0) { w.write((v & 0x7F) | 0x80); v >>>= 7; }
        w.write(v);
    }

    private static void writeLong(ByteArrayOutputStream w, long v) {
        for (int i = 7; i >= 0; i--) w.write((int) (v >>> (i * 8)) & 0xFF);
    }

    private static final class Reader {
        final byte[] b; int pos = 0;
        Reader(byte[] b) { this.b = b; }
        int u8() { return b[pos++] & 0xFF; }
        byte[] raw(int n) {
            if (pos + n > b.length) throw new IllegalStateException("overrun");
            byte[] o = Arrays.copyOfRange(b, pos, pos + n); pos += n; return o;
        }
        long long64() {
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | u8();
            return v;
        }
        int varInt() {
            int v = 0, s = 0, x;
            do { x = u8(); v |= (x & 0x7F) << s; s += 7; if (s > 35) throw new IllegalStateException("varint"); }
            while ((x & 0x80) != 0);
            return v;
        }
    }

    // ---------- command ----------

    private void resendChunks() {
        Set<Long> done = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isExempt(p)) continue;
            World w = p.getWorld();
            int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4;
            int vd = Math.min(p.getViewDistance(), 16);
            for (int dx = -vd; dx <= vd; dx++)
                for (int dz = -vd; dz <= vd; dz++) {
                    int x = cx + dx, z = cz + dz;
                    long key = (((long) x) << 32) ^ (z & 0xFFFFFFFFL) ^ ((long) w.getUID().hashCode() << 8);
                    if (w.isChunkLoaded(x, z) && done.add(key)) {
                        try { w.refreshChunk(x, z); } catch (Throwable ignored) { }
                    }
                }
        }
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase() : "status";
        switch (sub) {
            case "on", "off" -> {
                enabled = sub.equals("on");
                getConfig().set("enabled", enabled);
                saveConfig();
                resendChunks();
                s.sendMessage("Block disguise is now " + (enabled ? "ON" : "OFF"));
            }
            case "reload" -> {
                reloadConfig();
                try { buildTable(); } catch (Throwable t) { s.sendMessage("Reload failed: " + t); return true; }
                enabled = getConfig().getBoolean("enabled", true);
                resendChunks();
                s.sendMessage("Reloaded. Block disguise is " + (enabled ? "ON" : "OFF"));
            }
            default -> s.sendMessage("Block disguise is " + (enabled ? "ON" : "OFF") + ". Usage: /bd <on|off|reload|status>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        return a.length == 1 ? List.of("on", "off", "reload", "status") : List.of();
    }
}
