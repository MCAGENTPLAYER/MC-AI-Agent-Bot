package com.aibot.mod;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.Tags;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * 环境扫描器 — 从 bot 的视觉角度扫描地表环境
 * 不扫描地下，只扫描当前可见的表面，模拟真人视野。
 */
public class EnvironmentScanner {
    private static final Logger LOGGER = AiBotMod.LOGGER;

    /** 默认扫描半径 */
    public static final int DEFAULT_RADIUS = 20;

    // === 扫描结果 ===

    public static class ScanResult {
        public int totalColumns;
        public int scannedBlocks;

        public String biome = "";
        public String timeOfDay = "";
        public String weather = "";
        public int skyLight = 15;
        public int blockLight = 0;

        public Map<String, Integer> surfaceBlocks = new LinkedHashMap<>();

        public Map<String, Integer> trees = new LinkedHashMap<>();
        public Map<String, Integer> exposedOres = new LinkedHashMap<>();
        public Map<String, Integer> crops = new LinkedHashMap<>();
        public Map<String, Integer> flowers = new LinkedHashMap<>();

        public Map<String, Integer> workstations = new LinkedHashMap<>();
        public Map<String, Integer> containers = new LinkedHashMap<>();
        public Map<String, Integer> furniture = new LinkedHashMap<>();
        public Map<String, Integer> lights = new LinkedHashMap<>();
        public boolean hasBuilding = false;

        public boolean hasWater = false;
        public boolean hasLava = false;
        public boolean hasCaveEntrance = false;
        public boolean hasCliff = false;
        public int caveEntrances = 0;

        public Map<String, Integer> animals = new LinkedHashMap<>();
        public Map<String, Integer> monsters = new LinkedHashMap<>();
        public boolean hasPlayer = false;

        public int totalBuildings = 0;
        public int totalAnimals = 0;
        public int totalMonsters = 0;
    }

    // === 入口 ===

    public static ScanResult scan(Level level, BlockPos center, int radius) {
        ScanResult result = new ScanResult();
        if (level == null || center == null) return result;

        try {
            scanEnvironment(level, center, result);
            scanSurface(level, center, radius, result);
            scanEntities(level, center, radius, result);

            LOGGER.debug("[EnvironmentScanner] 扫描完成: {} 列, {} 方块, {} 动物, {} 怪物",
                result.totalColumns, result.scannedBlocks, result.totalAnimals, result.totalMonsters);
        } catch (Exception e) {
            LOGGER.error("[EnvironmentScanner] 扫描异常: {}", e.getMessage());
        }
        return result;
    }

    // === 环境信息 ===

    private static void scanEnvironment(Level level, BlockPos center, ScanResult result) {
        Biome biome = level.getBiome(center).value();
        result.biome = getBiomeName(biome);

        long dayTime = level.getDayTime() % 24000;
        result.timeOfDay = dayTime < 13000 ? "白天" : "夜晚";

        if (level.isThundering()) result.weather = "雷暴";
        else if (level.isRaining()) result.weather = "下雨";
        else result.weather = "晴天";

        result.skyLight = level.getBrightness(LightLayer.SKY, center);
        result.blockLight = level.getBrightness(LightLayer.BLOCK, center);
    }

    // === 地表扫描 ===

