# Custom Monuments

Server operators can place a **Monument Controller** to create a protected monument. Its controller chunk is designated automatically. Use the controller's **Chunk Map** tab to add or remove monument chunks. The map defaults to 11×11, can zoom from 7×7 through 25×25 with the mouse wheel or `+`/`-` controls, and can pan beyond the visible grid. Place **Monument Loot Crates** inside designated chunks to link them automatically; sneak-use a crate to cycle between Supply, Ammo, and Gun variants.

The Chunk Map uses the same sampled terrain rendering as the Faction Table claim map. Monument chunks have green borders, the controller chunk is gold, and chunks claimed by any faction have red borders. Terrain is shown when the operator is in the monument's dimension; otherwise the unloaded background is displayed with the ownership overlays.

Each monument can have up to eight named loot pools. Open **Settings & Loot**, create or select a pool, and place items into its 54-slot editor. Right-click an item to configure its minimum count, maximum count, and rarity from 0 (100% appearance chance) through 10 (0.1% appearance chance), using the same generation rules as supply drops. Level 9 has a 1% appearance chance. Sneak-use a linked Monument Loot Crate to cycle it through the monument's available pools. Existing Supply, Ammo, and Gun slot bands are migrated to matching named pools when an older monument is first opened.

Fulfilled player buy orders are held securely by the market. The buyer must open a Faction Market, select **My Listings**, and claim the item from **Pending Purchases**. Deliveries persist while the buyer is offline and across server restarts.

The market notifies online players when a listing sells or a buy order is filled. On login, players receive a summary of unclaimed sale proceeds and pending purchases. While claims remain, an hourly reminder shows the number of sales, their total value, and the number of pending purchases. Reminder timing persists across server restarts.

Use the controller to edit its three loot pools:

- Slots 1-18: Supply loot
- Slots 19-36: Ammo loot
- Slots 37-54: Gun loot

`/factions monument` lists each location and its live loot timer. Operators use `/faction monument edit` to open the monument browser, then click a monument to edit its name, tier, respawn timer, loot pools, designated chunks, or delete it. Right-clicking a Monument Controller opens the same panel directly. The controller chunk cannot be removed, chunks cannot overlap another monument, and chunks containing linked crates or Ore Generators must be cleared before removal.

Loot timers pause while any player is inside. The countdown runs at half speed on low population, reaches the configured base time at 10 online players, and caps at double speed at 20 or more players. Players cannot place or destroy blocks in a monument, and explosions do not damage it.

**Ore Generators** can only be placed by operators inside a monument and link automatically. They begin as unbreakable cobblestone. Enable operator bypass with `/faction bypass`, then use a vanilla or modded ore block on the generator to configure it; the selected ore activates immediately. It drops through the ore's native loot table when mined (including Fortune and Silk Touch), returns to unbreakable cobblestone, and regenerates on subsequent monument loot timers. Ordinary monument blocks cannot be placed or broken unless bypass is enabled.


Installation information
=======

This template repository can be directly cloned to get you started with a new
mod. Simply create a new repository cloned from this one, by following the
instructions provided by [GitHub](https://docs.github.com/en/repositories/creating-and-managing-repositories/creating-a-repository-from-a-template).

Once you have your clone, simply open the repository in the IDE of your choice. The usual recommendation for an IDE is either IntelliJ IDEA or Eclipse.

If at any point you are missing libraries in your IDE, or you've run into problems you can
run `gradlew --refresh-dependencies` to refresh the local cache. `gradlew clean` to reset everything 
{this does not affect your code} and then start the process again.

Mapping Names:
============
By default, the MDK is configured to use the official mapping names from Mojang for methods and fields 
in the Minecraft codebase. These names are covered by a specific license. All modders should be aware of this
license. For the latest license text, refer to the mapping file itself, or the reference copy here:
https://github.com/NeoForged/NeoForm/blob/main/Mojang.md

Additional Resources: 
==========
Community Documentation: https://docs.neoforged.net/  
NeoForged Discord: https://discord.neoforged.net/
