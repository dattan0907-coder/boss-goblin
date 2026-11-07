# Goblin Boss – NeoForge 1.21.1 (GeckoLib)

Port từ pack MythicMobs + ModelEngine "goblin_boss" của **Amonde** sang mod NeoForge 1.21.1.

## Build bằng GitHub Actions
Push project lên repo GitHub mới -> tab **Actions** -> workflow *Build mod* -> tải artifact `goblin_boss-jar`.

## Build trên máy
Cần JDK 21 và internet (để tải NeoForge + GeckoLib).

    gradle wrapper --gradle-version 8.10.2     # chỉ cần chạy 1 lần để tạo gradlew
    ./gradlew build                            # jar nằm trong build/libs/
    ./gradlew runClient                        # chạy thử trong game

Nếu Gradle báo không tìm thấy GeckoLib, đổi `geckolib_version` trong `gradle.properties`
sang bản mới nhất cho 1.21.1 (xem https://github.com/bernie-ng/GeckoLib/releases).

## Chạy
Cài `goblin_boss-1.0.0.jar` + **GeckoLib (NeoForge 1.21.1)** vào thư mục mods.
Gọi boss: `/summon goblin_boss:goblin_boss` hoặc dùng Spawn Egg (tab Spawn Eggs).

## Đã chuyển từ pack gốc
| Gốc (MythicMobs/ModelEngine) | Trong mod |
|---|---|
| `.bbmodel` | `geo/goblin_boss.geo.json`, `animations/goblin_boss.animation.json`, texture PNG (convert bằng script) |
| Health 150, speed 0.22, knockback resist 0.75 | Attributes trong `GoblinBossEntity.createAttributes()` |
| BossBar đỏ, range 50, fog, darken sky | `ServerBossEvent` |
| atk1–atk4 (phase 1), atk5 (gầm), atk11–atk44 (frenzy) | `GoblinBossEntity` (mỗi 20 tick random skill, cooldown/gcd/damage/delay giữ nguyên số trong YAML) |
| Vỡ giáp ở 90% / 75% / 50% | Ẩn bone armor_* + particle + sound |
| Frenzy: mắt đỏ, chạy nhanh (x1.7), animation fastwalk | `FRENZY_EYES`, attribute modifier |
| Sound (strider, blaze, mace, warden...) | Dùng đúng sound vanilla như YAML |

## Khác biệt / ước lượng (vì Mythic mechanics không có tương đương 1-1)
- `throw{velocity;velocityY}` và `jump`/`leap`: chuyển thành vận tốc vật lý ước lượng (velocity/10). Có thể chỉnh trong `knock()`, `hop()`, `leapToTarget()`.
- `brightness{b=10;s=10}` (model tự phát sáng) chưa làm; mắt không emissive.
- Loot: Mythic dùng `PreventOtherDrops`; mod này cho drop 2–5 gold ingot (sửa ở `data/goblin_boss/loot_table/entities/goblin_boss.json`).
- Boss không tự spawn; dùng spawn egg / lệnh / hoặc thêm vào structure/datapack của bạn.

Model/animation/texture & thiết kế boss thuộc về Amonde – hãy giữ credit.
