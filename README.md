# Custom Monuments

Requires Minecraft 1.21.1, NeoForge and LDLib2.

**Optional:** the [unofficial TaCZ 1.21.1 NeoForge port](https://modrinth.com/mod/tacz-1.21.1) (`1.1.8-hotfix-r6` or newer, on both the client and server). With it, monument PMCs carry and fire TaCZ guns and hear TaCZ gunfire. Without it, they use crossbows.

Server operators can place a **Monument Controller** to create a protected monument. Its controller chunk is designated automatically. Use the controller's **Chunk Map** tab to add or remove monument chunks. The map defaults to 11×11, can zoom from 7×7 through 25×25 with the mouse wheel or `+`/`-` controls, and can pan beyond the visible grid. Place **Monument Loot Crates** inside designated chunks to link them automatically; sneak-use a crate to cycle between Supply, Ammo, and Gun variants.

The Chunk Map uses the same sampled terrain rendering as the Faction Table claim map. Monument chunks have green borders, the controller chunk is gold, and chunks claimed by any faction have red borders. Terrain is shown when the operator is in the monument's dimension; otherwise the unloaded background is displayed with the ownership overlays.

Each monument can have up to eight named loot pools. Open the **Loot Pools** tab, create or select a pool, and place items into its 54-slot editor. Right-click an item to configure its minimum count, maximum count, and rarity from 0 (100% appearance chance) through 10 (0.1% appearance chance), using the same generation rules as supply drops. Level 9 has a 1% appearance chance. Sneak-use a linked Monument Loot Crate to cycle it through the monument's available pools. Existing Supply, Ammo, and Gun slot bands are migrated to matching named pools when an older monument is first opened.

## Market

The Faction Market has one **Auction House** page listing everything on the market. Each row shows whether it is a **Buy Now** item, an **Auction**, a **Buy Order** or a **Server** shop item. The filter bar has a search box, an item category (Weapons, Guns, Ammo, Armor, Tools, Food, Potions, Resources, Blocks, Redstone, Utility, Misc), a mod filter that only lists mods with items on the market, and a sort order (newest, price, ending soon, name). It also has type toggles, **Hide mine** and **Clear**.

**Buy orders** ask for a quantity of an item at a price per item, for example 25 Diamonds at 2 silver each. Placing one holds the full amount from your wallet. Anyone can turn in part of an order: if you have 5 diamonds you can sell those 5 and be paid for 5. The order then shows 5/25. The buyer gets a message such as "Steve filled 5 of your order for 25 Diamond (5/25) for 10s". If the buyer is offline, the message waits until they next log in. The order closes when it is full. Cancelling it or letting it expire refunds whatever money is still held. Server buy orders accept any amount.

Selling into a player's buy order is taxed at the Buy Now rate, and vassal tax applies to every kind of sale (Buy Now, auctions and buy orders). Each player can have up to **Listings per player** listings and buy orders up at once (`market.maxListingsPerPlayer`, default 5). Auctions last 4, 8, 12, 16 or 24 hours.

Purchased and buy-order items are held by the market until claimed. Open **My Market** and use **Claim** or **Claim All**. Deliveries persist while the player is offline and across server restarts.

**History.** The market's **History** page lists your purchases, sales, bids, refunds and buy-order activity. On the Faction Table, **Vault** has a **Wallet history** and a **Vault history**:
- **Wallet history** covers deposits, withdrawals, market payments and outpost teleports.
- **Vault history** covers deposits and withdrawals by member, land upkeep, claimed chunks, chunkloading deeds, war spoils, vassal tax and war demands.

Repeated upkeep charges are combined into one line per hour. The last 100 entries of each history are kept and saved with the world.

**Price history.** When you set a price for a sale, auction or buy order, the market shows that item's recent sales:
- the last sale and its source,
- the 7-day average price per item and how many sold,
- the low–high range,
- all-time totals,
- a bar chart of daily average prices over the last 14 days (or the individual recent sales while there is less history).

**Use average** fills in the average price for your stack. Every completed Buy Now sale, auction win and player buy-order fill is recorded. Server shop and server buy-order prices are not, because operators set them.

## Skins: Modern and Medieval

`/faction skin modern` or `/faction skin medieval` (or just `/faction skin` to switch) changes how the mod's blocks look **for you**. It's purely visual: nobody else is affected, and it doesn't change how anything works.

- **Modern** (the default): the military look. The Barracks is a 2-tall army locker whose doors swing open while someone has it open, the Currency Exchange a field ATM, and monument crates are ammo cans.
- **Medieval**: the older looks. The Barracks tent, the market-stall Currency Exchange (fitted to its 1-wide space) and barrels for monument crates.

Blocks that only ever had one look (seam extractor, meters, outpost storage box, and so on) look the same in both. The skin is a built-in resource pack ("Admin's Factions: Medieval"), so your choice is saved with your resource pack settings and you can also turn it on or off in the Resource Packs menu.

## Admin panel (operators)

`/faction admin` opens one panel for everything the old admin commands did. The commands still work for the console and command blocks.

- **Settings:** every server setting, grouped into Land & Upkeep, Market, Wars, Outposts & Return, Alliances & Vassals, Bounties, and Monuments & AI. Each row shows its current value with a tooltip explaining it, its allowed range and its default.
  - Type a value (money as `5g`, `2g50s` or `300`) and press ✔, or flip a switch. Changes apply straight away and are written to `config/adminsfactions-common.toml`, so you can also edit that file by hand. Settings that older versions kept in the world save are copied into the config the first time the world loads.
  - A yellow • marks settings changed from the default; ↺ puts one back.
  - The search box searches every setting.
- **Factions:** every faction with its members, chunks and vault. Delete a faction or free a vassal (both click twice), or move any player into a faction or out of theirs.
- **Monuments:** every monument, whether its controller block is actually there, and its location. **Go** teleports you to it. **Move** lets you put it somewhere else: the panel closes, and the next Monument Controller you place becomes that monument's new spot (you're given one if you don't have one). Its chunks, crate spots and ore generators move with it and keep the same layout around the controller. The old controller block is removed and its guards start over. This is how you set up imported monuments in a new world. `/faction monument move <name>` does the same. Crates and generators that aren't there yet link themselves when you place them in the monument area.
- **Editors & tools:** opens the monument editor, AI combat profiles, supply drop loot and spawns, and the exchange rate editor, and toggles your protection bypass.
- **Import / Export:** choose any of these and export them to a file:
  - mod settings
  - monuments (including loot pools, guard roster, gear and drops)
  - AI profiles
  - supply drops
  - exchange rates
  - server market listings

  Files are saved as readable SNBT in `config/adminsfactions/exports/` and can be copied to another server. Click a saved file, choose which parts to import, and click **Import** twice. Importing replaces only the chosen parts. Player listings, factions and money are never touched.

## Server Treasury (operators)

Money the server takes in is recorded in the **Treasury**, an operator-only page in the market sidebar (under **Admin**):
- **Overview:** totals for all time, today and the last 7 days; a 14-day chart of daily income; and a table of every source showing its share, today's amount, the number of payments and the all-time total.
- **Top payers:** the players and factions who have paid the most.
- **Recent:** the last 100 payments, with who paid and what for. Upkeep is merged into one line per hour.

What it counts:
- **Income:** market tax on Buy Now sales, auctions and buy-order sales, bounty tax, land claims, chunkloading deeds, land upkeep, outpost upkeep, outpost teleports, exchange item purchases and server shop sales.
- **Money issued** (shown separately, in red): exchange payouts and server buy orders. These create money rather than take it.

Payments between players or factions (vassal tax, war demands, spoils, independence buyouts) are not server income and aren't counted. **Reset** (click twice) wipes the statistics but not anyone's money. The data is saved with the world and refreshes every few seconds while the page is open.

The market tax percentages in the config (`marketTaxBinPercent`, `marketTaxAuction4h` … `24h`) are now actually used; before, the market ignored them and always charged 10–24%.

## Alliances

Alliances are non-aggression pacts. Open **Faction Table → Alliances** (under Diplomacy). Officers can propose an alliance to any faction they are not at war with. The other faction's officers accept or decline; if both sides propose, the alliance forms straight away. Proposals expire after 24 hours.

While two factions are allied:
- neither can declare war on the other (the Wars tab shows "🤝 Allied"),
- members cannot claim bounties on each other.

Breaking an alliance (click twice) keeps those rules in place for **2 days** (`alliances.breakCooldownHours`, default 48), so an ally can't break the pact and attack straight away. The Alliances page shows the time left under "Recently broken", and the Wars tab shows it as a timer. Everything is saved with the world, including cooldowns, and is cleaned up when a faction disbands.

**Leaving a faction** doesn't free you from its alliances straight away. For **24 hours** after you leave, are kicked, are moved by an admin, or your faction disbands (`alliances.leaveCooldownHours`, default 24), you can't place or claim bounties on your old faction or the factions it was allied with, declare war on them, or be sent to fight them. The factions covered are the ones allied (or in a break cooldown) at the moment you left.

## Bounty Board

The market's **Bounty Board** page lets players put a price on another player's head:
- **Place a bounty:** choose any player who has played on the server (online players you're allowed to target are suggested), the amount to pay from your wallet, how long it lasts (1, 3 or 7 days), and optionally a **Show as** name. Leave it empty to stay **Anonymous**. Your real name is never sent to other players.
- **Who you can target:** not yourself, your teammates, or allied factions (including the 2 days after an alliance is broken).
- **Tax:** 15% of what you pay is kept as tax when you place it; the other 85% is the reward. The tax is never refunded, so a claim pays out the 85% and a cancel or expiry refunds the 85%.
- **Board:** every target with their total reward, online status, faction and whether *you* can claim it. Click a target to see each bounty on them.
- **My bounties:** your open bounties, with **Cancel** (click twice) to get the reward back.

Killing a target pays every bounty on them that you're allowed to claim straight into your wallet, and the server announces it. You **cannot** claim a bounty if you are the target, you placed it, you are on the target's team, or your faction is allied with the target's faction (or broke an alliance with it in the last 2 days). Unclaimed bounties are refunded (minus the tax) when they expire.

Config (`bounties` section): `minimumCopper` (default 100 = 1 silver), `maxDays` (7), `maxActivePerPlayer` (5), `taxPercent` (15).

## Currency Exchange

A field ATM, two blocks tall (it needs a free block above it), with a lit screen and "EXCHANGE" sign. Right-click either half to open it.

- **Sell items** lists every item the exchange accepts that you carry, with the count and total value. Pick one and sell 1, a stack, all, or any amount. **Sell everything** sells it all at once (click twice). Enchanted, renamed and damaged items are never sold.
- **Buy items** appears when operators turn on two-way exchange. Buy any amount, with a **Max** button for what you can afford.
- **Coins or Wallet:** buying and selling both let you choose between coins in your inventory and your wallet. Wallet payments appear in your wallet history.
- **Wallet** shows the coins you carry. Deposit them all to your wallet, or withdraw wallet money as coins (typed like `5g` or `2g50s`).
- **Manage rates** (operators) lists all rates with search. Click a rate to edit it, or remove it with ✕ (click twice). To add a rate, type an item id, use your held item, or click an item in the inventory grid, then enter a price like `2g50s`. A checkbox turns two-way exchange on or off.

Coins that don't fit in a full inventory now drop at your feet instead of disappearing.

## Power and Fluid Meters

The **Power Meter** and **Fluid Meter** are two blocks tall. The bottom block is the storage: a battery (10,000 FE) or a tank (10,000 mB, one fluid at a time). Each **Meter efficiency** level makes it 50% bigger. The meter faces you when placed. Its computer screen reads out what it holds (with a fill bar), its network, whether it is buying or selling (or why it is stuck, such as out of coins or no seller) and the coins it is earning or spending per hour. Any mod's cables and pipes can push into it or pull out of it.

Right-click the **bottom block** to see what's stored, dump it (click twice), and set each of the four sides to **Any**, **Input** or **Output**. Sides are named as you look at the screen and match the coloured ports on the model: blue front, green left, yellow back, pink right. Output sides also push into the block next to them, for cables and pipes that never pull. The top and bottom faces always allow both. The meter works with any mod that uses NeoForge's standard energy (FE) and fluid handlers.

**NTM: Continuum:** NTM wires charge a meter through any side, but only draw power out of an **Output** side. NTM treats a block that can both take and give power as receive-only. NTM fluid ducts only fill other mods' tanks and never drain them, so to send fluid into NTM, put an NTM tank or machine set to that fluid directly against an Output side. Right-click the **top block** to buy or sell. Pistons can't move either half.

Power and fluid are traded on an automatic market:

- **Sell:** list what's in the meter with a name, a price (a number and a coin type per an amount, for example 1 Copper per 1000 FE) and who can buy: everyone or your faction only. A price of 0 makes it free. You can change the price at any time. Put more of your meters on the same listing from the **Market** with **Sell from this meter too**.
- **Buy:** set the most you'll pay (for example 2 Copper per 1000 FE). Nobody else can see it. A fluid meter also needs the fluid to buy, picked from a list of every fluid in the game. The meter connects by itself to a seller whose price is at or under your max, choosing the cheapest that has stock.
- **Shared fairly:** a buyer stays with its seller, and each tick a seller's stock is split equally between all the buyers connected to it. A buyer that needs less than its share leaves the rest for the others. If the seller raises its price above a buyer's max, that buyer is disconnected straight away and moves to another seller at or under its max, if there is one. A buyer also switches when its seller can't deliver (out of stock, not loaded, or coin storage full) and another seller can.
- **Paying:** click **Coin storage** and move coins into the meter's 9 coin slots, like a chest. Hoppers, item pipes and Create funnels can also put coins in or take them out, through any side of either block. The buyer pays the seller's price, not its own max, from those coins, and the change goes back into the slots. It buys until its battery or tank is full, up to 256 FE/tick or 1000 mB/s (each **Meter efficiency** level adds 50%). If a fluid meter's tank holds a different fluid from the one it buys, empty it first (bottom block → Dump).
- **Price graph:** the meter's main screen, the buy screen and the **Market** show the average price paid each hour over the last 24 hours, with ▲/▼ showing how the latest hour compares with the one before. Hover a bar for its price and amount sold. Power has one graph; each fluid has its own (the buy screen shows the fluid you pick). The **Market** also lists every listing, cheapest first, with its price per 1000, fluid and stock. Buyers' max prices are never shown.
- **Getting paid:** each supplier meter is paid for exactly what it delivers, in coins, into its own **Coin storage**, and its owner has to go and take them out. The owner gets a chat message when a meter's coin storage is 80% full and again when it's full. A full meter **stops selling** until coins are taken out.
- **Coin limit:** a meter's coin storage holds up to 5 gold (`meters.coinCapBase`, in copper). Each **Meter efficiency** level raises it by 50%. Coins that would go over the limit can't be put in.
- **Unloaded meters:** a supplier meter only sells while its chunk is loaded. If nobody is nearby, buyers see that it isn't loaded. A **Chunkloading Deed** keeps it running, as long as the generators feeding it are in deeded chunks too.
- Only the player who placed a meter can change it. Breaking a meter returns its coins to that player's wallet.

Rates, capacities and the upgrade cost are in the `meters` section of the config and on the admin panel.

## War negotiations

During a war, open **Wars → Negotiate** on the Faction Table. Officers can send a demand made of any mix of terms:
- a payment from the enemy vault (typed like `5g`, `2g50s` or `300`),
- items, by id or with **Use held**,
- land: their most recent claims, never the core chunk,
- vassalage.

An empty demand is a **white peace** offer. Each side has one open demand at a time, and sending a new one replaces it. Demands can be withdrawn, and expire after 10 minutes.

The receiving officers can **Accept** (click twice), **Reject** or **Counter**. Counter opens the demand form pre-filled with the enemy's terms. Before anything is taken, every term is checked: the vault balance, items across all online members' inventories, and enough non-core land. **Accepting any demand ends the war.**

## Outposts

An outpost is a small base away from home: right-click the Outpost item to place its **flag**, with an **Outpost Storage Box** beside it. It claims the chunk it's in. Members can teleport to it from the faction table. New outposts are called "Outpost 1", "Outpost 2" and so on; officers can rename them from the flag. A faction starts with one outpost slot and can buy more in Faction Upgrades.

**Coins and upkeep.** An outpost pays its own upkeep from coins kept in it, not from the faction vault. Open the flag and press **Coin storage**: it has 9 coin slots like a chest, and any faction member can put coins in (hoppers can too). Only officers can take coins out. Copper is exchanged into silver, gold and platinum on its own. Upkeep is `outposts.upkeepCopper` (default 5 silver) per day per level, plus 10% for every 5 chunks away from the faction table. If the coins run out, the outpost is **unsupplied** for 24 hours (`outposts.unsuppliedGraceHours`): payment is retried every hour, and if it's still unpaid after that the outpost falls apart.

**Capturing.** The flag has **50 HP** (`outposts.flagHp`). Breaking it doesn't remove it: each break by someone allowed to attack takes 1 HP, and at 0 their faction captures the outpost, with its land, storage box contents and coins. A flag that isn't hit for 5 minutes (`outposts.flagRegenMinutes`) repairs fully. Any faction except the owner's allies can attack a flag **at any time**, whether or not the owner is at war or the outpost is supplied. Flames above a flag mean it's in danger: damaged, out of coins, or its owner at war. Wars no longer have an outpost capture zone: they go straight to the faction table. (An attacker's outpost still shields their own faction table from being counter-captured, so defenders may want to take it first.) Outposts can't be moved or removed during a war or while the flag is damaged.

**Levels.** Officers can upgrade an outpost from its flag or in Faction Upgrades, paying in warbonds (`outposts.upgradeWarbonds` × level, so 3 then 6 by default):

| Level | Land | Flag HP | Storage box |
|---|---|---|---|
| 1 | 1 chunk | 50 | 15 kinds of item × 150 each |
| 2 | 5 chunks (plus shape) | 75 | 20 kinds × 300 |
| 3 | 9 chunks (3×3) | 100 | 25 kinds × 600 |

**Storage box.** A table with a computer on it, two blocks tall, placed beside the flag. Its screen shows how many kinds of item it holds out of the max, with a fill bar, and its 5 biggest stacks. Right-click it to open. Click an item in your inventory to put that stack in (shift-click: all of that item); click a stored item to take a stack (shift-click: as much as fits, right-click: one). Putting items in and taking them out both need the "use storage" permission. **Moving** an outpost needs its storage box emptied of items first (coins can stay). It packs the outpost up: the Outpost item you get back carries its name, level and coins (shown in its tooltip), and placing it sets the outpost up again as it was. Don't lose the item. If an outpost is **deleted** or falls apart, its items go to the faction item vault (Faction Table → Vault), anything that doesn't fit is delivered to the faction owner through the market (My Market), and its coins go to the faction coin vault; the chat message says how much went where.

## Faction item vault

The faction table's Vault tab has an **Item vault** button: a normal inventory shared by the whole faction, like a big chest. It starts with **27 slots** (`itemVault.slots`) and scrolls when it gets bigger. Everyone who has it open sees changes straight away.

The first slots are **secure** (shown in gold), one to start with. Items in secure slots are never lost or looted: not in a war, not when the faction's core is taken. When the core is taken they always go to the outpost the faction falls back to (or stay in the secure slots if the outpost's box is full).

Each upgrade (Faction Upgrades tab) adds **10 slots** (`itemVault.slotsPerLevel`) and **one more secure slot**, up to level 6 (`itemVault.maxLevel`). Putting items in and taking them out both need the "use storage" permission. When a faction disbands, its item vault is delivered to the owner through the market. Items that hold other items (shulker boxes, bundles, backpacks, chests carrying their contents...) can't go in the item vault or an outpost storage box; more can be added with the item tag `adminsfactions:not_allowed_in_storage`.

## Warbonds and Faction Upgrades

**Warbonds** pay for every faction upgrade. A faction gets 5 for winning a war (`warbonds.warWinReward`), put straight into its item vault; operators can also hand them out through the server shop, monument loot or supply drops.

The faction table's **Upgrades** tab lists everything a faction can improve, with the current and next level and the price. Officers buy upgrades with two clicks; warbonds come from the item vault first, then the buyer's inventory.

| Upgrade | Levels | Price (warbonds, by default) | Effect |
|---|---|---|---|
| Item vault | 1-6 | 2 × level | +10 slots and +1 secure slot |
| Land | no limit | 3, then × 1.5 each time (`warbonds.landUpgradeWarbonds`, `warbonds.landUpgradeRamp`) | claim one chunk further from the faction table |
| Outpost slots | 1-3 (two upgrades) | 5 × slots | one more outpost |
| Meter efficiency | 1-10 | 3 × level (`meters.efficiencyWarbonds`) | +50% a level (`meters.efficiencyPercentPerLevel`) to your meters' buy rate, battery/tank size and coin storage; level 10 is 5.5× |
| Extractor quantity | 1-3 | 5 × extractors (`seams.minersUpgradeWarbonds`) | one more Seam Extractor per seam |
| Seam Speed / Quantity / Efficiency | 0-4, pick one | 4 × level (`seams.pathUpgradeWarbonds`) | see Seams; the other two lock |

**Land** is claimed in a radius around the faction table: up to 3 chunks away in every direction to start (`warbonds.landBaseRadius`), and one further with each land upgrade. Outposts claim their own land wherever they are.
| Each outpost | 1-3 | 3 × level | more land, a stronger flag and a bigger storage box |

## Seams

Some chunks have a hidden **seam**: a fake ore vein that only a **Seam Extractor** can mine. Seams are spread out: between a seam and its neighbours there are always 5 to 7 empty chunks (configurable as `seams.minEmptyChunks` / `seams.maxEmptyChunks`; `seams.spawnChance` below 100 leaves some out, making gaps bigger). The ore isn't in the world and has nothing to do with the real ores nearby.

**How a seam is rolled.** A `Random` seeded with the world seed xor the chunk coordinates (chunk x in the high 32 bits, chunk z in the low) first picks an ore, then rolls 1-100 for the seam's richness. The ore is any ore block that belongs to that dimension: blocks in the `c:ores` tag, plus ore blocks a mod left untagged but named like an ore (`*_ore`, or `ore_*` as NTM names them) (vanilla and modded; stone/deepslate ores in the Overworld, netherrack ores in the Nether, end stone ores in the End), minus the admin blacklist. The same seed always gives the same seams, and a seam is only saved once it's mined or edited.

| Richness | Chance | Ore before it drops a tier | Speed |
|---|---|---|---|
| Overflowing | 10% | 2,000 | 1 ore / 15 s |
| Rich | 20% | 3,000 | 1 ore / 30 s |
| Middling | 30% | 4,000 | 1 ore / 45 s |
| Poor | 20% | 5,000 | 1 ore / 60 s |
| Depleted | 10% | none | - |
| Endless | 10% | never runs out | 10% Overflowing, 20% Rich, 30% Middling, 40% Poor speed |

Mining a seam out drops it one tier (Overflowing → Rich → Middling → Poor → Depleted), so an Overflowing seam gives 14,000 ore in all.

**Seam Viewer.** Right-click it to scan the 9×9 chunks around you: a map of the terrain shows each seam's ore with a tint for its richness (hover a chunk for how much is left). Chunks without a seam show just the terrain, depleted seams are greyed out, and chunks you haven't scanned are dimmed. It's the only way to find seams; placing an extractor doesn't tell you anything. Like a map mod, the viewer keeps every chunk scanned, saved with the world, and there's no cooldown between scans. It's **shared by your faction**: every member's scans go on one map (per dimension), and scanned seams also show on the **faction table's territory map** with their ore and details on hover. Players without a faction keep their own map, and it joins their faction's when they join one. Drag the map to look at places you've been. Far chunks show what they were when you last scanned them and only update when you scan them again.

**Placement preview.** Holding any of the mod's blocks that take more than one space (Seam Extractor, Faction Table, Market, Currency Exchange, meters, care package) shows a see-through copy where it would go, with every space outlined: green when it fits, red where something is in the way.

**Seam Extractor.** A 2×2×3 laser drill: it needs a clear space 2 wide, 2 deep and 3 tall, and reaches away from you from the block you place it on. Its front (the side facing you when you place it) has a screen showing the ore, extraction progress, charge and state. Power only goes in through the **energy sockets** on its left and right sides (one per block, where a cable attaches). Hoppers and item pipes can take ore out of any part of it. While it mines, its rings spin up and a laser cuts into the ground; the beam's colour shows the faction's seam upgrade path (yellow: none, red: Speed, blue: Quantity, green: Efficiency) and it gets wider with each upgrade level. Each upgrade level also lights the lens emitters on one more ring (from the top down), which fire small amplifier lasers into the main beam; at the top level they burn brighter. It can only be placed on your faction's land (a claimed chunk or an outpost's land) and stops if that land is lost. Its screen shows the ore, richness, how much is left, speed and power. It needs **50 FE/t (1,000 FE a second)** while mining (`seams.extractorPowerPerTick`, doubled by each seam upgrade level) from an FE cable on one of its energy ports. The supply has to be steady: while the power runs short, the extraction in progress winds back down at twice the speed it fills, like a furnace that goes out (its 200-tick buffer covers short gaps). It puts ore in 9 output slots that hoppers and pipes can empty. You need to be in a faction to place one, and a faction can have 1 extractor per seam to start (up to 3 with **Extractor quantity**). Only the owning faction can open it. If the owning faction disbands, the extractor becomes unowned: the faction that claims its chunk takes it over automatically, or the first faction member to right-click it does (as long as that faction has room for another extractor on the seam).

**Seam upgrades** (Faction Upgrades): pick **one** of three paths, 4 levels each. Buying a level of one locks the other two. Every level doubles the power all of the faction's extractors use (level 4 = 16,000 FE/t).

| Path | Per level | At level 4 |
|---|---|---|
| Speed | +1× mining speed, +0.25× seam depletion | 5× faster, seam depletes 2× faster: it gives half the ore before running out |
| Quantity | +0.25 bonus ore per extraction, normal depletion | 2 ore per extraction |
| Efficiency | +1× slower depletion, +0.25× slower mining | seam lasts 5× longer, mining 2× slower |

**Admin panel → Seams** shows a live 9×9 seam map around you; drag it to look around. Click a chunk to select it, then make it richer or poorer, delete it, reset it to what the seed gives, or spawn a new seam of any ore, richness and endless or not. It also edits the **ore blacklist** (item ids, or `modid:*` for a whole mod). **Settings → Seam settings** has every chance, the endless speed chances, seconds per ore, ore per tier, extractor power and the upgrade prices. Changing chances only affects seams nobody has mined or edited yet.

## Losing your core

When a faction's core is taken (losing an All-Out War, or the winner choosing to take everything after a conquest) and it still has an outpost, it isn't disbanded. Instead:

- It **falls back to an outpost** (its highest level one). The winners take all its other land.
- **Its items:** items in **secure slots** always make it to that outpost's storage box (or stay safely in the secure slots if the box is full). Every other slot rolls on its own: a **25%** chance (`warbonds.coreTransferPercent`) that its whole stack makes it (as far as the box has room). Everything else stays behind.
- **The old faction table becomes loot.** The winning faction can open it to see and take everything left behind: all the coins that were in the faction vault (they go to the looter's faction vault) and the items that didn't make it. When it's empty it crumbles.
- **A new core.** The owner gets a new Faction Table (in their inventory, or in My Market if offline). It has to be placed on the faction's own land, like the outpost, and that chunk becomes the new core. Nobody can declare war on them until they place it.
- Every member is told which outpost they fell back to, what made it across and what was left behind. The winners are told where to loot.

A faction with no outpost is still disbanded as before.

## Chunkloading deeds

Select one of your faction's chunks (including the core chunk) on the Faction Table's territory map to see its **Chunkloading** section. Officers and above can buy a **Chunkloading Deed** for that chunk from the faction vault. The deed costs twice what the chunk originally cost; chunks stored with no price use the base claim cost. Once bought, chunkloading is switched on and the chunk stays loaded and ticking even with no players nearby. Its upkeep is doubled while chunkloading is on.

The checkbox under the deed turns chunkloading off, which puts the chunk back to normal upkeep, and on again at no extra cost. Chunkloaded chunks have a purple inner outline on the map and a ⚡ in the deed list. Chunkloading pauses while the faction vault is empty (claims unprotected) and resumes once upkeep is paid. Unclaiming a chunk, or losing it in a war, removes its deed.

Deeds and their on/off state are saved with the faction's claims. Buying a deed or flipping the checkbox saves the world data straight away. On every server start, force-loaded chunks are rebuilt from that saved data, so restarts and crashes keep chunkloading exactly as it was last set.

The market notifies online players when a listing sells or a buy order is filled. On login, players receive a summary of unclaimed sale proceeds and pending purchases. While claims remain, an hourly reminder shows the number of sales, their total value, and the number of pending purchases. Reminder timing persists across server restarts.

Use the controller to edit its three loot pools:

- Slots 1-18: Supply loot
- Slots 19-36: Ammo loot
- Slots 37-54: Gun loot

`/factions monument` lists each location and its live loot timer. Operators use `/faction monument edit` to open the monument browser, then click a monument to edit its name, tier, respawn timer, loot pools, designated chunks, or delete it. Right-clicking a Monument Controller opens the same panel directly. The controller chunk cannot be removed, chunks cannot overlap another monument, and chunks containing linked crates or Ore Generators must be cleared before removal.

Loot timers pause while any player is inside. The countdown runs at half speed on low population, reaches the configured base time at 10 online players, and caps at double speed at 20 or more players. Players cannot place or destroy blocks in a monument, and explosions do not damage it.

**Ore Generators** can only be placed by operators inside a monument and link automatically. They begin as unbreakable cobblestone. Enable operator bypass with `/faction bypass`, then use a vanilla or modded ore block on the generator to configure it; the selected ore activates immediately. It drops through the ore's native loot table when mined (including Fortune and Silk Touch), returns to unbreakable cobblestone, and regenerates on subsequent monument loot timers. Ordinary monument blocks cannot be placed or broken unless bypass is enabled.

## Monument guards (Rogue PMCs)

Each refill spawns Sniper, Assault, Heavy Gunner and Light Gunner guards in squads of up to five. Set how many of each class spawn, and each class's difficulty (Easy to Extreme), on the monument's **AI Roster** tab. Their weapons and armor are set on **AI Gear**.

Guards never know where you are unless they see or hear you:

- **Vision:** only inside their field of view and with line of sight. A detection meter fills faster when you are close, in the centre of their view, moving or sprinting, and in daylight. Crouching, going prone, staying still and darkness all slow it down. A glimpse makes them suspicious and they turn to look.
- **Hearing:** footsteps (sprinting is louder, sneaking is silent), landing, doors, crates, placing or breaking blocks, eating, gunfire, explosions, and bullets hitting near them. Quiet noises make a guard stop, look, and creep over to check while its squad freezes. Loud noises put the whole squad on alert.
- **States:** calm → suspicious → searching → combat. A guard only shoots once it has spotted you and turned to face you. If it loses sight of you it searches your last known position and the area around it, then calms down.
- **Squads:** a guard that spots you calls out your *last known position*. Assault and Light Gunners flank, Heavy Gunners suppress, and Snipers hold overwatch. Other squads hear the gunfire and move in.

Operators tune each class with `/faction ai edit`. It has four 0-100 sliders (Accuracy, Reaction, Awareness, Aggression) plus engagement and preferred range, and a live preview that explains the result at each difficulty. Each difficulty level above or below Normal shifts the sliders by 15.

The **AI Drops** tab holds the items guards can drop on death. Right-click an item to set its count, rarity and **Dropped by** classes, so for example a Heavy Gunner can drop an item that a Sniper never does. With no item selected, the panel summarises what each class can drop.


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
