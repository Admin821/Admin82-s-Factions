package com.admin82.factions.faction;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

import javax.annotation.Nullable;
import java.util.*;

public class Faction {
    private final UUID id;
    private String name;
    private String description;
    private UUID ownerId;
    private int power;
    private final List<FactionMember> members = new ArrayList<>();
    private final Map<FactionPermission, Boolean> permissions = new EnumMap<>(FactionPermission.class);
    private final Map<FactionRole, Map<FactionPermission, Boolean>> rolePermissions = new EnumMap<>(FactionRole.class);
    private final List<LandClaim> landClaims = new ArrayList<>();
    private final List<WarEntry> wars = new ArrayList<>();
    /** Epoch-ms when this faction was created. Used for the 1-hour grace-period protection. */
    private long createdAt = 0L;

    // Default permissions for each role (what each role is allowed to do by default)
    private static final Map<FactionRole, Set<FactionPermission>> DEFAULT_ROLE_PERMS;
    static {
        DEFAULT_ROLE_PERMS = new EnumMap<>(FactionRole.class);
        DEFAULT_ROLE_PERMS.put(FactionRole.ADMIN, EnumSet.of(
                FactionPermission.MEMBER_BUILD, FactionPermission.MEMBER_INTERACT,
                FactionPermission.MEMBER_USE_STORAGE, FactionPermission.OFFICER_INVITE,
                FactionPermission.OFFICER_CLAIM, FactionPermission.OFFICER_KICK,
                FactionPermission.OFFICER_DECLARE_WAR));
        DEFAULT_ROLE_PERMS.put(FactionRole.OFFICER, EnumSet.of(
                FactionPermission.MEMBER_BUILD, FactionPermission.MEMBER_INTERACT,
                FactionPermission.MEMBER_USE_STORAGE, FactionPermission.OFFICER_INVITE,
                FactionPermission.OFFICER_CLAIM));
        DEFAULT_ROLE_PERMS.put(FactionRole.MEMBER, EnumSet.of(
                FactionPermission.MEMBER_BUILD, FactionPermission.MEMBER_INTERACT));
    }

    public Faction(UUID id, String name, UUID ownerId, String description) {
        this.id = id;
        this.name = name;
        this.ownerId = ownerId;
        this.description = description;
        this.power = 10;
        this.createdAt = System.currentTimeMillis();
        for (FactionPermission perm : FactionPermission.values()) {
            permissions.put(perm, perm.getDefaultValue());
        }
        // Initialize per-role permissions with defaults
        for (FactionRole role : new FactionRole[]{FactionRole.ADMIN, FactionRole.OFFICER, FactionRole.MEMBER}) {
            Map<FactionPermission, Boolean> rp = new EnumMap<>(FactionPermission.class);
            Set<FactionPermission> allowed = DEFAULT_ROLE_PERMS.getOrDefault(role, EnumSet.noneOf(FactionPermission.class));
            for (FactionPermission p : FactionPermission.values()) rp.put(p, allowed.contains(p));
            rolePermissions.put(role, rp);
        }
    }