    private static void scanSurface(Level level, BlockPos center, int radius, ScanResult result) {
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();
        int topY = Math.min(cy + 15, level.getMaxBuildHeight());
        int bottomY = Math.max(cy - 10, level.getMinBuildHeight());

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                result.totalColumns++;
                BlockPos found = findSurfaceBlock(level, cx + dx, topY, cz + dz, bottomY);
                if (found == null) continue;
                result.scannedBlocks++;
                categorizeBlock(level.getBlockState(found), found, cx, cy, cz, level, result);
            }
        }
    }

    private static BlockPos findSurfaceBlock(Level level, int x, int topY, int z, int bottomY) {
        for (int y = topY; y >= bottomY; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || isReplaceablePlant(state)) continue;
            return pos;
        }
        return null;
    }

    private static boolean isReplaceablePlant(BlockState state) {
        Block b = state.getBlock();
        return b instanceof LeavesBlock
            || b instanceof TallGrassBlock
            || b instanceof DoublePlantBlock
            || b instanceof VineBlock
            || b instanceof DeadBushBlock;
    }

    // === 方块分类 ===

    private static void categorizeBlock(BlockState state, BlockPos pos,
                                        int cx, int cy, int cz, Level level, ScanResult result) {
        Block block = state.getBlock();
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
        if (id == null) return;
        String path = id.getPath();
        String name = id.toString();

        // === 1. 树木 ===
        if (state.is(BlockTags.LOGS)) {
            result.trees.merge(simplifyLogName(name), 1, Integer::sum);
            return;
        }

        // === 2. 裸露矿石 ===
        if (state.is(BlockTags.COAL_ORES)) { result.exposedOres.merge("煤矿", 1, Integer::sum); return; }
        if (state.is(BlockTags.IRON_ORES)) { result.exposedOres.merge("铁矿", 1, Integer::sum); return; }
        if (state.is(BlockTags.COPPER_ORES)) { result.exposedOres.merge("铜矿", 1, Integer::sum); return; }
        if (state.is(BlockTags.GOLD_ORES)) { result.exposedOres.merge("金矿", 1, Integer::sum); return; }
        if (state.is(BlockTags.LAPIS_ORES)) { result.exposedOres.merge("青金石矿", 1, Integer::sum); return; }
        if (state.is(BlockTags.REDSTONE_ORES)) { result.exposedOres.merge("红石矿", 1, Integer::sum); return; }
        if (state.is(BlockTags.DIAMOND_ORES)) { result.exposedOres.merge("钻石矿", 1, Integer::sum); return; }
        if (state.is(BlockTags.EMERALD_ORES)) { result.exposedOres.merge("绿宝石矿", 1, Integer::sum); return; }
        if (state.is(Tags.Blocks.ORES)) {
            String oreName = getBlockDisplayName(id);
            result.exposedOres.merge(oreName.isEmpty() ? name : oreName, 1, Integer::sum);
            return;
        }

        // === 3. 作物 ===
        if (state.is(BlockTags.CROPS)) {
            result.crops.merge(getCropName(state), 1, Integer::sum);
            return;
        }
        if (block instanceof CropBlock) {
            result.crops.merge(getBlockDisplayName(id) + (isMature(state) ? "(成熟)" : "(生长中)"), 1, Integer::sum);
            return;
        }
        if (state.is(BlockTags.BEE_GROWABLES) || block instanceof StemBlock) {
            result.crops.merge(getBlockDisplayName(id), 1, Integer::sum);
            return;
        }
        if (block instanceof FarmBlock) {
            int moisture = state.getValue(BlockStateProperties.MOISTURE);
            result.surfaceBlocks.merge("耕地" + (moisture == 7 ? "(湿润)" : ""), 1, Integer::sum);
            return;
        }

        // === 4. 花/特殊植物 ===
        if (block instanceof FlowerBlock || block instanceof FlowerPotBlock) {
            result.flowers.merge(getBlockDisplayName(id), 1, Integer::sum);
            return;
        }
        if (block instanceof SweetBerryBushBlock || block instanceof CactusBlock
            || block instanceof SugarCaneBlock
            || block instanceof NetherWartBlock || block instanceof CocoaBlock) {
            result.crops.merge(getBlockDisplayName(id), 1, Integer::sum);
            return;
        }
        if (block instanceof PumpkinBlock || block instanceof CarvedPumpkinBlock) {
            result.crops.merge("南瓜", 1, Integer::sum);
            return;
        }
        if (path.contains("melon")) {
            result.crops.merge("西瓜", 1, Integer::sum);
            return;
        }

        // === 5. 人工建筑 ===
        if (isWorkstation(block, id)) {
            result.workstations.merge(getBlockDisplayName(id), 1, Integer::sum);
            result.hasBuilding = true; result.totalBuildings++;
            return;
        }
        if (isContainer(block, id)) {
            result.containers.merge(getBlockDisplayName(id), 1, Integer::sum);
            result.hasBuilding = true; result.totalBuildings++;
            return;
        }
        if (block instanceof BedBlock) {
            result.furniture.merge("床", 1, Integer::sum);
            result.hasBuilding = true; result.totalBuildings++;
            return;
        }
        if (block instanceof DoorBlock) {
            result.furniture.merge("门", 1, Integer::sum);
            result.hasBuilding = true; result.totalBuildings++;
            return;
        }
        if (block instanceof FenceBlock || block instanceof FenceGateBlock) {
            result.furniture.merge("栅栏/墙", 1, Integer::sum);
            result.hasBuilding = true; result.totalBuildings++;
            return;
        }
        if (block instanceof TorchBlock || block instanceof LanternBlock || block instanceof EndRodBlock) {
            result.lights.merge("火把/灯", 1, Integer::sum);
            result.hasBuilding = true; result.totalBuildings++;
            return;
        }
        if (block instanceof StairBlock || block instanceof SlabBlock) {
            result.totalBuildings++;
            return;
        }
        if (isPlacedBlock(block, id)) {
            result.totalBuildings++;
            result.hasBuilding = true;
            return;
        }

        // === 6. 水体/熔岩 ===
        if (!state.getFluidState().isEmpty()) {
            if (path.contains("lava")) result.hasLava = true;
            else result.hasWater = true;
            return;
        }

        // === 7. 普通地表分类 ===
        String surfaceName = getSurfaceCategory(state, block, id);
        if (surfaceName != null) {
            result.surfaceBlocks.merge(surfaceName, 1, Integer::sum);
        }

        // === 8. 洞穴入口检测 ===
        if (isCaveEntrance(level, pos, cy)) {
            result.caveEntrances++;
            result.hasCaveEntrance = true;
        }

        // === 9. 悬崖检测 ===
        if (Math.abs(pos.getY() - cy) >= 5) {
            result.hasCliff = true;
        }
    }

    // === 工作站/容器/人工方块 ===

    private static boolean isWorkstation(Block block, ResourceLocation id) {
        return block instanceof CraftingTableBlock
            || block instanceof FurnaceBlock || block instanceof AbstractFurnaceBlock
            || block instanceof AnvilBlock
            || block instanceof BrewingStandBlock
            || block instanceof CauldronBlock
            || block instanceof GrindstoneBlock
            || block instanceof StonecutterBlock
            || block instanceof SmithingTableBlock
            || block instanceof LoomBlock
            || block instanceof ComposterBlock
            || block instanceof BarrelBlock
            || id.getPath().contains("campfire")
            || id.getPath().contains("workbench");
    }

    private static boolean isContainer(Block block, ResourceLocation id) {
        return block instanceof ChestBlock
            || block instanceof BarrelBlock
            || block instanceof ShulkerBoxBlock
            || id.getPath().contains("crate")
            || id.getPath().contains("cabinet")
            || id.getPath().contains("drawer");
    }

    private static boolean isPlacedBlock(Block block, ResourceLocation id) {
        String path = id.getPath();
        return path.contains("planks")
            || path.matches(".*stripped_.*log.*")
            || path.contains("_bricks")
            || path.contains("concrete")
            || path.contains("terracotta")
            || (path.contains("glass") && !path.contains("stained_glass"))
            || path.contains("wool")
            || path.contains("carpet")
            || path.contains("bookshelf")
            || path.contains("chain")
            || block instanceof IronBarsBlock;
    }

    // === 地表分类 ===

    private static String getSurfaceCategory(BlockState state, Block block, ResourceLocation id) {
        String path = id.getPath();

        if (state.is(BlockTags.BASE_STONE_OVERWORLD)) {
            if (path.contains("deepslate")) return "深板岩";
            if (path.contains("diorite")) return "闪长岩";
            if (path.contains("andesite")) return "安山岩";
            if (path.contains("granite")) return "花岗岩";
            if (path.contains("tuff")) return "凝灰岩";
            if (path.contains("calcite")) return "方解石";
            return "石头";
        }
        if (block instanceof GravelBlock) return "沙砾";
        if (block instanceof SandBlock) return path.contains("red_sand") ? "红沙" : "沙子";
        if (block instanceof GrassBlock || block instanceof MyceliumBlock) return "草地";
        if (block instanceof SnowLayerBlock) return "雪地";
        if (block instanceof IceBlock) return "冰面";
        if (block instanceof SoulSandBlock) return "灵魂沙";
        if (block instanceof NetherrackBlock) return "下界岩";

        // 用 path 替代不存在的 Block 子类
        if (path.contains("dirt") || path.contains("podzol") || path.contains("rooted_dirt")) return "泥土";
        if (path.contains("snow_block") || path.contains("powder_snow")) return "雪地";
        if (path.contains("blue_ice") || path.contains("packed_ice") || path.contains("frosted_ice")) return "冰面";
        if (path.contains("clay")) return "粘土";
        if (path.contains("soul_soil")) return "灵魂沙";
        if (path.contains("end_stone")) return "末地石";
        if (path.contains("moss_block") || path.contains("mossy")) return "苔藓";
        if (path.contains("mushroom")) return "蘑菇";
        if (path.contains("cobblestone") || path.contains("cobble")) return "圆石";
        if (path.contains("nether_brick")) return "下界砖";
        if (block instanceof HayBlock) return "干草捆";

        return null;
    }

    // === 实体扫描 ===

    private static void scanEntities(Level level, BlockPos center, int radius, ScanResult result) {
        AABB aabb = new AABB(center).inflate(radius);
        var entities = level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, aabb,
            e -> e instanceof LivingEntity && e.isAlive());

        for (var entity : entities) {
            if (entity instanceof Player) {
                var mc = Minecraft.getInstance();
                if (entity == mc.player) continue;
                result.hasPlayer = true;
                continue;
            }
            if (entity instanceof Animal) {
                String name = entity.getType().getDescription().getString();
                result.animals.merge(name, 1, Integer::sum);
                result.totalAnimals++;
            } else if (entity instanceof Enemy) {
                String name = entity.getType().getDescription().getString();
                result.monsters.merge(name, 1, Integer::sum);
                result.totalMonsters++;
            }
        }
    }

    // === 洞穴入口检测 ===

    private static boolean isCaveEntrance(Level level, BlockPos pos, int botY) {
        if (Math.abs(pos.getY() - botY) > 6) return false;
        BlockState above = level.getBlockState(pos.above());
        if (!above.isAir()) return false;

        int airNeighbors = 0;
        int wallNeighbors = 0;
        for (var dir : List.of(
            pos.north(), pos.south(), pos.east(), pos.west(),
            pos.above(), pos.below()
        )) {
            BlockState s = level.getBlockState(dir);
            if (s.isAir()) airNeighbors++;
            else if (s.is(BlockTags.BASE_STONE_OVERWORLD)) wallNeighbors++;
        }
        return airNeighbors >= 2 && wallNeighbors >= 1;
    }

    // === 工具方法 ===

    private static String simplifyLogName(String blockId) {
        String path = blockId.contains(":") ? blockId.substring(blockId.indexOf(':') + 1) : blockId;
        if (path.startsWith("stripped_")) path = path.substring(9);
        if (path.endsWith("_log")) path = path.substring(0, path.length() - 4);
        else if (path.endsWith("_wood")) path = path.substring(0, path.length() - 5);
        return path + "原木";
    }

    private static String getBlockDisplayName(ResourceLocation id) {
        var block = ForgeRegistries.BLOCKS.getValue(id);
        if (block == null) return id.getPath().replace('_', ' ');
        try {
            return net.minecraft.network.chat.Component.translatable(block.getDescriptionId()).getString();
        } catch (Exception e) {
            return id.getPath().replace('_', ' ');
        }
    }

    private static String getCropName(BlockState state) {
        Block block = state.getBlock();
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
        if (id == null) return "作物";
        String name = getBlockDisplayName(id);
        String maturity = "";
        if (state.hasProperty(BlockStateProperties.AGE_7))
            maturity = state.getValue(BlockStateProperties.AGE_7) >= 7 ? "(成熟)" : "(生长中)";
        else if (state.hasProperty(BlockStateProperties.AGE_3))
            maturity = state.getValue(BlockStateProperties.AGE_3) >= 3 ? "(成熟)" : "(生长中)";
        else if (state.hasProperty(BlockStateProperties.AGE_2))
            maturity = state.getValue(BlockStateProperties.AGE_2) >= 2 ? "(成熟)" : "(生长中)";
        return name + maturity;
    }

    private static boolean isMature(BlockState state) {
        if (state.hasProperty(BlockStateProperties.AGE_7)) return state.getValue(BlockStateProperties.AGE_7) >= 7;
        if (state.hasProperty(BlockStateProperties.AGE_3)) return state.getValue(BlockStateProperties.AGE_3) >= 3;
        if (state.hasProperty(BlockStateProperties.AGE_2)) return state.getValue(BlockStateProperties.AGE_2) >= 2;
        if (state.hasProperty(BlockStateProperties.AGE_1)) return state.getValue(BlockStateProperties.AGE_1) >= 1;
        return true;
    }

    private static String getBiomeName(Biome biome) {
        ResourceLocation id = ForgeRegistries.BIOMES.getKey(biome);
        if (id == null) return "未知";
        return switch (id.getPath()) {
            case "plains" -> "平原";
            case "forest" -> "森林";
            case "birch_forest" -> "白桦林";
            case "dark_forest" -> "黑森林";
            case "flower_forest" -> "繁花森林";
            case "taiga" -> "针叶林";
            case "old_growth_taiga" -> "古针叶林";
            case "old_growth_pine_taiga" -> "古松针叶林";
            case "jungle" -> "丛林";
            case "bamboo_jungle" -> "竹林";
            case "sparse_jungle" -> "稀疏丛林";
            case "savanna" -> "热带草原";
            case "savanna_plateau" -> "热带高原";
            case "desert" -> "沙漠";
            case "badlands" -> "恶地";
            case "wooded_badlands" -> "林地恶地";
            case "swamp" -> "沼泽";
            case "mangrove_swamp" -> "红树林沼泽";
            case "snowy_plains" -> "雪原";
            case "snowy_taiga" -> "雪针叶林";
            case "ice_spikes" -> "冰刺之地";
            case "ocean" -> "海洋";
            case "deep_ocean" -> "深海";
            case "cold_ocean" -> "冷水海洋";
            case "frozen_ocean" -> "冻洋";
            case "river" -> "河流";
            case "frozen_river" -> "冻河";
            case "beach" -> "沙滩";
            case "snowy_beach" -> "雪滩";
            case "stony_shore" -> "石岸";
            case "mushroom_fields" -> "蘑菇岛";
            case "dripstone_caves" -> "钟乳石洞穴";
            case "lush_caves" -> "繁茂洞穴";
            case "deep_dark" -> "深暗之域";
            case "meadow" -> "草甸";
            case "cherry_grove" -> "樱花树林";
            case "grove" -> "雪林";
            case "snowy_slopes" -> "积雪山坡";
            case "stony_peaks" -> "石峰";
            case "jagged_peaks" -> "尖峭山峰";
            case "frozen_peaks" -> "冰封山峰";
            case "nether_wastes" -> "下界荒地";
            case "crimson_forest" -> "绯红森林";
            case "warped_forest" -> "诡异森林";
            case "soul_sand_valley" -> "灵魂沙峡谷";
            case "basalt_deltas" -> "玄武岩三角洲";
            case "the_end" -> "末地";
            case "end_highlands" -> "末地高地";
            case "end_midlands" -> "末地内陆";
            case "small_end_islands" -> "末地小型岛屿";
            case "end_barrens" -> "末地荒地";
            default -> id.getPath().replace('_', ' ');
        };
    }

    // === 格式化输出 ===

    public static String formatForAI(ScanResult result) {
        if (result == null) return "（环境扫描失败）";

        StringBuilder sb = new StringBuilder();
        sb.append("## 当前环境\n");
        sb.append("- 群系: ").append(result.biome).append("\n");
        sb.append("- 时间: ").append(result.timeOfDay).append("\n");
        sb.append("- 天气: ").append(result.weather).append("\n");
        if (result.skyLight < 7) {
            sb.append("- 光照: 昏暗（天空光 ").append(result.skyLight).append("）\n");
        }

        if (!result.trees.isEmpty()) {
            sb.append("- 树木: ");
            result.trees.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> sb.append(e.getValue()).append("棵").append(e.getKey()).append("，"));
            sb.setLength(sb.length() - 1);
            sb.append("\n");
        }

        if (!result.exposedOres.isEmpty()) {
            sb.append("- 裸露矿石: ");
            var oreStr = new ArrayList<String>();
            result.exposedOres.forEach((name, count) -> oreStr.add(name + "×" + count));
            sb.append(String.join("、", oreStr)).append("\n");
        }

        if (!result.crops.isEmpty()) {
            sb.append("- 农田: ");
            var cropStr = new ArrayList<String>();
            result.crops.forEach((name, count) -> cropStr.add(name + "×" + count));
            sb.append(String.join("、", cropStr)).append("\n");
        }
        if (!result.flowers.isEmpty()) {
            sb.append("- 花草: ");
            result.flowers.forEach((name, count) -> sb.append(name).append("×").append(count).append(" "));
            sb.append("\n");
        }

        if (result.hasBuilding) {
            if (!result.workstations.isEmpty()) {
                sb.append("- 工作台: ");
                var wsStr = new ArrayList<String>();
                result.workstations.forEach((name, count) -> wsStr.add(name + "×" + count));
                sb.append(String.join("、", wsStr)).append("\n");
            }
            if (!result.containers.isEmpty()) {
                sb.append("- 容器: ");
                var ctStr = new ArrayList<String>();
                result.containers.forEach((name, count) -> ctStr.add(name + "×" + count));
                sb.append(String.join("、", ctStr)).append("\n");
            }
            if (!result.furniture.isEmpty()) {
                result.furniture.forEach((name, count) ->
                    sb.append("- ").append(name).append(": ").append(count).append("\n"));
            }
        }

        if (!result.animals.isEmpty()) {
            sb.append("- 动物: ");
            result.animals.forEach((name, count) -> sb.append(name).append("×").append(count).append(" "));
            sb.append("\n");
        }
        if (!result.monsters.isEmpty()) {
            sb.append("- ⚠️ 怪物: ");
            result.monsters.forEach((name, count) -> sb.append(name).append("×").append(count).append(" "));
            sb.append("\n");
        }

        var features = new ArrayList<String>();
        if (result.hasWater) features.add("水源");
        if (result.hasLava) features.add("熔岩");
        if (result.hasCaveEntrance) features.add("洞穴入口×" + result.caveEntrances);
        if (result.hasCliff) features.add("悬崖/陡坡");
        if (!features.isEmpty()) {
            sb.append("- 地形: ").append(String.join("、", features)).append("\n");
        }

        if (!result.surfaceBlocks.isEmpty()) {
            var dominant = result.surfaceBlocks.entrySet().stream()
                .max(Map.Entry.comparingByValue());
            if (dominant.isPresent()) {
                sb.append("- 地表以").append(dominant.get().getKey()).append("为主\n");
            }
        }

        return sb.toString();
    }
}