    // ── Getters / Setters ──────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public long getCreatedAt() { return createdAt; }
    /** True if the faction is still within the 1-hour new-faction protection grace period. */
    public boolean isInGracePeriod() { return createdAt > 0L && System.currentTimeMillis() - createdAt < 3_600_000L; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String desc) { this.description = desc; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
    public int getPower() { return power; }
    public void setPower(int power) { this.power = power; }
    public List<FactionMember> getMembers() { return Collections.unmodifiableList(members); }

    // ── Member management ──────────────────────────────────────────────────────

    @Nullable
    public FactionMember getMember(UUID uuid) {
        return members.stream().filter(m -> m.getUuid().equals(uuid)).findFirst().orElse(null);
    }

    public boolean hasMember(UUID uuid) { return getMember(uuid) != null; }

    public void addMember(FactionMember member) {
        members.add(member);
    }

    public void removeMember(UUID uuid) {
        members.removeIf(m -> m.getUuid().equals(uuid));
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    public boolean getPermission(FactionPermission permission) {
        return permissions.getOrDefault(permission, permission.getDefaultValue());
    }

    public void setPermission(FactionPermission permission, boolean value) {
        permissions.put(permission, value);
    }

    public Map<FactionPermission, Boolean> getPermissions() { return Collections.unmodifiableMap(permissions); }

    public boolean getRolePermission(FactionRole role, FactionPermission perm) {
        if (role == FactionRole.OWNER) return true;
        Map<FactionPermission, Boolean> rp = rolePermissions.get(role);
        if (rp == null) {
            Set<FactionPermission> defaults = DEFAULT_ROLE_PERMS.getOrDefault(role, EnumSet.noneOf(FactionPermission.class));
            return defaults.contains(perm);
        }
        return rp.getOrDefault(perm, false);
    }

    public void setRolePermission(FactionRole role, FactionPermission perm, boolean value) {
        if (role == FactionRole.OWNER) return;
        rolePermissions.computeIfAbsent(role, r -> new EnumMap<>(FactionPermission.class)).put(perm, value);
    }

    public Map<FactionRole, Map<FactionPermission, Boolean>> getRolePermissions() {
        return Collections.unmodifiableMap(rolePermissions);
    }

    // ── Land claims ───────────────────────────────────────────────────────────

    public List<LandClaim> getLandClaims() { return Collections.unmodifiableList(landClaims); }

    public boolean hasClaim(int chunkX, int chunkZ, String dimension) {
        return landClaims.stream().anyMatch(c -> c.matches(chunkX, chunkZ, dimension));
    }

    public void addClaim(LandClaim claim) { landClaims.add(claim); }

    public boolean removeClaim(int chunkX, int chunkZ, String dimension) {
        return landClaims.removeIf(c -> c.matches(chunkX, chunkZ, dimension));
    }

    // ── Wars ──────────────────────────────────────────────────────────────────

    public List<WarEntry> getWars() { return Collections.unmodifiableList(wars); }
    public void addWar(WarEntry war) { wars.add(war); }
    public void removeWar(UUID targetFactionId) { wars.removeIf(w -> w.targetFactionId().equals(targetFactionId)); }
    public boolean isAtWarWith(UUID targetId) { return wars.stream().anyMatch(w -> w.targetFactionId().equals(targetId)); }

    // ── NBT Serialization ─────────────────────────────────────────────────────

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putString("name", name);
        tag.putString("description", description);
        tag.putUUID("owner", ownerId);
        tag.putInt("power", power);

        ListTag memberList = new ListTag();
        members.forEach(m -> memberList.add(m.save()));
        tag.put("members", memberList);

        CompoundTag permsTag = new CompoundTag();
        permissions.forEach((perm, val) -> permsTag.putBoolean(perm.getKey(), val));
        tag.put("permissions", permsTag);

        // Per-role permissions
        CompoundTag rolePermsTag = new CompoundTag();
        rolePermissions.forEach((role, rp) -> {
            CompoundTag rt = new CompoundTag();
            rp.forEach((perm, val) -> rt.putBoolean(perm.getKey(), val));
            rolePermsTag.put(role.getId(), rt);
        });
        tag.put("rolePermissions", rolePermsTag);

        ListTag claimList = new ListTag();
        landClaims.forEach(c -> claimList.add(c.save()));
        tag.put("claims", claimList);

        ListTag warList = new ListTag();
        wars.forEach(w -> warList.add(w.save()));
        tag.put("wars", warList);
        tag.putLong("createdAt", createdAt);

        return tag;
    }

    public static Faction load(CompoundTag tag) {
        Faction faction = new Faction(
                tag.getUUID("id"),
                tag.getString("name"),
                tag.getUUID("owner"),
                tag.getString("description")
        );
        faction.power = tag.getInt("power");

        ListTag memberList = tag.getList("members", Tag.TAG_COMPOUND);
        for (int i = 0; i < memberList.size(); i++) {
            faction.members.add(FactionMember.load(memberList.getCompound(i)));
        }

        CompoundTag permsTag = tag.getCompound("permissions");
        for (FactionPermission perm : FactionPermission.values()) {
            if (permsTag.contains(perm.getKey())) {
                faction.permissions.put(perm, permsTag.getBoolean(perm.getKey()));
            }
        }

        // Per-role permissions
        if (tag.contains("rolePermissions")) {
            CompoundTag rolePermsTag = tag.getCompound("rolePermissions");
            for (FactionRole role : new FactionRole[]{FactionRole.ADMIN, FactionRole.OFFICER, FactionRole.MEMBER}) {
                if (rolePermsTag.contains(role.getId())) {
                    CompoundTag rt = rolePermsTag.getCompound(role.getId());
                    Map<FactionPermission, Boolean> rp = new EnumMap<>(FactionPermission.class);
                    Set<FactionPermission> defaults = DEFAULT_ROLE_PERMS.getOrDefault(role, EnumSet.noneOf(FactionPermission.class));
                    for (FactionPermission perm : FactionPermission.values()) {
                        rp.put(perm, rt.contains(perm.getKey()) ? rt.getBoolean(perm.getKey()) : defaults.contains(perm));
                    }
                    faction.rolePermissions.put(role, rp);
                }
            }
        }

        ListTag claimList = tag.getList("claims", Tag.TAG_COMPOUND);
        for (int i = 0; i < claimList.size(); i++) {
            faction.landClaims.add(LandClaim.load(claimList.getCompound(i)));
        }

        ListTag warList = tag.getList("wars", Tag.TAG_COMPOUND);
        for (int i = 0; i < warList.size(); i++) {
            faction.wars.add(WarEntry.load(warList.getCompound(i)));
        }
        faction.createdAt = tag.contains("createdAt") ? tag.getLong("createdAt") : 0L;

        return faction;
    }

    // ── Network Serialization ─────────────────────────────────────────────────

    public void toNetwork(FriendlyByteBuf buf) {
        buf.writeUUID(id);
        buf.writeUtf(name);
        buf.writeUtf(description);
        buf.writeUUID(ownerId);
        buf.writeVarInt(power);

        buf.writeVarInt(members.size());
        members.forEach(m -> m.toNetwork(buf));

        buf.writeVarInt(permissions.size());
        permissions.forEach((perm, val) -> {
            buf.writeUtf(perm.getKey());
            buf.writeBoolean(val);
        });

        // Per-role permissions
        buf.writeVarInt(rolePermissions.size());
        rolePermissions.forEach((role, rp) -> {
            buf.writeUtf(role.getId());
            buf.writeVarInt(rp.size());
            rp.forEach((perm, val) -> { buf.writeUtf(perm.getKey()); buf.writeBoolean(val); });
        });

        buf.writeVarInt(landClaims.size());
        landClaims.forEach(c -> c.toNetwork(buf));

        buf.writeVarInt(wars.size());
        wars.forEach(w -> w.toNetwork(buf));
    }

    public static Faction fromNetwork(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        String name = buf.readUtf(64);
        String description = buf.readUtf(256);
        UUID ownerId = buf.readUUID();
        int power = buf.readVarInt();

        Faction faction = new Faction(id, name, ownerId, description);
        faction.power = power;

        int memberCount = buf.readVarInt();
        for (int i = 0; i < memberCount; i++) {
            faction.members.add(FactionMember.fromNetwork(buf));
        }

        int permCount = buf.readVarInt();
        for (int i = 0; i < permCount; i++) {
            String key = buf.readUtf(50);
            boolean val = buf.readBoolean();
            FactionPermission perm = FactionPermission.fromKey(key);
            if (perm != null) faction.permissions.put(perm, val);
        }

        // Per-role permissions
        int rolePermCount = buf.readVarInt();
        for (int i = 0; i < rolePermCount; i++) {
            FactionRole role = FactionRole.fromId(buf.readUtf(20));
            int rpCount = buf.readVarInt();
            Map<FactionPermission, Boolean> rp = new EnumMap<>(FactionPermission.class);
            for (int j = 0; j < rpCount; j++) {
                FactionPermission perm = FactionPermission.fromKey(buf.readUtf(64));
                boolean val = buf.readBoolean();
                if (perm != null) rp.put(perm, val);
            }
            if (role != FactionRole.OWNER) faction.rolePermissions.put(role, rp);
        }

        int claimCount = buf.readVarInt();
        for (int i = 0; i < claimCount; i++) {
            faction.landClaims.add(LandClaim.fromNetwork(buf));
        }

        int warCount = buf.readVarInt();
        for (int i = 0; i < warCount; i++) {
            faction.wars.add(WarEntry.fromNetwork(buf));
        }

        return faction;
    }
}
